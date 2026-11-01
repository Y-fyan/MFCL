/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.launch;

import org.jackhuang.hmcl.util.platform.CommandBuilder;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Compares native launch assembly with Java ordering and script quoting.
@NotNullByDefault
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class NativeLaunchAssemblyTest {
    /// Original backend path.
    private final @Nullable String oldPath = System.getProperty("mfcl.native.path");
    /// Original native launch setting.
    private final @Nullable String oldEnabled = System.getProperty("mfcl.native.launch.enabled");

    /// Restores global settings.
    @AfterEach void restore() {
        if (oldPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", oldPath);
        if (oldEnabled == null) System.clearProperty("mfcl.native.launch.enabled"); else System.setProperty("mfcl.native.launch.enabled", oldEnabled);
    }

    /// Argument sections and parse flags match Java for both shell and batch output.
    @Test void nativeSectionsPreserveOrderingAndQuoting() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"); assumeTrue(executable != null && !executable.isBlank());
        System.setProperty("mfcl.native.path", executable); System.clearProperty("mfcl.native.launch.enabled");
        List<List<String>> groups = List.of(List.of("-Dpath=space value", "-Dx=\"quoted\""), List.of("auth-jvm"),
                List.of("legacy"), List.of("--token", "synthetic-token"), List.of("--default"),
                List.of("auth-game"), List.of("raw '$HOME'", "--custom"));
        List<String> agents = List.of("/tmp/agent one.jar", "agent-two.jar"); String main = "example.Main";
        @Nullable NativeLaunchAssembly.Plan plan = NativeLaunchAssembly.tryPlan(groups, agents, main); assertNotNull(plan);
        for (OperatingSystem os : List.of(OperatingSystem.LINUX, OperatingSystem.WINDOWS)) {
            CommandBuilder java = new CommandBuilder(os), nativeCommand = new CommandBuilder(os);
            java.addAll(groups.get(0)); java.addAll(groups.get(1));
            for (String agent : agents) java.add("-javaagent:" + agent);
            java.add(main); for (int i = 2; i < 6; i++) java.addAll(groups.get(i));
            java.add("--fullscreen"); java.addAllWithoutParsing(groups.get(6));
            nativeCommand.addAll(plan.before()); nativeCommand.add("--fullscreen"); nativeCommand.addAllWithoutParsing(plan.after());
            assertEquals(java.asList(), nativeCommand.asList()); assertEquals(java.toString(), nativeCommand.toString());
        }
        System.setProperty("mfcl.native.launch.enabled", "false");
        assertNull(NativeLaunchAssembly.tryPlan(groups, agents, main));
    }
}
