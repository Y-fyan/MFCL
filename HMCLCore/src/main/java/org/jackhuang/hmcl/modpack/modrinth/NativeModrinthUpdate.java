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
import java.util.Objects;
import java.util.function.BooleanSupplier;

/// Compares Modrinth update entries in Rust while Java retains file deletion.
@NotNullByDefault
final class NativeModrinthUpdate {
    /// Prevents instantiation.
    private NativeModrinthUpdate() { }

    /// Finds removed entries or falls back before mutating any files.
    /// @param oldFiles previous manifest entries
    /// @param newFiles replacement manifest entries
    /// @param cancelled owning task cancellation predicate
    /// @return removed flags, or null for Java comparison
    /// @throws InterruptedException when cancelled
    static boolean @Nullable [] tryFindRemoved(List<ModrinthManifest.File> oldFiles,
            List<ModrinthManifest.File> newFiles, BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank() || !Boolean.parseBoolean(System.getProperty("mfcl.native.modpack.enabled", "true"))) return null;
        try {
            JsonObject params = new JsonObject(); params.add("old", encode(oldFiles, cancelled)); params.add("new", encode(newFiles, cancelled));
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(params.toString())) return null;
            return NativeBackend.selectRemovedModrinthFiles(Path.of(executable), params, oldFiles.size(), cancelled);
        } catch (IOException | RuntimeException e) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            return null;
        }
    }

    /// Encodes exactly the fields used by Modrinth file equality.
    /// @param files ordered file entries
    /// @param cancelled cancellation predicate
    /// @return entries suitable for structural comparison
    /// @throws InterruptedException when cancelled
    private static JsonArray encode(List<ModrinthManifest.File> files, BooleanSupplier cancelled) throws InterruptedException {
        JsonArray result = new JsonArray();
        for (ModrinthManifest.File file : files) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            JsonObject item = new JsonObject(); item.addProperty("path", Objects.requireNonNull(file.path()));
            item.add("hashes", stringMap(Objects.requireNonNull(file.hashes())));
            if (file.env() == null) item.add("env", com.google.gson.JsonNull.INSTANCE); else item.add("env", stringMap(file.env()));
            JsonArray downloads = new JsonArray(); for (@Nullable String value : Objects.requireNonNull(file.downloads())) downloads.add(value);
            item.add("downloads", downloads); item.addProperty("size", file.fileSize()); result.add(item);
        }
        return result;
    }

    /// Encodes a string map preserving null values and ignoring iteration order.
    /// @param values source map
    /// @return encoded map
    private static JsonObject stringMap(Map<String, String> values) {
        JsonObject result = new JsonObject();
        values.forEach((key, value) -> result.addProperty(Objects.requireNonNull(key), value));
        return result;
    }
}
