/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.auth.authlibinjector;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/// Reads server metadata through an isolated native process without logging its contents.
final class NativeAuthlibMetadata {
    private NativeAuthlibMetadata() { }

    record Plan(@Nullable String name, Map<String, String> links, boolean nonEmailLogin) { }

    static @Nullable Plan tryParse(String metadata) {
        @Nullable String binary = System.getProperty("mfcl.native.path");
        if (binary == null || binary.isBlank() || !Boolean.parseBoolean(System.getProperty("mfcl.native.yggdrasil.enabled", "true"))) return null;
        try {
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(metadata) || metadata.getBytes(StandardCharsets.UTF_8).length > 768 * 1024) return null;
            JsonObject result = NativeBackend.planAuthlibMetadata(Path.of(binary), metadata);
            JsonElement name = result.get("name"), feature = result.get("non_email_login"), rawLinks = result.get("links"), present = result.get("links_present");
            if (name == null || (!name.isJsonNull() && (!name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()))
                    || feature == null || !feature.isJsonPrimitive() || !feature.getAsJsonPrimitive().isBoolean()
                    || present == null || !present.isJsonPrimitive() || !present.getAsJsonPrimitive().isBoolean()
                    || rawLinks == null || !rawLinks.isJsonArray() || rawLinks.getAsJsonArray().size() > 20_000) return null;
            Map<String, String> links = new LinkedHashMap<>();
            for (JsonElement item : rawLinks.getAsJsonArray()) {
                JsonObject entry = item.getAsJsonObject(); JsonElement key = entry.get("key"), value = entry.get("value");
                if (key == null || value == null || !key.isJsonPrimitive() || !key.getAsJsonPrimitive().isString()
                        || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                        || links.putIfAbsent(key.getAsString(), value.getAsString()) != null) return null;
            }
            if (!present.getAsBoolean() && !links.isEmpty()) return null;
            return new Plan(name.isJsonNull() ? null : name.getAsString(), present.getAsBoolean() ? links : java.util.Collections.emptyMap(), feature.getAsBoolean());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return null;
        } catch (IOException | RuntimeException e) { return null; }
    }
}
