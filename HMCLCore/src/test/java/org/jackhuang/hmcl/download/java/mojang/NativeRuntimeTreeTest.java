/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.java.mojang;

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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Checks native runtime tree failure handling and compatibility fallback.
@NotNullByDefault
@EnabledOnOs(OS.LINUX)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class NativeRuntimeTreeTest {
    /// Isolated staging directory.
    @TempDir Path temp;
    /// Original native path.
    private final @Nullable String oldPath = System.getProperty("mfcl.native.path");
    /// Original native enablement.
    private final @Nullable String oldEnabled = System.getProperty("mfcl.native.java.enabled");

    /// Restores the process properties.
    @AfterEach void restore() {
        if (oldPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", oldPath);
        if (oldEnabled == null) System.clearProperty("mfcl.native.java.enabled"); else System.setProperty("mfcl.native.java.enabled", oldEnabled);
    }

    /// Unsupported path syntax falls back before writes; mutation errors do not replay.
    @Test void unsupportedAndFailedPreparation() throws Exception {
        @Nullable String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(binary != null && !binary.isBlank());
        System.setProperty("mfcl.native.path", binary); System.clearProperty("mfcl.native.java.enabled");
        Path root = temp.resolve("staging"); Files.createDirectories(root.resolve("bin/link"));
        Files.writeString(root.resolve("bin/link/keep"), "keep");
        List<Map.Entry<String, MojangJavaRemoteFiles.Remote>> unusual = List.of(Map.entry("bin/a:b", new MojangJavaRemoteFiles.RemoteDirectory()));
        assertFalse(NativeRuntimeTree.tryPrepare(root, unusual, () -> false));
        assertFalse(Files.exists(root.resolve("bin/a:b")));
        List<Map.Entry<String, MojangJavaRemoteFiles.Remote>> entries = List.of(
                Map.entry("prepared", new MojangJavaRemoteFiles.RemoteDirectory()),
                Map.entry("bin", new MojangJavaRemoteFiles.RemoteDirectory()),
                Map.entry("bin/link", new MojangJavaRemoteFiles.RemoteLink("java")));
        RuntimePathValidator.validate(root, entries, () -> false);
        assertThrows(IOException.class, () -> NativeRuntimeTree.tryPrepare(root, entries, () -> false));
        assertTrue(Files.isDirectory(root.resolve("prepared")));
        assertEquals("keep", Files.readString(root.resolve("bin/link/keep")));
        assertThrows(InterruptedException.class, () -> NativeRuntimeTree.tryPrepare(root, entries, () -> true));
    }
}
