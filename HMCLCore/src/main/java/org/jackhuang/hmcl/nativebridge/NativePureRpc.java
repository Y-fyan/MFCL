/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.nativebridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/// Reuses verified native workers for read-only planning and caller-owned staging.
@NotNullByDefault
final class NativePureRpc {
    /// Maximum request frame, including its newline.
    private static final int REQUEST_LIMIT = 1024 * 1024;
    /// Maximum native response frame.
    private static final int RESPONSE_LIMIT = 8 * 1024 * 1024;
    /// Upper bound for a health probe or one expansion request.
    private static final long TIMEOUT_SECONDS = 30;
    /// Upper bound for a file hash batch, matching the one-shot implementation.
    private static final long HASH_TIMEOUT_SECONDS = 180;

    /// Prevents instantiation.
    private NativePureRpc() { }

    /// Attempts to use a worker that explicitly supports persistent processor requests.
    /// @param executable backend executable
    /// @param params literal expansion parameters
    /// @param cancelled owning task cancellation predicate
    /// @return result, or null when an older backend must use the one-shot protocol
    /// @throws IOException when a verified worker fails while expanding
    /// @throws InterruptedException on cancellation
    static @Nullable JsonObject tryProcessorLiterals(Path executable, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return tryRequest(executable, "expand_processor_literals", params, cancelled);
    }

    static @Nullable JsonObject tryProcessorLiteralsV2(Path executable, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return tryRequest(executable, "expand_processor_literals_v2", params, cancelled);
    }

    /// Attempts a file hash batch with progress events disabled.
    /// @param executable backend executable
    /// @param params hash request parameters
    /// @param cancelled owning task cancellation predicate
    /// @return result, or null when an older backend must use the one-shot protocol
    /// @throws IOException when a verified worker fails while hashing
    /// @throws InterruptedException on cancellation
    static @Nullable JsonObject tryFileHashes(Path executable, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return tryRequest(executable, "check_files", params, cancelled);
    }

    /// Attempts a batch of processor option plans through a persistent worker.
    /// @param executable backend executable
    /// @param params option planning parameters
    /// @param cancelled owning task cancellation predicate
    /// @return result, or null when an older backend must use a one-shot request
    /// @throws IOException when planning fails
    /// @throws InterruptedException on cancellation
    static @Nullable JsonObject tryProcessorOptions(Path executable, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return tryRequest(executable, "plan_processor_options", params, cancelled);
    }

    static @Nullable JsonObject tryProcessorOptionsV2(Path executable, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return tryRequest(executable, "plan_processor_options_v2", params, cancelled);
    }

    /// Attempts a read-only legacy library checksum request through a persistent worker.
    /// @param executable backend executable
    /// @param params legacy checksum parameters
    /// @param cancelled cancellation predicate
    /// @return result, or null when streaming is unsupported
    /// @throws IOException on operation failure
    /// @throws InterruptedException on cancellation
    static @Nullable JsonObject tryLegacyChecksum(Path executable, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return tryRequest(executable, "check_legacy_library", params, cancelled);
    }

    /// Attempts a read-only dual-digest request on a reusable worker.
    /// @param executable backend executable
    /// @param params file path
    /// @param cancelled cancellation predicate
    /// @return result, or null when streaming is unsupported
    /// @throws IOException on operation failure
    /// @throws InterruptedException on cancellation
    static @Nullable JsonObject tryModpackHashes(Path executable, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return tryRequest(executable, "hash_modpack_file", params, cancelled);
    }

    /// Attempts a cache copy within a caller-owned private staging directory.
    /// @param executable backend executable
    /// @param params staging request parameters
    /// @param cancelled cancellation predicate
    /// @return result, or null when streaming is unsupported
    /// @throws IOException on operation failure
    /// @throws InterruptedException on cancellation
    static @Nullable JsonObject tryCacheStage(Path executable, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return tryRequest(executable, "stage_cache_copy", params, cancelled);
    }

    /// Attempts a read-only cache SHA-1 computation through a reusable worker.
    /// @param executable backend executable
    /// @param params file path
    /// @param cancelled cancellation predicate
    /// @return result, or null when streaming is unsupported
    /// @throws IOException on operation failure
    /// @throws InterruptedException on cancellation
    static @Nullable JsonObject tryCacheHash(Path executable, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return tryRequest(executable, "hash_cache_sha1", params, cancelled);
    }

