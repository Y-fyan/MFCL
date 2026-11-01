/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.java.mojang;

import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jackhuang.hmcl.util.io.ChecksumMismatchException;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.function.BooleanSupplier;

/// Stages verified Mojang runtime LZMA decompression through the native backend.
@NotNullByDefault
final class NativeRuntimeLzma {
    /// Prevents instantiation.
    private NativeRuntimeLzma() { }

    /// Decompresses into a private stage and retains Java validation and publication.
    /// @param compressed downloaded LZMA source
    /// @param output installer-owned temporary output
    /// @param sha1 expected raw digest, or null when unavailable
    /// @param size expected raw byte count
    /// @param cancelled owning task cancellation predicate
    /// @return true when the verified temporary output was produced
    /// @throws InterruptedException if cancelled
    static boolean tryDecompress(Path compressed, Path output, @Nullable String sha1, int size, BooleanSupplier cancelled)
            throws InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank() || !Boolean.parseBoolean(System.getProperty("mfcl.native.java.enabled", "true"))
                || OperatingSystem.CURRENT_OS != OperatingSystem.LINUX
                || sha1 == null || !DigestUtils.isSha1Digest(sha1) || size <= 0
                || compressed.getFileSystem() != FileSystems.getDefault() || output.getFileSystem() != FileSystems.getDefault()) return false;
        @Nullable Path stageDirectory = null;
        try {
            @Nullable Path parent = output.toAbsolutePath().getParent();
            if (parent == null || !Files.isDirectory(parent)) return false;
            stageDirectory = Files.createTempDirectory(parent, ".mfcl-runtime-stage-");
            Path staged = stageDirectory.resolve("payload");
            // An ordinary probe captures the permissions Java's stream copy would receive.
            Path probe = Files.createFile(stageDirectory.resolve("permissions-probe"));
            NativeBackend.stageRuntimeLzma(Path.of(executable), compressed, staged, probe, sha1, size, cancelled);
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            ChecksumMismatchException.verifyChecksum(staged, "SHA-1", sha1);
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            // Files.copy(InputStream, Path) creates normal default permissions, unlike a private tempfile.
            @Nullable PosixFileAttributeView stagedPosix = Files.getFileAttributeView(staged, PosixFileAttributeView.class);
            if (stagedPosix != null) {
                stagedPosix.setPermissions(Files.getPosixFilePermissions(probe));
            }
            Files.move(staged, output, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | RuntimeException e) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            return false;
        } finally {
            if (stageDirectory != null) FileUtils.deleteDirectoryQuietly(stageDirectory);
        }
    }
}
