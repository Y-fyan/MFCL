/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.java.mojang;

import com.google.gson.JsonObject;
import org.jackhuang.hmcl.game.GameJavaVersion;
import org.jackhuang.hmcl.java.JavaInfo;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/// Selects canonical runtime metadata using Rust with a compatibility fallback.
@NotNullByDefault
final class NativeRuntimeList {
    /// Prevents instantiation.
    private NativeRuntimeList() { }

    /// Selects a runtime, or lets the existing Java parser handle unusual metadata.
    /// @param metadata runtime index JSON
    /// @param platform operating system platform key
    /// @param version required runtime component and major version
    /// @param cancelled owning task cancellation predicate
    /// @return compatible runtime, or null to use the Java path
    /// @throws InterruptedException when cancelled
    static @Nullable MojangJavaDownloads.JavaDownload trySelect(String metadata, String platform, GameJavaVersion version, BooleanSupplier cancelled)
            throws InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank() || !Boolean.parseBoolean(System.getProperty("mfcl.native.java.enabled", "true"))) return null;
        try {
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(metadata) || !StandardCharsets.UTF_8.newEncoder().canEncode(platform)
                    || !StandardCharsets.UTF_8.newEncoder().canEncode(version.component())
                    || metadata.getBytes(StandardCharsets.UTF_8).length > 768 * 1024) return null;
            JsonObject params = new JsonObject();
            params.addProperty("metadata", metadata); params.addProperty("platform", platform);
            params.addProperty("component", version.component()); params.addProperty("major", version.majorVersion());
            JsonObject result = NativeBackend.selectJavaRuntime(Path.of(executable), params, cancelled);
            if (!result.has("selected") || !result.get("selected").isJsonObject()) return null;
            MojangJavaDownloads.JavaDownload selected = JsonUtils.GSON.fromJson(result.get("selected"), MojangJavaDownloads.JavaDownload.class);
            if (selected == null || selected.manifest() == null || selected.manifest().getUrl() == null
                    || selected.version() == null || selected.version().name() == null
                    || JavaInfo.parseVersion(selected.version().name()) < version.majorVersion()) return null;
            return selected;
        } catch (IOException | RuntimeException e) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            return null;
        }
    }
}
