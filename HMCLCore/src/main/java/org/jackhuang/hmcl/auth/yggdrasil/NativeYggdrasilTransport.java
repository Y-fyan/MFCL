/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.auth.yggdrasil;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import org.glavo.url.WebURL;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jackhuang.hmcl.util.io.ContentEncoding;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Sends compatible Yggdrasil authentication requests through an isolated Rust process.
@NotNullByDefault
final class NativeYggdrasilTransport {
    /// Maximum decoded authentication response size.
    private static final int MAX_DECODED = 16 * 1024 * 1024;
    /// Maximum raw authentication response size accepted from the backend.
    private static final int MAX_RAW = 4 * 1024 * 1024;

    /// Prevents instantiation.
    private NativeYggdrasilTransport() { }

    /// Performs one request or returns null before any credentials are sent when unsupported.
    /// @param url selected provider endpoint
    /// @param body JSON request body, potentially containing credentials
    /// @return decoded response, or null if the Java path must be used
    /// @throws IOException if a submitted request or its response fails
    /// @throws InterruptedException if interrupted
    static @Nullable String tryPost(WebURL url, String body) throws IOException, InterruptedException {
        @Nullable Configuration config = prepare(url, body, "yggdrasil_request");
        if (config == null) return null;
        JsonObject params = new JsonObject(); params.addProperty("method", "POST"); params.addProperty("body", body);
        @Nullable JsonObject response = submit(config, params);
        return response == null ? null : decode(response);
    }

    /// Attempts a read-only profile request, returning null for redirects, failures or unsupported settings.
    /// @param url profile endpoint
    /// @return response text, or null for the existing Java GET path
    /// @throws InterruptedException if interrupted
    static @Nullable String tryGet(WebURL url) throws InterruptedException {
        return tryGet(url, "mfcl.native.yggdrasil.enabled");
    }

    /// Attempts the same read-only transport under the caller's feature switch.
    /// @param url profile endpoint
    /// @param feature native feature property name
    /// @return response text, or null for Java handling
    /// @throws InterruptedException if interrupted
    static @Nullable String tryGet(WebURL url, String feature) throws InterruptedException {
        @Nullable Configuration config = prepare(url, null, "yggdrasil_get", feature);
        if (config == null) return null;
        try {
            JsonObject params = new JsonObject(); params.addProperty("method", "GET");
            @Nullable JsonObject response = submit(config, params);
            if (response == null) return null;
            int status = status(response);
            if (status >= 300 && status <= 308 && status != 304 && status != 306) return null;
            return decode(response);
        } catch (IOException | RuntimeException e) {
            // Profile GET is read-only, so the existing Java path can safely retry it.
            LOG.debug("Native Yggdrasil profile read unavailable; using Java transport");
            return null;
        }
    }

