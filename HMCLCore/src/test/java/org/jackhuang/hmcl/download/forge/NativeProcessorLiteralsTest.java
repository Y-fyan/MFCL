/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.forge;

import org.jackhuang.hmcl.download.neoforge.NeoForgeOldInstallTask;
import org.jackhuang.hmcl.game.Artifact;
import org.jackhuang.hmcl.game.DefaultGameRepositoryLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Compares the Rust processor parser with the existing Forge-family parsers.
@NotNullByDefault
@ResourceLock(Resources.SYSTEM_PROPERTIES)
public final class NativeProcessorLiteralsTest {
    /// Isolated repository path.
    @TempDir Path temp;
    /// Original backend setting.
    private final @Nullable String previousPath = System.getProperty("mfcl.native.path");
    /// Original processor setting.
    private final @Nullable String previousEnabled = System.getProperty("mfcl.native.processors.enabled");

    /// Restores process-wide settings after each test.
    @AfterEach void restore() {
        if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
        if (previousEnabled == null) System.clearProperty("mfcl.native.processors.enabled"); else System.setProperty("mfcl.native.processors.enabled", previousEnabled);
    }

    /// Valid values match Java expansion, including null and resolved repository paths.
    @Test void matchesBothJavaParsers() throws Exception {
        configureNative();
        var layout = new DefaultGameRepositoryLayout(temp.resolve("目录 with space"));
        Map<String, String> variables = new HashMap<>(); variables.put("SIDE", "client 🦀"); variables.put("empty", null);
        List<String> ordinary = List.of("prefix-{SIDE}-'quoted'", "escaped-\\{SIDE\\}", "null-{empty}", "中文 🦀", "'a\\'b' suffix");
        List<@Nullable String> actual = NativeProcessorLiterals.tryExpand(ordinary, variables, layout, () -> false);
        assertNotNull(actual);
        for (Class<?> type : List.of(ForgeNewInstallTask.class, NeoForgeOldInstallTask.class)) {
            Method parser = type.getDeclaredMethod("replaceTokens", Map.class, String.class); parser.setAccessible(true);
            for (int i = 0; i < ordinary.size(); i++) assertEquals(parser.invoke(null, variables, ordinary.get(i)), actual.get(i));
        }
        List<@Nullable String> special = NativeProcessorLiterals.tryExpand(
                List.of("{SIDE}", "{missing}", "'quoted'", "'", "[g:a:1]"), variables, layout, () -> false);
        assertEquals(Arrays.asList("client 🦀", null, "quoted", "'", layout.getArtifactFile(Artifact.fromDescriptor("g:a:1")).toString()), special);
        assertThrows(UnsupportedOperationException.class, () -> special.add("unexpected"));
        var custom = new DefaultGameRepositoryLayout(temp) {
            @Override public Path getArtifactFile(Artifact artifact) { return temp.resolve("custom").resolve(artifact.getFileName()); }
        };
        assertEquals(List.of(custom.getArtifactFile(Artifact.fromDescriptor("g:a:1")).toString()),
                NativeProcessorLiterals.tryExpand(List.of("[g:a:1]"), variables, custom, () -> false));
        assertEquals(List.of(layout.getArtifactFile(Artifact.fromDescriptor("g:a:v@zip:classifier")).toString()),
                NativeProcessorLiterals.tryExpand(List.of("[g:a:v@zip:classifier]"), variables, layout, () -> false));
    }

    /// Invalid patterns and malformed Unicode fall back to the Java implementation.
    @Test void invalidValuesFallBackAndCancellationPropagates() throws Exception {
        configureNative(); var layout = new DefaultGameRepositoryLayout(temp);
        assertNull(NativeProcessorLiterals.tryExpand(List.of("prefix-{missing}"), Map.of(), layout, () -> false));
        assertNull(NativeProcessorLiterals.tryExpand(List.of("bad\\"), Map.of(), layout, () -> false));
        assertNull(NativeProcessorLiterals.tryExpand(List.of("\ud800"), Map.of(), layout, () -> false));
        assertThrows(InterruptedException.class, () -> NativeProcessorLiterals.tryExpand(List.of("x"), Map.of(), layout, () -> true));
        System.setProperty("mfcl.native.processors.enabled", "false");
        assertNull(NativeProcessorLiterals.tryExpand(List.of("x"), Map.of(), layout, () -> false));
    }

    /// Enables the real test backend.
    private void configureNative() {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        System.setProperty("mfcl.native.path", executable);
        System.clearProperty("mfcl.native.processors.enabled");
    }
}
