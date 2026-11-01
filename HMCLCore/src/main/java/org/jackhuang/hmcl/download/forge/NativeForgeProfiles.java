/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.forge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.download.game.NativeGameManifest;
import org.jackhuang.hmcl.game.Artifact;
import org.jackhuang.hmcl.game.GameInstanceManifest;
import org.jackhuang.hmcl.game.Library;
import org.jackhuang.hmcl.game.GameInstanceID;
import org.jackhuang.hmcl.game.GameRepositoryLayout;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/// Reads Forge-family installer profiles through Rust while retaining Java format adapters.
public final class NativeForgeProfiles {
    private NativeForgeProfiles() { }

    public record Parsed(ForgeNewInstallProfile profile, GameInstanceManifest version,
                         NativeBackend.FileHashResult observation, boolean specDeclared,
                         @Nullable String profileName, boolean containsNeoForge) { }

    public record LegacyParsed(ForgeInstallProfile profile, NativeBackend.FileHashResult observation) { }

    /// Reads a legacy Forge profile and its embedded version from a stable installer.
    public static @Nullable LegacyParsed tryReadLegacy(Path installer, BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank()
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.processors.enabled", "true"))) return null;
        try {
            NativeBackend.ForgeProfiles data = NativeBackend.readForgeProfiles(Path.of(executable), installer, cancelled);
            if (!"versionInfo".equals(data.versionPath())) return null;
            JsonObject raw = com.google.gson.JsonParser.parseString(data.profile()).getAsJsonObject();
            if (raw.has("spec") || !raw.has("install") || !raw.get("install").isJsonObject()) return null;
            @Nullable ForgeInstall install = null;
            if (data.projection() != null) {
                try { install = decodeLegacyInstall(data.projection()); } catch (IOException | RuntimeException ignored) { }
            }
            if (install == null) install = JsonUtils.GSON.fromJson(raw.get("install"), ForgeInstall.class);
            if (install == null) return null;
            @Nullable GameInstanceManifest version = null;
            if (data.versionProjection() != null) {
                try { version = NativeGameManifest.decodeLoaderManifest(data.versionProjection(), data.version()); }
                catch (IOException | RuntimeException ignored) { }
            }
            if (version == null) version = JsonUtils.fromNonNullJson(data.version(), GameInstanceManifest.class);
            if (!NativeBackend.isFileHashObservationCurrent(installer, data.observation())) return null;
            return new LegacyParsed(new ForgeInstallProfile(install, version), data.observation());
        } catch (IOException | RuntimeException e) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            return null;
        }
    }

    private static ForgeInstall decodeLegacyInstall(JsonObject data) throws IOException {
        JsonElement marker = required(data, "legacy_install_v1"), path = required(data, "path");
        if (!marker.isJsonPrimitive() || !marker.getAsJsonPrimitive().isBoolean() || !marker.getAsBoolean())
            throw new IOException("Unsupported legacy installer projection");
        return new ForgeInstall(nullableText(data, "profileName"), nullableText(data, "target"),
                path.isJsonNull() ? null : artifact(path), nullableText(data, "version"), nullableText(data, "filePath"),
                nullableText(data, "welcome"), nullableText(data, "minecraft"), nullableText(data, "mirrorList"), nullableText(data, "logo"));
    }

    private record Embedded(Path archivePath, Path destination) { }

    /// Stages a regular ZIP entry and preserves the ZIP filesystem copy attributes on publication.
    public static boolean tryCopyArchiveEntry(Path installer, Path source, Path destination,
            NativeBackend.FileHashResult expected, BooleanSupplier cancelled) throws IOException, InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank()
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.processors.enabled", "true"))) return false;
        String entry = source.toString();
        if (entry.startsWith("/")) entry = entry.substring(1);
        Path parent;
        try { parent = Files.createTempDirectory("mfcl-archive-entry-").toAbsolutePath(); }
        catch (IOException | RuntimeException e) { if (cancelled.getAsBoolean()) throw new InterruptedException(); return false; }
        try {
            Path stagedFile;
            try {
                if (!NativeBackend.isFileHashObservationCurrent(installer, expected)) return false;
                NativeBackend.ForgeEntries staged = NativeBackend.stageForgeData(Path.of(executable), installer, parent, List.of(entry), cancelled);
                @Nullable NativeBackend.FileHashResult observation = staged.entries().get(0);
                stagedFile = staged.stage().resolve("0");
                if (observation == null || !NativeBackend.isFileHashObservationCurrent(stagedFile, observation)
                        || !NativeBackend.isFileHashObservationCurrent(installer, staged.installer())
                        || !NativeBackend.isFileHashObservationCurrent(installer, expected)) return false;
                BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class);
                if (!attributes.isRegularFile()) return false;
                Files.getFileAttributeView(stagedFile, BasicFileAttributeView.class).setTimes(
                        attributes.lastModifiedTime(), attributes.lastAccessTime(), attributes.creationTime());
            } catch (IOException | RuntimeException e) {
                if (cancelled.getAsBoolean()) throw new InterruptedException();
                return false;
            }
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            org.jackhuang.hmcl.util.io.FileUtils.copyFile(stagedFile, destination);
            return true;
        } finally {
            org.jackhuang.hmcl.util.io.FileUtils.deleteDirectoryQuietly(parent);
        }
    }

    /// Stages the legacy universal JAR in Rust and publishes it using the original stream semantics.
    public static boolean tryCopyLegacyEmbedded(Path installer, String entry, Path destination,
            NativeBackend.FileHashResult profileObservation, BooleanSupplier cancelled) throws IOException, InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank()
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.processors.enabled", "true"))
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.forge.embedded.enabled", "true"))) return false;
        Path parent;
        try { parent = Files.createTempDirectory("mfcl-forge-legacy-").toAbsolutePath(); }
        catch (IOException | RuntimeException e) { if (cancelled.getAsBoolean()) throw new InterruptedException(); return false; }
        try {
            NativeBackend.ForgeEntries staged;
            try {
                if (!NativeBackend.isFileHashObservationCurrent(installer, profileObservation)) return false;
                staged = NativeBackend.stageForgeData(Path.of(executable), installer, parent, List.of(entry), cancelled);
                @Nullable NativeBackend.FileHashResult observation = staged.entries().get(0);
                Path source = staged.stage().resolve("0");
                if (observation == null || !NativeBackend.isFileHashObservationCurrent(source, observation)
                        || !NativeBackend.isFileHashObservationCurrent(installer, staged.installer())
                        || !NativeBackend.isFileHashObservationCurrent(installer, profileObservation)) return false;
            } catch (IOException | RuntimeException e) {
                if (cancelled.getAsBoolean()) throw new InterruptedException();
                return false;
            }
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            try (var in = Files.newInputStream(staged.stage().resolve("0"));
                 var out = Files.newOutputStream(destination, java.nio.file.StandardOpenOption.CREATE,
                         java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                in.transferTo(out);
            }
            return true;
        } finally {
            org.jackhuang.hmcl.util.io.FileUtils.deleteDirectoryQuietly(parent);
        }
    }

    /// Extracts ordinary installer data to temporary files; null requests the original Java path.
    public static @Nullable List<String> tryExtractData(Path installer, FileSystem fs, List<String> paths,
            Path tempDirectory, BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank()
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.processors.enabled", "true"))
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.forge.embedded.enabled", "true"))) return null;
        if (paths.isEmpty()) return List.of();
        Path parent;
        try { parent = Files.createTempDirectory("mfcl-forge-data-parent-").toAbsolutePath(); }
        catch (IOException | RuntimeException e) { if (cancelled.getAsBoolean()) throw new InterruptedException(); return null; }
        List<Path> published = new ArrayList<>(); boolean success = false;
        try {
            List<Path> sources = new ArrayList<>(paths.size()); List<String> names = new ArrayList<>(paths.size());
            for (String name : paths) {
                Path source = fs.getPath(name); sources.add(source);
                String value = source.toString(); names.add(value.startsWith("/") ? value.substring(1) : value);
            }
            NativeBackend.ForgeEntries staged = NativeBackend.stageForgeData(Path.of(executable), installer, parent, names, cancelled);
            if (!NativeBackend.isFileHashObservationCurrent(installer, staged.installer())) return null;
            for (int i = 0; i < sources.size(); i++) {
                if (cancelled.getAsBoolean()) throw new InterruptedException();
                Path file = staged.stage().resolve(Integer.toString(i));
                @Nullable NativeBackend.FileHashResult observation = staged.entries().get(i);
                if (observation == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || !NativeBackend.isFileHashObservationCurrent(file, observation)) return null;
                BasicFileAttributes attrs = Files.readAttributes(sources.get(i), BasicFileAttributes.class);
                if (!attrs.isRegularFile()) return null;
                Files.getFileAttributeView(file, BasicFileAttributeView.class).setTimes(attrs.lastModifiedTime(), attrs.lastAccessTime(), attrs.creationTime());
            }
            if (!NativeBackend.isFileHashObservationCurrent(installer, staged.installer())) return null;
            for (int i = 0; i < sources.size(); i++) {
                if (cancelled.getAsBoolean()) throw new InterruptedException();
                Path destination = Files.createTempFile(tempDirectory, null, null); published.add(destination);
                org.jackhuang.hmcl.util.io.FileUtils.copyFile(staged.stage().resolve(Integer.toString(i)), destination);
            }
            success = true;
            return published.stream().map(Path::toString).toList();
        } catch (IOException | RuntimeException e) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            return null;
        } finally {
            if (!success) for (Path path : published) try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            org.jackhuang.hmcl.util.io.FileUtils.deleteDirectoryQuietly(parent);
        }
    }

    /// Stages ordinary embedded libraries in Rust and publishes them with the existing copy semantics.
    /// Returns false only before publishing any files, allowing the Java ZIP path to take over.
    public static boolean tryCopyEmbedded(Path installer, FileSystem fs, ForgeNewInstallProfile profile,
            GameInstanceID id, GameRepositoryLayout layout, BooleanSupplier cancelled) throws IOException, InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank()
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.processors.enabled", "true"))
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.forge.embedded.enabled", "true"))) return false;
        List<Embedded> entries = new ArrayList<>();
        try {
            Path maven = fs.getPath("maven");
            for (Library library : profile.getLibraries())
                entries.add(new Embedded(maven.resolve(library.getPath()), layout.getLibraryFile(id, library)));
            if (profile.getPath().isPresent()) {
                Artifact artifact = profile.getPath().get();
                entries.add(new Embedded(artifact.getPath(maven), layout.getArtifactFile(artifact)));
            }
        } catch (RuntimeException e) { return false; }
        if (entries.isEmpty()) return true;
        Path parent;
        try { parent = Files.createTempDirectory("mfcl-forge-entries-parent-").toAbsolutePath(); }
        catch (IOException | RuntimeException e) { if (cancelled.getAsBoolean()) throw new InterruptedException(); return false; }
        try {
            @Nullable NativeBackend.ForgeEntries staged = null;
            try {
                staged = NativeBackend.stageForgeEntries(Path.of(executable), installer, parent,
                        entries.stream().map(value -> value.archivePath().toString()).toList(), cancelled);
                if (!NativeBackend.isFileHashObservationCurrent(installer, staged.installer())) return false;
                for (int i = 0; i < entries.size(); i++) {
                    if (cancelled.getAsBoolean()) throw new InterruptedException();
                    Path source = entries.get(i).archivePath(), file = staged.stage().resolve(Integer.toString(i));
                    @Nullable NativeBackend.FileHashResult observation = staged.entries().get(i);
                    if (Files.exists(source) != (observation != null)) return false;
                    if (observation != null) {
                        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !NativeBackend.isFileHashObservationCurrent(file, observation)) return false;
                        BasicFileAttributes attrs = Files.readAttributes(source, BasicFileAttributes.class);
                        if (!attrs.isRegularFile()) return false;
                        Files.getFileAttributeView(file, BasicFileAttributeView.class).setTimes(attrs.lastModifiedTime(), attrs.lastAccessTime(), attrs.creationTime());
                    }
                }
                if (!NativeBackend.isFileHashObservationCurrent(installer, staged.installer())) return false;
            } catch (IOException | RuntimeException e) {
                if (cancelled.getAsBoolean()) throw new InterruptedException();
                return false;
            }
            for (int i = 0; i < entries.size(); i++) {
                if (cancelled.getAsBoolean()) throw new InterruptedException();
                if (staged.entries().get(i) != null)
                    org.jackhuang.hmcl.util.io.FileUtils.copyFile(staged.stage().resolve(Integer.toString(i)), entries.get(i).destination());
            }
            return true;
        } finally {
            org.jackhuang.hmcl.util.io.FileUtils.deleteDirectoryQuietly(parent);
        }
    }

    public static @Nullable Parsed tryRead(Path installer, BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank()
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.processors.enabled", "true"))) return null;
        try {
            NativeBackend.ForgeProfiles data = NativeBackend.readForgeProfiles(Path.of(executable), installer, cancelled);
            @Nullable ForgeNewInstallProfile profile = null;
            if (data.projection() != null) {
                try { profile = decode(data.projection()); } catch (IOException | RuntimeException ignored) { }
            }
            if (profile == null) profile = JsonUtils.fromNonNullJson(data.profile(), ForgeNewInstallProfile.class);
            if (!data.versionPath().equals(profile.getJson())) return null;
            @Nullable GameInstanceManifest version = null;
            if (data.versionProjection() != null) {
                try { version = NativeGameManifest.decodeLoaderManifest(data.versionProjection(), data.version()); }
                catch (IOException | RuntimeException ignored) { }
            }
            if (version == null) version = JsonUtils.fromNonNullJson(data.version(), GameInstanceManifest.class);
            if (!NativeBackend.isFileHashObservationCurrent(installer, data.observation())) return null;
            JsonObject raw = com.google.gson.JsonParser.parseString(data.profile()).getAsJsonObject();
            @Nullable JsonElement name = raw.get("profile");
            @Nullable String profileName = name != null && name.isJsonPrimitive() && name.getAsJsonPrimitive().isString()
                    ? name.getAsString() : null;
            return new Parsed(profile, version, data.observation(), raw.has("spec"), profileName, data.profile().contains("neoforge"));
        } catch (IOException | RuntimeException e) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            return null;
        }
    }

    /// Reads a profile for synchronous install entry points and preserves interruption.
    public static @Nullable Parsed tryReadForInstall(Path installer) throws IOException {
        try { return tryRead(installer, () -> Thread.currentThread().isInterrupted()); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("Installer inspection was interrupted");
        }
    }

    /// Reconstructs the existing Java-facing profile from native fields.
    static ForgeNewInstallProfile decode(JsonObject object) throws IOException {
        JsonElement spec = object.get("spec");
        if (spec == null || !spec.isJsonPrimitive() || !spec.getAsJsonPrimitive().isNumber()) throw new IOException("Invalid installer spec");
        JsonElement rawPath = required(object, "path"), rawLibraries = required(object, "libraries");
        JsonElement rawProcessors = required(object, "processors"), rawData = required(object, "data");
        @Nullable Artifact path = rawPath.isJsonNull() ? null : artifact(rawPath);
        @Nullable List<Library> libraries = NativeGameManifest.decodeNativeLibraries(rawLibraries);
        @Nullable List<ForgeNewInstallProfile.Processor> processors = null;
        if (!rawProcessors.isJsonNull()) {
            processors = new ArrayList<>();
            for (JsonElement element : rawProcessors.getAsJsonArray()) {
                JsonObject processor = element.getAsJsonObject();
                JsonElement rawClasspath = required(processor, "classpath");
                @Nullable List<Artifact> classpath = null;
                if (!rawClasspath.isJsonNull()) {
                    classpath = new ArrayList<>(); for (JsonElement item : rawClasspath.getAsJsonArray()) classpath.add(artifact(item));
                }
                processors.add(new ForgeNewInstallProfile.Processor(strings(required(processor, "sides")), artifact(required(processor, "jar")),
                        classpath, strings(required(processor, "args")), stringMap(required(processor, "outputs"), "value")));
            }
        }
        @Nullable Map<String, ForgeNewInstallProfile.Datum> data = null;
        if (!rawData.isJsonNull()) {
            data = new LinkedHashMap<>();
            for (JsonElement element : rawData.getAsJsonArray()) {
                JsonObject datum = element.getAsJsonObject();
                data.put(text(datum, "key"), new ForgeNewInstallProfile.Datum(nullableText(datum, "client")));
            }
        }
        return new ForgeNewInstallProfile(spec.getAsBigDecimal().intValueExact(), text(object, "minecraft"), text(object, "json"),
                text(object, "version"), path, libraries, processors, data);
    }

    private static Artifact artifact(JsonElement value) throws IOException {
        JsonObject object = value.getAsJsonObject();
        return new Artifact(text(object, "group"), text(object, "name"), text(object, "version"),
                nullableText(object, "classifier"), nullableText(object, "extension"));
    }

    private static @Nullable List<String> strings(JsonElement value) throws IOException {
        if (value.isJsonNull()) return null;
        List<String> result = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray()) {
            if (item.isJsonNull()) result.add(null);
            else if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) result.add(item.getAsString());
            else throw new IOException("Invalid installer string list");
        }
        return result;
    }

    private static @Nullable Map<String, String> stringMap(JsonElement value, String field) throws IOException {
        if (value.isJsonNull()) return null;
        Map<String, String> result = new LinkedHashMap<>();
        for (JsonElement item : value.getAsJsonArray()) {
            JsonObject entry = item.getAsJsonObject(); result.put(text(entry, "key"), nullableText(entry, field));
        }
        return result;
    }

    private static JsonElement required(JsonObject object, String key) throws IOException {
        @Nullable JsonElement value = object.get(key); if (value == null) throw new IOException("Missing installer field"); return value;
    }

    private static String text(JsonObject object, String key) throws IOException {
        JsonElement value = required(object, key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Invalid installer string");
        return value.getAsString();
    }

    private static @Nullable String nullableText(JsonObject object, String key) throws IOException {
        return required(object, key).isJsonNull() ? null : text(object, key);
    }
}
