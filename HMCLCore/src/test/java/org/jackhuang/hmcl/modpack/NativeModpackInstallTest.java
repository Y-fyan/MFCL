/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.modpack;

import org.jackhuang.hmcl.util.DigestUtils;
import org.jackhuang.hmcl.modpack.modrinth.ModrinthModpackProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Checks native-staged modpack overrides against the Java update behavior.
@NotNullByDefault
@EnabledOnOs(OS.LINUX)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class NativeModpackInstallTest {
    /// Isolated test files.
    @TempDir Path temp;
    /// Original executable setting.
    private final @Nullable String oldPath = System.getProperty("mfcl.native.path");
    /// Original extraction setting.
    private final @Nullable String oldEnabled = System.getProperty("mfcl.native.natives.extract.enabled");
    /// Original modpack integration setting.
    private final @Nullable String oldModpack = System.getProperty("mfcl.native.modpack.enabled");

    /// Restores shared settings.
    @AfterEach void restore() {
        if (oldPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", oldPath);
        if (oldEnabled == null) System.clearProperty("mfcl.native.natives.extract.enabled"); else System.setProperty("mfcl.native.natives.extract.enabled", oldEnabled);
        if (oldModpack == null) System.clearProperty("mfcl.native.modpack.enabled"); else System.setProperty("mfcl.native.modpack.enabled", oldModpack);
    }

    /// Native ZIP hashes retain the existing Java tree order and update configuration values.
    @Test void nativeArchiveHashesMatchJavaConfiguration() throws Exception {
        @Nullable String backend = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(backend != null && !backend.isBlank());
        Path wrapper = temp.resolve("hash-backend");
        Files.writeString(wrapper, "#!/bin/sh\nread line\nprintf '%s\\n' \"$line\" > \"$0.call\"\nprintf '%s\\n' \"$line\" | '" + backend.replace("'", "'\\''") + "' | tee \"$0.response\"\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        Path archive = temp.resolve("hash-pack.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (String[] entry : new String[][]{{"overrides/a", "alpha"}, {"overrides/dir/b", "beta"}, {"client-overrides/c", "gamma"}}) {
                zip.putNextEntry(new ZipEntry(entry[0])); zip.write(entry[1].getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        System.setProperty("mfcl.native.path", wrapper.toString());
        System.setProperty("mfcl.native.modpack.enabled", "false");
        var javaTask = new MinecraftInstanceTask<>(archive, List.of("/client-overrides", "/overrides"), "manifest",
                ModrinthModpackProvider.INSTANCE, "test", "1", temp.resolve("java.json"));
        javaTask.execute();
        System.clearProperty("mfcl.native.modpack.enabled");
        var nativeTask = new MinecraftInstanceTask<>(archive, List.of("/client-overrides", "/overrides"), "manifest",
                ModrinthModpackProvider.INSTANCE, "test", "1", temp.resolve("native.json"));
        nativeTask.execute();
        assertTrue(Files.readString(Path.of(wrapper + ".call")).contains("hash_zip_entries"));
        assertTrue(Files.readString(Path.of(wrapper + ".response")).contains("\"sha1\""));
        var javaEntries = javaTask.getResult().getOverrides(); var nativeEntries = nativeTask.getResult().getOverrides();
        assertEquals(javaEntries.size(), nativeEntries.size());
        for (int i = 0; i < javaEntries.size(); i++) {
            assertEquals(javaEntries.get(i).getPath(), nativeEntries.get(i).getPath());
            assertEquals(javaEntries.get(i).getHash(), nativeEntries.get(i).getHash());
        }
    }

    /// Selected subtrees use Rust while preserving changed, deleted, filtered and obsolete overrides.
    @Test void nativeSubtreesPreserveUpdateSemantics() throws Exception {
        @Nullable String backend = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(backend != null && !backend.isBlank());
        Path wrapper = temp.resolve("backend");
        Files.writeString(wrapper, "#!/bin/sh\nread line\nprintf '%s\\n' \"$line\" >> \"$0.calls\"\nprintf '%s\\n' \"$line\" | exec '" + backend.replace("'", "'\\''") + "'\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        Path archive = temp.resolve("pack.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (String[] entry : new String[][]{{"overrides/unchanged.txt", "new"}, {"overrides/changed.txt", "new"},
                    {"overrides/missing.txt", "new"}, {"overrides/added.txt", "added"},
                    {"overrides/ignored.txt", "ignore"}, {"client-overrides/client.txt", "client"}, {"irrelevant/a.txt", "unused"}}) {
                zip.putNextEntry(new ZipEntry(entry[0])); zip.write(entry[1].getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        String oldHash = DigestUtils.digestToString("SHA-1", "old".getBytes(StandardCharsets.UTF_8));
        var config = new ModpackConfiguration<>("manifest", "test", "test", "1", List.of(
                new ModpackConfiguration.FileInformation("unchanged.txt", oldHash),
                new ModpackConfiguration.FileInformation("changed.txt", oldHash),
                new ModpackConfiguration.FileInformation("missing.txt", oldHash),
                new ModpackConfiguration.FileInformation("obsolete.txt", oldHash)));
        Path javaDir = temp.resolve("java"); Path nativeDir = temp.resolve("native");
        for (Path dest : List.of(javaDir, nativeDir)) {
            Files.createDirectories(dest); Files.writeString(dest.resolve("unchanged.txt"), "old");
            Files.writeString(dest.resolve("changed.txt"), "user"); Files.writeString(dest.resolve("obsolete.txt"), "old");
        }
        System.setProperty("mfcl.native.path", wrapper.toString());
        System.setProperty("mfcl.native.natives.extract.enabled", "false");
        new ModpackInstallTask<>(archive, javaDir, List.of("/client-overrides", "/overrides"), name -> !name.equals("ignored.txt"), config).execute();
        System.clearProperty("mfcl.native.natives.extract.enabled");
        new ModpackInstallTask<>(archive, nativeDir, List.of("/client-overrides", "/overrides"), name -> !name.equals("ignored.txt"), config).execute();
        assertEquals(2, Files.readAllLines(Path.of(wrapper + ".calls")).stream().filter(line -> line.contains("extract_zip_prefix")).count());
        for (String name : List.of("unchanged.txt", "changed.txt", "added.txt", "client.txt"))
            assertEquals(Files.readString(javaDir.resolve(name)), Files.readString(nativeDir.resolve(name)));
        assertEquals("new", Files.readString(nativeDir.resolve("unchanged.txt")));
        assertEquals("user", Files.readString(nativeDir.resolve("changed.txt")));
        for (String name : List.of("missing.txt", "obsolete.txt", "ignored.txt")) assertFalse(Files.exists(nativeDir.resolve(name)));
    }
}
