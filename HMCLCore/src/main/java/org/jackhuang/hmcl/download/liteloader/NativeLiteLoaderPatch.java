/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.liteloader;

import com.google.gson.JsonObject;
import org.jackhuang.hmcl.download.game.NativeGameManifest;
import org.jackhuang.hmcl.game.Arguments;
import org.jackhuang.hmcl.game.GameInstancePatch;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/// Adapts Rust LiteLoader installation plans to the launcher patch model.
final class NativeLiteLoaderPatch {
    private NativeLiteLoaderPatch() { }

    static @Nullable GameInstancePatch tryPlan(LiteLoaderRemoteVersion remote, BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        @Nullable String binary = System.getProperty("mfcl.native.path");
        if (binary == null || binary.isBlank() || !Boolean.parseBoolean(System.getProperty("mfcl.native.loaders.enabled", "true"))) return null;
        try {
            JsonObject params = new JsonObject(); params.addProperty("version", remote.getSelfVersion());
            params.addProperty("url", remote.getUrls().get(0)); params.addProperty("libraries", JsonUtils.GSON.toJson(remote.getLibraries()));
            JsonObject result = NativeBackend.planLiteLoaderPatch(Path.of(binary), params, cancelled);
            for (String marker : List.of("library_v3", "rules_v1")) {
                var value = result.get(marker);
                if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean() || !value.getAsBoolean()) return null;
            }
            String id = string(result, "id"), version = string(result, "version");
            if (!"liteloader".equals(id) || !remote.getSelfVersion().equals(version)) return null;
            var priority = result.get("priority");
            if (priority == null || !priority.isJsonPrimitive() || !priority.getAsJsonPrimitive().isNumber()) return null;
            var libraries = NativeGameManifest.decodeNativeLibraries(result.get("libraries"));
            if (libraries == null) return null;
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            return new GameInstancePatch(id, version, priority.getAsBigDecimal().intValueExact(),
                    new Arguments().addGameArguments("--tweakClass", string(result, "tweaker")),
                    string(result, "main_class"), libraries).withLogging(Collections.emptyMap());
        } catch (IOException | RuntimeException e) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            return null;
        }
    }

    private static String string(JsonObject object, String name) throws IOException {
        var value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Invalid LiteLoader plan");
        return value.getAsString();
    }
}
