/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.forge;

import org.jackhuang.hmcl.game.Artifact;
import org.jackhuang.hmcl.game.DefaultGameRepositoryLayout;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Verifies native processor main-class and classpath preparation.
@NotNullByDefault
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class NativeProcessorCommandTest {
    /// Isolated repository files.
    @TempDir Path temp;
    /// Previous backend path.
    private final @Nullable String previousPath = System.getProperty("mfcl.native.path");
    /// Previous processor integration setting.
    private final @Nullable String previousEnabled = System.getProperty("mfcl.native.processors.enabled");
    private final @Nullable String previousPool = System.getProperty("mfcl.native.pool.enabled");

    /// Restores global process settings.
    @AfterEach void restore() {
        if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
        if (previousEnabled == null) System.clearProperty("mfcl.native.processors.enabled"); else System.setProperty("mfcl.native.processors.enabled", previousEnabled);
        if (previousPool == null) System.clearProperty("mfcl.native.pool.enabled"); else System.setProperty("mfcl.native.pool.enabled", previousPool);
    }

    /// Native results match Java for a real processor JAR and ordered dependencies.
    @Test void preparesProcessorCommandAndFallsBackForUnsupportedFiles() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"); assumeTrue(executable != null && !executable.isBlank());
        System.setProperty("mfcl.native.path", executable); System.clearProperty("mfcl.native.processors.enabled");
        var layout = new DefaultGameRepositoryLayout(temp.resolve("游戏 with space"));
        Artifact processor = Artifact.fromDescriptor("test.group:processor:1");
        Artifact first = Artifact.fromDescriptor("test.group:first:1"), second = Artifact.fromDescriptor("test.group:second:1");
        Path jar = layout.getArtifactFile(processor), firstPath = layout.getArtifactFile(first), secondPath = layout.getArtifactFile(second);
        Files.createDirectories(jar.getParent()); Files.createDirectories(firstPath.getParent()); Files.createDirectories(secondPath.getParent());
        Manifest manifest = new Manifest(); manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "example.Main");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) { }
        Files.writeString(firstPath, "first"); Files.writeString(secondPath, "second");
        try (JarFile javaJar = new JarFile(jar.toFile())) {
            assertEquals(javaJar.getManifest().getMainAttributes().getValue(Attributes.Name.MAIN_CLASS),
                    NativeProcessorCommand.tryMainClass(jar, () -> false));
        }
        assertEquals(String.join(File.pathSeparator, firstPath.toString(), secondPath.toString(), jar.toString()),
                NativeProcessorCommand.tryClasspath(jar, List.of(first, second), layout, () -> false));
        Path wrapper = temp.resolve("combined-backend");
        Files.writeString(wrapper, "#!/bin/sh\nread line\nprintf '%s\\n' \"$line\" >> \"$0.requests\"\nprintf '%s\\n' \"$line\" | exec '"
                + executable.replace("'", "'\\''") + "'\n");
        assertTrue(wrapper.toFile().setExecutable(true)); System.setProperty("mfcl.native.path", wrapper.toString());
        System.setProperty("mfcl.native.pool.enabled", "false");
        @Nullable NativeProcessorCommand.Prepared combined = NativeProcessorCommand.tryCombined(jar, List.of(first, second), layout,
                List.of("hello-{SIDE}", "[test.group:first:1]", "{missing}"), Map.of("SIDE", "client"), () -> false);
        assertNotNull(combined, () -> "Combined response missing; requests=" + readRequests(wrapper)); assertEquals("example.Main", combined.mainClass());
        assertEquals(String.join(File.pathSeparator, firstPath.toString(), secondPath.toString(), jar.toString()), combined.classpath());
        assertEquals(java.util.Arrays.asList("hello-client", firstPath.toString(), null), combined.arguments());
        String requests = Files.readString(Path.of(wrapper + ".requests"));
        assertTrue(requests.contains(File.separatorChar == '/' ? "\"processor_command_v3\"" : "\"processor_command\""));
        if (File.separatorChar == '/') {
            assertTrue(requests.contains("\"artifact_root\"")); assertFalse(requests.contains("\"artifacts\""));
            assertTrue(requests.contains("\"coordinates\"")); assertFalse(requests.contains("\"libraries\""));
        }
        assertFalse(requests.contains("\"processor_main_class\"")); assertFalse(requests.contains("\"processor_classpath\""));
        System.setProperty("mfcl.native.path", executable);
        Files.delete(secondPath);
        assertNull(NativeProcessorCommand.tryClasspath(jar, List.of(first, second), layout, () -> false));
        var custom = new DefaultGameRepositoryLayout(temp) {
            /// Overrides artifact paths to exercise the compatibility fallback.
            @Override public Path getArtifactFile(Artifact artifact) { return temp.resolve("custom").resolve(artifact.getFileName()); }
        };
        assertNull(NativeProcessorCommand.tryClasspath(jar, List.of(first), custom, () -> false));
        Path folded = temp.resolve("folded.jar"); writeFoldedManifest(folded);
        try (JarFile javaJar = new JarFile(folded.toFile())) {
            assertEquals("example.Main", javaJar.getManifest().getMainAttributes().getValue(Attributes.Name.MAIN_CLASS));
        }
        assertEquals("example.Main", NativeProcessorCommand.tryMainClass(folded, () -> false));
        assertThrows(InterruptedException.class, () -> NativeProcessorCommand.tryMainClass(jar, () -> true));
    }

    /// Writes a valid JAR with a folded main-class attribute.
    /// @param file destination JAR
    /// @throws IOException if writing fails
    private static void writeFoldedManifest(Path file) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(file))) {
            output.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            output.write("Manifest-Version: 1.0\r\nMain-Class: example.\r\n Main\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private static String readRequests(Path wrapper) {
        try { return Files.readString(Path.of(wrapper + ".requests")); } catch (IOException e) { return e.toString(); }
    }
}
