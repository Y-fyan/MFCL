/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.game.Arguments;
import org.jackhuang.hmcl.game.Argument;
import org.jackhuang.hmcl.game.Artifact;
import org.jackhuang.hmcl.game.AssetIndexInfo;
import org.jackhuang.hmcl.game.CompatibilityRule;
import org.jackhuang.hmcl.game.DownloadInfo;
import org.jackhuang.hmcl.game.DownloadType;
import org.jackhuang.hmcl.game.ExtractRules;
import org.jackhuang.hmcl.game.GameComponentType;
import org.jackhuang.hmcl.game.GameInstanceID;
import org.jackhuang.hmcl.game.GameInstancePatch;
import org.jackhuang.hmcl.game.GameInstanceManifest;
import org.jackhuang.hmcl.game.GameJavaVersion;
import org.jackhuang.hmcl.game.IdDownloadInfo;
import org.jackhuang.hmcl.game.Library;
import org.jackhuang.hmcl.game.LibraryDownloadInfo;
import org.jackhuang.hmcl.game.LibrariesDownloadInfo;
import org.jackhuang.hmcl.game.LoggingInfo;
import org.jackhuang.hmcl.game.OSRestriction;
import org.jackhuang.hmcl.game.ReleaseType;
import org.jackhuang.hmcl.game.RuledArgument;
import org.jackhuang.hmcl.game.StringArgument;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jackhuang.hmcl.util.ImmutableSequencedMap;
import org.jackhuang.hmcl.util.gson.InstantTypeAdapter;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.gson.LowerCaseEnumTypeAdapter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Builds a base-game patch from a native manifest projection.
@NotNullByDefault
public final class NativeGameManifest {
    /// Prevents instantiation.
    private NativeGameManifest() { }

    /// Projects standard manifest fields while retaining existing nested type adapters.
    /// @param metadata original game manifest JSON
    /// @param version selected game version
    /// @return base-game patch, or null for Java parsing
    /// @throws IOException if interrupted
    static @Nullable GameInstancePatch tryPatch(String metadata, String version) throws IOException {
        @Nullable String binary = System.getProperty("mfcl.native.path");
        if (binary == null || binary.isBlank() || !Boolean.parseBoolean(System.getProperty("mfcl.native.game.enabled", "true"))) return null;
        try {
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(metadata) || metadata.getBytes(StandardCharsets.UTF_8).length > 768 * 1024) return null;
            JsonObject response = NativeBackend.projectGameManifest(Path.of(binary), metadata);
            JsonObject fields = response.getAsJsonObject("fields");
            boolean nativeNested = response.has("nested_v1");
            if (nativeNested && (!response.get("nested_v1").isJsonPrimitive()
                    || !response.get("nested_v1").getAsJsonPrimitive().isBoolean() || !response.get("nested_v1").getAsBoolean()
                    || !response.has("asset") || !response.has("downloads"))) return null;
            boolean nativeLibraryV3 = response.has("library_v3");
            boolean nativeLibraryV2 = nativeLibraryV3 || response.has("library_v2");
            boolean nativeLibraries = nativeLibraryV2 || response.has("library_v1");
            String libraryMarker = nativeLibraryV3 ? "library_v3" : nativeLibraryV2 ? "library_v2" : "library_v1";
            if (nativeLibraries && (!response.get(libraryMarker).isJsonPrimitive()
                    || !response.get(libraryMarker).getAsJsonPrimitive().isBoolean() || !response.get(libraryMarker).getAsBoolean()
                    || !response.has("libraries"))) return null;
            boolean nativeArguments = response.has("arguments_v1");
            if (nativeArguments && (!response.get("arguments_v1").isJsonPrimitive()
                    || !response.get("arguments_v1").getAsJsonPrimitive().isBoolean() || !response.get("arguments_v1").getAsBoolean()
                    || !response.has("arguments"))) return null;
            boolean nativeRules = response.has("rules_v1");
            if (nativeRules && (!response.get("rules_v1").isJsonPrimitive()
                    || !response.get("rules_v1").getAsJsonPrimitive().isBoolean() || !response.get("rules_v1").getAsBoolean())) return null;
            boolean nativeRuntimeLogging = response.has("runtime_logging_v1");
            if (nativeRuntimeLogging && (!response.get("runtime_logging_v1").isJsonPrimitive()
                    || !response.get("runtime_logging_v1").getAsJsonPrimitive().isBoolean() || !response.get("runtime_logging_v1").getAsBoolean()
                    || !response.has("javaVersion") || !response.has("logging"))) return null;
            String id = text(fields, "id");
            if (id == null) return null;
            new GameInstanceID(id); // Keep the original manifest ID validation.
            return new GameInstancePatch(GameComponentType.GAME.getPatchId(), version, GameInstancePatch.PRIORITY_MC,
                    text(fields, "minecraftArguments"), nativeArguments ? nativeArguments(response.get("arguments"), nativeRules) : nested(fields, "arguments", Arguments.class), text(fields, "mainClass"),
                    null, null, nativeNested ? asset(response.get("asset")) : nested(fields, "assetIndex", AssetIndexInfo.class), text(fields, "assets"),
                    integer(fields, "complianceLevel"), nativeRuntimeLogging ? nativeJavaVersion(response.get("javaVersion")) : nested(fields, "javaVersion", GameJavaVersion.class),
                    nativeLibraries ? nativeLibraries(response.get("libraries"), nativeLibraryV2, nativeLibraryV3, nativeRules) : libraries(fields), null, nativeNested ? downloads(response.get("downloads")) : typedMap(fields, "downloads", DownloadInfo.class),
                    nativeRuntimeLogging ? nativeLogging(response.get("logging")) : typedMap(fields, "logging", LoggingInfo.class),
                    releaseType(fields), instant(fields, "time"), instant(fields, "releaseTime"), integer(fields, "minimumLauncherVersion"),
                    bool(fields, "hidden"), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new InterruptedIOException("Native game manifest parsing was interrupted");
        } catch (IOException | RuntimeException e) { return null; }
    }

