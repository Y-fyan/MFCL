/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.download.liteloader;

import org.jackhuang.hmcl.download.ComponentRemoteVersionList;
import org.jackhuang.hmcl.download.DefaultDependencyManager;
import org.jackhuang.hmcl.download.ComponentRemoteVersion;
import org.jackhuang.hmcl.game.GameComponentType;
import org.jackhuang.hmcl.game.GameInstanceManifest;
import org.jackhuang.hmcl.game.GameInstancePatch;
import org.jackhuang.hmcl.game.Library;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.util.gson.JsonSerializable;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.versioning.GameVersionNumber;
import org.jackhuang.hmcl.download.game.NativeGameManifest;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

@NotNullByDefault
public final class LiteLoaderRemoteVersion extends ComponentRemoteVersion {

    @JsonSerializable
    record LiteLoaderRemoteVersionRecord(String gameVersion, String version, boolean snapshot, String url,
                                         String tweakClass, List<Library> libraries) { }

    public static Task<ComponentRemoteVersionList<LiteLoaderRemoteVersion>> fetchAsync(GameVersionNumber gameVersion) {
        return Task.supplyAsync(() -> {
            List<LiteLoaderRemoteVersionRecord> records;

            try (var input = LiteLoaderRemoteVersion.class.getResourceAsStream("/assets/liteloader/versions.json")) {
                if (input == null) {
                    LOG.warning("Failed to load LiteLoader versions.json from resources");
                    return ComponentRemoteVersionList.of(GameComponentType.LITELOADER);
                }

                String metadata = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                records = tryNativeCatalog(metadata);
                if (records == null) records = JsonUtils.GSON.fromJson(metadata, JsonUtils.listTypeOf(LiteLoaderRemoteVersionRecord.class));
            }

            var versions = new TreeSet<LiteLoaderRemoteVersion>();
            for (var record : records) {
                if (GameVersionNumber.asGameVersion(record.gameVersion).equals(gameVersion)) {
                    versions.add(new LiteLoaderRemoteVersion(
                            gameVersion,
                            record.version,
                            record.snapshot ? Type.SNAPSHOT : Type.RELEASE,
                            List.of(record.url),
                            record.tweakClass,
                            List.copyOf(record.libraries)
                    ));
                }
            }
            return ComponentRemoteVersionList.of(GameComponentType.LITELOADER, versions);
        });
    }

    static @Nullable List<LiteLoaderRemoteVersionRecord> tryNativeCatalog(String metadata) throws IOException {
        @Nullable String binary = System.getProperty("mfcl.native.path");
        if (binary == null || binary.isBlank() || !Boolean.parseBoolean(System.getProperty("mfcl.native.loaders.enabled", "true"))) return null;
        try {
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(metadata) || metadata.getBytes(StandardCharsets.UTF_8).length > 768 * 1024) return null;
            var result = NativeBackend.parseLiteLoaderCatalog(Path.of(binary), metadata);
            for (String marker : List.of("library_v3", "rules_v1")) {
                var value = result.get(marker);
                if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean() || !value.getAsBoolean()) return null;
            }
            var records = result.getAsJsonArray("records");
            if (records == null || records.size() > 20_000) return null;
            List<LiteLoaderRemoteVersionRecord> output = new ArrayList<>(records.size());
            for (var value : records) {
                var record = value.getAsJsonObject();
                var snapshot = record.get("snapshot");
                if (snapshot == null || !snapshot.isJsonPrimitive() || !snapshot.getAsJsonPrimitive().isBoolean()) return null;
                var libraries = NativeGameManifest.decodeNativeLibraries(record.get("libraries"));
                if (libraries == null) return null;
                output.add(new LiteLoaderRemoteVersionRecord(nativeString(record, "gameVersion"), nativeString(record, "version"),
                        snapshot.getAsBoolean(), nativeString(record, "url"), nativeString(record, "tweakClass"), libraries));
            }
            return List.copyOf(output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new InterruptedIOException("LiteLoader catalog parsing was interrupted");
        } catch (IOException | RuntimeException e) { return null; }
    }

    private static String nativeString(com.google.gson.JsonObject object, String key) throws IOException {
        var value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Invalid LiteLoader field");
        return value.getAsString();
    }

    private final String tweakClass;
    private final Collection<Library> libraries;

    /**
     * Constructor.
     *
     * @param gameVersion the Minecraft version that this remote version suits.
     * @param selfVersion the version string of the remote version.
     * @param urls        the installer or universal jar original URL.
     */
    LiteLoaderRemoteVersion(GameVersionNumber gameVersion, String selfVersion, Type type, List<String> urls, String tweakClass, Collection<Library> libraries) {
        super(GameComponentType.LITELOADER, gameVersion, selfVersion, null, type, urls);

        this.tweakClass = tweakClass;
        this.libraries = libraries;
    }

    public Collection<Library> getLibraries() {
        return libraries;
    }

    public String getTweakClass() {
        return tweakClass;
    }

    @Override
    public Task<GameInstancePatch> getInstallTask(DefaultDependencyManager dependencyManager, GameInstanceManifest baseManifest, Path modsDirectory) {
        return new LiteLoaderInstallTask(dependencyManager, baseManifest, this);
    }
}
