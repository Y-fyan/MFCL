/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.nativebridge;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/// Reuses idle native processes while giving each operation exclusive ownership.
@NotNullByDefault
final class NativeDownloadPool {
    /// Maximum number of idle children retained across all executable paths.
    private static final int MAX_IDLE = 32;
    /// Idle children are closed after this interval.
    private static final long IDLE_NANOS = TimeUnit.SECONDS.toNanos(30);
    /// Protects the idle queues and idle count.
    private static final Object LOCK = new Object();
    /// Idle processes indexed by their normalized executable path.
    private static final Map<Path, ArrayDeque<Worker>> IDLE = new HashMap<>();
    /// All live children, including those currently borrowed.
    private static final Set<Process> LIVE = ConcurrentHashMap.newKeySet();
    /// Number of children in the idle queues.
    private static int idleCount;

    static {
        var reaper = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MFCL-native-download-reaper"); thread.setDaemon(true); return thread;
        });
        reaper.scheduleWithFixedDelay(NativeDownloadPool::reap, 10, 10, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> LIVE.forEach(Process::destroyForcibly), "MFCL-native-download-shutdown"));
    }

    /// Prevents instantiation.
    private NativeDownloadPool() { }

    /// Filesystem identity used to avoid reusing a replaced executable.
    /// @param size executable byte size
    /// @param modified executable modification time
    /// @param key filesystem identity when available
    private record Stamp(long size, FileTime modified, @Nullable Object key) { }

    /// An exclusively borrowed or idle process and its next protocol identifier.
    static final class Worker {
        /// Executable path.
        final Path path;
        /// Executable identity at creation time.
        private final Stamp stamp;
        /// Native child process.
        final Process process;
        /// Last protocol ID used on this process.
        private long lastId;
        /// Whether this child has advertised the reusable processor-literal protocol.
        boolean processorStreamVerified;
        /// Whether this child has advertised the reusable file-hash protocol.
        boolean fileHashStreamVerified;
        /// Whether this child has advertised the reusable processor-option protocol.
        boolean processorOptionsStreamVerified;
        /// Whether this child supports reusable legacy library verification.
        boolean legacyChecksumStreamVerified;
        /// Whether this child supports reusable processor-command preparation.
        boolean processorCommandStreamVerified;
        boolean processorCombinedCommandVerified;
        boolean processorArtifactPathsVerified;
        boolean processorOptionsPathsVerified;
        boolean processorCoordinateClasspathVerified;
        /// Whether this worker supports persistent Modrinth file hashing.
        boolean modpackHashStreamVerified;
        /// Whether this worker supports persistent private cache staging.
        boolean cacheStageStreamVerified;
        /// Whether this worker supports persistent cache hashing.
        boolean cacheHashStreamVerified;
        /// Whether this worker supports additional cache digest algorithms.
        boolean cacheDigestStreamVerified;
        /// Whether this worker supports persistent runtime LZMA staging.
        boolean runtimeLzmaStreamVerified;
        /// Whether the backend supports persistent runtime metadata requests.
        boolean runtimeMetadataStreamVerified;
        boolean instanceManifestStreamVerified;
        boolean bootstrapIgnoreStreamVerified;
        boolean classpathPlanStreamVerified;
        boolean classpathRulesCombinedVerified;
        /// Whether library rule selection can use a persistent worker.
        boolean libraryRuleStreamVerified;
        /// Whether native library selection supports persistent workers.
        boolean nativeLibraryStreamVerified;
        /// Whether native archive planning supports persistent workers.
        boolean nativeArchivePlanStreamVerified;
        boolean nativeArchiveRulesCombinedVerified;
        /// Whether Modrinth file selection supports persistent workers.
        boolean modrinthSelectStreamVerified;
        /// Whether Modrinth path planning supports persistent workers.
        boolean modrinthPlanStreamVerified;
        /// Whether Modrinth update comparison supports persistent workers.
        boolean modrinthUpdateStreamVerified;
        /// Whether CurseForge file selection supports persistent workers.
        boolean curseSelectStreamVerified;
        /// Whether CurseForge download planning supports persistent workers.
        boolean cursePlanStreamVerified;
        /// Whether the stream capability health check has completed.
        boolean pureCapabilitiesChecked;
        /// Time when this worker was returned to the idle queue.
        private long idleSince;

        /// Creates a process wrapper.
        /// @param path executable path
        /// @param stamp executable identity
        /// @param process child process
        private Worker(Path path, Stamp stamp, Process process) { this.path = path; this.stamp = stamp; this.process = process; }

        /// Returns a unique positive request ID for this process.
        /// @return next protocol ID
        long nextId() { return ++lastId; }
    }

    /// Captures executable metadata for pool invalidation.
    /// @param path executable path
    /// @return current identity
    /// @throws IOException if metadata cannot be read
    private static Stamp stamp(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        if (!attributes.isRegularFile()) throw new IOException("Native executable is not a regular file");
        return new Stamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
    }

    /// Borrows one idle child or starts a new one.
    /// @param executable configured backend executable
    /// @return exclusively owned worker
    /// @throws IOException if starting or inspecting the backend fails
    static Worker acquire(Path executable) throws IOException {
        Path path = executable.toAbsolutePath().normalize(); Stamp current = stamp(path);
        synchronized (LOCK) {
            @Nullable ArrayDeque<Worker> queue = IDLE.get(path);
            while (queue != null && !queue.isEmpty()) {
                Worker worker = queue.removeFirst(); idleCount--;
                if (worker.process.isAlive() && worker.stamp.equals(current) && worker.lastId < Long.MAX_VALUE) return worker;
                worker.process.destroyForcibly();
            }
            if (queue != null) IDLE.remove(path);
        }
        Process process = new ProcessBuilder(path.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        LIVE.add(process); process.onExit().thenRun(() -> LIVE.remove(process));
        return new Worker(path, current, process);
    }

    /// Returns a healthy worker to the idle queue, or destroys it when the pool is full.
    /// @param worker completed worker
    static void release(Worker worker) {
        synchronized (LOCK) {
            if (worker.process.isAlive() && idleCount < MAX_IDLE) {
                worker.idleSince = System.nanoTime();
                IDLE.computeIfAbsent(worker.path, ignored -> new ArrayDeque<>()).addFirst(worker); idleCount++;
                return;
            }
        }
        worker.process.destroyForcibly();
    }

    /// Reaps expired or exited idle processes without affecting active transfers.
    private static void reap() {
        long now = System.nanoTime();
        synchronized (LOCK) {
            var iterator = IDLE.entrySet().iterator();
            while (iterator.hasNext()) {
                var queue = iterator.next().getValue();
                queue.removeIf(worker -> {
                    if (worker.process.isAlive() && now - worker.idleSince < IDLE_NANOS) return false;
                    idleCount--; worker.process.destroyForcibly(); return true;
                });
                if (queue.isEmpty()) iterator.remove();
            }
        }
    }

    /// Discards idle children for isolated tests.
    static void clearIdle() {
        synchronized (LOCK) {
            IDLE.values().forEach(queue -> queue.forEach(worker -> worker.process.destroyForcibly()));
            IDLE.clear(); idleCount = 0;
        }
    }
}
