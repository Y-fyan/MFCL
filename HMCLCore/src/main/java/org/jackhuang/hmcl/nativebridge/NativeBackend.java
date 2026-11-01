/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.nativebridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.jackhuang.hmcl.game.AssetObject;
import org.jackhuang.hmcl.game.Artifact;
import org.jackhuang.hmcl.game.Argument;
import org.jackhuang.hmcl.game.StringArgument;
import org.jackhuang.hmcl.game.RuledArgument;
import org.jackhuang.hmcl.game.CompatibilityRule;
import org.jackhuang.hmcl.game.DefaultGameRepositoryLayout;
import org.jackhuang.hmcl.game.GameInstanceID;
import org.jackhuang.hmcl.game.GameInstanceManifest;
import org.jackhuang.hmcl.game.GameRepositoryLayout;
import org.jackhuang.hmcl.game.Library;
import org.jackhuang.hmcl.game.LibraryDownloadInfo;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.LinkOption;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/// Calls native backend operations through isolated or exclusively borrowed child processes.
///
/// Offline account creation can use a native UUID only after comparing it with Java.
@NotNullByDefault
public final class NativeBackend {
    /// Maximum accepted response size in bytes.
    private static final int RESPONSE_LIMIT = 8 * 1024 * 1024;
    /// Maximum request size in bytes for currently supported small protocol messages.
    private static final int REQUEST_LIMIT = 1024 * 1024;
    /// Maximum time to wait for the backend process.
    private static final long TIMEOUT_SECONDS = 5;

    /// Prevents instantiation.
    private NativeBackend() {
    }

    /// Merges normalized manifest field tokens in the native backend.
    /// @param executable native backend executable
    /// @param params encoded parent and child field tokens
    /// @return merged token object
    /// @throws IOException on native or protocol failure
    /// @throws InterruptedException if interrupted
    public static JsonObject mergeManifestTokens(Path executable, JsonObject params) throws IOException, InterruptedException {
        return request(executable, "merge_manifest", params, 30);
    }

    /// Plans generated JVM defaults using a one-shot native process.
    /// Arguments may contain secrets and are passed only over the process input stream.
    /// @param executable native backend executable
    /// @param params JVM arguments and environment flags
    /// @return planned argument object
    /// @throws IOException on protocol or planning failure
    /// @throws InterruptedException when interrupted
    public static JsonObject planJvmDefaults(Path executable, JsonObject params) throws IOException, InterruptedException {
        return request(executable, "plan_jvm_defaults", params, 30);
    }

    /// Plans initial memory and user-provided JVM defaults using a one-shot process.
    /// @param executable native backend executable
    /// @param params JVM settings and arguments, which may contain secrets
    /// @return planned argument object
    /// @throws IOException on protocol or planning failure
    /// @throws InterruptedException when interrupted
    public static JsonObject planJvmInitial(Path executable, JsonObject params) throws IOException, InterruptedException {
        return request(executable, "plan_jvm_initial", params, 30);
    }

    /// Plans platform, game proxy and optimization defaults in an isolated process.
    /// @param executable native backend executable
    /// @param params launch settings, possibly including credentials
    /// @return planned argument object
    /// @throws IOException on protocol or planning failure
    /// @throws InterruptedException when interrupted
    public static JsonObject planJvmPlatform(Path executable, JsonObject params) throws IOException, InterruptedException {
        return request(executable, "plan_jvm_platform", params, 30);
    }

    /// Plans legacy BootstrapLauncher ignore lists using a verified reusable worker when available.
    /// @param executable native backend executable
    /// @param params library paths and ignore lists
    /// @return planned lists
    /// @throws IOException on protocol or planning failure
    /// @throws InterruptedException when interrupted
    public static JsonObject planBootstrapIgnores(Path executable, JsonObject params) throws IOException, InterruptedException {
        try {
            @Nullable JsonObject result = NativePureRpc.tryBootstrapIgnore(executable, params, () -> Thread.currentThread().isInterrupted());
            if (result != null) return result;
        } catch (IOException ignored) {
            // This read-only operation can safely retry with a fresh process.
        }
        return request(executable, "plan_bootstrap_ignore", params, 30, () -> Thread.currentThread().isInterrupted());
    }

    /// Classifies game termination in an isolated process; input may contain private logs.
    public static JsonObject classifyGameExit(Path executable, JsonObject params) throws IOException, InterruptedException {
        return request(executable, "classify_game_exit", params, 30, () -> Thread.currentThread().isInterrupted());
    }

    /// Projects authlib-injector server metadata without logging its contents.
    public static JsonObject planAuthlibMetadata(Path executable, String metadata) throws IOException, InterruptedException {
        JsonObject params = new JsonObject(); params.addProperty("metadata", metadata);
        return request(executable, "plan_authlib_metadata", params, 30, () -> Thread.currentThread().isInterrupted());
    }

    /// Assembles launch sections in an isolated process without logging arguments.
    /// @param executable native backend executable
    /// @param params expanded arguments, agents and main class
    /// @return ordered sections and quoting flags
    /// @throws IOException on protocol failure
    /// @throws InterruptedException if interrupted
    public static JsonObject assembleLaunchSections(Path executable, JsonObject params) throws IOException, InterruptedException {
        return request(executable, "assemble_launch_sections", params, 30);
    }

    /// Projects supported base-game manifest fields in an isolated native process.
    /// @param executable native backend executable
    /// @param metadata original game manifest JSON
    /// @return raw fields in a native projection
    /// @throws IOException on protocol or parsing failure
    /// @throws InterruptedException if interrupted
    public static JsonObject projectGameManifest(Path executable, String metadata) throws IOException, InterruptedException {
        JsonObject params = new JsonObject(); params.addProperty("metadata", metadata);
        return request(executable, "project_game_manifest", params, 30);
    }

    /// Projects a local instance manifest using a reusable worker when supported.
    public static JsonObject projectInstanceManifest(Path executable, String metadata) throws IOException, InterruptedException {
        JsonObject params = new JsonObject(); params.addProperty("metadata", metadata);
        @Nullable JsonObject result = NativePureRpc.tryInstanceManifest(executable, params, () -> Thread.currentThread().isInterrupted());
        return result != null ? result : request(executable, "project_instance_manifest", params, 30);
    }

    /// Parses a canonical game-version catalog in an isolated native process.
    /// @param executable native backend executable
    /// @param metadata original catalog JSON
    /// @return ordered version records
    /// @throws IOException on protocol or parsing failure
    /// @throws InterruptedException if interrupted
    public static JsonObject parseGameVersions(Path executable, String metadata) throws IOException, InterruptedException {
        JsonObject params = new JsonObject(); params.addProperty("metadata", metadata);
        return request(executable, "parse_game_versions", params, 30);
    }

    /// Projects the bundled LiteLoader catalog in the native backend.
    public static JsonObject parseLiteLoaderCatalog(Path executable, String metadata) throws IOException, InterruptedException {
        JsonObject params = new JsonObject(); params.addProperty("metadata", metadata);
        return request(executable, "parse_liteloader_catalog", params, 30);
    }

    /// Plans a LiteLoader patch and its ordered libraries.
    public static JsonObject planLiteLoaderPatch(Path executable, JsonObject params, BooleanSupplier cancelled) throws IOException, InterruptedException {
        return request(executable, "plan_liteloader_patch", params, 30, cancelled);
    }

    /// Selects a compatible Java runtime from an official runtime list in Rust.
    /// @param executable native backend executable
    /// @param params runtime list and selection criteria
    /// @param cancelled owning task cancellation predicate
    /// @return selection response
    /// @throws IOException on protocol or parsing failure
    /// @throws InterruptedException when interrupted
    public static JsonObject selectJavaRuntime(Path executable, JsonObject params, BooleanSupplier cancelled) throws IOException, InterruptedException {
        @Nullable JsonObject pooled = NativePureRpc.tryRuntimeMetadata(executable, "select_java_runtime", params, cancelled);
        return pooled != null ? pooled : request(executable, "select_java_runtime", params, 30, cancelled);
    }

    /// Parses a canonical Mojang Java runtime manifest in Rust.
    /// @param executable native backend executable
    /// @param params original runtime manifest
    /// @param cancelled owning task cancellation predicate
    /// @return ordered runtime entries
    /// @throws IOException on protocol or parsing failure
    /// @throws InterruptedException when interrupted
    public static JsonObject parseJavaRuntimeManifest(Path executable, JsonObject params, BooleanSupplier cancelled) throws IOException, InterruptedException {
        @Nullable JsonObject pooled = NativePureRpc.tryRuntimeMetadata(executable, "parse_java_runtime_manifest", params, cancelled);
        return pooled != null ? pooled : request(executable, "parse_java_runtime_manifest", params, 30, cancelled);
    }

    /// Creates validated runtime directories and links in Rust.
    /// @param executable native backend executable
    /// @param params runtime root and ordered entries
    /// @param cancelled owning task cancellation predicate
    /// @throws IOException if preparation or its response fails
    /// @throws InterruptedException if interrupted
    public static void prepareJavaRuntimeTree(Path executable, JsonObject params, BooleanSupplier cancelled) throws IOException, InterruptedException {
        JsonObject result = request(executable, "prepare_java_runtime_tree", params, 180, cancelled);
        try {
            if (!result.get("prepared").isJsonPrimitive() || !result.get("prepared").getAsJsonPrimitive().isBoolean()
                    || !result.get("prepared").getAsBoolean()) throw new IOException("Invalid native runtime tree result");
        } catch (RuntimeException e) { throw new IOException("Invalid native runtime tree response", e); }
    }

    /// Validates relative Java runtime paths and declared link relationships in Rust.
    /// @param executable native backend executable
    /// @param params runtime entry paths and optional link targets
    /// @param cancelled owning task cancellation predicate
    /// @throws IOException on unsafe paths or protocol failure
    /// @throws InterruptedException when interrupted
    public static void validateRuntimePaths(Path executable, JsonObject params, BooleanSupplier cancelled) throws IOException, InterruptedException {
        JsonObject result = request(executable, "validate_runtime_paths", params, 30, cancelled);
        try {
            if (!result.get("validated").isJsonPrimitive() || !result.get("validated").getAsJsonPrimitive().isBoolean()
                    || !result.get("validated").getAsBoolean()) throw new IOException("Invalid native runtime path result");
        } catch (RuntimeException e) { throw new IOException("Invalid native runtime path response", e); }
    }

    /// Plans a Fabric-family loader patch from its untrusted metadata document.
    /// @param executable native backend executable
    /// @param kind loader kind
    /// @param metadata original metadata JSON
    /// @param cancelled cancellation predicate
    /// @return native loader plan
    /// @throws IOException if parsing or protocol validation fails
    /// @throws InterruptedException if cancelled
    public static JsonObject planLoader(Path executable, String kind, String metadata, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        JsonObject params = new JsonObject(); params.addProperty("kind", kind); params.addProperty("metadata", metadata);
        return request(executable, "plan_loader", params, 30, cancelled);
    }

    /// Expands a batch of Forge-family processor literals in Rust.
    /// @param executable native backend executable
    /// @param params literals, variables and caller-resolved artifact paths
    /// @param cancelled cancellation predicate
    /// @return native expansion response
    /// @throws IOException on protocol or expansion failure
    /// @throws InterruptedException if cancelled
    public static JsonObject expandProcessorLiterals(Path executable, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        @Nullable JsonObject pooled = NativePureRpc.tryProcessorLiterals(executable, params, cancelled);
        if (pooled != null) return pooled;
        return request(executable, "expand_processor_literals", params, 30, cancelled);
    }

    /// Resolves canonical artifact coordinates and processor literals in Rust.
    public static JsonObject expandProcessorLiteralsV2(Path executable, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        @Nullable JsonObject pooled = NativePureRpc.tryProcessorLiteralsV2(executable, params, cancelled);
        if (pooled != null) return pooled;
        return request(executable, "expand_processor_literals_v2", params, 30, cancelled);
    }

    /// Plans installer processor options through Rust.
    /// @param executable native backend executable
    /// @param params processor groups and resolved values
    /// @param cancelled cancellation predicate
    /// @return ordered option pairs for each processor
    /// @throws IOException on communication or planning failure
    /// @throws InterruptedException if cancelled
    public static JsonObject planProcessorOptions(Path executable, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        @Nullable JsonObject pooled = NativePureRpc.tryProcessorOptions(executable, params, cancelled);
        if (pooled != null) return pooled;
        return request(executable, "plan_processor_options", params, 30, cancelled);
    }

    /// Plans processor options and resolves canonical artifact coordinates in Rust.
    public static JsonObject planProcessorOptionsV2(Path executable, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        @Nullable JsonObject pooled = NativePureRpc.tryProcessorOptionsV2(executable, params, cancelled);
        if (pooled != null) return pooled;
        return request(executable, "plan_processor_options_v2", params, 30, cancelled);
    }

    /// Prepares a processor main class or classpath through the native backend.
    /// @param executable backend executable
    /// @param method supported processor command operation
    /// @param params operation parameters
    /// @param cancelled owning task cancellation predicate
    /// @return native result
    /// @throws IOException on communication or planning failure
    /// @throws InterruptedException on cancellation
    public static JsonObject prepareProcessorCommand(Path executable, String method, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        @Nullable JsonObject pooled;
        try {
            pooled = NativePureRpc.tryProcessorCommand(executable, method, params, cancelled);
        } catch (OperationException e) {
            throw e;
        } catch (IOException e) {
            if (cancelled.getAsBoolean()) throw new InterruptedException("Native operation cancelled");
            // Preparation is read-only; a failed pooled worker can be retried once in a fresh process.
            pooled = null;
        }
        if (pooled != null) return pooled;
        return request(executable, method, params, 30, cancelled);
    }

    /// Applies priority-ordered manifest patches to normalized field tokens in Rust.
    /// @param executable native backend executable
    /// @param params encoded base and patch values
    /// @return patched token object
    /// @throws IOException on native or protocol failure
    /// @throws InterruptedException if interrupted
    public static JsonObject applyManifestPatchTokens(Path executable, JsonObject params) throws IOException, InterruptedException {
        return request(executable, "apply_manifest_patches", params, 30);
    }

    /// Applies manifest patches in their original order without priority sorting.
    /// @param executable native backend executable
    /// @param params encoded base and patch values
    /// @return patched token object
    /// @throws IOException on native or protocol failure
    /// @throws InterruptedException if interrupted
    public static JsonObject applyOrderedManifestPatchTokens(Path executable, JsonObject params) throws IOException, InterruptedException {
        return request(executable, "apply_manifest_patches_ordered", params, 30);
    }

