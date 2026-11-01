/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.game;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.JavaFXLauncher;
import org.jackhuang.hmcl.download.DefaultCacheRepository;
import org.jackhuang.hmcl.download.DefaultDependencyManager;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.game.AssetObject;
import org.jackhuang.hmcl.game.DefaultGameInstance;
import org.jackhuang.hmcl.game.DefaultGameRepository;
import org.jackhuang.hmcl.game.DefaultGameRepositoryLayout;
import org.jackhuang.hmcl.game.DefaultGameRepositorySnapshot;
import org.jackhuang.hmcl.game.GameInstanceID;
import org.jackhuang.hmcl.game.GameInstanceManifest;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Exercises the actual asset task with Rust and Java against isolated repository fixtures.
@NotNullByDefault
@EnabledOnOs(OS.LINUX)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
public final class NativeAssetTaskIntegrationTest {
    /// Isolated repository and cache directory.
    @TempDir Path tempDir;
    /// Previous backend configuration.
    private final @Nullable String previousPath = System.getProperty("mfcl.native.path");
    /// Previous native index setting.
    private final @Nullable String previousIndexEnabled = System.getProperty("mfcl.native.assets.enabled");
    /// Previous native hash setting.
    private final @Nullable String previousHashEnabled = System.getProperty("mfcl.native.assets.hash.enabled");
    /// Previous JavaFX renderer setting.
    private final @Nullable String previousPrism = System.getProperty("prism.order");

    /// Restores settings after each test.
    @AfterEach void restore() {
        restoreProperty("mfcl.native.path", previousPath);
        restoreProperty("mfcl.native.assets.enabled", previousIndexEnabled);
        restoreProperty("mfcl.native.assets.hash.enabled", previousHashEnabled);
        restoreProperty("prism.order", previousPrism);
    }

    /// Native planning preserves the Java task's cache entries and pending download set.
    @Test @Timeout(20) void nativeAndJavaAssetTasksProduceSameWork() throws Exception {
        @Nullable String nativeBinary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(nativeBinary != null && !nativeBinary.isBlank());
        System.setProperty("prism.order", "sw");
        JavaFXLauncher.start(); assumeTrue(JavaFXLauncher.isStarted(), "JavaFX toolkit unavailable");
        TestRepository repository = new TestRepository(tempDir.resolve("game"));
        AssetObject good = asset("abc"); AssetObject bad = asset("expected"); AssetObject missing = asset("missing");
        writeAsset(repository, good, "abc"); writeAsset(repository, bad, "incorrect");
        JsonObject objects = new JsonObject();
        objects.add("good", assetJson(good)); objects.add("duplicate", assetJson(good));
        objects.add("bad", assetJson(bad)); objects.add("missing", assetJson(missing));
        JsonObject index = new JsonObject(); index.add("objects", objects);
        Path indexFile = repository.getLayout().getAssetIndexFile("fixture");
        Files.createDirectories(indexFile.getParent()); Files.writeString(indexFile, index.toString());
        GameInstanceManifest manifest = GameInstanceManifest.fromJson(JsonParser.parseString(
                "{\"id\":\"fixture\",\"assetIndex\":{\"id\":\"fixture\",\"url\":\"https://example.invalid/index\"}}"
        ).getAsJsonObject(), false);
        Path wrapper = nativeWrapper(nativeBinary);
        System.setProperty("mfcl.native.path", wrapper.toString());
        System.clearProperty("mfcl.native.assets.enabled"); System.clearProperty("mfcl.native.assets.hash.enabled");
        DefaultCacheRepository nativeCache = new DefaultCacheRepository(tempDir.resolve("native-cache"));
        GameAssetDownloadTask nativeTask = task(repository, nativeCache, manifest);
        nativeTask.execute();
        List<String> nativePending = pendingHashes(nativeTask);
        assertEquals(List.of(bad.hash(), missing.hash()).stream().sorted().toList(), nativePending);
        assertTrue(Files.exists(Path.of(wrapper + ".combined")), "Combined Rust asset planning was not used");
        assertEquals("abc", Files.readString(cachePath(nativeCache, good.hash())));
        System.setProperty("mfcl.native.assets.enabled", "false"); System.setProperty("mfcl.native.assets.hash.enabled", "false");
        DefaultCacheRepository javaCache = new DefaultCacheRepository(tempDir.resolve("java-cache"));
        GameAssetDownloadTask javaTask = task(repository, javaCache, manifest);
        javaTask.execute();
        assertEquals(nativePending, pendingHashes(javaTask));
        assertEquals("abc", Files.readString(cachePath(javaCache, good.hash())));
        assertEquals("abc", Files.readString(repository.getLayout().getAssetObject(good)));
        assertEquals("incorrect", Files.readString(repository.getLayout().getAssetObject(bad)));
        assertFalse(Files.exists(repository.getLayout().getAssetObject(missing)));
    }

