/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.java.mojang;

import org.jackhuang.hmcl.game.DownloadInfo;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jackhuang.hmcl.nativebridge.NativeTransferTestSupport;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Tests native batch verification of already downloaded runtime files.
@NotNullByDefault
@EnabledOnOs(OS.LINUX)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class NativeRuntimeFileHashesTest {
    /// Isolated runtime staging root.
    @TempDir Path temp;
    /// Original native backend setting.
    private final @Nullable String oldPath = System.getProperty("mfcl.native.path");
    /// Original Java runtime native setting.
    private final @Nullable String oldEnabled = System.getProperty("mfcl.native.java.enabled");

    /// Restores test settings and stops idle native workers.
    @AfterEach void restore() {
        NativeTransferTestSupport.clearIdle();
        if (oldPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", oldPath);
        if (oldEnabled == null) System.clearProperty("mfcl.native.java.enabled"); else System.setProperty("mfcl.native.java.enabled", oldEnabled);
    }

    /// Matching, corrupt, missing and changed files retain the existing checksum behavior.
    @Test void checksRuntimeFilesAndRejectsStaleObservations() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        System.setProperty("mfcl.native.path", executable); System.clearProperty("mfcl.native.java.enabled");
        Path root = temp.resolve("runtime"); Files.createDirectories(root.resolve("bin"));
        Path good = root.resolve("bin/java"); Path corrupt = root.resolve("bin/other");
        Files.writeString(good, "abc"); Files.writeString(corrupt, "bad"); Files.writeString(temp.resolve("outside"), "abc");
        String hash = DigestUtils.digestToString("SHA-1", "abc".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        DownloadInfo raw = new DownloadInfo("https://example.invalid/java", hash, 3);
        var file = new MojangJavaRemoteFiles.RemoteFile(false, Map.of("raw", raw));
        Map<String, MojangJavaRemoteFiles.Remote> manifest = new LinkedHashMap<>();
        manifest.put("bin/java", file); manifest.put("bin/other", file);
        manifest.put("bin/missing", file); manifest.put("../outside", file);
        var observations = NativeRuntimeFileHashes.inspect(root, new ArrayList<>(manifest.entrySet()), () -> false);
        assertEquals(2, observations.size());
        NativeBackend.FileHashResult goodResult = java.util.Objects.requireNonNull(observations.get(0));
        NativeBackend.FileHashResult badResult = java.util.Objects.requireNonNull(observations.get(1));
        assertTrue(goodResult.matches()); assertFalse(badResult.matches());
        assertDoesNotThrow(() -> MojangJavaDownloadTask.verifyRuntimeFile(good, raw, goodResult));
        assertThrows(IOException.class, () -> MojangJavaDownloadTask.verifyRuntimeFile(corrupt, raw, badResult));
        Files.writeString(good, "bad");
        assertFalse(NativeBackend.isFileHashObservationCurrent(good, goodResult));
        assertThrows(IOException.class, () -> MojangJavaDownloadTask.verifyRuntimeFile(good, raw, goodResult));
        System.setProperty("mfcl.native.java.enabled", "false");
        assertTrue(NativeRuntimeFileHashes.inspect(root, new ArrayList<>(manifest.entrySet()), () -> false).isEmpty());
    }
}
