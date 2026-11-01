/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.game;

import org.jackhuang.hmcl.game.AssetObject;
import org.jackhuang.hmcl.game.DefaultGameInstance;
import org.jackhuang.hmcl.game.DefaultGameRepository;
import org.jackhuang.hmcl.game.DefaultGameRepositoryLayout;
import org.jackhuang.hmcl.game.DefaultGameRepositorySnapshot;
import org.jackhuang.hmcl.game.GameInstanceID;
import org.jackhuang.hmcl.game.GameInstanceManifest;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jackhuang.hmcl.util.DigestUtils;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Tests native asset hash inspection and the existing Java verification fallback.
@NotNullByDefault
@EnabledOnOs(OS.LINUX)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
public final class NativeAssetHashTest {
    /// Isolated game directory.
    @TempDir Path tempDir;
    /// Previous native executable setting.
    private final @Nullable String previousPath = System.getProperty("mfcl.native.path");
    /// Previous native asset hash setting.
    private final @Nullable String previousEnabled = System.getProperty("mfcl.native.assets.hash.enabled");
    /// Previous pure-request pooling setting.
    private final @Nullable String previousPool = System.getProperty("mfcl.native.pool.enabled");
    /// Known hash of `abc`.
    private static final String ABC = "a9993e364706816aba3e25717850c26c9cd0d89d";

    /// Restores process-global configuration.
    @AfterEach void restore() {
        if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
        if (previousEnabled == null) System.clearProperty("mfcl.native.assets.hash.enabled"); else System.setProperty("mfcl.native.assets.hash.enabled", previousEnabled);
        if (previousPool == null) System.clearProperty("mfcl.native.pool.enabled"); else System.setProperty("mfcl.native.pool.enabled", previousPool);
    }

    /// Native observations agree with Java for valid, mismatched, missing and duplicate objects.
    @Test void observationsMatchJavaDecisions() throws Exception {
        configureNative(); TestRepository repo = new TestRepository(tempDir.resolve("game"));
        AssetObject good = new AssetObject(ABC, 3); writeAsset(repo, good, "abc");
        String expected = DigestUtils.digestToString("SHA-1", "expected".getBytes(StandardCharsets.UTF_8));
        AssetObject bad = new AssetObject(expected, 8); writeAsset(repo, bad, "incorrect");
        AssetObject missing = new AssetObject("0000000000000000000000000000000000000000", 1);
        AssetObject legacy = new AssetObject("ab", 1); writeAsset(repo, legacy, "legacy");
        List<AssetObject> objects = List.of(good, bad, missing, good, legacy);
        Map<Integer, NativeBackend.FileHashResult> observations = GameAssetDownloadTask.inspectAssetHashes(repo, objects, true, () -> false, null);
        assertEquals(3, observations.size()); assertSame(observations.get(0), observations.get(3));
        assertFalse(observations.containsKey(2)); assertFalse(observations.containsKey(4));
        for (int i = 0; i < objects.size(); i++) {
            Path path = repo.getLayout().getAssetObject(objects.get(i));
            boolean javaResult = GameAssetDownloadTask.shouldDownloadAsset(path, objects.get(i), true, null);
            boolean nativeResult = GameAssetDownloadTask.shouldDownloadAsset(path, objects.get(i), true, observations.get(i));
            assertEquals(javaResult, nativeResult, "asset index " + i);
        }
        assertFalse(GameAssetDownloadTask.shouldDownloadAsset(repo.getLayout().getAssetObject(good), good, true, observations.get(0)));
        assertTrue(GameAssetDownloadTask.shouldDownloadAsset(repo.getLayout().getAssetObject(bad), bad, true, observations.get(1)));
    }

    /// Duplicate logical assets are hashed once but keep observations for every original index.
    @Test void duplicateAssetsShareOneNativeInspection() throws Exception {
        configureNative(); TestRepository repo = new TestRepository(tempDir.resolve("game"));
        AssetObject asset = new AssetObject(ABC, 3); writeAsset(repo, asset, "abc");
        List<AssetObject> objects = Collections.nCopies(1000, asset);
        List<Long> progress = Collections.synchronizedList(new ArrayList<>());
        Map<Integer, NativeBackend.FileHashResult> observations = GameAssetDownloadTask.inspectAssetHashes(repo, objects, true,
                () -> false, (done, total) -> { assertEquals(1, total); progress.add(done); });
        assertEquals(1000, observations.size()); assertEquals(List.of(1L), progress);
        assertSame(observations.get(0), observations.get(999));
    }