    /// Rechecks changed and newly created files before applying a native installation plan.
    @Test @Timeout(20) void nativePlanRechecksFilesystemChanges() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        TestRepository repository = new TestRepository(tempDir.resolve("game"));
        AssetObject good = asset("abc"), bad = asset("expected"), missing = asset("missing");
        writeAsset(repository, good, "abc"); writeAsset(repository, bad, "incorrect");
        JsonObject objects = new JsonObject();
        objects.add("good", assetJson(good)); objects.add("duplicate", assetJson(good));
        objects.add("bad", assetJson(bad)); objects.add("missing", assetJson(missing));
        JsonObject index = new JsonObject(); index.add("objects", objects);
        Path indexFile = repository.getLayout().getAssetIndexFile("fixture");
        Files.createDirectories(indexFile.getParent()); Files.writeString(indexFile, index.toString());
        @Unmodifiable List<NativeBackend.AssetInstallEntry> plan = NativeBackend.planAssetInstall(Path.of(executable), indexFile,
                repository.getLayout().getAssetDirectory(), true, () -> false, (done, total) -> { });
        assertEquals(4, plan.size());
        var goodEntry = plan.stream().filter(item -> item.asset().hash().equals(good.hash())).findFirst().orElseThrow();
        var badEntry = plan.stream().filter(item -> item.asset().hash().equals(bad.hash())).findFirst().orElseThrow();
        var missingEntry = plan.stream().filter(item -> item.asset().hash().equals(missing.hash())).findFirst().orElseThrow();
        assertNotNull(goodEntry.observation()); assertTrue(goodEntry.observation().matches());
        assertTrue(GameAssetDownloadTask.shouldDownloadPlannedAsset(repository.getLayout().getAssetObject(bad), badEntry, true));
        assertTrue(missingEntry.missing());
        writeAsset(repository, missing, "missing");
        assertFalse(GameAssetDownloadTask.shouldDownloadPlannedAsset(repository.getLayout().getAssetObject(missing), missingEntry, true));
        writeAsset(repository, good, "changed-content");
        assertTrue(GameAssetDownloadTask.shouldDownloadPlannedAsset(repository.getLayout().getAssetObject(good), goodEntry, true));
    }

    /// Custom asset locations and disabled hashing retain the existing Java workflow.
    @Test void customLayoutAndDisabledHashingSkipCombinedPlan() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        System.setProperty("mfcl.native.path", executable);
        System.clearProperty("mfcl.native.assets.enabled"); System.clearProperty("mfcl.native.assets.hash.enabled");
        CustomAssetRepository custom = new CustomAssetRepository(tempDir.resolve("custom-game"));
        Path index = tempDir.resolve("index.json"); Files.writeString(index, "{\"objects\":{}}");
        assertNull(GameAssetDownloadTask.tryNativeInstallPlan(custom, index, true, () -> false, (done, total) -> { }));
        System.setProperty("mfcl.native.assets.hash.enabled", "false");
        TestRepository standard = new TestRepository(tempDir.resolve("standard-game"));
        assertNull(GameAssetDownloadTask.tryNativeInstallPlan(standard, index, true, () -> false, (done, total) -> { }));
    }

    /// Creates a task without executing its network dependencies.
    /// @param repository isolated game repository
    /// @param cache isolated cache
    /// @param manifest synthetic manifest
    /// @return asset installation task
    private static GameAssetDownloadTask task(TestRepository repository, DefaultCacheRepository cache, GameInstanceManifest manifest) {
        return new GameAssetDownloadTask(new DefaultDependencyManager(repository, new DownloadProvider(), cache), manifest, false, true);
    }

    /// Returns sorted hashes of the file-download dependencies without running them.
    /// @param task asset task
    /// @return pending asset hashes
    private static List<String> pendingHashes(GameAssetDownloadTask task) {
        return task.getDependencies().stream().map(Task::getDependents).map(items -> items.iterator().next())
                .map(item -> ((FileDownloadTask) item).getName()).sorted().toList();
    }

    /// Constructs an asset from synthetic content.
    /// @param content expected file content
    /// @return corresponding asset descriptor
    private static AssetObject asset(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new AssetObject(DigestUtils.digestToString("SHA-1", bytes), bytes.length);
    }

    /// Converts an asset descriptor into an index entry.
    /// @param asset asset descriptor
    /// @return JSON index entry
    private static JsonObject assetJson(AssetObject asset) {
        JsonObject value = new JsonObject(); value.addProperty("hash", asset.hash()); value.addProperty("size", asset.size()); return value;
    }

    /// Writes a synthetic asset file.
    /// @param repository isolated repository
    /// @param asset asset descriptor
    /// @param content actual contents
    /// @throws IOException if writing fails
    private static void writeAsset(TestRepository repository, AssetObject asset, String content) throws IOException {
        Path file = repository.getLayout().getAssetObject(asset); Files.createDirectories(file.getParent()); Files.writeString(file, content);
    }

    /// Returns the expected content-addressed cache path.
    /// @param cache cache repository
    /// @param hash asset SHA-1
    /// @return cache file path
    private static Path cachePath(DefaultCacheRepository cache, String hash) {
        return cache.getCacheDirectory().resolve("SHA-1").resolve(hash.substring(0, 2)).resolve(hash);
    }

    /// Creates a wrapper that records invoked methods and forwards one request to real Rust.
    /// @param nativeBinary native executable path
    /// @return wrapper executable
    /// @throws IOException if writing fails
    private Path nativeWrapper(String nativeBinary) throws IOException {
        Path wrapper = tempDir.resolve("native-wrapper");
        Files.writeString(wrapper, """
                #!/usr/bin/env python3
                import json, pathlib, subprocess, sys
                native = %s
                line = sys.stdin.readline()
                request = json.loads(line)
                child = subprocess.Popen([native], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
                child.stdin.write(line)
                child.stdin.close()
                for response in child.stdout:
                    frame = json.loads(response)
                    if request.get('method') == 'plan_asset_install' and 'result' in frame:
                        pathlib.Path(__file__ + '.combined').touch()
                    sys.stdout.write(response)
                    sys.stdout.flush()
                sys.exit(child.wait())
                """.formatted(new com.google.gson.JsonPrimitive(nativeBinary).toString()));
        if (!wrapper.toFile().setExecutable(true)) throw new IOException("Cannot mark wrapper executable");
        return wrapper;
    }

    /// Restores one optional system property.
    /// @param key property name
    /// @param value previous value or null
    private static void restoreProperty(String key, @Nullable String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }

    /// Minimal isolated repository for the asset task.
    private static final class TestRepository extends DefaultGameRepository {
        /// Creates the repository.
        /// @param root fixture root
        private TestRepository(Path root) { super(root); }
        /// Creates the standard layout.
        @Override protected DefaultGameRepositoryLayout createLayout(Path root) { return new DefaultGameRepositoryLayout(root); }
        /// No instance is materialized by this task.
        @Override protected DefaultGameInstance createInstance(DefaultGameRepositorySnapshot snapshot, GameInstanceID id,
                                                               GameInstanceManifest manifest, @Nullable Path manifestFile) {
            throw new UnsupportedOperationException("No instance expected");
        }
    }

    /// Repository fixture whose asset-object layout deliberately differs from the default.
    private static final class CustomAssetRepository extends DefaultGameRepository {
        /// Creates a repository with a custom asset location.
        /// @param root fixture root
        private CustomAssetRepository(Path root) { super(root); }
        /// Creates a layout overriding only asset-object resolution.
        /// @param root repository root
        /// @return custom layout
        @Override protected DefaultGameRepositoryLayout createLayout(Path root) {
            return new DefaultGameRepositoryLayout(root) {
                /// Resolves objects into the fixture's custom directory.
                /// @param object asset descriptor
                /// @return custom asset path
                @Override public Path getAssetObject(AssetObject object) { return root.resolve("custom-assets").resolve(object.hash()); }
            };
        }
        /// This fixture does not materialize game instances.
        @Override protected DefaultGameInstance createInstance(DefaultGameRepositorySnapshot snapshot, GameInstanceID id,
                                                               GameInstanceManifest manifest, @Nullable Path manifestFile) {
            throw new UnsupportedOperationException("No instance expected");
        }
    }
}
