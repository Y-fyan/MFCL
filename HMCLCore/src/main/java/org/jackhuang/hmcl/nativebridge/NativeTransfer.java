/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.nativebridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jackhuang.hmcl.util.DigestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/// Runs native downloads with progress events, cooperative cancellation and process reuse.
@NotNullByDefault
public final class NativeTransfer {
    /// Maximum size of a single protocol frame.
    private static final int FRAME_LIMIT = 65536;
    /// Upper bound for one backend execution.
    private static final long TIMEOUT_SECONDS = 400;
    /// Bounds how many files can use four simultaneous ranges at once.
    private static final java.util.concurrent.Semaphore PARALLEL_FILES = new java.util.concurrent.Semaphore(8);

    /// Prevents instantiation.
    private NativeTransfer() { }

    /// Downloads an HTTPS file to a previously nonexistent staging destination.
    ///
    /// @param executable native backend executable
    /// @param url source HTTPS URL
    /// @param destination nonexistent destination path
    /// @param sha1 expected SHA-1, or null when the caller independently verifies another digest
    /// @param maxBytes maximum accepted byte count
    /// @param progress receives downloaded bytes and total bytes, or -1 when unknown
    /// @param cancelled reports whether the owning task was cancelled
    /// @return completed byte count
    /// @throws IOException if download or protocol validation fails
    /// @throws InterruptedException when cancelled or interrupted
    public static long download(Path executable, String url, Path destination, @Nullable String sha1, long maxBytes,
                                BiConsumer<Long, Long> progress, BooleanSupplier cancelled) throws IOException, InterruptedException {
        return download(executable, url, destination, sha1, maxBytes, null, progress, cancelled, false);
    }

    /// Downloads with an optional persistent checkpoint directory for SHA-1-verified ranges.
    /// @param executable native backend executable
    /// @param url source HTTPS URL
    /// @param destination nonexistent staging destination
    /// @param sha1 expected SHA-1, or null when independently checked
    /// @param maxBytes maximum accepted byte count
    /// @param resumeDirectory optional private checkpoint directory
    /// @param progress progress callback
    /// @param cancelled cancellation predicate
    /// @return completed byte count
    /// @throws IOException on transfer failure
    /// @throws InterruptedException if cancelled
    public static long download(Path executable, String url, Path destination, @Nullable String sha1, long maxBytes,
                                @Nullable Path resumeDirectory, BiConsumer<Long, Long> progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return download(executable, url, destination, sha1, maxBytes, resumeDirectory, progress, cancelled, false);
    }

    /// Implements the transfer, permitting numeric loopback HTTP only for isolated tests.
    ///
    /// @param executable native executable
    /// @param url source URL
    /// @param destination nonexistent output path
    /// @param sha1 expected SHA-1, or null for independent caller verification
    /// @param maxBytes maximum size
    /// @param progress progress listener
    /// @param cancelled cancellation predicate
    /// @param allowLoopbackHttp whether loopback HTTP is enabled for tests
    /// @return completed byte count
    /// @throws IOException on backend or protocol error
    /// @throws InterruptedException on cancellation or interruption
    static long download(Path executable, String url, Path destination, @Nullable String sha1, long maxBytes,
                         BiConsumer<Long, Long> progress, BooleanSupplier cancelled, boolean allowLoopbackHttp)
            throws IOException, InterruptedException {
        return download(executable, url, destination, sha1, maxBytes, null, progress, cancelled, allowLoopbackHttp);
    }

    /// Downloads a file with Rust verifying SHA-512 before creating the staging file.
    /// @param executable native backend executable
    /// @param url source HTTPS URL
    /// @param destination nonexistent staging path
    /// @param sha512 expected 128-digit digest
    /// @param maxBytes maximum accepted byte count
    /// @param progress progress callback
    /// @param cancelled cancellation predicate
    /// @return completed byte count
    /// @throws IOException on transfer or validation failure
    /// @throws InterruptedException on cancellation
    public static long downloadSha512(Path executable, String url, Path destination, String sha512, long maxBytes,
            BiConsumer<Long, Long> progress, BooleanSupplier cancelled) throws IOException, InterruptedException {
        return downloadSha512(executable, url, destination, sha512, maxBytes, null, progress, cancelled, false);
    }

