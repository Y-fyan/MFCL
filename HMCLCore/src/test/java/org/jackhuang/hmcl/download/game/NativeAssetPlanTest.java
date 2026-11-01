/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.game;

import com.google.gson.JsonObject;
import org.jackhuang.hmcl.game.AssetObject;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Verifies native asset index planning against the existing Java parser.
@NotNullByDefault
@EnabledOnOs({OS.LINUX, OS.MAC})
@ResourceLock(Resources.SYSTEM_PROPERTIES)
public final class NativeAssetPlanTest {
    /// Temporary directory for isolated indexes and executables.
    @TempDir Path tempDir;
    /// Saved native executable configuration.
    private final @Nullable String previousPath = System.getProperty("mfcl.native.path");
    /// Saved native asset parsing setting.
    private final @Nullable String previousEnabled = System.getProperty("mfcl.native.assets.enabled");
    /// Sample asset hash.
    private static final String HASH = "a9993e364706816aba3e25717850c26c9cd0d89d";

    /// Cached index checks use the native digest and retain the Java fallback.
    @Test void cachedIndexHashUsesNativeBackend() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        @Nullable String previousPool = System.getProperty("mfcl.native.pool.enabled");
        @Nullable String previousHash = System.getProperty("mfcl.native.assets.hash.enabled");
        Path wrapper = tempDir.resolve("hash-backend"), index = tempDir.resolve("index.json");
        Files.writeString(index, "abc");
        Files.writeString(wrapper, "#!/bin/sh\nIFS= read -r line\ncase \"$line\" in *hash_cache_sha1*) : > \"$0.called\" ;; esac\n"
                + "printf '%s\\n' \"$line\" | exec '" + executable.replace("'", "'\\''") + "'\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        try {
            System.setProperty("mfcl.native.path", wrapper.toString());
            System.clearProperty("mfcl.native.assets.enabled"); System.clearProperty("mfcl.native.assets.hash.enabled");
            System.setProperty("mfcl.native.pool.enabled", "false");
            assertEquals(HASH, GameAssetIndexDownloadTask.hashIndex(index));
            assertTrue(Files.exists(Path.of(wrapper + ".called")));
            System.setProperty("mfcl.native.path", tempDir.resolve("missing").toString());
            assertEquals(HASH, GameAssetIndexDownloadTask.hashIndex(index));
        } finally {
            if (previousPool == null) System.clearProperty("mfcl.native.pool.enabled"); else System.setProperty("mfcl.native.pool.enabled", previousPool);
            if (previousHash == null) System.clearProperty("mfcl.native.assets.hash.enabled"); else System.setProperty("mfcl.native.assets.hash.enabled", previousHash);
        }
    }

    /// Restores global settings.
    @AfterEach void restore() {
        if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
        if (previousEnabled == null) System.clearProperty("mfcl.native.assets.enabled"); else System.setProperty("mfcl.native.assets.enabled", previousEnabled);
    }

    /// The real native backend agrees with Java for duplicates and a response exceeding 64 KiB.
    @Test void nativePlanMatchesJavaForLargeIndex() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        Path index = tempDir.resolve("资源.json");
        JsonObject entries = new JsonObject();
        for (int i = 0; i < 2500; i++) {
            JsonObject item = new JsonObject(); item.addProperty("hash", HASH); item.addProperty("size", i);
            entries.add("asset/" + i, item);
        }
        JsonObject root = new JsonObject(); root.add("objects", entries); Files.writeString(index, root.toString());
        System.setProperty("mfcl.native.path", executable);
        System.setProperty("mfcl.native.assets.enabled", "false");
        List<AssetObject> javaObjects = GameAssetDownloadTask.loadAssetObjects(index, () -> false);
        System.clearProperty("mfcl.native.assets.enabled");
        List<AssetObject> directNative = NativeBackend.planAssets(Path.of(executable), index, () -> false);
        List<AssetObject> selected = GameAssetDownloadTask.loadAssetObjects(index, () -> false);
        assertEquals(2500, directNative.size()); assertEquals(2500, selected.size());
        assertEquals(javaObjects.stream().sorted(java.util.Comparator.comparingLong(AssetObject::size)).toList(),
                selected.stream().sorted(java.util.Comparator.comparingLong(AssetObject::size)).toList());
    }

    /// Invalid native output causes fallback to the existing Java index parser.
    @Test void invalidNativePlanFallsBack() throws Exception {
        Path script = script("printf '%s\\n' '{\"version\":1,\"id\":1,\"result\":{\"objects\":[{\"hash\":\"bad\",\"size\":3}]}}'");
        Path index = tempDir.resolve("index.json");
        Files.writeString(index, "{\"objects\":{\"a\":{\"hash\":\"" + HASH + "\",\"size\":3}}}");
        System.setProperty("mfcl.native.path", script.toString());
        assertEquals(List.of(new AssetObject(HASH, 3)), GameAssetDownloadTask.loadAssetObjects(index, () -> false));
    }

    /// An index accepted only by the legacy parser still falls back to Java.
    @Test void legacyHashFallsBackToJava() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        Path index = tempDir.resolve("legacy.json");
        Files.writeString(index, "{\"objects\":{\"legacy\":{\"hash\":\"ab\",\"size\":1}}}");
        System.setProperty("mfcl.native.path", executable);
        assertEquals(List.of(new AssetObject("ab", 1)), GameAssetDownloadTask.loadAssetObjects(index, () -> false));
    }

    /// Duplicate JSON keys retain Gson's legacy behavior rather than being silently collapsed by Rust.
    @Test void duplicateFieldsFallBackToJavaParser() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        Path index = tempDir.resolve("duplicate.json");
        Files.writeString(index, "{\"objects\":{\"entry\":{\"hash\":\"0000000000000000000000000000000000000000\",\"hash\":\"" + HASH + "\",\"size\":3}}}");
        System.setProperty("mfcl.native.path", executable); System.clearProperty("mfcl.native.assets.enabled");
        assertEquals(List.of(new AssetObject(HASH, 3)), GameAssetDownloadTask.loadAssetObjects(index, () -> false));
    }

    /// Symbolic links followed by `..` resolve through the filesystem rather than lexically.
    @Test void nativePlansPreserveSymlinkTraversal() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        Path base = tempDir.resolve("base"), target = tempDir.resolve("target"), child = target.resolve("child");
        Files.createDirectories(base); Files.createDirectories(child); Files.createSymbolicLink(base.resolve("link"), child);
        String actual = "{\"objects\":{\"entry\":{\"hash\":\"" + HASH + "\",\"size\":3}}}";
        String wrong = "{\"objects\":{\"entry\":{\"hash\":\"0000000000000000000000000000000000000000\",\"size\":3}}}";
        Files.writeString(target.resolve("index.json"), actual); Files.writeString(base.resolve("index.json"), wrong);
        Path index = base.resolve("link").resolve("..").resolve("index.json");
        Path root = base.resolve("link").resolve("..").resolve("assets");
        Path object = root.resolve("objects").resolve(HASH.substring(0, 2)).resolve(HASH);
        Files.createDirectories(target.resolve("assets").resolve("objects").resolve(HASH.substring(0, 2)));
        Files.writeString(object, "abc");
        assertEquals(List.of(new AssetObject(HASH, 3)), NativeBackend.planAssets(Path.of(executable), index, () -> false));
        @Unmodifiable List<NativeBackend.AssetInstallEntry> plan = NativeBackend.planAssetInstall(Path.of(executable), index, root,
                true, () -> false, (completed, total) -> { });
        assertEquals(1, plan.size()); assertEquals(HASH, plan.get(0).asset().hash());
        @Nullable NativeBackend.FileHashResult observation = plan.get(0).observation();
        assertNotNull(observation); assertTrue(observation.matches());
        assertTrue(NativeBackend.isFileHashObservationCurrent(object, observation));
    }

    /// Invalid JSON retains the existing malformed-index exception.
    @Test void malformedIndexRetainsExistingError() throws Exception {
        Path index = tempDir.resolve("bad.json"); Files.writeString(index, "{");
        System.setProperty("mfcl.native.path", tempDir.resolve("missing").toString());
        assertThrows(GameAssetIndexDownloadTask.GameAssetIndexMalformedException.class,
                () -> GameAssetDownloadTask.loadAssetObjects(index, () -> false));
    }

    /// Cancellation terminates a native process while an index plan is being generated.
    @Test void cancellationStopsNativePlanner() throws Exception {
        Path script = script("printf '%s' \"$$\" > \"$0.pid\"\nwhile :; do :; done");
        Path index = tempDir.resolve("index.json"); Files.writeString(index, "{\"objects\":{}}");
        System.setProperty("mfcl.native.path", script.toString());
        AtomicBoolean cancelled = new AtomicBoolean(); AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try { GameAssetDownloadTask.loadAssetObjects(index, cancelled::get); failure.set(new AssertionError("Expected cancellation")); }
            catch (Throwable e) { failure.set(e); }
        });
        thread.setDaemon(true); thread.start();
        try {
            Path pidFile = Path.of(script + ".pid"); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!Files.exists(pidFile) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(pidFile)); long pid = Long.parseLong(Files.readString(pidFile));
            cancelled.set(true); thread.join(5000); assertFalse(thread.isAlive());
            assertInstanceOf(InterruptedException.class, failure.get());
            ProcessHandle.of(pid).ifPresent(process -> assertDoesNotThrow(() -> process.onExit().get(3, TimeUnit.SECONDS)));
        } finally { cancelled.set(true); thread.interrupt(); thread.join(5000); }
    }

    /// Creates a shell fixture that consumes one request before executing its body.
    ///
    /// @param body script body
    /// @return fixture executable
    /// @throws IOException if creation fails
    private Path script(String body) throws IOException {
        Path path=tempDir.resolve("backend"); Files.writeString(path, "#!/bin/sh\nread line\n" + body + "\n");
        if (!path.toFile().setExecutable(true)) throw new IOException("Cannot mark test executable");
        return path;
    }
}
