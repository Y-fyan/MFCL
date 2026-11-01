/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/// Plans legacy BootstrapLauncher ignore-list repairs through Rust.
@NotNullByDefault
final class NativeBootstrapIgnore {
    /// Prevents instantiation.
    private NativeBootstrapIgnore() { }

    /// Rewrites ignore lists in order, or returns null for Java handling.
    /// @param lists original ignore-list values
    /// @param classpath ordered library paths
    /// @param libraryDirectory normalized absolute library root
    /// @return immutable rewritten values, or null when unsupported
    /// @throws IOException if interrupted
    static @Nullable @Unmodifiable List<String> tryPlan(List<String> lists, Collection<String> classpath, Path libraryDirectory) throws IOException {
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank()
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.launch.enabled", "true"))) return null;
        try {
            String root = libraryDirectory.toString();
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(root)) return null;
            JsonObject params = new JsonObject(); params.addProperty("library_directory", root);
            params.add("classpath", encode(classpath)); params.add("ignore_lists", encode(lists));
            JsonArray values = NativeBackend.planBootstrapIgnores(Path.of(executable), params).getAsJsonArray("ignore_lists");
            if (values.size() != lists.size()) throw new IOException("Invalid native ignore-list count");
            List<String> result = new ArrayList<>(values.size());
            for (JsonElement value : values) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Invalid native ignore list");
                result.add(value.getAsString());
            }
            return List.copyOf(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IOException("Native ignore-list planning interrupted", e);
        } catch (IOException | RuntimeException e) { return null; }
    }

    /// Encodes ordered UTF-8 strings without replacing invalid Unicode.
    /// @param values input values
    /// @return encoded array
    /// @throws IOException if a value cannot be encoded
    private static JsonArray encode(Collection<String> values) throws IOException {
        JsonArray result = new JsonArray();
        for (String value : values) {
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(value)) throw new IOException("Unsupported bootstrap argument encoding");
            result.add(value);
        }
        return result;
    }
}
