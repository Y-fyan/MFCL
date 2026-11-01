/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.liteloader;

import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.versioning.GameVersionNumber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
@org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.LINUX, org.junit.jupiter.api.condition.OS.MAC})
final class NativeLiteLoaderCatalogTest {
    @TempDir Path temp;

    @Test void installationPatchMatchesJavaAndSchedulesLibraries() throws Exception {
        String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(binary != null && !binary.isBlank());
        Path wrapper = temp.resolve("patch-backend");
        Files.writeString(wrapper, "#!/bin/sh\nIFS= read -r line\ncase \"$line\" in *plan_liteloader_patch*) : > \"$0.called\" ;; esac\n"
                + "printf '%s\\n' \"$line\" | exec '" + binary.replace("'", "'\\''") + "'\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        String oldPath = System.getProperty("mfcl.native.path"), oldEnabled = System.getProperty("mfcl.native.loaders.enabled");
        try {
            var remote = new LiteLoaderRemoteVersion(GameVersionNumber.asGameVersion("1.12.2"), "1.12.2_00",
                    org.jackhuang.hmcl.download.ComponentRemoteVersion.Type.RELEASE, List.of("https://example.test/liteloader.jar"),
                    "ignored", List.of(new org.jackhuang.hmcl.game.Library("net.minecraft", "launchwrapper", "1.12")));
            var manifest = new org.jackhuang.hmcl.game.GameInstanceManifest(new org.jackhuang.hmcl.game.GameInstanceID("fixture"));
            System.setProperty("mfcl.native.path", wrapper.toString()); System.setProperty("mfcl.native.loaders.enabled", "false");
            var javaTask = new LiteLoaderInstallTask(null, manifest, remote); javaTask.execute();
            System.clearProperty("mfcl.native.loaders.enabled");
            var nativeTask = new LiteLoaderInstallTask(null, manifest, remote); nativeTask.execute();
            assertTrue(Files.exists(Path.of(wrapper + ".called")));
            assertNotNull(NativeLiteLoaderPatch.tryPlan(remote, () -> false));
            assertEquals(javaTask.getResult().toJsonObject(), nativeTask.getResult().toJsonObject());
            assertEquals(1, nativeTask.getDependencies().size());
            System.setProperty("mfcl.native.path", temp.resolve("missing").toString());
            var fallback = new LiteLoaderInstallTask(null, manifest, remote); fallback.execute();
            assertEquals(javaTask.getResult().toJsonObject(), fallback.getResult().toJsonObject());
        } finally {
            if (oldPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", oldPath);
            if (oldEnabled == null) System.clearProperty("mfcl.native.loaders.enabled"); else System.setProperty("mfcl.native.loaders.enabled", oldEnabled);
        }
    }

    @Test void bundledCatalogAndFetchMatchJava() throws Exception {
        String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(binary != null && !binary.isBlank());
        Path wrapper = temp.resolve("backend");
        Files.writeString(wrapper, "#!/bin/sh\nIFS= read -r line\ncase \"$line\" in *parse_liteloader_catalog*) : > \"$0.called\" ;; esac\n"
                + "printf '%s\\n' \"$line\" | exec '" + binary.replace("'", "'\\''") + "'\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        String metadata;
        try (var input = Objects.requireNonNull(LiteLoaderRemoteVersion.class.getResourceAsStream("/assets/liteloader/versions.json"))) {
            metadata = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String oldPath = System.getProperty("mfcl.native.path"), oldEnabled = System.getProperty("mfcl.native.loaders.enabled");
        try {
            System.setProperty("mfcl.native.path", wrapper.toString()); System.clearProperty("mfcl.native.loaders.enabled");
            List<LiteLoaderRemoteVersion.LiteLoaderRemoteVersionRecord> expected = JsonUtils.GSON.fromJson(metadata,
                    JsonUtils.listTypeOf(LiteLoaderRemoteVersion.LiteLoaderRemoteVersionRecord.class));
            var actual = LiteLoaderRemoteVersion.tryNativeCatalog(metadata);
            assertNotNull(actual); assertEquals(JsonUtils.GSON.toJsonTree(expected), JsonUtils.GSON.toJsonTree(actual));
            assertTrue(Files.exists(Path.of(wrapper + ".called")));
            assertNull(LiteLoaderRemoteVersion.tryNativeCatalog("[{\"gameVersion\":\"1\",\"version\":\"v\",\"snapshot\":false,\"url\":\"u\",\"tweakClass\":\"t\",\"libraries\":[null]}]"));
            var version = GameVersionNumber.asGameVersion("1.12.2");
            System.setProperty("mfcl.native.loaders.enabled", "false");
            var javaTask = LiteLoaderRemoteVersion.fetchAsync(version); assertTrue(javaTask.executor().test());
            System.clearProperty("mfcl.native.loaders.enabled"); Files.delete(Path.of(wrapper + ".called"));
            var nativeTask = LiteLoaderRemoteVersion.fetchAsync(version); assertTrue(nativeTask.executor().test());
            assertTrue(Files.exists(Path.of(wrapper + ".called")));
            assertEquals(javaTask.getResult().stream().map(LiteLoaderRemoteVersion::getSelfVersion).toList(),
                    nativeTask.getResult().stream().map(LiteLoaderRemoteVersion::getSelfVersion).toList());
        } finally {
            if (oldPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", oldPath);
            if (oldEnabled == null) System.clearProperty("mfcl.native.loaders.enabled"); else System.setProperty("mfcl.native.loaders.enabled", oldEnabled);
        }
    }
}
