/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.auth.authlibinjector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
@org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.LINUX, org.junit.jupiter.api.condition.OS.MAC})
final class NativeAuthlibMetadataTest {
    @TempDir Path temp;

    @Test void refreshUsesNativeGetAndRedirectFallsBack() throws Exception {
        String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"); assumeTrue(binary != null && !binary.isBlank());
        org.jackhuang.hmcl.JavaFXLauncher.start(); assumeTrue(org.jackhuang.hmcl.JavaFXLauncher.isStarted());
        Path wrapper = temp.resolve("transport-backend");
        Files.writeString(wrapper, "#!/bin/sh\nIFS= read -r line\ncase \"$line\" in *yggdrasil_request*) : > \"$0.called\" ;; esac\n"
                + "printf '%s\\n' \"$line\" | exec '" + binary.replace("'", "'\\''") + "'\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        String previous = System.getProperty("mfcl.native.path"), enabled = System.getProperty("mfcl.native.yggdrasil.enabled");
        var previousProxy = java.net.ProxySelector.getDefault(); var previousAuth = java.net.Authenticator.getDefault();
        var previousCookies = java.net.CookieHandler.getDefault(); var previousCache = java.net.ResponseCache.getDefault();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/meta", exchange -> {
            requests.incrementAndGet(); byte[] body = "{\"meta\":{\"serverName\":\"测试服务器\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var compressed = new java.io.ByteArrayOutputStream();
            try (var gzip = new java.util.zip.GZIPOutputStream(compressed)) { gzip.write(body); }
            byte[] response = compressed.toByteArray(); exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=ISO-8859-1");
            exchange.sendResponseHeaders(200, response.length); try (var out = exchange.getResponseBody()) { out.write(response); }
        });
        server.createContext("/redirect", exchange -> {
            requests.incrementAndGet(); exchange.getResponseHeaders().set("Location", "/meta"); exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/locate", exchange -> {
            requests.incrementAndGet(); byte[] body = "{\"meta\":{\"serverName\":\"发现服务器\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length); try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.createContext("/alias", exchange -> {
            requests.incrementAndGet(); byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("x-authlib-injector-api-location", "/locate");
            exchange.sendResponseHeaders(200, body.length); try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            java.net.ProxySelector.setDefault(new java.net.ProxySelector() {
                @Override public List<java.net.Proxy> select(java.net.URI uri) { return List.of(java.net.Proxy.NO_PROXY); }
                @Override public void connectFailed(java.net.URI uri, java.net.SocketAddress address, java.io.IOException error) { }
            });
            java.net.Authenticator.setDefault(null); java.net.CookieHandler.setDefault(null); java.net.ResponseCache.setDefault(null);
            System.setProperty("mfcl.native.path", wrapper.toString()); System.clearProperty("mfcl.native.yggdrasil.enabled");
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            AuthlibInjectorServer direct = new AuthlibInjectorServer(base + "/meta"); direct.refreshMetadata();
            assertEquals("测试服务器", direct.getName()); assertEquals(1, requests.get()); assertTrue(Files.exists(Path.of(wrapper + ".called")));
            AuthlibInjectorServer redirect = new AuthlibInjectorServer(base + "/redirect"); redirect.refreshMetadata();
            assertEquals("测试服务器", redirect.getName()); assertEquals(4, requests.get());
            AuthlibInjectorServer located = AuthlibInjectorServer.locateServer(base + "/locate");
            assertEquals("发现服务器", located.getName()); assertEquals(base + "/locate/", located.getUrl()); assertEquals(5, requests.get());
            AuthlibInjectorServer aliased = AuthlibInjectorServer.locateServer(base + "/alias");
            assertEquals("发现服务器", aliased.getName()); assertEquals(base + "/locate/", aliased.getUrl()); assertEquals(8, requests.get());
        } finally {
            server.stop(0); java.net.ProxySelector.setDefault(previousProxy); java.net.Authenticator.setDefault(previousAuth);
            java.net.CookieHandler.setDefault(previousCookies); java.net.ResponseCache.setDefault(previousCache);
            if (previous == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previous);
            if (enabled == null) System.clearProperty("mfcl.native.yggdrasil.enabled"); else System.setProperty("mfcl.native.yggdrasil.enabled", enabled);
        }
    }

    @Test void metadataMatchesJavaAndRetainsFallback() throws Exception {
        String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"); assumeTrue(binary != null && !binary.isBlank());
        Path wrapper = temp.resolve("backend");
        Files.writeString(wrapper, "#!/bin/sh\nIFS= read -r line\ncase \"$line\" in *plan_authlib_metadata*) : > \"$0.called\" ;; esac\n"
                + "printf '%s\\n' \"$line\" | exec '" + binary.replace("'", "'\\''") + "'\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        String previous = System.getProperty("mfcl.native.path"), enabled = System.getProperty("mfcl.native.yggdrasil.enabled");
        try {
            for (String json : List.of("{}", "{\"meta\":null}", "{\"meta\":{\"serverName\":\"测试服务器\","
                    + "\"feature.non_email_login\":\"TrUe\",\"links\":{\"z\":\"https://example.test\",\"a\":true,\"skip\":{}}}}",
                    "{\"meta\":{\"serverName\":false,\"feature.non_email_login\":false,\"links\":{}}}")) {
                System.setProperty("mfcl.native.yggdrasil.enabled", "false");
                AuthlibInjectorServer javaServer = new AuthlibInjectorServer("https://example.test/"); javaServer.restoreMetadataCache(json, 123);
                System.setProperty("mfcl.native.path", wrapper.toString()); System.clearProperty("mfcl.native.yggdrasil.enabled");
                assertNotNull(NativeAuthlibMetadata.tryParse(json));
                AuthlibInjectorServer nativeServer = new AuthlibInjectorServer("https://example.test/"); nativeServer.restoreMetadataCache(json, 123);
                assertEquals(javaServer.getName(), nativeServer.getName()); assertEquals(javaServer.getLinks(), nativeServer.getLinks());
                assertEquals(List.copyOf(javaServer.getLinks().keySet()), List.copyOf(nativeServer.getLinks().keySet()));
                assertEquals(javaServer.isNonEmailLogin(), nativeServer.isNonEmailLogin());
                assertEquals(javaServer.getMetadataResponse(), nativeServer.getMetadataResponse()); assertEquals(123, nativeServer.getMetadataTimestamp());
                if (json.equals("{}")) assertThrows(UnsupportedOperationException.class, () -> nativeServer.getLinks().put("x", "y"));
            }
            assertTrue(Files.exists(Path.of(wrapper + ".called")));
            String legacy = "{\"meta\":{\"serverName\":1.0,\"links\":{\"count\":42}}}";
            assertNull(NativeAuthlibMetadata.tryParse(legacy));
            AuthlibInjectorServer fallback = new AuthlibInjectorServer("https://example.test/"); fallback.restoreMetadataCache(legacy, 9);
            assertEquals("1.0", fallback.getName()); assertEquals("42", fallback.getLinks().get("count"));
        } finally {
            if (previous == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previous);
            if (enabled == null) System.clearProperty("mfcl.native.yggdrasil.enabled"); else System.setProperty("mfcl.native.yggdrasil.enabled", enabled);
        }
    }
}