    /// Attempts a SHA-256 or MD5 cache check on a reusable worker.
    /// @param executable backend executable
    /// @param params digest request
    /// @param cancelled cancellation predicate
    /// @return result, or null when streaming is unsupported
    /// @throws IOException on operation failure
    /// @throws InterruptedException on cancellation
    static @Nullable JsonObject tryCacheDigest(Path executable, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return tryRequest(executable, "check_cache_digest", params, cancelled);
    }

    /// Attempts LZMA decoding into a caller-owned private staging directory.
    /// @param executable backend executable
    /// @param params runtime decompression request
    /// @param cancelled cancellation predicate
    /// @return result, or null when streaming is unsupported
    /// @throws IOException on operation failure
    /// @throws InterruptedException on cancellation
    static @Nullable JsonObject tryRuntimeLzma(Path executable, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return tryRequest(executable, "stage_runtime_lzma", params, cancelled);
    }

    /// Attempts CurseForge download planning through a reusable worker.
    /// @param executable backend executable
    /// @param params manifest entries, hashes and exclusions
    /// @param cancelled cancellation predicate
    /// @return result, or null when streaming is unsupported
    /// @throws IOException on operation failure
    /// @throws InterruptedException on cancellation
    static @Nullable JsonObject tryCursePlan(Path executable, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return tryRequest(executable, "plan_curse_downloads", params, cancelled);
    }

    /// Attempts CurseForge file selection through a reusable worker.
    /// @param executable backend executable
    /// @param params manifest file metadata and exclusions
    /// @param cancelled cancellation predicate
    /// @return result, or null when streaming is unsupported
    /// @throws IOException on operation failure
    /// @throws InterruptedException on cancellation
    static @Nullable JsonObject tryCurseSelection(Path executable, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return tryRequest(executable, "select_curse_files", params, cancelled);
    }

    /// Attempts Modrinth update comparison through a reusable worker.
    /// @param executable backend executable
    /// @param params old and new manifest entries
    /// @param cancelled cancellation predicate
    /// @return result, or null when streaming is unsupported
    /// @throws IOException on operation failure
    /// @throws InterruptedException on cancellation
    static @Nullable JsonObject tryModrinthUpdate(Path executable, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return tryRequest(executable, "select_modrinth_removed", params, cancelled);
    }

    /// Attempts Modrinth client path planning through a reusable worker.
    /// @param executable backend executable
    /// @param params modpack root, files and exclusion metadata
    /// @param cancelled cancellation predicate
    /// @return result, or null when streaming is unsupported
    /// @throws IOException on operation failure
    /// @throws InterruptedException on cancellation
    static @Nullable JsonObject tryModrinthPlan(Path executable, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return tryRequest(executable, "plan_modrinth_files", params, cancelled);
    }

    /// Attempts Modrinth client file selection through a reusable worker.
    /// @param executable backend executable
    /// @param params modpack file and exclusion metadata
    /// @param cancelled cancellation predicate
    /// @return result, or null when streaming is unsupported
    /// @throws IOException on operation failure
    /// @throws InterruptedException on cancellation
    static @Nullable JsonObject tryModrinthSelection(Path executable, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return tryRequest(executable, "select_modrinth_files", params, cancelled);
    }

    /// Attempts native archive path planning through a reusable worker.
    /// @param executable backend executable
    /// @param params archive descriptors and repository paths
    /// @param cancelled cancellation predicate
    /// @return result, or null when streaming is unsupported
    /// @throws IOException on operation failure
    /// @throws InterruptedException on cancellation
    static @Nullable JsonObject tryNativeArchivePlan(Path executable, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return tryRequest(executable, "plan_native_archives", params, cancelled);
    }

    static @Nullable JsonObject tryNativeArchiveWithRules(Path executable, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return tryRequest(executable, "plan_native_archives_with_rules", params, cancelled);
    }

    /// Attempts native library descriptor selection through a reusable worker.
    /// @param executable backend executable
    /// @param params ordered library descriptors
    /// @param cancelled cancellation predicate
    /// @return result, or null when streaming is unsupported
    /// @throws IOException on operation failure
    /// @throws InterruptedException on cancellation
    static @Nullable JsonObject tryNativeLibraries(Path executable, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return tryRequest(executable, "select_native_libraries", params, cancelled);
    }

    /// Attempts library-only rule selection through a reusable worker.
    /// @param executable backend executable
    /// @param params library rules and opaque index values
    /// @param cancelled cancellation predicate
    /// @return result, or null when streaming is unsupported
    /// @throws IOException on operation failure
    /// @throws InterruptedException on cancellation
    static @Nullable JsonObject tryLibraryRules(Path executable, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return tryRequest(executable, "select_library_rules", params, cancelled);
    }

