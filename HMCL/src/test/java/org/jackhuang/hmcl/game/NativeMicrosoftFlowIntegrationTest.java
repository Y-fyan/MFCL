/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.game;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jackhuang.hmcl.auth.OAuth;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftService;
import org.jackhuang.hmcl.auth.microsoft.MicrosoftSession;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jackhuang.hmcl.util.Lang;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Exercises Rust OAuth callback, authorization exchange and refresh against a local fixture.
@NotNullByDefault
@EnabledOnOs({OS.LINUX, OS.MAC})
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("proxy-selector")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class NativeMicrosoftFlowIntegrationTest {
    /// Isolated wrapper and fixture data.
    @TempDir Path temp;

    /// Runs the full native flow using only a numeric loopback test endpoint.
    @Test void authorizationAndRefreshUseNativeCallback() throws Exception {
        @Nullable String testBinary = System.getenv("MFCL_NATIVE_AUTH_TEST_EXECUTABLE");
        @Nullable String productionBinary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(testBinary != null && !testBinary.isBlank() && productionBinary != null && !productionBinary.isBlank());
        assertFalse(NativeBackend.supportsCapabilities(Path.of(productionBinary), "auth_test_endpoints"),
                "Production backend must not support test endpoint overrides");
        HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
        AtomicReference<String> codeForm = new AtomicReference<>(""); AtomicInteger devicePolls = new AtomicInteger();
        fixture.createContext("/", exchange -> respond(exchange, requests, codeForm, devicePolls)); fixture.start();
        Path wrapper = temp.resolve("auth-backend");
        Files.writeString(Path.of(wrapper + ".base"), "http://127.0.0.1:" + fixture.getAddress().getPort() + "/");
        Files.writeString(wrapper, """
                #!/usr/bin/env python3
                import json, os, pathlib, subprocess, sys
                os.environ['MFCL_AUTH_TEST_BASE'] = pathlib.Path(__file__ + '.base').read_text()
                if sys.argv[1:] == ['--microsoft-device-session-child']: pathlib.Path(__file__ + '.device').touch()
                target = os.environ['MFCL_NATIVE_AUTH_TEST_EXECUTABLE']
                if sys.argv[1:]: os.execv(target, [target] + sys.argv[1:])
                line = sys.stdin.readline()
                method = json.loads(line).get('method', '')
                if method.startswith('microsoft_'): pathlib.Path(__file__ + '.' + method).touch()
                child = subprocess.run([target], input=line, text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
                sys.stdout.write(child.stdout); sys.stdout.flush(); sys.exit(child.returncode)
                """);
        assertTrue(wrapper.toFile().setExecutable(true));
        @Nullable String oldPath = System.getProperty("mfcl.native.path");
        @Nullable String oldEnabled = System.getProperty("mfcl.native.microsoft.enabled");
        @Nullable ProxySelector previousProxy = ProxySelector.getDefault();
        AtomicReference<@Nullable NativeOAuthSession> callbackSession = new AtomicReference<>();
        AtomicReference<String> expectedVerifier = new AtomicReference<>("");
        try {
            System.setProperty("mfcl.native.path", wrapper.toString()); System.clearProperty("mfcl.native.microsoft.enabled");
            ProxySelector.setDefault(new ProxySelector() {
                /// Always connects directly in the isolated fixture.
                @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                /// No external connection failure handling is needed in the fixture.
                @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
            });
            assertTrue(NativeBackend.supportsCapabilities(wrapper, "auth_test_endpoints"),
                    "Test backend does not enforce the loopback fixture override");
            OAuth.Callback callback = new OAuth.Callback() {
                /// Uses the actual native callback listener with a local test page.
                @Override public OAuth.Session startServer() throws IOException {
                    try {
                        NativeOAuthSession session = NativeOAuthSession.start(wrapper, "127.0.0.1", List.of(0), () -> "fixture page");
                        callbackSession.set(session); expectedVerifier.set(session.getCodeVerifier()); return session;
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                }
                /// Device flow is not used by this test.
                @Override public void grantDeviceCode(String code, String uri) { throw new AssertionError("Unexpected device flow"); }
                /// Device flow is not used by this test.
                @Override public void loginCompletedDeviceCode() { throw new AssertionError("Unexpected device flow"); }
                /// Simulates the browser redirect without contacting Microsoft.
                @Override public void openBrowser(OAuth.GrantFlow flow, String url) throws IOException {
                    assertEquals(OAuth.GrantFlow.AUTHORIZATION_CODE, flow);
                    URI authorize = URI.create(url); assertEquals("login.live.com", authorize.getHost());
                    Map<String, String> query = Lang.mapOf(NetworkUtils.parseQuery(authorize.getRawQuery()));
                    NativeOAuthSession session = java.util.Objects.requireNonNull(callbackSession.get());
                    assertEquals(session.getState(), query.get("state"));
                    try {
                        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                                MessageDigest.getInstance("SHA-256").digest(session.getCodeVerifier().getBytes(StandardCharsets.US_ASCII)));
                        assertEquals(challenge, query.get("code_challenge"));
                    } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
                    assertEquals(session.getRedirectURI(), query.get("redirect_uri"));
                    int port = URI.create(session.getRedirectURI()).getPort();
                    URI redirect = URI.create("http://127.0.0.1:" + port + "/auth-response?code=fixture-code&state=" + session.getState());
                    HttpURLConnection connection = (HttpURLConnection) redirect.toURL().openConnection(Proxy.NO_PROXY);
                    connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
                    try { assertEquals(200, connection.getResponseCode()); connection.getInputStream().readAllBytes(); }
                    finally { connection.disconnect(); }
                }
                /// Supplies a synthetic OAuth client identifier.
                @Override public String getClientId() { return "fixture-client"; }
            };
            MicrosoftService service = new MicrosoftService(callback);
            MicrosoftSession session = service.authenticate(OAuth.GrantFlow.AUTHORIZATION_CODE);
            assertEquals("fixture-game-token", session.accessToken()); assertEquals("fixture-refresh", session.refreshToken());
            assertEquals("Alex", session.profile().name());
            Map<String, String> tokenForm = Lang.mapOf(NetworkUtils.parseQuery(codeForm.get()));
            assertEquals("fixture-code", tokenForm.get("code")); assertEquals(expectedVerifier.get(), tokenForm.get("code_verifier"));
            assertEquals("authorization_code", tokenForm.get("grant_type"));
            MicrosoftSession refreshed = service.refresh(session);
            assertEquals("fixture-game-token", refreshed.accessToken()); assertEquals("fixture-refresh-new", refreshed.refreshToken());
            assertTrue(Files.exists(Path.of(wrapper + ".microsoft_authorization_session")));
            assertTrue(Files.exists(Path.of(wrapper + ".microsoft_refresh_session")));
            assertFalse(Files.exists(Path.of(wrapper + ".microsoft_authorization_token")));
            assertFalse(Files.exists(Path.of(wrapper + ".microsoft_refresh")));
            assertFalse(Files.exists(Path.of(wrapper + ".microsoft_exchange")));
            AtomicReference<String> displayedCode = new AtomicReference<>(""); AtomicInteger completed = new AtomicInteger();
            OAuth.Callback deviceCallback = new OAuth.Callback() {
                /// Device authorization never starts a local browser callback.
                @Override public OAuth.Session startServer() { throw new AssertionError("Unexpected authorization-code flow"); }
                /// Records the user-facing device code.
                @Override public void grantDeviceCode(String code, String uri) { displayedCode.set(code); assertEquals("https://microsoft.com/devicelogin", uri); }
                /// Records that the native poll completed.
                @Override public void loginCompletedDeviceCode() { completed.incrementAndGet(); }
                /// Simulates opening the verification page without contacting it.
                @Override public void openBrowser(OAuth.GrantFlow flow, String url) {
                    assertEquals(OAuth.GrantFlow.DEVICE, flow); assertEquals("https://microsoft.com/devicelogin", url);
                }
                /// Supplies a synthetic OAuth client identifier.
                @Override public String getClientId() { return "fixture-client"; }
            };
            MicrosoftSession deviceSession = new MicrosoftService(deviceCallback).authenticate(OAuth.GrantFlow.DEVICE);
            assertEquals("ABCD-EFGH", displayedCode.get()); assertEquals(1, completed.get());
            assertEquals("fixture-device-refresh", deviceSession.refreshToken()); assertTrue(Files.exists(Path.of(wrapper + ".device")));
            assertEquals(2, devicePolls.get());
            OAuth.Callback failingCallback = new OAuth.Callback() {
                /// Device authorization does not open a callback server.
                @Override public OAuth.Session startServer() { throw new AssertionError("Unexpected authorization-code flow"); }
                /// Accepts the synthetic user code.
                @Override public void grantDeviceCode(String code, String uri) { }
                /// Simulates a UI failure before Xbox/Minecraft exchange begins.
                @Override public void loginCompletedDeviceCode() { throw new IllegalStateException("fixture UI failure"); }
                /// Suppresses the real browser.
                @Override public void openBrowser(OAuth.GrantFlow flow, String url) { }
                /// Supplies a synthetic OAuth client identifier.
                @Override public String getClientId() { return "fixture-client"; }
            };
            assertThrows(IllegalStateException.class, () -> new MicrosoftService(failingCallback).authenticate(OAuth.GrantFlow.DEVICE));
            assertEquals(3, devicePolls.get()); assertEquals(3, requests.get("/xbox").get());
            Process cancelledDevice = new ProcessBuilder(wrapper.toString(), "--microsoft-device-session-child")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                cancelledDevice.getOutputStream().write("{\"client_id\":\"fixture-client\"}\n".getBytes(StandardCharsets.UTF_8));
                cancelledDevice.getOutputStream().flush();
                var reader = new java.io.BufferedReader(new java.io.InputStreamReader(cancelledDevice.getInputStream(), StandardCharsets.UTF_8));
                String first = reader.readLine(); assertNotNull(first);
                var event = com.google.gson.JsonParser.parseString(first).getAsJsonObject();
                assertEquals("device", event.get("event").getAsString()); assertFalse(event.has("device_code"));
                assertFalse(first.contains("fixture-device-secret"));
                cancelledDevice.getOutputStream().write("{\"event\":\"continue\"}\n".getBytes(StandardCharsets.UTF_8));
                cancelledDevice.getOutputStream().flush(); cancelledDevice.getOutputStream().close();
                assertTrue(cancelledDevice.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, cancelledDevice.exitValue());
            } finally { cancelledDevice.destroyForcibly(); }
            assertEquals(3, devicePolls.get());
            assertEquals(1, requests.get("/code-token").get()); assertEquals(4, requests.get("/token").get());
            for (String endpoint : List.of("/xbox", "/xsts", "/login", "/entitlements", "/profile"))
                assertEquals(3, requests.get(endpoint).get(), endpoint);
        } finally {
            fixture.stop(0); ProxySelector.setDefault(previousProxy);
            if (oldPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", oldPath);
            if (oldEnabled == null) System.clearProperty("mfcl.native.microsoft.enabled"); else System.setProperty("mfcl.native.microsoft.enabled", oldEnabled);
        }
    }

    /// Returns synthetic token and profile responses and counts each fixture request.
    /// @param exchange local HTTP exchange
    /// @param requests per-path request counters
    /// @param codeForm captured authorization-code form
    /// @param devicePolls count of synthetic device polling requests
    /// @throws IOException if the local exchange fails
    private static void respond(HttpExchange exchange, Map<String, AtomicInteger> requests, AtomicReference<String> codeForm,
            AtomicInteger devicePolls) throws IOException {
        String path = exchange.getRequestURI().getPath(); requests.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if ("/code-token".equals(path)) codeForm.set(body);
        boolean devicePoll = "/token".equals(path) && body.contains("device_code=");
        boolean pending = devicePoll && devicePolls.incrementAndGet() == 1;
        String response = switch (path) {
            case "/code-token" -> "{\"access_token\":\"fixture-live\",\"refresh_token\":\"fixture-refresh\"}";
            case "/device" -> "{\"device_code\":\"fixture-device-secret\",\"user_code\":\"ABCD-EFGH\",\"verification_uri\":\"https://microsoft.com/devicelogin\",\"expires_in\":30,\"interval\":1}";
            case "/token" -> pending ? "{\"error\":\"authorization_pending\"}" : devicePoll
                    ? "{\"access_token\":\"fixture-device-live\",\"refresh_token\":\"fixture-device-refresh\"}"
                    : "{\"access_token\":\"fixture-live-new\",\"refresh_token\":\"fixture-refresh-new\"}";
            case "/xbox" -> "{\"Token\":\"fixture-xbox\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"fixture-userhash\"}]}}";
            case "/xsts" -> "{\"Token\":\"fixture-xsts\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"fixture-userhash\"}]}}";
            case "/login" -> "{\"access_token\":\"fixture-game-token\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"username\":\"fixture-user\"}";
            case "/entitlements" -> "{\"items\":[]}";
            case "/profile" -> "{\"id\":\"0123456789abcdef0123456789abcdef\",\"name\":\"Alex\"}";
            default -> "{}";
        };
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(pending ? 400 : 200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