    /// Downloads with optional SHA-512-addressed range checkpoints.
    /// @param executable native backend executable
    /// @param url source HTTPS URL
    /// @param destination nonexistent staging path
    /// @param sha512 expected digest
    /// @param maxBytes maximum accepted byte count
    /// @param resumeDirectory optional checkpoint directory
    /// @param progress progress callback
    /// @param cancelled cancellation predicate
    /// @return completed byte count
    /// @throws IOException on transfer or validation failure
    /// @throws InterruptedException on cancellation
    public static long downloadSha512(Path executable, String url, Path destination, String sha512, long maxBytes,
            @Nullable Path resumeDirectory, BiConsumer<Long, Long> progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return downloadSha512(executable, url, destination, sha512, maxBytes, resumeDirectory, progress, cancelled, false);
    }

    /// Implements SHA-512 transfer with optional loopback HTTP for isolated tests.
    /// @param executable native backend executable
    /// @param url source URL
    /// @param destination nonexistent staging path
    /// @param sha512 expected digest
    /// @param maxBytes maximum accepted byte count
    /// @param progress progress callback
    /// @param cancelled cancellation predicate
    /// @param allowLoopbackHttp permits numeric loopback HTTP in tests
    /// @return completed byte count
    /// @throws IOException on transfer or validation failure
    /// @throws InterruptedException on cancellation
    static long downloadSha512(Path executable, String url, Path destination, String sha512, long maxBytes,
            BiConsumer<Long, Long> progress, BooleanSupplier cancelled, boolean allowLoopbackHttp)
            throws IOException, InterruptedException {
        return downloadSha512(executable, url, destination, sha512, maxBytes, null, progress, cancelled, allowLoopbackHttp);
    }

    /// Implements SHA-512 range transfers and optional test-only loopback support.
    /// @param executable native backend executable
    /// @param url source URL
    /// @param destination nonexistent staging path
    /// @param sha512 expected digest
    /// @param maxBytes maximum accepted byte count
    /// @param resumeDirectory optional checkpoint directory
    /// @param progress progress callback
    /// @param cancelled cancellation predicate
    /// @param allowLoopbackHttp permits numeric loopback HTTP in tests
    /// @return completed byte count
    /// @throws IOException on transfer or validation failure
    /// @throws InterruptedException on cancellation
    static long downloadSha512(Path executable, String url, Path destination, String sha512, long maxBytes,
            @Nullable Path resumeDirectory, BiConsumer<Long, Long> progress, BooleanSupplier cancelled, boolean allowLoopbackHttp)
            throws IOException, InterruptedException {
        return downloadVerifiedDigest(executable, url, destination, "sha512", sha512, 128, maxBytes,
                resumeDirectory, progress, cancelled, allowLoopbackHttp);
    }

    /// Downloads a SHA-256- or MD5-verified file with optional range checkpoints.
    /// @param executable native backend executable
    /// @param url source HTTPS URL
    /// @param destination nonexistent staging path
    /// @param algorithm canonical algorithm name
    /// @param digest expected hexadecimal digest
    /// @param maxBytes maximum accepted byte count
    /// @param resumeDirectory optional checkpoint directory
    /// @param progress progress callback
    /// @param cancelled cancellation predicate
    /// @return completed byte count
    /// @throws IOException on transfer or validation failure
    /// @throws InterruptedException on cancellation
    public static long downloadOtherDigest(Path executable, String url, Path destination, String algorithm, String digest,
            long maxBytes, @Nullable Path resumeDirectory, BiConsumer<Long, Long> progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return switch (algorithm) {
            case "SHA-256" -> downloadVerifiedDigest(executable, url, destination, "sha256", digest, 64, maxBytes, resumeDirectory, progress, cancelled, false);
            case "MD5" -> downloadVerifiedDigest(executable, url, destination, "md5", digest, 32, maxBytes, resumeDirectory, progress, cancelled, false);
            default -> throw new IOException("Unsupported native download digest");
        };
    }

