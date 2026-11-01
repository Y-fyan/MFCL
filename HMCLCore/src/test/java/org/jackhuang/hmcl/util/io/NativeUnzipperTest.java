/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.util.io;

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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Compares native-staged extraction with the existing Java unzipper.
@NotNullByDefault
@EnabledOnOs(OS.LINUX)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
public final class NativeUnzipperTest {
    /// Isolated fixture directory.
    @TempDir Path temp;
    /// Previous backend path.
    private final @Nullable String oldPath = System.getProperty("mfcl.native.path");
    /// Previous extraction setting.
    private final @Nullable String oldEnabled = System.getProperty("mfcl.native.natives.extract.enabled");

    /// Restores process properties.
    @AfterEach void restore() {
        if (oldPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", oldPath);
        if (oldEnabled == null) System.clearProperty("mfcl.native.natives.extract.enabled"); else System.setProperty("mfcl.native.natives.extract.enabled", oldEnabled);
    }

    /// Native decompression retains filtering and pre-existing file conflict semantics.
    @Test void nativeStagePreservesJavaExtractionRules() throws Exception {
        @Nullable String backend = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(backend != null && !backend.isBlank());
        Path wrapper = temp.resolve("backend");
        Files.writeString(wrapper, "#!/bin/sh\nread line\nprintf '%s' \"$line\" > \"$0.called\"\nprintf '%s\\n' \"$line\" | exec '" + backend.replace("'", "'\\''") + "'\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        System.setProperty("mfcl.native.path", wrapper.toString());
        System.clearProperty("mfcl.native.natives.extract.enabled");
        Path archive = archive(); Path javaDest = temp.resolve("java"); Path nativeDest = temp.resolve("native");
        Files.createDirectories(javaDest); Files.createDirectories(nativeDest);
        Files.writeString(javaDest.resolve("existing.so"), "old"); Files.writeString(nativeDest.resolve("existing.so"), "old");
        unzip(archive, javaDest, false); unzip(archive, nativeDest, true);
        assertTrue(Files.readString(Path.of(wrapper + ".called")).contains("extract_zip"));
        for (String file : new String[]{"nested/lib.so", "existing.so"})
            assertEquals(Files.readString(javaDest.resolve(file)), Files.readString(nativeDest.resolve(file)));
        assertFalse(Files.exists(nativeDest.resolve("ignore.sha1")));
        assertEquals("old", Files.readString(nativeDest.resolve("existing.so")));
    }

    /// An unavailable backend falls back before destination publication.
    @Test void backendFailureUsesJavaExtraction() throws Exception {
        System.setProperty("mfcl.native.path", temp.resolve("missing").toString());
        Path target = temp.resolve("fallback"); unzip(archive(), target, true);
        assertEquals("library", Files.readString(target.resolve("nested/lib.so")));
        assertFalse(Files.exists(target.resolve("ignore.sha1")));
    }

    /// A valid staged response is actually used as the source of published bytes.
    @Test void publishesValidatedStagedBytes() throws Exception {
        Path backend = temp.resolve("fake-backend");
        Files.writeString(backend, """
                #!/usr/bin/env python3
                import json, pathlib, sys
                request = json.loads(sys.stdin.readline())
                pathlib.Path(__file__ + '.parent').write_text(request['params']['staging_parent'])
                stage = pathlib.Path(request['params']['staging_parent']) / '.mfcl-extract-fixture'
                (stage / 'nested').mkdir(parents=True)
                (stage / 'nested/lib.so').write_text('LIBRARY')
                (stage / 'ignore.sha1').write_text('digest')
                (stage / 'existing.so').write_text('new')
                print(json.dumps({'version': 1, 'id': 1, 'result': {'staging_directory': str(stage)}}))
                """);
        assertTrue(backend.toFile().setExecutable(true));
        System.setProperty("mfcl.native.path", backend.toString());
        System.clearProperty("mfcl.native.natives.extract.enabled");
        Path target = temp.resolve("staged"); unzip(archive(), target, true);
        assertEquals("LIBRARY", Files.readString(target.resolve("nested/lib.so")));
        assertFalse(Files.exists(Path.of(Files.readString(Path.of(backend + ".parent")))));
    }

    /// Missing staged files trigger the original Java path and the temporary parent is removed.
    @Test void incompleteStageFallsBackAndIsRemoved() throws Exception {
        Path backend = temp.resolve("incomplete-backend");
        Files.writeString(backend, """
                #!/usr/bin/env python3
                import json, pathlib, sys
                request = json.loads(sys.stdin.readline())
                parent = pathlib.Path(request['params']['staging_parent'])
                pathlib.Path(__file__ + '.parent').write_text(str(parent))
                stage = parent / '.mfcl-extract-fixture'
                stage.mkdir()
                print(json.dumps({'version': 1, 'id': 1, 'result': {'staging_directory': str(stage)}}))
                """);
        assertTrue(backend.toFile().setExecutable(true));
        System.setProperty("mfcl.native.path", backend.toString());
        System.clearProperty("mfcl.native.natives.extract.enabled");
        Path target = temp.resolve("fallback-stage"); unzip(archive(), target, true);
        assertEquals("library", Files.readString(target.resolve("nested/lib.so")));
        assertFalse(Files.exists(Path.of(Files.readString(Path.of(backend + ".parent")))));
    }

    /// Runs the same filter used to exclude sidecar files for native libraries.
    /// @param archive ZIP fixture
    /// @param destination extraction directory
    /// @param nativeStage whether to enable Rust staging
    /// @throws IOException if extraction fails
    private static void unzip(Path archive, Path destination, boolean nativeStage) throws IOException {
        Unzipper unzipper = new Unzipper(archive, destination).setFilter((entry, path, relative) -> !relative.endsWith(".sha1"));
        if (nativeStage) unzipper.setNativeExtraction();
        unzipper.unzip();
    }

    /// Creates an archive with regular, filtered, and conflicting entries.
    /// @return archive path
    /// @throws IOException if fixture creation fails
    private Path archive() throws IOException {
        Path archive = temp.resolve("libs.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (String[] item : new String[][]{{"nested/lib.so", "library"}, {"ignore.sha1", "digest"}, {"existing.so", "new"}}) {
                zip.putNextEntry(new ZipEntry(item[0])); zip.write(item[1].getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        return archive;
    }
}