    /// Projects a local instance, falling back when the format is unsupported.
    public static @Nullable GameInstanceManifest tryInstanceManifest(String metadata) {
        @Nullable String binary = System.getProperty("mfcl.native.path");
        if (binary == null || binary.isBlank() || !Boolean.parseBoolean(System.getProperty("mfcl.native.instances.enabled", "true"))) return null;
        try {
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(metadata) || metadata.getBytes(StandardCharsets.UTF_8).length > 768 * 1024) return null;
            return decodeLoaderManifest(NativeBackend.projectInstanceManifest(Path.of(binary), metadata), metadata);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return null;
        } catch (IOException | RuntimeException e) { return null; }
    }

    /// Reconstructs an inherited loader manifest while preserving its original JSON for later serialization.
    public static GameInstanceManifest decodeLoaderManifest(JsonObject response, String metadata) throws IOException {
        for (String marker : List.of("loader_v1", "nested_v1", "library_v3", "arguments_v1", "rules_v1", "runtime_logging_v1")) {
            JsonElement value = response.get(marker);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean() || !value.getAsBoolean())
                throw new IOException("Unsupported native loader projection");
        }
        for (String key : List.of("fields", "asset", "downloads", "libraries", "arguments", "manifest_rules", "javaVersion", "logging"))
            if (!response.has(key)) throw new IOException("Incomplete native loader projection");
        try {
            JsonObject fields = response.getAsJsonObject("fields");
            @Nullable String idText = text(fields, "id");
            if (idText == null) throw new IOException("Missing native loader ID");
            @Nullable List<CompatibilityRule> rules = nativeRules(response.get("manifest_rules"));
            if (rules != null) rules = List.copyOf(rules);
            JsonElement rawJson = JsonParser.parseString(metadata);
            if (!rawJson.isJsonObject()) throw new IOException("Invalid loader JSON");
            @Nullable List<GameInstancePatch> patches = null;
            JsonElement rawPatches = rawJson.getAsJsonObject().get("patches");
            if (rawPatches != null) {
                JsonElement marker = response.get("patches_v1"), projected = response.get("patches");
                if (marker == null || !marker.isJsonPrimitive() || !marker.getAsJsonPrimitive().isBoolean() || !marker.getAsBoolean() || projected == null)
                    throw new IOException("Unsupported native patch projection");
                if (rawPatches.isJsonArray()) {
                    if (!projected.isJsonArray()) throw new IOException("Invalid native patches");
                    patches = new ArrayList<>(); int index = 0;
                    for (JsonElement rawPatch : rawPatches.getAsJsonArray()) {
                        if (!rawPatch.isJsonObject()) continue;
                        if (index >= projected.getAsJsonArray().size()) throw new IOException("Missing native patch");
                        patches.add(decodePatch(projected.getAsJsonArray().get(index++).getAsJsonObject(), rawPatch.getAsJsonObject()));
                    }
                    if (index != projected.getAsJsonArray().size()) throw new IOException("Unexpected native patch");
                    patches = List.copyOf(patches);
                } else if (!projected.isJsonNull()) throw new IOException("Unexpected native patches");
            }
            return new GameInstanceManifest(new GameInstanceID(idText), text(fields, "minecraftArguments"),
                    nativeArguments(response.get("arguments"), true), text(fields, "mainClass"),
                    optionalId(fields, "inheritsFrom"), optionalId(fields, "jar"), asset(response.get("asset")), text(fields, "assets"),
                    integer(fields, "complianceLevel"), nativeJavaVersion(response.get("javaVersion")),
                    nativeLibraries(response.get("libraries"), true, true, true), rules, downloads(response.get("downloads")),
                    nativeLogging(response.get("logging")), releaseType(fields), instant(fields, "time"), instant(fields, "releaseTime"),
                    integer(fields, "minimumLauncherVersion"), bool(fields, "root"), bool(fields, "hidden"), patches, rawJson.getAsJsonObject());
        } catch (RuntimeException e) { throw new IOException("Invalid native loader projection", e); }
    }

    /// Reconstructs a projected patch while preserving its original JSON.
    private static GameInstancePatch decodePatch(JsonObject projection, JsonObject original) throws IOException {
        for (String marker : List.of("loader_v1", "nested_v1", "library_v3", "arguments_v1", "rules_v1", "runtime_logging_v1")) {
            JsonElement value = projection.get(marker);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean() || !value.getAsBoolean())
                throw new IOException("Unsupported native patch projection");
        }
        for (String key : List.of("fields", "asset", "downloads", "libraries", "arguments", "manifest_rules", "javaVersion", "logging"))
            if (!projection.has(key)) throw new IOException("Incomplete native patch projection");
        JsonObject fields = projection.getAsJsonObject("fields");
        @Nullable List<CompatibilityRule> rules = nativeRules(projection.get("manifest_rules"));
        if (rules != null) rules = List.copyOf(rules);
        return new GameInstancePatch(text(fields, "id"), text(fields, "version"), integer(fields, "priority"),
                text(fields, "minecraftArguments"), nativeArguments(projection.get("arguments"), true), text(fields, "mainClass"),
                optionalId(fields, "inheritsFrom"), optionalId(fields, "jar"), asset(projection.get("asset")), text(fields, "assets"),
                integer(fields, "complianceLevel"), nativeJavaVersion(projection.get("javaVersion")),
                nativeLibraries(projection.get("libraries"), true, true, true), rules, downloads(projection.get("downloads")),
                nativeLogging(projection.get("logging")), releaseType(fields), instant(fields, "time"), instant(fields, "releaseTime"),
                integer(fields, "minimumLauncherVersion"), bool(fields, "hidden"), original.deepCopy());
    }

    /// Matches the Java loader's treatment of invalid optional instance IDs.
    private static @Nullable GameInstanceID optionalId(JsonObject fields, String key) throws IOException {
        @Nullable String value = text(fields, key);
        if (value == null) return null;
        try { return new GameInstanceID(value); } catch (IllegalArgumentException ignored) { return null; }
    }

    /// Parses a raw projected JSON value.
    /// @param fields native projection
    /// @param key manifest field name
    /// @return decoded JSON, or null if absent
    /// @throws IOException if the projection is malformed
    private static @Nullable JsonElement raw(JsonObject fields, String key) throws IOException {
        @Nullable JsonElement encoded = fields.get(key);
        if (encoded == null) return null;
        if (!encoded.isJsonPrimitive() || !encoded.getAsJsonPrimitive().isString()) throw new IOException("Invalid native manifest field");
        return JsonParser.parseString(encoded.getAsString());
    }

    /// Reads a nullable string field.
    /// @param fields native projection
    /// @param key field name
    /// @return string or null
    /// @throws IOException if the projection is malformed
    private static @Nullable String text(JsonObject fields, String key) throws IOException {
        @Nullable JsonElement value = raw(fields, key);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Invalid native manifest string");
        return value.getAsString();
    }

    /// Reads a nullable integer field.
    /// @param fields native projection
    /// @param key field name
    /// @return integer or null
    /// @throws IOException if the projection is malformed
    private static @Nullable Integer integer(JsonObject fields, String key) throws IOException {
        @Nullable JsonElement value = raw(fields, key);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IOException("Invalid native manifest number");
        return value.getAsBigDecimal().intValueExact();
    }

    /// Reads a nullable boolean field.
    /// @param fields native projection
    /// @param key field name
    /// @return boolean or null
    /// @throws IOException if the projection is malformed
    private static @Nullable Boolean bool(JsonObject fields, String key) throws IOException {
        @Nullable JsonElement value = raw(fields, key);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IOException("Invalid native manifest flag");
        return value.getAsBoolean();
    }

    /// Applies an existing nested Gson adapter.
    /// @param fields native projection
    /// @param key field name
    /// @param type nested type
    /// @return decoded value or null
    /// @throws IOException if the projection is malformed
    private static <T> @Nullable T nested(JsonObject fields, String key, Class<T> type) throws IOException {
        @Nullable JsonElement value = raw(fields, key);
        return value == null || value.isJsonNull() ? null : JsonUtils.GSON.fromJson(value, type);
    }

    /// Builds game and JVM arguments from native-normalized descriptors.
    private static @Nullable Arguments nativeArguments(JsonElement value, boolean nativeRules) throws IOException {
        if (value == null || value.isJsonNull()) return null;
        JsonObject object = value.getAsJsonObject();
        if (!object.has("game") || !object.has("jvm")) throw new IOException("Missing native arguments");
        return new Arguments(nativeArgumentList(object.get("game"), nativeRules), nativeArgumentList(object.get("jvm"), nativeRules));
    }

    private static @Nullable List<Argument> nativeArgumentList(JsonElement value, boolean nativeRules) throws IOException {
        if (value.isJsonNull()) return null;
        List<Argument> result = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray()) {
            if (item.isJsonNull()) { result.add(null); continue; }
            JsonObject object = item.getAsJsonObject();
            if (object.has("string")) { result.add(new StringArgument(directString(object, "string"))); continue; }
            JsonElement rules = object.get("rules"), values = object.get("values");
            if (rules == null || values == null) throw new IOException("Invalid native ruled argument");
            result.add(new RuledArgument(nativeRules ? nativeRules(rules) : rules.isJsonNull() ? null : Arrays.asList(JsonUtils.GSON.fromJson(rules, CompatibilityRule[].class)),
                    values.isJsonNull() ? null : Arrays.asList(JsonUtils.GSON.fromJson(values, String[].class))));
        }
        return result;
    }

    /// Decodes libraries in order and retains Java's null-entry handling.
    /// @param fields native projection
    /// @return immutable library list or null
    /// @throws IOException if the projection is malformed
    private static @Nullable @Unmodifiable List<Library> libraries(JsonObject fields) throws IOException {
        @Nullable JsonElement value = raw(fields, "libraries");
        if (value == null || value.isJsonNull()) return null;
        JsonArray array = value.getAsJsonArray(); List<Library> result = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            @Nullable Library library = JsonUtils.GSON.fromJson(element, Library.class);
            if (library != null) result.add(library);
        }
        return List.copyOf(result);
    }

    /// Builds canonical library objects from native descriptors.
    private static @Nullable @Unmodifiable List<Library> nativeLibraries(JsonElement value, boolean withClassifiers, boolean withLegacyFields, boolean nativeRules) throws IOException {
        if (value == null || value.isJsonNull()) return null;
        List<Library> result = new ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            JsonObject object = element.getAsJsonObject();
            Artifact artifact = new Artifact(directString(object, "group"), directString(object, "name"),
                    directString(object, "version"), directNullableString(object, "classifier"), directNullableString(object, "extension"));
            JsonElement downloadValue = object.get("downloads");
            if (downloadValue == null) throw new IOException("Missing native library downloads");
            @Nullable LibrariesDownloadInfo downloads = null;
            if (!downloadValue.isJsonNull()) {
                JsonElement info = downloadValue.getAsJsonObject().get("artifact");
                if (info == null) throw new IOException("Missing native library artifact");
                @Nullable Map<String, LibraryDownloadInfo> classifiers = null;
                if (withClassifiers) {
                    JsonElement entries = downloadValue.getAsJsonObject().get("classifiers");
                    if (entries == null) throw new IOException("Missing native library classifiers");
                    if (!entries.isJsonNull()) {
                        classifiers = new LinkedHashMap<>();
                        for (JsonElement entry : entries.getAsJsonArray()) {
                            JsonObject classifier = entry.getAsJsonObject();
                            if (!classifier.has("info")) throw new IOException("Missing native library classifier");
                            classifiers.put(directString(classifier, "name"), libraryDownload(classifier.get("info")));
                        }
                    }
                }
                downloads = new LibrariesDownloadInfo(libraryDownload(info), classifiers);
            }
            JsonElement rulesValue = object.get("rules");
            if (rulesValue == null) throw new IOException("Missing native library rules");
            @Nullable List<CompatibilityRule> rules = nativeRules ? nativeRules(rulesValue) : rulesValue.isJsonNull() ? null
                    : Arrays.asList(JsonUtils.GSON.fromJson(rulesValue, CompatibilityRule[].class));
            @Nullable String url = null, hint = null, filename = null;
            @Nullable List<String> checksums = null;
            @Nullable ExtractRules extract = null;
            @Nullable Map<String, String> natives = null;
            if (withLegacyFields) {
                url = directNullableString(object, "url"); hint = directNullableString(object, "hint"); filename = directNullableString(object, "filename");
                JsonElement checksumsValue = object.get("checksums"), extractValue = object.get("extract"), nativesValue = object.get("natives");
                if (checksumsValue == null || extractValue == null || nativesValue == null) throw new IOException("Missing native library fields");
                if (!checksumsValue.isJsonNull()) checksums = Arrays.asList(JsonUtils.GSON.fromJson(checksumsValue, String[].class));
                if (!extractValue.isJsonNull()) extract = new ExtractRules(Arrays.asList(JsonUtils.GSON.fromJson(extractValue, String[].class)));
                if (!nativesValue.isJsonNull()) {
                    natives = new LinkedHashMap<>();
                    for (JsonElement entry : nativesValue.getAsJsonArray()) {
                        JsonObject nativeEntry = entry.getAsJsonObject();
                        natives.put(directString(nativeEntry, "name"), directNullableString(nativeEntry, "value"));
                    }
                }
            }
            result.add(new Library(artifact, url, downloads, checksums, extract, natives, rules, hint, filename));
        }
        return List.copyOf(result);
    }

    /// Builds libraries from the current native descriptor format.
    public static @Nullable @Unmodifiable List<Library> decodeNativeLibraries(JsonElement value) throws IOException {
        return nativeLibraries(value, true, true, true);
    }

    /// Builds compatibility rules projected by the native backend.
    private static @Nullable List<CompatibilityRule> nativeRules(JsonElement value) throws IOException {
        if (value.isJsonNull()) return null;
        List<CompatibilityRule> result = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray()) {
            if (item.isJsonNull()) { result.add(null); continue; }
            JsonObject rule = item.getAsJsonObject();
            @Nullable String actionName = directNullableString(rule, "action");
            @Nullable CompatibilityRule.Action action = actionName == null ? null : LowerCaseEnumTypeAdapter.fromJson(CompatibilityRule.Action.class, actionName);
            JsonElement osValue = rule.get("os"), featuresValue = rule.get("features");
            if (osValue == null || featuresValue == null || (actionName != null && action == null)) throw new IOException("Invalid native rule");
            @Nullable OSRestriction os = null;
            if (!osValue.isJsonNull()) {
                JsonObject obj = osValue.getAsJsonObject();
                os = new OSRestriction(directNullableString(obj, "name"), directNullableString(obj, "version"), directNullableString(obj, "arch"));
            }
            @Nullable Map<String, Boolean> features = null;
            if (!featuresValue.isJsonNull()) {
                features = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> entry : featuresValue.getAsJsonObject().entrySet()) {
                    JsonElement flag = entry.getValue();
                    if (!flag.isJsonNull() && (!flag.isJsonPrimitive() || !flag.getAsJsonPrimitive().isBoolean())) throw new IOException("Invalid native feature");
                    features.put(entry.getKey(), flag.isJsonNull() ? null : flag.getAsBoolean());
                }
            }
            result.add(new CompatibilityRule(action, os, features));
        }
        return result;
    }

    /// Builds one native-projected library download.
    private static @Nullable LibraryDownloadInfo libraryDownload(JsonElement value) throws IOException {
        if (value.isJsonNull()) return null;
        JsonObject descriptor = value.getAsJsonObject();
        return new LibraryDownloadInfo(directNullableString(descriptor, "path"), directString(descriptor, "url"),
                directNullableString(descriptor, "sha1"), directInt(descriptor, "size"));
    }

    /// Decodes a native download descriptor.
    /// @param value native descriptor
    /// @return download information or null
    /// @throws IOException if malformed
    private static @Nullable DownloadInfo download(JsonElement value) throws IOException {
        if (value == null || value.isJsonNull()) return null;
        JsonObject object = value.getAsJsonObject();
        String url = directString(object, "url");
        if (url.isBlank()) throw new IOException("Invalid native download URL");
        return new DownloadInfo(url, directNullableString(object, "sha1"), directInt(object, "size"));
    }

    private static @Nullable GameJavaVersion nativeJavaVersion(JsonElement value) throws IOException {
        if (value.isJsonNull()) return null;
        JsonObject object = value.getAsJsonObject();
        return new GameJavaVersion(directNullableString(object, "component"), directInt(object, "majorVersion"));
    }

    private static @Nullable @Unmodifiable Map<DownloadType, LoggingInfo> nativeLogging(JsonElement value) throws IOException {
        if (value.isJsonNull()) return null;
        LinkedHashMap<DownloadType, LoggingInfo> result = new LinkedHashMap<>();
        for (JsonElement entry : value.getAsJsonArray()) {
            JsonObject item = entry.getAsJsonObject();
            @Nullable DownloadType type = LowerCaseEnumTypeAdapter.fromJson(DownloadType.class, directString(item, "kind"));
            JsonElement info = item.get("info");
            if (type == null || info == null) throw new IOException("Invalid native logging entry");
            @Nullable LoggingInfo logging = null;
            if (!info.isJsonNull()) {
                JsonObject object = info.getAsJsonObject(), file = object.getAsJsonObject("file");
                if (file == null) throw new IOException("Missing native logging file");
                IdDownloadInfo download = new IdDownloadInfo(directString(file, "id"), directString(file, "url"),
                        directNullableString(file, "sha1"), directInt(file, "size"));
                logging = new LoggingInfo(download, directString(object, "argument"), directString(object, "type"));
            }
            result.put(type, logging);
        }
        return ImmutableSequencedMap.wrap(result);
    }

    /// Decodes a native asset index descriptor.
    /// @param value native descriptor
    /// @return asset index or null
    /// @throws IOException if malformed
    private static @Nullable AssetIndexInfo asset(JsonElement value) throws IOException {
        if (value == null || value.isJsonNull()) return null;
        JsonObject object = value.getAsJsonObject(); String id = directString(object, "id");
        String url = directString(object, "url");
        if (id.isBlank() || url.isBlank()) throw new IOException("Invalid native asset index");
        JsonElement total = object.get("totalSize");
        if (!total.isJsonPrimitive() || !total.getAsJsonPrimitive().isNumber()) throw new IOException("Invalid native asset size");
        return new AssetIndexInfo(id, url, directNullableString(object, "sha1"), directInt(object, "size"), total.getAsBigDecimal().longValueExact());
    }

    /// Decodes native download descriptors in original manifest order.
    /// @param value native descriptor list
    /// @return immutable map or null
    /// @throws IOException if malformed
    private static @Nullable @Unmodifiable Map<DownloadType, DownloadInfo> downloads(JsonElement value) throws IOException {
        if (value == null || value.isJsonNull()) return null;
        LinkedHashMap<DownloadType, DownloadInfo> result = new LinkedHashMap<>();
        for (JsonElement element : value.getAsJsonArray()) {
            JsonObject item = element.getAsJsonObject();
            @Nullable DownloadType type = LowerCaseEnumTypeAdapter.fromJson(DownloadType.class, directString(item, "kind"));
            if (type == null || !item.has("info")) throw new IOException("Invalid native download type");
            result.put(type, download(item.get("info")));
        }
        return ImmutableSequencedMap.wrap(result);
    }

    /// Reads a required native string.
    /// @param object native descriptor
    /// @param key field name
    /// @return string value
    /// @throws IOException if malformed
    private static String directString(JsonObject object, String key) throws IOException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Invalid native descriptor string");
        return value.getAsString();
    }

    /// Reads a nullable native string.
    /// @param object native descriptor
    /// @param key field name
    /// @return string or null
    /// @throws IOException if malformed
    private static @Nullable String directNullableString(JsonObject object, String key) throws IOException {
        JsonElement value = object.get(key);
        if (value == null) throw new IOException("Missing native descriptor string");
        return value.isJsonNull() ? null : directString(object, key);
    }

    /// Reads an exact native integer.
    /// @param object native descriptor
    /// @param key field name
    /// @return integer value
    /// @throws IOException if malformed
    private static int directInt(JsonObject object, String key) throws IOException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IOException("Invalid native descriptor integer");
        return value.getAsBigDecimal().intValueExact();
    }

    /// Decodes an ordered download-type map with existing nested adapters.
    /// @param fields native projection
    /// @param key field name
    /// @param type nested type
    /// @return immutable map or null
    /// @throws IOException if the projection is malformed
    private static <T> @Nullable @Unmodifiable Map<DownloadType, T> typedMap(JsonObject fields, String key, Class<T> type) throws IOException {
        @Nullable JsonElement value = raw(fields, key);
        if (value == null || value.isJsonNull()) return null;
        LinkedHashMap<DownloadType, T> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            try {
                @Nullable DownloadType name = LowerCaseEnumTypeAdapter.fromJson(DownloadType.class, entry.getKey());
                if (name != null) result.put(name, JsonUtils.GSON.fromJson(entry.getValue(), type));
            } catch (IllegalArgumentException ignored) { }
        }
        return ImmutableSequencedMap.wrap(result);
    }

    /// Decodes the release type using the existing enum adapter.
    /// @param fields native projection
    /// @return release type or null
    /// @throws IOException if the projection is malformed
    private static @Nullable ReleaseType releaseType(JsonObject fields) throws IOException {
        @Nullable String value = text(fields, "type");
        return value == null ? null : LowerCaseEnumTypeAdapter.fromJson(ReleaseType.class, value);
    }

    /// Decodes an optional time, preserving Java's invalid-time handling.
    /// @param fields native projection
    /// @param key field name
    /// @return instant or null
    /// @throws IOException if the projection is malformed
    private static @Nullable Instant instant(JsonObject fields, String key) throws IOException {
        @Nullable String value = text(fields, key);
        if (value == null) return null;
        try { return InstantTypeAdapter.deserializeToInstant(value); }
        catch (RuntimeException e) { return null; }
    }
}