    /// Performs a verified non-SHA-1 transfer, optionally allowing test-only loopback HTTP.
    /// @param executable native executable
    /// @param url source URL
    /// @param destination nonexistent staging path
    /// @param field native digest field
    /// @param digest expected digest
    /// @param length required hexadecimal length
    /// @param maxBytes maximum accepted byte count
    /// @param resumeDirectory optional checkpoint directory
    /// @param progress progress callback
    /// @param cancelled cancellation predicate
    /// @param allowLoopbackHttp whether test-only loopback HTTP is allowed
    /// @return completed byte count
    /// @throws IOException on transfer or validation failure
    /// @throws InterruptedException on cancellation
    private static long downloadVerifiedDigest(Path executable, String url, Path destination, String field, String digest, int length,
            long maxBytes, @Nullable Path resumeDirectory, BiConsumer<Long, Long> progress, BooleanSupplier cancelled,
            boolean allowLoopbackHttp) throws IOException, InterruptedException {
        if (!hexDigest(digest, length)) throw new IOException("Invalid expected download digest");
        JsonObject params = new JsonObject();
        params.addProperty("url", url); params.addProperty("destination", destination.toString());
        params.addProperty(field, digest); params.addProperty("max_bytes", maxBytes);
        params.addProperty("allow_http_loopback", allowLoopbackHttp); params.addProperty("progress", true);
        params.addProperty("follow_redirects", false);
        if (resumeDirectory != null) {
            Path absolute = resumeDirectory.toAbsolutePath().normalize();
            if (!Path.of(absolute.toString()).equals(absolute)) throw new IOException("Unsupported checkpoint directory encoding");
            params.addProperty("resume_directory", absolute.toString());
        }
        boolean parallel = Boolean.parseBoolean(System.getProperty("mfcl.native.download.parallel.enabled", "true"))
                && PARALLEL_FILES.tryAcquire();
        params.addProperty("parallel_chunks", parallel ? 4 : 0);
        try {
            return execute(executable, params, maxBytes, progress, cancelled, result -> {
                long bytes = result.get("bytes_written").getAsLong();
                String actual = result.get(field).getAsString();
                if (bytes < 0 || bytes > maxBytes || !hexDigest(actual, length) || !digest.equalsIgnoreCase(actual))
                    throw new IOException("Invalid native verified download result");
                return bytes;
            });
        } finally {
            if (parallel) PARALLEL_FILES.release();
        }
    }

    /// Checks the exact length and ASCII hexadecimal alphabet of a digest.
    /// @param digest digest string
    /// @param length required character count
    /// @return whether the digest is valid
    private static boolean hexDigest(String digest, int length) {
        if (digest.length() != length) return false;
        for (int i = 0; i < digest.length(); i++) {
            char c = digest.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
        }
        return true;
    }

    /// Executes a transfer with an optional checkpoint directory and test-only loopback support.
    /// @param executable native executable
    /// @param url source URL
    /// @param destination nonexistent output path
    /// @param sha1 expected digest or null
    /// @param maxBytes maximum size
    /// @param resumeDirectory optional checkpoint directory
    /// @param progress progress callback
    /// @param cancelled cancellation predicate
    /// @param allowLoopbackHttp whether numeric loopback HTTP is allowed
    /// @return completed byte count
    /// @throws IOException on backend or protocol failure
    /// @throws InterruptedException on cancellation
    static long download(Path executable, String url, Path destination, @Nullable String sha1, long maxBytes,
                         @Nullable Path resumeDirectory, BiConsumer<Long, Long> progress, BooleanSupplier cancelled,
                         boolean allowLoopbackHttp) throws IOException, InterruptedException {
        JsonObject params = new JsonObject();
        params.addProperty("url", url);
        params.addProperty("destination", destination.toString());
        params.addProperty("sha1", sha1);
        if (sha1 == null) params.addProperty("allow_unverified", true);
        params.addProperty("max_bytes", maxBytes);
        params.addProperty("allow_http_loopback", allowLoopbackHttp);
        params.addProperty("progress", true);
        params.addProperty("follow_redirects", false);
        if (resumeDirectory != null && sha1 != null) {
            Path absolute = resumeDirectory.toAbsolutePath().normalize();
            if (!Path.of(absolute.toString()).equals(absolute)) throw new IOException("Unsupported checkpoint directory encoding");
            params.addProperty("resume_directory", absolute.toString());
        }
        boolean parallel = sha1 != null && Boolean.parseBoolean(System.getProperty("mfcl.native.download.parallel.enabled", "true"))
                && PARALLEL_FILES.tryAcquire();
        params.addProperty("parallel_chunks", parallel ? 4 : 0);
        try {
            return execute(executable, params, maxBytes, progress, cancelled, result -> {
                long bytes = result.get("bytes_written").getAsLong();
                String actualSha1 = result.get("sha1").getAsString();
                if (bytes < 0 || bytes > maxBytes || !DigestUtils.isSha1Digest(actualSha1)
                        || (sha1 != null && !sha1.equalsIgnoreCase(actualSha1))) throw new IOException("Invalid native download result");
                return bytes;
            });
        } finally {
            if (parallel) PARALLEL_FILES.release();
        }
    }

