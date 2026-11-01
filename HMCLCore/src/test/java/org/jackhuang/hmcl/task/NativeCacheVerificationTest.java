/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.task;

import org.jackhuang.hmcl.nativebridge.NativeTransferTestSupport;
import org.jackhuang.hmcl.util.CacheRepository;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jackhuang.hmcl.util.io.UrlResponseInfo;
import org.glavo.url.WebURL;
import org.jackhuang.hmcl.download.DefaultCacheRepository;
import org.jackhuang.hmcl.game.Artifact;
import org.jackhuang.hmcl.game.LibrariesDownloadInfo;
import org.jackhuang.hmcl.game.Library;
import org.jackhuang.hmcl.game.LibraryDownloadInfo;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Checks native cache verification against a real isolated backend.
@NotNullByDefault
@EnabledOnOs(OS.LINUX)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class NativeCacheVerificationTest {
    /// Test-owned backend and cache paths.
    @TempDir Path temp;
    /// Original backend setting.
    private final @Nullable String oldPath = System.getProperty("mfcl.native.path");
    /// Original cache setting.
    private final @Nullable String oldEnabled = System.getProperty("mfcl.native.cache.enabled");
    /// Original worker pool setting.
    private final @Nullable String oldPool = System.getProperty("mfcl.native.pool.enabled");

    /// Restores global state and closes test-owned idle workers.
    @AfterEach void restore() {
        NativeTransferTestSupport.clearIdle();
        if (oldPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", oldPath);
        if (oldEnabled == null) System.clearProperty("mfcl.native.cache.enabled"); else System.setProperty("mfcl.native.cache.enabled", oldEnabled);
        if (oldPool == null) System.clearProperty("mfcl.native.pool.enabled"); else System.setProperty("mfcl.native.pool.enabled", oldPool);
    }

    /// Supported cache digests reject changed content using the native worker.
    @Test void verifiesBothDigestTypesAndRejectsCorruption() throws Exception {
        @Nullable String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(binary != null && !binary.isBlank());
        Path wrapper = temp.resolve("backend");
        Files.writeString(wrapper, """
                #!/usr/bin/env python3
                import json, os, pathlib, subprocess, sys
                with open(__file__ + '.pids', 'a') as output: output.write(str(os.getpid()) + '\\n')
                child = subprocess.Popen([os.environ['MFCL_NATIVE_TEST_EXECUTABLE']], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
                try:
                    for line in sys.stdin:
                        request = json.loads(line)
                        with open(__file__ + '.calls', 'a') as output: output.write(request['method'] + '\\n')
                        child.stdin.write(line); child.stdin.flush()
                        while True:
                            response = child.stdout.readline()
                            if not response: sys.exit(1)
                            frame = json.loads(response)
                            if request['method'] == 'stage_cache_copy' and 'stage_inode' in frame.get('result', {}):
                                pathlib.Path(__file__ + '.stage-inode').write_text(str(frame['result']['stage_inode']))
                            sys.stdout.write(response); sys.stdout.flush()
                            if 'result' in frame or 'error' in frame: break
                finally:
                    child.stdin.close(); child.wait(timeout=5)
                """);
        assertTrue(wrapper.toFile().setExecutable(true));
        NativeTransferTestSupport.clearIdle();
        System.setProperty("mfcl.native.path", wrapper.toString()); System.clearProperty("mfcl.native.cache.enabled");
        System.clearProperty("mfcl.native.pool.enabled");
        byte[] data = new byte[1024 * 1024 + 7];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 251);
        Path source = temp.resolve("source"); Files.write(source, data);
        CacheRepository cache = new CacheRepository(); cache.changeDirectory(temp.resolve("cache-root"));
        for (String algorithm : new String[]{"SHA-1", "SHA-256", "SHA-512", "MD5"}) {
            String expected = DigestUtils.digestToString(algorithm, data);
            Path cached = cache.cacheFile(source, algorithm, expected);
            assertEquals(Long.parseLong(Files.readString(Path.of(wrapper + ".stage-inode"))),
                    ((Number) Files.getAttribute(cached, "unix:ino")).longValue());
            assertEquals(Files.getPosixFilePermissions(source), Files.getPosixFilePermissions(cached));
            assertEquals(Files.getLastModifiedTime(source), Files.getLastModifiedTime(cached));
            assertEquals(cached, cache.checkExistentFile(null, algorithm, expected).orElseThrow());
            data[0] ^= 1; Files.write(cached, data); data[0] ^= 1;
            assertTrue(cache.checkExistentFile(null, algorithm, expected).isEmpty());
            Path candidate = temp.resolve("candidate-" + algorithm); Files.write(candidate, data);
            assertEquals(cached, cache.checkExistentFile(candidate, algorithm, expected).orElseThrow());
            assertTrue(Files.isSameFile(candidate, cached));
        }
        WebURL url = WebURL.parse("https://example.invalid/cache-fixture");
        HttpHeaders headers = HttpHeaders.of(Map.of("etag", List.of("fixture"), "cache-control", List.of("max-age=60")), (k, v) -> true);
        Path remote = cache.cacheRemoteFile(new UrlResponseInfo(200, url, headers), source);
        Files.setLastModifiedTime(remote, FileTime.fromMillis(Files.getLastModifiedTime(remote).toMillis() + 10_000));
        assertEquals(remote, cache.getCachedRemoteFile(url, false));
        String libraryHash = DigestUtils.digestToString("SHA-1", data);
        Library library = new Library(new Artifact("org.example", "fixture", "1"), null,
                new LibrariesDownloadInfo(new LibraryDownloadInfo(null, "https://example.invalid/file", libraryHash)));
        DefaultCacheRepository libraryCache = new DefaultCacheRepository(temp.resolve("library-cache"));
        libraryCache.tryCacheLibrary(library, source);
        Path cachedLibrary = libraryCache.getLibrary(library).orElseThrow();
        assertEquals(Long.parseLong(Files.readString(Path.of(wrapper + ".stage-inode"))),
                ((Number) Files.getAttribute(cachedLibrary, "unix:ino")).longValue());
        assertArrayEquals(data, Files.readAllBytes(cachedLibrary));
        DefaultCacheRepository generatedHashCache = new DefaultCacheRepository(temp.resolve("generated-hash-cache"));
        Library noDigest = new Library(new Artifact("org.example", "without-digest", "1"));
        Path generated = generatedHashCache.cacheLibrary(noDigest, source, false);
        assertArrayEquals(data, Files.readAllBytes(generated));
        assertEquals(Long.parseLong(Files.readString(Path.of(wrapper + ".stage-inode"))),
                ((Number) Files.getAttribute(generated, "unix:ino")).longValue());
        String calls = Files.readString(Path.of(wrapper + ".calls"));
        assertTrue(calls.contains("check_files")); assertTrue(calls.contains("hash_modpack_file"));
        assertTrue(calls.contains("stage_cache_copy")); assertTrue(calls.contains("hash_cache_sha1"));
        assertTrue(calls.contains("check_cache_digest"));
        assertEquals(1, Files.readAllLines(Path.of(wrapper + ".pids")).size());

        int mode = ((Number) Files.getAttribute(source, "unix:mode")).intValue();
        Files.setAttribute(source, "unix:mode", mode | 04000);
        CacheRepository special = new CacheRepository(); special.changeDirectory(temp.resolve("special-cache"));
        String hash = DigestUtils.digestToString("SHA-1", data);
        Path copied = special.cacheFile(source, "SHA-1", hash);
        assertArrayEquals(data, Files.readAllBytes(copied));
        assertEquals(calls, Files.readString(Path.of(wrapper + ".calls")), "Special permission bits must use Java copying");
    }
}