    /// More than 500 unique assets are checked in bounded batches with aggregate progress.
    @Test void largeAssetSetUsesBoundedBatches() throws Exception {
        configureNative(); TestRepository repo = new TestRepository(tempDir.resolve("game"));
        List<AssetObject> objects = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            String data = "fixture-" + i;
            String hash = DigestUtils.digestToString("SHA-1", data.getBytes(StandardCharsets.UTF_8));
            AssetObject asset = new AssetObject(hash, data.length()); writeAsset(repo, asset, data); objects.add(asset);
        }
        List<Long> completed = Collections.synchronizedList(new ArrayList<>());
        Map<Integer, NativeBackend.FileHashResult> observations = GameAssetDownloadTask.inspectAssetHashes(repo, objects, true,
                () -> false, (done, total) -> { assertEquals(501, total); completed.add(done); });
        assertEquals(501, observations.size()); assertTrue(completed.contains(500L)); assertEquals(501L, completed.get(completed.size() - 1));
        for (int i = 0; i < objects.size(); i++) assertTrue(observations.get(i).matches());
    }

    /// Successful batches remain usable when a later backend request fails.
    @Test void failedLaterBatchKeepsEarlierResults() throws Exception {
        configuredNativeBinary();
        System.clearProperty("mfcl.native.pool.enabled");
        Path wrapper = tempDir.resolve("wrapper");
        Files.writeString(wrapper, """
                #!/usr/bin/env python3
                import json, os, subprocess, sys
                child = subprocess.Popen([os.environ['MFCL_NATIVE_TEST_EXECUTABLE']], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
                count = 0
                try:
                    for line in sys.stdin:
                        request = json.loads(line)
                        if request['method'] == 'check_files':
                            count += 1
                            if count > 1:
                                print(json.dumps({'version': 1, 'id': request['id'], 'error': {'code': 'fixture_failure', 'message': 'fixture'}}), flush=True)
                                continue
                        child.stdin.write(line)
                        child.stdin.flush()
                        response = child.stdout.readline()
                        if not response:
                            sys.exit(1)
                        sys.stdout.write(response)
                        sys.stdout.flush()
                finally:
                    child.stdin.close()
                    child.wait()
                """);
        assertTrue(wrapper.toFile().setExecutable(true)); System.setProperty("mfcl.native.path", wrapper.toString());
        TestRepository repo = new TestRepository(tempDir.resolve("game")); List<AssetObject> objects = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            String data = "asset-" + i; AssetObject asset = new AssetObject(DigestUtils.digestToString("SHA-1", data.getBytes(StandardCharsets.UTF_8)), data.length());
            writeAsset(repo, asset, data); objects.add(asset);
        }
        Map<Integer, NativeBackend.FileHashResult> observations = GameAssetDownloadTask.inspectAssetHashes(repo, objects, true, () -> false, null);
        assertEquals(500, observations.size()); assertFalse(observations.containsKey(500));
        assertFalse(GameAssetDownloadTask.shouldDownloadAsset(repo.getLayout().getAssetObject(objects.get(500)), objects.get(500), true, null));
    }

    /// Restoring mtime after changing bytes does not make a stale observation valid.
    @Test void changedAssetInvalidatesObservation() throws Exception {
        configureNative(); TestRepository repo = new TestRepository(tempDir.resolve("game"));
        AssetObject asset = new AssetObject(ABC, 3); Path path = writeAsset(repo, asset, "abc");
        NativeBackend.FileHashResult observation = java.util.Objects.requireNonNull(
                GameAssetDownloadTask.inspectAssetHashes(repo, List.of(asset), true, () -> false, null).get(0));
        FileTime oldTime = Files.getLastModifiedTime(path); Files.writeString(path, "xyz"); Files.setLastModifiedTime(path, oldTime);
        FileTime changed = (FileTime) Files.getAttribute(path, "unix:ctime");
        assumeTrue(changed.to(TimeUnit.NANOSECONDS) != observation.changedNanos());
        assertFalse(NativeBackend.isFileHashObservationCurrent(path, observation));
        assertTrue(GameAssetDownloadTask.shouldDownloadAsset(path, asset, true, observation));
    }

    /// Cancellation terminates the native inspection without changing the asset file.
    @Test void cancellationStopsInspection() throws Exception {
        Path script = tempDir.resolve("backend");
        Files.writeString(script, "#!/bin/sh\nread line\nprintf '%s' \"$$\" > \"$0.pid\"\nwhile :; do :; done\n");
        assertTrue(script.toFile().setExecutable(true)); System.setProperty("mfcl.native.path", script.toString());
        TestRepository repo = new TestRepository(tempDir.resolve("game")); AssetObject asset = new AssetObject(ABC, 3);
        Path file = writeAsset(repo, asset, "abc"); AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { GameAssetDownloadTask.inspectAssetHashes(repo, List.of(asset), true, cancelled::get, null); failure.set(new AssertionError("Expected cancellation")); }
            catch (Throwable e) { failure.set(e); }
        });
        worker.setDaemon(true); worker.start();
        try {
            Path pidFile = Path.of(script + ".pid"); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!Files.exists(pidFile) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(pidFile)); long pid = Long.parseLong(Files.readString(pidFile)); cancelled.set(true); worker.join(5000);
            assertFalse(worker.isAlive()); assertInstanceOf(InterruptedException.class, failure.get()); assertEquals("abc", Files.readString(file));
            ProcessHandle.of(pid).ifPresent(process -> assertDoesNotThrow(() -> process.onExit().get(3, TimeUnit.SECONDS)));
        } finally { cancelled.set(true); worker.interrupt(); worker.join(5000); }
    }

    /// Disabled native checking and disabled integrity checks avoid starting a backend.
    @Test void disabledCheckingKeepsJavaPath() throws Exception {
        Path script = tempDir.resolve("backend"); Files.writeString(script, "#!/bin/sh\n: > \"$0.called\"\nexit 1\n");
        assertTrue(script.toFile().setExecutable(true)); System.setProperty("mfcl.native.path", script.toString());
        TestRepository repo = new TestRepository(tempDir.resolve("game")); AssetObject asset = new AssetObject(ABC, 3); writeAsset(repo, asset, "abc");
        System.setProperty("mfcl.native.assets.hash.enabled", "false");
        assertTrue(GameAssetDownloadTask.inspectAssetHashes(repo, List.of(asset), true, () -> false, null).isEmpty());
        System.clearProperty("mfcl.native.assets.hash.enabled");
        assertTrue(GameAssetDownloadTask.inspectAssetHashes(repo, List.of(asset), false, () -> false, null).isEmpty());
        assertFalse(Files.exists(Path.of(script + ".called")));
    }

    /// Configures the explicitly supplied native test executable.
    private void configureNative() { System.setProperty("mfcl.native.path", configuredNativeBinary().toString()); System.clearProperty("mfcl.native.assets.hash.enabled"); }
    /// Returns the native test executable or skips the test.
    /// @return executable path
    private static Path configuredNativeBinary() {
        @Nullable String path = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"); assumeTrue(path != null && !path.isBlank()); return Path.of(path).toAbsolutePath().normalize();
    }
    /// Writes an asset fixture inside the isolated repository.
    /// @param repository owning repository
    /// @param asset asset descriptor
    /// @param content file contents
    /// @return written path
    /// @throws IOException if writing fails
    private static Path writeAsset(TestRepository repository, AssetObject asset, String content) throws IOException {
        Path path = repository.getLayout().getAssetObject(asset); Files.createDirectories(path.getParent()); Files.writeString(path, content); return path;
    }
    /// Minimal repository used for isolated asset paths.
    private static final class TestRepository extends DefaultGameRepository {
        /// Creates the repository.
        /// @param root fixture root
        private TestRepository(Path root) { super(root); }
        /// Creates the standard layout.
        @Override protected DefaultGameRepositoryLayout createLayout(Path root) { return new DefaultGameRepositoryLayout(root); }
        /// No instance is created in asset hash tests.
        @Override protected DefaultGameInstance createInstance(DefaultGameRepositorySnapshot snapshot, GameInstanceID id,
                                                               GameInstanceManifest manifest, @Nullable Path manifestFile) {
            throw new UnsupportedOperationException("No instance expected");
        }
    }
}