    /// Native metadata response with the cache-relevant HTTP headers.
    /// @param status HTTP status, either 200 or 304
    /// @param headers response headers
    /// @param bytes downloaded body length
    public record MetadataResult(int status, HttpHeaders headers, long bytes) { }

    /// Fetches an unverified metadata body for validation and caching by the owning Java task.
    /// @param executable native backend executable
    /// @param url source HTTPS URL
    /// @param destination nonexistent staging path
    /// @param etag optional conditional ETag
    /// @param progress progress callback
    /// @param cancelled cancellation predicate
    /// @return status and cache-relevant response headers
    /// @throws IOException if transfer or protocol validation fails
    /// @throws InterruptedException if cancelled
    public static MetadataResult fetchMetadata(Path executable, String url, Path destination, @Nullable String etag,
            BiConsumer<Long, Long> progress, BooleanSupplier cancelled) throws IOException, InterruptedException {
        return fetchMetadata(executable, url, destination, etag, progress, cancelled, false);
    }

    /// Fetches metadata while optionally allowing numeric loopback HTTP in isolated tests.
    /// @param executable native backend executable
    /// @param url source URL
    /// @param destination nonexistent staging path
    /// @param etag optional conditional ETag
    /// @param progress progress callback
    /// @param cancelled cancellation predicate
    /// @param allowLoopbackHttp whether to allow numeric loopback HTTP
    /// @return validated response metadata
    /// @throws IOException on transfer or protocol failure
    /// @throws InterruptedException if cancelled
    static MetadataResult fetchMetadata(Path executable, String url, Path destination, @Nullable String etag,
            BiConsumer<Long, Long> progress, BooleanSupplier cancelled, boolean allowLoopbackHttp) throws IOException, InterruptedException {
        JsonObject params = new JsonObject();
        params.addProperty("url", url); params.addProperty("destination", destination.toString());
        params.addProperty("allow_unverified", true); params.addProperty("metadata", true);
        params.addProperty("if_none_match", etag); params.addProperty("max_bytes", 1024L * 1024 * 1024);
        params.addProperty("allow_http_loopback", allowLoopbackHttp); params.addProperty("progress", true);
        params.addProperty("follow_redirects", false);
        return execute(executable, params, 1024L * 1024 * 1024, progress, cancelled, result -> {
            int status = result.get("status").getAsInt(); long bytes = result.get("bytes_written").getAsLong();
            if ((status != 200 && status != 304) || bytes < 0 || bytes > 1024L * 1024 * 1024 || (status == 304 && bytes != 0))
                throw new IOException("Invalid native metadata status");
            if (status == 200 && !DigestUtils.isSha1Digest(result.get("sha1").getAsString()))
                throw new IOException("Invalid native metadata digest");
            JsonObject raw = result.getAsJsonObject("headers"); Map<String, List<String>> headers = new LinkedHashMap<>();
            for (var entry : raw.entrySet()) {
                if (!java.util.Set.of("content-type", "content-length", "content-encoding", "cache-control", "etag", "expires", "last-modified", "x-bmclapi-hash").contains(entry.getKey()))
                    throw new IOException("Invalid native metadata header");
                List<String> values = new ArrayList<>();
                for (JsonElement value : entry.getValue().getAsJsonArray()) {
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Invalid native metadata header value");
                    values.add(value.getAsString());
                }
                headers.put(entry.getKey(), List.copyOf(values));
            }
            return new MetadataResult(status, HttpHeaders.of(headers, (name, value) -> true), bytes);
        });
    }

    /// Decodes a completed response while the worker is still exclusively owned.
    /// @param <T> decoded result type
    private interface ResultDecoder<T> {
        /// @param result response object
        /// @return validated result
        /// @throws IOException if the response is invalid
        T decode(JsonObject result) throws IOException;
    }

