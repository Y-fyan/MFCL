/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download;

import org.jackhuang.hmcl.download.fabric.FabricInstallTask;
import org.jackhuang.hmcl.download.legacyfabric.LegacyFabricInstallTask;
import org.jackhuang.hmcl.download.quilt.QuiltInstallTask;
import org.jackhuang.hmcl.game.GameComponentType;
import org.jackhuang.hmcl.game.GameInstancePatch;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.versioning.GameVersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Compares native Fabric-family patch plans with each existing Java installer.
@NotNullByDefault
@EnabledOnOs({OS.LINUX, OS.MAC})
@ResourceLock(Resources.SYSTEM_PROPERTIES)
public final class NativeLoaderPlannerTest {
    /// Temporary backend scripts.
    @TempDir Path temp;
    /// Previous backend configuration.
    private final @Nullable String previousPath = System.getProperty("mfcl.native.path");
    /// Previous native loader setting.
    private final @Nullable String previousEnabled = System.getProperty("mfcl.native.loaders.enabled");

    /// Restores process-global settings.
    @AfterEach void restore() {
        if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
        if (previousEnabled == null) System.clearProperty("mfcl.native.loaders.enabled"); else System.setProperty("mfcl.native.loaders.enabled", previousEnabled);
    }

    /// Standard and LaunchWrapper metadata agree for Fabric, Legacy Fabric and Quilt.
    @Test void nativePlansMatchAllJavaInstallers() throws Exception {
        configureNative();
        String base = """
                {"loader":{"maven":"org.quiltmc:quilt-loader:0.15"},
                 "intermediary":{"maven":"net.legacyfabric:intermediary:1.20.1"},
                 "launcherMeta":{"mainClass":{"client":"example.Client"},
                   "libraries":{"common":[{"name":"org.test:one:1","url":"https://example.invalid/"}],
                     "server":[{"name":"org.test:two:2"}],"client":[{"name":"org.test:ignored:3"}]}}}
                """;
        String wrapper = base.replace("\"libraries\":{", "\"launchwrapper\":{\"tweakers\":{\"client\":[\"example.Tweaker\"]}},\"libraries\":{");
        String quiltWithoutIntermediary = base.replace("\"intermediary\":{\"maven\":\"net.legacyfabric:intermediary:1.20.1\"},", "");
        for (GameComponentType kind : List.of(GameComponentType.FABRIC, GameComponentType.LEGACY_FABRIC, GameComponentType.QUILT)) {
            for (String metadata : List.of(base, wrapper)) assertEquivalent(kind, metadata);
        }
        assertEquivalent(GameComponentType.QUILT, quiltWithoutIntermediary);
    }

    /// An official Fabric metadata snapshot is compared without contacting the network.
    @Test void officialFabricMetadataMatchesJava() throws Exception {
        configureNative();
        try (var input = NativeLoaderPlannerTest.class.getResourceAsStream("/loader-metadata/fabric-1.20.1-0.15.11.json")) {
            assertNotNull(input);
            String metadata = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertEquivalent(GameComponentType.FABRIC, metadata);
            var patch = java.util.Objects.requireNonNull(NativeLoaderPlanner.tryPlan(GameComponentType.FABRIC, metadata, "0.15.11", () -> false));
            var mixin = patch.getLibraries().stream().filter(lib -> lib.is("net.fabricmc", "sponge-mixin")).findFirst().orElseThrow();
            assertEquals("9527e6b0d2449408958fd1302594dc65ec5ade9c", mixin.getDownload().getSha1());
            assertEquals(1458875, mixin.getDownload().getSize());
        }
    }

    /// Explicit nested download data wins; malformed top-level hashes are ignored.
    @Test void publishedHashesRespectExplicitDownloads() {
        var explicit = com.google.gson.JsonParser.parseString("{\"name\":\"org.test:lib:1\",\"sha1\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"downloads\":{\"artifact\":{\"url\":\"https://example.invalid/lib\",\"sha1\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\"}}}");
        var library = java.util.Objects.requireNonNull(LoaderLibraryParser.parse(explicit));
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", library.getDownload().getSha1());
        var invalid = com.google.gson.JsonParser.parseString("{\"name\":\"org.test:lib:1\",\"sha1\":\"not-a-sha1\"}");
        assertNull(java.util.Objects.requireNonNull(LoaderLibraryParser.parse(invalid)).getDownload().getSha1());
    }

