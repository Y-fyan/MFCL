/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// Adapts typed manifest values to opaque tokens so Rust can perform the merge without JSON re-parsing.
@NotNullByDefault
final class NativeManifestMerge {
    /// Values retain their Java types and ownership; token zero is represented by JSON null.
    private final List<@Nullable Object> values = new ArrayList<>();

    /// Creates an empty value registry.
    private NativeManifestMerge() { values.add(null); }

    /// Merges a child and parent using the native backend.
    /// @param executable backend executable
    /// @param child child manifest
    /// @param parent parent manifest
    /// @return merged manifest with the same typed values as Java's merge
    /// @throws IOException if the response is invalid or native processing fails
    /// @throws InterruptedException if interrupted
    static GameInstanceManifest merge(Path executable, GameInstanceManifest child, GameInstanceManifest parent)
            throws IOException, InterruptedException {
        NativeManifestMerge registry = new NativeManifestMerge();
        JsonObject params = new JsonObject();
        params.add("child", registry.encode(child, true)); params.add("parent", registry.encode(parent, false));
        JsonObject merged = NativeBackend.mergeManifestTokens(executable, params);
        try {
            GameInstanceID id = registry.required(merged, "id", GameInstanceID.class);
            if (!id.equals(child.id())) throw new IOException("Invalid native manifest identity");
            @Nullable Arguments arguments = registry.decodeArguments(merged.get("arguments"));
            @SuppressWarnings("unchecked") @Nullable Map<DownloadType, DownloadInfo> downloads =
                    (Map<DownloadType, DownloadInfo>) registry.optional(merged, "downloads", Map.class);
            @SuppressWarnings("unchecked") @Nullable Map<DownloadType, LoggingInfo> logging =
                    (Map<DownloadType, LoggingInfo>) registry.optional(merged, "logging", Map.class);
            @Nullable Integer minimum = merged.get("minimum").isJsonNull() ? null : merged.get("minimum").getAsBigDecimal().intValueExact();
            return new GameInstanceManifest(id,
                    registry.optional(merged, "minecraft_arguments", String.class), arguments,
                    registry.optional(merged, "main_class", String.class), null,
                    registry.optional(merged, "jar", GameInstanceID.class),
                    registry.optional(merged, "asset_index", AssetIndexInfo.class),
                    registry.optional(merged, "assets", String.class),
                    registry.optional(merged, "compliance", Integer.class),
                    registry.optional(merged, "java_version", GameJavaVersion.class),
                    registry.list(merged.get("libraries"), Library.class, false),
                    registry.list(merged.get("rules"), CompatibilityRule.class, false),
                    downloads, logging, registry.optional(merged, "type", ReleaseType.class),
                    registry.optional(merged, "time", Instant.class), registry.optional(merged, "release_time", Instant.class),
                    minimum, true, registry.optional(merged, "hidden", Boolean.class),
                    registry.list(merged.get("patches"), GameInstancePatch.class, false), null);
        } catch (RuntimeException e) {
            throw new IOException("Invalid native manifest merge response", e);
        }
    }

    /// Applies a manifest's patches in priority order while retaining the base's raw JSON behavior.
    /// @param executable native backend executable
    /// @param source manifest declaring patches and final identity
    /// @param base inherited intermediate manifest
    /// @return patched manifest
    /// @throws IOException if the response is invalid or native processing fails
    /// @throws InterruptedException if interrupted
    static GameInstanceManifest applyPatches(Path executable, GameInstanceManifest source, GameInstanceManifest base)
            throws IOException, InterruptedException {
        return applyPatches(executable, source, base, true);
    }

