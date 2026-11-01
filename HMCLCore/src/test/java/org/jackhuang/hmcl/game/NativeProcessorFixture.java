/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.game;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/// Minimal test-owned installer processor executed from an isolated JAR.
@NotNullByDefault
public final class NativeProcessorFixture {
    /// Prevents instantiation.
    private NativeProcessorFixture() { }

    /// Writes a fixture value to the output path supplied by the installer.
    /// @param args output path followed by file contents
    /// @throws IOException if the test output cannot be written
    public static void main(String @Unmodifiable [] args) throws IOException {
        Files.writeString(Path.of(args[0]), args[1]);
    }
}
