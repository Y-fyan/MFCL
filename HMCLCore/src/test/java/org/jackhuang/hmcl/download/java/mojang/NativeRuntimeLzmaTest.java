/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.java.mojang;

import org.jackhuang.hmcl.nativebridge.NativeTransferTestSupport;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jackhuang.hmcl.util.CacheRepository;
import org.jackhuang.hmcl.download.DefaultCacheRepository;
import org.jackhuang.hmcl.download.DownloadCandidates;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.game.GameJavaVersion;
import org.jackhuang.hmcl.JavaFXLauncher;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.LZMAInputStream;
import org.tukaani.xz.LZMAOutputStream;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Compares native runtime LZMA staging with the existing Java decoder.
@NotNullByDefault
@EnabledOnOs(OS.LINUX)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("cache-repository")
final class NativeRuntimeLzmaTest {
    /// Isolated test files.
    @TempDir Path temp;
    /// Original native executable setting.
    private final @Nullable String oldPath = System.getProperty("mfcl.native.path");
    /// Original runtime setting.
    private final @Nullable String oldEnabled = System.getProperty("mfcl.native.java.enabled");
    /// Original worker pool setting.
    private final @Nullable String oldPool = System.getProperty("mfcl.native.pool.enabled");
    /// Original graphics configuration.
    private final @Nullable String oldPrism = System.getProperty("prism.order");
    /// Original process-wide download cache.
    private final CacheRepository oldCache = CacheRepository.getInstance();

