/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.auth.microsoft;

import com.google.gson.JsonObject;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ProxySelector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.ArrayList;

/// Uses native read-only profile requests when JVM network policy permits direct access.
@NotNullByDefault
final class NativeMicrosoftProfile {
    /// Microsoft profile endpoint used by the existing account validator.
    private static final String PROFILE = "https://api.minecraftservices.com/minecraft/profile";
    /// Largest accepted native profile response.
    private static final int MAX_BODY = 1024 * 1024;

    /// Prevents instantiation.
    private NativeMicrosoftProfile() { }

    /// Bounded response returned by the native read-only profile request.
    /// @param status HTTP response status
    /// @param body response bytes for HTTP 200, otherwise empty
    /// @param contentType HTTP content type, or null
    @NotNullByDefault
    private record ProfileRead(int status, byte[] body, @Nullable String contentType, @Nullable JsonObject profile) { }

    /// Validates a Minecraft session, or returns null to use the original Java request.
    /// @param tokenType authorization scheme
    /// @param token Minecraft access token
    /// @return validation result, or null when Java must handle the request
    /// @throws InterruptedException when interrupted
    static @Nullable Boolean tryValidate(String tokenType, String token) throws InterruptedException {
        @Nullable ProfileRead response = read(tokenType, token);
        if (response == null || response.status() == 404 || (response.status() >= 300 && response.status() < 400)) return null;
        if (response.status() != 200) return false;
        if (response.profile() != null && StandardCharsets.UTF_8.equals(NetworkUtils.getCharsetFromContentType(response.contentType()))) {
            if (projectedProfile(response.profile()) != null) return true;
        }
        String body = new String(response.body(), NetworkUtils.getCharsetFromContentType(response.contentType()));
        JsonUtils.fromNonNullJson(body, MicrosoftService.MinecraftProfileResponse.class);
        return true;
    }

    /// Reads a complete profile using the UTF-8 semantics of the existing HTTP JSON helper.
    /// @param authorization complete authorization header, or null
    /// @return parsed profile, or null when Java must handle the request
    /// @throws InterruptedException when interrupted
    static @Nullable MicrosoftService.MinecraftProfileResponse tryCompleteProfile(@Nullable String authorization) throws InterruptedException {
        if (authorization == null) return null;
        int separator = authorization.indexOf(' ');
        if (separator <= 0 || separator == authorization.length() - 1) return null;
        @Nullable ProfileRead response = read(authorization.substring(0, separator), authorization.substring(separator + 1));
        if (response == null || response.status() != 200) return null;
        if (response.profile() != null) {
            @Nullable MicrosoftService.MinecraftProfileResponse profile = projectedProfile(response.profile());
            if (profile != null) return profile;
        }
        return JsonUtils.fromNonNullJson(new String(response.body(), StandardCharsets.UTF_8), MicrosoftService.MinecraftProfileResponse.class);
    }

    /// Builds Java's profile objects from a validated native projection.
    private static @Nullable MicrosoftService.MinecraftProfileResponse projectedProfile(JsonObject object) {
        try {
            var profile = new MicrosoftService.MinecraftProfileResponse();
            profile.id = org.glavo.uuid.UUIDs.parse(object.get("id").getAsString());
            profile.name = object.get("name").getAsString();
            profile.skins = new ArrayList<>(); profile.capes = new ArrayList<>();
            for (var element : object.getAsJsonArray("skins")) {
                JsonObject skinObject = element.getAsJsonObject();
                var skin = new MicrosoftService.MinecraftProfileResponseSkin();
                skin.id = skinObject.get("id").getAsString(); skin.state = skinObject.get("state").getAsString();
                skin.url = skinObject.get("url").getAsString(); skin.variant = skinObject.get("variant").getAsString();
                var alias = skinObject.get("alias"); skin.alias = alias.isJsonNull() ? null : alias.getAsString();
                profile.skins.add(skin);
            }
            for (var cape : object.getAsJsonArray("capes")) {
                if (cape.isJsonNull()) profile.capes.add(null);
                else { cape.getAsJsonObject(); profile.capes.add(new MicrosoftService.MinecraftProfileResponseCape()); }
            }
            return profile;
        } catch (RuntimeException e) { return null; }
    }

    /// Sends a compatible bounded profile request, returning null for Java fallback.
    /// @param tokenType authorization scheme
    /// @param token Minecraft access token
    /// @return native response, or null when unsupported
    /// @throws InterruptedException when interrupted
    private static @Nullable ProfileRead read(String tokenType, String token) throws InterruptedException {
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank()
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.microsoft.enabled", "true"))
                || !credential(tokenType) || !credential(token)) return null;
        try {
            if (!MicrosoftService.usesDirectConnections(ProxySelector.getDefault())
                    || java.net.Authenticator.getDefault() != null || java.net.CookieHandler.getDefault() != null
                    || java.net.ResponseCache.getDefault() != null || System.getProperty("javax.net.ssl.trustStore") != null
                    || !javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory().getClass().getName().equals("sun.security.ssl.SSLSocketFactoryImpl")
                    || !javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().getClass().getName().equals("javax.net.ssl.HttpsURLConnection$DefaultHostnameVerifier")) return null;
            HttpURLConnection connection = NetworkUtils.createHttpConnection(PROFILE);
            @Nullable String userAgent; @Nullable String language;
            try {
                if (connection.getRequestProperty("x-api-key") != null) return null;
                userAgent = connection.getRequestProperty("User-Agent"); language = connection.getRequestProperty("Accept-Language");
            } finally { connection.disconnect(); }
            if (userAgent == null || language == null || !header(userAgent) || !header(language)) return null;
            Path path = Path.of(executable);
            if (!NativeBackend.supportsCapabilities(path, "microsoft_profile_read")) return null;
            Logger.registerAccessToken(token);
            JsonObject params = new JsonObject(); params.addProperty("token_type", tokenType); params.addProperty("access_token", token);
            params.addProperty("user_agent", userAgent); params.addProperty("language", language);
            JsonObject result = NativeBackend.microsoft(path, "microsoft_profile_read", params);
            int status = result.get("status").getAsInt();
            if (status < 100 || status > 599) return null;
            if (status != 200) return new ProfileRead(status, new byte[0], null, null);
            if (!result.get("body_base64").isJsonPrimitive() || !result.get("body_base64").getAsJsonPrimitive().isString()) return null;
            byte[] bytes = Base64.getDecoder().decode(result.get("body_base64").getAsString());
            if (bytes.length > MAX_BODY) return null;
            @Nullable String contentType = result.get("content_type").isJsonNull() ? null : result.get("content_type").getAsString();
            @Nullable JsonObject profile = result.has("profile_v1") && result.get("profile_v1").isJsonObject()
                    ? result.getAsJsonObject("profile_v1") : null;
            return new ProfileRead(status, bytes, contentType, profile);
        } catch (IOException | RuntimeException e) {
            // This is a read-only request, so the original Java path can safely retry it.
            return null;
        }
    }

    /// Checks a header can be sent unchanged through the native protocol.
    /// @param value header value
    /// @return whether it is nonempty, bounded ASCII without control characters
    private static boolean header(String value) {
        return !value.isEmpty() && value.length() <= 65_536
                && StandardCharsets.US_ASCII.newEncoder().canEncode(value)
                && value.chars().allMatch(c -> c >= 0x20 && c <= 0x7e);
    }

    /// Restricts credentials to unambiguous printable ASCII without whitespace.
    /// @param value authorization scheme or token
    /// @return whether native transport preserves the value exactly
    private static boolean credential(String value) {
        return header(value) && value.chars().allMatch(c -> c > 0x20);
    }
}
