/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.task;

import org.glavo.url.WebURL;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.jackhuang.hmcl.download.DefaultCacheRepository;
import org.jackhuang.hmcl.download.DefaultDependencyManager;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.download.game.GameDownloadTask;
import org.jackhuang.hmcl.game.DefaultGameInstance;
import org.jackhuang.hmcl.game.DefaultGameRepository;
import org.jackhuang.hmcl.game.DefaultGameRepositoryLayout;
import org.jackhuang.hmcl.game.DefaultGameRepositoryDraft;
import org.jackhuang.hmcl.game.DefaultGameRepositorySnapshot;
import org.jackhuang.hmcl.game.GameInstanceID;
import org.jackhuang.hmcl.game.GameInstanceManifest;
import org.jackhuang.hmcl.download.DownloadCandidate;
import org.jackhuang.hmcl.download.DownloadCandidates;
import org.jackhuang.hmcl.util.CacheRepository;
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
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Verifies native transfers for content-addressed client JAR downloads.
@NotNullByDefault
@EnabledOnOs({OS.LINUX, OS.MAC})
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("proxy-selector")
public final class NativeCacheFileTaskTest {
    /// Known digest of the fixture bytes `abc`.
    private static final String SHA1 = "a9993e364706816aba3e25717850c26c9cd0d89d";
    /// Synthetic eligible client source, never contacted by the fixture backend.
    private static final WebURL SOURCE = WebURL.parse("https://piston-data.mojang.com/v1/objects/fixture/client.jar");
    /// Isolated cache and fixture directory.
    @TempDir Path tempDir;
    /// Previous native path.
    private final @Nullable String previousPath = System.getProperty("mfcl.native.path");
    /// Previous native transfer setting.
    private final @Nullable String previousEnabled = System.getProperty("mfcl.native.download.enabled");
    /// Previous JVM proxy selector.
    private final @Nullable ProxySelector previousProxy = ProxySelector.getDefault();

    /// Uses a direct connection policy for isolated test URLs.
    @BeforeEach void setup() {
        System.clearProperty("mfcl.native.download.enabled");
        ProxySelector.setDefault(new ProxySelector() {
            /// Returns a direct connection.
            @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
            /// Does nothing because the fixture does not contact a proxy.
            @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
        });
    }

    /// Restores process-global settings.
    @AfterEach void restore() {
        if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
        if (previousEnabled == null) System.clearProperty("mfcl.native.download.enabled"); else System.setProperty("mfcl.native.download.enabled", previousEnabled);
        ProxySelector.setDefault(previousProxy);
    }

    /// A verified native payload reaches the content-addressed cache and task result.
    @Test void nativeClientDownloadPublishesToCache() throws Exception {
        Path backend = backend("abc", false);
        System.setProperty("mfcl.native.path", backend.toString());
        FixtureCache cache = new FixtureCache(tempDir.resolve("cache"));
        CacheFileTask task = new CacheFileTask(DownloadCandidates.of(SOURCE), SHA1);
        task.setCacheRepository(cache);
        task.execute();
        assertTrue(Files.exists(Path.of(backend + ".called")));
        assertTrue(cache.cached);
        assertEquals("abc", Files.readString(task.getResult()));
        assertEquals(cache.output, task.getResult());
        assertFalse(Files.exists(Path.of(Files.readString(Path.of(backend + ".destination"))).getParent()));
    }

