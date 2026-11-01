/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.modpack.curse;

import org.jackhuang.hmcl.nativebridge.NativeTransferTestSupport;
import org.jackhuang.hmcl.util.StringUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Compares CurseForge native selection with the existing completion conditions.
@NotNullByDefault
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class NativeCurseSelectionTest {
    /// Original backend path.
    private final @Nullable String oldPath = System.getProperty("mfcl.native.path");
    /// Original feature setting.
    private final @Nullable String oldEnabled = System.getProperty("mfcl.native.modpack.enabled");

    /// Restores settings and closes idle workers.
    @AfterEach void restore() {
        NativeTransferTestSupport.clearIdle();
        if (oldPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", oldPath);
        if (oldEnabled == null) System.clearProperty("mfcl.native.modpack.enabled"); else System.setProperty("mfcl.native.modpack.enabled", oldEnabled);
    }

    /// Download plans preserve algorithm priority and fall back when mutable hashes change.
    @Test void nativeDownloadPlanMatchesJava() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"); assumeTrue(executable != null && !executable.isBlank());
        System.setProperty("mfcl.native.path", executable); System.clearProperty("mfcl.native.modpack.enabled");
        Map<String, @Nullable String> mutable = new java.util.HashMap<>(); mutable.put("sha1", "first"); mutable.put("sha512", "third");
        Map<String, @Nullable String> nullFirst = new java.util.HashMap<>(); nullFirst.put("sha1", null); nullFirst.put("sha256", "second");
        List<CurseManifestFile> files = List.of(
                new CurseManifestFile(1, 2, "one.jar", null, true, mutable, null, false),
                new CurseManifestFile(3, 4, "two.jar", null, true, nullFirst, null, false),
                new CurseManifestFile(5, 6, "three.jar", null, true, Map.of("md5", "fourth"), null, false),
                new CurseManifestFile(7, 8, "none.jar", null, true),
                new CurseManifestFile(9, 10, "excluded.jar", null, false));
        var plan = NativeCurseSelection.tryPlan(files, Set.of(files.get(4).key()), () -> false);
        assertNotNull(plan); assertEquals(Set.of(0, 1, 2, 3), plan.keySet());
        for (int i = 0; i < 4; i++) assertEquals(files.get(i).getIntegrityCheck(), plan.get(i).currentIntegrity(files.get(i)));
        mutable.remove("sha1"); mutable.put("sha256", "updated");
        assertEquals(files.get(0).getIntegrityCheck(), plan.get(0).currentIntegrity(files.get(0)));
        assertThrows(InterruptedException.class, () -> NativeCurseSelection.tryPlan(files, null, () -> true));
    }

    /// Metadata and download selection preserve blank names, derived URLs and exclusions.
    @Test void nativeSelectionMatchesJava() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"); assumeTrue(executable != null && !executable.isBlank());
        System.setProperty("mfcl.native.path", executable); System.clearProperty("mfcl.native.modpack.enabled");
        List<CurseManifestFile> files = List.of(new CurseManifestFile(1, 2, null, null, true),
                new CurseManifestFile(3, 4, " \t", "https://example.test/x", true),
                new CurseManifestFile(5, 6, "mod.jar", null, true),
                new CurseManifestFile(-7, 8, "optional.jar", "", false));
        Set<String> excluded = Set.of(files.get(3).key());
        boolean @Nullable [] resolve = NativeCurseSelection.trySelect(files, null, true, () -> false);
        boolean @Nullable [] download = NativeCurseSelection.trySelect(files, excluded, false, () -> false);
        assertNotNull(resolve); assertNotNull(download);
        for (int i = 0; i < files.size(); i++) {
            CurseManifestFile file = files.get(i);
            assertEquals(StringUtils.isBlank(file.fileName()) || file.url() == null, resolve[i]);
            assertEquals(file.fileName() != null && !excluded.contains(file.key()), download[i]);
        }
        List<CurseManifestFile> unicode = List.of(new CurseManifestFile(1, 2, "模组.jar", null, true));
        assertNull(NativeCurseSelection.trySelect(unicode, null, true, () -> false));
        assertArrayEquals(new boolean[]{true}, NativeCurseSelection.trySelect(unicode, null, false, () -> false));
        assertThrows(InterruptedException.class, () -> NativeCurseSelection.trySelect(files, null, true, () -> true));
        System.setProperty("mfcl.native.modpack.enabled", "false");
        assertNull(NativeCurseSelection.trySelect(files, excluded, false, () -> false));
    }
}
