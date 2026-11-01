/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.task;

import org.glavo.url.WebURL;
import com.sun.net.httpserver.HttpServer;
import org.jackhuang.hmcl.nativebridge.NativeTransferTestSupport;
import org.jackhuang.hmcl.download.DownloadCandidate;
import org.jackhuang.hmcl.util.CacheRepository;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Tests native file download routing using isolated fixture transfers.
@NotNullByDefault
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("proxy-selector")
public final class NativeFileDownloadTest {
    /// Expected hash of the fixture payload `abc`.
    private static final String SHA1 = "a9993e364706816aba3e25717850c26c9cd0d89d";
    /// Candidate eligible for the native transfer.
    private static final WebURL SOURCE = WebURL.parse("https://resources.download.minecraft.net/test");
    /// Test-owned destination directory.
    @TempDir Path tempDir;
    /// Previous backend executable setting.
    private final @Nullable String previousPath = System.getProperty("mfcl.native.path");
    /// Previous native download setting.
    private final @Nullable String previousEnabled = System.getProperty("mfcl.native.download.enabled");
    /// Previous restart-safe resume setting.
    private final @Nullable String previousResume = System.getProperty("mfcl.native.download.resume.enabled");
    /// Previous JVM proxy selector.
    private final @Nullable ProxySelector previousProxy = ProxySelector.getDefault();

    /// Configures an isolated fake backend and a direct proxy selector.
    @BeforeEach void setup() {
        System.setProperty("mfcl.native.path", tempDir.resolve("fixture").toString());
        System.clearProperty("mfcl.native.download.enabled");
        ProxySelector.setDefault(new ProxySelector() {
            /// Selects a direct connection for fixture URLs.
            @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
            /// Ignores connection errors because no network call is made.
            @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
        });
    }

    /// Restores global process settings.
    @AfterEach void restore() {
        NativeTransferTestSupport.clearIdle();
        if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
        if (previousEnabled == null) System.clearProperty("mfcl.native.download.enabled"); else System.setProperty("mfcl.native.download.enabled", previousEnabled);
        if (previousResume == null) System.clearProperty("mfcl.native.download.resume.enabled"); else System.setProperty("mfcl.native.download.resume.enabled", previousResume);
        ProxySelector.setDefault(previousProxy);
    }

    /// A native payload passes existing integrity handlers and reaches the existing cache.
    @Test void nativeTransferPreservesValidationAndCache() throws Exception {
        Path destination = tempDir.resolve("game.jar");
        Fixture task = new Fixture(destination, "abc");
        FixtureCache cache = new FixtureCache();
        task.setCacheRepository(cache);
        task.setCaching(true);
        AtomicBoolean checked = new AtomicBoolean();
        task.addIntegrityCheckHandler((file, target) -> {
            assertEquals("abc", Files.readString(file)); assertEquals(destination, target); checked.set(true);
        });
        task.execute();
        assertTrue(task.called); assertTrue(checked.get()); assertTrue(cache.cached);
        assertEquals("abc", Files.readString(destination)); assertEquals(3, task.progress);
    }

