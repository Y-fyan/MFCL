/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.modpack.curse;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/// Selects CurseForge metadata lookups and downloads in Rust.
@NotNullByDefault
final class NativeCurseSelection {
    /// Prevents instantiation.
    private NativeCurseSelection() { }

    /// A selected integrity check and the hash metadata it was computed from.
    /// @param integrity planned check, or null if no supported hash exists
    /// @param hashes immutable source snapshot, or null
    @NotNullByDefault
    record DownloadPlan(@Nullable FileDownloadTask.IntegrityCheck integrity, @Nullable Map<String, @Nullable String> hashes) {
        /// Uses the plan only while the source hashes remain unchanged.
        /// @param file current manifest entry
        /// @return planned or freshly computed integrity check
        @Nullable FileDownloadTask.IntegrityCheck currentIntegrity(CurseManifestFile file) {
            return Objects.equals(hashes, file.hashes()) ? integrity : file.getIntegrityCheck();
        }
    }

    /// Plans candidate downloads and their integrity checks in source order.
    /// @param files ordered manifest files
    /// @param excluded excluded file keys, or null
    /// @param cancelled owning task cancellation predicate
    /// @return selected plans, or null to use Java
    /// @throws InterruptedException when cancelled
    static @Nullable Map<Integer, DownloadPlan> tryPlan(List<CurseManifestFile> files, @Nullable Set<String> excluded,
            BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank() || !Boolean.parseBoolean(System.getProperty("mfcl.native.modpack.enabled", "true"))) return null;
        try {
            JsonArray entries = new JsonArray(); List<@Nullable Map<String, @Nullable String>> snapshots = new ArrayList<>(files.size());
            for (CurseManifestFile file : files) {
                if (cancelled.getAsBoolean()) throw new InterruptedException();
                @Nullable Map<String, @Nullable String> hashes = file.hashes() == null ? null : Collections.unmodifiableMap(new HashMap<>(file.hashes()));
                snapshots.add(hashes);
                JsonObject item = new JsonObject(); item.addProperty("name", file.fileName());
                item.addProperty("project", file.projectID()); item.addProperty("file", file.fileID());
                if (hashes != null) {
                    JsonObject encoded = new JsonObject();
                    for (String key : List.of("sha1", "sha256", "sha512", "md5"))
                        if (hashes.containsKey(key)) encoded.addProperty(key, hashes.get(key));
                    item.add("hashes", encoded);
                }
                entries.add(item);
            }
            JsonObject params = new JsonObject(); params.addProperty("mode", "download"); params.add("files", entries);
            if (excluded != null) { JsonArray keys = new JsonArray(); excluded.forEach(keys::add); params.add("excluded", keys); }
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(params.toString())) return null;
            JsonObject response = NativeBackend.planCurseDownloads(Path.of(executable), params, cancelled);
            Map<Integer, DownloadPlan> plans = new java.util.LinkedHashMap<>(); int previous = -1;
            for (JsonElement raw : response.getAsJsonArray("files")) {
                JsonObject item = raw.getAsJsonObject(); JsonElement number = item.get("index");
                if (!number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber() || !number.getAsString().matches("0|[1-9][0-9]*")) return null;
                int index = Integer.parseInt(number.getAsString());
                if (index <= previous || index >= files.size()) return null;
                JsonElement rawIntegrity = item.get("integrity");
                @Nullable FileDownloadTask.IntegrityCheck integrity = null;
                if (rawIntegrity == null) return null;
                if (!rawIntegrity.isJsonNull()) {
                    JsonObject check = rawIntegrity.getAsJsonObject();
                    JsonElement algorithm = check.get("algorithm"), checksum = check.get("checksum");
                    if (!algorithm.isJsonPrimitive() || !algorithm.getAsJsonPrimitive().isString()
                            || !checksum.isJsonPrimitive() || !checksum.getAsJsonPrimitive().isString()
                            || !List.of("SHA-1", "SHA-256", "SHA-512", "MD5").contains(algorithm.getAsString())) return null;
                    integrity = new FileDownloadTask.IntegrityCheck(algorithm.getAsString(), checksum.getAsString());
                }
                // Reject a malformed plan before it could silently remove an available integrity check.
                if (!Objects.equals(snapshots.get(index), files.get(index).hashes())
                        || !Objects.equals(integrity, files.get(index).getIntegrityCheck())) return null;
                plans.put(index, new DownloadPlan(integrity, snapshots.get(index))); previous = index;
            }
            return Collections.unmodifiableMap(plans);
        } catch (IOException | RuntimeException e) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            return null;
        }
    }

    /// Plans one stage or returns null when Java must handle unusual metadata.
    /// @param files ordered manifest files
    /// @param excluded excluded file keys, or null
    /// @param resolve whether to select unresolved metadata instead of downloads
    /// @param cancelled owning task cancellation predicate
    /// @return selection flags, or null to use Java
    /// @throws InterruptedException when cancelled
    static boolean @Nullable [] trySelect(List<CurseManifestFile> files, @Nullable Set<String> excluded,
            boolean resolve, BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank() || !Boolean.parseBoolean(System.getProperty("mfcl.native.modpack.enabled", "true"))) return null;
        try {
            JsonArray entries = new JsonArray();
            for (CurseManifestFile file : files) {
                if (cancelled.getAsBoolean()) throw new InterruptedException();
                JsonObject item = new JsonObject(); item.addProperty("name", file.fileName());
                item.addProperty("project", file.projectID()); item.addProperty("file", file.fileID()); entries.add(item);
            }
            JsonObject params = new JsonObject(); params.addProperty("mode", resolve ? "resolve" : "download"); params.add("files", entries);
            if (excluded != null) { JsonArray keys = new JsonArray(); excluded.forEach(keys::add); params.add("excluded", keys); }
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(params.toString())) return null;
            return NativeBackend.selectCurseFiles(Path.of(executable), params, files.size(), cancelled);
        } catch (IOException | RuntimeException e) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            return null;
        }
    }
}