    /// A game client task obtains a verified cache JAR through the real Rust child and local server.
    @Test void gameDownloadTaskUsesActualNativeBackend() throws Exception {
        @Nullable String nativeBinary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(nativeBinary != null && !nativeBinary.isBlank());
        com.sun.net.httpserver.HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/client.jar", exchange -> {
            byte[] bytes = "abc".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            String localUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/client.jar";
            Path wrapper = tempDir.resolve("real-native-wrapper");
            String quotedBinary = "'" + nativeBinary.replace("'", "'\\''") + "'";
            String substitution = "s|" + SOURCE.href() + "|" + localUrl + "|;"
                    + "s/\"allow_http_loopback\":false/\"allow_http_loopback\":true/;"
                    + "s/\"progress\":true/\"progress\":false/";
            Files.writeString(wrapper, "#!/bin/sh\nread line\n: > \"$0.called\"\nprintf '%s\\n' \"$line\" | sed '" + substitution + "' | " + quotedBinary + "\n");
            assertTrue(wrapper.toFile().setExecutable(true));
            System.setProperty("mfcl.native.path", wrapper.toString());
            DefaultCacheRepository cache = new DefaultCacheRepository(tempDir.resolve("cache-root"));
            TestRepository repository = new TestRepository(tempDir.resolve("game"));
            DefaultDependencyManager manager = new DefaultDependencyManager(repository, new DownloadProvider(), cache);
            String json = "{\"id\":\"fixture\",\"downloads\":{\"client\":{\"url\":\"" + SOURCE.href()
                    + "\",\"sha1\":\"" + SHA1 + "\",\"size\":3}}}";
            GameInstanceManifest manifest = GameInstanceManifest.fromJson(JsonParser.parseString(json).getAsJsonObject(), false);
            GameDownloadTask task = new GameDownloadTask(manager, manifest);
            task.execute();
            CacheFileTask download = (CacheFileTask) task.getDependencies().iterator().next();
            assertTrue(download.tryNativeDownload(DownloadCandidate.of(SOURCE), false));
            task.postExecute();
            assertEquals("abc", Files.readString(task.getResult()));
            assertTrue(Files.exists(Path.of(wrapper + ".called")));
            assertTrue(task.getResult().startsWith(cache.getCacheDirectory()));
            GameInstanceID instanceId = new GameInstanceID("fixture");
            Path instanceRoot = repository.getLayout().getInstanceRoot(instanceId);
            try (DefaultGameRepositoryDraft draft = repository.openDraft()) {
                draft.put(manifest);
                draft.putPrimaryJar(instanceId, task.getResult());
                assertFalse(Files.exists(instanceRoot));
                draft.commit();
            }
            assertEquals("abc", Files.readString(repository.getLayout().getInstanceJarFile(instanceId)));
            assertTrue(Files.isRegularFile(repository.getLayout().getInstanceJson(instanceId)));
            assertNotNull(repository.getSnapshot().getInstance(instanceId));
        } finally { server.stop(0); }
    }

    /// Repository used only to construct an isolated dependency manager.
    private static final class TestRepository extends DefaultGameRepository {
        /// Creates a repository rooted inside the test directory.
        /// @param root repository root
        private TestRepository(Path root) { super(root); }
        /// Creates the standard isolated layout.
        @Override protected DefaultGameRepositoryLayout createLayout(Path root) { return new DefaultGameRepositoryLayout(root); }
        /// Creates a test instance when the isolated draft is committed.
        @Override protected DefaultGameInstance createInstance(DefaultGameRepositorySnapshot snapshot, GameInstanceID id,
                                                               GameInstanceManifest manifest, @Nullable Path manifestFile) {
            return new TestInstance(snapshot, id, manifest, manifestFile);
        }
    }

    /// Minimal snapshot-bound instance used for transactional installation tests.
    private static final class TestInstance extends DefaultGameInstance {
        /// Creates a synthetic instance.
        /// @param snapshot owning snapshot
        /// @param id instance identifier
        /// @param manifest stored manifest
        /// @param file optional manifest path
        private TestInstance(DefaultGameRepositorySnapshot snapshot, GameInstanceID id,
                             GameInstanceManifest manifest, @Nullable Path file) { super(snapshot, id, manifest, file); }
        /// Copies this instance to a new snapshot.
        @Override protected DefaultGameInstance withNewSnapshot(DefaultGameRepositorySnapshot snapshot) {
            return new TestInstance(snapshot, id, manifest, manifestFile);
        }
        /// Copies this instance with an updated manifest.
        @Override protected DefaultGameInstance withManifest(DefaultGameRepositorySnapshot snapshot, GameInstanceManifest manifest) {
            return new TestInstance(snapshot, id, manifest, manifestFile);
        }
    }

    /// Cache hits complete before starting a native backend.
    @Test void cacheHitSkipsNativeClientDownload() throws Exception {
        Path backend = backend("abc", false); System.setProperty("mfcl.native.path", backend.toString());
        Path existing = tempDir.resolve("existing"); Files.writeString(existing, "abc");
        FixtureCache cache = new FixtureCache(tempDir.resolve("cache")); cache.existing = existing;
        CacheFileTask task = new CacheFileTask(DownloadCandidates.of(SOURCE), SHA1); task.setCacheRepository(cache);
        task.execute();
        assertEquals(existing, task.getResult()); assertFalse(Files.exists(Path.of(backend + ".called")));
    }

    /// A backend that writes corrupt data cannot populate the cache.
    @Test void corruptClientPayloadDoesNotEnterCache() throws Exception {
        Path backend = backend("bad", false); System.setProperty("mfcl.native.path", backend.toString());
        FixtureCache cache = new FixtureCache(tempDir.resolve("cache"));
        CacheFileTask task = new CacheFileTask(DownloadCandidates.of(SOURCE), SHA1); task.setCacheRepository(cache);
        assertFalse(task.tryNativeDownload(DownloadCandidate.of(SOURCE), false));
        assertFalse(cache.cached); assertFalse(Files.exists(cache.output));
        assertFalse(Files.exists(Path.of(Files.readString(Path.of(backend + ".destination"))).getParent()));
    }

    /// A cache publication error triggers fallback and removes native staging files.
    @Test void cacheFailureDoesNotLeaveNativeStaging() throws Exception {
        Path backend = backend("abc", false); System.setProperty("mfcl.native.path", backend.toString());
        FixtureCache cache = new FixtureCache(tempDir.resolve("cache")); cache.fail = true;
        CacheFileTask task = new CacheFileTask(DownloadCandidates.of(SOURCE), SHA1); task.setCacheRepository(cache);
        assertFalse(task.tryNativeDownload(DownloadCandidate.of(SOURCE), false));
        assertFalse(cache.cached); assertFalse(Files.exists(cache.output));
        assertFalse(Files.exists(Path.of(Files.readString(Path.of(backend + ".destination"))).getParent()));
    }

    /// A failed client transfer leaves a previously committed instance and snapshot untouched.
    @Test void failedNativeClientDoesNotChangeCommittedInstance() throws Exception {
        TestRepository repository = new TestRepository(tempDir.resolve("game"));
        GameInstanceID id = new GameInstanceID("existing");
        GameInstanceManifest manifest = new GameInstanceManifest(id);
        Path oldSource = tempDir.resolve("old-client.jar"); Files.writeString(oldSource, "old-client");
        try (DefaultGameRepositoryDraft draft = repository.openDraft()) {
            draft.put(manifest); draft.putPrimaryJar(id, oldSource); draft.commit();
        }
        DefaultGameRepositorySnapshot before = repository.getSnapshot();
        Path jar = repository.getLayout().getInstanceJarFile(id);
        Path json = repository.getLayout().getInstanceJson(id);
        String originalManifest = Files.readString(json);
        Path backend = backend("bad", false); System.setProperty("mfcl.native.path", backend.toString());
        FixtureCache cache = new FixtureCache(tempDir.resolve("new-cache"));
        CacheFileTask download = new CacheFileTask(DownloadCandidates.of(SOURCE), SHA1); download.setCacheRepository(cache);
        assertFalse(download.tryNativeDownload(DownloadCandidate.of(SOURCE), false));
        assertSame(before, repository.getSnapshot()); assertEquals("old-client", Files.readString(jar));
        assertEquals(originalManifest, Files.readString(json)); assertFalse(cache.cached);
    }

    /// Requests without a known digest do not use the native content-addressed path.
    @Test void noDigestRetainsEtagPath() throws Exception {
        Path backend = backend("abc", false); System.setProperty("mfcl.native.path", backend.toString());
        CacheFileTask task = new CacheFileTask(DownloadCandidates.of(SOURCE));
        assertFalse(task.tryNativeDownload(DownloadCandidate.of(SOURCE), false));
        assertFalse(Files.exists(Path.of(backend + ".called")));
    }

    /// Cancellation terminates the native child and removes private staging files.
    @Test void cancellationCleansClientStaging() throws Exception {
        Path backend = backend("abc", true); System.setProperty("mfcl.native.path", backend.toString());
        FixtureCache cache = new FixtureCache(tempDir.resolve("cache"));
        CacheFileTask task = new CacheFileTask(DownloadCandidates.of(SOURCE), SHA1); task.setCacheRepository(cache);
        AtomicBoolean cancelled = new AtomicBoolean(); task.setCancelled(cancelled::get);
        AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> { try { task.execute(); } catch (Throwable e) { failure.set(e); } });
        worker.setDaemon(true); worker.start();
        try {
            Path pidFile = Path.of(backend + ".pid");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!Files.exists(pidFile) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(pidFile)); long pid = Long.parseLong(Files.readString(pidFile));
            Path staging = Path.of(Files.readString(Path.of(backend + ".destination"))).getParent();
            cancelled.set(true); worker.join(5000);
            assertFalse(worker.isAlive()); assertNull(failure.get()); assertFalse(cache.cached); assertFalse(Files.exists(staging));
            ProcessHandle.of(pid).ifPresent(process -> assertDoesNotThrow(() -> process.onExit().get(3, TimeUnit.SECONDS)));
        } finally { cancelled.set(true); worker.interrupt(); worker.join(5000); }
    }

    /// Creates a fake backend that writes a payload to its requested staging path.
    ///
    /// @param payload synthetic payload
    /// @param wait whether to wait for cancellation instead of completing
    /// @return executable path
    /// @throws IOException if creation fails
    private Path backend(String payload, boolean wait) throws IOException {
        Path script = tempDir.resolve("backend");
        String prefix = "#!/bin/sh\nread line\n: > \"$0.called\"\n"
                + "destination=$(printf '%s' \"$line\" | sed -n 's/.*\"destination\":\"\\([^\"]*\\)\".*/\\1/p')\n"
                + "printf '%s' \"$destination\" > \"$0.destination\"\n";
        String body = wait ? "printf '%s' \"$$\" > \"$0.pid\"\nwhile :; do :; done\n"
                : "printf '%s' '" + payload + "' > \"$destination\"\nprintf '%s\\n' '{\"version\":1,\"id\":1,\"result\":{\"bytes_written\":3,\"sha1\":\"" + SHA1 + "\"}}'\n";
        Files.writeString(script, prefix + body);
        if (!script.toFile().setExecutable(true)) throw new IOException("Cannot mark test executable");
        return script;
    }

    /// Content-addressed cache fixture with an optional existing file.
    private static final class FixtureCache extends CacheRepository {
        /// Cache output path.
        private final Path output;
        /// Existing cache hit, if configured.
        private @Nullable Path existing;
        /// Whether a payload was written to the cache.
        private boolean cached;
        /// Whether the next cache publication should fail.
        private boolean fail;
        /// Creates a cache fixture.
        ///
        /// @param directory cache directory
        private FixtureCache(Path directory) { output = directory.resolve("client.jar"); }
        /// Returns the configured cache hit or a miss.
        @Override public Optional<Path> checkExistentFile(@Nullable Path original, String algorithm, String hash) { return Optional.ofNullable(existing); }
        /// Copies the validated payload to the fixture cache.
        @Override public Path cacheFile(Path source, String algorithm, String hash) throws IOException {
            if (fail) throw new IOException("Synthetic cache failure");
            Files.createDirectories(output.getParent()); Files.copy(source, output); cached = true; return output;
        }
    }
}
