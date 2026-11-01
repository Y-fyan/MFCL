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
package org.jackhuang.hmcl.modpack;

import kala.compress.archivers.zip.ZipArchiveEntry;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.CompressingUtils;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jackhuang.hmcl.util.tree.ArchiveFileTree;
import org.jackhuang.hmcl.util.tree.ZipFileTree;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class MinecraftInstanceTask<T> extends Task<ModpackConfiguration<T>> {

    private final Path zipFile;
    private final List<String> subDirectories;
    private final Path jsonFile;
    private final T manifest;
    private final String type;
    private final String name;
    private final String version;

    public MinecraftInstanceTask(Path zipFile, List<String> subDirectories, T manifest, ModpackProvider modpackProvider, String name, String version, Path jsonFile) {
        this.zipFile = zipFile;
        this.subDirectories = subDirectories.stream().map(FileUtils::normalizePath).toList();
        this.manifest = manifest;
        this.jsonFile = jsonFile;
        this.type = modpackProvider.getName();
        this.name = name;
        this.version = version;
    }

    /// One archive entry and its relative override path, retaining the Java tree traversal order.
    @NotNullByDefault
    private record OverrideEntry(String path, ZipArchiveEntry entry) { }

    /// Captures the archive identity required before accepting native observations.
    @NotNullByDefault
    private record ArchiveIdentity(BasicFileAttributes attributes, Object changed) { }

    /// Collects entries in the same order as the original recursive hash operation.
    /// @param overrides output entries
    /// @param dir current archive tree directory
    /// @param names path components relative to the selected override root
    private static void getOverrides(List<OverrideEntry> overrides,
                                     ArchiveFileTree.Dir<ZipArchiveEntry> dir,
                                     List<String> names) {
        String prefix = String.join("/", names);
        if (!prefix.isEmpty())
            prefix = prefix + "/";

        for (Map.Entry<String, ZipArchiveEntry> entry : dir.getFiles().entrySet()) {
            overrides.add(new OverrideEntry(prefix + entry.getKey(), entry.getValue()));
        }

        for (ArchiveFileTree.Dir<ZipArchiveEntry> subDir : dir.getSubDirs().values()) {
            names.add(subDir.getName());
            getOverrides(overrides, subDir, names);
            names.remove(names.size() - 1);
        }
    }

    /// Reads stable identity attributes, or null on unsupported filesystems.
    /// @return archive identity if available
    private @Nullable ArchiveIdentity archiveIdentity() {
        if (zipFile.getFileSystem() != FileSystems.getDefault()) return null;
        try {
            BasicFileAttributes attributes = Files.readAttributes(zipFile, BasicFileAttributes.class);
            @Nullable Object changed = Files.getAttribute(zipFile, "unix:ctime");
            if (!attributes.isRegularFile() || attributes.fileKey() == null || changed == null) return null;
            return new ArchiveIdentity(attributes, changed);
        } catch (IOException | RuntimeException e) { return null; }
    }

    /// Attempts to hash the selected entries in Rust when the source identity remains unchanged.
    /// @param before identity captured before opening the Java archive reader
    /// @param entries ordered override entries
    /// @return native hashes, or null when the existing Java reader must be used
    /// @throws InterruptedException if cancelled
    private @Nullable List<String> nativeHashes(@Nullable ArchiveIdentity before, List<OverrideEntry> entries) throws InterruptedException {
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (before == null || executable == null || executable.isBlank()
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.modpack.enabled", "true"))) return null;
        if (entries.isEmpty()) return List.of();
        try {
            List<String> hashes = NativeBackend.hashZipEntries(Path.of(executable), zipFile,
                    entries.stream().map(entry -> entry.entry().getName()).toList(), this::isCancelled);
            @Nullable ArchiveIdentity after = archiveIdentity();
            if (after == null || before.attributes().size() != after.attributes().size()
                    || !before.attributes().lastModifiedTime().equals(after.attributes().lastModifiedTime())
                    || !before.attributes().fileKey().equals(after.attributes().fileKey())
                    || !before.changed().equals(after.changed())) return null;
            return hashes;
        } catch (IOException | RuntimeException e) { return null; }
    }

    @Override
    public void execute() throws Exception {
        List<ModpackConfiguration.FileInformation> overrides = new ArrayList<>();
        @Nullable ArchiveIdentity before = archiveIdentity();
        try (var tree = new ZipFileTree(CompressingUtils.openZipFile(zipFile))) {
            List<OverrideEntry> entries = new ArrayList<>();
            for (String subDirectory : subDirectories) {
                ArchiveFileTree.Dir<ZipArchiveEntry> root = tree.getDirectory(subDirectory);
                if (root == null)
                    continue;
                var names = new ArrayList<String>();
                getOverrides(entries, root, names);
            }
            @Nullable List<String> hashes = nativeHashes(before, entries);
            for (int index = 0; index < entries.size(); index++) {
                OverrideEntry entry = entries.get(index);
                String hash;
                if (hashes != null) hash = hashes.get(index);
                else try (InputStream input = tree.getInputStream(entry.entry())) { hash = DigestUtils.digestToString("SHA-1", input); }
                overrides.add(new ModpackConfiguration.FileInformation(entry.path(), hash));
            }
        }
        ModpackConfiguration<T> configuration = new ModpackConfiguration<>(manifest, type, name, version, overrides);
        Files.createDirectories(jsonFile.getParent());
        JsonUtils.writeToJsonFile(jsonFile, configuration);
        setResult(configuration);
    }
}
