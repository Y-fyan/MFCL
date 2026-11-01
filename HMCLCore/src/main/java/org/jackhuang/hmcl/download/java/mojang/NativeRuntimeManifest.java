/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.java.mojang;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.game.DownloadInfo;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/// Builds runtime records from a Rust-parsed ordered manifest.
@NotNullByDefault
final class NativeRuntimeManifest {
    /// Prevents instantiation.
    private NativeRuntimeManifest() { }

    /// Parses ordinary manifests or delegates unusual inputs to the existing Java parser.
    /// @param metadata runtime manifest JSON
    /// @param cancelled owning task cancellation predicate
    /// @return parsed files or null when Java should handle the manifest
    /// @throws InterruptedException when cancelled
    static @Nullable MojangJavaRemoteFiles tryParse(String metadata, BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank() || !Boolean.parseBoolean(System.getProperty("mfcl.native.java.enabled", "true"))) return null;
        try {
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(metadata) || metadata.getBytes(StandardCharsets.UTF_8).length > 512 * 1024) return null;
            JsonObject params = new JsonObject(); params.addProperty("metadata", metadata);
            JsonObject response = NativeBackend.parseJavaRuntimeManifest(Path.of(executable), params, cancelled);
            Map<String, MojangJavaRemoteFiles.Remote> files = new LinkedHashMap<>();
            for (JsonElement item : response.getAsJsonArray("entries")) {
                if (cancelled.getAsBoolean()) throw new InterruptedException();
                JsonObject entry = item.getAsJsonObject();
                String path = entry.get("path").getAsString();
                String type = entry.get("type").getAsString();
                MojangJavaRemoteFiles.Remote remote;
                switch (type) {
                    case "directory" -> remote = new MojangJavaRemoteFiles.RemoteDirectory();
                    case "link" -> remote = new MojangJavaRemoteFiles.RemoteLink(entry.get("target").getAsString());
                    case "file" -> {
                        Map<String, DownloadInfo> downloads = new LinkedHashMap<>();
                        for (JsonElement value : entry.getAsJsonArray("downloads")) {
                            JsonObject download = value.getAsJsonObject();
                            String name = download.get("name").getAsString();
                            if (downloads.put(name, new DownloadInfo(download.get("url").getAsString(), download.get("sha1").getAsString(), download.get("size").getAsInt())) != null) return null;
                        }
                        remote = new MojangJavaRemoteFiles.RemoteFile(entry.get("executable").getAsBoolean(), downloads);
                    }
                    default -> { return null; }
                }
                if (files.put(path, remote) != null) return null;
            }
            return new MojangJavaRemoteFiles(files);
        } catch (IOException | RuntimeException e) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            return null;
        }
    }
}
