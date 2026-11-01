/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.auth.yggdrasil;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Decodes ordinary Yggdrasil texture properties through an isolated native child.
@NotNullByDefault
final class NativeYggdrasilTextures {
    /// Prevents instantiation.
    private NativeYggdrasilTextures() { }

    /// A native texture result, distinct from the Java-fallback sentinel.
    /// @param present whether the provider returned a texture map
    /// @param textures decoded map, or null when absent
    @NotNullByDefault
    record Result(boolean present, @Nullable Map<TextureType, @Nullable Texture> textures) { }

    /// Attempts native decoding without logging the encoded property or texture data.
    /// @param encoded Base64 texture property
    /// @return native result, or null when the existing Java parser must handle it
    static @Nullable Result tryParse(String encoded) {
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank()
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.yggdrasil.enabled", "true"))) return null;
        try {
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(encoded) || encoded.getBytes(StandardCharsets.UTF_8).length > 768 * 1024) return null;
            JsonObject params = new JsonObject(); params.addProperty("textures", encoded);
            JsonObject envelope = new JsonObject(); envelope.addProperty("version", 1); envelope.addProperty("id", 1);
            envelope.addProperty("method", "yggdrasil_plan_textures"); envelope.add("params", params);
            if ((envelope + "\n").getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) return null;
            JsonObject result = NativeBackend.planYggdrasilTextures(Path.of(executable), params);
            @Nullable JsonElement present = result.get("present"), values = result.get("textures");
            if (present == null || !present.isJsonPrimitive() || !present.getAsJsonPrimitive().isBoolean() || values == null)
                throw new IOException("Invalid native texture response");
            if (!present.getAsBoolean()) {
                if (!values.isJsonNull()) throw new IOException("Invalid native texture response");
                return new Result(false, null);
            }
            if (!values.isJsonArray()) throw new IOException("Invalid native texture response");
            JsonArray entries = values.getAsJsonArray(); if (entries.size() > 2) throw new IOException("Invalid native texture count");
            Map<TextureType, @Nullable Texture> textures = new LinkedHashMap<>();
            for (JsonElement entryValue : entries) {
                JsonObject entry = entryValue.getAsJsonObject(); String typeName = requiredString(entry, "type");
                TextureType type;
                try { type = TextureType.valueOf(typeName); }
                catch (IllegalArgumentException e) { throw new IOException("Invalid native texture type"); }
                if (textures.containsKey(type)) throw new IOException("Duplicate native texture type");
                @Nullable JsonElement textureValue = entry.get("texture");
                if (textureValue == null) throw new IOException("Invalid native texture value");
                if (textureValue.isJsonNull()) { textures.put(type, null); continue; }
                JsonObject texture = textureValue.getAsJsonObject();
                @Nullable String imageUrl = optionalString(texture, "url"); @Nullable JsonElement metadataValue = texture.get("metadata");
                if (metadataValue == null) throw new IOException("Invalid native texture metadata");
                @Nullable Map<String, @Nullable String> metadata = null;
                if (!metadataValue.isJsonNull()) {
                    JsonArray pairs = metadataValue.getAsJsonArray(); if (pairs.size() > 20_000) throw new IOException("Invalid native metadata count");
                    metadata = new LinkedHashMap<>();
                    for (JsonElement pairValue : pairs) {
                        JsonObject pair = pairValue.getAsJsonObject(); String name = requiredString(pair, "name");
                        if (metadata.containsKey(name)) throw new IOException("Duplicate native metadata key");
                        metadata.put(name, optionalString(pair, "value"));
                    }
                }
                textures.put(type, new Texture(imageUrl, metadata));
            }
            return new Result(true, textures);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return null;
        } catch (IOException | RuntimeException e) {
            LOG.debug("Native Yggdrasil texture parsing unavailable; using Java adapter"); return null;
        }
    }

    /// Reads a required string result field.
    /// @param object native result
    /// @param key field name
    /// @return string value
    /// @throws IOException if absent or invalid
    private static String requiredString(JsonObject object, String key) throws IOException {
        @Nullable String value = optionalString(object, key);
        if (value == null) throw new IOException("Invalid native texture field");
        return value;
    }

    /// Reads a nullable string result field.
    /// @param object native result
    /// @param key field name
    /// @return string value, or null
    /// @throws IOException if absent or invalid
    private static @Nullable String optionalString(JsonObject object, String key) throws IOException {
        @Nullable JsonElement value = object.get(key);
        if (value == null) throw new IOException("Invalid native texture field");
        if (value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Invalid native texture field");
        String text = value.getAsString();
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(text)) throw new IOException("Invalid native texture text");
        return text;
    }
}