    /// Attempts a read-only runtime metadata request through a reusable worker.
    /// @param executable backend executable
    /// @param method runtime metadata method
    /// @param params metadata and selection parameters
    /// @param cancelled cancellation predicate
    /// @return result, or null when streaming is unsupported
    /// @throws IOException on operation failure
    /// @throws InterruptedException on cancellation
    static @Nullable JsonObject tryRuntimeMetadata(Path executable, String method, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        if (!"select_java_runtime".equals(method) && !"parse_java_runtime_manifest".equals(method))
            throw new IOException("Unsupported runtime metadata method");
        return tryRequest(executable, method, params, cancelled);
    }

    /// Attempts a read-only processor main-class or classpath request.
    /// @param executable backend executable
    /// @param method supported processor command method
    /// @param params operation parameters
    /// @param cancelled cancellation predicate
    /// @return result, or null when streaming is unsupported
    /// @throws IOException on operation failure
    /// @throws InterruptedException on cancellation
    static @Nullable JsonObject tryProcessorCommand(Path executable, String method, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        if (!"processor_main_class".equals(method) && !"processor_classpath".equals(method) && !"processor_command".equals(method)
                && !"processor_command_v2".equals(method) && !"processor_command_v3".equals(method))
            throw new IOException("Unsupported processor command method");
        return tryRequest(executable, method, params, cancelled);
    }

    static @Nullable JsonObject tryInstanceManifest(Path executable, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return tryRequest(executable, "project_instance_manifest", params, cancelled);
    }

    static @Nullable JsonObject tryBootstrapIgnore(Path executable, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return tryRequest(executable, "plan_bootstrap_ignore", params, cancelled);
    }

    static @Nullable JsonObject tryClasspathPlan(Path executable, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return tryRequest(executable, "plan_classpath", params, cancelled);
    }

    static @Nullable JsonObject tryClasspathWithRules(Path executable, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return tryRequest(executable, "plan_classpath_with_rules", params, cancelled);
    }

