/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.addon;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.addon.repository.ModrinthRemoteAddonRepository;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jackhuang.hmcl.nativebridge.NativeCatalogRpc;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/// Read-only Rust content-store integration. Call on an IO worker, never on the FX thread.
/// Null results ask the repository to parse the SAME HTTP response using Java.
@NotNullByDefault
public final class NativeAddonCatalog {
    /// Maximum raw metadata size accepted before JSON framing adds escaping overhead.
    private static final int MAX_METADATA_CHARS = 768 * 1024;

    /// Prevents instantiation.
    private NativeAddonCatalog() { }

    /// Uses existing stable-file native SHA-1 inspection for local Modrinth identification.
    /// Small files stay in Java to avoid IPC overhead; unsupported files keep Java errors.
    public static String localFileSha1(Path file) throws IOException {
        String executable = System.getProperty("mfcl.native.path", "");
        if (!executable.isBlank() && file.getFileSystem() == FileSystems.getDefault()
                && Boolean.parseBoolean(System.getProperty("mfcl.native.catalog.enabled", "true"))
                && Files.size(file) >= 1024 * 1024) {
            try {
                @Nullable String hash = NativeBackend.hashCacheSha1(Path.of(executable), file);
                if (hash != null) return hash;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("Local file identification interrupted");
            } catch (IOException | RuntimeException ignored) {
                // Preserve the existing file reader when native inspection is unavailable.
            }
        }
        return DigestUtils.digestToString("SHA-1", file);
    }

    /// Plans CurseForge query values without passing API keys to the native worker.
    public static @Nullable @Unmodifiable Map<String, String> curseQuery(int section, int categoryId, String gameVersion,
            int page, int pageSize, String query, RemoteAddonRepository.SortType sort, RemoteAddonRepository.SortOrder order) {
        JsonObject params = new JsonObject();
        params.addProperty("section", section);
        params.addProperty("category_id", categoryId);
        params.addProperty("game_version", gameVersion);
        params.addProperty("page", page);
        params.addProperty("page_size", pageSize);
        params.addProperty("query", query);
        params.addProperty("sort", sort.name());
        params.addProperty("order", order.name());
        @Nullable JsonObject result = request("plan_curse_query", params);
        if (result == null) return null;
        try {
            Map<String, String> values = new LinkedHashMap<>();
            for (String key : List.of("gameId", "classId", "gameVersion", "searchFilter", "sortField", "sortOrder", "index", "pageSize")) {
                values.put(key, result.getAsJsonObject("query").get(key).getAsString());
            }
            if (categoryId != 0) values.put("categoryId", result.getAsJsonObject("query").get("categoryId").getAsString());
            return Map.copyOf(values);
        } catch (RuntimeException e) { return null; }
    }