    /// Reads authlib-injector metadata with the legacy HTTP status and UTF-8 decoding rules.
    static @Nullable String tryGetAuthlibMetadata(WebURL url) throws InterruptedException {
        @Nullable Configuration config = prepare(url, null, "yggdrasil_get");
        if (config == null) return null;
        try {
            JsonObject params = new JsonObject(); params.addProperty("method", "GET");
            @Nullable JsonObject response = submit(config, params);
            if (response == null || status(response) / 100 != 2) return null;
            JsonElement body = response.get("body_base64");
            if (body == null || !body.isJsonPrimitive() || !body.getAsJsonPrimitive().isString()) return null;
            byte[] bytes = Base64.getDecoder().decode(body.getAsString());
            if (bytes.length > MAX_RAW) return null;
            @Nullable String encoding = optionalString(response, "content_encoding");
            try (InputStream raw = new ByteArrayInputStream(bytes);
                 InputStream decoded = "gzip".equals(encoding) ? new java.util.zip.GZIPInputStream(raw) : raw) {
                byte[] content = decoded.readNBytes(MAX_DECODED + 1);
                if (content.length > MAX_DECODED) return null;
                return new String(content, StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            // A read-only GET can safely fall back to the established Java path.
            return null;
        }
    }

    /// Reads only direct metadata responses without an authlib discovery redirect.
    static @Nullable String tryLocateAuthlib(WebURL url) throws InterruptedException {
        @Nullable Configuration config = prepare(url, null, "authlib_locate_header_v1");
        if (config == null) return null;
        try {
            JsonObject params = new JsonObject(); params.addProperty("method", "GET");
            @Nullable JsonObject response = submit(config, params);
            if (response == null || status(response) / 100 != 2 || !response.has("authlib_location")
                    || !response.get("authlib_location").isJsonNull()) return null;
            return decode(response);
        } catch (IOException | RuntimeException e) { return null; }
    }

    /// Uploads a supported skin once, or returns null before submission when Java must handle it.
    /// @param url provider upload endpoint
    /// @param token access token, or null
    /// @param slim whether the slim model is selected
    /// @param file selected skin file
    /// @return response text, or null before submission if unsupported
    /// @throws IOException if the submitted upload fails
    /// @throws InterruptedException if interrupted
    static @Nullable String tryUpload(WebURL url, @Nullable String token, boolean slim, Path file) throws IOException, InterruptedException {
        if (token == null || token.length() > 65_536 || !asciiHeader(token)) return null;
        String filename;
        String path;
        String contentType;
        try {
            if (file.getFileSystem() != FileSystems.getDefault() || !Files.isRegularFile(file) || Files.size(file) > 8L * 1024 * 1024) return null;
            Path absolute = file.toAbsolutePath();
            filename = FileUtils.getName(file); path = absolute.toString(); contentType = "image/" + FileUtils.getExtension(file);
            if (filename.isEmpty() || filename.indexOf('"') >= 0 || filename.indexOf('\r') >= 0 || filename.indexOf('\n') >= 0
                    || filename.indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(filename)
                    || filename.getBytes(StandardCharsets.UTF_8).length > 4096 || !StandardCharsets.UTF_8.newEncoder().canEncode(path)
                    || !Path.of(path).equals(absolute) || !asciiHeader(contentType)) return null;
        } catch (IOException | RuntimeException e) { return null; }
        @Nullable Configuration config = prepare(url, null, "yggdrasil_skin_upload");
        if (config == null) return null;
        JsonObject params = new JsonObject(); params.addProperty("method", "PUT"); params.addProperty("access_token", token);
        params.addProperty("path", path); params.addProperty("filename", filename); params.addProperty("file_content_type", contentType);
        params.addProperty("slim", slim);
        @Nullable JsonObject response = submit(config, params);
        return response == null ? null : decode(response);
    }

    /// Sends a bounded request after all compatibility checks have completed.
    /// @param config verified native configuration
    /// @param params operation-specific parameters
    /// @return response envelope, or null when the request is too large to submit
    /// @throws IOException on submitted request failure
    /// @throws InterruptedException if interrupted
    private static @Nullable JsonObject submit(Configuration config, JsonObject params) throws IOException, InterruptedException {
        params.addProperty("url", config.url()); params.addProperty("user_agent", config.userAgent()); params.addProperty("language", config.language());
        JsonObject envelope = new JsonObject(); envelope.addProperty("version", 1); envelope.addProperty("id", 1);
        envelope.addProperty("method", "yggdrasil_request"); envelope.add("params", params);
        if ((envelope + "\n").getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) return null;
        return NativeBackend.yggdrasilRequest(config.executable(), params);
    }

    /// Validates the status of a native response.
    /// @param response response envelope
    /// @return HTTP status
    /// @throws IOException if invalid
    private static int status(JsonObject response) throws IOException {
        try {
            int status = response.get("status").getAsInt();
            if (status < 100 || status > 599) throw new IOException("Invalid native authentication response");
            return status;
        } catch (RuntimeException e) { throw new IOException("Invalid native authentication response"); }
    }

    /// Decodes bounded response bytes with the existing charset and compression rules.
    /// @param response native response envelope
    /// @return decoded response text
    /// @throws IOException if the response is invalid
    private static String decode(JsonObject response) throws IOException {
        try {
            status(response);
            if (!response.get("body_base64").isJsonPrimitive()
                    || !response.get("body_base64").getAsJsonPrimitive().isString()) throw new IOException("Invalid native authentication response");
            byte[] bytes = Base64.getDecoder().decode(response.get("body_base64").getAsString());
            if (bytes.length > MAX_RAW) throw new IOException("Native authentication response is too large");
            @Nullable String contentType = optionalString(response, "content_type");
            @Nullable String contentEncoding = optionalString(response, "content_encoding");
            HttpHeaders headers = HttpHeaders.of(contentEncoding == null ? Map.of() : Map.of("content-encoding", List.of(contentEncoding)), (name, value) -> true);
            Charset charset = NetworkUtils.getCharsetFromContentType(contentType);
            try (InputStream input = ContentEncoding.fromHeaders(headers).wrap(new ByteArrayInputStream(bytes))) {
                byte[] decoded = input.readNBytes(MAX_DECODED + 1);
                if (decoded.length > MAX_DECODED) throw new IOException("Native authentication response is too large");
                return new String(decoded, charset);
            }
        } catch (RuntimeException e) { throw new IOException("Invalid native authentication response"); }
    }

    /// Captures only the preflight values needed for the native request.
    /// @param executable native backend path
    /// @param url provider endpoint
    /// @param userAgent existing launcher user agent
    /// @param language existing launcher language header
    @NotNullByDefault
    private record Configuration(Path executable, String url, String userAgent, String language) { }

    /// Selects compatible direct endpoints and probes capabilities without submitting credentials.
    /// @param url selected endpoint
    /// @param body JSON request body, or null for GET and upload
    /// @param capability native operation capability required before submission
    /// @return configuration, or null for Java handling
    /// @throws InterruptedException if interrupted
    private static @Nullable Configuration prepare(WebURL url, @Nullable String body, String capability) throws InterruptedException {
        return prepare(url, body, capability, "mfcl.native.yggdrasil.enabled");
    }

    /// Performs transport preflight using the feature switch of the requesting account type.
    /// @param url selected endpoint
    /// @param body request body, or null
    /// @param capability required native capability
    /// @param feature native feature property name
    /// @return verified configuration, or null for Java handling
    /// @throws InterruptedException if interrupted
    private static @Nullable Configuration prepare(WebURL url, @Nullable String body, String capability, String feature) throws InterruptedException {
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank()
                || !Boolean.parseBoolean(System.getProperty(feature, "true"))) return null;
        try {
            URI uri = url.toURL().toURI();
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null) return null;
            String endpoint = url.toString();
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(endpoint) || endpoint.getBytes(StandardCharsets.UTF_8).length > 65_536) return null;
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            if (!https && !"http".equalsIgnoreCase(uri.getScheme())) return null;
            if (body != null && (!StandardCharsets.UTF_8.newEncoder().canEncode(body) || body.getBytes(StandardCharsets.UTF_8).length > 768 * 1024)) return null;
            if (java.net.Authenticator.getDefault() != null || java.net.CookieHandler.getDefault() != null
                    || java.net.ResponseCache.getDefault() != null) return null;
            if (https && (System.getProperty("javax.net.ssl.trustStore") != null
                    || !javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory().getClass().getName().equals("sun.security.ssl.SSLSocketFactoryImpl")
                    || !javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().getClass().getName().equals("javax.net.ssl.HttpsURLConnection$DefaultHostnameVerifier"))) return null;
            @Nullable ProxySelector selector = ProxySelector.getDefault();
            if (selector != null) {
                @Nullable List<Proxy> proxies = selector.select(uri);
                if (proxies == null || proxies.size() != 1 || proxies.get(0).type() != Proxy.Type.DIRECT) return null;
            }
            HttpURLConnection connection = NetworkUtils.createHttpConnection(url);
            @Nullable String userAgent;
            @Nullable String language;
            try {
                if (connection.getRequestProperty("x-api-key") != null) return null;
                userAgent = connection.getRequestProperty("User-Agent");
                language = connection.getRequestProperty("Accept-Language");
            } finally { connection.disconnect(); }
            if (userAgent == null || language == null || !asciiHeader(userAgent) || !asciiHeader(language)) return null;
            Path path = Path.of(executable);
            if (!NativeBackend.supportsCapabilities(path, capability)) return null;
            return new Configuration(path, endpoint, userAgent, language);
        } catch (IOException | RuntimeException | java.net.URISyntaxException e) {
            LOG.debug("Native Yggdrasil preflight unavailable; using Java transport");
            return null;
        }
    }

    /// Restricts request headers to safe ASCII shared by the Java and Rust clients.
    /// @param value header value
    /// @return whether the value is safe for native transport
    private static boolean asciiHeader(String value) {
        return value.length() <= 4096 && value.chars().allMatch(ch -> ch >= 0x20 && ch <= 0x7e);
    }

    /// Reads an optional string from a native response.
    /// @param object response object
    /// @param key response field name
    /// @return field value, or null
    /// @throws IOException if the field is not null or a string
    private static @Nullable String optionalString(JsonObject object, String key) throws IOException {
        if (!object.has(key)) throw new IOException("Invalid native authentication response");
        if (object.get(key).isJsonNull()) return null;
        if (!object.get(key).isJsonPrimitive() || !object.get(key).getAsJsonPrimitive().isString()) throw new IOException("Invalid native authentication response");
        return object.get(key).getAsString();
    }
}
