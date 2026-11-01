/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.java.mojang;

import org.jackhuang.hmcl.util.gson.JsonUtils;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Checks runtime manifest boundaries before installation writes occur.
@NotNullByDefault
@EnabledOnOs(OS.LINUX)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class RuntimePathValidatorTest {
    /// Isolated staging and outside directories.
    @TempDir Path temp;
    /// Original backend path.
    private final @Nullable String oldPath = System.getProperty("mfcl.native.path");
    /// Original runtime integration switch.
    private final @Nullable String oldEnabled = System.getProperty("mfcl.native.java.enabled");

    /// Restores global test settings.
    @AfterEach void restore() {
        if (oldPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", oldPath);
        if (oldEnabled == null) System.clearProperty("mfcl.native.java.enabled"); else System.setProperty("mfcl.native.java.enabled", oldEnabled);
    }

    /// Native and Java validation allow internal links and reject path or link escapes.
    @Test void validatesPathsAndExistingLinks() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        System.setProperty("mfcl.native.path", executable);
        Path root = temp.resolve("runtime"); Files.createDirectories(root);
        var file = new MojangJavaRemoteFiles.RemoteFile(false, Map.of());
        var valid = List.<Map.Entry<String, MojangJavaRemoteFiles.Remote>>of(
                Map.entry("legal/base/LICENSE", file), Map.entry("legal/compiler/LICENSE", new MojangJavaRemoteFiles.RemoteLink("../base/LICENSE")));
        for (boolean nativeEnabled : new boolean[]{false, true}) {
            System.setProperty("mfcl.native.java.enabled", Boolean.toString(nativeEnabled));
            assertDoesNotThrow(() -> RuntimePathValidator.validate(root, valid, () -> false));
            assertThrows(IOException.class, () -> RuntimePathValidator.validate(temp.resolve("alias/../runtime"), valid, () -> false));
            assertThrows(IOException.class, () -> RuntimePathValidator.validate(root, List.of(Map.entry("../outside", file)), () -> false));
            assertThrows(IOException.class, () -> RuntimePathValidator.validate(root, List.of(Map.entry("/absolute", file)), () -> false));
            assertThrows(IOException.class, () -> RuntimePathValidator.validate(root,
                    List.of(Map.entry("bin/java", new MojangJavaRemoteFiles.RemoteLink("../../outside"))), () -> false));
            assertThrows(IOException.class, () -> RuntimePathValidator.validate(root,
                    List.of(Map.entry("bin", new MojangJavaRemoteFiles.RemoteLink("lib")), Map.entry("bin/java", file)), () -> false));
            assertThrows(IOException.class, () -> RuntimePathValidator.validate(root,
                    List.of(Map.entry("a", new MojangJavaRemoteFiles.RemoteLink("b")),
                            Map.entry("b", new MojangJavaRemoteFiles.RemoteLink("c"))), () -> false));
            assertThrows(IOException.class, () -> RuntimePathValidator.validate(root,
                    List.of(Map.entry("link", new MojangJavaRemoteFiles.RemoteLink("alias/../target"))), () -> false));
        }
        Path outside = temp.resolve("outside"); Files.createDirectories(outside); Files.writeString(outside.resolve("sentinel"), "unchanged");
        Files.createSymbolicLink(root.resolve("bin"), outside);
        assertThrows(IOException.class, () -> RuntimePathValidator.validate(root, List.of(Map.entry("bin/java", file)), () -> false));
        assertEquals("unchanged", Files.readString(outside.resolve("sentinel")));
    }

    /// The currently retrieved official Linux, Windows and macOS runtime manifests pass validation.
    @Test void officialRuntimeManifestShapesAreAccepted() throws Exception {
        @Nullable String fixtureDir = System.getenv("MFCL_RUNTIME_FIXTURES");
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(fixtureDir != null && !fixtureDir.isBlank() && executable != null && !executable.isBlank());
        System.setProperty("mfcl.native.path", executable); System.clearProperty("mfcl.native.java.enabled");
        for (String platform : List.of("linux", "windows", "mac")) {
            Path fixture = Path.of(fixtureDir).resolve("mojang-runtime-" + platform + ".json");
            @Nullable MojangJavaRemoteFiles manifest = JsonUtils.fromJsonFile(fixture, MojangJavaRemoteFiles.class);
            assertNotNull(manifest);
            assertDoesNotThrow(() -> RuntimePathValidator.validate(temp.resolve(platform), new ArrayList<>(manifest.files().entrySet()), () -> false));
        }
    }
}
