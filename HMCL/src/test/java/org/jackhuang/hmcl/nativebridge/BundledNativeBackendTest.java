/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.nativebridge;

import org.jackhuang.hmcl.util.platform.Architecture;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies safe extraction and reuse of bundled native executables.
@NotNullByDefault
@EnabledOnOs({OS.LINUX, OS.MAC})
public final class BundledNativeBackendTest {
    /// Isolated launcher home.
    @TempDir Path tempDir;
    /// Synthetic executable content.
    private static final byte[] BYTES = "fixture-binary".getBytes(StandardCharsets.UTF_8);

    /// Valid cached content is reused by its content-addressed name.
    @Test void extractsAndReusesValidContent() throws Exception {
        Path first = install();
        Path second = install();
        assertEquals(first, second); assertArrayEquals(BYTES, Files.readAllBytes(first));
        assertTrue(Files.isExecutable(first)); assertTrue(first.getFileName().toString().startsWith("mfcl-native-"));
    }

    /// A corrupt cache entry is not overwritten and the correct content uses a unique path.
    @Test void doesNotOverwriteCorruptCacheEntry() throws Exception {
        Path first = install(); Files.writeString(first, "unrelated data");
        Path second = install();
        assertNotEquals(first, second); assertEquals("unrelated data", Files.readString(first));
        assertArrayEquals(BYTES, Files.readAllBytes(second)); assertTrue(Files.isExecutable(second));
    }

    /// A symlink occupying the content-addressed name is not followed or replaced.
    @Test void doesNotFollowCacheSymlink() throws Exception {
        Path first = install(); Files.delete(first);
        Path unrelated = tempDir.resolve("unrelated"); Files.writeString(unrelated, "keep");
        Files.createSymbolicLink(first, unrelated);
        Path second = install();
        assertNotEquals(first, second); assertEquals("keep", Files.readString(unrelated));
        assertTrue(Files.isSymbolicLink(first)); assertArrayEquals(BYTES, Files.readAllBytes(second));
    }

    /// Empty content and unknown executable names are rejected before creating files.
    @Test void rejectsInvalidResource() {
        assertThrows(IOException.class, () -> BundledNativeBackend.install(tempDir, new ByteArrayInputStream(new byte[0]), "mfcl-native"));
        assertThrows(IOException.class, () -> BundledNativeBackend.install(tempDir, new ByteArrayInputStream(BYTES), "other"));
        assertFalse(Files.exists(tempDir.resolve("native")));
    }

    /// Platform directory naming matches the Gradle packaging conventions.
    @Test void mapsSupportedPlatforms() {
        assertEquals("linux-x86_64", BundledNativeBackend.platform(OperatingSystem.LINUX, Architecture.X86_64));
        assertEquals("macos-aarch64", BundledNativeBackend.platform(OperatingSystem.MACOS, Architecture.ARM64));
        assertEquals("windows-x86_64", BundledNativeBackend.platform(OperatingSystem.WINDOWS, Architecture.X86_64));
        assertNull(BundledNativeBackend.platform(OperatingSystem.FREEBSD, Architecture.X86_64));
    }

    /// The built resource extracts and responds to a health check on this supported platform.
    @Test void packagedBackendPassesHealthCheck() throws Exception {
        Optional<Path> backend = BundledNativeBackend.installForCurrentPlatform(tempDir);
        boolean expected = Boolean.parseBoolean(System.getProperty("mfcl.test.native.bundle.expected", "true"));
        assertEquals(expected, backend.isPresent(), "Bundled backend availability does not match build configuration");
        if (backend.isPresent()) NativeBackend.checkHealth(backend.orElseThrow());
    }

    /// Installs the synthetic executable into the test-owned home.
    ///
    /// @return extracted executable path
    /// @throws IOException if installation fails
    private Path install() throws IOException {
        return BundledNativeBackend.install(tempDir, new ByteArrayInputStream(BYTES), "mfcl-native");
    }
}
