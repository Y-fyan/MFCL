/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.nativebridge;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/// Test-only access to loopback HTTP for the native transfer integration fixture.
@NotNullByDefault
public final class NativeTransferTestSupport {
    /// Prevents instantiation.
    private NativeTransferTestSupport() { }

    /// Shuts down idle test workers so their temporary executables can be removed.
    public static void clearIdle() { NativeDownloadPool.clearIdle(); }

    /// Downloads from a loopback-only fixture through the real native executable.
    ///
    /// @param executable native executable
    /// @param url loopback fixture URL
    /// @param destination temporary output path
    /// @param sha1 expected digest, or null for independent validation
    /// @param progress progress callback
    /// @param cancelled cancellation predicate
    /// @return bytes transferred
    /// @throws IOException on transfer failure
    /// @throws InterruptedException on cancellation
    public static long loopback(Path executable, String url, Path destination, @Nullable String sha1,
                                BiConsumer<Long, Long> progress, BooleanSupplier cancelled) throws IOException, InterruptedException {
        return NativeTransfer.download(executable, url, destination, sha1, 1024, progress, cancelled, true);
    }
}
