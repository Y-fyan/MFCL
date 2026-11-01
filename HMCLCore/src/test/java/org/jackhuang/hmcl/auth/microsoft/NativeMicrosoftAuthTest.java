/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.auth.microsoft;

import org.jackhuang.hmcl.auth.CredentialExpiredException;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import org.jackhuang.hmcl.auth.OAuth;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Tests native Microsoft orchestration with a local fake executable and callback.
@NotNullByDefault
@EnabledOnOs({OS.LINUX, OS.MAC})
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("proxy-selector")
public final class NativeMicrosoftAuthTest {
    /// Test-owned temporary directory.
    @TempDir Path tempDir;
    /// Original backend path.
    private final @Nullable String previousPath = System.getProperty("mfcl.native.path");
    /// Original Microsoft backend setting.
    private final @Nullable String previousEnabled = System.getProperty("mfcl.native.microsoft.enabled");

    /// Previous process proxy selector.
    private final @Nullable ProxySelector previousProxy = ProxySelector.getDefault();

    /// Uses direct connections for isolated authentication fixtures.
    @BeforeEach void configureDirectProxy() { ProxySelector.setDefault(selector(List.of(Proxy.NO_PROXY))); }

    /// Restores global settings after each test.
    @AfterEach void restore() {
        ProxySelector.setDefault(previousProxy);
        if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
        if (previousEnabled == null) System.clearProperty("mfcl.native.microsoft.enabled"); else System.setProperty("mfcl.native.microsoft.enabled", previousEnabled);
    }

