/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.launch;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Compares native legacy BootstrapLauncher ignore-list planning with Java.
@NotNullByDefault
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class NativeBootstrapIgnoreTest {
    /// Isolated classpath root.
    @TempDir Path temp;
    /// Original backend setting.
    private final @Nullable String oldPath = System.getProperty("mfcl.native.path");
    /// Original launch setting.
    private final @Nullable String oldEnabled = System.getProperty("mfcl.native.launch.enabled");

    /// Restores shared settings.
    @AfterEach void restore() {
        if (oldPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", oldPath);
        if (oldEnabled == null) System.clearProperty("mfcl.native.launch.enabled"); else System.setProperty("mfcl.native.launch.enabled", oldEnabled);
    }

    /// Empty tokens, commas and paths outside the library root retain Java behavior.
    @Test void nativePlanningMatchesJava() throws Exception {
        @Nullable String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(binary != null && !binary.isBlank());
        System.setProperty("mfcl.native.path", binary); System.clearProperty("mfcl.native.launch.enabled");
        Path root = temp.resolve("libraries").toAbsolutePath().normalize();
        var paths = new LinkedHashSet<String>();
        paths.add(root.resolve("org/asm/asm-9.jar").toString());
        paths.add(root.resolve("org/other,comma/safe.jar").toString());
        paths.add(temp.resolve("outside/bootstrap.jar").toAbsolutePath().toString());
        List<String> lists = List.of("asm,bootstrap,", "", ",,", "safe,,bootstrap", "missing");
        @Nullable List<String> actual = NativeBootstrapIgnore.tryPlan(lists, paths, root);
        assertNotNull(actual);
        for (int i = 0; i < lists.size(); i++) assertEquals(DefaultLauncher.rewriteIgnoreList(lists.get(i), paths, root), actual.get(i));
    }
}
