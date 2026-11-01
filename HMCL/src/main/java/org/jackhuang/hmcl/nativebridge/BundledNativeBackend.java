/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.nativebridge;

import org.jackhuang.hmcl.util.platform.Architecture;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/// Extracts the matching packaged Rust backend into a content-addressed launcher-owned directory.
@NotNullByDefault
public final class BundledNativeBackend {
    /// Maximum accepted bundled executable size.
    private static final int MAX_BINARY_BYTES = 64 * 1024 * 1024;

    /// Prevents instantiation.
    private BundledNativeBackend() { }

    /// Finds and installs the resource matching the current JVM operating system and architecture.
    ///
    /// @param home isolated launcher home directory
    /// @return executable path, or empty if no resource is packaged for this platform
    /// @throws IOException if extraction, validation or executable permissions fail
    public static Optional<Path> installForCurrentPlatform(Path home) throws IOException {
        @Nullable String platform = platform(OperatingSystem.CURRENT_OS, Architecture.CURRENT_ARCH);
        if (platform == null) return Optional.empty();
        String name = OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS ? "mfcl-native.exe" : "mfcl-native";
        String resource = "/assets/mfcl/native/" + platform + "/" + name;
        try (@Nullable InputStream input = BundledNativeBackend.class.getResourceAsStream(resource)) {
            if (input == null) return Optional.empty();
            return Optional.of(install(home, input, name));
        }
    }

    /// Maps supported JVM platforms to the matching packaged resource directory.
    ///
    /// @param os current operating system
    /// @param arch current JVM architecture
    /// @return resource directory name, or `null` for unsupported platforms
    static @Nullable String platform(OperatingSystem os, Architecture arch) {
        String osName = switch (os) {
            case LINUX -> "linux";
            case MACOS -> "macos";
            case WINDOWS -> "windows";
            default -> "";
        };
        String archName = switch (arch) {
            case X86_64 -> "x86_64";
            case ARM64 -> "aarch64";
            default -> "";
        };
        return osName.isEmpty() || archName.isEmpty() ? null : osName + "-" + archName;
    }

    /// Installs a bounded executable without replacing an existing mismatched cache file.
    ///
    /// The resource hash is part of the cache name and cached content is checked before reuse.
    /// The caller owns and closes the input stream.
    ///
    /// @param home isolated launcher home
    /// @param input packaged executable bytes
    /// @param name platform executable name
    /// @return extracted executable path
    /// @throws IOException if validation or file operations fail
    static Path install(Path home, InputStream input, String name) throws IOException {
        if (!name.equals("mfcl-native") && !name.equals("mfcl-native.exe")) throw new IOException("Invalid native executable name");
        byte[] binary = input.readNBytes(MAX_BINARY_BYTES + 1);
        if (binary.length == 0 || binary.length > MAX_BINARY_BYTES) throw new IOException("Invalid bundled backend size");
        String hash = HexFormat.of().formatHex(sha256().digest(binary));
        Path directory = home.toAbsolutePath().normalize().resolve("native");
        Files.createDirectories(directory);
        String suffix = name.endsWith(".exe") ? ".exe" : "";
        Path target = directory.resolve("mfcl-native-" + hash + suffix);
        if (validCached(target, hash)) return executable(target);
        Path temporary = Files.createTempFile(directory, ".mfcl-native-", suffix.isEmpty() ? ".tmp" : suffix);
        boolean keep = false;
        try {
            Files.write(temporary, binary);
            executable(temporary);
            try {
                Files.createLink(target, temporary);
                Files.delete(temporary);
                return executable(target);
            } catch (UnsupportedOperationException | IOException e) {
                if (validCached(target, hash)) return executable(target);
                // Keep the uniquely named file when links are unsupported or the cache name is occupied.
                keep = true;
                return temporary;
            }
        } finally {
            if (!keep) Files.deleteIfExists(temporary);
        }
    }

    /// Validates that a cache entry is a regular non-symlink file with the expected content hash.
    ///
    /// @param path cache entry
    /// @param expected expected SHA-256 digest
    /// @return whether the entry is valid
    private static boolean validCached(Path path, String expected) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false;
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            MessageDigest digest = sha256();
            byte[] buffer = new byte[65536];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_BINARY_BYTES) return false;
                digest.update(buffer, 0, count);
            }
            return expected.equals(HexFormat.of().formatHex(digest.digest()));
        } catch (IOException e) {
            return false;
        }
    }

    /// Ensures that a backend file is executable for the current user.
    ///
    /// @param path backend path
    /// @return the same path
    /// @throws IOException if execution is not permitted
    private static Path executable(Path path) throws IOException {
        if (!Files.isExecutable(path) && !path.toFile().setExecutable(true, true)) {
            throw new IOException("Cannot make bundled backend executable");
        }
        if (!Files.isExecutable(path)) throw new IOException("Bundled backend is not executable");
        return path;
    }

    /// Creates the standard SHA-256 digest implementation.
    ///
    /// @return a new SHA-256 digest
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }
}
