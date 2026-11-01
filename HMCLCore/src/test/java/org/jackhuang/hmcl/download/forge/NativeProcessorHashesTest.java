/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.forge;

import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Tests stable native observations of Forge-family processor outputs.
@NotNullByDefault
@EnabledOnOs(OS.LINUX)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class NativeProcessorHashesTest {
    /// Isolated processor files.
    @TempDir Path temp;
    /// Original backend path.
    private final @Nullable String previousPath = System.getProperty("mfcl.native.path");
    /// Original processor setting.
    private final @Nullable String previousEnabled = System.getProperty("mfcl.native.processors.enabled");

    /// Restores process-wide settings.
    @AfterEach void restore() {
        if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
        if (previousEnabled == null) System.clearProperty("mfcl.native.processors.enabled"); else System.setProperty("mfcl.native.processors.enabled", previousEnabled);
    }

    /// Correct hashes are used only while file identity is unchanged; mismatches retain Java handling.
    @Test void verifiesStableOutputsAndDetectsChanges() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        System.setProperty("mfcl.native.path", executable); System.clearProperty("mfcl.native.processors.enabled");
        Path correct = temp.resolve("正确.jar"), wrong = temp.resolve("wrong.jar");
        Files.writeString(correct, "abc"); Files.writeString(wrong, "different");
        String digest = "a9993e364706816aba3e25717850c26c9cd0d89d";
        List<Map.Entry<String, String>> outputs = List.of(Map.entry(correct.toString(), digest), Map.entry(wrong.toString(), digest),
                Map.entry(correct.toString(), digest.toUpperCase(java.util.Locale.ROOT)), Map.entry(temp.resolve("missing").toString(), digest));
        Map<Integer, NativeBackend.FileHashResult> observations = NativeProcessorHashes.inspectEntries(outputs, () -> false);
        assertTrue(NativeProcessorHashes.isVerified(correct, digest, observations.get(0)));
        assertFalse(NativeProcessorHashes.isVerified(wrong, digest, observations.get(1)));
        assertNull(observations.get(2)); assertNull(observations.get(3));
        Files.writeString(correct, "changed size");
        assertFalse(NativeProcessorHashes.isVerified(correct, digest, observations.get(0)));
        assertThrows(InterruptedException.class, () -> NativeProcessorHashes.inspectEntries(outputs, () -> true));
        System.setProperty("mfcl.native.processors.enabled", "false");
        assertTrue(NativeProcessorHashes.inspectEntries(outputs, () -> false).isEmpty());
    }
}