    /// Computes a CurseForge fingerprint only when the file's Unix identity remains current.
    /// The unsigned 32-bit result is for lookup, not a cryptographic integrity check.
    public static @Nullable Long curseFingerprint(Path file) {
        if (file.getFileSystem() != FileSystems.getDefault()) return null;
        Path absolute = file.toAbsolutePath();
        String path = absolute.toString();
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(path) || !Path.of(path).equals(absolute)) return null;
        JsonObject params = new JsonObject();
        params.addProperty("path", path);
        @Nullable JsonObject result = request("curse_fingerprint", params);
        if (result == null || result.has("unknown")) return null;
        try {
            Map<String, Object> attributes = Files.readAttributes(file, "unix:*");
            if (!Boolean.TRUE.equals(attributes.get("isRegularFile"))) return null;
            for (String key : List.of("size", "dev", "ino")) {
                String field = switch (key) {
                    case "dev" -> "device";
                    case "ino" -> "inode";
                    default -> key;
                };
                if (!(attributes.get(key) instanceof Number number)
                        || number.longValue() != result.get(field).getAsBigDecimal().longValueExact()) return null;
            }
            if (!(attributes.get("lastModifiedTime") instanceof FileTime modified)
                    || !(attributes.get("ctime") instanceof FileTime changed)
                    || modified.to(TimeUnit.NANOSECONDS) != result.get("mtime_ns").getAsBigDecimal().longValueExact()
                    || changed.to(TimeUnit.NANOSECONDS) != result.get("ctime_ns").getAsBigDecimal().longValueExact()) return null;
            long hash = result.get("fingerprint").getAsBigDecimal().longValueExact();
            return hash >= 0 && hash <= 0xffff_ffffL ? hash : null;
        } catch (IOException | RuntimeException e) { return null; }
    }

    /// Executes a bounded, credential-free plan without changing HTTP/proxy/mirror policy.
    private static @Nullable JsonObject request(String method, JsonObject params) {
        if (!Boolean.parseBoolean(System.getProperty("mfcl.native.catalog.enabled", "true"))) return null;
        String executable = System.getProperty("mfcl.native.path", "");
        if (executable.isBlank() || Thread.currentThread().isInterrupted()) return null;
        try {
            return NativeCatalogRpc.request(Path.of(executable), method, params);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | RuntimeException e) {
            // Unsupported inputs, older backends and malformed responses retain Java behavior.
            return null;
        }
    }

    /// Plans Modrinth search query values; URL escaping and transport stay in the repository.
    public static @Nullable @Unmodifiable Map<String, String> modrinthQuery(String projectType, String gameVersion,
            @Nullable String category, int page, int pageSize, String query, RemoteAddonRepository.SortType sort) {
        JsonObject params = new JsonObject();
        params.addProperty("project_type", projectType);
        params.addProperty("game_version", StringUtils.isBlank(gameVersion) ? "" : gameVersion);
        params.addProperty("category", StringUtils.isBlank(category) ? "" : category);
        params.addProperty("page", page);
        params.addProperty("page_size", pageSize);
        params.addProperty("query", query);
        params.addProperty("sort", sort.name());
        @Nullable JsonObject result = request("plan_modrinth_query", params);
        if (result == null) return null;
        try {
            Map<String, String> values = new LinkedHashMap<>();
            for (String key : List.of("query", "facets", "offset", "limit", "index")) {
                values.put(key, result.getAsJsonObject("query").get(key).getAsString());
            }
            return Map.copyOf(values);
        } catch (RuntimeException e) { return null; }
    }

    /// Decodes version metadata, maps dependencies and selects each first file in Rust.
    /// Java reconstructs typed objects and dates; no network request is repeated on fallback.
    public static @Nullable @Unmodifiable List<RemoteAddon.Version> modrinthVersions(String metadata, boolean single) {
        if (metadata.length() > MAX_METADATA_CHARS) return null;
        JsonObject params = new JsonObject();
        params.addProperty("metadata", metadata);
        params.addProperty("single", single);
        @Nullable JsonObject result = request("project_modrinth_versions", params);
        if (result == null) return null;
        try {
            List<RemoteAddon.Version> versions = new ArrayList<>();
            for (var element : result.getAsJsonArray("versions")) {
                ProjectedVersion version = JsonUtils.fromNonNullJson(element.toString(), ProjectedVersion.class);
                versions.add(version.toVersion());
            }
            return List.copyOf(versions);
        } catch (RuntimeException e) { return null; }
    }

    /// Filters category metadata in Rust, retaining upstream extension data and ordering.
    public static @Nullable @Unmodifiable List<RemoteAddonRepository.Category> modrinthCategories(String metadata, String projectType) {
        if (metadata.length() > MAX_METADATA_CHARS) return null;
        JsonObject params = new JsonObject();
        params.addProperty("metadata", metadata);
        params.addProperty("project_type", projectType);
        @Nullable JsonObject result = request("select_modrinth_categories", params);
        if (result == null) return null;
        try {
            List<RemoteAddonRepository.Category> categories = new ArrayList<>();
            for (var element : result.getAsJsonArray("categories")) {
                var category = JsonUtils.fromNonNullJson(element.toString(), ModrinthRemoteAddonRepository.Category.class);
                Objects.requireNonNull(category.name());
                if (!projectType.equals(category.projectType())) return null;
                categories.add(category.toCategory());
            }
            return List.copyOf(categories);
        } catch (RuntimeException e) { return null; }
    }

    /// Filters and stably sorts either store's versions, retaining the original objects.
    /// Unicode comparison and unsupported backends retain the Java comparator exactly.
    public static @Unmodifiable List<RemoteAddon.Version> filterVersions(List<RemoteAddon.Version> versions,
            String gameVersion, @Nullable String loader) {
        String game = StringUtils.isBlank(gameVersion) ? "" : gameVersion;
        String selectedLoader = StringUtils.isBlank(loader) ? "" : Objects.requireNonNull(loader);
        JsonArray observations = new JsonArray();
        if (versions.size() <= 10_000 && !System.getProperty("mfcl.native.path", "").isBlank()
                && Boolean.parseBoolean(System.getProperty("mfcl.native.catalog.enabled", "true"))) {
            for (RemoteAddon.Version version : versions) {
                JsonObject observation = new JsonObject();
                observation.add("game_versions", JsonUtils.GSON.toJsonTree(version.gameVersions()));
                observation.add("loaders", JsonUtils.GSON.toJsonTree(version.loaders().stream().map(AddonLoader::name).toList()));
                @Nullable Instant date = version.datePublished();
                if (date != null) {
                    JsonObject timestamp = new JsonObject();
                    timestamp.addProperty("seconds", date.getEpochSecond());
                    timestamp.addProperty("nanos", date.getNano());
                    observation.add("date", timestamp);
                }
                observations.add(observation);
            }
            JsonObject params = new JsonObject();
            params.addProperty("game_version", game);
            params.addProperty("loader", selectedLoader);
            params.add("versions", observations);
            @Nullable JsonObject result = request("select_addon_versions", params);
            if (result != null) {
                try {
                    List<RemoteAddon.Version> selected = new ArrayList<>();
                    Set<Integer> seen = new HashSet<>();
                    for (var element : result.getAsJsonArray("indices")) {
                        int index = element.getAsBigDecimal().intValueExact();
                        if (index < 0 || index >= versions.size() || !seen.add(index)) throw new IllegalArgumentException();
                        selected.add(versions.get(index));
                    }
                    return List.copyOf(selected);
                } catch (RuntimeException ignored) {
                    // Invalid native selections are discarded as a whole.
                }
            }
        }
        return versions.stream()
                .filter(v -> game.isEmpty() || v.gameVersions().isEmpty() || v.gameVersions().stream().anyMatch(game::equalsIgnoreCase))
                .filter(v -> selectedLoader.isEmpty() || v.loaders().stream().map(AddonLoader::name).anyMatch(selectedLoader::equalsIgnoreCase))
                .sorted(Comparator.comparing(RemoteAddon.Version::datePublished, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
    }

    /// Wire-only dependency shape; a broken dependency has no project ID.
    @NotNullByDefault
    private record ProjectedDependency(RemoteAddon.DependencyType type, @Nullable String id) {
        /// Validates the wire enum and creates the existing Java dependency handle.
        RemoteAddon.Dependency toDependency() {
            Objects.requireNonNull(type);
            return type == RemoteAddon.DependencyType.BROKEN ? RemoteAddon.Dependency.ofBroken()
                    : RemoteAddon.Dependency.ofGeneral(type, RemoteAddon.Source.MODRINTH, Objects.requireNonNull(id));
        }
    }

    /// Wire-only version shape; loader classification and Instant parsing stay Java-compatible.
    @NotNullByDefault
    private record ProjectedVersion(String versionId, String projectId, String name, String version,
            @Nullable Instant datePublished, RemoteAddon.VersionType versionType, RemoteAddon.File file,
            List<ProjectedDependency> dependencies, List<String> gameVersions, List<String> loaders) {
        /// Validates mandatory fields before publishing an immutable version to the repository.
        RemoteAddon.Version toVersion() {
            RemoteAddon.File immutableFile = new RemoteAddon.File(Map.copyOf(file.hashes()),
                    Objects.requireNonNull(file.url()), Objects.requireNonNull(file.filename()));
            return new RemoteAddon.Version(RemoteAddon.Source.MODRINTH, Objects.requireNonNull(versionId),
                    Objects.requireNonNull(projectId), Objects.requireNonNull(name), Objects.requireNonNull(version),
                    datePublished, Objects.requireNonNull(versionType), immutableFile,
                    dependencies.stream().map(ProjectedDependency::toDependency).toList(),
                    List.copyOf(gameVersions), loaders.stream().map(AddonLoader::of).toList());
        }
    }
}