    /// The task, native child, local HTTP fixture, validator, and cache work end to end.
    @Test void actualNativeTransferPassesThroughTask() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        System.setProperty("mfcl.native.path", executable);
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file", exchange -> {
            byte[] bytes = "abc".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            String fixtureUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/file";
            Path destination = tempDir.resolve("native-game.jar");
            Fixture task = new Fixture(destination, "unused") {
                /// Routes the real native process to the isolated loopback server.
                @Override protected void transferNative(Path executablePath, String ignored, Path payload, @Nullable String sha1,
                                                        BiConsumer<Long, Long> report) throws IOException, InterruptedException {
                    NativeTransferTestSupport.loopback(executablePath, fixtureUrl, payload, sha1, report, () -> false);
                }
            };
            FixtureCache cache = new FixtureCache();
            task.setCacheRepository(cache);
            task.setCaching(true);
            task.execute();
            assertEquals("abc", Files.readString(destination));
            assertEquals(3, task.progress);
            assertTrue(cache.cached);
        } finally { server.stop(0); }
    }

    /// The real file task supplies its own cache directory for restart-safe native checkpoints.
    @Test void actualFileTaskUsesPrivateResumeCache() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        byte[] data = new byte[1024 * 1024 + 123];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 251);
        String sha1 = org.jackhuang.hmcl.util.DigestUtils.digestToString("SHA-1", data);
        String sha256 = org.jackhuang.hmcl.util.DigestUtils.digestToString("SHA-256", data);
        String sha512 = org.jackhuang.hmcl.util.DigestUtils.digestToString("SHA-512", data);
        String md5 = org.jackhuang.hmcl.util.DigestUtils.digestToString("MD5", data);
        var serverWorkers = java.util.concurrent.Executors.newFixedThreadPool(6);
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(serverWorkers);
        java.util.concurrent.atomic.AtomicInteger ranges = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/file", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range"); int start = 0, end = data.length - 1;
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes"); exchange.getResponseHeaders().set("ETag", "\"fixture\"");
            if (range != null) {
                ranges.incrementAndGet(); String[] parts = range.substring("bytes=".length()).split("-");
                start = Integer.parseInt(parts[0]); end = Integer.parseInt(parts[1]);
                exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + data.length);
            }
            try {
                exchange.sendResponseHeaders(range == null ? 200 : 206, end - start + 1);
                try (var output = exchange.getResponseBody()) { output.write(data, start, end - start + 1); }
            } catch (IOException ignored) { exchange.close(); }
        });
        server.start();
        try {
            Path wrapper = tempDir.resolve("resume-backend");
            Files.writeString(wrapper, """
                    #!/usr/bin/env python3
                    import json, os, pathlib, subprocess, sys
                    target = pathlib.Path(__file__ + '.url').read_text()
                    child = subprocess.Popen([os.environ['MFCL_NATIVE_TEST_EXECUTABLE']], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
                    try:
                        for line in sys.stdin:
                            request = json.loads(line)
                            if request['method'] == 'download':
                                pathlib.Path(__file__ + '.resume').write_text(request['params'].get('resume_directory', ''))
                                request['params']['url'] = target; request['params']['allow_http_loopback'] = True
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
            Files.writeString(Path.of(wrapper + ".url"), "http://127.0.0.1:" + server.getAddress().getPort() + "/file");
            assertTrue(wrapper.toFile().setExecutable(true)); System.setProperty("mfcl.native.path", wrapper.toString());
            System.clearProperty("mfcl.native.download.resume.enabled");
            var cache = new org.jackhuang.hmcl.download.DefaultCacheRepository(tempDir.resolve("private-cache"));
            Path resume = cache.getCacheDirectory().resolve("native-resume-v1").toAbsolutePath().normalize();
            for (var entry : java.util.Map.of("SHA-1", sha1, "SHA-256", sha256, "SHA-512", sha512, "MD5", md5).entrySet()) {
                Path destination = tempDir.resolve("actual-resume-" + entry.getKey() + ".jar");
                int previousRanges = ranges.get();
                FileDownloadTask task = new FileDownloadTask(SOURCE.href(), destination, new FileDownloadTask.IntegrityCheck(entry.getKey(), entry.getValue())) {
                    /// Avoids JavaFX in this isolated test.
                    @Override protected void updateProgress(long count, long total) { }
                    /// Avoids JavaFX in this isolated test.
                    @Override protected void updateProgress(double progress) { }
                };
                task.setCacheRepository(cache); task.setCaching(true); task.execute();
                assertArrayEquals(data, Files.readAllBytes(destination)); assertTrue(ranges.get() - previousRanges >= 2);
                assertEquals(resume.toString(), Files.readString(Path.of(wrapper + ".resume")));
                assertTrue(Files.isRegularFile(resume.resolve(entry.getValue() + ".lock")));
            }
            try (var files = Files.list(resume)) { assertFalse(files.anyMatch(path -> path.toString().endsWith(".part"))); }
        } finally { server.stop(0); serverWorkers.shutdownNow(); }
    }

    /// A corrupt native payload falls back without replacing an existing destination.
    @Test void corruptNativePayloadDoesNotReplaceDestination() throws Exception {
        Path destination = tempDir.resolve("game.jar");
        Files.writeString(destination, "old");
        Fixture task = new Fixture(destination, "bad");
        task.setCacheRepository(new FixtureCache());
        task.setCaching(true);
        assertFalse(task.tryNativeDownload(DownloadCandidate.of(SOURCE), false));
        assertEquals("old", Files.readString(destination));
    }

    /// A non-SHA-1 integrity check remains authoritative before final publication.
    @Test void otherChecksumUsesNativeStagingAndRejectsCorruption() throws Exception {
        String sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        Path destination = tempDir.resolve("other-digest");
        Fixture good = new Fixture(destination, "abc", new FileDownloadTask.IntegrityCheck("SHA-256", sha256));
        good.setCacheRepository(new FixtureCache()); good.setCaching(true);
        assertTrue(good.tryNativeDownload(DownloadCandidate.of(SOURCE), false));
        assertTrue(good.called); assertEquals("abc", Files.readString(destination));
        Fixture bad = new Fixture(destination, "bad", new FileDownloadTask.IntegrityCheck("SHA-256", sha256));
        bad.setCacheRepository(new FixtureCache()); bad.setCaching(true);
        assertFalse(bad.tryNativeDownload(DownloadCandidate.of(SOURCE), false));
        assertTrue(bad.called); assertEquals("abc", Files.readString(destination));
    }

    /// Modrinth SHA-512 is checked before publishing a native transfer.
    @Test void modrinthSha512RejectsCorruptNativePayload() throws Exception {
        String sha512 = "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f";
        Path destination = tempDir.resolve("mod.jar");
        Fixture good = new Fixture(destination, "abc", new FileDownloadTask.IntegrityCheck("SHA-512", sha512));
        good.setCacheRepository(new FixtureCache()); good.setCaching(true);
        assertTrue(good.tryNativeDownload(DownloadCandidate.of(SOURCE), false));
        Fixture bad = new Fixture(destination, "bad", new FileDownloadTask.IntegrityCheck("SHA-512", sha512));
        bad.setCacheRepository(new FixtureCache()); bad.setCaching(true);
        assertFalse(bad.tryNativeDownload(DownloadCandidate.of(SOURCE), false));
        assertEquals("abc", Files.readString(destination));
        CacheRepository cache = new CacheRepository(); cache.changeDirectory(tempDir.resolve("real-cache"));
        Path cached = cache.cacheFile(destination, "SHA-512", sha512);
        assertEquals(cached, cache.checkExistentFile(null, "SHA-512", sha512).orElseThrow());
        Files.writeString(cached, "corrupt");
        assertTrue(cache.checkExistentFile(null, "SHA-512", sha512).isEmpty());
    }

    /// Files without an independently checked checksum never use unverified native staging.
    @Test void missingOrEmptyChecksumCannotUseUnverifiedNativeTransfer() throws Exception {
        Fixture missing = new Fixture(tempDir.resolve("missing-check"), "abc", null);
        Fixture empty = new Fixture(tempDir.resolve("empty-check"), "abc", new FileDownloadTask.IntegrityCheck("SHA-256", ""));
        assertFalse(missing.tryNativeDownload(DownloadCandidate.of(SOURCE), false));
        assertFalse(empty.tryNativeDownload(DownloadCandidate.of(SOURCE), false));
        assertFalse(missing.called); assertFalse(empty.called);
    }

    /// The real Rust transfer and Java SHA-256 verifier work together against a loopback server.
    @Test void actualNativeTransferWithSha256() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        System.setProperty("mfcl.native.path", executable);
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file", exchange -> {
            byte[] bytes = "abc".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            String fixtureUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/file";
            Path destination = tempDir.resolve("sha256.jar");
            String sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
            AtomicBoolean transferred = new AtomicBoolean();
            Fixture task = new Fixture(destination, "unused", new FileDownloadTask.IntegrityCheck("SHA-256", sha256)) {
                /// Calls the real Rust process against the isolated server.
                @Override protected void transferNative(Path path, String ignored, Path payload, @Nullable String sha1,
                                                        BiConsumer<Long, Long> report) throws IOException, InterruptedException {
                    assertNull(sha1);
                    transferred.set(true);
                    NativeTransferTestSupport.loopback(path, fixtureUrl, payload, sha1, report, () -> false);
                }
            };
            task.setCacheRepository(new FixtureCache()); task.setCaching(true);
            task.execute(); assertTrue(transferred.get());
            assertEquals("abc", Files.readString(destination));
        } finally { server.stop(0); }
    }

    /// Cancellation after native transfer prevents final publication.
    @Test void cancellationPreventsPublication() throws Exception {
        Path destination = tempDir.resolve("game.jar");
        Fixture task = new Fixture(destination, "abc");
        AtomicBoolean cancelled = new AtomicBoolean();
        task.cancelAfterTransfer = cancelled;
        task.setCancelled(cancelled::get);
        task.setCacheRepository(new FixtureCache());
        task.setCaching(true);
        assertThrows(InterruptedException.class, () -> task.tryNativeDownload(DownloadCandidate.of(SOURCE), false));
        assertFalse(Files.exists(destination));
    }

    /// User-requested direct downloads bypass a configured JVM proxy.
    @Test void configuredProxyStillUsesDirectNativePath() throws Exception {
        ProxySelector.setDefault(new ProxySelector() {
            /// Supplies a test-only HTTP proxy.
            @Override public List<Proxy> select(URI uri) { return List.of(new Proxy(Proxy.Type.HTTP, new java.net.InetSocketAddress("127.0.0.1", 1234))); }
            /// Ignores fixture connection errors.
            @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
        });
        Fixture task = new Fixture(tempDir.resolve("game.jar"), "abc");
        task.setCacheRepository(new FixtureCache()); task.setCaching(true);
        assertTrue(task.tryNativeDownload(DownloadCandidate.of(SOURCE), false));
        assertTrue(task.called);
    }

    /// Java fallback and every redirect bypass a configured HTTP proxy.
    @Test void javaFallbackDownloadsDirectlyEvenWhenProxyConfigured() throws Exception {
        java.util.concurrent.atomic.AtomicInteger proxySelections = new java.util.concurrent.atomic.AtomicInteger();
        ProxySelector.setDefault(new ProxySelector() {
            /// Supplies a deliberately unreachable proxy.
            @Override public List<Proxy> select(URI uri) {
                proxySelections.incrementAndGet();
                return List.of(new Proxy(Proxy.Type.HTTP, new java.net.InetSocketAddress("127.0.0.1", 1)));
            }
            /// Ignores fixture connection failures.
            @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
        });
        System.setProperty("mfcl.native.download.enabled", "false");
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/file"); exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/file", exchange -> {
            byte[] data = "abc".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, data.length);
            try (var out = exchange.getResponseBody()) { out.write(data); }
        });
        server.start();
        try {
            Path destination = tempDir.resolve("direct.jar");
            FileDownloadTask task = new FileDownloadTask("http://127.0.0.1:" + server.getAddress().getPort() + "/redirect",
                    destination, new FileDownloadTask.IntegrityCheck("SHA-1", SHA1)) {
                /// Avoids JavaFX in this isolated network test.
                @Override protected void updateProgress(long count, long total) { }
                /// Avoids JavaFX in this isolated network test.
                @Override protected void updateProgress(double progress) { }
            };
            task.setCacheRepository(new org.jackhuang.hmcl.download.DefaultCacheRepository(tempDir.resolve("direct-cache")));
            task.setCaching(true); task.execute();
            assertEquals("abc", Files.readString(destination));
            assertEquals(0, proxySelections.get());
        } finally { server.stop(0); }
    }

    /// Direct HTTPS mirrors and loader repositories use the same verified Rust path.
    @Test void verifiedThirdPartySourcesUseNativeTransfer() throws Exception {
        for (String url : List.of("https://bmclapi2.bangbang93.com/assets/test", "https://maven.fabricmc.net/test.jar",
                "https://maven.minecraftforge.net/test.jar")) {
            Fixture task = new Fixture(tempDir.resolve("output-" + Math.abs(url.hashCode())), "abc");
            task.setCacheRepository(new FixtureCache());
            assertTrue(task.tryNativeDownload(DownloadCandidate.of(url), false));
            assertTrue(task.called);
        }
    }

    /// API-key sources, custom timeouts and explicitly disabled downloads retain the Java path.
    @Test void unsupportedPolicyAndDisabledFeatureUseJavaPath() throws Exception {
        Fixture task = new Fixture(tempDir.resolve("game.jar"), "abc");
        assertFalse(task.tryNativeDownload(DownloadCandidate.of("https://api.curseforge.com/file"), false));
        assertFalse(task.tryNativeDownload(DownloadCandidate.of("https://edge.forgecdn.net/file"), false));
        assertFalse(task.tryNativeDownload(DownloadCandidate.of("http://example.invalid/file"), false));
        assertFalse(task.tryNativeDownload(DownloadCandidate.of("https://user:password@example.invalid/file"), false));
        assertFalse(task.tryNativeDownload(DownloadCandidate.of("https://example.invalid/file#fragment"), false));
        assertFalse(task.tryNativeDownload(new DownloadCandidate(SOURCE, null, -1, Duration.ofSeconds(1), null), false));
        assertFalse(task.tryNativeDownload(new DownloadCandidate(SOURCE, null, -1, null, Duration.ofSeconds(1)), false));
        System.setProperty("mfcl.native.download.enabled", "false");
        assertFalse(task.tryNativeDownload(DownloadCandidate.of(SOURCE), false));
        assertFalse(task.called);
    }

    /// A failed integrity handler prevents replacement and requests Java fallback.
    @Test void failedIntegrityHandlerPreservesDestination() throws Exception {
        Path destination = tempDir.resolve("game.jar");
        Files.writeString(destination, "old");
        Fixture task = new Fixture(destination, "abc");
        task.setCacheRepository(new FixtureCache());
        task.setCaching(true);
        task.addIntegrityCheckHandler((payload, target) -> { throw new IOException("fixture rejected"); });
        assertFalse(task.tryNativeDownload(DownloadCandidate.of(SOURCE), false));
        assertEquals("old", Files.readString(destination));
    }

    /// Existing validated cache content is used before attempting a native transfer.
    @Test void cacheHitSkipsNativeTransfer() throws Exception {
        Path source = tempDir.resolve("cached"); Files.writeString(source, "abc");
        Path destination = tempDir.resolve("game.jar"); Fixture task = new Fixture(destination, "abc");
        task.setCacheRepository(new FixtureCache() {
            /// Returns the synthetic existing cache file.
            @Override public Optional<Path> checkExistentFile(@Nullable Path original, String algorithm, String hash) { return Optional.of(source); }
        });
        task.setCaching(true);
        task.execute();
        assertFalse(task.called); assertEquals("abc", Files.readString(destination));
    }

    /// Native transfers are skipped for sources requiring ETag handling.
    @Test void etagRequestsUseJavaPath() throws Exception {
        Fixture task = new Fixture(tempDir.resolve("game.jar"), "abc");
        assertFalse(task.tryNativeDownload(DownloadCandidate.of(SOURCE), true));
        assertFalse(task.called);
    }

    /// Transfer fixture that writes controlled data without contacting a server.
    private static class Fixture extends FileDownloadTask {
        /// Payload to write as the native result.
        private final String payload;
        /// Whether the native method was invoked.
        private boolean called;
        /// Last reported byte count.
        private long progress;
        /// Optional flag to request cancellation after transfer.
        private @Nullable AtomicBoolean cancelAfterTransfer;

        /// Creates a fixture task.
        ///
        /// @param destination target file
        /// @param payload synthetic transfer bytes
        private Fixture(Path destination, String payload) {
            this(destination, payload, new IntegrityCheck("SHA-1", SHA1));
        }

        /// Creates a fixture with an explicit integrity algorithm.
        /// @param destination target file
        /// @param payload synthetic transfer bytes
        /// @param check expected digest, or null
        private Fixture(Path destination, String payload, @Nullable IntegrityCheck check) {
            super(SOURCE.href(), destination, check);
            this.payload = payload;
        }

        /// Writes the synthetic payload and reports progress.
        @Override protected void transferNative(Path executable, String url, Path destination, @Nullable String sha1,
                                                BiConsumer<Long, Long> report) throws IOException, InterruptedException {
            called = true;
            Files.writeString(destination, payload, StandardCharsets.UTF_8);
            report.accept((long) payload.length(), (long) payload.length());
            if (cancelAfterTransfer != null) cancelAfterTransfer.set(true);
        }

        /// Records progress without requiring a JavaFX toolkit in this isolated test.
        @Override protected void updateProgress(long count, long total) { progress = count; }
    }

    /// Cache fixture that records successful cache insertion.
    private static class FixtureCache extends CacheRepository {
        /// Whether the downloaded payload was cached.
        private boolean cached;
        /// Returns a cache miss for the fixture.
        @Override public Optional<Path> checkExistentFile(@Nullable Path original, String algorithm, String hash) { return Optional.empty(); }
        /// Records a successful cache write.
        @Override public Path cacheFile(Path path, String algorithm, String hash) { cached = true; return path; }
    }
}
