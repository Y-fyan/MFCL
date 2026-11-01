/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.nativebridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/// Starts a process through a Unix native backend that replaces itself with the game process.
@NotNullByDefault
public final class NativeLaunch {
    /// Maximum encoded request size accepted by the native backend.
    private static final int MAX_REQUEST = 1024 * 1024;
    /// Maximum wait for sending the launch request.
    private static final long SEND_TIMEOUT_SECONDS = 10;

    /// Prevents instantiation.
    private NativeLaunch() { }

    /// Checks that a command can cross the native UTF-8 control channel without modification.
    public static boolean supportsCommand(List<String> command) {
        return encodeRequest(command, 1) != null;
    }

    /// Encodes a bounded request, returning null for strings that cannot be passed exactly.
    private static byte @Nullable [] encodeRequest(List<String> command, int protocol) {
        if (command.isEmpty() || command.get(0) == null || command.get(0).isEmpty()) return null;
        JsonArray arguments = new JsonArray();
        var encoder = StandardCharsets.UTF_8.newEncoder();
        for (@Nullable String argument : command) {
            if (argument == null || argument.indexOf('\0') >= 0 || !encoder.canEncode(argument)) return null;
            arguments.add(argument);
        }
        JsonObject request = new JsonObject(); request.add("command", arguments);
        byte[] bytes = (request + (protocol == 1 ? "\n" : "")).getBytes(StandardCharsets.UTF_8);
        return bytes.length <= MAX_REQUEST ? bytes : null;
    }

    /// Starts the native child and sends the game command over its private standard input.
    ///
    /// The returned process retains the same PID and streams after Unix process replacement.
    /// A failure after the request is sent is reported by the returned process's exit status and
    /// stderr, not as a synchronous Java process-creation error. The caller must have checked the
    /// `launch_exec` capability. Credentials in the command are not placed in the native child's
    /// argument list or diagnostic output.
    ///
    /// @param executable native backend executable
    /// @param command game program and arguments
    /// @param workingDirectory game working directory
    /// @param environment variables to add to the inherited environment
    /// @return child process, subsequently replaced by the game
    /// @throws IOException if starting the child or sending the command fails
    /// @throws InterruptedException if interrupted while sending the request
    public static Process start(Path executable, List<String> command, Path workingDirectory, Map<String, String> environment)
            throws IOException, InterruptedException {
        return start(executable, command, workingDirectory, environment, 1);
    }

    /// Starts with either the legacy newline protocol or the bounded length-prefixed protocol.
    public static Process start(Path executable, List<String> command, Path workingDirectory, Map<String, String> environment, int protocol)
            throws IOException, InterruptedException {
        if (protocol != 1 && protocol != 2) throw new IOException("Unsupported native launch protocol");
        byte @Nullable [] bytes = encodeRequest(command, protocol);
        if (bytes == null) throw new IOException("Unsupported native launch command");
        ProcessBuilder builder = new ProcessBuilder(executable.toString(), protocol == 2 ? "--exec-child-v2" : "--exec-child").directory(workingDirectory.toFile());
        builder.environment().putAll(environment);
        Process process = builder.start();
        FutureTask<Void> writer = new FutureTask<>(() -> {
            if (protocol == 2) {
                int length = bytes.length;
                process.getOutputStream().write(new byte[]{(byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8), (byte) length});
            }
            process.getOutputStream().write(bytes);
            process.getOutputStream().flush();
            return null;
        });
        Thread writerThread = new Thread(writer, "MFCL-native-launch-writer");
        writerThread.setDaemon(true);
        writerThread.start();
        try {
            writer.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return process;
        } catch (ExecutionException | TimeoutException e) {
            process.destroyForcibly();
            throw new IOException("Could not send native launch request", e);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        } finally {
            writer.cancel(true);
        }
    }
}