    /// Borrows a worker for supported read-only operations or private staging.
    /// @param executable backend executable
    /// @param method supported pure method
    /// @param params operation parameters
    /// @param cancelled cancellation predicate
    /// @return result, or null if the backend lacks the matching capability
    /// @throws IOException on operation failure
    /// @throws InterruptedException on cancellation
    private static @Nullable JsonObject tryRequest(Path executable, String method, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        if (!Boolean.parseBoolean(System.getProperty("mfcl.native.pool.enabled", "true"))) return null;
        if (cancelled.getAsBoolean()) throw new InterruptedException("Native operation cancelled");
        NativeDownloadPool.Worker worker = NativeDownloadPool.acquire(executable);
        boolean reusable = false;
        try {
            if (!worker.pureCapabilitiesChecked) {
                JsonObject health;
                try { health = exchange(worker, "health", new JsonObject(), cancelled, TIMEOUT_SECONDS); }
                catch (IOException e) { return null; }
                if (!worker.process.isAlive() || !isValidHealth(health)) return null;
                worker.processorStreamVerified = hasCapability(health, "processor_literal_stream_v1");
                worker.fileHashStreamVerified = hasCapability(health, "file_hash_stream_v1");
                worker.processorOptionsStreamVerified = hasCapability(health, "processor_options_stream_v1");
                worker.legacyChecksumStreamVerified = hasCapability(health, "legacy_checksum_stream_v1");
                worker.processorCommandStreamVerified = hasCapability(health, "processor_command_stream_v1");
                worker.processorCombinedCommandVerified = hasCapability(health, "processor_combined_command_v1");
                worker.processorArtifactPathsVerified = hasCapability(health, "processor_artifact_paths_v1");
                worker.processorOptionsPathsVerified = hasCapability(health, "processor_options_paths_v1");
                worker.processorCoordinateClasspathVerified = hasCapability(health, "processor_coordinate_classpath_v1");
                worker.modpackHashStreamVerified = hasCapability(health, "modpack_hash_stream_v1");
                worker.cacheStageStreamVerified = hasCapability(health, "cache_stage_stream_v1");
                worker.cacheHashStreamVerified = hasCapability(health, "cache_hash_stream_v1");
                worker.cacheDigestStreamVerified = hasCapability(health, "cache_digest_stream_v1");
                worker.runtimeLzmaStreamVerified = hasCapability(health, "runtime_lzma_stream_v1");
                worker.runtimeMetadataStreamVerified = hasCapability(health, "runtime_metadata_stream_v1");
                worker.instanceManifestStreamVerified = hasCapability(health, "instance_manifest_stream_v1");
                worker.bootstrapIgnoreStreamVerified = hasCapability(health, "bootstrap_ignore_stream_v1");
                worker.classpathPlanStreamVerified = hasCapability(health, "classpath_plan_stream_v1");
                worker.classpathRulesCombinedVerified = hasCapability(health, "classpath_rules_combined_v1");
                worker.libraryRuleStreamVerified = hasCapability(health, "library_rule_stream_v1");
                worker.nativeLibraryStreamVerified = hasCapability(health, "native_library_stream_v1");
                worker.nativeArchivePlanStreamVerified = hasCapability(health, "native_archive_plan_stream_v1");
                worker.nativeArchiveRulesCombinedVerified = hasCapability(health, "native_archive_rules_combined_v1");
                worker.modrinthSelectStreamVerified = hasCapability(health, "modrinth_select_stream_v1");
                worker.modrinthPlanStreamVerified = hasCapability(health, "modrinth_plan_stream_v1");
                worker.modrinthUpdateStreamVerified = hasCapability(health, "modrinth_update_stream_v1");
                worker.curseSelectStreamVerified = hasCapability(health, "curse_select_stream_v1");
                worker.cursePlanStreamVerified = hasCapability(health, "curse_plan_stream_v1");
                worker.pureCapabilitiesChecked = true;
            }
            boolean supported = switch (method) {
                case "expand_processor_literals" -> worker.processorStreamVerified;
                case "check_files" -> worker.fileHashStreamVerified;
                case "plan_processor_options" -> worker.processorOptionsStreamVerified;
                case "check_legacy_library" -> worker.legacyChecksumStreamVerified;
                case "processor_main_class", "processor_classpath" -> worker.processorCommandStreamVerified;
                case "processor_command" -> worker.processorCombinedCommandVerified;
                case "processor_command_v2", "expand_processor_literals_v2" -> worker.processorArtifactPathsVerified;
                case "plan_processor_options_v2" -> worker.processorOptionsPathsVerified;
                case "processor_command_v3" -> worker.processorCoordinateClasspathVerified;
                case "hash_modpack_file" -> worker.modpackHashStreamVerified;
                case "stage_cache_copy" -> worker.cacheStageStreamVerified;
                case "hash_cache_sha1" -> worker.cacheHashStreamVerified;
                case "check_cache_digest" -> worker.cacheDigestStreamVerified;
                case "stage_runtime_lzma" -> worker.runtimeLzmaStreamVerified;
                case "select_java_runtime", "parse_java_runtime_manifest" -> worker.runtimeMetadataStreamVerified;
                case "project_instance_manifest" -> worker.instanceManifestStreamVerified;
                case "plan_bootstrap_ignore" -> worker.bootstrapIgnoreStreamVerified;
                case "plan_classpath" -> worker.classpathPlanStreamVerified;
                case "plan_classpath_with_rules" -> worker.classpathRulesCombinedVerified;
                case "select_library_rules" -> worker.libraryRuleStreamVerified;
                case "select_native_libraries" -> worker.nativeLibraryStreamVerified;
                case "plan_native_archives" -> worker.nativeArchivePlanStreamVerified;
                case "plan_native_archives_with_rules" -> worker.nativeArchiveRulesCombinedVerified;
                case "select_modrinth_files" -> worker.modrinthSelectStreamVerified;
                case "plan_modrinth_files" -> worker.modrinthPlanStreamVerified;
                case "select_modrinth_removed" -> worker.modrinthUpdateStreamVerified;
                case "select_curse_files" -> worker.curseSelectStreamVerified;
                case "plan_curse_downloads" -> worker.cursePlanStreamVerified;
                default -> false;
            };
            if (!supported) return null;
            JsonObject result = exchange(worker, method, params, cancelled,
                    "check_files".equals(method) || "check_legacy_library".equals(method)
                            || "hash_modpack_file".equals(method) || "stage_cache_copy".equals(method)
                            || "hash_cache_sha1".equals(method) || "check_cache_digest".equals(method)
                            || "stage_runtime_lzma".equals(method) || "plan_classpath".equals(method)
                            || "plan_classpath_with_rules".equals(method) ? HASH_TIMEOUT_SECONDS : TIMEOUT_SECONDS);
            reusable = worker.process.isAlive();
            return result;
        } finally {
            if (reusable) NativeDownloadPool.release(worker);
            else terminate(worker.process);
        }
    }

