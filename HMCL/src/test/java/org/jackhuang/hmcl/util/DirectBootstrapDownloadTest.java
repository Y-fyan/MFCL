/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.util;

import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies that bootstrap dependency downloads bypass configured proxies.
@NotNullByDefault
@ResourceLock("proxy-selector")
public final class DirectBootstrapDownloadTest {
    /// Bootstrap downloads and redirected requests never consult the JVM proxy selector.
    @Test void bootstrapAndRedirectUseDirectConnections() throws Exception {
        @Nullable ProxySelector previous = ProxySelector.getDefault();
        AtomicInteger selected = new AtomicInteger();
        ProxySelector.setDefault(new ProxySelector() {
            /// Returns an unreachable proxy if consulted.
            @Override public List<Proxy> select(URI uri) {
                selected.incrementAndGet(); return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 1)));
            }
            /// No error processing is needed for this fixture.
            @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
        });
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/file"); exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/file", exchange -> {
            byte[] data = "fixture".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, data.length);
            try (var output = exchange.getResponseBody()) { output.write(data); }
        });
        server.start();
        try (var input = SelfDependencyPatcher.openDirectDownload("http://127.0.0.1:" + server.getAddress().getPort() + "/redirect")) {
            assertEquals("fixture", new String(input.readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(0, selected.get());
        } finally { server.stop(0); ProxySelector.setDefault(previous); }
    }
}
