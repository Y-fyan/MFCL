/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.auth.yggdrasil;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.glavo.url.WebURL;
import org.jackhuang.hmcl.auth.ServerDisconnectException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ResponseCache;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Exercises third-party authentication against local synthetic services only.
@NotNullByDefault
@EnabledOnOs({OS.LINUX, OS.MAC})
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("proxy-selector")
final class NativeYggdrasilTransportTest {
    /// Isolated executable wrappers.
    @TempDir Path temp;
    /// Original backend path.
    private final @Nullable String previousPath = System.getProperty("mfcl.native.path");
    /// Original Yggdrasil native setting.
    private final @Nullable String previousEnabled = System.getProperty("mfcl.native.yggdrasil.enabled");
    /// Original Microsoft native setting.
    private final @Nullable String previousMicrosoft = System.getProperty("mfcl.native.microsoft.enabled");
    /// Original JVM proxy selector.
    private final @Nullable ProxySelector previousProxy = ProxySelector.getDefault();
    /// Original HTTP authenticator.
    private final @Nullable Authenticator previousAuthenticator = Authenticator.getDefault();
    /// Original cookie handler.
    private final @Nullable CookieHandler previousCookies = CookieHandler.getDefault();
    /// Original HTTP cache.
    private final @Nullable ResponseCache previousCache = ResponseCache.getDefault();

    /// Uses a clean direct network configuration inside the test JVM.
    @BeforeEach void configure() {
        ProxySelector.setDefault(selector(List.of(Proxy.NO_PROXY)));
        Authenticator.setDefault(null); CookieHandler.setDefault(null); ResponseCache.setDefault(null);
        System.clearProperty("mfcl.native.yggdrasil.enabled");
        System.clearProperty("mfcl.native.microsoft.enabled");
    }

    /// Restores process-wide configuration.
    @AfterEach void restore() {
        ProxySelector.setDefault(previousProxy); Authenticator.setDefault(previousAuthenticator);
        CookieHandler.setDefault(previousCookies); ResponseCache.setDefault(previousCache);
        if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
        if (previousEnabled == null) System.clearProperty("mfcl.native.yggdrasil.enabled"); else System.setProperty("mfcl.native.yggdrasil.enabled", previousEnabled);
        if (previousMicrosoft == null) System.clearProperty("mfcl.native.microsoft.enabled"); else System.setProperty("mfcl.native.microsoft.enabled", previousMicrosoft);
    }