    /// Validates the identity and capability array of a health response.
    /// @param health decoded health result
    /// @return whether the health result describes a compatible backend
    private static boolean isValidHealth(JsonObject health) {
        try {
            return "mfcl-native".equals(health.get("name").getAsString()) && health.get("protocol").getAsInt() == 1
                    && health.get("capabilities").isJsonArray();
        } catch (RuntimeException e) { return false; }
    }

    /// Checks a validated health response for an advertised capability.
    /// @param health decoded health result
    /// @param expected capability name
    /// @return whether the capability is present
    private static boolean hasCapability(JsonObject health, String expected) {
        try {
            for (var capability : health.getAsJsonArray("capabilities"))
                if (expected.equals(capability.getAsString())) return true;
            return false;
        } catch (RuntimeException e) { return false; }
    }

    /// Exchanges one bounded request while maintaining exclusive ownership of the process.
    /// @param worker exclusively borrowed native worker
    /// @param method protocol method
    /// @param params protocol parameters
    /// @param cancelled cancellation predicate
    /// @param timeoutSeconds absolute deadline in seconds
    /// @return response result
    /// @throws IOException on timeout or protocol failure
    /// @throws InterruptedException on cancellation
    private static JsonObject exchange(NativeDownloadPool.Worker worker, String method, JsonObject params,
            BooleanSupplier cancelled, long timeoutSeconds) throws IOException, InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException("Native operation cancelled");
        long id = worker.nextId();
        JsonObject request = new JsonObject(); request.addProperty("version", 1); request.addProperty("id", id);
        request.addProperty("method", method); request.add("params", params);
        byte[] bytes = (request + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > REQUEST_LIMIT) throw new IOException("Native backend request is too large");
        Process process = worker.process;
        FutureTask<JsonObject> reader = new FutureTask<>(() -> readResponse(process.getInputStream(), id));
        FutureTask<Void> writer = new FutureTask<>(() -> { process.getOutputStream().write(bytes); process.getOutputStream().flush(); return null; });
        Thread readThread = new Thread(reader, "MFCL-native-pure-reader");
        Thread writeThread = new Thread(writer, "MFCL-native-pure-writer");
        readThread.setDaemon(true); writeThread.setDaemon(true); readThread.start(); writeThread.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        try {
            while (true) {
                if (cancelled.getAsBoolean()) throw new InterruptedException("Native operation cancelled");
                if (System.nanoTime() >= deadline) throw new IOException("Native backend request timed out");
                try {
                    if (writer.isDone()) writer.get();
                    JsonObject result = reader.get(100, TimeUnit.MILLISECONDS);
                    writer.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    if (cancelled.getAsBoolean()) throw new InterruptedException("Native operation cancelled");
                    if (!process.isAlive() && process.exitValue() != 0) throw new IOException("Native backend process failed");
                    return result;
                } catch (TimeoutException ignored) {
                    // Recheck cancellation and the deadline while either stream is blocked.
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof IOException failure) throw failure;
                    throw new IOException("Native backend communication failed");
                }
            }
        } finally { reader.cancel(true); writer.cancel(true); }
    }

    /// Reads exactly one bounded frame without consuming bytes belonging to another request.
    /// @param input native stdout
    /// @param id expected request ID
    /// @return decoded result
    /// @throws IOException on malformed, failed or oversized responses
    private static JsonObject readResponse(InputStream input, long id) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value < 0) throw new IOException("Native backend ended before its response");
            if (value == '\n') break;
            if (buffer.size() >= RESPONSE_LIMIT) throw new IOException("Native backend response is too large");
            buffer.write(value);
        }
        try {
            JsonObject frame = JsonParser.parseString(buffer.toString(StandardCharsets.UTF_8)).getAsJsonObject();
            if (frame.get("version").getAsInt() != 1 || frame.get("id").getAsLong() != id
                    || frame.has("event") || frame.has("error") || !frame.has("result"))
                throw new IOException("Invalid native backend response");
            if (!frame.get("result").isJsonObject()) throw new IOException("Invalid native backend response");
            return frame.getAsJsonObject("result");
        } catch (RuntimeException e) { throw new IOException("Invalid native backend response"); }
    }

    /// Stops a failed child before returning control to the caller.
    /// @param process owned child process
    private static void terminate(Process process) {
        boolean interrupted = Thread.interrupted();
        try {
            if (process.isAlive()) process.destroyForcibly();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (process.isAlive() && System.nanoTime() < deadline) {
                try { process.waitFor(100, TimeUnit.MILLISECONDS); }
                catch (InterruptedException e) { interrupted = true; }
            }
            try { process.getInputStream().close(); } catch (IOException ignored) { }
            try { process.getOutputStream().close(); } catch (IOException ignored) { }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
