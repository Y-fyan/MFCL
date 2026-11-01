/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.nativebridge;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import com.sun.net.httpserver.HttpServer;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Verifies that the launcher checks the native process response without trusting malformed output.
@NotNullByDefault
@EnabledOnOs({OS.LINUX, OS.MAC})
public class NativeBackendTest {
    /// Directory owned by the test runner.
    @TempDir
    Path tempDir;

    /// Accepts a correctly formed response from a child process.
    @Test
    void acceptsCompatibleBackend() throws IOException {
        Path executable = script("read line\nprintf '%s\\n' '{\"version\":1,\"id\":1,\"result\":{\"name\":\"mfcl-native\",\"protocol\":1,\"read_only\":true}}'");
        assertDoesNotThrow(() -> NativeBackend.checkHealth(executable));
    }

    /// Rejects output that is not a compatible health response.
    @Test
    void rejectsInvalidBackend() throws IOException {
        Path executable = script("read line\nprintf '%s\\n' '{\"version\":2}'");
        assertThrows(IOException.class, () -> NativeBackend.checkHealth(executable));
    }

    /// Parses an offline UUID response without creating an account.
    @Test
    void parsesOfflineProfile() throws Exception {
        Path executable = script("read line\nprintf '%s\\n' '{\"version\":1,\"id\":1,\"result\":{\"username\":\"Steve\",\"uuid\":\"5627dd98-e6be-3c21-b8a8-e92344183641\"}}'");
        assertEquals(UUID.fromString("5627dd98-e6be-3c21-b8a8-e92344183641"),
                NativeBackend.calculateOfflineUuid(executable, "Steve"));
    }

    /// Rejects a profile response that identifies a different username.
    @Test
    void rejectsMismatchedOfflineProfile() throws IOException {
        Path executable = script("read line\nprintf '%s\\n' '{\"version\":1,\"id\":1,\"result\":{\"username\":\"Alex\",\"uuid\":\"5627dd98-e6be-3c21-b8a8-e92344183641\"}}'");
        assertThrows(IOException.class, () -> NativeBackend.calculateOfflineUuid(executable, "Steve"));
    }

    /// Rejects an oversized request before starting a backend process.
    @Test
    void rejectsOversizedRequest() {
        assertThrows(IOException.class, () -> NativeBackend.calculateOfflineUuid(tempDir.resolve("missing"), "x".repeat(1024 * 1024 + 1)));
    }

