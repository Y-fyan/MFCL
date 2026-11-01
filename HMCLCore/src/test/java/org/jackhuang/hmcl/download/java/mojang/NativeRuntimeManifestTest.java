/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.java.mojang;

import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Compares Rust runtime manifest parsing with the existing Gson implementation.
@NotNullByDefault
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class NativeRuntimeManifestTest {
    /// Original executable setting.
    private final @Nullable String oldPath = System.getProperty("mfcl.native.path");
    /// Original runtime setting.
    private final @Nullable String oldEnabled = System.getProperty("mfcl.native.java.enabled");

    /// Restores process properties.
    @AfterEach void restore() {
        if (oldPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", oldPath);
        if (oldEnabled == null) System.clearProperty("mfcl.native.java.enabled"); else System.setProperty("mfcl.native.java.enabled", oldEnabled);
    }

    /// Real official platform manifests preserve entry order and all interpreted properties.
    @Test void officialManifestsMatchJava() throws Exception {
        @Nullable String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        @Nullable String fixtureDir = System.getenv("MFCL_RUNTIME_FIXTURES");
        assumeTrue(binary != null && !binary.isBlank() && fixtureDir != null && !fixtureDir.isBlank());
        System.setProperty("mfcl.native.path", binary); System.clearProperty("mfcl.native.java.enabled");
        for (String platform : new String[]{"linux", "mac", "windows"}) {
            String metadata = Files.readString(Path.of(fixtureDir, "mojang-runtime-" + platform + ".json"));
            MojangJavaRemoteFiles javaFiles = JsonUtils.fromNonNullJson(metadata, MojangJavaRemoteFiles.class);
            @Nullable MojangJavaRemoteFiles nativeFiles = NativeRuntimeManifest.tryParse(metadata, () -> false);
            assertNotNull(nativeFiles, platform);
            assertEquals(new ArrayList<>(javaFiles.files().keySet()), new ArrayList<>(nativeFiles.files().keySet()));
            for (String path : javaFiles.files().keySet()) {
                MojangJavaRemoteFiles.Remote expected = javaFiles.files().get(path), actual = nativeFiles.files().get(path);
                assertEquals(expected.getClass(), actual.getClass(), path);
                assertEquals(expected.getType(), actual.getType(), path);
                if (expected instanceof MojangJavaRemoteFiles.RemoteLink link)
                    assertEquals(link.getTarget(), ((MojangJavaRemoteFiles.RemoteLink) actual).getTarget(), path);
                if (expected instanceof MojangJavaRemoteFiles.RemoteFile file) {
                    MojangJavaRemoteFiles.RemoteFile nativeFile = (MojangJavaRemoteFiles.RemoteFile) actual;
                    assertEquals(file.isExecutable(), nativeFile.isExecutable(), path);
                    assertEquals(file.getDownloads().keySet(), nativeFile.getDownloads().keySet(), path);
                    for (String name : file.getDownloads().keySet()) {
                        var left = file.getDownloads().get(name); var right = nativeFile.getDownloads().get(name);
                        assertEquals(left.getUrl(), right.getUrl(), path);
                        assertEquals(left.getSha1(), right.getSha1(), path);
                        assertEquals(left.getSize(), right.getSize(), path);
                    }
                }
            }
        }
        assertNull(NativeRuntimeManifest.tryParse("{\"files\":{\"a\":{\"type\":\"directory\"},\"a\":{\"type\":\"directory\"}}}", () -> false));
        assertThrows(InterruptedException.class, () -> NativeRuntimeManifest.tryParse("{}", () -> true));
    }
}
