/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.java.mojang;

import org.jackhuang.hmcl.game.DownloadInfo;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/// Inspects existing Mojang runtime files in a bounded native batch.
@NotNullByDefault
final class NativeRuntimeFileHashes {
    /// Prevents instantiation.
    private NativeRuntimeFileHashes() { }

    /// Inspects compatible existing runtime files while preserving Java fallback for other entries.
    /// @param root runtime staging directory
    /// @param entries ordered runtime manifest entries
    /// @param cancelled owning task cancellation predicate
    /// @return immutable observations keyed by entry index
    /// @throws InterruptedException if cancelled
    static @Unmodifiable Map<Integer, NativeBackend.FileHashResult> inspect(Path root,
            List<Map.Entry<String, MojangJavaRemoteFiles.Remote>> entries, BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank() || root.getFileSystem() != FileSystems.getDefault()
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.java.enabled", "true"))) return Map.of();
        List<NativeBackend.FileHashInput> inputs = new ArrayList<>();
        try {
            for (int index = 0; index < entries.size(); index++) {
                if (cancelled.getAsBoolean()) throw new InterruptedException();
                Map.Entry<String, MojangJavaRemoteFiles.Remote> entry = entries.get(index);
                if (!(entry.getValue() instanceof MojangJavaRemoteFiles.RemoteFile file)) continue;
                @Nullable DownloadInfo raw = file.getDownloads().get("raw");
                if (raw == null || !DigestUtils.isSha1Digest(raw.getSha1())) continue;
                Path relative = Path.of(entry.getKey());
                if (relative.isAbsolute() || relative.startsWith("..")
                        || !relative.normalize().equals(relative) || relative.getNameCount() == 0) continue;
                Path cursor = root; boolean linked = Files.isSymbolicLink(cursor);
                for (Path part : relative) {
                    cursor = cursor.resolve(part);
                    if (Files.isSymbolicLink(cursor)) { linked = true; break; }
                }
                if (linked) continue;
                Path path = root.resolve(relative).toAbsolutePath(); String encoded = path.toString();
                if (!StandardCharsets.UTF_8.newEncoder().canEncode(encoded) || !Path.of(encoded).equals(path)
                        || !Files.isRegularFile(path) || Files.size(path) != raw.getSize()) continue;
                inputs.add(new NativeBackend.FileHashInput(index, path, raw.getSha1()));
            }
            if (inputs.isEmpty()) return Map.of();
            return NativeBackend.checkFileHashes(Path.of(executable), inputs, cancelled);
        } catch (IOException | RuntimeException e) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            return Map.of();
        }
    }
}
