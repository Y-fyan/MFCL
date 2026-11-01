/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.modpack.server;

import org.jackhuang.hmcl.modpack.ModpackConfiguration;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.charset.StandardCharsets;
import org.jackhuang.hmcl.addon.LocalAddonManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/// Inspects unchanged server-pack files using a reusable Rust hash worker.
@NotNullByDefault
final class NativeServerFileHashes {
    /// Prevents instantiation.
    private NativeServerFileHashes() { }

    /// Hashes ordinary existing files, retaining Java handling for unsupported metadata.
    /// @param root normalized absolute instance root
    /// @param modsDirectory normalized mods directory
    /// @param oldFiles old manifest entries keyed by path
    /// @param remoteFiles ordered new manifest entries
    /// @param cancelled owning task cancellation predicate
    /// @return stable hash observations keyed by new-entry index
    /// @throws InterruptedException when cancelled
    static @Unmodifiable Map<Integer, NativeBackend.FileHashResult> inspect(Path root, Path modsDirectory,
            Map<String, ModpackConfiguration.FileInformation> oldFiles,
            List<ModpackConfiguration.FileInformation> remoteFiles, BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank() || !Boolean.parseBoolean(System.getProperty("mfcl.native.modpack.enabled", "true"))
                || root.getFileSystem() != FileSystems.getDefault()) return Map.of();
        List<NativeBackend.FileHashInput> inputs = new ArrayList<>();
        for (int i = 0; i < remoteFiles.size(); i++) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            try {
                ModpackConfiguration.FileInformation remote = remoteFiles.get(i);
                @Nullable ModpackConfiguration.FileInformation old = oldFiles.get(remote.getPath());
                if (old == null) continue;
                @Nullable String sha = old.getHash();
                // Java compares its lowercase computed digest case-sensitively.
                if (!DigestUtils.isSha1Digest(sha) || !sha.equals(sha.toLowerCase(Locale.ROOT))) continue;
                Path file = root.resolve(remote.getPath()).toAbsolutePath().normalize();
                if (!file.startsWith(root) || !Files.isRegularFile(file) || !StandardCharsets.UTF_8.newEncoder().canEncode(file.toString())) continue;
                @Nullable Path fileName = file.getFileName();
                if (fileName != null && modsDirectory.equals(file.getParent())
                        && (Files.exists(file.resolveSibling(fileName + LocalAddonManager.DISABLED_EXTENSION))
                        || Files.exists(file.resolveSibling(fileName + LocalAddonManager.OLD_EXTENSION)))) continue;
                inputs.add(new NativeBackend.FileHashInput(i, file, sha));
            } catch (RuntimeException ignored) {
                // The original task will handle this entry and report malformed metadata.
            }
        }
        if (inputs.isEmpty()) return Map.of();
        try { return NativeBackend.checkFileHashes(Path.of(executable), inputs, cancelled); }
        catch (IOException | RuntimeException e) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            return Map.of();
        }
    }
}
