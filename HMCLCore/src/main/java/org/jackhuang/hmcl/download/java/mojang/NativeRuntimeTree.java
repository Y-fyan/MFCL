/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.java.mojang;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/// Delegates validated runtime directory and link creation to Rust on Linux.
@NotNullByDefault
final class NativeRuntimeTree {
    /// Prevents instantiation.
    private NativeRuntimeTree() { }

    /// Selects the native implementation before any tree mutations, without replay on failure.
    /// @param root runtime staging root
    /// @param entries ordered and validated runtime entries
    /// @param cancelled owning task cancellation predicate
    /// @return true when Rust created the tree
    /// @throws IOException if native preparation fails after selection
    /// @throws InterruptedException if cancelled
    static boolean tryPrepare(Path root, List<Map.Entry<String, MojangJavaRemoteFiles.Remote>> entries, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        @Nullable String binary = System.getProperty("mfcl.native.path");
        if (binary == null || binary.isBlank() || !Boolean.parseBoolean(System.getProperty("mfcl.native.java.enabled", "true"))
                || OperatingSystem.CURRENT_OS != OperatingSystem.LINUX || root.getFileSystem() != FileSystems.getDefault()) return false;
        Path executable; JsonObject params = new JsonObject();
        try {
            executable = Path.of(binary);
            String absoluteRoot = root.toAbsolutePath().toString();
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(absoluteRoot)) return false;
            JsonArray items = new JsonArray();
            for (Map.Entry<String, MojangJavaRemoteFiles.Remote> entry : entries) {
                if (cancelled.getAsBoolean()) throw new InterruptedException();
                if (!StandardCharsets.UTF_8.newEncoder().canEncode(entry.getKey())) return false;
                JsonObject item = new JsonObject(); item.addProperty("path", entry.getKey());
                if (entry.getValue() instanceof MojangJavaRemoteFiles.RemoteDirectory) item.addProperty("kind", "directory");
                else if (entry.getValue() instanceof MojangJavaRemoteFiles.RemoteFile) item.addProperty("kind", "file");
                else if (entry.getValue() instanceof MojangJavaRemoteFiles.RemoteLink link) {
                    @Nullable String target = link.getTarget();
                    if (target == null || !StandardCharsets.UTF_8.newEncoder().canEncode(target)) return false;
                    item.addProperty("kind", "link"); item.addProperty("target", target);
                } else return false;
                items.add(item);
            }
            params.addProperty("root", absoluteRoot); params.add("entries", items);
            if (params.toString().getBytes(StandardCharsets.UTF_8).length > 900 * 1024
                    || !NativeBackend.supportsCapabilities(executable, "prepare_java_runtime_tree")) return false;
            // Check native lexical support before allowing the operation to mutate files.
            NativeBackend.validateRuntimePaths(executable, params, cancelled);
        } catch (IOException | RuntimeException e) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            return false;
        }
        // A failed or interrupted mutating operation must not be repeated by Java.
        NativeBackend.prepareJavaRuntimeTree(executable, params, cancelled);
        return true;
    }
}
