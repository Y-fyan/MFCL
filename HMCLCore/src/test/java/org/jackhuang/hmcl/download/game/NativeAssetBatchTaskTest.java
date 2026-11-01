/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.game;

import com.sun.net.httpserver.HttpServer;
import org.jackhuang.hmcl.download.DefaultCacheRepository;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class NativeAssetBatchTaskTest {
    @TempDir Path temp;

    @Test void largeAndUnknownDownloadsRetainRangeAndResumePath() {
        assertFalse(NativeDownloadBatchTask.isBatchSizeEligible(0));
        assertTrue(NativeDownloadBatchTask.isBatchSizeEligible(1024 * 1024 - 1));
        assertFalse(NativeDownloadBatchTask.isBatchSizeEligible(1024 * 1024));
    }

    @Test void cachePresenceIsOnlyAHintAndDoesNotTrustCorruptData() throws Exception {
        var cache = new DefaultCacheRepository(temp.resolve("cache"));
        String hash = "a9993e364706816aba3e25717850c26c9cd0d89d";
        Path cached = cache.getCacheDirectory().resolve("SHA-1").resolve(hash.substring(0, 2)).resolve(hash);
        Files.createDirectories(cached.getParent()); Files.writeString(cached, "wrong");
        assertTrue(cache.mayHaveCachedFile(null, "SHA-1", hash));
        assertTrue(cache.checkExistentFile(null, "SHA-1", hash).isEmpty());
        var library = new org.jackhuang.hmcl.game.Library(new org.jackhuang.hmcl.game.Artifact("a", "b", "1"), null,
                new org.jackhuang.hmcl.game.LibrariesDownloadInfo(new org.jackhuang.hmcl.game.LibraryDownloadInfo(
                        "a/b/1/b-1.jar", "https://example.test/b", hash)), null, null, null, null, null, null);
        assertTrue(cache.mayHaveLibrary(library));
        assertTrue(cache.getLibrary(library).isEmpty(), "Corrupt cache hints must not bypass verification");
        Files.delete(cached);
        Path legacy = cache.getCommonDirectory().resolve("libraries/a/b/1/b-1.jar");
        Files.createDirectories(legacy.getParent()); Files.writeString(legacy, "abc");
        assertTrue(cache.mayHaveLibrary(library));
        assertTrue(cache.getLibrary(library).isPresent());
    }

    @Test void stagedLibraryStillRequiresValidJar() throws Exception {
        Path staged = temp.resolve("staged"), destination = temp.resolve("library.jar");
        Files.writeString(staged, "abc");
        FileDownloadTask task = new FileDownloadTask("https://example.invalid/library", destination,
                new FileDownloadTask.IntegrityCheck("SHA-1", "a9993e364706816aba3e25717850c26c9cd0d89d"));
        task.setCacheRepository(new DefaultCacheRepository(temp.resolve("cache")));
        task.addIntegrityCheckHandler(FileDownloadTask.ZIP_INTEGRITY_CHECK_HANDLER);
        assertThrows(java.io.IOException.class, () -> task.importNativePayload(staged, () -> false));
        assertFalse(Files.exists(destination));
    }

    /// A Rust success is published and a Rust failure uses the existing individual downloader.
    @Test void publishesAndRetriesFailedItems() throws Exception {
        String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(binary != null && !binary.isBlank());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/good", exchange -> {
            byte[] data = "abc".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, data.length); try (var out = exchange.getResponseBody()) { out.write(data); }
        });
        server.createContext("/retry", exchange -> {
            if (attempts.incrementAndGet() == 1) { exchange.sendResponseHeaders(500, -1); exchange.close(); return; }
            byte[] data = "abc".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, data.length); try (var out = exchange.getResponseBody()) { out.write(data); }
        });
        server.start();
        try {
            Path wrapper = temp.resolve("native-wrapper");
            Files.writeString(wrapper, """
                    #!/usr/bin/env python3
                    import json, os, subprocess, sys
                    request = json.loads(sys.stdin.readline())
                    request['params']['allow_http_loopback'] = True
                    subprocess.run([os.environ['MFCL_NATIVE_TEST_EXECUTABLE']], input=json.dumps(request) + '\\n', text=True, stdout=sys.stdout, check=True)
                    """);
            assertTrue(wrapper.toFile().setExecutable(true));
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            String hash = "a9993e364706816aba3e25717850c26c9cd0d89d";
            Path first = temp.resolve("first"), second = temp.resolve("second");
            DefaultCacheRepository cache = new DefaultCacheRepository(temp.resolve("cache"));
            FileDownloadTask one = new FileDownloadTask(base + "/good", first, new FileDownloadTask.IntegrityCheck("SHA-1", hash));
            FileDownloadTask two = new FileDownloadTask(base + "/retry", second, new FileDownloadTask.IntegrityCheck("SHA-1", hash));
            one.setCacheRepository(cache); two.setCacheRepository(cache);
            NativeDownloadBatchTask task = new NativeDownloadBatchTask(wrapper, List.of(
                    new NativeDownloadBatchTask.Entry(one, one, hash, List.of(base + "/good"), () -> { }),
                    new NativeDownloadBatchTask.Entry(two, two, hash, List.of(base + "/retry"), () -> { })), "hmcl.install.assets");
            assertTrue(task.executor().test(), () -> "Batch task failed: " + task.getException());
            assertEquals("abc", Files.readString(first)); assertEquals("abc", Files.readString(second));
            assertEquals(2, attempts.get());
        } finally { server.stop(0); }
    }
}