    /// Microsoft public profile lookup uses its own feature switch and the native parser.
    @Test void microsoftPublicProfileUsesNativeReadAndParser() throws Exception {
        Path wrapper = wrapper(); System.setProperty("mfcl.native.path", wrapper.toString());
        System.setProperty("mfcl.native.yggdrasil.enabled", "false");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/profile", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, "{\"id\":\"0123456789abcdef0123456789abcdef\",\"name\":\"Steve\",\"properties\":[{\"name\":\"textures\",\"value\":\"fixture\"}]}", false);
        });
        server.start();
        try {
            WebURL url = WebURL.parse("http://127.0.0.1:" + server.getAddress().getPort() + "/profile");
            @Nullable CompleteGameProfile result = NativeYggdrasilProfile.tryMicrosoftProfile(url);
            assertNotNull(result); assertEquals("fixture", result.getProperties().get("textures"));
            assertTrue(Files.exists(Path.of(wrapper + ".profiles"))); assertEquals(1, requests.get());
            System.setProperty("mfcl.native.microsoft.enabled", "false");
            assertNull(NativeYggdrasilProfile.tryMicrosoftProfile(url)); assertEquals(1, requests.get());
        } finally { server.stop(0); }
    }

    /// Rust handles authentication, refresh, validation, invalidation and gzip without a real account.
    @Test void nativePostFlowMatchesExistingSessionBehavior() throws Exception {
        Path wrapper = wrapper(); System.setProperty("mfcl.native.path", wrapper.toString());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<@Nullable JsonObject> authentication = new AtomicReference<>();
        AtomicReference<@Nullable JsonObject> refreshRequest = new AtomicReference<>();
        AtomicReference<@Nullable String> uploaded = new AtomicReference<>();
        AtomicReference<@Nullable String> uploadAuthorization = new AtomicReference<>();
        AtomicInteger requests = new AtomicInteger(); AtomicBoolean rejectValidation = new AtomicBoolean();
        server.createContext("/authenticate", exchange -> {
            requests.incrementAndGet(); authentication.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            respond(exchange, 200, sessionJson(), true);
        });
        server.createContext("/refresh", exchange -> {
            requests.incrementAndGet(); refreshRequest.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            respond(exchange, 200, sessionJson(), false);
        });
        server.createContext("/validate", exchange -> {
            requests.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            if (rejectValidation.get()) respond(exchange, 403, "{\"error\":\"ForbiddenOperationException\",\"errorMessage\":\"expired\"}", false);
            else respond(exchange, 204, "", false);
        });
        server.createContext("/invalidate", exchange -> { requests.incrementAndGet(); exchange.getRequestBody().readAllBytes(); respond(exchange, 204, "", false); });
        server.createContext("/profile", exchange -> {
            requests.incrementAndGet(); assertEquals("GET", exchange.getRequestMethod());
            respond(exchange, 200, "{\"id\":\"0123456789abcdef0123456789abcdef\",\"name\":\"Steve\",\"properties\":[{\"name\":\"textures\",\"value\":\"fixture\"}]}", false);
        });
        server.createContext("/skin", exchange -> {
            requests.incrementAndGet(); assertEquals("PUT", exchange.getRequestMethod());
            uploadAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            uploaded.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 204, "", false);
        });
        server.start();
        try {
            YggdrasilService service = new YggdrasilService(new Provider("http://127.0.0.1:" + server.getAddress().getPort()));
            YggdrasilSession session = service.authenticate("fixture-user", "密码 🔒", "fixture-client");
            assertEquals("fixture-access", session.getAccessToken()); assertNotNull(session.getSelectedProfile());
            assertEquals("Steve", session.getSelectedProfile().getName());
            JsonObject auth = java.util.Objects.requireNonNull(authentication.get());
            assertEquals("fixture-user", auth.get("username").getAsString()); assertEquals("密码 🔒", auth.get("password").getAsString());
            assertEquals("Minecraft", auth.getAsJsonObject("agent").get("name").getAsString());
            assertEquals("fixture-client", auth.get("clientToken").getAsString()); assertTrue(auth.get("requestUser").getAsBoolean());
            YggdrasilSession refreshed = service.refresh("fixture-access", "fixture-client", session.getSelectedProfile());
            assertEquals(session.getSelectedProfile().getId(), java.util.Objects.requireNonNull(refreshed.getSelectedProfile()).getId());
            JsonObject refresh = java.util.Objects.requireNonNull(refreshRequest.get());
            assertEquals("fixture-access", refresh.get("accessToken").getAsString());
            assertEquals("0123456789abcdef0123456789abcdef", refresh.getAsJsonObject("selectedProfile").get("id").getAsString());
            assertTrue(service.validate("fixture-access", "fixture-client"));
            rejectValidation.set(true); assertFalse(service.validate("fixture-access", "fixture-client"));
            service.invalidate("fixture-access", "fixture-client");
            UUID profileId = java.util.Objects.requireNonNull(session.getSelectedProfile()).getId();
            assertEquals("fixture", service.getCompleteGameProfile(profileId).orElseThrow().getProperties().get("textures"));
            assertTrue(Files.exists(Path.of(wrapper + ".profiles")), "Complete profile did not reach Rust planning");
            Path skin = temp.resolve("皮肤.png"); Files.writeString(skin, "skin-fixture");
            service.uploadSkin(profileId, "fixture-access", true, skin);
            assertEquals("Bearer fixture-access", uploadAuthorization.get());
            String upload = java.util.Objects.requireNonNull(uploaded.get());
            assertTrue(upload.contains("name=\"model\"\r\n\r\nslim"));
            assertTrue(upload.contains("filename=\"皮肤.png\"")); assertTrue(upload.contains("skin-fixture"));
            assertEquals(7, requests.get()); assertEquals(7, Files.readAllLines(Path.of(wrapper + ".calls")).size());
        } finally { server.stop(0); }
    }

    /// A failed submitted request is not silently replayed through Java.
    @Test void failedSubmissionIsNotReplayed() throws Exception {
        Path wrapper = wrapper(); System.setProperty("mfcl.native.path", wrapper.toString());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); AtomicInteger requests = new AtomicInteger();
        server.createContext("/authenticate", exchange -> { requests.incrementAndGet(); exchange.getRequestBody().readAllBytes(); respond(exchange, 503, "", false); });
        server.start();
        try {
            YggdrasilService service = new YggdrasilService(new Provider("http://127.0.0.1:" + server.getAddress().getPort()));
            assertThrows(ServerDisconnectException.class, () -> service.authenticate("fixture", "fixture-password", "fixture-client"));
            assertEquals(1, requests.get()); assertEquals(1, Files.readAllLines(Path.of(wrapper + ".calls")).size());
            System.setProperty("mfcl.native.yggdrasil.enabled", "false");
            assertThrows(ServerDisconnectException.class, () -> service.authenticate("fixture", "fixture-password", "fixture-client"));
            assertEquals(2, requests.get()); assertEquals(1, Files.readAllLines(Path.of(wrapper + ".calls")).size());
        } finally { server.stop(0); }
    }

    /// Redirects do not cause credentials to be submitted to another endpoint.
    @Test void submittedCredentialsAreNotForwardedOnRedirect() throws Exception {
        Path wrapper = wrapper(); System.setProperty("mfcl.native.path", wrapper.toString());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger submitted = new AtomicInteger(), redirected = new AtomicInteger();
        server.createContext("/authenticate", exchange -> {
            submitted.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Location", "/redirected");
            respond(exchange, 307, "{\"error\":\"Redirect\",\"errorMessage\":\"moved\"}", false);
        });
        server.createContext("/skin", exchange -> {
            submitted.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Location", "/redirected");
            respond(exchange, 307, "{\"error\":\"Redirect\",\"errorMessage\":\"moved\"}", false);
        });
        server.createContext("/redirected", exchange -> { redirected.incrementAndGet(); respond(exchange, 200, "{}", false); });
        server.start();
        try {
            YggdrasilService service = new YggdrasilService(new Provider("http://127.0.0.1:" + server.getAddress().getPort()));
            assertThrows(org.jackhuang.hmcl.auth.AuthenticationException.class,
                    () -> service.authenticate("fixture", "fixture-password", "fixture-client"));
            assertEquals(1, submitted.get()); assertEquals(0, redirected.get());
            Path skin = temp.resolve("redirect.png"); Files.writeString(skin, "fixture");
            assertThrows(org.jackhuang.hmcl.auth.AuthenticationException.class,
                    () -> service.uploadSkin(UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"), "fixture-access", false, skin));
            assertEquals(2, submitted.get()); assertEquals(0, redirected.get());
            assertEquals(2, Files.readAllLines(Path.of(wrapper + ".calls")).size());
        } finally { server.stop(0); }
    }

    /// A read-only profile redirect returns to the original Java redirect handling.
    @Test void profileRedirectUsesExistingJavaPath() throws Exception {
        Path wrapper = wrapper(); System.setProperty("mfcl.native.path", wrapper.toString());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger initial = new AtomicInteger(), target = new AtomicInteger();
        server.createContext("/profile", exchange -> {
            initial.incrementAndGet(); exchange.getResponseHeaders().set("Location", "/profile-target");
            respond(exchange, 302, "", false);
        });
        server.createContext("/profile-target", exchange -> {
            target.incrementAndGet();
            respond(exchange, 200, "{\"id\":\"0123456789abcdef0123456789abcdef\",\"name\":\"Steve\",\"properties\":[]}", false);
        });
        server.start();
        try {
            YggdrasilService service = new YggdrasilService(new Provider("http://127.0.0.1:" + server.getAddress().getPort()));
            assertEquals("Steve", service.getCompleteGameProfile(UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")).orElseThrow().getName());
            assertEquals(2, initial.get()); assertEquals(1, target.get());
            assertEquals(1, Files.readAllLines(Path.of(wrapper + ".calls")).size());
        } finally { server.stop(0); }
    }

    /// A failed read-only native profile request can use the existing Java GET path.
    @Test void failedProfileReadFallsBackToJava() throws Exception {
        Path wrapper = wrapper(); System.setProperty("mfcl.native.path", wrapper.toString());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); AtomicInteger requests = new AtomicInteger();
        server.createContext("/profile", exchange -> {
            if (requests.incrementAndGet() == 1) respond(exchange, 503, "", false);
            else respond(exchange, 200, "{\"id\":\"0123456789abcdef0123456789abcdef\",\"name\":\"Steve\",\"properties\":[]}", false);
        });
        server.start();
        try {
            YggdrasilService service = new YggdrasilService(new Provider("http://127.0.0.1:" + server.getAddress().getPort()));
            assertEquals("Steve", service.getCompleteGameProfile(UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")).orElseThrow().getName());
            assertEquals(2, requests.get()); assertEquals(1, Files.readAllLines(Path.of(wrapper + ".calls")).size());
        } finally { server.stop(0); }
    }

    /// A failed skin upload is not repeated through the Java path.
    @Test void failedSkinUploadIsNotReplayed() throws Exception {
        Path wrapper = wrapper(); System.setProperty("mfcl.native.path", wrapper.toString());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); AtomicInteger requests = new AtomicInteger();
        server.createContext("/skin", exchange -> { requests.incrementAndGet(); exchange.getRequestBody().readAllBytes(); respond(exchange, 503, "", false); });
        server.start();
        try {
            YggdrasilService service = new YggdrasilService(new Provider("http://127.0.0.1:" + server.getAddress().getPort()));
            Path skin = temp.resolve("fixture.png"); Files.writeString(skin, "fixture");
            assertThrows(org.jackhuang.hmcl.auth.AuthenticationException.class,
                    () -> service.uploadSkin(UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"), "fixture-access", false, skin));
            assertEquals(1, requests.get()); assertEquals(1, Files.readAllLines(Path.of(wrapper + ".calls")).size());
        } finally { server.stop(0); }
    }

    /// Non-UTF-8 filenames cannot accidentally select a different file for upload.
    @Test @EnabledOnOs(OS.LINUX) void nonUtf8SkinPathsRetainJavaHandling() throws Exception {
        Process generator;
        try {
            generator = new ProcessBuilder("python3", "-c",
                    "import os,sys; p=os.fsencode(sys.argv[1])+b'/skin_\\xff.png'; f=os.open(p,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600); os.write(f,b'fixture'); os.close(f)",
                    temp.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) { assumeTrue(false, "python3 unavailable for non-UTF-8 path fixture"); return; }
        assertTrue(generator.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0, generator.exitValue());
        Path file;
        try (var paths = Files.list(temp)) { file = paths.findFirst().orElseThrow(); }
        assertTrue(Files.isRegularFile(file));
        assertNull(NativeYggdrasilTransport.tryUpload(WebURL.parse("http://127.0.0.1:9/skin"), "fixture-token", true, file));
        var method = org.jackhuang.hmcl.auth.microsoft.MicrosoftService.class.getDeclaredMethod("supportsNativeSkinPath", Path.class);
        method.setAccessible(true); assertEquals(false, method.invoke(null, file));
    }

    /// Proxy-managed endpoints decline native handling before a request is submitted.
    @Test void proxyConfigurationFallsBackBeforeSendingCredentials() throws Exception {
        Path wrapper = wrapper(); System.setProperty("mfcl.native.path", wrapper.toString());
        ProxySelector.setDefault(selector(List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 9)))));
        assertNull(NativeYggdrasilTransport.tryPost(WebURL.parse("https://example.invalid/auth"), "{\"password\":\"fixture\"}"));
        assertFalse(Files.exists(Path.of(wrapper + ".calls")));
    }

    /// Creates a wrapper around the native backend without recording credentials.
    /// @return wrapper path
    /// @throws IOException if the wrapper cannot be created
    private Path wrapper() throws IOException {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        Path wrapper = temp.resolve("backend");
        Files.writeString(wrapper, "#!/bin/sh\nIFS= read -r line\ncase \"$line\" in *yggdrasil_request*) printf '%s\\n' run >> \"$0.calls\" ;; *yggdrasil_plan_profile*) : > \"$0.profiles\" ;; esac\n"
                + "printf '%s\\n' \"$line\" | exec '" + executable.replace("'", "'\\''") + "'\n");
        assertTrue(wrapper.toFile().setExecutable(true)); return wrapper;
    }

    /// Returns a fixture authentication response.
    /// @return response JSON
    private static String sessionJson() {
        return "{\"accessToken\":\"fixture-access\",\"clientToken\":\"fixture-client\",\"selectedProfile\":{\"id\":\"0123456789abcdef0123456789abcdef\",\"name\":\"Steve\"}}";
    }

    /// Sends one synthetic response, optionally compressed.
    /// @param exchange local test request
    /// @param status HTTP status
    /// @param text response text
    /// @param gzip whether to compress the response
    /// @throws IOException on fixture I/O failure
    private static void respond(HttpExchange exchange, int status, String text, boolean gzip) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (gzip) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (GZIPOutputStream stream = new GZIPOutputStream(buffer)) { stream.write(bytes); }
            bytes = buffer.toByteArray(); exchange.getResponseHeaders().set("Content-Encoding", "gzip");
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (status == 204 || bytes.length == 0) { exchange.sendResponseHeaders(status, -1); exchange.close(); return; }
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    /// Creates a fixed proxy selector for a test.
    /// @param proxies selected proxy list
    /// @return fixture selector
    private static ProxySelector selector(List<Proxy> proxies) {
        return new ProxySelector() {
            /// Returns the configured proxies.
            @Override public List<Proxy> select(URI uri) { return proxies; }
            /// Ignores fixture connection failures.
            @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
        };
    }

    /// Supplies local fixture endpoints.
    /// @param base test server origin
    @NotNullByDefault
    private record Provider(String base) implements YggdrasilProvider {
        /// Authentication endpoint.
        @Override public WebURL getAuthenticationURL() { return WebURL.parse(base + "/authenticate"); }
        /// Token refresh endpoint.
        @Override public WebURL getRefreshmentURL() { return WebURL.parse(base + "/refresh"); }
        /// Token validation endpoint.
        @Override public WebURL getValidationURL() { return WebURL.parse(base + "/validate"); }
        /// Token invalidation endpoint.
        @Override public WebURL getInvalidationURL() { return WebURL.parse(base + "/invalidate"); }
        /// Fixture upload endpoint.
        @Override public WebURL getSkinUploadURL(UUID uuid) { return WebURL.parse(base + "/skin"); }
        /// Fixture profile endpoint.
        @Override public WebURL getProfilePropertiesURL(UUID uuid) { return WebURL.parse(base + "/profile"); }
    }
}