    /// Ordered instance IDs followed by whether the terminal item closes a cycle.
    /// @param chain inheritance chain, including the starting ID
    /// @param cycle whether the terminal item is a repeated cyclic node
    public record InheritancePlan(@Unmodifiable List<GameInstanceID> chain, boolean cycle) {
        /// Creates an immutable plan.
        public InheritancePlan { chain = List.copyOf(chain); }
    }

    /// Plans a manifest's inheritance chain against a snapshot of instance manifests.
    /// @param executable native backend executable
    /// @param start manifest being resolved
    /// @param manifests snapshot manifests
    /// @return ordered chain and cycle flag
    /// @throws IOException on invalid input, missing parent or native failure
    /// @throws InterruptedException if interrupted
    public static InheritancePlan planInheritance(Path executable, GameInstanceManifest start, List<GameInstanceManifest> manifests)
            throws IOException, InterruptedException {
        JsonArray entries = new JsonArray();
        for (GameInstanceManifest manifest : manifests) entries.add(inheritanceEntry(manifest));
        JsonObject params = new JsonObject(); params.add("start", inheritanceEntry(start)); params.add("instances", entries);
        JsonObject result = request(executable, "plan_inheritance", params, 30);
        try {
            JsonArray raw = result.getAsJsonArray("chain");
            if (raw.isEmpty() || raw.size() > manifests.size() + 2) throw new IOException("Invalid native inheritance chain length");
            List<GameInstanceID> chain = new ArrayList<>(raw.size());
            for (JsonElement value : raw) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Invalid native inheritance ID");
                chain.add(new GameInstanceID(value.getAsString()));
            }
            JsonElement cycle = result.get("cycle");
            if (cycle == null || !cycle.isJsonPrimitive() || !cycle.getAsJsonPrimitive().isBoolean()) throw new IOException("Invalid native inheritance cycle flag");
            return new InheritancePlan(chain, cycle.getAsBoolean());
        } catch (RuntimeException e) { throw new IOException("Invalid native inheritance response", e); }
    }

    /// Encodes the normalized identifier and optional parent used for inheritance planning.
    /// @param manifest snapshot manifest
    /// @return protocol object
    /// @throws IOException if the manifest has no identifier
    private static JsonObject inheritanceEntry(GameInstanceManifest manifest) throws IOException {
        if (manifest.id() == null) throw new IOException("Invalid instance ID in inheritance input");
        JsonObject entry = new JsonObject(); entry.addProperty("id", manifest.id().id());
        @Nullable GameInstanceID parent = manifest.inheritsFrom();
        entry.addProperty("parent", parent == null ? null : parent.id());
        return entry;
    }

    /// Determines which already-parsed instances have a missing inheritance ancestor.
    /// @param executable native backend executable
    /// @param manifests current repository manifests
    /// @return instance IDs to remove
    /// @throws IOException on backend or response validation failure
    /// @throws InterruptedException if the caller is interrupted
    public static @Unmodifiable Set<GameInstanceID> findInvalidInheritance(Path executable, List<GameInstanceManifest> manifests)
            throws IOException, InterruptedException {
        JsonArray entries = new JsonArray();
        java.util.Set<GameInstanceID> known = new java.util.HashSet<>();
        for (GameInstanceManifest manifest : manifests) {
            GameInstanceID id = manifest.id();
            if (id == null || !known.add(id)) throw new IOException("Invalid instance ID in inheritance input");
            JsonObject entry = new JsonObject(); entry.addProperty("id", id.id());
            @Nullable GameInstanceID parent = manifest.inheritsFrom();
            entry.addProperty("parent", parent == null ? null : parent.id()); entries.add(entry);
        }
        JsonObject params = new JsonObject(); params.add("instances", entries);
        JsonObject result = request(executable, "find_invalid_inheritance", params, 30);
        try {
            JsonArray invalid = result.getAsJsonArray("invalid");
            java.util.Set<GameInstanceID> removed = new java.util.HashSet<>();
            for (JsonElement value : invalid) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Invalid native inheritance ID");
                GameInstanceID id = new GameInstanceID(value.getAsString());
                if (!known.contains(id) || !removed.add(id)) throw new IOException("Unknown or duplicate native inheritance ID");
            }
            return java.util.Set.copyOf(removed);
        } catch (RuntimeException e) {
            throw new IOException("Invalid native inheritance response", e);
        }
    }

    /// Lists candidate instance directories without parsing or publishing manifests.
    /// @param executable native backend executable
    /// @param versionsDirectory existing directory containing instances
    /// @return validated child paths in filesystem enumeration order
    /// @throws IOException if enumeration or response validation fails
    /// @throws InterruptedException if the calling thread is interrupted
    public static @org.jetbrains.annotations.Unmodifiable List<Path> listInstanceDirectories(Path executable, Path versionsDirectory)
            throws IOException, InterruptedException {
        JsonObject params = new JsonObject();
        Path absolute = versionsDirectory.toAbsolutePath();
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(absolute.toString())
                || !Path.of(absolute.toString()).equals(absolute)) throw new IOException("Unsupported native directory encoding");
        params.addProperty("versions_directory", absolute.toString());
        JsonObject result = request(executable, "list_instance_dirs", params, 30);
        try {
            JsonArray array = result.getAsJsonArray("directories");
            if (array.size() > 20_000) throw new IOException("Too many native instance directories");
            List<Path> paths = new ArrayList<>(array.size());
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (JsonElement value : array) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Invalid native directory name");
                String name = value.getAsString();
                Path relative = Path.of(name);
                if (name.isEmpty() || name.equals(".") || name.equals("..") || relative.isAbsolute()
                        || relative.getNameCount() != 1 || !relative.toString().equals(name) || !seen.add(name)) {
                    throw new IOException("Invalid native directory name");
                }
                Path child = versionsDirectory.resolve(relative);
                if (!absolute.normalize().equals(child.toAbsolutePath().normalize().getParent())) throw new IOException("Invalid native directory path");
                paths.add(child);
            }
            return List.copyOf(paths);
        } catch (RuntimeException e) {
            throw new IOException("Invalid native directory response", e);
        }
    }

    /// Validates the identity and protocol of a configured native backend.
    ///
    /// @param executable explicit path to the backend executable
    /// @throws IOException if starting the process or validating the response fails
    /// @throws InterruptedException if the calling thread is interrupted
    public static void checkHealth(Path executable) throws IOException, InterruptedException {
        JsonObject result = request(executable, "health", new JsonObject(), TIMEOUT_SECONDS);
        try {
            if (!"mfcl-native".equals(result.get("name").getAsString()) || result.get("protocol").getAsInt() != 1) {
                throw new IOException("Incompatible native backend health response");
            }
        } catch (RuntimeException e) {
            throw new IOException("Invalid native backend health response", e);
        }
    }

    /// Checks whether a native backend advertises Unix process replacement.
    ///
    /// @param executable explicit path to the backend executable
    /// @return whether process replacement is supported
    /// @throws IOException if the health response is malformed
    /// @throws InterruptedException if interrupted
    public static boolean supportsLaunchExec(Path executable) throws IOException, InterruptedException {
        return launchExecProtocol(executable) > 0;
    }

    /// Returns the supported native launch framing version, or zero when unavailable.
    public static int launchExecProtocol(Path executable) throws IOException, InterruptedException {
        JsonObject result = request(executable, "health", new JsonObject(), TIMEOUT_SECONDS);
        try {
            if (!"mfcl-native".equals(result.get("name").getAsString()) || result.get("protocol").getAsInt() != 1) {
                throw new IOException("Incompatible native backend health response");
            }
            boolean legacy = false, lengthPrefixed = false;
            for (var capability : result.getAsJsonArray("capabilities")) {
                if ("launch_exec".equals(capability.getAsString())) legacy = true;
                if ("launch_exec_length_v1".equals(capability.getAsString())) lengthPrefixed = true;
            }
            return lengthPrefixed ? 2 : legacy ? 1 : 0;
        } catch (RuntimeException e) {
            throw new IOException("Invalid native backend health response", e);
        }
    }

    /// Checks whether the backend advertises every requested capability.
    ///
    /// @param executable native backend executable
    /// @param required capability names
    /// @return whether all capabilities are advertised
    /// @throws IOException if the health response is invalid
    /// @throws InterruptedException if interrupted
    public static boolean supportsCapabilities(Path executable, String... required) throws IOException, InterruptedException {
        JsonObject result = request(executable, "health", new JsonObject(), TIMEOUT_SECONDS);
        try {
            if (!"mfcl-native".equals(result.get("name").getAsString()) || result.get("protocol").getAsInt() != 1) {
                throw new IOException("Incompatible native backend health response");
            }
            java.util.Set<String> capabilities = new java.util.HashSet<>();
            for (var capability : result.getAsJsonArray("capabilities")) capabilities.add(capability.getAsString());
            return capabilities.containsAll(java.util.Arrays.asList(required));
        } catch (RuntimeException e) {
            throw new IOException("Invalid native backend health response", e);
        }
    }

    /// Calculates an offline profile UUID without creating or modifying an account.
    ///
    /// The caller is responsible for comparing this result with the Java implementation.
    ///
    /// @param executable explicit path to the backend executable
    /// @param username offline player name
    /// @return the UUID produced by the native backend
    /// @throws IOException if the request fails or returns an invalid response
    /// @throws InterruptedException if the calling thread is interrupted
    public static UUID calculateOfflineUuid(Path executable, String username) throws IOException, InterruptedException {
        JsonObject params = new JsonObject();
        params.addProperty("username", username);
        JsonObject result = request(executable, "offline_profile", params, TIMEOUT_SECONDS);
        try {
            if (!username.equals(result.get("username").getAsString())) {
                throw new IOException("Native backend returned a different username");
            }
            return UUID.fromString(result.get("uuid").getAsString());
        } catch (RuntimeException e) {
            throw new IOException("Invalid native offline profile response", e);
        }
    }

    /// Downloads one SHA-1-verified file through the experimental native process.
    ///
    /// The destination must not exist, and its parent directory must already exist. The Rust
    /// implementation currently supports HTTPS, has a 1 GiB limit, and does not provide progress
    /// or resumption. This method is not connected to the launcher download task system.
    ///
    /// @param executable explicit path to the backend executable
    /// @param url HTTPS download URL
    /// @param destination output file which must not already exist
    /// @param sha1 expected SHA-1 digest
    /// @param maxBytes maximum accepted file size in bytes
    /// @return number of downloaded bytes
    /// @throws IOException if the request fails or returns an invalid response
    /// @throws InterruptedException if the calling thread is interrupted
    public static long download(Path executable, String url, Path destination, String sha1, long maxBytes)
            throws IOException, InterruptedException {
        return download(executable, url, destination, sha1, maxBytes, false);
    }

    /// Issues a single download request, optionally allowing loopback HTTP for isolated tests.
    ///
    /// @param executable backend executable
    /// @param url source URL
    /// @param destination output path
    /// @param sha1 expected digest
    /// @param maxBytes maximum accepted size
    /// @param allowLoopbackHttp whether numeric loopback HTTP is permitted
    /// @return number of downloaded bytes
    /// @throws IOException if the operation fails
    /// @throws InterruptedException if interrupted
    static long download(Path executable, String url, Path destination, String sha1, long maxBytes,
                         boolean allowLoopbackHttp) throws IOException, InterruptedException {
        JsonObject params = new JsonObject();
        params.addProperty("url", url);
        params.addProperty("destination", destination.toString());
        params.addProperty("sha1", sha1);
        params.addProperty("max_bytes", maxBytes);
        params.addProperty("allow_http_loopback", allowLoopbackHttp);
        JsonObject result = request(executable, "download", params, 75);
        try {
            long size = result.get("bytes_written").getAsLong();
            if (size < 0 || size > maxBytes || !sha1.equalsIgnoreCase(result.get("sha1").getAsString())) {
                throw new IOException("Invalid native download result");
            }
            return size;
        } catch (RuntimeException e) {
            throw new IOException("Invalid native download response", e);
        }
    }

    /// Extracts a ZIP archive into a new native-owned staging directory.
    ///
    /// The caller owns the returned directory and must remove it when it is no longer needed.
    ///
    /// @param executable explicit path to the backend executable
    /// @param archive archive to extract
    /// @param stagingParent existing directory in which to create the staging directory
    /// @return the newly created staging directory
    /// @throws IOException if extraction or response validation fails
    /// @throws InterruptedException if the calling thread is interrupted
    public static Path extractZip(Path executable, Path archive, Path stagingParent) throws IOException, InterruptedException {
        return extractZip(executable, archive, stagingParent, null);
    }

    /// Extracts a ZIP or a single subtree into a new native-owned staging directory.
    /// @param executable native backend executable
    /// @param archive archive to extract
    /// @param stagingParent existing staging parent
    /// @param prefix relative directory prefix ending in slash, or null for the whole archive
    /// @return caller-owned staging directory
    /// @throws IOException on extraction or response validation failure
    /// @throws InterruptedException on interruption
    public static Path extractZip(Path executable, Path archive, Path stagingParent, @Nullable String prefix) throws IOException, InterruptedException {
        Path parent = stagingParent.toAbsolutePath().normalize();
        JsonObject params = new JsonObject();
        params.addProperty("archive", archive.toAbsolutePath().normalize().toString());
        params.addProperty("staging_parent", parent.toString());
        if (prefix != null) {
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(prefix)) throw new IOException("Unsupported archive prefix");
            params.addProperty("prefix", prefix);
        }
        JsonObject result = request(executable, prefix == null ? "extract_zip" : "extract_zip_prefix", params, 75);
        try {
            Path stage = Path.of(result.get("staging_directory").getAsString()).toAbsolutePath().normalize();
            if (!parent.equals(stage.getParent()) || !stage.getFileName().toString().startsWith(".mfcl-extract-")
                    || !Files.isDirectory(stage, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Invalid native extraction staging directory");
            }
            return stage;
        } catch (RuntimeException e) {
            throw new IOException("Invalid native extraction response", e);
        }
    }

    /// Computes ordered SHA-1 digests of uniquely named ZIP entries without extracting files.
    /// @param executable native backend executable
    /// @param archive source ZIP archive
    /// @param entries exact ZIP entry names in caller order
    /// @param cancelled owning task cancellation predicate
    /// @return immutable digest list in the same order
    /// @throws IOException on unsupported entries or protocol failure
    /// @throws InterruptedException on cancellation
    public static @Unmodifiable List<String> hashZipEntries(Path executable, Path archive, List<String> entries,
            BooleanSupplier cancelled) throws IOException, InterruptedException {
        if (entries.size() > 20_000) throw new IOException("Too many ZIP entries");
        String archivePath = archive.toAbsolutePath().toString();
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(archivePath)) throw new IOException("Unsupported archive path");
        JsonArray names = new JsonArray();
        for (String entry : entries) {
            if (entry.isEmpty() || !StandardCharsets.UTF_8.newEncoder().canEncode(entry)) throw new IOException("Unsupported ZIP entry name");
            names.add(entry);
        }
        JsonObject params = new JsonObject(); params.addProperty("archive", archivePath); params.add("entries", names);
        JsonObject result = request(executable, "hash_zip_entries", params, 180, cancelled);
        try {
            JsonArray values = result.getAsJsonArray("sha1");
            if (values.size() != entries.size()) throw new IOException("Invalid ZIP hash result count");
            List<String> hashes = new ArrayList<>(values.size());
            for (JsonElement value : values) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || !DigestUtils.isSha1Digest(value.getAsString()))
                    throw new IOException("Invalid ZIP hash result");
                hashes.add(value.getAsString());
            }
            return List.copyOf(hashes);
        } catch (RuntimeException e) { throw new IOException("Invalid ZIP hash response", e); }
    }

    /// Calls one Microsoft authentication operation without logging its secret parameters.
    ///
    /// Tokens in the request and result are sensitive; callers must not log either object.
    ///
    /// @param executable explicit path to the backend executable
    /// @param method one of the supported Microsoft operation names
    /// @param params operation parameters, potentially including credentials
    /// @return the result object, potentially including credentials
    /// @throws IOException if the process or operation fails
    /// @throws InterruptedException if the calling thread is interrupted
    public static JsonObject microsoft(Path executable, String method, JsonObject params) throws IOException, InterruptedException {
        if (!java.util.Set.of("microsoft_authorization_url", "microsoft_authorization_token", "microsoft_device_begin",
                "microsoft_device_poll", "microsoft_refresh", "microsoft_exchange", "microsoft_authorization_session",
                "microsoft_refresh_session", "microsoft_upload_skin", "microsoft_profile_read").contains(method)) {
            throw new IOException("Unsupported native authentication operation");
        }
        long timeout = "microsoft_authorization_session".equals(method) || "microsoft_refresh_session".equals(method) ? 300 : 180;
        return request(executable, method, params, timeout);
    }

    /// Decodes persisted Microsoft account fields in an isolated process; both arguments and result contain secrets.
    public static JsonObject microsoftStorage(Path executable, JsonObject params) throws IOException, InterruptedException {
        return request(executable, "microsoft_storage", params, 5);
    }

    /// Sends one Yggdrasil request in an isolated child without placing credentials on its command line.
    /// @param executable backend executable
    /// @param params encoded endpoint, headers and request body
    /// @return sanitized response envelope containing a bounded encoded body
    /// @throws IOException on communication failure
    /// @throws InterruptedException on interruption
    public static JsonObject yggdrasilRequest(Path executable, JsonObject params) throws IOException, InterruptedException {
        return request(executable, "yggdrasil_request", params, 40);
    }

    /// Parses a Yggdrasil response in an isolated child without logging credentials.
    /// @param executable backend executable
    /// @param params response text and expected client token
    /// @return native session plan
    /// @throws IOException on communication or parsing failure
    /// @throws InterruptedException on interruption
    public static JsonObject planYggdrasilResponse(Path executable, JsonObject params) throws IOException, InterruptedException {
        return request(executable, "yggdrasil_plan_response", params, 30);
    }

    /// Parses a complete Yggdrasil profile in an isolated child without logging its contents.
    /// @param executable backend executable
    /// @param params profile response text
    /// @return native profile plan
    /// @throws IOException on communication or parsing failure
    /// @throws InterruptedException on interruption
    public static JsonObject planYggdrasilProfile(Path executable, JsonObject params) throws IOException, InterruptedException {
        return request(executable, "yggdrasil_plan_profile", params, 30);
    }

    /// Decodes a Yggdrasil texture property in an isolated child without logging its contents.
    /// @param executable backend executable
    /// @param params encoded texture property
    /// @return native texture plan
    /// @throws IOException on communication or parsing failure
    /// @throws InterruptedException on interruption
    public static JsonObject planYggdrasilTextures(Path executable, JsonObject params) throws IOException, InterruptedException {
        return request(executable, "yggdrasil_plan_textures", params, 30);
    }

    /// Plans optional game arguments without exposing credentials on the process command line.
    /// @param executable native backend executable
    /// @param params encoded launch options
    /// @return native response containing ordered arguments
    /// @throws IOException if communication or planning fails
    /// @throws InterruptedException if interrupted
    public static JsonObject planLaunchExtras(Path executable, JsonObject params) throws IOException, InterruptedException {
        return request(executable, "plan_launch_extras", params, 30);
    }

    /// Represents a sanitized error code returned by the native backend.
    public static final class OperationException extends IOException {
        /// Stable protocol error code without server-provided descriptions.
        private final String code;
        private final @Nullable XboxFailure xbox;

        /// Creates an error containing only an identifier from the native protocol.
        ///
        /// @param code sanitized protocol error code
        private OperationException(String code, @Nullable XboxFailure xbox) {
            super("Native backend operation failed: " + code);
            this.code = code;
            this.xbox = xbox;
        }

        /// Returns the sanitized protocol error code.
        ///
        /// @return protocol error code
        public String code() {
            return code;
        }

        public @Nullable XboxFailure xbox() { return xbox; }
    }

    /// Xbox error details kept out of exception messages and logs.
    public record XboxFailure(long errorCode, @Nullable String redirect) { }

    /// Parses bounded Xbox error fields from a native response or device event.
    public static @Nullable XboxFailure parseXboxFailure(JsonObject envelope, String code) throws IOException {
        if (!"xbox_authorization".equals(code)) return null;
        try {
            JsonObject xbox = envelope.getAsJsonObject("xbox");
            JsonElement errorCode = xbox.get("error_code"), redirect = xbox.get("redirect");
            if (errorCode == null || !errorCode.isJsonPrimitive() || !errorCode.getAsJsonPrimitive().isNumber()
                    || redirect == null || (!redirect.isJsonNull() && (!redirect.isJsonPrimitive() || !redirect.getAsJsonPrimitive().isString())))
                throw new IOException("Invalid native Xbox error");
            long number = errorCode.getAsBigDecimal().longValueExact();
            @Nullable String url = redirect.isJsonNull() ? null : redirect.getAsString();
            if (number == 0 || (url != null && (url.length() > 4096 || url.chars().anyMatch(Character::isISOControl))))
                throw new IOException("Invalid native Xbox error");
            return new XboxFailure(number, url);
        } catch (RuntimeException e) { throw new IOException("Invalid native Xbox error", e); }
    }

    /// Expands multiple launch-argument groups through the native backend.
    ///
    /// Placeholder values may include credentials. Neither request nor result should be logged.
    /// Null argument entries are skipped as in the Java argument parser.
    ///
    /// @param executable native backend executable
    /// @param groups ordered argument groups
    /// @param keys placeholder values
    /// @return expanded groups in the original group order
    /// @throws IOException if the request or response is invalid
    /// @throws InterruptedException if interrupted
    public static @Unmodifiable List<@Unmodifiable List<String>> expandArgumentGroups(Path executable,
            List<? extends List<@org.jetbrains.annotations.Nullable String>> groups, Map<String, String> keys)
            throws IOException, InterruptedException {
        JsonArray inputGroups = new JsonArray();
        for (List<@org.jetbrains.annotations.Nullable String> group : groups) {
            JsonArray input = new JsonArray();
            for (@org.jetbrains.annotations.Nullable String argument : group) input.add(argument);
            inputGroups.add(input);
        }
        JsonObject replacements = new JsonObject();
        keys.forEach(replacements::addProperty);
        JsonObject params = new JsonObject();
        params.add("groups", inputGroups);
        params.add("keys", replacements);
        JsonObject result = request(executable, "expand_arguments", params, 30);
        try {
            JsonArray outputGroups = result.getAsJsonArray("groups");
            if (outputGroups.size() != groups.size()) throw new IOException("Invalid native argument groups");
            List<@Unmodifiable List<String>> expanded = new ArrayList<>(groups.size());
            for (int i = 0; i < groups.size(); i++) {
                JsonArray output = outputGroups.get(i).getAsJsonArray();
                long expected = groups.get(i).stream().filter(java.util.Objects::nonNull).count();
                if (output.size() != expected) throw new IOException("Invalid native argument count");
                List<String> values = new ArrayList<>(output.size());
                for (var value : output) {
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                        throw new IOException("Invalid native argument value");
                    }
                    values.add(value.getAsString());
                }
                expanded.add(List.copyOf(values));
            }
            return List.copyOf(expanded);
        } catch (RuntimeException e) {
            throw new IOException("Invalid native argument response", e);
        }
    }

    /// Selects rule-governed launch arguments and expands placeholders in the native backend.
    ///
    /// Operating-system restrictions are evaluated in Java; Rust applies ordered actions and
    /// feature requirements. Credential-bearing inputs and results must never be logged.
    ///
    /// @param executable native backend executable
    /// @param groups ordered argument groups
    /// @param featureGroups active features for each group
    /// @param keys placeholder values
    /// @return expanded selected argument groups
    /// @throws IOException for unsupported argument types or invalid protocol responses
    /// @throws InterruptedException if interrupted
    public static @Unmodifiable List<@Unmodifiable List<String>> expandRuledArgumentGroups(Path executable,
            List<? extends List<@Nullable Argument>> groups, List<? extends Map<String, Boolean>> featureGroups,
            Map<String, String> keys) throws IOException, InterruptedException {
        return expandRuledArgumentGroups(executable, groups, featureGroups, keys, () -> false, false);
    }

    /// Expands ruled argument groups while observing task cancellation.
    /// @param executable native backend executable
    /// @param groups ordered argument groups
    /// @param featureGroups feature values for each group
    /// @param keys placeholder replacements
    /// @param cancelled owning task cancellation predicate
    /// @param libraryOnly whether this contains only library indices and can use the shared worker
    /// @return expanded argument groups
    /// @throws IOException for unsupported inputs or responses
    /// @throws InterruptedException when cancelled
    private static @Unmodifiable List<@Unmodifiable List<String>> expandRuledArgumentGroups(Path executable,
            List<? extends List<@Nullable Argument>> groups, List<? extends Map<String, Boolean>> featureGroups,
            Map<String, String> keys, BooleanSupplier cancelled, boolean libraryOnly) throws IOException, InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        JsonObject params = encodeRuledArgumentGroups(groups, featureGroups, keys);
        @Nullable JsonObject pooled = libraryOnly ? NativePureRpc.tryLibraryRules(executable, params, cancelled) : null;
        JsonObject result = pooled != null ? pooled : request(executable, "expand_ruled_arguments", params, 30, cancelled);
        try {
            JsonArray outputGroups = result.getAsJsonArray("groups");
            if (outputGroups.size() != groups.size()) throw new IOException("Invalid native rule group count");
            List<@Unmodifiable List<String>> expanded = new ArrayList<>(groups.size());
            int total = 0;
            for (var value : outputGroups) {
                JsonArray array = value.getAsJsonArray();
                total = Math.addExact(total, array.size());
                if (total > 20_000) throw new IOException("Native argument count exceeds limit");
                List<String> group = new ArrayList<>(array.size());
                for (var item : array) {
                    if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) throw new IOException("Invalid native argument value");
                    group.add(item.getAsString());
                }
                expanded.add(List.copyOf(group));
            }
            return List.copyOf(expanded);
        } catch (RuntimeException e) {
            throw new IOException("Invalid native ruled argument response", e);
        }
    }

    private static JsonObject encodeRuledArgumentGroups(List<? extends List<@Nullable Argument>> groups,
            List<? extends Map<String, Boolean>> featureGroups, Map<String, String> keys) throws IOException {
        if (groups.size() != featureGroups.size()) throw new IOException("Invalid native rule group count");
        JsonArray inputGroups = new JsonArray();
        for (int i = 0; i < groups.size(); i++) {
            JsonObject group = new JsonObject();
            JsonObject features = new JsonObject();
            featureGroups.get(i).forEach(features::addProperty);
            group.add("features", features);
            JsonArray arguments = new JsonArray();
            for (@Nullable Argument argument : groups.get(i)) {
                if (argument == null) {
                    arguments.add((String) null);
                } else if (argument instanceof StringArgument stringArgument) {
                    @Nullable String value = stringArgument.argument();
                    if (value == null) throw new IOException("Invalid native string argument");
                    arguments.add(value);
                } else if (argument instanceof RuledArgument ruled) {
                    JsonObject object = new JsonObject();
                    @Nullable List<@Nullable CompatibilityRule> rules = ruled.rulesOrNull();
                    if (rules == null) {
                        object.add("rules", com.google.gson.JsonNull.INSTANCE);
                    } else {
                        JsonArray array = new JsonArray();
                        for (@Nullable CompatibilityRule rule : rules) {
                            if (rule == null) throw new IOException("Invalid native argument rule");
                            JsonObject item = new JsonObject();
                            @Nullable var restriction = rule.getOSRestriction();
                            @Nullable String osName = restriction == null ? null : restriction.getName();
                            @Nullable String versionRegex = restriction == null ? null : restriction.getVersionRegex();
                            @Nullable String archRegex = restriction == null ? null : restriction.getArchitectureRegex();
                            String systemVersion = org.jackhuang.hmcl.util.platform.OperatingSystem.SYSTEM_VERSION.getVersion();
                            String systemArch = org.jackhuang.hmcl.util.platform.Architecture.SYSTEM_ARCH.getCheckedName();
                            boolean compound = supportsCompoundRuntimeOs(osName);
                            boolean simple = (compound || ((osName == null || (osName.indexOf('-') < 0 && osName.chars().allMatch(c -> c < 128)))
                                    && supportsRuntimeRuleRegex(versionRegex) && supportsRuntimeRuleRegex(archRegex)))
                                    && portableRuntimeRuleText(systemVersion) && portableRuntimeRuleText(systemArch);
                            if (simple) {
                                item.addProperty("os_name", osName);
                                item.addProperty("system_os", org.jackhuang.hmcl.util.platform.OperatingSystem.CURRENT_OS.name());
                                item.addProperty("version_regex", versionRegex); item.addProperty("arch_regex", archRegex);
                                item.addProperty("system_version", systemVersion); item.addProperty("system_arch", systemArch);
                                item.addProperty("system_arch_enum", org.jackhuang.hmcl.util.platform.Architecture.SYSTEM_ARCH.name());
                            } else {
                                item.addProperty("os_matches", rule.matchesCurrentOS());
                            }
                            @Nullable CompatibilityRule.Action action = rule.getAction();
                            item.addProperty("action", action == null ? null : action.name().toLowerCase(java.util.Locale.ROOT));
                            @Nullable Map<String, @Nullable Boolean> requiredFeatures = rule.getFeatures();
                            if (requiredFeatures == null) {
                                item.add("features", com.google.gson.JsonNull.INSTANCE);
                            } else {
                                JsonObject required = new JsonObject();
                                requiredFeatures.forEach(required::addProperty);
                                item.add("features", required);
                            }
                            array.add(item);
                        }
                        object.add("rules", array);
                    }
                    @Nullable List<@Nullable String> values = ruled.valuesOrNull();
                    if (values == null) {
                        object.add("values", com.google.gson.JsonNull.INSTANCE);
                    } else {
                        JsonArray array = new JsonArray();
                        for (@Nullable String value : values) array.add(value);
                        object.add("values", array);
                    }
                    arguments.add(object);
                } else {
                    throw new IOException("Unsupported native argument type");
                }
            }
            group.add("arguments", arguments);
            inputGroups.add(group);
        }
        JsonObject replacements = new JsonObject();
        keys.forEach(replacements::addProperty);
        JsonObject params = new JsonObject();
        params.add("groups", inputGroups);
        params.add("keys", replacements);
        return params;
    }

    /// Checks whether a compound OS and architecture name has portable aliases.
    /// @param name original OS restriction
    /// @return whether Rust supports its compound form
    private static boolean supportsCompoundRuntimeOs(@Nullable String name) {
        if (name == null || !name.chars().allMatch(c -> c < 128)) return false;
        String[] parts = name.split("-", -1);
        if (parts.length != 2) return false;
        String os = parts[0].trim().toLowerCase(java.util.Locale.ROOT);
        String arch = parts[1].trim().toLowerCase(java.util.Locale.ROOT);
        return java.util.Set.of("linux", "windows", "win", "osx", "mac", "darwin", "freebsd", "solaris", "unix", "sunos").contains(os)
                && java.util.Set.of("x8664", "x86_64", "amd64", "ia32e", "em64t", "x64", "intel64", "x8632", "x86_32", "x86", "i86pc",
                    "i386", "i486", "i586", "i686", "ia32", "x32", "arm64", "aarch64", "arm", "arm32").contains(arch);
    }

    /// Restricts cross-engine regex matching to a common ASCII syntax.
    /// @param regex Java regex or null
    /// @return whether the regex can be matched by the Rust backend
    private static boolean supportsRuntimeRuleRegex(@Nullable String regex) {
        if (regex == null) return true;
        if (regex.length() > 256) return false;
        for (int i = 0; i < regex.length(); i++) {
            char ch = regex.charAt(i);
            if (ch == '\\') {
                if (++i >= regex.length() || regex.charAt(i) != '.') return false;
            } else if (!(ch >= 'a' && ch <= 'z') && !(ch >= 'A' && ch <= 'Z') && !(ch >= '0' && ch <= '9')
                    && "._^$*+-".indexOf(ch) < 0) return false;
        }
        try { java.util.regex.Pattern.compile(regex); return true; }
        catch (java.util.regex.PatternSyntaxException e) { return false; }
    }

    /// Whether system text is portable between the Java and Rust regex engines.
    /// @param value system version or architecture
    /// @return true for printable ASCII text
    private static boolean portableRuntimeRuleText(String value) {
        return value.chars().allMatch(ch -> ch >= 32 && ch < 127);
    }

    /// Resolves the classpath for the standard repository library layout in the native backend.
    ///
    /// Platform and feature applicability are evaluated in a native batch. Layouts that
    /// override library path resolution are rejected so their original implementation remains in use.
    ///
    /// @param executable native backend executable
    /// @param layout game repository layout
    /// @param instanceId instance identifier
    /// @param libraries resolved library descriptors
    /// @param processOptiFine whether OptiFine installer ordering is needed
    /// @param modLauncher whether ModLauncher discovers OptiFine separately
    /// @return ordered, deduplicated classpath entries
    /// @throws IOException if the layout or native response is unsupported
    /// @throws InterruptedException if interrupted
    public static @Unmodifiable List<String> planClasspath(Path executable, GameRepositoryLayout layout,
            GameInstanceID instanceId, List<Library> libraries, boolean processOptiFine, boolean modLauncher)
            throws IOException, InterruptedException {
        if (!(layout instanceof DefaultGameRepositoryLayout)) throw new IOException("Unsupported classpath layout");
        try {
            if (layout.getClass().getMethod("getLibraryFile", GameInstanceID.class, Library.class).getDeclaringClass()
                    != DefaultGameRepositoryLayout.class) throw new IOException("Custom library path layout");
        } catch (NoSuchMethodException | SecurityException e) {
            throw new IOException("Cannot verify library path layout", e);
        }
        JsonObject params = new JsonObject();
        params.addProperty("libraries_root", layout.getLibrariesDirectory().toAbsolutePath().toString());
        params.addProperty("instance_root", layout.getInstanceRoot(instanceId).toAbsolutePath().toString());
        params.addProperty("process_optifine", processOptiFine);
        params.addProperty("modlauncher", modLauncher);
        params.addProperty("platform_os", org.jackhuang.hmcl.util.platform.OperatingSystem.CURRENT_OS.getMojangName());
        params.addProperty("platform_arch", org.jackhuang.hmcl.util.platform.Architecture.SYSTEM_ARCH.getCheckedName());
        params.addProperty("platform_bits", org.jackhuang.hmcl.util.platform.Architecture.SYSTEM_ARCH.getBits().getBit());
        @Nullable JsonObject response = null;
        try {
            List<Argument> ruleInputs = new ArrayList<>(libraries.size());
            for (int i = 0; i < libraries.size(); i++)
                ruleInputs.add(new RuledArgument(libraries.get(i).rules(), List.of(Integer.toString(i))));
            JsonObject combined = params.deepCopy();
            combined.add("libraries", encodeClasspathLibraries(libraries, null));
            combined.add("rule_params", encodeRuledArgumentGroups(List.of(ruleInputs), List.of(Map.of()), Map.of()));
            response = NativePureRpc.tryClasspathWithRules(executable, combined, () -> Thread.currentThread().isInterrupted());
        } catch (IOException | RuntimeException ignored) {
            // Unsupported or unavailable combined planning retains the existing two-step path.
        }
        if (response == null) {
            boolean[] applicableLibraries = selectLibraryApplicability(executable, libraries);
            params.add("libraries", encodeClasspathLibraries(libraries, applicableLibraries));
            try { response = NativePureRpc.tryClasspathPlan(executable, params, () -> Thread.currentThread().isInterrupted()); }
            catch (IOException ignored) { /* A read-only plan may be retried in a fresh worker. */ }
            if (response == null) response = request(executable, "plan_classpath", params, 180, () -> Thread.currentThread().isInterrupted());
        }
        try {
            JsonArray paths = response.getAsJsonArray("classpath");
            if (paths.size() > libraries.size() + 1) throw new IOException("Invalid native classpath size");
            List<String> result = new ArrayList<>(paths.size());
            java.util.Set<String> unique = new java.util.HashSet<>();
            for (var element : paths) {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw new IOException("Invalid native classpath entry");
                String path = element.getAsString();
                if (!Path.of(path).isAbsolute() || !Path.of(path).normalize().toString().equals(path) || !unique.add(path)) {
                    throw new IOException("Invalid native classpath path");
                }
                result.add(path);
            }
            return List.copyOf(result);
        } catch (RuntimeException e) {
            throw new IOException("Invalid native classpath response", e);
        }
    }

    private static JsonArray encodeClasspathLibraries(List<Library> libraries, boolean @Nullable [] applicableLibraries) {
        JsonArray entries = new JsonArray();
        for (int i = 0; i < libraries.size(); i++) {
            Library library = libraries.get(i);
            JsonObject entry = new JsonObject();
            boolean applicable = applicableLibraries == null || applicableLibraries[i];
            entry.addProperty("applicable", applicable);
            if (applicable) {
                entry.addProperty("natives_present", library.natives() != null);
                JsonArray classifiers = new JsonArray();
                if (library.downloads() != null && library.downloads().classifiers() != null) {
                    library.downloads().classifiers().forEach((name, info) -> {
                        JsonObject classifier = new JsonObject(); classifier.addProperty("name", name);
                        classifier.addProperty("present", info != null); classifiers.add(classifier);
                    });
                }
                entry.add("classifiers", classifiers);
                Artifact artifact = library.artifact();
                entry.addProperty("group", artifact.getGroup());
                entry.addProperty("name", artifact.getName());
                entry.addProperty("version", artifact.getVersion());
                entry.addProperty("extension", artifact.getExtension());
                entry.addProperty("original_classifier", artifact.getClassifier());
                entry.addProperty("hint", library.hint());
                entry.addProperty("filename", library.filename());
                @Nullable LibraryDownloadInfo info = library.downloads() == null ? null : library.downloads().artifact();
                entry.addProperty("download_path", info == null ? null : info.getPath());
            }
            entries.add(entry);
        }
        return entries;
    }

    /// Selects library applicability in Rust using the same ordered rules as launch arguments.
    /// @param executable native backend executable
    /// @param libraries library entries in original order
    /// @return one applicability flag for each library
    /// @throws IOException on invalid input or protocol failure
    /// @throws InterruptedException if interrupted
    public static boolean[] selectLibraryApplicability(Path executable, List<Library> libraries) throws IOException, InterruptedException {
        return selectLibraryApplicability(executable, libraries, () -> false);
    }

    /// Selects library rules with cancellation for installation tasks.
    /// @param executable native backend executable
    /// @param libraries library entries in original order
    /// @param cancelled owning task cancellation predicate
    /// @return one applicability flag for each library
    /// @throws IOException on invalid input or protocol failure
    /// @throws InterruptedException if cancelled
    public static boolean[] selectLibraryApplicability(Path executable, List<Library> libraries, BooleanSupplier cancelled) throws IOException, InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        List<Argument> ruleInputs = new ArrayList<>(libraries.size());
        for (int i = 0; i < libraries.size(); i++)
            ruleInputs.add(new RuledArgument(libraries.get(i).rules(), List.of(Integer.toString(i))));
        List<String> selected = expandRuledArgumentGroups(executable, List.of(ruleInputs), List.of(Map.of()), Map.of(), cancelled, true).get(0);
        boolean[] applicableLibraries = new boolean[libraries.size()];
        int previous = -1;
        try {
            for (String value : selected) {
                int index = Integer.parseInt(value);
                if (index <= previous || index >= libraries.size()) throw new IOException("Invalid native library selection");
                applicableLibraries[index] = true; previous = index;
            }
        } catch (NumberFormatException e) { throw new IOException("Invalid native library selection", e); }
        return applicableLibraries;
    }

    /// Plans native archive paths for the standard game repository layout.
    /// @param executable native backend executable
    /// @param layout repository layout
    /// @param instanceId instance identifier
    /// @param libraries resolved libraries in order
    /// @return ordered map of library indices to archive paths
    /// @throws IOException on unsupported layout, metadata or protocol
    /// @throws InterruptedException if interrupted
    public static @Unmodifiable Map<Integer, Path> planNativeArchives(Path executable, GameRepositoryLayout layout,
            GameInstanceID instanceId, List<Library> libraries) throws IOException, InterruptedException {
        if (!(layout instanceof DefaultGameRepositoryLayout)) throw new IOException("Unsupported native archive layout");
        try {
            if (layout.getClass().getMethod("getLibraryFile", GameInstanceID.class, Library.class).getDeclaringClass()
                    != DefaultGameRepositoryLayout.class) throw new IOException("Custom native archive layout");
        } catch (NoSuchMethodException | SecurityException e) { throw new IOException("Cannot verify native archive layout", e); }
        JsonObject params = new JsonObject();
        params.addProperty("libraries_root", layout.getLibrariesDirectory().toString());
        params.addProperty("instance_root", layout.getInstanceRoot(instanceId).toString());
        params.addProperty("platform_os", org.jackhuang.hmcl.util.platform.OperatingSystem.CURRENT_OS.getMojangName());
        params.addProperty("platform_arch", org.jackhuang.hmcl.util.platform.Architecture.SYSTEM_ARCH.getCheckedName());
        params.addProperty("platform_bits", org.jackhuang.hmcl.util.platform.Architecture.SYSTEM_ARCH.getBits().getBit());
        @Nullable boolean[] applicable = null;
        @Nullable JsonObject result = null;
        try {
            List<Argument> ruleInputs = new ArrayList<>(libraries.size());
            for (int i = 0; i < libraries.size(); i++)
                ruleInputs.add(new RuledArgument(libraries.get(i).rules(), List.of(Integer.toString(i))));
            JsonObject combined = params.deepCopy();
            combined.add("libraries", encodeNativeArchiveLibraries(libraries, null));
            combined.add("rule_params", encodeRuledArgumentGroups(List.of(ruleInputs), List.of(Map.of()), Map.of()));
            result = NativePureRpc.tryNativeArchiveWithRules(executable, combined, () -> Thread.currentThread().isInterrupted());
            if (result != null) applicable = decodeApplicableIndices(result, libraries.size());
        } catch (IOException | RuntimeException ignored) {
            result = null; applicable = null;
        }
        if (result == null) {
            applicable = selectLibraryApplicability(executable, libraries);
            params.add("libraries", encodeNativeArchiveLibraries(libraries, applicable));
            @Nullable JsonObject pooled = NativePureRpc.tryNativeArchivePlan(executable, params, () -> Thread.currentThread().isInterrupted());
            result = pooled != null ? pooled : request(executable, "plan_native_archives", params, 30, () -> Thread.currentThread().isInterrupted());
        }
        boolean[] selected = java.util.Objects.requireNonNull(applicable);
        try {
            Map<Integer, Path> archives = new java.util.LinkedHashMap<>(); int previous = -1;
            for (JsonElement element : result.getAsJsonArray("archives")) {
                JsonObject archive = element.getAsJsonObject(); JsonElement rawIndex = archive.get("index");
                if (!rawIndex.isJsonPrimitive() || !rawIndex.getAsJsonPrimitive().isNumber()
                        || !rawIndex.getAsString().matches("0|[1-9][0-9]*")) throw new IOException("Invalid native archive index");
                int index = Integer.parseInt(rawIndex.getAsString());
                if (index <= previous || index >= libraries.size() || !selected[index]) throw new IOException("Invalid native archive selection");
                JsonElement rawPath = archive.get("path");
                if (!rawPath.isJsonPrimitive() || !rawPath.getAsJsonPrimitive().isString()) throw new IOException("Invalid native archive path");
                archives.put(index, Path.of(rawPath.getAsString())); previous = index;
            }
            return java.util.Collections.unmodifiableMap(archives);
        } catch (RuntimeException e) { throw new IOException("Invalid native archive plan", e); }
    }

    private static JsonArray encodeNativeArchiveLibraries(List<Library> libraries, boolean @Nullable [] applicable) {
        JsonArray encoded = new JsonArray();
        for (int i = 0; i < libraries.size(); i++) {
            Library library = libraries.get(i); JsonObject item = new JsonObject(); item.addProperty("applicable", applicable == null || applicable[i]);
            if (applicable == null || applicable[i]) {
                Artifact artifact = library.artifact(); item.addProperty("group", artifact.getGroup());
                item.addProperty("name", artifact.getName()); item.addProperty("version", artifact.getVersion());
                item.addProperty("extension", artifact.getExtension()); item.addProperty("original_classifier", artifact.getClassifier());
                item.addProperty("hint", library.hint()); item.addProperty("filename", library.filename());
                if (library.natives() != null) {
                    JsonObject natives = new JsonObject(); library.natives().forEach(natives::addProperty); item.add("natives", natives);
                }
                if (library.downloads() != null && library.downloads().classifiers() != null) {
                    JsonObject classifiers = new JsonObject();
                    library.downloads().classifiers().forEach((name, info) -> {
                        if (info == null) classifiers.add(name, com.google.gson.JsonNull.INSTANCE);
                        else { JsonObject entry = new JsonObject(); entry.addProperty("path", info.getPath()); classifiers.add(name, entry); }
                    });
                    item.add("classifiers", classifiers);
                }
            }
            encoded.add(item);
        }
        return encoded;
    }

    private static boolean[] decodeApplicableIndices(JsonObject result, int count) throws IOException {
        boolean[] selected = new boolean[count]; int previous = -1;
        var indices = result.getAsJsonArray("applicable_indices");
        if (indices == null) throw new IOException("Missing native applicability");
        for (JsonElement raw : indices) {
            if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber()
                    || !raw.getAsString().matches("0|[1-9][0-9]*")) throw new IOException("Invalid native applicability");
            int index;
            try { index = Integer.parseInt(raw.getAsString()); }
            catch (NumberFormatException e) { throw new IOException("Invalid native applicability", e); }
            if (index <= previous || index >= count) throw new IOException("Invalid native applicability");
            selected[index] = true; previous = index;
        }
        return selected;
    }

    /// One Modrinth file path and its corresponding SHA-512 syntax result.
    /// @param path resolved path inside the instance
    /// @param sha512 original digest, or null
    /// @param validSha512 whether the digest has valid SHA-512 syntax
    public record ModrinthFilePlan(Path path, @Nullable String sha512, boolean validSha512) { }

    /// Plans canonical Modrinth client file paths.
    /// @param executable native backend executable
    /// @param params file metadata, exclusions and root
    /// @param root normalized absolute game directory
    /// @param paths original manifest paths
    /// @param cancelled cancellation predicate
    /// @return ordered selected indices and paths
    /// @throws IOException on unsupported paths or protocol failure
    /// @throws InterruptedException when cancelled
    public static @Unmodifiable Map<Integer, ModrinthFilePlan> planModrinthFiles(Path executable, JsonObject params, Path root,
            List<String> paths, BooleanSupplier cancelled) throws IOException, InterruptedException {
        @Nullable JsonObject pooled = NativePureRpc.tryModrinthPlan(executable, params, cancelled);
        JsonObject result = pooled != null ? pooled : request(executable, "plan_modrinth_files", params, 30, cancelled);
        try {
            Map<Integer, ModrinthFilePlan> selected = new java.util.LinkedHashMap<>(); int previous = -1;
            for (JsonElement raw : result.getAsJsonArray("files")) {
                JsonObject item = raw.getAsJsonObject(); JsonElement number = item.get("index");
                if (!number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber()
                        || !number.getAsString().matches("0|[1-9][0-9]*")) throw new IOException("Invalid Modrinth file index");
                int index = Integer.parseInt(number.getAsString());
                if (index <= previous || index >= paths.size()) throw new IOException("Invalid Modrinth file selection");
                JsonElement rawPath = item.get("path");
                if (!rawPath.isJsonPrimitive() || !rawPath.getAsJsonPrimitive().isString()) throw new IOException("Invalid Modrinth planned path");
                Path path = Path.of(rawPath.getAsString());
                if (!path.isAbsolute() || !path.equals(path.normalize()) || !path.startsWith(root)
                        || !path.equals(root.resolve(paths.get(index)).toAbsolutePath().normalize()))
                    throw new IOException("Invalid Modrinth planned path");
                JsonElement rawSha = item.get("sha512");
                if (rawSha == null || (!rawSha.isJsonNull() && (!rawSha.isJsonPrimitive() || !rawSha.getAsJsonPrimitive().isString())))
                    throw new IOException("Invalid Modrinth digest");
                @Nullable String sha = rawSha.isJsonNull() ? null : rawSha.getAsString();
                JsonElement valid = item.get("valid_sha512");
                if (!valid.isJsonPrimitive() || !valid.getAsJsonPrimitive().isBoolean()) throw new IOException("Invalid Modrinth digest result");
                selected.put(index, new ModrinthFilePlan(path, sha, valid.getAsBoolean())); previous = index;
            }
            return java.util.Collections.unmodifiableMap(selected);
        } catch (RuntimeException e) { throw new IOException("Invalid Modrinth plan response", e); }
    }

    /// Plans CurseForge download selections and integrity algorithms in Rust.
    /// @param executable native backend executable
    /// @param params manifest metadata and exclusions
    /// @param cancelled cancellation predicate
    /// @return native plan
    /// @throws IOException on protocol failure
    /// @throws InterruptedException if cancelled
    public static JsonObject planCurseDownloads(Path executable, JsonObject params, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        @Nullable JsonObject pooled = NativePureRpc.tryCursePlan(executable, params, cancelled);
        return pooled != null ? pooled : request(executable, "plan_curse_downloads", params, 30, cancelled);
    }

    /// Parses one OAuth callback query in an isolated process.
    /// @param executable native backend executable
    /// @param query raw callback query; it may contain an authorization code
    /// @return parsed callback fields
    /// @throws IOException on unsupported input or protocol failure
    /// @throws InterruptedException if interrupted
    public static JsonObject parseOAuthCallback(Path executable, String query) throws IOException, InterruptedException {
        JsonObject params = new JsonObject(); params.addProperty("query", query);
        return request(executable, "parse_oauth_callback", params, 30);
    }

    /// Selects CurseForge metadata lookups or downloads in Rust.
    /// @param executable native backend executable
    /// @param params manifest data and selection mode
    /// @param size input entry count
    /// @param cancelled cancellation predicate
    /// @return selected flags in source order
    /// @throws IOException on protocol failure
    /// @throws InterruptedException if cancelled
    public static boolean[] selectCurseFiles(Path executable, JsonObject params, int size, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        @Nullable JsonObject pooled = NativePureRpc.tryCurseSelection(executable, params, cancelled);
        JsonObject result = pooled != null ? pooled : request(executable, "select_curse_files", params, 30, cancelled);
        try {
            boolean[] selected = new boolean[size]; int previous = -1;
            for (JsonElement raw : result.getAsJsonArray("indices")) {
                if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber()
                        || !raw.getAsString().matches("0|[1-9][0-9]*")) throw new IOException("Invalid CurseForge file index");
                int index = Integer.parseInt(raw.getAsString());
                if (index <= previous || index >= size) throw new IOException("Invalid CurseForge file selection");
                selected[index] = true; previous = index;
            }
            return selected;
        } catch (RuntimeException e) { throw new IOException("Invalid CurseForge selection response", e); }
    }

    /// Removes prevalidated managed paths under an instance root in an isolated process.
    /// @param executable native backend executable
    /// @param params instance root and relative paths
    /// @param maximum maximum number of removals
    /// @param cancelled cancellation predicate
    /// @throws IOException if removal or response validation fails
    /// @throws InterruptedException if cancelled
    public static void removeManagedFiles(Path executable, JsonObject params, int maximum, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        JsonObject result = request(executable, "remove_managed_files", params, 180, cancelled);
        try {
            JsonElement count = result.get("removed");
            if (!count.isJsonPrimitive() || !count.getAsJsonPrimitive().isNumber()
                    || !count.getAsString().matches("0|[1-9][0-9]*")) throw new IOException("Invalid native removal result");
            int removed = Integer.parseInt(count.getAsString());
            if (removed > maximum) throw new IOException("Invalid native removal count");
        } catch (RuntimeException e) { throw new IOException("Invalid native removal response", e); }
    }

    /// Finds removed Modrinth entries using native manifest comparison.
    /// @param executable native backend executable
    /// @param params old and new entry data
    /// @param size old entry count
    /// @param cancelled cancellation predicate
    /// @return removed flags in the original order
    /// @throws IOException on protocol failure
    /// @throws InterruptedException if cancelled
    public static boolean[] selectRemovedModrinthFiles(Path executable, JsonObject params, int size, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        @Nullable JsonObject pooled = NativePureRpc.tryModrinthUpdate(executable, params, cancelled);
        JsonObject result = pooled != null ? pooled : request(executable, "select_modrinth_removed", params, 30, cancelled);
        try {
            boolean[] removed = new boolean[size]; int previous = -1;
            for (JsonElement raw : result.getAsJsonArray("indices")) {
                if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber()
                        || !raw.getAsString().matches("0|[1-9][0-9]*")) throw new IOException("Invalid removed file index");
                int index = Integer.parseInt(raw.getAsString());
                if (index <= previous || index >= size) throw new IOException("Invalid removed file selection");
                removed[index] = true; previous = index;
            }
            return removed;
        } catch (RuntimeException e) { throw new IOException("Invalid removed file response", e); }
    }

    /// Selects eligible Modrinth client downloads using a reusable Rust worker.
    /// @param executable native backend executable
    /// @param params file metadata and optional exclusions
    /// @param size number of input files
    /// @param cancelled cancellation predicate
    /// @return flags corresponding to input entries
    /// @throws IOException on protocol failure
    /// @throws InterruptedException when cancelled
    public static boolean[] selectModrinthFiles(Path executable, JsonObject params, int size, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        @Nullable JsonObject pooled = NativePureRpc.tryModrinthSelection(executable, params, cancelled);
        JsonObject result = pooled != null ? pooled : request(executable, "select_modrinth_files", params, 30, cancelled);
        try {
            boolean[] selected = new boolean[size]; int previous = -1;
            for (JsonElement raw : result.getAsJsonArray("indices")) {
                if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber()
                        || !raw.getAsString().matches("0|[1-9][0-9]*")) throw new IOException("Invalid Modrinth file index");
                int index = Integer.parseInt(raw.getAsString());
                if (index <= previous || index >= size) throw new IOException("Invalid Modrinth file selection");
                selected[index] = true; previous = index;
            }
            return selected;
        } catch (RuntimeException e) { throw new IOException("Invalid Modrinth selection response", e); }
    }

    /// Selects applicable native libraries in their original order.
    /// @param executable native backend executable
    /// @param libraries library metadata
    /// @return flags indicating which archives need native extraction
    /// @throws IOException on protocol or metadata failure
    /// @throws InterruptedException when interrupted
    public static boolean[] selectNativeLibraries(Path executable, List<Library> libraries) throws IOException, InterruptedException {
        boolean[] applicable = selectLibraryApplicability(executable, libraries);
        JsonArray encoded = new JsonArray();
        for (int i = 0; i < libraries.size(); i++) {
            Library library = libraries.get(i); JsonObject item = new JsonObject(); item.addProperty("applicable", applicable[i]);
            if (applicable[i]) {
                item.addProperty("natives_present", library.natives() != null);
                JsonArray classifiers = new JsonArray();
                if (library.downloads() != null && library.downloads().classifiers() != null)
                    library.downloads().classifiers().keySet().forEach(classifiers::add);
                item.add("classifiers", classifiers);
            }
            encoded.add(item);
        }
        JsonObject params = new JsonObject(); params.add("libraries", encoded);
        @Nullable JsonObject pooled = NativePureRpc.tryNativeLibraries(executable, params, () -> Thread.currentThread().isInterrupted());
        JsonObject result = pooled != null ? pooled : request(executable, "select_native_libraries", params, 30);
        try {
            boolean[] selected = new boolean[libraries.size()]; int previous = -1;
            for (JsonElement element : result.getAsJsonArray("indices")) {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()
                        || !element.getAsString().matches("0|[1-9][0-9]*")) throw new IOException("Invalid native library index");
                int index = Integer.parseInt(element.getAsString());
                if (index <= previous || index >= libraries.size() || !applicable[index]) throw new IOException("Invalid native library selection");
                selected[index] = true; previous = index;
            }
            return selected;
        } catch (RuntimeException e) { throw new IOException("Invalid native library selection response", e); }
    }

    /// Describes one existing file to inspect without modifying it.
    ///
    /// @param index position in the owning file list
    /// @param path file path
    /// @param sha1 expected SHA-1 digest
    public record FileHashInput(int index, Path path, String sha1) { }

    /// Bounded installer profiles read from a stable archive.
    public record ForgeProfiles(String profile, @Nullable JsonObject projection, String version,
                                @Nullable JsonObject versionProjection, String versionPath, FileHashResult observation) { }

    /// Selected installer entries staged under a private directory.
    public record ForgeEntries(Path stage, List<@Nullable FileHashResult> entries, FileHashResult installer) { }

    /// Stages selected Forge-family embedded files without publishing them.
    public static ForgeEntries stageForgeEntries(Path executable, Path archive, Path parent, List<String> entries, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return stageForgeFiles(executable, archive, parent, entries, cancelled, "stage_forge_entries");
    }

    /// Stages selected installer data files without publishing them.
    public static ForgeEntries stageForgeData(Path executable, Path archive, Path parent, List<String> entries, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        return stageForgeFiles(executable, archive, parent, entries, cancelled, "stage_forge_data");
    }

    private static ForgeEntries stageForgeFiles(Path executable, Path archive, Path parent, List<String> entries,
            BooleanSupplier cancelled, String method) throws IOException, InterruptedException {
        if (entries.size() > 20_000) throw new IOException("Too many installer entries");
        Path absolute = archive.toAbsolutePath(), stageParent = parent.toAbsolutePath();
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(absolute.toString())
                || !StandardCharsets.UTF_8.newEncoder().canEncode(stageParent.toString())) throw new IOException("Unsupported installer path");
        JsonObject params = new JsonObject(); params.addProperty("archive", absolute.toString()); params.addProperty("staging_parent", stageParent.toString());
        JsonArray requested = new JsonArray();
        for (String name : entries) {
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(name)) throw new IOException("Unsupported installer entry");
            requested.add(name);
        }
        params.add("entries", requested);
        JsonObject result = request(executable, method, params, 180, cancelled);
        try {
            Path stage = Path.of(result.get("stage").getAsString()).toAbsolutePath();
            if (!stageParent.equals(stage.getParent()) || !stage.getFileName().toString().startsWith(".mfcl-forge-entries-")
                    || !Files.isDirectory(stage, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid native staging directory");
            FileHashResult installer = identity(result, absolute);
            if (!isFileHashObservationCurrent(archive, installer)) throw new IOException("Installer changed during extraction");
            JsonArray values = result.getAsJsonArray("entries");
            if (values.size() != entries.size()) throw new IOException("Invalid native installer entry count");
            List<@Nullable FileHashResult> output = new ArrayList<>(values.size());
            for (int i = 0; i < values.size(); i++) {
                JsonObject value = values.get(i).getAsJsonObject(); JsonElement present = value.get("present");
                if (value.get("index").getAsInt() != i || present == null || !present.isJsonPrimitive() || !present.getAsJsonPrimitive().isBoolean())
                    throw new IOException("Invalid native installer entry");
                Path staged = stage.resolve(Integer.toString(i));
                if (!present.getAsBoolean()) { if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unexpected staged entry"); output.add(null); continue; }
                if (!Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing staged entry");
                FileHashResult observation = identity(value, staged);
                if (!isFileHashObservationCurrent(staged, observation)) throw new IOException("Staged entry changed");
                output.add(observation);
            }
            return new ForgeEntries(stage, java.util.Collections.unmodifiableList(output), installer);
        } catch (RuntimeException e) { throw new IOException("Invalid native installer staging response", e); }
    }

    /// Parses a stable Unix file identity from a native response.
    private static FileHashResult identity(JsonObject value, Path path) throws IOException {
        try {
            long size = value.get("size").getAsBigDecimal().longValueExact();
            long modified = value.get("mtime_ns").getAsBigDecimal().longValueExact(), changed = value.get("ctime_ns").getAsBigDecimal().longValueExact();
            long device = value.get("device").getAsBigDecimal().longValueExact(), inode = value.get("inode").getAsBigDecimal().longValueExact();
            if (size < 0 || size > 1024L * 1024 * 1024 || modified < 0 || changed < 0 || device < 0 || inode < 0)
                throw new IOException("Invalid native file identity");
            return new FileHashResult(path.toAbsolutePath(), "", true, size, modified, changed, device, inode);
        } catch (RuntimeException e) { throw new IOException("Invalid native file identity", e); }
    }

    public record OptifinePlan(boolean patcher, boolean wrapper2, @Nullable String wrapperVersion,
                               boolean wrapperOf, @Nullable String build, FileHashResult observation) { }

    public record OptifineIdentity(String minecraft, String edition, String release, FileHashResult observation) { }

    /// Reads OptiFine version constants from a stable installer.
    public static OptifineIdentity identifyOptifineInstaller(Path executable, Path archive) throws IOException, InterruptedException {
        Path absolute = archive.toAbsolutePath(); String path = absolute.toString();
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(path) || !Path.of(path).equals(absolute)) throw new IOException("Unsupported installer path");
        JsonObject params = new JsonObject(); params.addProperty("archive", path);
        JsonObject result = request(executable, "identify_optifine_installer", params, 30, () -> Thread.currentThread().isInterrupted());
        try {
            for (String key : List.of("minecraft", "edition", "release")) {
                JsonElement value = result.get(key);
                if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                        || value.getAsString().length() > 65535) throw new IOException("Invalid OptiFine version constant");
            }
            FileHashResult observation = identity(result, absolute);
            if (!isFileHashObservationCurrent(archive, observation)) throw new IOException("Installer changed after inspection");
            return new OptifineIdentity(result.get("minecraft").getAsString(), result.get("edition").getAsString(),
                    result.get("release").getAsString(), observation);
        } catch (RuntimeException e) { throw new IOException("Invalid native OptiFine identity", e); }
    }

    /// Inspects an OptiFine installer without changing its contents.
    public static OptifinePlan planOptifineInstaller(Path executable, Path archive, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        Path absolute = archive.toAbsolutePath(); String path = absolute.toString();
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(path) || !Path.of(path).equals(absolute)) throw new IOException("Unsupported installer path");
        JsonObject params = new JsonObject(); params.addProperty("archive", path);
        JsonObject result = request(executable, "plan_optifine_installer", params, 30, cancelled);
        try {
            for (String key : List.of("patcher", "wrapper2", "wrapper_of")) {
                JsonElement value = result.get(key);
                if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IOException("Invalid installer flag");
            }
            for (String key : List.of("wrapper_version", "build")) {
                JsonElement value = result.get(key);
                if (value == null || (!value.isJsonNull() && (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                        || value.getAsString().length() > 16384))) throw new IOException("Invalid installer text");
            }
            @Nullable String version = result.get("wrapper_version").isJsonNull() ? null : result.get("wrapper_version").getAsString();
            @Nullable String build = result.get("build").isJsonNull() ? null : result.get("build").getAsString();
            boolean wrapperOf = result.get("wrapper_of").getAsBoolean();
            if (wrapperOf && version == null) throw new IOException("Invalid installer wrapper");
            FileHashResult observation = identity(result, absolute);
            if (!isFileHashObservationCurrent(archive, observation)) throw new IOException("Installer changed after inspection");
            return new OptifinePlan(result.get("patcher").getAsBoolean(), result.get("wrapper2").getAsBoolean(),
                    version, wrapperOf, build, observation);
        } catch (RuntimeException e) { throw new IOException("Invalid native OptiFine plan", e); }
    }

    /// Reads Forge-family profile JSON without extracting or mutating the installer.
    public static ForgeProfiles readForgeProfiles(Path executable, Path archive, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        Path absolute = archive.toAbsolutePath(); String text = absolute.toString();
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(text) || !Path.of(text).equals(absolute)) throw new IOException("Unsupported installer path");
        JsonObject params = new JsonObject(); params.addProperty("archive", text);
        JsonObject result = request(executable, "read_forge_profiles", params, 30, cancelled);
        try {
            for (String key : List.of("profile", "version", "version_path")) {
                if (!result.has(key) || !result.get(key).isJsonPrimitive() || !result.get(key).getAsJsonPrimitive().isString())
                    throw new IOException("Invalid native installer profile");
            }
            String profile = result.get("profile").getAsString(), version = result.get("version").getAsString();
            String versionPath = result.get("version_path").getAsString();
            if (profile.length() > 768 * 1024 || version.length() > 768 * 1024 || versionPath.length() > 4096)
                throw new IOException("Native installer profile is too large");
            long size = result.get("size").getAsBigDecimal().longValueExact();
            long modified = result.get("mtime_ns").getAsBigDecimal().longValueExact();
            long changed = result.get("ctime_ns").getAsBigDecimal().longValueExact();
            long device = result.get("device").getAsBigDecimal().longValueExact(), inode = result.get("inode").getAsBigDecimal().longValueExact();
            if (size < 0 || size > 1024L * 1024 * 1024 || modified < 0 || changed < 0 || device < 0 || inode < 0)
                throw new IOException("Invalid native installer identity");
            FileHashResult observation = new FileHashResult(absolute, "", true, size, modified, changed, device, inode);
            if (!isFileHashObservationCurrent(archive, observation)) throw new IOException("Installer changed after native read");
            @Nullable JsonObject projection = result.has("profile_projection_v1") && result.get("profile_projection_v1").isJsonObject()
                    ? result.getAsJsonObject("profile_projection_v1") : null;
            @Nullable JsonObject versionProjection = result.has("version_projection_v1") && result.get("version_projection_v1").isJsonObject()
                    ? result.getAsJsonObject("version_projection_v1") : null;
            return new ForgeProfiles(profile, projection, version, versionProjection, versionPath, observation);
        } catch (RuntimeException e) { throw new IOException("Invalid native installer profile", e); }
    }

    /// One verified file to download into a caller-owned private staging directory.
    public record BatchDownloadInput(int index, String sha1, List<String> urls) { }

    /// Downloads staged files while retaining the result for every requested index.
    public static @Unmodifiable Map<Integer, Boolean> downloadBatch(Path executable, Path stage,
            List<BatchDownloadInput> inputs, BooleanSupplier cancelled, BiConsumer<Long, Long> progress)
            throws IOException, InterruptedException {
        if (inputs.size() > 64 || !stage.isAbsolute()) throw new IOException("Invalid download batch");
        JsonObject params = new JsonObject(); params.addProperty("stage_directory", stage.toString());
        JsonArray items = new JsonArray(); java.util.Set<Integer> expected = new java.util.HashSet<>();
        for (BatchDownloadInput input : inputs) {
            if (input.index() < 0 || !expected.add(input.index()) || !DigestUtils.isSha1Digest(input.sha1())
                    || input.urls().isEmpty() || input.urls().size() > 8) throw new IOException("Invalid batch item");
            JsonObject item = new JsonObject(); item.addProperty("index", input.index());
            item.addProperty("name", Integer.toString(input.index())); item.addProperty("sha1", input.sha1());
            item.addProperty("max_bytes", 1024L * 1024 * 1024);
            JsonArray urls = new JsonArray(); input.urls().forEach(urls::add); item.add("urls", urls); items.add(item);
        }
        params.add("items", items); params.addProperty("concurrency", 1);
        JsonObject response = request(executable, "download_batch", params, 600, cancelled, (completed, total) -> {
            if (completed < 0 || completed > inputs.size() || total != inputs.size()) throw new IllegalArgumentException("Invalid batch progress");
            progress.accept(completed, total);
        });
        try {
            Map<Integer, Boolean> result = new java.util.HashMap<>();
            for (JsonElement element : response.getAsJsonArray("results")) {
                JsonObject item = element.getAsJsonObject(); int index = item.get("index").getAsInt();
                JsonElement status = item.get("ok");
                if (!expected.contains(index) || status == null || !status.isJsonPrimitive() || !status.getAsJsonPrimitive().isBoolean()
                        || result.putIfAbsent(index, status.getAsBoolean()) != null) throw new IOException("Invalid batch result");
            }
            if (result.size() != inputs.size()) throw new IOException("Incomplete batch result");
            return Map.copyOf(result);
        } catch (RuntimeException e) { throw new IOException("Invalid native batch response", e); }
    }

    /// Captures a native file hash result and the metadata observed during inspection.
    ///
    /// @param path path that was inspected
    /// @param expectedSha1 digest used for inspection
    /// @param matches whether the content matched the expected digest
    /// @param size observed file size
    /// @param modifiedNanos observed modification time in nanoseconds since the epoch
    /// @param changedNanos observed Unix change time in nanoseconds since the epoch
    /// @param device observed Unix device identifier
    /// @param inode observed Unix inode identifier
    public record FileHashResult(Path path, String expectedSha1, boolean matches, long size, long modifiedNanos,
                                    long changedNanos, long device, long inode) { }

    /// Captures a stable legacy library checksum result and file identity.
    /// @param path inspected path
    /// @param valid whether one allowed checksum verified the file or its legacy JAR entries
    /// @param size observed size
    /// @param modifiedNanos observed modification time
    /// @param changedNanos observed Unix change time
    /// @param device observed device identifier
    /// @param inode observed inode identifier
    public record LegacyChecksumResult(Path path, boolean valid, long size, long modifiedNanos,
                                       long changedNanos, long device, long inode) { }

    /// Checks one library against nonempty Forge-style checksums without modifying it.
    /// @param executable native backend executable
    /// @param file library path
    /// @param checksums nonempty accepted checksum list, possibly containing null entries
    /// @param jar whether Java's legacy JAR verification applies
    /// @return stable result, or null when native verification is unsupported
    /// @throws IOException on protocol or communication failure
    /// @throws InterruptedException if interrupted
    public static @Nullable LegacyChecksumResult checkLegacyLibrary(Path executable, Path file,
            List<@Nullable String> checksums, boolean jar) throws IOException, InterruptedException {
        String absolute = file.toAbsolutePath().toString();
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(absolute)) throw new IOException("Unsupported native path");
        JsonArray values = new JsonArray();
        for (@Nullable String checksum : checksums) {
            if (checksum != null && !StandardCharsets.UTF_8.newEncoder().canEncode(checksum)) throw new IOException("Unsupported native checksum");
            values.add(checksum);
        }
        JsonObject params = new JsonObject(); params.addProperty("path", absolute); params.add("checksums", values); params.addProperty("jar", jar);
        @Nullable JsonObject pooled = NativePureRpc.tryLegacyChecksum(executable, params, () -> Thread.currentThread().isInterrupted());
        JsonObject result = pooled != null ? pooled : request(executable, "check_legacy_library", params, 180);
        try {
            if (result.has("unknown")) {
                if (!result.get("unknown").isJsonPrimitive() || !result.get("unknown").getAsJsonPrimitive().isBoolean()
                        || !result.get("unknown").getAsBoolean() || result.has("valid")) throw new IOException("Invalid native checksum status");
                return null;
            }
            if (!result.get("valid").isJsonPrimitive() || !result.get("valid").getAsJsonPrimitive().isBoolean())
                throw new IOException("Invalid native checksum result");
            long size = result.get("size").getAsLong(), modified = result.get("mtime_ns").getAsLong();
            long changed = result.get("ctime_ns").getAsLong(), device = result.get("device").getAsLong(), inode = result.get("inode").getAsLong();
            if (size < 0 || size > 1024L * 1024 * 1024 || modified < 0 || changed < 0 || device < 0 || inode < 0)
                throw new IOException("Invalid native checksum identity");
            return new LegacyChecksumResult(file.toAbsolutePath(), result.get("valid").getAsBoolean(), size, modified, changed, device, inode);
        } catch (RuntimeException e) { throw new IOException("Invalid native checksum response"); }
    }

    /// Rechecks the identity of a native legacy-checksum observation.
    /// @param file current library path
    /// @param observation native observation
    /// @return whether the observed identity is still current
    public static boolean isLegacyChecksumObservationCurrent(Path file, LegacyChecksumResult observation) {
        return isFileHashObservationCurrent(file, new FileHashResult(observation.path(), "", observation.valid(), observation.size(),
                observation.modifiedNanos(), observation.changedNanos(), observation.device(), observation.inode()));
    }

    /// Stages a verified cache copy without publishing it to the shared cache.
    /// @param executable native backend executable
    /// @param source source file
    /// @param stage nonexistent file in a caller-owned staging directory
    /// @param algorithm SHA-1, SHA-256, SHA-512 or MD5
    /// @param digest expected digest
    /// @return stable source observation, or null when native staging is unavailable
    /// @throws IOException on protocol or I/O failure
    /// @throws InterruptedException if interrupted
    public static @Nullable FileHashResult stageCacheCopy(Path executable, Path source, Path stage, String algorithm,
            String digest) throws IOException, InterruptedException {
        Path absolute = source.toAbsolutePath(); Path stageAbsolute = stage.toAbsolutePath();
        String sourceText = absolute.toString(), stageText = stageAbsolute.toString();
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(sourceText) || !StandardCharsets.UTF_8.newEncoder().canEncode(stageText)
                || !Path.of(sourceText).equals(absolute) || !Path.of(stageText).equals(stageAbsolute)) return null;
        JsonObject params = new JsonObject(); params.addProperty("source", sourceText); params.addProperty("stage", stageText);
        params.addProperty("algorithm", algorithm); params.addProperty("digest", digest);
        @Nullable JsonObject pooled = NativePureRpc.tryCacheStage(executable, params, () -> Thread.currentThread().isInterrupted());
        JsonObject result = pooled != null ? pooled : request(executable, "stage_cache_copy", params, 180, () -> Thread.currentThread().isInterrupted());
        try {
            if (result.has("unknown")) {
                if (!result.get("unknown").isJsonPrimitive() || !result.get("unknown").getAsJsonPrimitive().isBoolean()
                        || !result.get("unknown").getAsBoolean()) throw new IOException("Invalid native cache stage status");
                return null;
            }
            long size = result.get("size").getAsLong(), modified = result.get("mtime_ns").getAsLong();
            long changed = result.get("ctime_ns").getAsLong(), device = result.get("device").getAsLong(), inode = result.get("inode").getAsLong();
            if (size < 0 || size > 1024L * 1024 * 1024 || modified < 0 || changed < 0 || device < 0 || inode < 0
                    || !Files.isRegularFile(stageAbsolute, LinkOption.NOFOLLOW_LINKS) || Files.size(stageAbsolute) != size)
                throw new IOException("Invalid native cache staging result");
            return new FileHashResult(absolute, digest, true, size, modified, changed, device, inode);
        } catch (RuntimeException e) { throw new IOException("Invalid native cache stage response", e); }
    }

    /// Computes SHA-1 for a stable cache file through the reusable backend.
    /// @param executable native backend executable
    /// @param file file to hash
    /// @return lowercase SHA-1, or null if the observation is unsupported or stale
    /// @throws IOException on protocol or I/O failure
    /// @throws InterruptedException if interrupted
    public static @Nullable String hashCacheSha1(Path executable, Path file) throws IOException, InterruptedException {
        Path absolute = file.toAbsolutePath(); String path = absolute.toString();
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(path) || !Path.of(path).equals(absolute)) return null;
        JsonObject params = new JsonObject(); params.addProperty("path", path);
        @Nullable JsonObject pooled = NativePureRpc.tryCacheHash(executable, params, () -> Thread.currentThread().isInterrupted());
        JsonObject result = pooled != null ? pooled : request(executable, "hash_cache_sha1", params, 180, () -> Thread.currentThread().isInterrupted());
        try {
            if (result.has("unknown")) {
                if (!result.get("unknown").isJsonPrimitive() || !result.get("unknown").getAsJsonPrimitive().isBoolean()
                        || !result.get("unknown").getAsBoolean()) throw new IOException("Invalid native cache hash status");
                return null;
            }
            String hash = result.get("sha1").getAsString();
            if (!DigestUtils.isSha1Digest(hash) || !hash.equals(hash.toLowerCase(java.util.Locale.ROOT)))
                throw new IOException("Invalid native cache hash");
            long size = result.get("size").getAsLong(), modified = result.get("mtime_ns").getAsLong();
            long changed = result.get("ctime_ns").getAsLong(), device = result.get("device").getAsLong(), inode = result.get("inode").getAsLong();
            if (size < 0 || size > 1024L * 1024 * 1024 || modified < 0 || changed < 0 || device < 0 || inode < 0)
                throw new IOException("Invalid native cache hash identity");
            FileHashResult observation = new FileHashResult(absolute, hash, true, size, modified, changed, device, inode);
            return isFileHashObservationCurrent(absolute, observation) ? hash : null;
        } catch (RuntimeException e) { throw new IOException("Invalid native cache hash response", e); }
    }

    /// Checks a SHA-256 or MD5 digest against a stable cache file.
    /// @param executable native backend executable
    /// @param file cache file
    /// @param algorithm canonical algorithm name
    /// @param digest expected hexadecimal digest
    /// @return match result, or null if the file observation is unavailable or stale
    /// @throws IOException on protocol or I/O failure
    /// @throws InterruptedException if interrupted
    public static @Nullable Boolean checkCacheDigest(Path executable, Path file, String algorithm, String digest)
            throws IOException, InterruptedException {
        Path absolute = file.toAbsolutePath(); String path = absolute.toString();
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(path) || !Path.of(path).equals(absolute)) return null;
        JsonObject params = new JsonObject(); params.addProperty("path", path);
        params.addProperty("algorithm", algorithm); params.addProperty("digest", digest);
        @Nullable JsonObject pooled = NativePureRpc.tryCacheDigest(executable, params, () -> Thread.currentThread().isInterrupted());
        JsonObject result = pooled != null ? pooled : request(executable, "check_cache_digest", params, 180, () -> Thread.currentThread().isInterrupted());
        try {
            if (result.has("unknown")) {
                if (!result.get("unknown").isJsonPrimitive() || !result.get("unknown").getAsJsonPrimitive().isBoolean()
                        || !result.get("unknown").getAsBoolean()) throw new IOException("Invalid native cache digest status");
                return null;
            }
            if (!result.get("matches").isJsonPrimitive() || !result.get("matches").getAsJsonPrimitive().isBoolean())
                throw new IOException("Invalid native cache digest match");
            long size = result.get("size").getAsLong(), modified = result.get("mtime_ns").getAsLong();
            long changed = result.get("ctime_ns").getAsLong(), device = result.get("device").getAsLong(), inode = result.get("inode").getAsLong();
            if (size < 0 || size > 1024L * 1024 * 1024 || modified < 0 || changed < 0 || device < 0 || inode < 0)
                throw new IOException("Invalid native cache digest identity");
            boolean matches = result.get("matches").getAsBoolean();
            FileHashResult observation = new FileHashResult(absolute, digest, matches, size, modified, changed, device, inode);
            return isFileHashObservationCurrent(absolute, observation) ? matches : null;
        } catch (RuntimeException e) { throw new IOException("Invalid native cache digest response", e); }
    }

    /// Decodes a Mojang runtime LZMA file into a caller-owned private stage.
    /// @param executable native backend executable
    /// @param source compressed source file
    /// @param stage nonexistent private output path
    /// @param probe existing ordinary file in the same private stage directory
    /// @param sha1 expected uncompressed SHA-1
    /// @param size expected uncompressed size
    /// @param cancelled owning task cancellation predicate
    /// @throws IOException on unsupported input, decoding failure or invalid response
    /// @throws InterruptedException if cancelled
    public static void stageRuntimeLzma(Path executable, Path source, Path stage, Path probe, String sha1, long size,
            BooleanSupplier cancelled) throws IOException, InterruptedException {
        Path sourceAbsolute = source.toAbsolutePath(), stageAbsolute = stage.toAbsolutePath(), probeAbsolute = probe.toAbsolutePath();
        String sourceText = sourceAbsolute.toString(), stageText = stageAbsolute.toString(), probeText = probeAbsolute.toString();
        if (!DigestUtils.isSha1Digest(sha1) || size <= 0 || size > 1024L * 1024 * 1024
                || !StandardCharsets.UTF_8.newEncoder().canEncode(sourceText) || !StandardCharsets.UTF_8.newEncoder().canEncode(stageText)
                || !StandardCharsets.UTF_8.newEncoder().canEncode(probeText) || !Path.of(probeText).equals(probeAbsolute)
                || !Path.of(sourceText).equals(sourceAbsolute) || !Path.of(stageText).equals(stageAbsolute))
            throw new IOException("Unsupported runtime LZMA parameters");
        JsonObject params = new JsonObject(); params.addProperty("source", sourceText); params.addProperty("stage", stageText);
        params.addProperty("probe", probeText); params.addProperty("sha1", sha1); params.addProperty("size", size);
        @Nullable JsonObject pooled = NativePureRpc.tryRuntimeLzma(executable, params, cancelled);
        JsonObject result = pooled != null ? pooled : request(executable, "stage_runtime_lzma", params, 180, cancelled);
        try {
            if (result.get("size").getAsLong() != size || !sha1.equalsIgnoreCase(result.get("sha1").getAsString())
                    || !Files.isRegularFile(stageAbsolute, LinkOption.NOFOLLOW_LINKS) || Files.size(stageAbsolute) != size)
                throw new IOException("Invalid native runtime LZMA result");
        } catch (RuntimeException e) { throw new IOException("Invalid native runtime LZMA response", e); }
    }

    /// Computes SHA-1 and SHA-512 together for a stable file used in a Modrinth export.
    /// @param executable native backend executable
    /// @param file file to inspect
    /// @param cancelled owning task cancellation predicate
    /// @return immutable digest map, or null if the file could not be safely observed
    /// @throws IOException on protocol failure
    /// @throws InterruptedException if cancelled
    public static @Nullable @Unmodifiable Map<String, String> hashModpackFile(Path executable, Path file,
            BooleanSupplier cancelled) throws IOException, InterruptedException {
        String path = file.toAbsolutePath().toString();
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(path)) return null;
        JsonObject params = new JsonObject(); params.addProperty("path", path);
        @Nullable JsonObject pooled = NativePureRpc.tryModpackHashes(executable, params, cancelled);
        JsonObject result = pooled != null ? pooled : request(executable, "hash_modpack_file", params, 180, cancelled);
        try {
            if (result.has("unknown")) {
                if (!result.get("unknown").isJsonPrimitive() || !result.get("unknown").getAsJsonPrimitive().isBoolean()
                        || !result.get("unknown").getAsBoolean()) throw new IOException("Invalid native hash status");
                return null;
            }
            String sha1 = result.get("sha1").getAsString(), sha512 = result.get("sha512").getAsString();
            if (!DigestUtils.isSha1Digest(sha1) || !DigestUtils.isSha512Digest(sha512)) throw new IOException("Invalid native modpack digests");
            long size = result.get("size").getAsLong(), modified = result.get("mtime_ns").getAsLong();
            long changed = result.get("ctime_ns").getAsLong(), device = result.get("device").getAsLong(), inode = result.get("inode").getAsLong();
            if (size < 0 || size > 1024L * 1024 * 1024 || modified < 0 || changed < 0 || device < 0 || inode < 0)
                throw new IOException("Invalid native file identity");
            FileHashResult observation = new FileHashResult(file.toAbsolutePath(), sha1, true, size, modified, changed, device, inode);
            if (!isFileHashObservationCurrent(file, observation)) return null;
            return Map.of("sha1", sha1, "sha512", sha512);
        } catch (RuntimeException e) { throw new IOException("Invalid native modpack hash response", e); }
    }

    /// Checks SHA-1 for a batch of existing files without changing them.
    ///
    /// Files that cannot be inspected safely are omitted from the returned map. Callers must
    /// recheck file metadata before using a result and retain ZIP and additional checksum checks.
    ///
    /// @param executable native backend executable
    /// @param inputs bounded file inspection requests
    /// @param cancelled owning task cancellation predicate
    /// @return immutable map from input index to a verified hash observation
    /// @throws IOException if the request or response is invalid
    /// @throws InterruptedException if cancelled or interrupted
    public static @Unmodifiable Map<Integer, FileHashResult> checkFileHashes(Path executable,
            List<FileHashInput> inputs, BooleanSupplier cancelled) throws IOException, InterruptedException {
        return checkFileHashes(executable, inputs, cancelled, null);
    }

    /// Checks file hashes and optionally reports completed items while the native child runs.
    ///
    /// @param executable native backend executable
    /// @param inputs bounded file inspection requests
    /// @param cancelled owning task cancellation predicate
    /// @param progress optional callback receiving completed and total item counts
    /// @return immutable map of stable hash observations
    /// @throws IOException if the request, progress event or response is invalid
    /// @throws InterruptedException if cancelled or interrupted
    public static @Unmodifiable Map<Integer, FileHashResult> checkFileHashes(Path executable,
            List<FileHashInput> inputs, BooleanSupplier cancelled, @Nullable BiConsumer<Long, Long> progress)
            throws IOException, InterruptedException {
        if (inputs.size() > 20_000) throw new IOException("Native file batch exceeds limit");
        JsonArray items = new JsonArray();
        Map<Integer, FileHashInput> expected = new java.util.HashMap<>();
        for (FileHashInput input : inputs) {
            if (input.index() < 0 || expected.putIfAbsent(input.index(), input) != null) throw new IOException("Invalid file batch index");
            JsonObject item = new JsonObject();
            item.addProperty("index", input.index());
            item.addProperty("path", input.path().toAbsolutePath().toString());
            item.addProperty("sha1", input.sha1());
            items.add(item);
        }
        JsonObject params = new JsonObject();
        params.add("items", items);
        params.addProperty("progress", progress != null);
        @Nullable BiConsumer<Long, Long> verifiedProgress = progress == null ? null : (completed, total) -> {
            if (total != inputs.size() || completed < 0 || completed > total) {
                throw new IllegalArgumentException("Invalid native file progress");
            }
            progress.accept(completed, total);
        };
        @Nullable JsonObject pooled = progress == null ? NativePureRpc.tryFileHashes(executable, params, cancelled) : null;
        JsonObject result = pooled != null ? pooled : request(executable, "check_files", params, 180, cancelled, verifiedProgress);
        try {
            JsonArray entries = result.getAsJsonArray("results");
            if (entries.size() != inputs.size()) throw new IOException("Invalid native file result count");
            Map<Integer, FileHashResult> verified = new java.util.HashMap<>();
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (var value : entries) {
                JsonObject entry = value.getAsJsonObject();
                int index = entry.get("index").getAsInt();
                if (!expected.containsKey(index) || !seen.add(index)) throw new IOException("Invalid native file result index");
                if (entry.has("unknown")) {
                    if (!entry.get("unknown").getAsBoolean() || entry.has("matches")) throw new IOException("Invalid native file status");
                    continue;
                }
                if (!entry.get("matches").isJsonPrimitive() || !entry.get("matches").getAsJsonPrimitive().isBoolean()) {
                    throw new IOException("Invalid native file hash status");
                }
                long size = entry.get("size").getAsLong();
                long modified = entry.get("mtime_ns").getAsLong();
                long changed = entry.get("ctime_ns").getAsLong();
                long device = entry.get("device").getAsLong();
                long inode = entry.get("inode").getAsLong();
                if (size < 0 || size > 1024L * 1024 * 1024 || modified < 0 || changed < 0 || device < 0 || inode < 0) {
                    throw new IOException("Invalid native file metadata");
                }
                FileHashInput input = java.util.Objects.requireNonNull(expected.get(index));
                verified.put(index, new FileHashResult(input.path().toAbsolutePath(), input.sha1(),
                        entry.get("matches").getAsBoolean(), size, modified, changed, device, inode));
            }
            return Map.copyOf(verified);
        } catch (RuntimeException e) {
            throw new IOException("Invalid native file response", e);
        }
    }

    /// Checks whether an inspected file still has the observed path, timestamps and Unix identity.
    ///
    /// Unsupported file systems or metadata providers return false, preserving Java verification.
    ///
    /// @param file current file path
    /// @param observation native hash observation
    /// @return whether the observation still applies to the current file
    public static boolean isFileHashObservationCurrent(Path file, FileHashResult observation) {
        if (file.getFileSystem() != FileSystems.getDefault() || !file.toAbsolutePath().equals(observation.path())) return false;
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            @Nullable Object device = Files.getAttribute(file, "unix:dev");
            @Nullable Object inode = Files.getAttribute(file, "unix:ino");
            @Nullable Object changed = Files.getAttribute(file, "unix:ctime");
            return attributes.isRegularFile() && attributes.size() == observation.size()
                    && attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS) == observation.modifiedNanos()
                    && device instanceof Number deviceNumber && deviceNumber.longValue() == observation.device()
                    && inode instanceof Number inodeNumber && inodeNumber.longValue() == observation.inode()
                    && changed instanceof FileTime changeTime && changeTime.to(TimeUnit.NANOSECONDS) == observation.changedNanos();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /// Parses an asset index into the installation work list using the native backend.
    ///
    /// Logical entries are not deduplicated. The caller remains responsible for checking files,
    /// downloading missing assets, caching and reporting installation progress.
    ///
    /// @param executable native backend executable
    /// @param indexPath asset index file
    /// @param cancelled cancellation predicate for the owning task
    /// @return immutable list of asset objects
    /// @throws IOException if parsing or protocol validation fails
    /// @throws InterruptedException if cancelled or interrupted
    public static @Unmodifiable List<AssetObject> planAssets(Path executable, Path indexPath, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        JsonObject params = new JsonObject();
        // Preserve `..`: lexical normalization can cross a symbolic link differently from the OS.
        params.addProperty("index_path", indexPath.toAbsolutePath().toString());
        JsonObject result = request(executable, "plan_assets", params, 180, cancelled);
        try {
            var array = result.getAsJsonArray("objects");
            if (array.size() > 50_000) throw new IOException("Native asset plan exceeds object limit");
            List<AssetObject> objects = new ArrayList<>(array.size());
            for (var value : array) {
                JsonObject object = value.getAsJsonObject();
                String hash = object.get("hash").getAsString();
                long size = object.get("size").getAsLong();
                if (!hash.matches("[0-9a-fA-F]{40}") || size < 0) throw new IOException("Invalid native asset entry");
                objects.add(new AssetObject(hash, size));
            }
            return List.copyOf(objects);
        } catch (RuntimeException e) {
            throw new IOException("Invalid native asset plan", e);
        }
    }

    /// One asset and the native inspection performed for its expected location.
    /// @param asset logical asset descriptor
    /// @param missing whether the native scan observed a missing or non-regular file
    /// @param observation stable checksum observation, or null when unavailable
    public record AssetInstallEntry(AssetObject asset, boolean missing, @Nullable FileHashResult observation) { }

    /// Parses and inspects a standard asset directory in one native operation.
    /// @param executable native backend executable
    /// @param indexPath asset index file
    /// @param assetDirectory standard asset directory
    /// @param integrity whether to hash existing files
    /// @param cancelled owning task cancellation predicate
    /// @param progress completed and total logical assets
    /// @return immutable entries in native index order
    /// @throws IOException if planning or protocol validation fails
    /// @throws InterruptedException if cancelled
    public static @Unmodifiable List<AssetInstallEntry> planAssetInstall(Path executable, Path indexPath,
            Path assetDirectory, boolean integrity, BooleanSupplier cancelled, BiConsumer<Long, Long> progress)
            throws IOException, InterruptedException {
        // Preserve symbolic-link traversal semantics in both the index and asset paths.
        Path root = assetDirectory.toAbsolutePath();
        String rootString = root.toString(), indexString = indexPath.toAbsolutePath().toString();
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(rootString) || !StandardCharsets.UTF_8.newEncoder().canEncode(indexString))
            throw new IOException("Unsupported native asset path");
        JsonObject params = new JsonObject(); params.addProperty("index_path", indexString);
        params.addProperty("asset_directory", rootString); params.addProperty("integrity", integrity);
        java.util.concurrent.atomic.AtomicLong advertised = new java.util.concurrent.atomic.AtomicLong(-1);
        JsonObject result = request(executable, "plan_asset_install", params, 180, cancelled, (completed, total) -> {
            if (total < 0 || total > 50_000 || (advertised.get() != -1 && advertised.get() != total))
                throw new IllegalArgumentException("Invalid native asset progress");
            advertised.set(total); progress.accept(completed, total);
        });
        try {
            @Nullable JsonArray values = result.getAsJsonArray("objects");
            if (values == null || values.size() > 50_000 || advertised.get() != values.size()) throw new IOException("Invalid native asset count");
            List<AssetInstallEntry> entries = new ArrayList<>(values.size());
            for (JsonElement value : values) {
                JsonObject object = value.getAsJsonObject();
                if (!object.get("hash").isJsonPrimitive() || !object.get("hash").getAsJsonPrimitive().isString())
                    throw new IOException("Invalid native asset hash");
                if (!object.get("size").isJsonPrimitive() || !object.get("size").getAsJsonPrimitive().isNumber())
                    throw new IOException("Invalid native asset size");
                String hash = object.get("hash").getAsString();
                String sizeText = object.get("size").getAsString();
                if (!hash.matches("[0-9a-fA-F]{40}") || !sizeText.matches("[0-9]{1,19}")) throw new IOException("Invalid native asset entry");
                long size = Long.parseLong(sizeText);
                @Nullable JsonObject inspection = object.getAsJsonObject("inspection");
                if (inspection == null) throw new IOException("Invalid native asset inspection");
                String state = inspection.get("state").getAsString();
                boolean missing = "missing".equals(state);
                @Nullable FileHashResult observation = null;
                if ("observed".equals(state)) {
                    if (!integrity) throw new IOException("Unexpected native asset hash");
                    @Nullable JsonObject item = inspection.getAsJsonObject("observation");
                    if (item == null) throw new IOException("Invalid native asset observation");
                    if (!item.get("matches").isJsonPrimitive() || !item.get("matches").getAsJsonPrimitive().isBoolean())
                        throw new IOException("Invalid native asset hash status");
                    long observedSize = item.get("size").getAsLong(), modified = item.get("mtime_ns").getAsLong();
                    long changed = item.get("ctime_ns").getAsLong(), device = item.get("device").getAsLong(), inode = item.get("inode").getAsLong();
                    if (observedSize < 0 || observedSize > 1024L * 1024 * 1024 || modified < 0 || changed < 0 || device < 0 || inode < 0)
                        throw new IOException("Invalid native asset identity");
                    Path path = root.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
                    observation = new FileHashResult(path, hash, item.get("matches").getAsBoolean(), observedSize, modified, changed, device, inode);
                } else if (!missing && !"unknown".equals(state)) throw new IOException("Invalid native asset inspection");
                entries.add(new AssetInstallEntry(new AssetObject(hash, size), missing, observation));
            }
            return List.copyOf(entries);
        } catch (RuntimeException e) { throw new IOException("Invalid native asset plan"); }
    }

    /// Sends one bounded JSON request and reads a response from a child process.
    ///
    /// @param executable explicit executable path
    /// @param method protocol method name
    /// @param params request parameters
    /// @param timeoutSeconds maximum wait for the process and response
    /// @return the result object
    /// @throws IOException on process, I/O, protocol, or remote-operation failure
    /// @throws InterruptedException if the calling thread is interrupted
    private static JsonObject request(Path executable, String method, JsonObject params, long timeoutSeconds) throws IOException, InterruptedException {
        return request(executable, method, params, timeoutSeconds, () -> false);
    }

    /// Sends a request while monitoring a caller-provided cancellation predicate.
    ///
    /// @param executable native backend executable
    /// @param method protocol method
    /// @param params protocol parameters
    /// @param timeoutSeconds maximum process runtime
    /// @param cancelled cancellation predicate
    /// @return response result
    /// @throws IOException if the request fails
    /// @throws InterruptedException if cancelled or interrupted
    private static JsonObject request(Path executable, String method, JsonObject params, long timeoutSeconds,
                                      BooleanSupplier cancelled) throws IOException, InterruptedException {
        return request(executable, method, params, timeoutSeconds, cancelled, null);
    }

    /// Sends a cancellable request and optionally delivers bounded progress events.
    ///
    /// @param executable native backend executable
    /// @param method protocol method
    /// @param params protocol parameters
    /// @param timeoutSeconds maximum process runtime
    /// @param cancelled cancellation predicate
    /// @param progress optional progress listener
    /// @return response result
    /// @throws IOException if communication fails
    /// @throws InterruptedException if cancelled or interrupted
    private static JsonObject request(Path executable, String method, JsonObject params, long timeoutSeconds,
                                      BooleanSupplier cancelled, @Nullable BiConsumer<Long, Long> progress)
            throws IOException, InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException("Native operation cancelled");
        JsonObject request = new JsonObject();
        request.addProperty("version", 1);
        request.addProperty("id", 1);
        request.addProperty("method", method);
        request.add("params", params);
        byte[] requestBytes = (request + "\n").getBytes(StandardCharsets.UTF_8);
        if (requestBytes.length > REQUEST_LIMIT) {
            throw new IOException("Native backend request is too large");
        }
        Process process = new ProcessBuilder(executable.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        FutureTask<JsonObject> response = new FutureTask<>(() -> readFrames(process.getInputStream(), progress));
        FutureTask<Void> writer = new FutureTask<>(() -> {
            try (var input = process.getOutputStream()) {
                input.write(requestBytes);
            }
            return null;
        });
        Thread readerThread = new Thread(response, "MFCL-native-response-reader");
        Thread writerThread = new Thread(writer, "MFCL-native-request-writer");
        readerThread.setDaemon(true);
        writerThread.setDaemon(true);
        readerThread.start();
        writerThread.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        try {
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) throw new InterruptedException("Native operation cancelled");
                if (System.nanoTime() >= deadline) throw new IOException("Native backend request timed out");
                if (response.isDone()) {
                    try { response.get(); }
                    catch (ExecutionException e) { throw responseFailure(e); }
                }
            }
            if (cancelled.getAsBoolean()) throw new InterruptedException("Native operation cancelled");
            if (process.exitValue() != 0) {
                throw new IOException("Native backend exited unsuccessfully");
            }
            try {
                writer.get(Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                return response.get(Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (ExecutionException e) {
                throw responseFailure(e);
            } catch (TimeoutException e) {
                throw new IOException("Native backend response timed out");
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            process.getInputStream().close();
            process.getOutputStream().close();
            response.cancel(true);
            writer.cancel(true);
        }
    }

    /// Unwraps a bounded reader failure without including response contents in diagnostics.
    ///
    /// @param failure reader or writer failure
    /// @return sanitized I/O exception
    private static IOException responseFailure(ExecutionException failure) {
        if (failure.getCause() instanceof IOException error) return error;
        return new IOException("Native backend communication failed");
    }

    /// Reads progress events followed by one final response, with per-frame limits.
    ///
    /// @param input child standard output
    /// @param progress optional progress listener
    /// @return final result object
    /// @throws IOException if a frame is malformed or exceeds limits
    private static JsonObject readFrames(InputStream input, @Nullable BiConsumer<Long, Long> progress) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(input);
        long previous = -1;
        int events = 0;
        while (true) {
            String line = readFrame(buffered);
            JsonObject frame;
            try {
                frame = JsonParser.parseString(line).getAsJsonObject();
                if (!frame.has("event")) {
                    JsonObject result = parseResponse(line);
                    int trailing = 0;
                    int value;
                    while ((value = buffered.read()) != -1) {
                        if (++trailing > 1024 || (value != ' ' && value != '\t' && value != '\r' && value != '\n')) {
                            throw new IOException("Unexpected data after native backend response");
                        }
                    }
                    return result;
                }
                if (progress == null || ++events > 20_001 || frame.get("version").getAsInt() != 1
                        || frame.get("id").getAsInt() != 1 || !"progress".equals(frame.get("event").getAsString())
                        || frame.has("result") || frame.has("error")) throw new IOException("Invalid native progress envelope");
                long completed = frame.get("downloaded").getAsLong();
                var totalValue = frame.get("total");
                long total = totalValue.isJsonNull() ? -1 : totalValue.getAsLong();
                if (completed < 0 || completed < previous || total < -1 || (total >= 0 && completed > total)) {
                    throw new IOException("Invalid native progress value");
                }
                previous = completed;
                progress.accept(completed, total);
            } catch (RuntimeException e) {
                throw new IOException("Invalid native backend progress response");
            }
        }
    }

    /// Reads one UTF-8 JSON protocol frame without accepting unbounded data.
    ///
    /// @param input buffered child output
    /// @return one newline-terminated frame
    /// @throws IOException if the stream closes early or the frame is too large
    private static String readFrame(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value < 0) throw new IOException("Native backend ended before its final response");
            if (value == '\n') return bytes.toString(StandardCharsets.UTF_8);
            if (bytes.size() >= RESPONSE_LIMIT) throw new IOException("Native backend response is too large");
            bytes.write(value);
        }
    }

    /// Checks the response envelope and extracts its result object.
    ///
    /// @param text JSON response text
    /// @return the result object
    /// @throws IOException if the response is malformed, incompatible, or reports an error
    private static JsonObject parseResponse(String text) throws IOException {
        try {
            JsonObject response = JsonParser.parseString(text).getAsJsonObject();
            if (response.get("version").getAsInt() != 1 || response.get("id").getAsInt() != 1) {
                throw new IOException("Invalid native backend response envelope");
            }
            if (response.has("error") && !response.has("result")) {
                JsonObject error = response.getAsJsonObject("error");
                String code = error.get("code").getAsString();
                if (!code.matches("[a-z_]{1,64}")) {
                    throw new IOException("Invalid native backend error code");
                }
                throw new OperationException(code, parseXboxFailure(error, code));
            }
            if (!response.has("result") || !response.get("result").isJsonObject() || response.has("error")) {
                throw new IOException("Invalid native backend response envelope");
            }
            return response.getAsJsonObject("result");
        } catch (RuntimeException e) {
            throw new IOException("Invalid native backend response");
        }
    }
}