    /// Applies patches with either stable priority sorting or their original order.
    /// @param executable native backend executable
    /// @param source manifest declaring patches and final identity
    /// @param base inherited intermediate manifest
    /// @param sort whether to sort by priority
    /// @return patched manifest
    /// @throws IOException if the response is invalid or native processing fails
    /// @throws InterruptedException if interrupted
    static GameInstanceManifest applyPatches(Path executable, GameInstanceManifest source, GameInstanceManifest base, boolean sort)
            throws IOException, InterruptedException {
        NativeManifestMerge registry = new NativeManifestMerge();
        JsonObject params = new JsonObject(); params.add("base", registry.encode(base, false));
        JsonArray patches = new JsonArray();
        for (GameInstancePatch patch : source.getPatches()) patches.add(registry.encodePatch(patch));
        params.add("patches", patches);
        JsonObject merged = sort ? NativeBackend.applyManifestPatchTokens(executable, params)
                : NativeBackend.applyOrderedManifestPatchTokens(executable, params);
        try {
            GameInstanceManifest.Builder builder = new GameInstanceManifest.Builder(base);
            builder.setId(source.id()); builder.setPatches(null);
            GameInstanceManifest shell = builder.toManifest();
            @SuppressWarnings("unchecked") @Nullable Map<DownloadType, DownloadInfo> downloads =
                    (Map<DownloadType, DownloadInfo>) registry.optional(merged, "downloads", Map.class);
            @SuppressWarnings("unchecked") @Nullable Map<DownloadType, LoggingInfo> logging =
                    (Map<DownloadType, LoggingInfo>) registry.optional(merged, "logging", Map.class);
            @Nullable Integer minimum = merged.get("minimum").isJsonNull() ? null : merged.get("minimum").getAsBigDecimal().intValueExact();
            return new GameInstanceManifest(shell.id(), registry.optional(merged, "minecraft_arguments", String.class),
                    registry.decodeArguments(merged.get("arguments")), registry.optional(merged, "main_class", String.class),
                    shell.inheritsFrom(), shell.jar(), registry.optional(merged, "asset_index", AssetIndexInfo.class),
                    registry.optional(merged, "assets", String.class), registry.optional(merged, "compliance", Integer.class),
                    registry.optional(merged, "java_version", GameJavaVersion.class), registry.list(merged.get("libraries"), Library.class, false),
                    registry.list(merged.get("rules"), CompatibilityRule.class, false), downloads, logging,
                    registry.optional(merged, "type", ReleaseType.class), registry.optional(merged, "time", Instant.class),
                    registry.optional(merged, "release_time", Instant.class), minimum, shell.root(), shell.hidden(), null, shell.rawJson());
        } catch (RuntimeException e) { throw new IOException("Invalid native manifest patch response", e); }
    }

    /// Adds a value to the registry and returns its opaque identifier.
    /// @param value value to register
    /// @return identifier, or null for a null value
    private @Nullable Integer register(@Nullable Object value) {
        if (value == null) return null;
        values.add(value); return values.size() - 1;
    }

    /// Encodes one nullable collection as ordered opaque identifiers.
    /// @param input collection to encode
    /// @return encoded array or null
    private JsonElement encodeList(@Nullable List<?> input) {
        if (input == null) return JsonNull.INSTANCE;
        JsonArray array = new JsonArray();
        for (@Nullable Object item : input) array.add(register(item));
        return array;
    }

    /// Encodes a typed manifest without serializing credentials or arbitrary raw JSON.
    /// @param manifest manifest to encode
    /// @param child whether a synthetic child patch is required
    /// @return normalized token object
    private JsonObject encode(GameInstanceManifest manifest, boolean child) {
        JsonObject result = new JsonObject();
        result.addProperty("id", register(manifest.id()));
        result.addProperty("minecraft_arguments", register(manifest.minecraftArguments()));
        result.addProperty("main_class", register(manifest.mainClass()));
        result.addProperty("jar", register(manifest.jar()));
        result.addProperty("asset_index", register(manifest.assetIndex()));
        result.addProperty("assets", register(manifest.assets()));
        result.addProperty("compliance", register(manifest.complianceLevel()));
        result.addProperty("java_version", register(manifest.javaVersion()));
        result.addProperty("downloads", register(manifest.downloads()));
        result.addProperty("logging", register(manifest.logging()));
        result.addProperty("type", register(manifest.type()));
        result.addProperty("time", register(manifest.time()));
        result.addProperty("release_time", register(manifest.releaseTime()));
        result.addProperty("hidden", register(manifest.hidden()));
        result.addProperty("self_patch", child ? register(manifest.toPatch()) : null);
        result.addProperty("minimum", manifest.minimumLauncherVersion());
        result.add("libraries", encodeList(manifest.libraries()));
        result.add("rules", encodeList(manifest.compatibilityRules()));
        result.add("patches", encodeList(manifest.patches()));
        result.add("arguments", encodeArguments(manifest.arguments()));
        return result;
    }

