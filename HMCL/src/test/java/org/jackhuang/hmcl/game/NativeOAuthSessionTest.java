/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.game;

import org.jackhuang.hmcl.auth.AuthenticationException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;

import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Tests the native loopback listener without a real account or external network.
@NotNullByDefault
final class NativeOAuthSessionTest {
    /// Starts an isolated native listener on an ephemeral port.
    /// @return callback session
    /// @throws Exception if startup fails
    private static NativeOAuthSession start() throws Exception {
        @Nullable String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"); assumeTrue(binary != null && !binary.isBlank());
        return NativeOAuthSession.start(Path.of(binary), "127.0.0.1", List.of(0), () -> "<html>fixture</html>");
    }

    /// The listener ignores unrelated paths and serves the callback page before returning the code.
    @Test void loopbackRoundTrip() throws Exception {
        try (NativeOAuthSession session = start()) {
            assertTrue(session.getState().matches("[A-Za-z0-9_-]{43}"));
            assertTrue(session.getCodeVerifier().matches("[A-Za-z0-9_-]{86}"));
            int port = URI.create(session.getRedirectURI()).getPort();
            HttpURLConnection unrelated = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/other").toURL().openConnection(Proxy.NO_PROXY);
            unrelated.setConnectTimeout(5000); unrelated.setReadTimeout(5000);
            try { assertEquals(404, unrelated.getResponseCode()); } finally { unrelated.disconnect(); }
            String url = "http://127.0.0.1:" + port + "/auth-response?code=fixture%2Bcode&state=" + session.getState() + "&id_token=fixture-token";
            HttpURLConnection callback = (HttpURLConnection) URI.create(url).toURL().openConnection(Proxy.NO_PROXY);
            callback.setConnectTimeout(5000); callback.setReadTimeout(5000);
            try {
                assertEquals(200, callback.getResponseCode());
                assertEquals("<html>fixture</html>", new String(callback.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } finally { callback.disconnect(); }
            assertEquals("fixture+code", session.waitFor()); assertEquals("fixture-token", session.getIdToken());
        }
    }

    /// A native decision inconsistent with the independently checked state is rejected.
    @Test void inconsistentNativeDecisionIsRejected(@TempDir Path temp) throws Exception {
        Path fake = temp.resolve("incorrect-backend");
        Files.writeString(fake, """
                #!/usr/bin/env python3
                import json, sys
                sys.stdin.readline()
                state = 'A' * 43
                print(json.dumps({'event':'ready','port':29111,'state':state,'verifier':'B' * 86}), flush=True)
                print(json.dumps({'event':'callback','query':'code=fixture&state=' + state,
                    'fields':{'code':'fixture','state':state},'decision':'invalid_state'}), flush=True)
                sys.stdin.readline()
                """);
        assertTrue(fake.toFile().setExecutable(true));
        try (NativeOAuthSession session = NativeOAuthSession.start(fake, "127.0.0.1", List.of(29111), () -> "")) {
            assertTrue(assertThrows(ExecutionException.class, session::waitFor).getCause() instanceof java.io.IOException);
        }
    }

    /// Forged native fields cannot replace a different raw callback state.
    @Test void inconsistentCallbackFieldsAreRejected(@TempDir Path temp) throws Exception {
        Path fake = temp.resolve("forged-backend");
        Files.writeString(fake, """
                #!/usr/bin/env python3
                import json, sys
                sys.stdin.readline()
                state = 'A' * 43
                print(json.dumps({'event':'ready','port':29111,'state':state,'verifier':'B' * 86}), flush=True)
                print(json.dumps({'event':'callback','query':'code=fixture&state=wrong',
                    'fields':{'code':'fixture','state':state},'decision':'ok'}), flush=True)
                sys.stdin.readline()
                """);
        assertTrue(fake.toFile().setExecutable(true));
        try (NativeOAuthSession session = NativeOAuthSession.start(fake, "127.0.0.1", List.of(29111), () -> "")) {
            assertTrue(assertThrows(ExecutionException.class, session::waitFor).getCause() instanceof java.io.IOException);
        }
    }

    /// A mismatched state cannot complete authorization, even though the page is served.
    @Test void invalidStateIsRejected() throws Exception {
        try (NativeOAuthSession session = start()) {
            int port = URI.create(session.getRedirectURI()).getPort();
            HttpURLConnection callback = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/auth-response?code=fixture&state=wrong").toURL().openConnection(Proxy.NO_PROXY);
            callback.setConnectTimeout(5000); callback.setReadTimeout(5000);
            try { assertEquals(200, callback.getResponseCode()); } finally { callback.disconnect(); }
            assertTrue(assertThrows(ExecutionException.class, session::waitFor).getCause() instanceof AuthenticationException);
        }
    }

    /// Duplicate callback keys use the Java-compatible parser for POST callbacks.
    @Test void postCallbackFallsBackForDuplicateKeys() throws Exception {
        try (NativeOAuthSession session = start()) {
            int port = URI.create(session.getRedirectURI()).getPort();
            String url = "http://127.0.0.1:" + port + "/auth-response?code=old&code=new&state=" + session.getState();
            HttpURLConnection callback = (HttpURLConnection) URI.create(url).toURL().openConnection(Proxy.NO_PROXY);
            callback.setConnectTimeout(5000); callback.setReadTimeout(5000); callback.setRequestMethod("POST"); callback.setDoOutput(true);
            try {
                try (var output = callback.getOutputStream()) { output.write("x=y".getBytes(StandardCharsets.UTF_8)); }
                assertEquals(200, callback.getResponseCode());
            } finally { callback.disconnect(); }
            assertEquals("new", session.waitFor());
        }
    }

    /// Closing a pending session completes waiters with the same authentication failure as Java.
    @Test void closingSessionUnblocksWaiters() throws Exception {
        NativeOAuthSession session = start();
        session.close();
        assertTrue(assertThrows(ExecutionException.class, session::waitFor).getCause() instanceof AuthenticationException);
    }

    /// The listener exits if its launcher control stream closes unexpectedly.
    @Test void callbackChildExitsWhenParentPipeCloses() throws Exception {
        @Nullable String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"); assumeTrue(binary != null && !binary.isBlank());
        Process child = new ProcessBuilder(binary, "--oauth-callback-child").redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            child.getOutputStream().write("{\"bind\":\"127.0.0.1\",\"ports\":[0]}\n".getBytes(StandardCharsets.UTF_8));
            child.getOutputStream().flush();
            var input = new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
            String ready = input.readLine(); assertNotNull(ready);
            assertEquals("ready", com.google.gson.JsonParser.parseString(ready).getAsJsonObject().get("event").getAsString());
            child.getOutputStream().close();
            assertTrue(child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0, child.exitValue());
        } finally { child.destroyForcibly(); }
    }

    /// The normal callback factory chooses the native session before opening a browser.
    @Test @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    void callbackFactorySelectsNativeListener() throws Exception {
        @Nullable String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"); assumeTrue(binary != null && !binary.isBlank());
        @Nullable String previousPath = System.getProperty("mfcl.native.path");
        @Nullable String previousEnabled = System.getProperty("mfcl.native.microsoft.enabled");
        @Nullable String previousClient = System.getProperty("hmcl.microsoft.auth.id");
        try {
            System.setProperty("mfcl.native.path", binary); System.clearProperty("mfcl.native.microsoft.enabled");
            System.setProperty("hmcl.microsoft.auth.id", "fixture-client");
            try (var session = new OAuthServer.Factory().startServer()) {
                assertTrue(session instanceof NativeOAuthSession);
                assertTrue(List.of(29111, 29112, 29113, 29114, 29115).contains(URI.create(session.getRedirectURI()).getPort()));
            }
        } finally {
            if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
            if (previousEnabled == null) System.clearProperty("mfcl.native.microsoft.enabled"); else System.setProperty("mfcl.native.microsoft.enabled", previousEnabled);
            if (previousClient == null) System.clearProperty("hmcl.microsoft.auth.id"); else System.setProperty("hmcl.microsoft.auth.id", previousClient);
        }
    }

    /// If native startup fails, the factory falls back before opening a browser.
    @Test @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    void callbackFactoryFallsBackWhenNativeStartupFails(@TempDir Path temp) throws Exception {
        Path binary = temp.resolve("failed-backend");
        Files.writeString(binary, """
                #!/bin/sh
                if [ "$1" = "--oauth-callback-child" ]; then exit 1; fi
                read -r line
                printf '%s\n' '{"version":1,"id":1,"result":{"name":"mfcl-native","protocol":1,"capabilities":["oauth_callback_server_v1"]}}'
                """);
        assertTrue(binary.toFile().setExecutable(true));
        @Nullable String previousPath = System.getProperty("mfcl.native.path");
        @Nullable String previousEnabled = System.getProperty("mfcl.native.microsoft.enabled");
        @Nullable String previousClient = System.getProperty("hmcl.microsoft.auth.id");
        try {
            System.setProperty("mfcl.native.path", binary.toString()); System.clearProperty("mfcl.native.microsoft.enabled");
            System.setProperty("hmcl.microsoft.auth.id", "fixture-client");
            try (var session = new OAuthServer.Factory().startServer()) { assertTrue(session instanceof OAuthServer); }
        } finally {
            if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
            if (previousEnabled == null) System.clearProperty("mfcl.native.microsoft.enabled"); else System.setProperty("mfcl.native.microsoft.enabled", previousEnabled);
            if (previousClient == null) System.clearProperty("hmcl.microsoft.auth.id"); else System.setProperty("hmcl.microsoft.auth.id", previousClient);
        }
    }

    /// A non-loopback bind is rejected before any callback is accepted.
    @Test void refusesNonLoopbackBind() throws Exception {
        @Nullable String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"); assumeTrue(binary != null && !binary.isBlank());
        assertThrows(java.io.IOException.class, () -> NativeOAuthSession.start(Path.of(binary), "0.0.0.0", List.of(0), () -> ""));
    }
}