    /// Restores global settings and closes idle workers.
    @AfterEach void restore() {
        NativeTransferTestSupport.clearIdle();
        if (oldPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", oldPath);
        if (oldEnabled == null) System.clearProperty("mfcl.native.java.enabled"); else System.setProperty("mfcl.native.java.enabled", oldEnabled);
        if (oldPool == null) System.clearProperty("mfcl.native.pool.enabled"); else System.setProperty("mfcl.native.pool.enabled", oldPool);
        if (oldPrism == null) System.clearProperty("prism.order"); else System.setProperty("prism.order", oldPrism);
        CacheRepository.setInstance(oldCache);
    }

    /// Valid streams, permissions, process reuse and malformed inputs preserve Java behavior.
    @Test void nativeStagingMatchesJavaAndRejectsCorruption() throws Exception {
        @Nullable String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(binary != null && !binary.isBlank());
        Path wrapper = temp.resolve("backend");
        Files.writeString(wrapper, "#!/bin/sh\nprintf '%s\\n' \"$$\" >> \"$0.pids\"\nexec '" + binary.replace("'", "'\\''") + "' \"$@\"\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        NativeTransferTestSupport.clearIdle(); System.setProperty("mfcl.native.path", wrapper.toString());
        System.clearProperty("mfcl.native.java.enabled"); System.clearProperty("mfcl.native.pool.enabled");
        byte[] data = new byte[128 * 1024 + 3]; for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 251);
        Path compressed = temp.resolve("runtime.lzma");
        try (var output = new LZMAOutputStream(Files.newOutputStream(compressed), new LZMA2Options(), data.length)) { output.write(data); }
        String hash = DigestUtils.digestToString("SHA-1", data);
        Path javaOutput = temp.resolve("java-output");
        try (var input = new LZMAInputStream(Files.newInputStream(compressed))) {
            Files.copy(input, javaOutput, StandardCopyOption.REPLACE_EXISTING);
        }
        for (int i = 0; i < 2; i++) {
            Path output = temp.resolve("native-output-" + i);
            assertTrue(NativeRuntimeLzma.tryDecompress(compressed, output, hash, data.length, () -> false));
            assertArrayEquals(Files.readAllBytes(javaOutput), Files.readAllBytes(output));
            assertEquals(Files.getPosixFilePermissions(javaOutput), Files.getPosixFilePermissions(output));
        }
        assertEquals(1, Files.readAllLines(Path.of(wrapper + ".pids")).size());
        Path preserved = temp.resolve("preserved"); Files.writeString(preserved, "old");
        assertFalse(NativeRuntimeLzma.tryDecompress(compressed, preserved, "0".repeat(40), data.length, () -> false));
        assertEquals("old", Files.readString(preserved));
        Files.writeString(compressed, "broken");
        assertFalse(NativeRuntimeLzma.tryDecompress(compressed, preserved, hash, data.length, () -> false));
        assertEquals("old", Files.readString(preserved));
        assertThrows(InterruptedException.class, () -> NativeRuntimeLzma.tryDecompress(compressed, preserved, hash, data.length, () -> true));
    }

    /// An optional directly retrieved official sample matches Java's LZMA decoder.
    @Test void officialSampleMatchesJava() throws Exception {
        @Nullable String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        @Nullable String sample = System.getenv("MFCL_RUNTIME_LZMA_SAMPLE");
        assumeTrue(binary != null && !binary.isBlank() && sample != null && !sample.isBlank());
        System.setProperty("mfcl.native.path", binary); System.clearProperty("mfcl.native.java.enabled");
        Path nativeOutput = temp.resolve("official-native"), javaOutput = temp.resolve("official-java");
        try (var input = new LZMAInputStream(Files.newInputStream(Path.of(sample)))) { Files.copy(input, javaOutput); }
        assertTrue(NativeRuntimeLzma.tryDecompress(Path.of(sample), nativeOutput,
                "7fb67e217c53a685cb9314001592b5bd50b5fbb9", 647, () -> false));
        assertArrayEquals(Files.readAllBytes(javaOutput), Files.readAllBytes(nativeOutput));
    }

    /// An explicitly requested direct-network installation exercises the official Linux runtime.
    @Test void realOfficialRuntimeInstall() throws Exception {
        @Nullable String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue("true".equals(System.getenv("MFCL_REAL_RUNTIME_INSTALL")) && binary != null && !binary.isBlank());
        System.setProperty("prism.order", "sw"); JavaFXLauncher.start();
        assumeTrue(JavaFXLauncher.isStarted(), "JavaFX toolkit unavailable");
        Path wrapper = temp.resolve("official-backend");
        Files.writeString(wrapper, """
                #!/usr/bin/env python3
                import json, os, pathlib, subprocess, sys
                child = subprocess.Popen([os.environ['MFCL_NATIVE_TEST_EXECUTABLE']], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
                try:
                    for line in sys.stdin:
                        request = json.loads(line); child.stdin.write(line); child.stdin.flush()
                        while True:
                            response = child.stdout.readline()
                            if not response: sys.exit(1)
                            frame = json.loads(response)
                            if 'result' in frame and request.get('method') in ('select_java_runtime', 'parse_java_runtime_manifest', 'prepare_java_runtime_tree', 'stage_runtime_lzma'):
                                pathlib.Path(__file__ + '.' + request['method']).touch()
                            sys.stdout.write(response); sys.stdout.flush()
                            if 'result' in frame or 'error' in frame: break
                finally:
                    child.stdin.close(); child.wait(timeout=5)
                """);
        assertTrue(wrapper.toFile().setExecutable(true));
        NativeTransferTestSupport.clearIdle(); System.setProperty("mfcl.native.path", wrapper.toString());
        System.clearProperty("mfcl.native.java.enabled"); System.clearProperty("mfcl.native.pool.enabled");
        DefaultCacheRepository cache = new DefaultCacheRepository(temp.resolve("official-cache")); CacheRepository.setInstance(cache);
        DownloadProvider provider = new DownloadProvider() {
            /// Uses a disposable cache for the real installation.
            @Override public DefaultCacheRepository getCacheRepository() { return cache; }
        };
        Path target = temp.resolve("official-java"), staging = temp.resolve("official-staging"); Files.createDirectories(target);
        MojangJavaDownloadTask task = new MojangJavaDownloadTask(provider, target, staging, GameJavaVersion.JAVA_17, "linux");
        assertTrue(task.executor().test(), () -> "Official runtime installation failed: " + task.getException());
        Path javaBinary = target.resolve("bin/java"); assertTrue(Files.isExecutable(javaBinary));
        Process process = new ProcessBuilder(javaBinary.toString(), "-version").redirectErrorStream(true).start();
        assertTrue(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output); assertTrue(output.contains("\"17."), output);
        for (String method : new String[]{"select_java_runtime", "parse_java_runtime_manifest", "prepare_java_runtime_tree", "stage_runtime_lzma"})
            assertTrue(Files.exists(Path.of(wrapper + "." + method)), "Official runtime did not use " + method);
    }

    /// A local runtime installation schedules downloads, native decode, executable mode and links.
    @Test void localRuntimeInstallUsesNativeLzma() throws Exception {
        @Nullable String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(binary != null && !binary.isBlank());
        System.setProperty("prism.order", "sw"); JavaFXLauncher.start();
        assumeTrue(JavaFXLauncher.isStarted(), "JavaFX toolkit unavailable");
        Path wrapper = temp.resolve("install-backend");
        Files.writeString(wrapper, """
                #!/usr/bin/env python3
                import json, os, pathlib, subprocess, sys
                child = subprocess.Popen([os.environ['MFCL_NATIVE_TEST_EXECUTABLE']], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
                try:
                    for line in sys.stdin:
                        request = json.loads(line); child.stdin.write(line); child.stdin.flush()
                        while True:
                            response = child.stdout.readline()
                            if not response: sys.exit(1)
                            frame = json.loads(response)
                            if request.get('method') == 'prepare_java_runtime_tree' and 'result' in frame:
                                pathlib.Path(__file__ + '.tree').touch()
                            if request.get('method') == 'parse_java_runtime_manifest' and 'result' in frame:
                                pathlib.Path(__file__ + '.manifest').touch()
                            if request.get('method') == 'select_java_runtime' and 'result' in frame:
                                pathlib.Path(__file__ + '.list').touch()
                            if request.get('method') == 'stage_runtime_lzma' and 'result' in frame:
                                pathlib.Path(__file__ + '.lzma').touch()
                            sys.stdout.write(response); sys.stdout.flush()
                            if 'result' in frame or 'error' in frame: break
                finally:
                    child.stdin.close(); child.wait(timeout=5)
                """);
        assertTrue(wrapper.toFile().setExecutable(true));
        NativeTransferTestSupport.clearIdle(); System.setProperty("mfcl.native.path", wrapper.toString());
        System.clearProperty("mfcl.native.java.enabled"); System.clearProperty("mfcl.native.pool.enabled");
        byte[] data = "isolated runtime executable fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path compressed = temp.resolve("fixture.lzma");
        try (var output = new LZMAOutputStream(Files.newOutputStream(compressed), new LZMA2Options(), data.length)) { output.write(data); }
        String compressedHash = DigestUtils.digestToString("SHA-1", compressed);
        String rawHash = DigestUtils.digestToString("SHA-1", data);
        Path manifest = temp.resolve("runtime-manifest.json");
        Files.writeString(manifest, """
                {"files":{"bin":{"type":"directory"},"bin/java":{"type":"file","executable":true,
                "downloads":{"lzma":{"url":"%s","sha1":"%s","size":%d},"raw":{"url":"%s","sha1":"%s","size":%d}}},
                "bin/java-link":{"type":"link","target":"java"}}}
                """.formatted(compressed.toUri(), compressedHash, Files.size(compressed), compressed.toUri(), rawHash, data.length));
        Path index = temp.resolve("runtime-index.json");
        Files.writeString(index, """
                {"linux":{"java-runtime-beta":[{"availability":{"group":0,"progress":100},
                "manifest":{"url":"%s","sha1":"0000000000000000000000000000000000000000","size":1},"version":{"name":"17.0.15","released":"2026-01-01"}}]}}
                """.formatted(manifest.toUri()));
        DefaultCacheRepository cache = new DefaultCacheRepository(temp.resolve("cache")); CacheRepository.setInstance(cache);
        DownloadProvider provider = new DownloadProvider() {
            /// Redirects the fixed index endpoint to the local fixture.
            @Override public DownloadCandidates getDownloadCandidates(String url) {
                return super.getDownloadCandidates(url.contains("all.json") ? index.toUri().toString() : url);
            }
            /// Uses the isolated test cache.
            @Override public DefaultCacheRepository getCacheRepository() { return cache; }
        };
        Path target = temp.resolve("installed"), staging = temp.resolve("staging"); Files.createDirectories(target);
        MojangJavaDownloadTask task = new MojangJavaDownloadTask(provider, target, staging, GameJavaVersion.JAVA_17, "linux");
        assertTrue(task.executor().test(), () -> "Runtime fixture installation failed: " + task.getException());
        assertNotNull(task.getResult()); assertArrayEquals(data, Files.readAllBytes(target.resolve("bin/java")));
        assertTrue(Files.isExecutable(target.resolve("bin/java"))); assertTrue(Files.isSymbolicLink(target.resolve("bin/java-link")));
        assertTrue(Files.exists(Path.of(wrapper + ".list")), "Runtime did not use native list selection");
        assertTrue(Files.exists(Path.of(wrapper + ".manifest")), "Runtime did not use native manifest parsing");
        assertTrue(Files.exists(Path.of(wrapper + ".tree")), "Runtime did not use native tree preparation");
        assertTrue(Files.exists(Path.of(wrapper + ".lzma")), "Runtime did not use native LZMA staging");
    }
}
