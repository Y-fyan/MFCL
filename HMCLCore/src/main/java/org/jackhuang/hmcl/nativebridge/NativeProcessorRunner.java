/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.nativebridge;

import org.jackhuang.hmcl.util.platform.ManagedProcess;
import org.jackhuang.hmcl.util.platform.SystemUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Starts installer processors through Rust where supported and retains their standard output.
@NotNullByDefault
public final class NativeProcessorRunner {
    /// Prevents instantiation.
    private NativeProcessorRunner() { }

    /// Executes a processor once, preserving its current directory, environment and exit status.
    /// @param command program and arguments
    /// @return processor exit code
    /// @throws IOException if process creation or communication fails
    /// @throws InterruptedException if interrupted
    public static int run(List<String> command) throws IOException, InterruptedException {
        @Nullable String configured = System.getProperty("mfcl.native.path");
        if (configured == null || configured.isBlank() || !NativeLaunch.supportsCommand(command)
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.processors.enabled", "true"))) {
            return SystemUtils.callExternalProcess(command);
        }
        Path executable;
        int protocol;
        try {
            executable = Path.of(configured);
            protocol = NativeBackend.launchExecProtocol(executable);
            if (protocol == 0) return SystemUtils.callExternalProcess(command);
        } catch (IOException | RuntimeException e) {
            LOG.warning("Native processor launch is unavailable; using Java process creation");
            return SystemUtils.callExternalProcess(command);
        }
        Process child = NativeLaunch.start(executable, command, Path.of("").toAbsolutePath().normalize(), Map.of(), protocol);
        ManagedProcess managed = new ManagedProcess(child, command);
        managed.pumpInputStream(SystemUtils::onLogLine);
        managed.pumpErrorStream(SystemUtils::onLogLine);
        try {
            return child.waitFor();
        } catch (InterruptedException e) {
            child.destroy();
            try {
                if (!child.waitFor(2, TimeUnit.SECONDS)) child.destroyForcibly();
            } catch (InterruptedException ignored) { child.destroyForcibly(); }
            Thread.currentThread().interrupt();
            throw e;
        }
    }
}