    /// Real Fabric coordinates, including the Mixin version containing '+', enter the native classpath.
    @Test void fabricMixinAppearsInNativeClasspath() throws Exception {
        configureNative();
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        try (var input = NativeLoaderPlannerTest.class.getResourceAsStream("/loader-metadata/fabric-1.20.1-0.15.11.json")) {
            assertNotNull(input);
            GameInstancePatch patch = java.util.Objects.requireNonNull(NativeLoaderPlanner.tryPlan(GameComponentType.FABRIC,
                    new String(input.readAllBytes(), StandardCharsets.UTF_8), "0.15.11", () -> false));
            var id = new org.jackhuang.hmcl.game.GameInstanceID("fixture");
            var layout = new org.jackhuang.hmcl.game.DefaultGameRepositoryLayout(temp.resolve("game"));
            for (var library : patch.getLibraries()) {
                Path file = layout.getLibraryFile(id, library); Files.createDirectories(file.getParent()); Files.writeString(file, "fixture");
            }
            var mixin = patch.getLibraries().stream().filter(lib -> lib.is("net.fabricmc", "sponge-mixin")).findFirst().orElseThrow();
            Path path = layout.getLibraryFile(id, mixin).toAbsolutePath().normalize();
            assertTrue(org.jackhuang.hmcl.nativebridge.NativeBackend.planClasspath(Path.of(java.util.Objects.requireNonNull(executable)),
                    layout, id, patch.getLibraries(), false, false).contains(path.toString()));
        }
    }

    /// An official Quilt metadata snapshot is compared without contacting the network.
    @Test void officialQuiltMetadataMatchesJava() throws Exception {
        configureNative();
        try (var input = NativeLoaderPlannerTest.class.getResourceAsStream("/loader-metadata/quilt-1.20.1-0.24.0.json")) {
            assertNotNull(input);
            assertEquivalent(GameComponentType.QUILT, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /// Invalid or unsupported metadata returns control to the existing Java parser.
    @Test void malformedMetadataAndDisabledFeatureUseFallback() throws Exception {
        configureNative();
        assertNull(NativeLoaderPlanner.tryPlan(GameComponentType.FABRIC, "{", "1", () -> false));
        assertNull(NativeLoaderPlanner.tryPlan(GameComponentType.QUILT,
                "{\"loader\":{\"maven\":\"a:b:c\"},\"launcherMeta\":{\"mainClass\":\"A\",\"libraries\":{\"common\":[{\"name\":\"a:b:c\",\"size\":1.5}],\"server\":[]}}}", "1", () -> false));
        System.setProperty("mfcl.native.loaders.enabled", "false");
        assertNull(NativeLoaderPlanner.tryPlan(GameComponentType.FABRIC, "{}", "1", () -> false));
    }

    /// Cancelling an in-flight request terminates the native child.
    @Test void cancellationStopsNativePlanner() throws Exception {
        Path wrapper = temp.resolve("backend");
        Files.writeString(wrapper, "#!/bin/sh\nread line\nprintf '%s' \"$$\" > \"$0.pid\"\nwhile :; do :; done\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        System.setProperty("mfcl.native.path", wrapper.toString()); System.clearProperty("mfcl.native.loaders.enabled");
        AtomicBoolean cancelled = new AtomicBoolean(); AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { NativeLoaderPlanner.tryPlan(GameComponentType.FABRIC, "{}", "1", cancelled::get); failure.set(new AssertionError("Expected cancellation")); }
            catch (Throwable error) { failure.set(error); }
        });
        worker.setDaemon(true); worker.start();
        try {
            Path pidFile = Path.of(wrapper + ".pid"); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!Files.exists(pidFile) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(pidFile)); long pid = Long.parseLong(Files.readString(pidFile));
            cancelled.set(true); worker.join(5000); assertFalse(worker.isAlive());
            assertInstanceOf(InterruptedException.class, failure.get());
            ProcessHandle.of(pid).ifPresent(process -> assertDoesNotThrow(() -> process.onExit().get(3, TimeUnit.SECONDS)));
        } finally { cancelled.set(true); worker.interrupt(); worker.join(5000); }
    }

    /// Selects the real backend from the test environment.
    private void configureNative() {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        System.setProperty("mfcl.native.path", executable); System.clearProperty("mfcl.native.loaders.enabled");
    }

    /// Compares complete serialized patches, including full library metadata and ordering.
    /// @param kind loader kind
    /// @param metadata loader JSON
    /// @throws Exception if either planner fails
    private static void assertEquivalent(GameComponentType kind, String metadata) throws Exception {
        GameInstancePatch actual = java.util.Objects.requireNonNull(NativeLoaderPlanner.tryPlan(kind, metadata, "0.15.11", () -> false));
        GameInstancePatch expected;
        if (kind == GameComponentType.QUILT) {
            var info = JsonUtils.GSON.fromJson(metadata, QuiltInstallTask.QuiltInfo.class);
            Method method = QuiltInstallTask.class.getDeclaredMethod("getPatch", QuiltInstallTask.QuiltInfo.class, String.class);
            method.setAccessible(true); expected = (GameInstancePatch) method.invoke(null, info, "0.15.11");
        } else {
            var info = JsonUtils.GSON.fromJson(metadata, FabricInstallTask.FabricInfo.class);
            Class<?> owner = kind == GameComponentType.FABRIC ? FabricInstallTask.class : LegacyFabricInstallTask.class;
            Method method = owner.getDeclaredMethod("getPatch", FabricInstallTask.FabricInfo.class, GameVersionNumber.class, String.class);
            method.setAccessible(true); expected = (GameInstancePatch) method.invoke(null, info, GameVersionNumber.asGameVersion("1.20.1"), "0.15.11");
        }
        assertEquals(expected.toJsonObject(), actual.toJsonObject(), kind.name());
    }
}
