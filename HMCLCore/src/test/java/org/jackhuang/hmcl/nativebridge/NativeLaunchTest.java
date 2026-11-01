/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.nativebridge;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Verifies native process replacement with isolated synthetic commands.
@NotNullByDefault
@EnabledOnOs({OS.LINUX, OS.MAC})
public final class NativeLaunchTest {
    /// Test-owned working directory.
    @TempDir Path tempDir;

    /// Native process replacement preserves PID, working directory, environment, input and output.
    @Test void preservesProcessIdentityAndStreams() throws Exception {
        Path executable = nativeExecutable();
        assertTrue(NativeBackend.supportsLaunchExec(executable));
        Path work = tempDir.resolve("测试 space"); Files.createDirectory(work);
        String script = "IFS= read -r line; printf '%s\\n%s\\n%s\\n%s\\n' \"$$\" \"$PWD\" \"$MFCL_TEST_VALUE\" \"$line\"; printf 'stderr-line\\n' >&2; exit 7";
        Process process = NativeLaunch.start(executable, List.of("/bin/sh", "-c", script), work, Map.of("MFCL_TEST_VALUE", "sample"));
        try {
            process.getOutputStream().write("input after launch\n".getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            List<String> output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(7, process.exitValue()); assertEquals(Long.toString(process.pid()), output.get(0));
            assertEquals(work.toString(), output.get(1)); assertEquals("sample", output.get(2)); assertEquals("input after launch", output.get(3));
            assertEquals("stderr-line", error.trim());
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    @Test void lengthFramingKeepsGameInputAndProcessIdentity() throws Exception {
        Path executable = nativeExecutable(); assertEquals(2, NativeBackend.launchExecProtocol(executable));
        Process process = NativeLaunch.start(executable, List.of("/bin/sh", "-c", "IFS= read -r x; printf '%s %s\\n' \"$$\" \"$x\""),
                tempDir, Map.of(), 2);
        try {
            process.getOutputStream().write("后续输入\n".getBytes(StandardCharsets.UTF_8)); process.getOutputStream().close();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, process.exitValue());
            assertEquals(process.pid() + " 后续输入", new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    @Test void rejectsCommandsThatCannotCrossTheNativeChannelExactly() throws Exception {
        assertTrue(NativeLaunch.supportsCommand(List.of("/bin/echo", "中文", "")));
        assertFalse(NativeLaunch.supportsCommand(List.of("/bin/echo", "\uD800")));
        assertFalse(NativeLaunch.supportsCommand(List.of("/bin/echo", "a\0b")));
        assertFalse(NativeLaunch.supportsCommand(List.of("/bin/echo", "x".repeat(1024 * 1024))));
        Path marker = tempDir.resolve("must-not-start");
        Path fake = tempDir.resolve("backend-rejected");
        Files.writeString(fake, "#!/bin/sh\n: > '" + marker + "'\n"); assertTrue(fake.toFile().setExecutable(true));
        assertThrows(java.io.IOException.class, () -> NativeLaunch.start(fake, List.of("/bin/echo", "\uD800"), tempDir, Map.of(), 2));
        assertFalse(Files.exists(marker));
    }

    /// Destroying the returned process terminates the exec-replaced program.
    @Test void stopTerminatesReplacedProcess() throws Exception {
        Path executable = nativeExecutable();
        Process process = NativeLaunch.start(executable, List.of("/bin/sh", "-c", "printf 'ready\\n'; exec sleep 30"), tempDir, Map.of());
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            assertEquals("ready", reader.readLine());
            long pid = process.pid();
            process.destroy();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            ProcessHandle.of(pid).ifPresent(handle -> assertFalse(handle.isAlive()));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    /// Failed exec reports a nonzero exit without echoing command credentials.
    @Test void missingProgramDoesNotExposeArguments() throws Exception {
        Path executable = nativeExecutable();
        Process process = NativeLaunch.start(executable, List.of("/does-not-exist", "secret-token"), tempDir, Map.of());
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(127, process.exitValue());
            assertFalse(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).contains("secret-token"));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    /// A backend without the process-replacement capability is not selected.
    @Test void oldBackendDoesNotAdvertiseLaunch() throws Exception {
        Path fake = tempDir.resolve("old-backend");
        Files.writeString(fake, "#!/bin/sh\nread request\nprintf '%s\\n' '{\"version\":1,\"id\":1,\"result\":{\"name\":\"mfcl-native\",\"protocol\":1,\"capabilities\":[]}}'\n");
        assertTrue(fake.toFile().setExecutable(true));
        assertFalse(NativeBackend.supportsLaunchExec(fake));
    }

    /// Oversized commands are rejected before the native child is started.
    @Test void oversizedCommandDoesNotStartBackend() throws Exception {
        Path fake = tempDir.resolve("backend");
        Files.writeString(fake, "#!/bin/sh\n: > \"$0.started\"\n");
        assertTrue(fake.toFile().setExecutable(true));
        assertThrows(java.io.IOException.class, () -> NativeLaunch.start(fake,
                List.of("/bin/sh", "x".repeat(1024 * 1024)), tempDir, Map.of()));
        assertFalse(Files.exists(Path.of(fake + ".started")));
    }

    /// Returns the explicitly supplied native test executable or skips the test.
    ///
    /// @return executable path
    private static Path nativeExecutable() {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        return Path.of(executable);
    }
}