    /// Device authorization handles a pending response and returns a Minecraft session.
    @Test void deviceLoginUsesNativeBackend() throws Exception {
        Path executable = backend(false);
        System.setProperty("mfcl.native.path", executable.toString());
        System.clearProperty("mfcl.native.microsoft.enabled");
        Callback callback = new Callback();
        MicrosoftSession session = new MicrosoftService(callback).authenticate(OAuth.GrantFlow.DEVICE);
        assertEquals("ABCD", callback.code);
        assertEquals("https://microsoft.com/devicelogin", callback.url);
        assertTrue(callback.completed);
        assertEquals("game-token", session.accessToken());
        assertEquals("refresh-token", session.refreshToken());
        assertEquals("Steve", session.profile().name());
        assertEquals(UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"), session.profile().id());
        assertTrue(Files.exists(Path.of(executable + ".polled")));
    }

    /// Browser authorization uses the native PKCE flow and closes the callback session.
    @Test void authorizationCodeUsesNativeBackend() throws Exception {
        Path executable = backend(false);
        System.setProperty("mfcl.native.path", executable.toString());
        System.clearProperty("mfcl.native.microsoft.enabled");
        Callback callback = new Callback();
        MicrosoftSession session = new MicrosoftService(callback).authenticate(OAuth.GrantFlow.AUTHORIZATION_CODE);
        assertEquals(OAuth.GrantFlow.AUTHORIZATION_CODE, callback.flow);
        assertEquals("https://login.live.com/oauth20_authorize.srf?state=fixture-state", callback.url);
        assertFalse(callback.url.contains("dBjftJeZ4CVP"));
        assertTrue(java.util.Objects.requireNonNull(callback.session).closed);
        assertTrue(Files.exists(Path.of(executable + ".authorization-token")));
        assertEquals("game-token", session.accessToken());
        assertEquals("refresh-token", session.refreshToken());
    }

    /// Configured HTTP proxies keep authentication on the existing Java network path.
    @Test void configuredProxyDoesNotSelectNativeBackend() throws Exception {
        Path executable = backend(false);
        System.setProperty("mfcl.native.path", executable.toString());
        ProxySelector.setDefault(selector(List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 1234)))));
        Callback callback = new Callback();
        MicrosoftService service = new MicrosoftService(callback);
        assertNull(service.configuredNativeAuth("microsoft_exchange"));
        assertFalse(Files.exists(Path.of(executable + ".health")));
    }

    /// An older backend lacking required operations is not selected before authorization.
    @Test void missingCapabilityUsesJavaPath() throws Exception {
        Path executable = tempDir.resolve("old-backend");
        Files.writeString(executable, "#!/bin/sh\nread line\nprintf '%s\\n' '{\"version\":1,\"id\":1,\"result\":{\"name\":\"mfcl-native\",\"protocol\":1,\"capabilities\":[]}}'\n");
        assertTrue(executable.toFile().setExecutable(true));
        System.setProperty("mfcl.native.path", executable.toString());
        assertNull(new MicrosoftService(new Callback()).configuredNativeAuth("microsoft_device_begin", "microsoft_exchange"));
    }

    /// Creates a deterministic proxy selector without contacting a proxy.
    ///
    /// @param proxies proxy selection result
    /// @return selector returning the supplied proxies
    private static ProxySelector selector(List<Proxy> proxies) {
        return new ProxySelector() {
            /// Returns the configured fixture proxy list.
            @Override public List<Proxy> select(URI uri) { return proxies; }
            /// Ignores fixture connection errors.
            @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
        };
    }

    /// Runs Java callback, actual Rust PKCE and exchange code against local synthetic services.
    @Test void authorizationCodeUsesActualRustWithLocalServices() throws Exception {
        @Nullable String nativeBinary = System.getenv("MFCL_NATIVE_AUTH_TEST_EXECUTABLE");
        assumeTrue(nativeBinary != null && !nativeBinary.isBlank());
        Map<String, String> requests = new ConcurrentHashMap<>();
        Map<String, String> authorization = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, String> replies = Map.of(
                "/code-token", "{\"access_token\":\"live-token\",\"refresh_token\":\"refresh-token\"}",
                "/xbox", "{\"Token\":\"xbox-token\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"user-hash\"}]}}",
                "/xsts", "{\"Token\":\"xsts-token\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"user-hash\"}]}}",
                "/login", "{\"access_token\":\"game-token\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"username\":\"xbox-user\"}",
                "/entitlements", "{\"items\":[{\"name\":\"game_minecraft\"}]}",
                "/profile", "{\"id\":\"0123456789abcdef0123456789abcdef\",\"name\":\"Steve\"}");
        for (Map.Entry<String, String> entry : replies.entrySet()) {
            server.createContext(entry.getKey(), exchange -> respond(exchange, entry.getValue(), requests, authorization));
        }
        server.start();
        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            Path wrapper = tempDir.resolve("native-wrapper");
            String quotedBinary = "'" + nativeBinary.replace("'", "'\\''") + "'";
            Files.writeString(wrapper, "#!/bin/sh\nMFCL_AUTH_TEST_BASE='" + origin + "' exec " + quotedBinary + " \"$@\"\n");
            assertTrue(wrapper.toFile().setExecutable(true));
            System.setProperty("mfcl.native.path", wrapper.toString());
            System.clearProperty("mfcl.native.microsoft.enabled");
            Callback callback = new Callback();
            MicrosoftSession session = new MicrosoftService(callback).authenticate(OAuth.GrantFlow.AUTHORIZATION_CODE);
            assertEquals("game-token", session.accessToken()); assertEquals("refresh-token", session.refreshToken());
            assertEquals("Steve", session.profile().name()); assertTrue(java.util.Objects.requireNonNull(callback.session).closed);
            assertTrue(callback.url.startsWith("https://login.live.com/oauth20_authorize.srf?"));
            assertTrue(callback.url.contains("code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"));
            assertFalse(callback.url.contains("dBjftJeZ4CVP"));
            assertTrue(requests.get("/code-token").contains("code=auth-code-secret"));
            assertTrue(requests.get("/code-token").contains("code_verifier=dBjftJeZ4CVP"));
            assertTrue(requests.get("/xbox").contains("d=live-token"));
            assertTrue(requests.get("/xsts").contains("xbox-token"));
            assertTrue(requests.get("/login").contains("XBL3.0 x=user-hash;xsts-token"));
            assertEquals("Bearer game-token", authorization.get("/entitlements"));
            assertEquals("Bearer game-token", authorization.get("/profile"));
        } finally { server.stop(0); }
    }

    /// Known Xbox authorization failures reach the launcher's existing actionable error types.
    @Test void actualRustMapsXboxAuthorizationFailures() throws Exception {
        @Nullable String binary = System.getenv("MFCL_NATIVE_AUTH_TEST_EXECUTABLE");
        assumeTrue(binary != null && !binary.isBlank());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var status = new java.util.concurrent.atomic.AtomicInteger(401);
        var code = new java.util.concurrent.atomic.AtomicLong(2148916238L);
        server.createContext("/code-token", exchange -> respond(exchange,
                "{\"access_token\":\"live-token\",\"refresh_token\":\"refresh-token\"}", new ConcurrentHashMap<>(), new ConcurrentHashMap<>()));
        server.createContext("/xbox", exchange -> respond(exchange,
                "{\"Token\":\"xbox-token\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"user-hash\"}]}}", new ConcurrentHashMap<>(), new ConcurrentHashMap<>()));
        server.createContext("/xsts", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = (status.get() == 400 ? "not-json" : "{\"XErr\":" + code.get()
                    + ",\"Redirect\":\"https://example.test/xbox-help\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length); try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            Path wrapper = tempDir.resolve("native-xbox-errors");
            String quoted = "'" + binary.replace("'", "'\\''") + "'";
            Files.writeString(wrapper, "#!/bin/sh\nMFCL_AUTH_TEST_BASE='http://127.0.0.1:" + server.getAddress().getPort() + "' exec " + quoted + " \"$@\"\n");
            assertTrue(wrapper.toFile().setExecutable(true));
            long[] codes = {2148916227L, 2148916233L, 2148916235L, 2148916238L, 2148999999L};
            for (long value : codes) {
                code.set(value);
                var error = assertThrows(MicrosoftService.XboxAuthorizationException.class,
                        () -> new NativeMicrosoftAuth(wrapper, new Callback()).authenticateAuthorizationCode());
                assertEquals(value, error.getErrorCode());
                assertEquals("https://example.test/xbox-help", error.getRedirect());
            }
            status.set(400);
            assertThrows(MicrosoftService.XBox400Exception.class,
                    () -> new NativeMicrosoftAuth(wrapper, new Callback()).authenticateAuthorizationCode());
        } finally { server.stop(0); }
    }

    /// Records a local fixture request and returns synthetic JSON.
    ///
    /// @param exchange local HTTP exchange
    /// @param body synthetic response JSON
    /// @param requests received request bodies
    /// @param authorization received authorization headers
    /// @throws IOException on fixture I/O failure
    private static void respond(HttpExchange exchange, String body, Map<String, String> requests,
                                Map<String, String> authorization) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requests.put(path, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        @Nullable String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header != null) authorization.put(path, header);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    /// A consumed or expired code is not retried through the Java authorization path.
    @Test void invalidAuthorizationCodeDoesNotRestartFlow() throws Exception {
        Path executable = tempDir.resolve("expired-backend");
        Files.writeString(executable, "#!/bin/sh\nread line\ncase \"$line\" in\n"
                + "*health*) : > \"$0.health\"; printf '%s\\n' '{\"version\":1,\"id\":1,\"result\":{\"name\":\"mfcl-native\",\"protocol\":1,\"capabilities\":[\"microsoft_authorization_url\",\"microsoft_authorization_token\",\"microsoft_exchange\",\"microsoft_device_begin\",\"microsoft_device_poll\",\"microsoft_refresh\"]}}' ;;\n"
                + "*microsoft_authorization_url*) printf '%s\\n' '{\"version\":1,\"id\":1,\"result\":{\"authorization_url\":\"https://login.live.com/oauth20_authorize.srf?state=fixture-state\"}}' ;;\n"
                + "*microsoft_authorization_token*) printf '%s\\n' '{\"version\":1,\"id\":1,\"error\":{\"code\":\"invalid_grant\"}}' ;;\n"
                + "*) exit 1 ;;\nesac\n");
        assertTrue(executable.toFile().setExecutable(true));
        System.setProperty("mfcl.native.path", executable.toString());
        Callback callback = new Callback();
        assertThrows(CredentialExpiredException.class,
                () -> new MicrosoftService(callback).authenticate(OAuth.GrantFlow.AUTHORIZATION_CODE));
        assertEquals(1, callback.serverStarts); assertEquals(1, callback.browserOpens);
        assertTrue(java.util.Objects.requireNonNull(callback.session).closed);
    }

    /// An invalid native browser URL is rejected before it can be opened.
    @Test void authorizationRejectsUnexpectedBrowserUrl() throws Exception {
        Path executable = tempDir.resolve("bad-backend");
        Files.writeString(executable, "#!/bin/sh\nread line\ncase \"$line\" in\n"
                + "*health*) : > \"$0.health\"; printf '%s\\n' '{\"version\":1,\"id\":1,\"result\":{\"name\":\"mfcl-native\",\"protocol\":1,\"capabilities\":[\"microsoft_authorization_url\",\"microsoft_authorization_token\",\"microsoft_exchange\",\"microsoft_device_begin\",\"microsoft_device_poll\",\"microsoft_refresh\"]}}' ;;\n"
                + "*microsoft_authorization_url*) printf '%s\\n' '{\"version\":1,\"id\":1,\"result\":{\"authorization_url\":\"https://evil.invalid/\"}}' ;;\n"
                + "*) exit 1 ;;\nesac\n");
        assertTrue(executable.toFile().setExecutable(true));
        System.setProperty("mfcl.native.path", executable.toString());
        Callback callback = new Callback();
        assertThrows(org.jackhuang.hmcl.auth.ServerDisconnectException.class,
                () -> new MicrosoftService(callback).authenticate(OAuth.GrantFlow.AUTHORIZATION_CODE));
        assertEquals("", callback.url);
        assertTrue(java.util.Objects.requireNonNull(callback.session).closed);
    }

    /// Combined authorization errors keep their original types and never replay the one-time code.
    @Test void combinedAuthorizationErrorsAreNotReplayed() throws Exception {
        for (String code : List.of("invalid_grant", "license_not_found", "profile_not_found")) {
            Path executable = combinedFailureBackend("authorization", code); Callback callback = new Callback();
            if ("invalid_grant".equals(code))
                assertThrows(CredentialExpiredException.class, () -> new NativeMicrosoftAuth(executable, callback).authenticateAuthorizationCode());
            else if ("license_not_found".equals(code))
                assertThrows(MicrosoftService.MinecraftJavaEditionLicenseNotFoundException.class, () -> new NativeMicrosoftAuth(executable, callback).authenticateAuthorizationCode());
            else assertThrows(MicrosoftService.MinecraftJavaEditionProfileNotFoundException.class, () -> new NativeMicrosoftAuth(executable, callback).authenticateAuthorizationCode());
            assertEquals(1, callback.browserOpens); assertTrue(java.util.Objects.requireNonNull(callback.session).closed);
            assertEquals(List.of("combined"), Files.readAllLines(Path.of(executable + ".calls")));
            assertFalse(Files.exists(Path.of(executable + ".legacy")));
        }
    }

    /// Combined refresh maps expired credentials and missing profiles without retrying.
    @Test void combinedRefreshErrorsAreNotReplayed() throws Exception {
        MicrosoftSession old = new MicrosoftSession("Bearer", "old-game", 1, "old-refresh", new MicrosoftSession.User("old"),
                new MicrosoftSession.GameProfile(UUID.randomUUID(), "Steve"));
        for (String code : List.of("invalid_grant", "profile_not_found")) {
            Path executable = combinedFailureBackend("refresh", code);
            if ("invalid_grant".equals(code))
                assertThrows(CredentialExpiredException.class, () -> new NativeMicrosoftAuth(executable, new Callback()).refresh(old));
            else assertThrows(MicrosoftService.MinecraftJavaEditionProfileNotFoundException.class, () -> new NativeMicrosoftAuth(executable, new Callback()).refresh(old));
            assertEquals(List.of("combined"), Files.readAllLines(Path.of(executable + ".calls")));
            assertFalse(Files.exists(Path.of(executable + ".legacy")));
        }
    }

    /// Creates a one-shot backend that advertises combined session operations and returns a sanitized error.
    /// @param kind authorization or refresh fixture
    /// @param code native error code
    /// @return executable fixture
    /// @throws IOException if the script cannot be written
    private Path combinedFailureBackend(String kind, String code) throws IOException {
        Path executable = tempDir.resolve("combined-" + kind + "-" + code);
        String script = """
                #!/usr/bin/env python3
                import json, pathlib, sys
                request = json.loads(sys.stdin.readline()); method = request['method']
                if method == 'health':
                    result = {'name':'mfcl-native','protocol':1,'capabilities':['microsoft_authorization_session_v1','microsoft_refresh_session_v1']}
                    frame = {'version':1,'id':request['id'],'result':result}
                elif method == 'microsoft_authorization_url':
                    frame = {'version':1,'id':request['id'],'result':{'authorization_url':'https://login.live.com/oauth20_authorize.srf?state=fixture-state'}}
                elif method in ('microsoft_authorization_session','microsoft_refresh_session'):
                    with open(__file__ + '.calls', 'a') as f: f.write('combined\\n')
                    frame = {'version':1,'id':request['id'],'error':{'code':'ERROR_CODE','message':'fixture'}}
                else:
                    pathlib.Path(__file__ + '.legacy').touch()
                    frame = {'version':1,'id':request['id'],'error':{'code':'unexpected','message':'fixture'}}
                print(json.dumps(frame), flush=True)
                """.replace("ERROR_CODE", code);
        Files.writeString(executable, script); assertTrue(executable.toFile().setExecutable(true)); return executable;
    }

    /// Refresh carries the new refresh token through to the saved session.
    @Test void refreshUsesNativeBackend() throws Exception {
        Path executable = backend(false);
        System.setProperty("mfcl.native.path", executable.toString());
        MicrosoftSession old = new MicrosoftSession("Bearer", "old-game", 1, "old-refresh", new MicrosoftSession.User("old"),
                new MicrosoftSession.GameProfile(UUID.randomUUID(), "Steve"));
        MicrosoftSession result = new MicrosoftService(new Callback()).refresh(old);
        assertEquals("refresh-token", result.refreshToken());
        assertEquals("game-token", result.accessToken());
    }

    /// Invalid refresh credentials map to the existing expiration exception.
    @Test void expiredRefreshIsReported() throws Exception {
        Path executable = backend(true);
        System.setProperty("mfcl.native.path", executable.toString());
        MicrosoftSession old = new MicrosoftSession("Bearer", "old-game", 1, "old-refresh", new MicrosoftSession.User("old"),
                new MicrosoftSession.GameProfile(UUID.randomUUID(), "Steve"));
        assertThrows(CredentialExpiredException.class, () -> new MicrosoftService(new Callback()).refresh(old));
    }

    /// A submitted skin request is never replayed through Java after a native error.
    @Test void skinUploadUsesNativeOnceAndDoesNotRetryFailure() throws Exception {
        Path executable = tempDir.resolve("skin-backend");
        Files.writeString(executable, "#!/bin/sh\nread line\ncase \"$line\" in\n"
                + "*health*) printf '%s\\n' '{\"version\":1,\"id\":1,\"result\":{\"name\":\"mfcl-native\",\"protocol\":1,\"capabilities\":[\"microsoft_upload_skin\"]}}' ;;\n"
                + "*microsoft_upload_skin*) printf '%s\\n' run >> \"$0.calls\"; printf '%s\\n' '{\"version\":1,\"id\":1,\"error\":{\"code\":\"authentication_failed\",\"message\":\"rejected\"}}' ;;\n"
                + "*) exit 1 ;;\nesac\n");
        assertTrue(executable.toFile().setExecutable(true));
        Path skin = tempDir.resolve("skin.png"); Files.writeString(skin, "fixture");
        System.setProperty("mfcl.native.path", executable.toString());
        assertThrows(org.jackhuang.hmcl.auth.AuthenticationException.class,
                () -> new MicrosoftService(new Callback()).uploadSkin("test-token", true, skin));
        assertEquals(List.of("run"), Files.readAllLines(Path.of(executable + ".calls")));
    }

    /// The actual Rust upload accepts the Java bridge request against a local service only.
    @Test void skinUploadUsesRustAgainstLocalService() throws Exception {
        @Nullable String nativeBinary = System.getenv("MFCL_NATIVE_AUTH_TEST_EXECUTABLE");
        assumeTrue(nativeBinary != null && !nativeBinary.isBlank());
        Map<String, String> requests = new ConcurrentHashMap<>();
        Map<String, String> authorization = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/profile/skins", exchange -> respond(exchange, "{}", requests, authorization));
        server.start();
        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            Path wrapper = tempDir.resolve("skin-native-wrapper");
            Files.writeString(wrapper, "#!/bin/sh\nMFCL_AUTH_TEST_BASE='" + origin + "' exec '"
                    + nativeBinary.replace("'", "'\\''") + "' \"$@\"\n");
            assertTrue(wrapper.toFile().setExecutable(true)); System.setProperty("mfcl.native.path", wrapper.toString());
            Path skin = tempDir.resolve("skin.png"); Files.writeString(skin, "local-fixture");
            new MicrosoftService(new Callback()).uploadSkin("local-token", true, skin);
            assertEquals("Bearer local-token", authorization.get("/profile/skins"));
            assertTrue(requests.get("/profile/skins").contains("local-fixture"));
            assertTrue(requests.get("/profile/skins").contains("\r\n\r\nslim\r\n"));
        } finally { server.stop(0); }
    }

    /// Creates a fake executable that returns deterministic responses and never contacts the network.
    ///
    /// @param expired whether refresh should report an invalid grant
    /// @return executable path
    /// @throws IOException if the fixture cannot be created
    private Path backend(boolean expired) throws IOException {
        Path path = tempDir.resolve("backend");
        String refresh = expired ? "{\"version\":1,\"id\":1,\"error\":{\"code\":\"invalid_grant\",\"message\":\"secret must not escape\"}}"
                : "{\"version\":1,\"id\":1,\"result\":{\"access_token\":\"live-token\",\"refresh_token\":\"refresh-token\"}}";
        String token = "{\"version\":1,\"id\":1,\"result\":{\"access_token\":\"live-token\",\"refresh_token\":\"refresh-token\"}}";
        String script = "#!/bin/sh\nread line\ncase \"$line\" in\n"
                + "*health*) : > \"$0.health\"; printf '%s\\n' '{\"version\":1,\"id\":1,\"result\":{\"name\":\"mfcl-native\",\"protocol\":1,\"capabilities\":[\"microsoft_authorization_url\",\"microsoft_authorization_token\",\"microsoft_exchange\",\"microsoft_device_begin\",\"microsoft_device_poll\",\"microsoft_refresh\"]}}' ;;\n"
                + "*microsoft_authorization_url*) printf '%s\\n' '{\"version\":1,\"id\":1,\"result\":{\"authorization_url\":\"https://login.live.com/oauth20_authorize.srf?state=fixture-state\"}}' ;;\n"
                + "*microsoft_authorization_token*) case \"$line\" in *auth-code-secret*dBjftJeZ4CVP*|*dBjftJeZ4CVP*auth-code-secret*) : > \"$0.authorization-token\"; printf '%s\\n' '" + token + "' ;; *) exit 1 ;; esac ;;\n"
                + "*microsoft_device_begin*) printf '%s\\n' '{\"version\":1,\"id\":1,\"result\":{\"device_code\":\"device-token\",\"user_code\":\"ABCD\",\"verification_uri\":\"https://microsoft.com/devicelogin\",\"expires_in\":30,\"interval\":1}}' ;;\n"
                + "*microsoft_device_poll*) if [ ! -f \"$0.polled\" ]; then : > \"$0.polled\"; printf '%s\\n' '{\"version\":1,\"id\":1,\"error\":{\"code\":\"authorization_pending\"}}'; else printf '%s\\n' '" + token + "'; fi ;;\n"
                + "*microsoft_refresh*) printf '%s\\n' '" + refresh + "' ;;\n"
                + "*microsoft_exchange*) printf '%s\\n' '{\"version\":1,\"id\":1,\"result\":{\"access_token\":\"game-token\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"user_id\":\"xbox-user\",\"profile\":{\"id\":\"0123456789abcdef0123456789abcdef\",\"name\":\"Steve\"}}}' ;;\n"
                + "*) exit 1 ;;\nesac\n";
        Files.writeString(path, script);
        if (!path.toFile().setExecutable(true)) throw new IOException("Cannot mark test executable");
        return path;
    }

    /// Records device authorization events without opening a browser.
    private static final class Callback implements OAuth.Callback {
        /// User code displayed to the caller.
        private String code = "";
        /// URL requested for the device login.
        private String url = "";
        /// Whether authorization completed.
        private boolean completed;
        /// Last browser flow.
        private @Nullable OAuth.GrantFlow flow;
        /// Last synthetic callback session.
        private @Nullable Session session;
        /// Number of callback sessions created.
        private int serverStarts;
        /// Number of browser events emitted.
        private int browserOpens;
        /// Creates a state-validating synthetic callback session.
        @Override public OAuth.Session startServer() { serverStarts++; session = new Session(); return session; }
        /// Records the provided device code.
        @Override public void grantDeviceCode(String userCode, String verificationURI) { code = userCode; }
        /// Records completion.
        @Override public void loginCompletedDeviceCode() { completed = true; }
        /// Records the URL without opening a browser.
        @Override public void openBrowser(OAuth.GrantFlow flow, String url) { browserOpens++; this.flow = flow; this.url = url; }
        /// Provides a synthetic client ID.
        @Override public String getClientId() { return "test-client"; }
    }

    /// Synthetic callback session with deterministic state and a one-time authorization code.
    private static final class Session implements OAuth.Session {
        /// Whether the callback session has been closed.
        private boolean closed;
        /// Returns a synthetic CSRF state.
        @Override public String getState() { return "fixture-state"; }
        /// Returns a valid synthetic PKCE verifier.
        @Override public String getCodeVerifier() { return "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"; }
        /// Returns the synthetic loopback callback URL.
        @Override public String getRedirectURI() { return "http://localhost:29111/auth-response"; }
        /// Returns a synthetic authorization code without network access.
        @Override public String waitFor() { return "auth-code-secret"; }
        /// Records session closure.
        @Override public void close() { closed = true; }
    }
}
