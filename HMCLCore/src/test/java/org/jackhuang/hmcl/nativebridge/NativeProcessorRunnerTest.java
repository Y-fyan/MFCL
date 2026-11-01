/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.nativebridge;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Tests native creation of Forge-family processor subprocesses.
@NotNullByDefault
@EnabledOnOs({OS.LINUX, OS.MAC})
@ResourceLock(Resources.SYSTEM_PROPERTIES)
public final class NativeProcessorRunnerTest {
    /// Temporary scripts and output.
    @TempDir Path temp;
    /// Previous backend path.
    private final @Nullable String previousPath = System.getProperty("mfcl.native.path");
    /// Previous processor setting.
    private final @Nullable String previousEnabled = System.getProperty("mfcl.native.processors.enabled");

    /// Restores global settings.
    @AfterEach void restore() {
        if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
        if (previousEnabled == null) System.clearProperty("mfcl.native.processors.enabled"); else System.setProperty("mfcl.native.processors.enabled", previousEnabled);
    }

    /// The real Rust child preserves argument boundaries and the processor exit code.
    @Test void nativeProcessorPreservesArgumentsAndExitCode() throws Exception {
        Path wrapper = nativeWrapper(); System.setProperty("mfcl.native.path", wrapper.toString());
        System.clearProperty("mfcl.native.processors.enabled"); Path output = temp.resolve("result");
        int exit = NativeProcessorRunner.run(List.of("/bin/sh", "-c", "printf '%s' \"$1\" > \"$2\"; exit 7", "fixture", "中文 value $ literal", output.toString()));
        assertEquals(7, exit); assertEquals("中文 value $ literal", Files.readString(output));
        assertEquals(1, Files.readAllLines(Path.of(wrapper + ".executions")).size());
    }

    /// A backend without the capability falls back before the processor is started.
    @Test void missingCapabilityUsesJavaOnce() throws Exception {
        Path wrapper = temp.resolve("old-backend");
        Files.writeString(wrapper, "#!/bin/sh\nread line\nprintf '%s\\n' '{\"version\":1,\"id\":1,\"result\":{\"name\":\"mfcl-native\",\"protocol\":1,\"capabilities\":[]}}'\n");
        assertTrue(wrapper.toFile().setExecutable(true)); System.setProperty("mfcl.native.path", wrapper.toString());
        Path output = temp.resolve("fallback");
        assertEquals(0, NativeProcessorRunner.run(List.of("/bin/sh", "-c", "printf fallback > \"$1\"", "fixture", output.toString())));
        assertEquals("fallback", Files.readString(output));
    }

    /// An exec failure is returned rather than replaying a possibly started processor.
    @Test void nativeExecFailureDoesNotReplay() throws Exception {
        Path wrapper = nativeWrapper(); System.setProperty("mfcl.native.path", wrapper.toString());
        assertEquals(127, NativeProcessorRunner.run(List.of(temp.resolve("missing-program").toString())));
        assertEquals(1, Files.readAllLines(Path.of(wrapper + ".executions")).size());
    }

    /// Interrupting a running processor stops only that test-owned child and preserves interruption.
    @Test void interruptionStopsNativeProcessor() throws Exception {
        Path wrapper = nativeWrapper(); System.setProperty("mfcl.native.path", wrapper.toString());
        Path pidFile = temp.resolve("processor.pid"); AtomicBoolean interruptRetained = new AtomicBoolean();
        AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                NativeProcessorRunner.run(List.of("/bin/sh", "-c", "printf '%s' \"$$\" > \"$1\"; exec sleep 30", "fixture", pidFile.toString()));
                failure.set(new AssertionError("Expected interruption"));
            } catch (InterruptedException e) { interruptRetained.set(Thread.currentThread().isInterrupted()); }
            catch (Throwable e) { failure.set(e); }
        });
        worker.setDaemon(true); worker.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(pidFile) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(pidFile)); long pid = Long.parseLong(Files.readString(pidFile));
            worker.interrupt(); worker.join(5000); assertFalse(worker.isAlive()); assertNull(failure.get()); assertTrue(interruptRetained.get());
            ProcessHandle.of(pid).ifPresent(process -> assertDoesNotThrow(() -> process.onExit().get(3, TimeUnit.SECONDS)));
        } finally { worker.interrupt(); worker.join(5000); }
    }

    /// Creates a wrapper that records exec requests and delegates to the real backend.
    /// @return wrapper executable
    /// @throws Exception if creation fails
    private Path nativeWrapper() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        Path wrapper = temp.resolve("backend");
        Files.writeString(wrapper, "#!/bin/sh\nif [ \"${1-}\" = \"--exec-child\" ] || [ \"${1-}\" = \"--exec-child-v2\" ]; then printf '%s\\n' run >> \"$0.executions\"; fi\nexec '" + executable.replace("'", "'\\''") + "' \"$@\"\n");
        assertTrue(wrapper.toFile().setExecutable(true)); return wrapper;
    }
}
