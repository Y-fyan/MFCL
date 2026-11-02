/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.game;

import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jackhuang.hmcl.task.FetchTask;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/// Downloads eligible batches in Rust, preserving ordinary per-file tasks as fallback.
final class NativeDownloadBatchTask extends Task<Void> {
    record Entry(FileDownloadTask task, Task<?> fallback, String sha1, List<String> urls, Runnable afterPublish) { }

    /// Large or unknown-size files retain the individual transfer's range and resume support.
    static boolean isBatchSizeEligible(long size) { return size > 0 && size < 1024L * 1024; }

    private final Path executable;
    private final List<Entry> entries;
    private final String stageName;
    private final List<Task<?>> dependencies = new ArrayList<>();

    NativeDownloadBatchTask(Path executable, List<Entry> entries, String stageName) {
        this.executable = executable;
        this.entries = List.copyOf(entries);
        this.stageName = stageName;
        setStage(stageName);
        setExecutor(FetchTask.getDownloadExecutor());
    }

    @Override public Collection<Task<?>> getDependencies() { return dependencies; }

    /// Returns scheduled content hashes for diagnostics without executing the batch.
    @Unmodifiable List<String> plannedHashes() {
        return entries.stream().map(Entry::sha1).toList();
    }

    @Override public void execute() throws InterruptedException {
        FetchTask.acquireDownloadPermit();
        try { executeBatch(); } finally { FetchTask.releaseDownloadPermit(); }
    }

    private void executeBatch() throws InterruptedException {
        for (int start = 0; start < entries.size(); start += 64) {
            if (isCancelled()) throw new InterruptedException();
            int end = Math.min(start + 64, entries.size());
            Path stage = null;
            try {
                stage = Files.createTempDirectory("mfcl-native-asset-batch-").toAbsolutePath();
                List<NativeBackend.BatchDownloadInput> inputs = new ArrayList<>();
                for (int i = start; i < end; i++) {
                    Entry entry = entries.get(i);
                    inputs.add(new NativeBackend.BatchDownloadInput(i, entry.sha1(), entry.urls()));
                }
                int offset = start;
                Map<Integer, Boolean> results = NativeBackend.downloadBatch(executable, stage, inputs, this::isCancelled,
                        (completed, total) -> updateProgress(offset + completed, entries.size()));
                for (int i = start; i < end; i++) {
                    if (isCancelled()) throw new InterruptedException();
                    Entry entry = entries.get(i);
                    if (Boolean.TRUE.equals(results.get(i))) {
                        try {
                            entry.task().importNativePayload(stage.resolve(Integer.toString(i)), this::isCancelled);
                            entry.afterPublish().run();
                            dependencies.add(Task.completed(null).withCounter(stageName));
                            continue;
                        }
                        catch (IOException | RuntimeException e) {
                            org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Cannot publish native asset; retrying individually", e);
                        }
                    }
                    dependencies.add(entry.fallback().withCounter(stageName));
                }
            } catch (IOException | RuntimeException e) {
                if (isCancelled()) throw new InterruptedException();
                org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Native asset batch failed; retrying individually", e);
                for (int i = start; i < end; i++) dependencies.add(entries.get(i).fallback().withCounter(stageName));
            } finally {
                if (stage != null) FileUtils.deleteDirectoryQuietly(stage);
            }
        }
    }
}
