/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.nativebridge;

import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Tests native download progress and cancellation with isolated fixtures.
@NotNullByDefault
@EnabledOnOs({OS.LINUX, OS.MAC})
public final class NativeTransferTest {
    /// Test-owned temporary directory.
    @TempDir Path tempDir;

    /// Removes reusable children between isolated tests.
    @AfterEach void closeIdleProcesses() { NativeDownloadPool.clearIdle(); }

    /// The real native transfer publishes only content matching its requested SHA-512.
    @Test void sha512TransferChecksContentBeforePublishing() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file", exchange -> {
            byte[] bytes = "abc".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/file";
            String hash = "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f";
            Path good = tempDir.resolve("good"); Path bad = tempDir.resolve("bad");
            assertEquals(3, NativeTransfer.downloadSha512(Path.of(executable), url, good, hash, 3, (n, t) -> { }, () -> false, true));
            assertEquals("abc", Files.readString(good));
            assertThrows(java.io.IOException.class, () -> NativeTransfer.downloadSha512(Path.of(executable), url, bad,
                    "0".repeat(128), 3, (n, t) -> { }, () -> false, true));
            assertFalse(Files.exists(bad));
        } finally { server.stop(0); }
    }

    /// Consecutive transfers reuse the same Rust process and HTTP connection.
    @Test void reusesBackendAndHttpConnection() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        Path wrapper = tempDir.resolve("backend");
        Files.writeString(wrapper, "#!/bin/sh\nprintf '%s\\n' \"$$\" >> \"$0.pids\"\nexec '" + executable.replace("'", "'\\''") + "' \"$@\"\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        var remotePorts = java.util.concurrent.ConcurrentHashMap.<Integer>newKeySet();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file", exchange -> {
            remotePorts.add(exchange.getRemoteAddress().getPort());
            byte[] bytes = "abc".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/file";
            for (int i = 0; i < 3; i++) {
                Path destination = tempDir.resolve("file-" + i);
                assertEquals(3, NativeTransfer.download(wrapper, url, destination,
                        "a9993e364706816aba3e25717850c26c9cd0d89d", 3, (done, total) -> { }, () -> false, true));
                assertEquals("abc", Files.readString(destination));
            }
            assertEquals(1, Files.readAllLines(Path.of(wrapper + ".pids")).size(), "backend should be reused");
            assertEquals(1, remotePorts.size(), "HTTP connection should be reused");
        } finally { server.stop(0); }
    }

    /// An exited idle backend is replaced on the next transfer.
    @Test void replacesExitedBackend() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        Path wrapper = tempDir.resolve("backend");
        Files.writeString(wrapper, "#!/bin/sh\nprintf '%s\\n' \"$$\" >> \"$0.pids\"\nexec '" + executable.replace("'", "'\\''") + "' \"$@\"\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file", exchange -> {
            byte[] bytes = "abc".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/file";
            String sha1 = "a9993e364706816aba3e25717850c26c9cd0d89d";
            NativeTransfer.download(wrapper, url, tempDir.resolve("first"), sha1, 3, (done, total) -> { }, () -> false, true);
            long pid = Long.parseLong(Files.readAllLines(Path.of(wrapper + ".pids")).get(0));
            ProcessHandle process = ProcessHandle.of(pid).orElseThrow(); process.destroyForcibly(); process.onExit().get(5, TimeUnit.SECONDS);
            NativeTransfer.download(wrapper, url, tempDir.resolve("second"), sha1, 3, (done, total) -> { }, () -> false, true);
            assertEquals(2, Files.readAllLines(Path.of(wrapper + ".pids")).size());
            assertEquals("abc", Files.readString(tempDir.resolve("second")));
        } finally { server.stop(0); }
    }

    /// Replacing the executable invalidates an otherwise idle worker.
    @Test void replacesWorkerWhenExecutableChanges() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        Path wrapper = tempDir.resolve("backend");
        String script = "#!/bin/sh\nprintf '%s\\n' \"$$\" >> \"$0.pids\"\nexec '" + executable.replace("'", "'\\''") + "' \"$@\"\n";
        Files.writeString(wrapper, script); assertTrue(wrapper.toFile().setExecutable(true));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file", exchange -> {
            byte[] bytes = "abc".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/file";
            String sha1 = "a9993e364706816aba3e25717850c26c9cd0d89d";
            NativeTransfer.download(wrapper, url, tempDir.resolve("first"), sha1, 3, (done, total) -> { }, () -> false, true);
            Files.writeString(wrapper, script + "# changed\n");
            NativeTransfer.download(wrapper, url, tempDir.resolve("second"), sha1, 3, (done, total) -> { }, () -> false, true);
            assertEquals(2, Files.readAllLines(Path.of(wrapper + ".pids")).size());
        } finally { server.stop(0); }
    }

    /// Concurrent transfers use distinct workers and cannot mix response frames.
    @Test void concurrentTransfersRemainIsolated() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        Path wrapper = tempDir.resolve("backend");
        Files.writeString(wrapper, "#!/bin/sh\nprintf '%s\\n' \"$$\" >> \"$0.pids\"\nexec '" + executable.replace("'", "'\\''") + "' \"$@\"\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        var workers = java.util.concurrent.Executors.newFixedThreadPool(8);
        var serverWorkers = java.util.concurrent.Executors.newFixedThreadPool(8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverWorkers);
        java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maximum = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/file", exchange -> {
            int current = active.incrementAndGet(); maximum.accumulateAndGet(current, Math::max);
            try {
                try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                byte[] bytes = "abc".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            } finally { active.decrementAndGet(); }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/file";
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Long>>();
            for (int i = 0; i < 8; i++) {
                Path destination = tempDir.resolve("concurrent-" + i);
                futures.add(workers.submit(() -> NativeTransfer.download(wrapper, url, destination,
                        "a9993e364706816aba3e25717850c26c9cd0d89d", 3, (done, total) -> { }, () -> false, true)));
            }
            for (var future : futures) assertEquals(3, future.get(10, TimeUnit.SECONDS));
            assertTrue(maximum.get() > 1, "transfers should run concurrently");
            for (int i = 0; i < 8; i++) assertEquals("abc", Files.readString(tempDir.resolve("concurrent-" + i)));
        } finally { server.stop(0); workers.shutdownNow(); serverWorkers.shutdownNow(); }
    }

    /// A failed request discards its child before the next successful transfer.
    @Test void failedRequestDiscardsBackend() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        Path wrapper = tempDir.resolve("backend");
        Files.writeString(wrapper, "#!/bin/sh\nprintf '%s\\n' \"$$\" >> \"$0.pids\"\nexec '" + executable.replace("'", "'\\''") + "' \"$@\"\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bad", exchange -> { exchange.sendResponseHeaders(500, -1); exchange.close(); });
        server.createContext("/good", exchange -> {
            byte[] bytes = "abc".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            String prefix = "http://127.0.0.1:" + server.getAddress().getPort();
            String sha1 = "a9993e364706816aba3e25717850c26c9cd0d89d";
            assertThrows(java.io.IOException.class, () -> NativeTransfer.download(wrapper, prefix + "/bad", tempDir.resolve("bad"),
                    sha1, 3, (done, total) -> { }, () -> false, true));
            NativeTransfer.download(wrapper, prefix + "/good", tempDir.resolve("good"), sha1, 3, (done, total) -> { }, () -> false, true);
            assertEquals(2, Files.readAllLines(Path.of(wrapper + ".pids")).size());
        } finally { server.stop(0); }
    }

    /// Large checked files use bounded ranges, and the feature switch restores serial transfer.
    @Test
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    void parallelRangesCanBeDisabled() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        @Nullable String previous = System.getProperty("mfcl.native.download.parallel.enabled");
        byte[] data = new byte[1024 * 1024 + 127];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 251);
        String sha1 = org.jackhuang.hmcl.util.DigestUtils.digestToString("SHA-1", data);
        var serverWorkers = java.util.concurrent.Executors.newFixedThreadPool(8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(serverWorkers);
        java.util.concurrent.atomic.AtomicInteger ranges = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/file", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            int start = 0, end = data.length - 1;
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            exchange.getResponseHeaders().set("ETag", "\"fixture\"");
            if (range != null) {
                ranges.incrementAndGet(); String[] parts = range.substring("bytes=".length()).split("-");
                start = Integer.parseInt(parts[0]); end = Integer.parseInt(parts[1]);
                exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + data.length);
            }
            int length = end - start + 1;
            try {
                exchange.sendResponseHeaders(range == null ? 200 : 206, length);
                try (var output = exchange.getResponseBody()) { output.write(data, start, length); }
            } catch (java.io.IOException ignored) { exchange.close(); }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/file";
            System.clearProperty("mfcl.native.download.parallel.enabled");
            Path parallel = tempDir.resolve("parallel");
            NativeTransfer.download(Path.of(executable), url, parallel, sha1, data.length, (done, total) -> { }, () -> false, true);
            assertEquals(4, ranges.get()); assertArrayEquals(data, Files.readAllBytes(parallel));
            System.setProperty("mfcl.native.download.parallel.enabled", "false");
            Path serial = tempDir.resolve("serial");
            NativeTransfer.download(Path.of(executable), url, serial, sha1, data.length, (done, total) -> { }, () -> false, true);
            assertEquals(4, ranges.get()); assertArrayEquals(data, Files.readAllBytes(serial));
        } finally {
            server.stop(0); serverWorkers.shutdownNow();
            if (previous == null) System.clearProperty("mfcl.native.download.parallel.enabled");
            else System.setProperty("mfcl.native.download.parallel.enabled", previous);
        }
    }

    /// Cancelling an active parallel range transfer kills its Rust worker and leaves no final file.
    @Test
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    void cancellationStopsParallelRanges() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        @Nullable String previous = System.getProperty("mfcl.native.download.parallel.enabled");
        System.clearProperty("mfcl.native.download.parallel.enabled");
        Path wrapper = tempDir.resolve("backend");
        Files.writeString(wrapper, "#!/bin/sh\nprintf '%s' \"$$\" > \"$0.pid\"\nexec '" + executable.replace("'", "'\\''") + "' \"$@\"\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        byte[] data = new byte[1024 * 1024 + 127];
        String sha1 = org.jackhuang.hmcl.util.DigestUtils.digestToString("SHA-1", data);
        var serverWorkers = java.util.concurrent.Executors.newFixedThreadPool(8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(serverWorkers);
        var rangeStarted = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/file", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range"); int start = 0, end = data.length - 1;
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            if (range != null) {
                String[] parts = range.substring("bytes=".length()).split("-"); start = Integer.parseInt(parts[0]); end = Integer.parseInt(parts[1]);
                exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + data.length);
            }
            int length = end - start + 1;
            try {
                exchange.sendResponseHeaders(range == null ? 200 : 206, length);
                if (range != null) { rangeStarted.countDown(); Thread.sleep(2000); }
                try (var output = exchange.getResponseBody()) { output.write(data, start, length); }
            } catch (java.io.IOException ignored) { exchange.close(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); exchange.close(); }
        });
        server.start();
        AtomicBoolean cancelled = new AtomicBoolean(); AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        Path stage = tempDir.resolve("stage"); Files.createDirectories(stage); Path destination = stage.resolve("output");
        Thread task = new Thread(() -> {
            try {
                NativeTransfer.download(wrapper, "http://127.0.0.1:" + server.getAddress().getPort() + "/file", destination,
                        sha1, data.length, (done, total) -> { }, cancelled::get, true);
                failure.set(new AssertionError("Expected cancellation"));
            } catch (Throwable error) { failure.set(error); }
        });
        task.setDaemon(true); task.start();
        try {
            assertTrue(rangeStarted.await(8, TimeUnit.SECONDS));
            long pid = Long.parseLong(Files.readString(Path.of(wrapper + ".pid")));
            cancelled.set(true); task.join(5000); assertFalse(task.isAlive());
            assertInstanceOf(InterruptedException.class, failure.get()); assertFalse(Files.exists(destination));
            ProcessHandle.of(pid).ifPresent(process -> assertDoesNotThrow(() -> process.onExit().get(3, TimeUnit.SECONDS)));
            org.jackhuang.hmcl.util.io.FileUtils.deleteDirectory(stage); assertFalse(Files.exists(stage));
        } finally {
            cancelled.set(true); task.interrupt(); task.join(5000); server.stop(0); serverWorkers.shutdownNow();
            if (previous == null) System.clearProperty("mfcl.native.download.parallel.enabled");
            else System.setProperty("mfcl.native.download.parallel.enabled", previous);
        }
    }

    /// Completed chunks survive cancellation and are reused by a new Rust process.
    @Test
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    void cancelledDownloadResumesCompletedChunks() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        @Nullable String previous = System.getProperty("mfcl.native.download.parallel.enabled");
        System.clearProperty("mfcl.native.download.parallel.enabled");
        byte[] data = new byte[3 * 512 * 1024 + 137];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 251);
        String sha1 = org.jackhuang.hmcl.util.DigestUtils.digestToString("SHA-1", data);
        var serverWorkers = java.util.concurrent.Executors.newFixedThreadPool(8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(serverWorkers);
        var releaseSlow = new java.util.concurrent.CountDownLatch(1);
        var starts = new java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.atomic.AtomicInteger>();
        server.createContext("/file", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range"); int start = 0, end = data.length - 1;
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes"); exchange.getResponseHeaders().set("ETag", "\"fixture\"");
            if (range != null) {
                String[] parts = range.substring("bytes=".length()).split("-"); start = Integer.parseInt(parts[0]); end = Integer.parseInt(parts[1]);
                starts.computeIfAbsent(start, ignored -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
                exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + data.length);
            }
            int length = end - start + 1;
            try {
                exchange.sendResponseHeaders(range == null ? 200 : 206, length);
                if (range != null && start != 0) releaseSlow.await(10, TimeUnit.SECONDS);
                try (var output = exchange.getResponseBody()) { output.write(data, start, length); }
            } catch (java.io.IOException ignored) { exchange.close(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); exchange.close(); }
        });
        server.start();
        Path resume = tempDir.resolve("resume"); Path first = tempDir.resolve("first"); Path second = tempDir.resolve("second");
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/file";
        AtomicBoolean cancelled = new AtomicBoolean(); AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        Thread task = new Thread(() -> {
            try {
                NativeTransfer.download(Path.of(executable), url, first, sha1, data.length, resume, (done, total) -> { }, cancelled::get, true);
                failure.set(new AssertionError("Expected cancellation"));
            } catch (Throwable error) { failure.set(error); }
        });
        task.setDaemon(true); task.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8); boolean checkpoint = false;
            while (System.nanoTime() < deadline) {
                if (Files.isDirectory(resume)) {
                    try (var files = Files.list(resume)) { checkpoint = files.anyMatch(path -> path.getFileName().toString().endsWith(".part")); }
                    if (checkpoint) break;
                }
                Thread.sleep(20);
            }
            assertTrue(checkpoint, "A complete chunk should be persisted before cancellation");
            cancelled.set(true); task.join(5000); assertFalse(task.isAlive()); assertInstanceOf(InterruptedException.class, failure.get());
            assertFalse(Files.exists(first)); releaseSlow.countDown();
            NativeTransfer.download(Path.of(executable), url, second, sha1, data.length, resume, (done, total) -> { }, () -> false, true);
            assertArrayEquals(data, Files.readAllBytes(second));
            assertEquals(1, starts.get(0).get(), "The completed first chunk must not be downloaded again");
            try (var files = Files.list(resume)) { assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".part"))); }
        } finally {
            cancelled.set(true); releaseSlow.countDown(); task.interrupt(); task.join(5000); server.stop(0); serverWorkers.shutdownNow();
            if (previous == null) System.clearProperty("mfcl.native.download.parallel.enabled");
            else System.setProperty("mfcl.native.download.parallel.enabled", previous);
        }
    }

    /// Real Rust process emits transfer progress and verifies the resulting file.
    @Test void downloadsWithProgress() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file", exchange -> {
            byte[] bytes = "abc".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/file";
            Path dest = tempDir.resolve("数据.jar");
            AtomicLong received = new AtomicLong(-1);
            AtomicLong total = new AtomicLong(-1);
            long size = NativeTransfer.download(Path.of(executable), url, dest,
                    "a9993e364706816aba3e25717850c26c9cd0d89d", 3,
                    (done, expected) -> { received.set(done); total.set(expected); }, () -> false, true);
            assertEquals(3, size); assertEquals(3, received.get()); assertEquals(3, total.get());
            assertEquals("abc", Files.readString(dest));
        } finally { server.stop(0); }
    }

    /// Rust can stage bytes while a caller remains responsible for another digest.
    @Test void stagesWithoutSha1ForIndependentValidation() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file", exchange -> {
            byte[] bytes = "abc".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            Path dest = tempDir.resolve("independent");
            long size = NativeTransfer.download(Path.of(executable), "http://127.0.0.1:" + server.getAddress().getPort() + "/file",
                    dest, null, 3, (done, total) -> { }, () -> false, true);
            assertEquals(3, size); assertEquals("abc", Files.readString(dest));
        } finally { server.stop(0); }
    }

    /// Cancelling a transfer terminates its child process even without thread interruption.
    @Test void cancellationTerminatesChild() throws Exception {
        Path script = tempDir.resolve("backend");
        Files.writeString(script, "#!/bin/sh\nread line\nprintf '%s' \"$$\" > \"$0.pid\"\nprintf '%s\\n' '{\"version\":1,\"id\":1,\"event\":\"progress\",\"downloaded\":1,\"total\":3}'\nwhile :; do :; done\n");
        assertTrue(script.toFile().setExecutable(true));
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicLong progress = new AtomicLong();
        AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        Thread task = new Thread(() -> {
            try {
                NativeTransfer.download(script, "https://example.invalid/", tempDir.resolve("out"),
                        "a9993e364706816aba3e25717850c26c9cd0d89d", 3,
                        (done, total) -> progress.set(done), cancelled::get);
                failure.set(new AssertionError("Cancelled transfer returned successfully"));
            } catch (Throwable e) { failure.set(e); }
        });
        task.setDaemon(true);
        task.start();
        try {
            Path pidFile = Path.of(script + ".pid");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while ((!Files.exists(pidFile) || progress.get() != 1) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(pidFile)); assertEquals(1, progress.get());
            long pid = Long.parseLong(Files.readString(pidFile));
            cancelled.set(true);
            task.join(5000);
            assertFalse(task.isAlive()); assertInstanceOf(InterruptedException.class, failure.get());
            assertFalse(Files.exists(tempDir.resolve("out")));
            ProcessHandle.of(pid).ifPresent(process -> assertDoesNotThrow(() -> process.onExit().get(3, TimeUnit.SECONDS)));
        } finally { cancelled.set(true); task.interrupt(); task.join(5000); }
    }
}
