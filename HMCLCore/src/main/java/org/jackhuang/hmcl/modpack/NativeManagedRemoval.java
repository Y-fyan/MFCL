/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.modpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

/// Removes validated modpack update entries using no-follow directory handles on Linux.
@NotNullByDefault
public final class NativeManagedRemoval {
    /// Prevents instantiation.
    private NativeManagedRemoval() { }

    /// Validates a candidate path and all existing ancestor directories.
    /// @param root normalized absolute instance root
    /// @param value relative manifest path
    /// @return safe normalized path
    /// @throws IOException if the path escapes or traverses a symbolic link
    public static Path validateRemovalPath(Path root, String value) throws IOException {
        try {
            Path relative = Path.of(value);
            if (relative.isAbsolute() || value.isEmpty()) throw new IOException("Unsafe modpack removal path: " + value);
            for (Path part : relative) if ("..".equals(part.toString())) throw new IOException("Unsafe modpack removal path: " + value);
            Path target = root.resolve(relative).normalize();
            if (target.equals(root) || !target.startsWith(root)) throw new IOException("Unsafe modpack removal path: " + value);
            @Nullable Path parent = target.getParent(); Path current = root;
            if (parent != null && !parent.equals(root)) for (Path part : root.relativize(parent)) {
                current = current.resolve(part);
                if (Files.readAttributes(current, java.nio.file.attribute.BasicFileAttributes.class,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS).isSymbolicLink())
                    throw new IOException("Modpack removal path traverses a symbolic link: " + value);
            }
            return target;
        } catch (java.nio.file.InvalidPathException e) { throw new IOException("Invalid modpack removal path", e); }
    }

    /// Removes a batch already preflighted with [#validateRemovalPath].
    /// @param root normalized absolute instance root
    /// @param paths prevalidated absolute file paths
    /// @param cancelled owning task cancellation predicate
    /// @throws IOException if removal fails
    /// @throws InterruptedException if cancelled
    public static void removeValidated(Path root, List<Path> paths, BooleanSupplier cancelled) throws IOException, InterruptedException {
        if (tryRemove(root, paths, cancelled)) return;
        for (Path path : paths) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            Files.deleteIfExists(validateRemovalPath(root, root.relativize(path).toString()));
        }
    }

    /// Selects the native implementation before mutation and never replays failed removals.
    /// @param root normalized absolute instance root
    /// @param paths prevalidated absolute paths
    /// @param cancelled owning task cancellation predicate
    /// @return true if native removal handled the batch
    /// @throws IOException if native removal fails after selection
    /// @throws InterruptedException if cancelled
    public static boolean tryRemove(Path root, List<Path> paths, BooleanSupplier cancelled) throws IOException, InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        if (paths.isEmpty()) return true;
        @Nullable String binary = System.getProperty("mfcl.native.path");
        if (binary == null || binary.isBlank() || !Boolean.parseBoolean(System.getProperty("mfcl.native.modpack.enabled", "true"))
                || OperatingSystem.CURRENT_OS != OperatingSystem.LINUX || root.getFileSystem() != FileSystems.getDefault()
                || paths.size() > 20_000) return false;
        Path executable; JsonObject params = new JsonObject();
        try {
            executable = Path.of(binary); JsonArray relativePaths = new JsonArray();
            for (Path file : paths) {
                if (cancelled.getAsBoolean()) throw new InterruptedException();
                if (!file.startsWith(root) || file.equals(root)) return false;
                Path relative = root.relativize(file);
                if (!root.resolve(relative).equals(file)) return false;
                relativePaths.add(relative.toString());
            }
            params.addProperty("root", root.toString()); params.add("paths", relativePaths);
            String encoded = params.toString();
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(encoded)
                    || encoded.getBytes(StandardCharsets.UTF_8).length > 900 * 1024
                    || !NativeBackend.supportsCapabilities(executable, "remove_managed_files_v1")) return false;
        } catch (IOException | RuntimeException e) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            return false;
        }
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        // The child may have deleted files even when its final response is lost.
        NativeBackend.removeManagedFiles(executable, params, paths.size(), cancelled);
        return true;
    }
}
