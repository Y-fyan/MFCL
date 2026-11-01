/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.task;

import com.sun.net.httpserver.HttpServer;
import org.glavo.url.WebURL;
import org.jackhuang.hmcl.JavaFXLauncher;
import org.jackhuang.hmcl.download.DefaultCacheRepository;
import org.jackhuang.hmcl.nativebridge.NativeTransferTestSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Exercises metadata caching through the real Rust downloader and a loopback origin.
@NotNullByDefault
@EnabledOnOs({OS.LINUX, OS.MAC})
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("proxy-selector")
public final class NativeMetadataTest {
    /// Isolated test files.
    @TempDir Path temp;
    /// Prior backend path.
    private final @Nullable String previousPath = System.getProperty("mfcl.native.path");
    /// Prior download setting.
    private final @Nullable String previousEnabled = System.getProperty("mfcl.native.download.enabled");
    /// Prior JavaFX renderer.
    private final @Nullable String previousPrism = System.getProperty("prism.order");
    /// Prior proxy selector.
    private final @Nullable ProxySelector previousProxy = ProxySelector.getDefault();

    /// Restores process-global settings and terminates idle test backends.
    @AfterEach void restore() {
        NativeTransferTestSupport.clearIdle();
        restoreProperty("mfcl.native.path", previousPath);
        restoreProperty("mfcl.native.download.enabled", previousEnabled);
        restoreProperty("prism.order", previousPrism);
        ProxySelector.setDefault(previousProxy);
    }

    /// Restores a nullable system property.
    /// @param key property name
    /// @param value previous value
    private static void restoreProperty(String key, @Nullable String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }

    /// A 200 response is decoded and cached; an expired entry is reused after a native 304.
    @Test void textMetadataPreservesCharsetAndEtag() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        System.setProperty("prism.order", "sw"); JavaFXLauncher.start();
        assumeTrue(JavaFXLauncher.isStarted(), "JavaFX toolkit unavailable");
        ProxySelector.setDefault(new ProxySelector() {
            /// Returns an intentionally unreachable proxy to prove the download is direct.
            @Override public List<Proxy> select(URI uri) { return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", 1))); }
            /// Ignores fixture connection errors.
            @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
        });
        AtomicInteger count = new AtomicInteger(); AtomicInteger conditional = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/metadata", exchange -> {
            count.incrementAndGet(); String etag = exchange.getRequestHeaders().getFirst("If-None-Match");
            exchange.getResponseHeaders().set("ETag", "\"v1\"");
            exchange.getResponseHeaders().set("Cache-Control", "fresh".equals(exchange.getRequestURI().getQuery()) ? "max-age=3600" : "max-age=0");
            if ("\"v1\"".equals(etag)) {
                conditional.incrementAndGet(); exchange.sendResponseHeaders(304, -1); exchange.close(); return;
            }
            byte[] bytes = "café".getBytes(StandardCharsets.ISO_8859_1);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=ISO-8859-1");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            Path wrapper = temp.resolve("backend");
            String target = "http://127.0.0.1:" + server.getAddress().getPort() + "/metadata";
            Files.writeString(wrapper, """
                    #!/usr/bin/env python3
                    import json, os, pathlib, subprocess, sys
                    native = os.environ['MFCL_NATIVE_TEST_EXECUTABLE']
                    child = subprocess.Popen([native], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
                    try:
                        for line in sys.stdin:
                            request = json.loads(line)
                            request['params']['url'] = pathlib.Path(__file__ + '.url').read_text()
                            request['params']['allow_http_loopback'] = True
                            with open(__file__ + '.calls', 'a') as marker: marker.write('call\\n')
                            child.stdin.write(json.dumps(request) + '\\n'); child.stdin.flush()
                            while True:
                                response = child.stdout.readline()
                                if not response: sys.exit(1)
                                sys.stdout.write(response); sys.stdout.flush()
                                frame = json.loads(response)
                                if 'result' in frame or 'error' in frame: break
                    finally:
                        child.stdin.close(); child.wait(timeout=5)
                    """);
            Files.writeString(Path.of(wrapper + ".url"), target); assertTrue(wrapper.toFile().setExecutable(true));
            System.setProperty("mfcl.native.path", wrapper.toString()); System.clearProperty("mfcl.native.download.enabled");
            DefaultCacheRepository cache = new DefaultCacheRepository(temp.resolve("cache"));
            WebURL source = WebURL.parse("https://example.invalid/metadata");
            GetTask first = new GetTask(source); first.setCacheRepository(cache); first.execute();
            assertEquals("café", first.getResult()); assertEquals(1, count.get());
            Thread.sleep(30);
            GetTask second = new GetTask(source); second.setCacheRepository(cache); second.execute();
            assertEquals("café", second.getResult()); assertEquals(2, count.get()); assertEquals(1, conditional.get());
            CacheFileTask cacheFile = new CacheFileTask(WebURL.parse("https://example.invalid/cache-file"));
            cacheFile.setCacheRepository(cache); cacheFile.execute();
            assertArrayEquals("café".getBytes(StandardCharsets.ISO_8859_1), Files.readAllBytes(cacheFile.getResult()));
            Thread.sleep(30);
            CacheFileTask cachedFile = new CacheFileTask(WebURL.parse("https://example.invalid/cache-file"));
            cachedFile.setCacheRepository(cache); cachedFile.execute();
            assertArrayEquals("café".getBytes(StandardCharsets.ISO_8859_1), Files.readAllBytes(cachedFile.getResult()));
            assertEquals(2, conditional.get());
            Path output = temp.resolve("downloaded-file");
            FileDownloadTask file = new FileDownloadTask("https://example.invalid/direct-file", output);
            file.setCacheRepository(cache); file.execute();
            assertArrayEquals("café".getBytes(StandardCharsets.ISO_8859_1), Files.readAllBytes(output));
            Files.writeString(Path.of(wrapper + ".url"), target + "?fresh");
            WebURL freshSource = WebURL.parse("https://example.invalid/fresh");
            GetTask fresh = new GetTask(freshSource); fresh.setCacheRepository(cache); fresh.execute();
            GetTask cached = new GetTask(freshSource); cached.setCacheRepository(cache); cached.execute();
            assertEquals("café", cached.getResult()); assertEquals(6, count.get());
            assertEquals(6, Files.readAllLines(Path.of(wrapper + ".calls")).size());
        } finally { server.stop(0); }
    }
}