    /// Encodes arguments while preserving a token for an unchanged object.
    /// @param arguments optional arguments
    /// @return encoded arguments or JSON null
    private JsonElement encodeArguments(@Nullable Arguments arguments) {
        if (arguments == null) return JsonNull.INSTANCE;
        else {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("ref", register(arguments));
            encoded.add("game", encodeList(arguments.game())); encoded.add("jvm", encodeList(arguments.jvm()));
            return encoded;
        }
    }

    /// Encodes patch fields used by the existing builder merge.
    /// @param patch patch to encode
    /// @return normalized token object
    private JsonObject encodePatch(GameInstancePatch patch) {
        JsonObject result = new JsonObject(); result.addProperty("priority", patch.getPriority());
        result.addProperty("minecraft_arguments", register(patch.minecraftArguments()));
        result.addProperty("main_class", register(patch.mainClass()));
        result.addProperty("asset_index", register(patch.assetIndex()));
        result.addProperty("assets", register(patch.assets()));
        result.addProperty("compliance", register(patch.complianceLevel()));
        result.addProperty("java_version", register(patch.javaVersion()));
        result.addProperty("downloads", register(patch.downloads()));
        result.addProperty("logging", register(patch.logging()));
        result.addProperty("type", register(patch.type()));
        result.addProperty("time", register(patch.time()));
        result.addProperty("release_time", register(patch.releaseTime()));
        result.addProperty("minimum", patch.minimumLauncherVersion());
        result.add("libraries", encodeList(patch.libraries()));
        result.add("rules", encodeList(patch.compatibilityRules()));
        result.add("arguments", encodeArguments(patch.arguments()));
        return result;
    }

    /// Resolves one opaque identifier and checks its expected Java type.
    /// @param element encoded identifier
    /// @param type expected value class
    /// @param <T> expected type
    /// @return registered value or null
    /// @throws IOException if the identifier or type is invalid
    private <T> @Nullable T decode(@Nullable JsonElement element, Class<T> type) throws IOException {
        if (element == null) throw new IOException("Missing native manifest token");
        if (element.isJsonNull()) return null;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) throw new IOException("Invalid native manifest token");
        int id = element.getAsBigDecimal().intValueExact();
        if (id <= 0 || id >= values.size()) throw new IOException("Native manifest token out of range");
        @Nullable Object value = values.get(id);
        if (!type.isInstance(value)) throw new IOException("Native manifest token has wrong type");
        return type.cast(value);
    }

    /// Reads a nullable named token.
    /// @param object response object
    /// @param key field name
    /// @param type expected class
    /// @param <T> expected type
    /// @return resolved value or null
    /// @throws IOException if the token is invalid
    private <T> @Nullable T optional(JsonObject object, String key, Class<T> type) throws IOException {
        return decode(object.get(key), type);
    }

    /// Reads a required named token.
    /// @param object response object
    /// @param key field name
    /// @param type expected class
    /// @param <T> expected type
    /// @return resolved value
    /// @throws IOException if absent or invalid
    private <T> T required(JsonObject object, String key, Class<T> type) throws IOException {
        @Nullable T value = optional(object, key, type);
        if (value == null) throw new IOException("Missing required native manifest value");
        return value;
    }

    /// Resolves a nullable list of opaque identifiers.
    /// @param element encoded list
    /// @param type element type
    /// @param allowNull whether a null list is permitted
    /// @param <T> element type
    /// @return ordered decoded values, or null if allowed
    /// @throws IOException if an entry is invalid
    private <T> @Nullable List<T> list(@Nullable JsonElement element, Class<T> type, boolean allowNull) throws IOException {
        if (element == null) throw new IOException("Missing native manifest list");
        if (element.isJsonNull()) {
            if (!allowNull) throw new IOException("Missing required native manifest list");
            return null;
        }
        List<T> output = new ArrayList<>();
        for (JsonElement value : element.getAsJsonArray()) output.add(decode(value, type));
        return output;
    }

    /// Resolves native argument-list results.
    /// @param element encoded arguments
    /// @return decoded argument object or null
    /// @throws IOException if malformed
    private @Nullable Arguments decodeArguments(@Nullable JsonElement element) throws IOException {
        if (element == null) throw new IOException("Missing native arguments");
        if (element.isJsonNull()) return null;
        JsonObject object = element.getAsJsonObject();
        @Nullable Arguments original = decode(object.get("ref"), Arguments.class);
        if (original != null) return original;
        return new Arguments(list(object.get("game"), Argument.class, true), list(object.get("jvm"), Argument.class, true));
    }
}