    /// Executes one request using an exclusively borrowed persistent worker.
    /// @param <T> decoded result type
    /// @param executable native executable
    /// @param params operation parameters
    /// @param maxBytes maximum progress byte count
    /// @param progress progress callback
    /// @param cancelled cancellation predicate
    /// @param decoder result validator
    /// @return decoded result
    /// @throws IOException on communication failure
    /// @throws InterruptedException if cancelled
    private static <T> T execute(Path executable, JsonObject params, long maxBytes, BiConsumer<Long, Long> progress,
            BooleanSupplier cancelled, ResultDecoder<T> decoder) throws IOException, InterruptedException {
        JsonObject request = new JsonObject();
        request.addProperty("version", 1);
        request.addProperty("id", 1);
        request.addProperty("method", "download");
        request.add("params", params);
        if (cancelled.getAsBoolean()) throw new InterruptedException("Download cancelled");
        if ((request + "\n").getBytes(StandardCharsets.UTF_8).length > FRAME_LIMIT - 32)
            throw new IOException("Native download request too large");
        NativeDownloadPool.Worker worker = NativeDownloadPool.acquire(executable);
        Process process = worker.process;
        long requestId = worker.nextId();
        request.addProperty("id", requestId);
        byte[] requestBytes = (request + "\n").getBytes(StandardCharsets.UTF_8);
        FutureTask<T> reader = new FutureTask<>(() -> readFrames(process.getInputStream(), requestId, maxBytes, progress, decoder));
        FutureTask<Void> writer = new FutureTask<>(() -> {
            var output = process.getOutputStream(); output.write(requestBytes); output.flush();
            return null;
        });
        Thread readerThread = new Thread(reader, "MFCL-native-download-reader");
        Thread writerThread = new Thread(writer, "MFCL-native-download-writer");
        readerThread.setDaemon(true);
        writerThread.setDaemon(true);
        readerThread.start();
        writerThread.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        boolean reusable = false;
        try {
            while (true) {
                if (cancelled.getAsBoolean()) throw new InterruptedException("Download cancelled");
                if (System.nanoTime() >= deadline) throw new IOException("Native download timed out");
                try {
                    if (writer.isDone()) writer.get();
                    T result = reader.get(100, TimeUnit.MILLISECONDS);
                    writer.get(Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    if (cancelled.getAsBoolean()) throw new InterruptedException("Download cancelled");
                    if (!process.isAlive() && process.exitValue() != 0) throw new IOException("Native download process failed");
                    reusable = process.isAlive();
                    return result;
                } catch (TimeoutException ignored) {
                    // Periodically recheck task cancellation and the absolute deadline.
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof IOException io) throw io;
                    throw new IOException("Native download protocol failed", e);
                }
            }
        } finally {
            reader.cancel(true);
            writer.cancel(true);
            if (reusable) NativeDownloadPool.release(worker); else terminate(process);
        }
    }

    /// Reads bounded progress frames until a validated final result arrives.
    ///
    /// @param input native stdout
    /// @param requestId expected protocol request ID
    /// @param maxBytes maximum progress byte count
    /// @param progress progress listener
    /// @param decoder result validator
    /// @param <T> decoded result type
    /// @return validated backend result
    /// @throws IOException on malformed or failed protocol response
    private static <T> T readFrames(InputStream input, long requestId, long maxBytes, BiConsumer<Long, Long> progress,
                                    ResultDecoder<T> decoder) throws IOException {
        while (true) {
            JsonObject frame;
            try {
                frame = JsonParser.parseString(readLine(input)).getAsJsonObject();
                if (frame.get("version").getAsInt() != 1 || frame.get("id").getAsLong() != requestId) {
                    throw new IOException("Invalid native download envelope");
                }
                if (frame.has("event")) {
                    if (!"progress".equals(frame.get("event").getAsString()) || frame.has("result") || frame.has("error")) {
                        throw new IOException("Invalid native download event");
                    }
                    long downloaded = frame.get("downloaded").getAsLong();
                    JsonElement totalValue = frame.get("total");
                    long total = totalValue.isJsonNull() ? -1 : totalValue.getAsLong();
                    if (downloaded < 0 || downloaded > maxBytes || total < -1 || (total >= 0 && downloaded > total)) {
                        throw new IOException("Invalid native download progress");
                    }
                    progress.accept(downloaded, total);
                    continue;
                }
                if (frame.has("error")) throw new IOException("Native download failed");
                return decoder.decode(frame.getAsJsonObject("result"));
            } catch (RuntimeException e) {
                throw new IOException("Invalid native download response", e);
            }
        }
    }

    /// Reads one UTF-8 protocol line without accepting unbounded input.
    ///
    /// @param input child stdout
    /// @return one protocol frame
    /// @throws IOException on end of stream or oversize frame
    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value < 0) throw new IOException("Native download ended before its final response");
            if (value == '\n') return bytes.toString(StandardCharsets.UTF_8);
            if (bytes.size() >= FRAME_LIMIT) throw new IOException("Native download frame too large");
            bytes.write(value);
        }
    }

    /// Terminates the child and waits for it before its caller removes staging files.
    ///
    /// @param process native child process
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
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
