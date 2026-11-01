/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.modpack.modrinth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.nio.file.FileSystems;
import java.util.Set;
import java.util.function.BooleanSupplier;

/// Selects Modrinth client files using Rust, with Java fallback for unusual metadata.
@NotNullByDefault
final class NativeModrinthSelection {
    /// Prevents instantiation.
    private NativeModrinthSelection() { }

    /// Selects entries before the existing filesystem and integrity checks.
    /// @param files ordered manifest files
    /// @param excluded excluded optional file keys, or null
    /// @param cancelled owning task cancellation predicate
    /// @return selection flags, or null to use Java
    /// @throws InterruptedException if cancelled
    static boolean @Nullable [] trySelect(List<ModrinthManifest.File> files, @Nullable Set<String> excluded, BooleanSupplier cancelled)
            throws InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank() || !Boolean.parseBoolean(System.getProperty("mfcl.native.modpack.enabled", "true"))) return null;
        try {
            JsonObject params = encode(files, excluded, cancelled);
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(params.toString())) return null;
            return NativeBackend.selectModrinthFiles(Path.of(executable), params, files.size(), cancelled);
        } catch (IOException | RuntimeException e) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            return null;
        }
    }

    /// Plans canonical file paths, falling back to Java for unsupported paths.
    /// @param root normalized absolute game directory
    /// @param files ordered manifest files
    /// @param excluded excluded optional keys, or null
    /// @param cancelled owning task cancellation predicate
    /// @return selected paths, or null to use the Java workflow
    /// @throws InterruptedException if cancelled
    static @Nullable Map<Integer, NativeBackend.ModrinthFilePlan> tryPlan(Path root, List<ModrinthManifest.File> files,
            @Nullable Set<String> excluded, BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank() || !Boolean.parseBoolean(System.getProperty("mfcl.native.modpack.enabled", "true"))
                || root.getFileSystem() != FileSystems.getDefault()) return null;
        try {
            JsonObject params = encode(files, excluded, cancelled); params.addProperty("root", root.toString());
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(params.toString())) return null;
            List<String> paths = new ArrayList<>(files.size());
            for (ModrinthManifest.File file : files) paths.add(file.path());
            return NativeBackend.planModrinthFiles(Path.of(executable), params, root, paths, cancelled);
        } catch (IOException | RuntimeException e) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            return null;
        }
    }

    /// Encodes selection metadata without download URLs or credentials.
    /// @param files ordered manifest files
    /// @param excluded excluded optional keys
    /// @param cancelled cancellation predicate
    /// @return selection parameters
    /// @throws InterruptedException if cancelled
    private static JsonObject encode(List<ModrinthManifest.File> files, @Nullable Set<String> excluded, BooleanSupplier cancelled)
            throws InterruptedException {
        JsonArray entries = new JsonArray();
        for (ModrinthManifest.File file : files) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            JsonObject entry = new JsonObject();
            entry.addProperty("client", file.env() == null ? "required" : file.env().getOrDefault("client", "required"));
            entry.addProperty("has_downloads", !file.downloads().isEmpty()); entry.addProperty("path", file.path());
            entry.addProperty("sha512", file.hashes() == null ? null : file.hashes().get("sha512")); entries.add(entry);
        }
        JsonObject params = new JsonObject(); params.add("files", entries);
        if (excluded != null) { JsonArray keys = new JsonArray(); excluded.forEach(keys::add); params.add("excluded", keys); }
        return params;
    }
}
