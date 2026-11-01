/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.game.ReleaseType;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jackhuang.hmcl.util.gson.InstantTypeAdapter;
import org.jackhuang.hmcl.util.gson.LowerCaseEnumTypeAdapter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Parses canonical game-version catalogs in Rust with Java compatibility fallback.
@NotNullByDefault
final class NativeGameVersions {
    /// Prevents instantiation.
    private NativeGameVersions() { }

    /// Parses an ordinary catalog while preserving record order and Java date semantics.
    /// @param metadata original downloaded catalog JSON
    /// @return parsed catalog, or null for the Java parser
    /// @throws IOException when interrupted
    static @Nullable GameRemoteVersions tryParse(String metadata) throws IOException {
        @Nullable String binary = System.getProperty("mfcl.native.path");
        if (binary == null || binary.isBlank() || !Boolean.parseBoolean(System.getProperty("mfcl.native.game.enabled", "true"))) return null;
        try {
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(metadata) || metadata.getBytes(StandardCharsets.UTF_8).length > 768 * 1024) return null;
            JsonObject result = NativeBackend.parseGameVersions(Path.of(binary), metadata);
            JsonArray values = result.getAsJsonArray("versions");
            if (values.size() > 20_000) return null;
            List<GameRemoteVersionInfo> versions = new ArrayList<>(values.size());
            for (JsonElement value : values) {
                JsonObject item = value.getAsJsonObject();
                String id = string(item, "id"), url = string(item, "url");
                if (id.isBlank() || url.isBlank()) return null;
                versions.add(new GameRemoteVersionInfo(id,
                        InstantTypeAdapter.deserializeToInstant(string(item, "time")),
                        InstantTypeAdapter.deserializeToInstant(string(item, "releaseTime")),
                        LowerCaseEnumTypeAdapter.fromJson(ReleaseType.class, string(item, "type")), url));
            }
            JsonObject latest = result.getAsJsonObject("latest");
            return new GameRemoteVersions(List.copyOf(versions), new GameRemoteLatestVersions(string(latest, "snapshot"), string(latest, "release")));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new InterruptedIOException("Native game version parsing was interrupted");
        } catch (IOException | RuntimeException e) { return null; }
    }

    /// Reads a required string from a native response.
    /// @param object response object
    /// @param name property name
    /// @return decoded string
    /// @throws IOException on invalid response
    private static String string(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Invalid native game version field");
        return value.getAsString();
    }
}
