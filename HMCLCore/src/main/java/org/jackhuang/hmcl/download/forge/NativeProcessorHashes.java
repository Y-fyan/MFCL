/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.forge;

import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Inspects processor outputs without changing or publishing any files.
@NotNullByDefault
public final class NativeProcessorHashes {
    /// Prevents instantiation.
    private NativeProcessorHashes() { }

    /// Attempts to hash resolved processor output literals in their original order.
    /// @param values alternating paths and expected digests, or null for Java parsing
    /// @param cancelled owning task cancellation predicate
    /// @return observations indexed by output position
    /// @throws InterruptedException if cancelled
    public static @Unmodifiable Map<Integer, NativeBackend.FileHashResult> inspectLiterals(
            @Nullable List<@Nullable String> values, BooleanSupplier cancelled) throws InterruptedException {
        if (values == null || values.isEmpty() || values.size() % 2 != 0) return Map.of();
        List<NativeBackend.FileHashInput> inputs = new ArrayList<>();
        for (int i = 0; i < values.size() / 2; i++) addCandidate(inputs, i, values.get(i * 2), values.get(i * 2 + 1));
        return inspect(inputs, cancelled);
    }

    /// Attempts to hash processor outputs after the tool has completed.
    /// @param entries resolved paths and expected digests
    /// @param cancelled owning task cancellation predicate
    /// @return observations indexed by entry position
    /// @throws InterruptedException if cancelled
    public static @Unmodifiable Map<Integer, NativeBackend.FileHashResult> inspectEntries(
            List<Map.Entry<String, String>> entries, BooleanSupplier cancelled) throws InterruptedException {
        List<NativeBackend.FileHashInput> inputs = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) addCandidate(inputs, i, entries.get(i).getKey(), entries.get(i).getValue());
        return inspect(inputs, cancelled);
    }

    /// Adds an eligible existing regular file without changing Java's error ordering.
    /// @param inputs mutable native input list
    /// @param index index in the caller's original list
    /// @param path output path, possibly absent
    /// @param digest expected digest, possibly absent
    private static void addCandidate(List<NativeBackend.FileHashInput> inputs, int index,
            @Nullable String path, @Nullable String digest) {
        if (path == null || digest == null || !DigestUtils.isSha1Digest(digest)
                || !digest.equals(digest.toLowerCase(Locale.ROOT))) return;
        try {
            Path file = Path.of(path);
            if (Files.isRegularFile(file)) inputs.add(new NativeBackend.FileHashInput(index, file, digest));
        } catch (RuntimeException ignored) {
            // The original Java loop reports malformed paths in its original order.
        }
    }

    /// Runs a bounded native hash batch and retains Java fallback on communication failures.
    /// @param inputs indexed output files
    /// @param cancelled owning task cancellation predicate
    /// @return stable native observations
    /// @throws InterruptedException if cancelled
    private static @Unmodifiable Map<Integer, NativeBackend.FileHashResult> inspect(
            List<NativeBackend.FileHashInput> inputs, BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        @Nullable String configured = System.getProperty("mfcl.native.path");
        if (inputs.isEmpty() || inputs.size() > 20_000 || configured == null || configured.isBlank()
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.processors.enabled", "true"))) return Map.of();
        try { return NativeBackend.checkFileHashes(Path.of(configured), inputs, cancelled); }
        catch (IOException | RuntimeException e) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            LOG.warning("Native processor output inspection failed; using Java hashing");
            return Map.of();
        }
    }

    /// Accepts a matching hash only if the output still has the inspected file identity.
    /// @param file current processor output
    /// @param digest expected lowercase SHA-1
    /// @param observation native observation, possibly absent
    /// @return whether native verification can replace Java hashing
    public static boolean isVerified(Path file, String digest, @Nullable NativeBackend.FileHashResult observation) {
        return observation != null && DigestUtils.isSha1Digest(digest) && digest.equals(digest.toLowerCase(Locale.ROOT))
                && observation.matches() && digest.equals(observation.expectedSha1())
                && NativeBackend.isFileHashObservationCurrent(file, observation);
    }
}