    /// Interrupting a request terminates its direct child process.
    @Test
    void interruptionStopsChild() throws Exception {
        Path executable = script("read line\nprintf '%s' \"$$\" > \"$0.pid\"\nwhile :; do :; done");
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        Thread caller = new Thread(() -> {
            try {
                NativeBackend.checkHealth(executable);
            } catch (InterruptedException e) {
                interrupted.set(true);
            } catch (IOException e) {
                // The child is intentionally terminated while the request is in progress.
            } finally {
                finished.countDown();
            }
        });
        caller.setDaemon(true);
        caller.start();
        try {
            Path pidFile = Path.of(executable + ".pid");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!Files.exists(pidFile) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(pidFile));
            long pid = Long.parseLong(Files.readString(pidFile));
            caller.interrupt();
            assertTrue(finished.await(3, TimeUnit.SECONDS));
            assertTrue(interrupted.get());
            ProcessHandle.of(pid).ifPresent(process -> assertDoesNotThrow(() -> process.onExit().get(3, TimeUnit.SECONDS)));
        } finally {
            caller.interrupt();
            finished.await(3, TimeUnit.SECONDS);
        }
    }

    /// Rejects a backend response that names a directory outside the staging parent.
    @Test
    void rejectsUnexpectedExtractionDirectory() throws IOException {
        Path executable = script("read line\nprintf '%s\\n' '{\"version\":1,\"id\":1,\"result\":{\"staging_directory\":\"/\"}}'");
        assertThrows(IOException.class, () -> NativeBackend.extractZip(executable, tempDir.resolve("missing.zip"), tempDir));
    }

    /// Sends a token larger than a typical pipe buffer without deadlocking the request writer.
    @Test
    void supportsLongAuthenticationRequests() throws IOException, InterruptedException {
        Path executable = script("read line\nprintf '%s\\n' '{\"version\":1,\"id\":1,\"result\":{\"ok\":true}}'");
        JsonObject params = new JsonObject();
        params.addProperty("access_token", "x".repeat(32768));
        assertTrue(NativeBackend.microsoft(executable, "microsoft_exchange", params).get("ok").getAsBoolean());
    }

    /// Preserves a recognized protocol error code without exposing backend descriptions.
    @Test
    void authenticationErrorDoesNotExposeServerMessage() throws IOException {
        Path executable = script("read line\nprintf '%s\\n' '{\"version\":1,\"id\":1,\"error\":{\"code\":\"authorization_pending\",\"message\":\"secret-token\"}}'");
        NativeBackend.OperationException error = assertThrows(NativeBackend.OperationException.class,
                () -> NativeBackend.microsoft(executable, "microsoft_device_poll", new JsonObject()));
        assertEquals("authorization_pending", error.code());
        assertFalse(error.getMessage().contains("secret-token"));
    }

    /// Rejects trailing non-whitespace after a final protocol response.
    @Test void rejectsTrailingProtocolData() throws IOException {
        Path executable = script("read line\nprintf '%s\\n' '{\"version\":1,\"id\":1,\"result\":{\"name\":\"mfcl-native\",\"protocol\":1}}' 'unexpected'");
        assertThrows(IOException.class, () -> NativeBackend.checkHealth(executable));
    }

    /// Rejects a progress event whose declared total differs from the requested batch.
    @Test void rejectsInvalidLibraryProgress() throws IOException {
        Path executable = script("read line\nprintf '%s\\n' '{\"version\":1,\"id\":1,\"event\":\"progress\",\"downloaded\":1,\"total\":2}' '{\"version\":1,\"id\":1,\"result\":{\"results\":[]}}'");
        var input = new NativeBackend.FileHashInput(0, tempDir.resolve("fixture"), "a9993e364706816aba3e25717850c26c9cd0d89d");
        assertThrows(IOException.class, () -> NativeBackend.checkFileHashes(executable, List.of(input), () -> false, (done, total) -> { }));
    }

    /// Extracts an archive through the real native process into an isolated staging directory.
    @Test
    void extractsThroughActualNativeProcess() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        Path archive = tempDir.resolve("fixture.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("mods/example.txt"));
            zip.write("abc".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path stage = NativeBackend.extractZip(Path.of(executable), archive, tempDir);
        assertEquals("abc", Files.readString(stage.resolve("mods/example.txt")));
        assertFalse(Files.exists(tempDir.resolve("mods")));
    }

    /// Sends a download through the real Rust process and an isolated loopback HTTP server.
    @Test
    void downloadsThroughActualNativeProcess() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file", exchange -> {
            byte[] data = "abc".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, data.length);
            try (var body = exchange.getResponseBody()) {
                body.write(data);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/file";
            String checksum = "a9993e364706816aba3e25717850c26c9cd0d89d";
            Path destination = tempDir.resolve("测试 文件.jar");
            assertEquals(3, NativeBackend.download(Path.of(executable), url, destination, checksum, 3, true));
            assertEquals("abc", Files.readString(destination));
            Path badDestination = tempDir.resolve("bad.jar");
            assertThrows(IOException.class, () -> NativeBackend.download(Path.of(executable), url, badDestination,
                    "0000000000000000000000000000000000000000", 3, true));
            assertFalse(Files.exists(badDestination));
        } finally {
            server.stop(0);
        }
    }

    /// Creates a temporary executable without touching any user data.
    ///
    /// @param body shell commands for the child process
    /// @return path to the executable
    /// @throws IOException if the executable cannot be created
    private Path script(String body) throws IOException {
        Path path = tempDir.resolve("native-test");
        Files.writeString(path, "#!/bin/sh\n" + body + "\n");
        if (!path.toFile().setExecutable(true)) {
            throw new IOException("Cannot mark test executable");
        }
        return path;
    }
}
