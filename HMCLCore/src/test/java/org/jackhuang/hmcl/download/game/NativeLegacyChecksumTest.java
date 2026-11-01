/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.game;

import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Compares native legacy library checksum handling with the existing Java verifier.
@NotNullByDefault
@EnabledOnOs(OS.LINUX)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class NativeLegacyChecksumTest {
    /// Isolated library files.
    @TempDir Path temp;
    /// Previous backend path.
    private final @Nullable String previousPath = System.getProperty("mfcl.native.path");
    /// Previous native library setting.
    private final @Nullable String previousEnabled = System.getProperty("mfcl.native.libraries.enabled");
    /// Previous pure-request pool setting.
    private final @Nullable String previousPool = System.getProperty("mfcl.native.pool.enabled");
    /// Embedded checksum list used in fixtures.
    private static final String LISTING = "a9993e364706816aba3e25717850c26c9cd0d89d data.bin\n";

    /// Restores process-wide settings.
    @AfterEach void restore() {
        if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
        if (previousEnabled == null) System.clearProperty("mfcl.native.libraries.enabled"); else System.setProperty("mfcl.native.libraries.enabled", previousEnabled);
        if (previousPool == null) System.clearProperty("mfcl.native.pool.enabled"); else System.setProperty("mfcl.native.pool.enabled", previousPool);
    }

    /// Direct checksums, embedded checksums and stale observations retain Java behavior.
    @Test void verifiesSimpleLibrariesAndDefersManifestJars() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"); assumeTrue(executable != null && !executable.isBlank());
        System.setProperty("mfcl.native.path", executable); System.clearProperty("mfcl.native.libraries.enabled");
        Path good = temp.resolve("good.jar"), bad = temp.resolve("bad.jar"), manifest = temp.resolve("manifest.jar");
        makeJar(good, "abc", false); makeJar(bad, "wrong", false); makeJar(manifest, "abc", true);
        String listingHash = DigestUtils.digestToString("SHA-1", LISTING.getBytes(StandardCharsets.UTF_8));
        String directHash = DigestUtils.digestToString("SHA-1", Files.readAllBytes(good));
        @Nullable NativeBackend.LegacyChecksumResult correct = NativeBackend.checkLegacyLibrary(Path.of(executable), good, List.of(listingHash), true);
        @Nullable NativeBackend.LegacyChecksumResult wrong = NativeBackend.checkLegacyLibrary(Path.of(executable), bad, List.of(listingHash), true);
        assertNotNull(correct); assertTrue(correct.valid()); assertTrue(NativeBackend.isLegacyChecksumObservationCurrent(good, correct));
        assertNotNull(wrong); assertFalse(wrong.valid());
        assertNull(NativeBackend.checkLegacyLibrary(Path.of(executable), manifest, List.of(listingHash), true));
        for (Path file : List.of(good, bad, manifest)) {
            for (String expected : List.of(listingHash, directHash, directHash.toUpperCase(Locale.ROOT))) {
                System.setProperty("mfcl.native.libraries.enabled", "false");
                boolean javaResult = LibraryDownloadTask.checksumValid(file, List.of(expected));
                System.clearProperty("mfcl.native.libraries.enabled");
                assertEquals(javaResult, LibraryDownloadTask.checksumValid(file, List.of(expected)));
            }
        }
        Path wrapper = temp.resolve("backend");
        Files.writeString(wrapper, "#!/bin/sh\nIFS= read -r line\ncase \"$line\" in *check_legacy_library*) : > \"$0.checked\" ;; esac\n"
                + "printf '%s\\n' \"$line\" | exec '" + executable.replace("'", "'\\''") + "'\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        System.setProperty("mfcl.native.path", wrapper.toString()); System.setProperty("mfcl.native.pool.enabled", "false");
        assertTrue(LibraryDownloadTask.checksumValid(good, List.of(listingHash)));
        assertTrue(Files.exists(Path.of(wrapper + ".checked")), "Library verification did not reach Rust");
        Files.writeString(good, "changed size");
        assertFalse(NativeBackend.isLegacyChecksumObservationCurrent(good, correct));
        assertTrue(LibraryDownloadTask.checksumValid(temp.resolve("missing"), null));
    }

    /// Writes a simple JAR with known local-header sizes, optionally including a manifest.
    /// @param file destination archive
    /// @param payload contents of the referenced entry
    /// @param manifest whether to include a manifest requiring Java fallback
    /// @throws IOException on archive write failure
    private static void makeJar(Path file, String payload, boolean manifest) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(file))) {
            if (manifest) entry(output, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n");
            entry(output, "data.bin", payload); entry(output, "checksums.sha1", LISTING);
        }
    }

    /// Writes one stored entry with its size and CRC in the local header.
    /// @param output destination archive
    /// @param name entry name
    /// @param content UTF-8 entry contents
    /// @throws IOException on archive write failure
    private static void entry(ZipOutputStream output, String name, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8); CRC32 crc = new CRC32(); crc.update(bytes);
        ZipEntry entry = new ZipEntry(name); entry.setMethod(ZipEntry.STORED); entry.setSize(bytes.length); entry.setCompressedSize(bytes.length); entry.setCrc(crc.getValue());
        output.putNextEntry(entry); output.write(bytes); output.closeEntry();
    }
}
