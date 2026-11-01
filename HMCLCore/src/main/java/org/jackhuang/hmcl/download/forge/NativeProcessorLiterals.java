/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.forge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.game.Artifact;
import org.jackhuang.hmcl.game.GameRepositoryLayout;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Resolves Forge-family processor literal batches through the native backend.
@NotNullByDefault
public final class NativeProcessorLiterals {
    /// Prevents instantiation.
    private NativeProcessorLiterals() { }

    /// Attempts a native batch without modifying any processor files.
    /// @param literals input literals
    /// @param variables processor variable map
    /// @param layout repository layout used to resolve Maven artifacts
    /// @param cancelled cancellation predicate
    /// @return expanded values, or null when Java must handle the batch
    /// @throws InterruptedException if cancelled
    public static @Nullable @Unmodifiable List<@Nullable String> tryExpand(List<String> literals,
            Map<String, @Nullable String> variables, GameRepositoryLayout layout, BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        @Nullable String configured = System.getProperty("mfcl.native.path");
        if (configured == null || configured.isBlank()
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.processors.enabled", "true"))) return null;
        try {
            @Nullable JsonObject nativePaths = encodeWithRoot(literals, variables, layout);
            if (nativePaths != null) {
                try {
                    return decode(NativeBackend.expandProcessorLiteralsV2(Path.of(configured), nativePaths, cancelled), literals.size());
                } catch (IOException | RuntimeException e) {
                    if (cancelled.getAsBoolean()) throw new InterruptedException();
                }
            }
            JsonObject params = encode(literals, variables, layout);
            JsonObject response = NativeBackend.expandProcessorLiterals(Path.of(configured), params, cancelled);
            return decode(response, literals.size());
        } catch (IOException | RuntimeException e) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            LOG.warning("Native processor literal expansion failed; using Java parser");
            return null;
        }
    }

    /// Encodes ordinary-layout inputs without resolving their Maven coordinates in Java.
    static @Nullable JsonObject encodeWithRoot(List<String> literals, Map<String, @Nullable String> variables,
            GameRepositoryLayout layout) throws IOException {
        if (java.io.File.separatorChar != '/' || literals.stream().noneMatch(v -> v.startsWith("[") && v.endsWith("]"))) return null;
        try {
            if (layout.getClass().getMethod("getArtifactFile", Artifact.class).getDeclaringClass() != GameRepositoryLayout.class) return null;
        } catch (ReflectiveOperationException e) { return null; }
        Path root = layout.getLibrariesDirectory();
        if (root.getFileSystem() != FileSystems.getDefault()) return null;
        String path = root.toString(); checkUnicode(path); if (path.isEmpty()) return null;
        JsonObject result = new JsonObject(); JsonArray values = new JsonArray(); JsonObject vars = new JsonObject();
        for (String literal : literals) { checkUnicode(literal); values.add(literal); }
        for (var entry : variables.entrySet()) {
            checkUnicode(entry.getKey()); @Nullable String value = entry.getValue(); if (value != null) checkUnicode(value);
            vars.addProperty(entry.getKey(), value);
        }
        result.add("literals", values); result.add("variables", vars); result.addProperty("artifact_root", path);
        return result;
    }

    /// Encodes literal inputs for standalone or combined native preparation.
    static JsonObject encode(List<String> literals, Map<String, @Nullable String> variables, GameRepositoryLayout layout) throws IOException {
        JsonObject params = new JsonObject(); JsonArray inputs = new JsonArray(); JsonObject artifactPaths = new JsonObject();
        for (String literal : literals) {
            checkUnicode(literal); inputs.add(literal);
            if (literal.startsWith("[") && literal.endsWith("]")) {
                String descriptor = literal.length() >= 2 ? literal.substring(1, literal.length() - 1) : literal;
                String path = layout.getArtifactFile(Artifact.fromDescriptor(descriptor)).toString();
                checkUnicode(path); artifactPaths.addProperty(literal, path);
            }
        }
        JsonObject vars = new JsonObject();
        for (var entry : variables.entrySet()) {
            checkUnicode(entry.getKey()); @Nullable String value = entry.getValue(); if (value != null) checkUnicode(value);
            vars.addProperty(entry.getKey(), value);
        }
        params.add("literals", inputs); params.add("variables", vars); params.add("artifacts", artifactPaths);
        return params;
    }

    /// Validates returned literal values while preserving null entries.
    static @Unmodifiable List<@Nullable String> decode(JsonObject response, int count) throws IOException {
        JsonArray values = response.getAsJsonArray("values");
        if (values.size() != count) throw new IOException("Invalid native processor value count");
        List<@Nullable String> result = new ArrayList<>(values.size());
        for (JsonElement value : values) {
            if (value.isJsonNull()) result.add(null);
            else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) result.add(value.getAsString());
            else throw new IOException("Invalid native processor value");
        }
        return Collections.unmodifiableList(result);
    }

    /// Rejects malformed UTF-16 that cannot round-trip through the native UTF-8 protocol.
    /// @param value string to inspect
    /// @throws IOException if a surrogate is unpaired
    private static void checkUnicode(String value) throws IOException {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw new IOException("Invalid UTF-16");
            } else if (Character.isLowSurrogate(c)) throw new IOException("Invalid UTF-16");
        }
    }
}
