/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download;

import com.sun.net.httpserver.HttpServer;
import org.jackhuang.hmcl.JavaFXLauncher;
import org.jackhuang.hmcl.download.fabric.FabricRemoteVersion;
import org.jackhuang.hmcl.download.legacyfabric.LegacyFabricRemoteVersion;
import org.jackhuang.hmcl.download.quilt.QuiltRemoteVersion;
import org.jackhuang.hmcl.game.*;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.util.versioning.GameVersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Exercises loader installation when required library downloads fail.
@NotNullByDefault
@EnabledOnOs({OS.LINUX, OS.MAC})
@ResourceLock(Resources.SYSTEM_PROPERTIES)
public final class LoaderDependencyFailureTest {
    /// Isolated fixture root.
    @TempDir Path temp;

    /// Failed required library downloads fail each Fabric-family installer.
    @Test void missingLoaderLibrariesFailInstallation() throws Exception {
        @Nullable String previousPrism = System.getProperty("prism.order");
        System.setProperty("prism.order", "sw"); JavaFXLauncher.start();
        if (!JavaFXLauncher.isStarted()) {
            if (previousPrism == null) System.clearProperty("prism.order"); else System.setProperty("prism.order", previousPrism);
        }
        assumeTrue(JavaFXLauncher.isStarted(), "JavaFX toolkit unavailable");
        @Nullable String previousPath = System.getProperty("mfcl.native.path");
        @Nullable String previousEnabled = System.getProperty("mfcl.native.loaders.enabled");
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        if (executable != null && !executable.isBlank()) System.setProperty("mfcl.native.path", executable);
        System.clearProperty("mfcl.native.loaders.enabled");
        AtomicInteger missingRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] metadata = ("{\"loader\":{\"maven\":\"net.fabricmc:fabric-loader:1\"}," +
                "\"intermediary\":{\"maven\":\"net.fabricmc:intermediary:1\"}," +
                "\"launcherMeta\":{\"mainClass\":\"example.Client\",\"libraries\":{\"common\":[{\"name\":\"org.test:missing:1\"}],\"server\":[]}}}")
                .getBytes(StandardCharsets.UTF_8);
        server.createContext("/meta", exchange -> {
            exchange.sendResponseHeaders(200, metadata.length);
            try (var output = exchange.getResponseBody()) { output.write(metadata); }
        });
        server.createContext("/missing", exchange -> {
            missingRequests.incrementAndGet(); exchange.sendResponseHeaders(404, -1); exchange.close();
        });
        server.start();
        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            GameVersionNumber version = GameVersionNumber.asGameVersion("1.20.1");
            List<ComponentRemoteVersion> remotes = List.of(
                    new FabricRemoteVersion(version, "1", List.of(origin + "/meta")),
                    new LegacyFabricRemoteVersion(version, "1", List.of(origin + "/meta")),
                    new QuiltRemoteVersion(version, "1", List.of(origin + "/meta")));
            for (ComponentRemoteVersion remote : remotes) {
                var repository = new TestRepository(temp.resolve(remote.getComponentType().name()));
                var cache = new DefaultCacheRepository(temp.resolve("cache-" + remote.getComponentType().name()));
                var provider = new DownloadProvider() {
                    /// Uses the isolated cache.
                    @Override public DefaultCacheRepository getCacheRepository() { return cache; }
                    /// Routes library files to the local missing endpoint.
                    @Override public DownloadCandidates getDownloadCandidates(String url) { return DownloadCandidates.of(origin + "/missing"); }
                };
                var manager = new DefaultDependencyManager(repository, provider, cache);
                var manifest = new GameInstanceManifest(new GameInstanceID("fixture"))
                        .withMainClass(GameComponentAnalyzer.VANILLA_MAIN)
                        .withPatches(List.of(new GameInstancePatch(GameComponentType.GAME.getPatchId())));
                Task<GameInstancePatch> task = remote.getInstallTask(manager, manifest, temp.resolve("mods"));
                assertFalse(task.executor().test(), "Missing libraries must fail " + remote.getComponentType());
                assertNotNull(task.getException());
            }
            assertTrue(missingRequests.get() > 0);
        } finally {
            server.stop(0);
            if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
            if (previousEnabled == null) System.clearProperty("mfcl.native.loaders.enabled"); else System.setProperty("mfcl.native.loaders.enabled", previousEnabled);
            if (previousPrism == null) System.clearProperty("prism.order"); else System.setProperty("prism.order", previousPrism);
        }
    }

    /// Missing required vanilla libraries must not be reported as a successful installation.
    @Test void missingVanillaLibraryFailsInstallation() throws Exception {
        @Nullable String previousPrism = System.getProperty("prism.order");
        System.setProperty("prism.order", "sw"); JavaFXLauncher.start();
        if (!JavaFXLauncher.isStarted()) {
            if (previousPrism == null) System.clearProperty("prism.order"); else System.setProperty("prism.order", previousPrism);
        }
        assumeTrue(JavaFXLauncher.isStarted(), "JavaFX toolkit unavailable");
        var previousCache = org.jackhuang.hmcl.util.CacheRepository.getInstance();
        AtomicInteger missingRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        byte[] assets = "{\"objects\":{}}".getBytes(StandardCharsets.UTF_8);
        String assetHash = org.jackhuang.hmcl.util.DigestUtils.digestToString("SHA-1", assets);
        byte[] client = "abc".getBytes(StandardCharsets.UTF_8);
        String metadata = "{\"id\":\"1.20.1\",\"mainClass\":\"net.minecraft.client.main.Main\"," +
                "\"assetIndex\":{\"id\":\"fixture\",\"url\":\"" + origin + "/assets\",\"sha1\":\"" + assetHash + "\",\"size\":" + assets.length + "}," +
                "\"downloads\":{\"client\":{\"url\":\"" + origin + "/client\",\"sha1\":\"a9993e364706816aba3e25717850c26c9cd0d89d\",\"size\":3}}," +
                "\"libraries\":[{\"name\":\"org.test:missing:1\",\"url\":\"" + origin + "/repo/\"}]}";
        server.createContext("/manifest", exchange -> {
            byte[] data = metadata.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, data.length);
            try (var output = exchange.getResponseBody()) { output.write(data); }
        });
        server.createContext("/assets", exchange -> {
            exchange.sendResponseHeaders(200, assets.length); try (var output = exchange.getResponseBody()) { output.write(assets); }
        });
        server.createContext("/client", exchange -> {
            exchange.sendResponseHeaders(200, client.length); try (var output = exchange.getResponseBody()) { output.write(client); }
        });
        server.createContext("/repo", exchange -> { missingRequests.incrementAndGet(); exchange.sendResponseHeaders(404, -1); exchange.close(); });
        server.start();
        try {
            var repository = new TestRepository(temp.resolve("vanilla"));
            var cache = new DefaultCacheRepository(temp.resolve("vanilla-cache"));
            org.jackhuang.hmcl.util.CacheRepository.setInstance(cache);
            var remote = new org.jackhuang.hmcl.download.game.GameRemoteVersion(GameVersionNumber.asGameVersion("1.20.1"),
                    List.of(origin + "/manifest"), ReleaseType.RELEASE, java.time.Instant.parse("2023-06-07T00:00:00Z"));
            var provider = new DownloadProvider() {
                /// Uses the isolated cache.
                @Override public DefaultCacheRepository getCacheRepository() { return cache; }
                /// Returns only the local version fixture.
                @Override public Task<ComponentRemoteVersionList<?>> getVersionsAsync(GameComponentType type,
                        @Nullable GameVersionNumber gameVersion, boolean refresh) {
                    java.util.TreeSet<org.jackhuang.hmcl.download.game.GameRemoteVersion> versions = new java.util.TreeSet<>();
                    versions.add(remote); return Task.completed(ComponentRemoteVersionList.of(GameComponentType.GAME, versions));
                }
            };
            var manager = new DefaultDependencyManager(repository, provider, cache);
            Task<GameInstancePatch> task = remote.getInstallTask(manager, new GameInstanceManifest(new GameInstanceID("fixture")), temp.resolve("mods"));
            assertFalse(task.executor().test(), "A missing vanilla library must fail installation");
            assertTrue(missingRequests.get() > 0); assertNotNull(task.getException());
        } finally {
            server.stop(0); org.jackhuang.hmcl.util.CacheRepository.setInstance(previousCache);
            if (previousPrism == null) System.clearProperty("prism.order"); else System.setProperty("prism.order", previousPrism);
        }
    }

    /// Failed Fabric-family API mod downloads must fail installation.
    @Test void missingLoaderApiFilesFailInstallation() throws Exception {
        @Nullable String previousPrism = System.getProperty("prism.order");
        System.setProperty("prism.order", "sw"); JavaFXLauncher.start();
        if (!JavaFXLauncher.isStarted()) {
            if (previousPrism == null) System.clearProperty("prism.order"); else System.setProperty("prism.order", previousPrism);
        }
        assumeTrue(JavaFXLauncher.isStarted(), "JavaFX toolkit unavailable");
        var previousCache = org.jackhuang.hmcl.util.CacheRepository.getInstance();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/missing", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(404, -1); exchange.close(); });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/missing";
            var file = new org.jackhuang.hmcl.addon.RemoteAddon.File(java.util.Map.of(), url, "api.jar");
            var date = java.time.Instant.parse("2023-06-07T00:00:00Z");
            var addon = new org.jackhuang.hmcl.addon.RemoteAddon.Version(org.jackhuang.hmcl.addon.RemoteAddon.Source.MODRINTH,
                    "id", "project", "fixture", "1", date, org.jackhuang.hmcl.addon.RemoteAddon.VersionType.Release,
                    file, List.of(), List.of("1.20.1"), List.of());
            var gameVersion = GameVersionNumber.asGameVersion("1.20.1");
            List<ComponentRemoteVersion> remotes = List.of(
                    new org.jackhuang.hmcl.download.fabric.FabricAPIRemoteVersion(gameVersion, "1", "1", date, addon, List.of(url)),
                    new org.jackhuang.hmcl.download.legacyfabric.LegacyFabricAPIRemoteVersion(gameVersion, "1", "1", date, addon, List.of(url)),
                    new org.jackhuang.hmcl.download.quilt.QuiltAPIRemoteVersion(gameVersion, "1", "1", date, addon, List.of(url)));
            var repository = new TestRepository(temp.resolve("api"));
            var cache = new DefaultCacheRepository(temp.resolve("api-cache"));
            org.jackhuang.hmcl.util.CacheRepository.setInstance(cache);
            var manager = new DefaultDependencyManager(repository, new DownloadProvider(), cache);
            var manifest = new GameInstanceManifest(new GameInstanceID("fixture"));
            for (ComponentRemoteVersion remote : remotes) {
                Task<GameInstancePatch> task = remote.getInstallTask(manager, manifest, temp.resolve("mods"));
                assertFalse(task.executor().test(), "A missing API file must fail " + remote.getComponentType());
                assertNotNull(task.getException());
            }
            assertEquals(3, requests.get());
        } finally {
            server.stop(0); org.jackhuang.hmcl.util.CacheRepository.setInstance(previousCache);
            if (previousPrism == null) System.clearProperty("prism.order"); else System.setProperty("prism.order", previousPrism);
        }
    }

    /// A structurally valid but hash-mismatched loader JAR cannot be published.
    @Test void publishedLoaderHashRejectsWrongJar() throws Exception {
        @Nullable String previousPrism = System.getProperty("prism.order");
        @Nullable String previousPath = System.getProperty("mfcl.native.path");
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        System.setProperty("prism.order", "sw"); JavaFXLauncher.start();
        if (!JavaFXLauncher.isStarted()) {
            if (previousPrism == null) System.clearProperty("prism.order"); else System.setProperty("prism.order", previousPrism);
        }
        assumeTrue(JavaFXLauncher.isStarted(), "JavaFX toolkit unavailable");
        if (executable != null && !executable.isBlank()) System.setProperty("mfcl.native.path", executable);
        var buffer = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(buffer)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("fixture.txt")); zip.write("valid zip".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        byte[] jar = buffer.toByteArray();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        byte[] metadata = ("{\"loader\":{\"maven\":\"net.fabricmc:fabric-loader:1\"}," +
                "\"intermediary\":{\"maven\":\"net.fabricmc:intermediary:1\"}," +
                "\"launcherMeta\":{\"mainClass\":\"example.Client\",\"libraries\":{\"common\":[" +
                "{\"name\":\"org.test:hashed:1\",\"sha1\":\"0000000000000000000000000000000000000000\"}],\"server\":[]}}}")
                .getBytes(StandardCharsets.UTF_8);
        server.createContext("/meta", exchange -> {
            exchange.sendResponseHeaders(200, metadata.length); try (var output = exchange.getResponseBody()) { output.write(metadata); }
        });
        server.createContext("/jar", exchange -> {
            exchange.sendResponseHeaders(200, jar.length); try (var output = exchange.getResponseBody()) { output.write(jar); }
        });
        server.start();
        try {
            var repository = new TestRepository(temp.resolve("hash-game"));
            var cache = new DefaultCacheRepository(temp.resolve("hash-cache"));
            var provider = new DownloadProvider() {
                /// Uses the isolated cache.
                @Override public DefaultCacheRepository getCacheRepository() { return cache; }
                /// Serves a valid but intentionally mismatched JAR for every library.
                @Override public DownloadCandidates getDownloadCandidates(String url) { return DownloadCandidates.of(origin + "/jar"); }
            };
            var manager = new DefaultDependencyManager(repository, provider, cache);
            var manifest = new GameInstanceManifest(new GameInstanceID("fixture")).withMainClass(GameComponentAnalyzer.VANILLA_MAIN)
                    .withPatches(List.of(new GameInstancePatch(GameComponentType.GAME.getPatchId())));
            var remote = new FabricRemoteVersion(GameVersionNumber.asGameVersion("1.20.1"), "1", List.of(origin + "/meta"));
            Task<GameInstancePatch> task = remote.getInstallTask(manager, manifest, temp.resolve("mods"));
            assertFalse(task.executor().test()); assertNotNull(task.getException());
            Path rejected = repository.getLayout().getLibrariesDirectory().resolve("org/test/hashed/1/hashed-1.jar");
            assertFalse(java.nio.file.Files.exists(rejected));
        } finally {
            server.stop(0);
            if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
            if (previousPrism == null) System.clearProperty("prism.order"); else System.setProperty("prism.order", previousPrism);
        }
    }

    /// Local OptiFine version detection uses the same constant-pool sequence as Java.
    @Test void optifineLocalVersionUsesNativeClassReader() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        var bytes = new java.io.ByteArrayOutputStream();
        try (var data = new java.io.DataOutputStream(bytes)) {
            data.writeInt(0xcafebabe); data.writeShort(0); data.writeShort(52); data.writeShort(8);
            for (String text : List.of("中文\0😀", "MC_VERSION", "1.20.1", "OF_EDITION", "HD_U", "OF_RELEASE", "I1")) {
                data.writeByte(1); data.writeUTF(text);
            }
        }
        var constants = new java.util.ArrayList<String>();
        org.jenkinsci.constant_pool_scanner.ConstantPoolScanner.parse(bytes.toByteArray(), org.jenkinsci.constant_pool_scanner.ConstantType.UTF8)
                .list(org.jenkinsci.constant_pool_scanner.Utf8Constant.class).forEach(value -> constants.add(value.get()));
        assertEquals(List.of("中文\0😀", "MC_VERSION", "1.20.1", "OF_EDITION", "HD_U", "OF_RELEASE", "I1"), constants);
        Path installer = temp.resolve("local-optifine.jar"), wrapper = temp.resolve("identity-backend");
        try (var zip = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(installer))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("net/optifine/Config.class")); zip.write(bytes.toByteArray()); zip.closeEntry();
        }
        java.nio.file.Files.writeString(wrapper, "#!/bin/sh\nIFS= read -r line\ncase \"$line\" in *identify_optifine_installer*) : > \"$0.called\" ;; esac\n"
                + "printf '%s\\n' \"$line\" | exec '" + executable.replace("'", "'\\''") + "'\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        @Nullable String previous = System.getProperty("mfcl.native.path");
        try {
            System.setProperty("mfcl.native.path", wrapper.toString());
            var identity = org.jackhuang.hmcl.nativebridge.NativeBackend.identifyOptifineInstaller(wrapper, installer);
            assertEquals(constants.get(2), identity.minecraft()); assertEquals(constants.get(4), identity.edition()); assertEquals(constants.get(6), identity.release());
            var repository = new TestRepository(temp.resolve("identity-game"));
            var cache = new DefaultCacheRepository(temp.resolve("identity-cache"));
            var provider = new DownloadProvider() { @Override public DefaultCacheRepository getCacheRepository() { return cache; } };
            var manager = new DefaultDependencyManager(repository, provider, cache);
            var manifest = new GameInstanceManifest(new GameInstanceID("fixture"));
            java.nio.file.Files.deleteIfExists(Path.of(wrapper + ".called"));
            assertNotNull(org.jackhuang.hmcl.download.optifine.OptiFineInstallTask.install(manager, manifest, "1.20.1", installer));
            assertTrue(java.nio.file.Files.exists(Path.of(wrapper + ".called")));
            assertThrows(VersionMismatchException.class, () ->
                    org.jackhuang.hmcl.download.optifine.OptiFineInstallTask.install(manager, manifest, "1.19.4", installer));
            System.setProperty("mfcl.native.path", temp.resolve("missing").toString());
            assertNotNull(org.jackhuang.hmcl.download.optifine.OptiFineInstallTask.install(manager, manifest, "1.20.1", installer));
            var unsupported = new java.io.ByteArrayOutputStream();
            try (var data = new java.io.DataOutputStream(unsupported)) {
                data.writeInt(0xcafebabe); data.writeShort(0); data.writeShort(52); data.writeShort(2); data.writeByte(17); data.writeInt(0);
            }
            assertThrows(java.io.IOException.class, () -> org.jenkinsci.constant_pool_scanner.ConstantPoolScanner.parse(
                    unsupported.toByteArray(), org.jenkinsci.constant_pool_scanner.ConstantType.UTF8));
            Path unsupportedJar = temp.resolve("unsupported-class.jar");
            try (var zip = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(unsupportedJar))) {
                zip.putNextEntry(new java.util.zip.ZipEntry("Config.class")); zip.write(unsupported.toByteArray()); zip.closeEntry();
            }
            assertThrows(java.io.IOException.class, () -> org.jackhuang.hmcl.nativebridge.NativeBackend.identifyOptifineInstaller(wrapper, unsupportedJar));
        } finally {
            if (previous == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previous);
        }
    }

    /// OptiFine's installation task uses Rust inspection of ordinary wrapper metadata.
    @Test void optifineInstallerUsesNativeInspection() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        Path installer = temp.resolve("optifine.jar"), wrapper = temp.resolve("backend"), client = temp.resolve("client.jar");
        java.nio.file.Files.writeString(client, "client");
        try (var zip = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(installer))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("launchwrapper-2.0.jar")); zip.write("two".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("launchwrapper-of.txt")); zip.write("  2.3\r\n".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("launchwrapper-of-2.3.jar")); zip.write("of".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("buildof.txt")); zip.write("20220101-120000\n".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        java.nio.file.Files.writeString(wrapper, "#!/bin/sh\nIFS= read -r line\ncase \"$line\" in *plan_optifine_installer*) : > \"$0.called\" ;; *stage_forge_data*) printf '%s\\n' stage >> \"$0.staged\" ;; esac\n"
                + "printf '%s\\n' \"$line\" | exec '" + executable.replace("'", "'\\''") + "'\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        @Nullable String previousPath = System.getProperty("mfcl.native.path");
        org.jackhuang.hmcl.download.optifine.OptiFineInstallTask task = null;
        try {
            System.setProperty("mfcl.native.path", wrapper.toString());
            var plan = org.jackhuang.hmcl.nativebridge.NativeBackend.planOptifineInstaller(wrapper, installer, () -> false);
            assertFalse(plan.patcher()); assertTrue(plan.wrapper2()); assertTrue(plan.wrapperOf());
            assertEquals("2.3", plan.wrapperVersion()); assertEquals("20220101-120000", plan.build());
            var repository = new TestRepository(temp.resolve("game"));
            var cache = new DefaultCacheRepository(temp.resolve("cache"));
            var provider = new DownloadProvider() { @Override public DefaultCacheRepository getCacheRepository() { return cache; } };
            var manager = new DefaultDependencyManager(repository, provider, cache);
            var manifest = new GameInstanceManifest(new GameInstanceID("fixture")).withMainClass(GameComponentAnalyzer.VANILLA_MAIN);
            var remote = new org.jackhuang.hmcl.download.optifine.OptiFineRemoteVersion(
                    GameVersionNumber.asGameVersion("1.20.1"), "HD_U_I1", List.of("https://example.test/installer"), false);
            task = new org.jackhuang.hmcl.download.optifine.OptiFineInstallTask(manager, manifest, remote, client, installer);
            task.preExecute(); java.nio.file.Files.deleteIfExists(Path.of(wrapper + ".called")); task.execute();
            assertTrue(java.nio.file.Files.exists(Path.of(wrapper + ".called")));
            assertEquals("two", java.nio.file.Files.readString(repository.getLayout().getArtifactFile(org.jackhuang.hmcl.game.Artifact.fromDescriptor("optifine:launchwrapper:2.0"))));
            assertEquals("of", java.nio.file.Files.readString(repository.getLayout().getArtifactFile(org.jackhuang.hmcl.game.Artifact.fromDescriptor("optifine:launchwrapper-of:2.3"))));
            assertNotNull(task.getResult());
            assertEquals(2, java.nio.file.Files.readAllLines(Path.of(wrapper + ".staged")).size());
            try (var zipfs = org.jackhuang.hmcl.util.io.CompressingUtils.createReadOnlyZipFileSystem(installer)) {
                Path expected = temp.resolve("expected-wrapper.jar");
                org.jackhuang.hmcl.util.io.FileUtils.copyFile(zipfs.getPath("launchwrapper-2.0.jar"), expected);
                Path actual = repository.getLayout().getArtifactFile(org.jackhuang.hmcl.game.Artifact.fromDescriptor("optifine:launchwrapper:2.0"));
                assertEquals(java.nio.file.Files.getLastModifiedTime(expected), java.nio.file.Files.getLastModifiedTime(actual));
                assertEquals(java.nio.file.Files.getPosixFilePermissions(expected), java.nio.file.Files.getPosixFilePermissions(actual));
            }
            Path unusual = temp.resolve("unusual.jar");
            try (var zip = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(unusual))) {
                zip.putNextEntry(new java.util.zip.ZipEntry("launchwrapper-of.txt")); zip.write("../legacy".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
            assertThrows(java.io.IOException.class, () ->
                    org.jackhuang.hmcl.nativebridge.NativeBackend.planOptifineInstaller(wrapper, unusual, () -> false));
        } finally {
            if (task != null) {
                var field = org.jackhuang.hmcl.download.optifine.OptiFineInstallTask.class.getDeclaredField("dest");
                field.setAccessible(true); @Nullable Path temporary = (Path) field.get(task);
                if (temporary != null) java.nio.file.Files.deleteIfExists(temporary);
            }
            if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
        }
    }

    /// Missing OptiFine runtime libraries fail installation even with a valid local installer fixture.
    @Test void missingOptifineLibraryFailsInstallation() throws Exception {
        @Nullable String previousPrism = System.getProperty("prism.order");
        System.setProperty("prism.order", "sw"); JavaFXLauncher.start();
        if (!JavaFXLauncher.isStarted()) {
            if (previousPrism == null) System.clearProperty("prism.order"); else System.setProperty("prism.order", previousPrism);
        }
        assumeTrue(JavaFXLauncher.isStarted(), "JavaFX toolkit unavailable");
        Path installer = temp.resolve("installer.jar");
        try (var zip = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(installer))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("fixture.txt")); zip.write("fixture".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        Path client = temp.resolve("client.jar"); java.nio.file.Files.writeString(client, "fixture");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/missing", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(404, -1); exchange.close(); });
        server.start();
        @Nullable org.jackhuang.hmcl.download.optifine.OptiFineInstallTask task = null;
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/missing";
            var repository = new TestRepository(temp.resolve("optifine-game"));
            var cache = new DefaultCacheRepository(temp.resolve("optifine-cache"));
            var provider = new DownloadProvider() {
                /// Uses the isolated cache.
                @Override public DefaultCacheRepository getCacheRepository() { return cache; }
                /// Makes the required launch wrapper unavailable.
                @Override public DownloadCandidates getDownloadCandidates(String ignored) { return DownloadCandidates.of(url); }
            };
            var manager = new DefaultDependencyManager(repository, provider, cache);
            var manifest = new GameInstanceManifest(new GameInstanceID("fixture")).withMainClass(GameComponentAnalyzer.VANILLA_MAIN);
            var remote = new org.jackhuang.hmcl.download.optifine.OptiFineRemoteVersion(
                    GameVersionNumber.asGameVersion("1.20.1"), "HD_U_I1", List.of(url), false);
            task = new org.jackhuang.hmcl.download.optifine.OptiFineInstallTask(manager, manifest, remote, client, installer);
            assertFalse(task.executor().test()); assertNotNull(task.getException()); assertTrue(requests.get() > 0);
        } finally {
            server.stop(0);
            if (task != null) {
                var field = org.jackhuang.hmcl.download.optifine.OptiFineInstallTask.class.getDeclaredField("dest");
                field.setAccessible(true); @Nullable Path temporary = (Path) field.get(task);
                if (temporary != null) java.nio.file.Files.deleteIfExists(temporary);
            }
            if (previousPrism == null) System.clearProperty("prism.order"); else System.setProperty("prism.order", previousPrism);
        }
    }

    /// Minimal repository for isolated install tasks.
    private static final class TestRepository extends DefaultGameRepository {
        /// @param root isolated game root
        private TestRepository(Path root) { super(root); }
        /// Creates the standard repository layout.
        @Override protected DefaultGameRepositoryLayout createLayout(Path root) { return new DefaultGameRepositoryLayout(root); }
        /// No instance needs publishing in this test.
        @Override protected DefaultGameInstance createInstance(DefaultGameRepositorySnapshot snapshot, GameInstanceID id,
                                                               GameInstanceManifest manifest, @Nullable Path manifestFile) {
            throw new UnsupportedOperationException("No instance expected");
        }
    }
}
