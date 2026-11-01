/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.forge;

import org.jackhuang.hmcl.game.GameInstanceManifest;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
@org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.LINUX, org.junit.jupiter.api.condition.OS.MAC})
final class NativeForgeProfilesTest {
    @TempDir Path temp;

    @Test void legacyInstallerProfileUsesRustReader() throws Exception {
        String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        String metadata = "{\"install\":{\"minecraft\":\"1.12.2\",\"path\":\"net.minecraftforge:forge:1.12.2-1\","
                + "\"filePath\":\"forge.jar\"},\"versionInfo\":{\"id\":\"forge\",\"inheritsFrom\":\"1.12.2\","
                + "\"libraries\":[{\"name\":\"example:library:1\"}],\"custom\":true}}";
        Path installer = temp.resolve("legacy-installer.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(installer))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("install_profile.json"));
            zip.write(metadata.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("forge.jar")); zip.write(new byte[] {1, 2, 3}); zip.closeEntry();
        }
        Path wrapper = temp.resolve("legacy-backend");
        Files.writeString(wrapper, "#!/bin/sh\nIFS= read -r line\ncase \"$line\" in *stage_forge_data*) : > \"$0.staged\" ;; esac\n"
                + "printf '%s\\n' \"$line\" | exec '" + executable.replace("'", "'\\''") + "'\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        String previous = System.getProperty("mfcl.native.path");
        try {
            System.setProperty("mfcl.native.path", wrapper.toString());
            var projected = NativeBackend.readForgeProfiles(wrapper, installer, () -> false);
            assertNotNull(projected.projection());
            assertTrue(projected.projection().get("legacy_install_v1").getAsBoolean());
            var nativeProfile = NativeForgeProfiles.tryReadLegacy(installer, () -> false);
            assertNotNull(nativeProfile);
            ForgeInstallProfile expected = JsonUtils.fromNonNullJson(metadata, ForgeInstallProfile.class);
            assertEquals(JsonUtils.GSON.toJsonTree(expected.install()), JsonUtils.GSON.toJsonTree(nativeProfile.profile().install()));
            assertEquals(expected.versionInfo(), nativeProfile.profile().versionInfo());
            assertNull(NativeForgeProfiles.tryRead(installer, () -> false));
            assertFalse(ForgeInstallTask.detectForgeInstallerType(
                    org.jackhuang.hmcl.util.versioning.GameVersionNumber.asGameVersion("1.12.2"), installer));
            assertThrows(org.jackhuang.hmcl.download.VersionMismatchException.class, () ->
                    ForgeInstallTask.detectForgeInstallerType(org.jackhuang.hmcl.util.versioning.GameVersionNumber.asGameVersion("1.20.1"), installer));
            Path output = temp.resolve("forge-output.jar"); Files.writeString(output, "old content");
            assertTrue(NativeForgeProfiles.tryCopyLegacyEmbedded(installer, "forge.jar", output, nativeProfile.observation(), () -> false));
            assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(output));
            assertTrue(Files.exists(Path.of(wrapper + ".staged")));
            assertFalse(NativeForgeProfiles.tryCopyLegacyEmbedded(installer, "missing.jar", output, nativeProfile.observation(), () -> false));
            assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(output));
        } finally {
            if (previous == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previous);
        }
    }

    @Test void forgeTypeDetectionUsesNativeProfilesAndPreservesFallback() throws Exception {
        String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        Path wrapper = temp.resolve("backend"), installer = temp.resolve("installer.jar");
        Files.writeString(wrapper, "#!/bin/sh\nIFS= read -r line\ncase \"$line\" in *read_forge_profiles*) : > \"$0.called\" ;; esac\n"
                + "printf '%s\\n' \"$line\" | exec '" + executable.replace("'", "'\\''") + "'\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(installer))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("install_profile.json"));
            zip.write("{\"spec\":1,\"minecraft\":\"1.20.1\",\"json\":\"/version.json\",\"version\":\"forge-1\"}".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("version.json")); zip.write("{\"id\":\"forge\"}".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        String previous = System.getProperty("mfcl.native.path");
        try {
            System.setProperty("mfcl.native.path", wrapper.toString());
            assertTrue(ForgeInstallTask.detectForgeInstallerType(org.jackhuang.hmcl.util.versioning.GameVersionNumber.asGameVersion("1.20.1"), installer));
            assertTrue(Files.exists(Path.of(wrapper + ".called")));
            assertThrows(org.jackhuang.hmcl.download.VersionMismatchException.class, () ->
                    ForgeInstallTask.detectForgeInstallerType(org.jackhuang.hmcl.util.versioning.GameVersionNumber.asGameVersion("1.19.4"), installer));
            System.setProperty("mfcl.native.path", temp.resolve("missing").toString());
            assertTrue(ForgeInstallTask.detectForgeInstallerType(org.jackhuang.hmcl.util.versioning.GameVersionNumber.asGameVersion("1.20.1"), installer));
        } finally {
            if (previous == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previous);
        }
    }

    @Test void actualInstallerProfilesMatchJavaReaders() throws Exception {
        String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"), fixture = System.getenv("MFCL_NEOFORGE_INSTALLER_FIXTURE");
        assumeTrue(executable != null && !executable.isBlank() && fixture != null && !fixture.isBlank());
        Path installer = Path.of(fixture), binary = Path.of(executable);
        var nativeData = NativeBackend.readForgeProfiles(binary, installer, () -> false);
        assertNotNull(nativeData.projection(), "Real installer profile should be projected by Rust");
        assertNotNull(nativeData.versionProjection(), "Real loader version should be projected by Rust");
        ForgeNewInstallProfile expectedProfile;
        GameInstanceManifest expectedVersion;
        try (ZipFile archive = new ZipFile(installer.toFile())) {
            String javaProfile = new String(archive.getInputStream(archive.getEntry("install_profile.json")).readAllBytes(), StandardCharsets.UTF_8);
            expectedProfile = JsonUtils.fromNonNullJson(javaProfile, ForgeNewInstallProfile.class);
            String versionPath = expectedProfile.getJson().replaceFirst("^/", "");
            String javaVersion = new String(archive.getInputStream(archive.getEntry(versionPath)).readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(javaProfile, nativeData.profile()); assertEquals(javaVersion, nativeData.version());
            assertEquals(expectedProfile.getJson(), nativeData.versionPath());
            expectedVersion = JsonUtils.fromNonNullJson(nativeData.version(), GameInstanceManifest.class);
        }
        String previous = System.getProperty("mfcl.native.path");
        try {
            System.setProperty("mfcl.native.path", executable);
            ForgeNewInstallProfile projection = NativeForgeProfiles.decode(java.util.Objects.requireNonNull(nativeData.projection()));
            assertEquals(JsonUtils.GSON.toJsonTree(expectedProfile), JsonUtils.GSON.toJsonTree(projection));
            GameInstanceManifest projectedVersion = org.jackhuang.hmcl.download.game.NativeGameManifest.decodeLoaderManifest(
                    java.util.Objects.requireNonNull(nativeData.versionProjection()), nativeData.version());
            assertEquals(expectedVersion.toJsonObject(), projectedVersion.toJsonObject());
            assertEquals(org.jackhuang.hmcl.game.GameInstancePatch.fromManifest(expectedVersion, "loader", "fixture", 1).toJsonObject(),
                    org.jackhuang.hmcl.game.GameInstancePatch.fromManifest(projectedVersion, "loader", "fixture", 1).toJsonObject());
            var decoded = NativeForgeProfiles.tryRead(installer, () -> false);
            assertNotNull(decoded);
            assertEquals(JsonUtils.GSON.toJsonTree(expectedProfile), JsonUtils.GSON.toJsonTree(decoded.profile()));
            assertEquals(expectedProfile.getData(), decoded.profile().getData());
            var id = new org.jackhuang.hmcl.game.GameInstanceID("fixture");
            var nativeLayout = new org.jackhuang.hmcl.game.DefaultGameRepositoryLayout(temp.resolve("native"));
            var javaLayout = new org.jackhuang.hmcl.game.DefaultGameRepositoryLayout(temp.resolve("java"));
            try (var fs = org.jackhuang.hmcl.util.io.CompressingUtils.createReadOnlyZipFileSystem(installer)) {
                assertTrue(NativeForgeProfiles.tryCopyEmbedded(installer, fs, decoded.profile(), id, nativeLayout, () -> false));
                int copied = 0;
                for (var library : expectedProfile.getLibraries()) {
                    Path source = fs.getPath("maven").resolve(library.getPath());
                    if (!Files.exists(source)) continue;
                    Path javaFile = javaLayout.getLibraryFile(id, library), nativeFile = nativeLayout.getLibraryFile(id, library);
                    org.jackhuang.hmcl.util.io.FileUtils.copyFile(source, javaFile);
                    assertArrayEquals(Files.readAllBytes(javaFile), Files.readAllBytes(nativeFile));
                    assertEquals(Files.getLastModifiedTime(javaFile), Files.getLastModifiedTime(nativeFile));
                    assertEquals(Files.getPosixFilePermissions(javaFile), Files.getPosixFilePermissions(nativeFile));
                    copied++;
                }
                assertTrue(copied > 0, "Fixture should contain at least one embedded library");
                var paths = decoded.profile().getData().values().stream()
                        .filter(value -> value != null && value.startsWith("/data/")).toList();
                assertFalse(paths.isEmpty(), "Fixture should contain at least one embedded data file");
                Path dataDirectory = Files.createDirectory(temp.resolve("data"));
                var staged = NativeForgeProfiles.tryExtractData(installer, fs, paths, dataDirectory, () -> false);
                assertNotNull(staged); assertEquals(paths.size(), staged.size());
                for (int i = 0; i < paths.size(); i++) {
                    Path expected = Files.createTempFile(temp, "java-data-", ".bin"), actual = Path.of(staged.get(i));
                    org.jackhuang.hmcl.util.io.FileUtils.copyFile(fs.getPath(paths.get(i)), expected);
                    assertArrayEquals(Files.readAllBytes(expected), Files.readAllBytes(actual));
                    assertEquals(Files.getLastModifiedTime(expected), Files.getLastModifiedTime(actual));
                    assertEquals(Files.getPosixFilePermissions(expected), Files.getPosixFilePermissions(actual));
                }
            }
            Path legacy = temp.resolve("legacy.jar");
            try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(legacy))) {
                zip.putNextEntry(new java.util.zip.ZipEntry("install_profile.json"));
                zip.write("{\"spec\":\"1\",\"minecraft\":\"1.20.1\",\"json\":\"/version.json\",\"version\":\"legacy\"}".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
                zip.putNextEntry(new java.util.zip.ZipEntry("version.json")); zip.write("{\"id\":\"legacy\"}".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
            assertNull(NativeBackend.readForgeProfiles(binary, legacy, () -> false).projection());
            var legacyProfile = NativeForgeProfiles.tryRead(legacy, () -> false);
            assertNotNull(legacyProfile); assertEquals(1, legacyProfile.profile().getSpec());
        } finally {
            if (previous == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previous);
        }
    }
}
