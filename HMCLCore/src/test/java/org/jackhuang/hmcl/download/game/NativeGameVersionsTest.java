/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.game;

import org.jackhuang.hmcl.download.DefaultCacheRepository;
import org.jackhuang.hmcl.download.DownloadCandidates;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.util.CacheRepository;
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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Compares native game-version catalog parsing and launcher integration with Java.
@NotNullByDefault
@EnabledOnOs({OS.LINUX, OS.MAC})
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@ResourceLock("cache-repository")
final class NativeGameVersionsTest {
    /// Isolated test directory.
    @TempDir Path temp;
    /// Original native backend path.
    private final @Nullable String oldPath = System.getProperty("mfcl.native.path");
    /// Original native game setting.
    private final @Nullable String oldEnabled = System.getProperty("mfcl.native.game.enabled");
    /// Original graphics setting.
    private final @Nullable String oldPrism = System.getProperty("prism.order");
    /// Original global cache.
    private final CacheRepository oldCache = CacheRepository.getInstance();

    /// Restores global state.
    @AfterEach void restore() {
        CacheRepository.setInstance(oldCache);
        if (oldPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", oldPath);
        if (oldEnabled == null) System.clearProperty("mfcl.native.game.enabled"); else System.setProperty("mfcl.native.game.enabled", oldEnabled);
        if (oldPrism == null) System.clearProperty("prism.order"); else System.setProperty("prism.order", oldPrism);
    }

    /// Official catalog records match Java, and ambiguous or malformed data falls back.
    @Test void officialCatalogMatchesJava() throws Exception {
        @Nullable String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"), fixture = System.getenv("MFCL_GAME_VERSIONS_FIXTURE");
        assumeTrue(binary != null && !binary.isBlank() && fixture != null && !fixture.isBlank());
        System.setProperty("mfcl.native.path", binary); System.clearProperty("mfcl.native.game.enabled");
        String json = Files.readString(Path.of(fixture));
        assertEquals(JsonUtils.fromNonNullJson(json, GameRemoteVersions.class), NativeGameVersions.tryParse(json));
        assertNull(NativeGameVersions.tryParse("{\"latest\":{\"release\":\"a\",\"snapshot\":\"b\"},\"versions\":[],\"versions\":[]}"));
        assertNull(NativeGameVersions.tryParse("{\"latest\":{\"release\":\"a\",\"snapshot\":\"b\"},\"versions\":[{\"id\":\"版本\",\"url\":\"x\",\"type\":\"release\",\"time\":\"bad\",\"releaseTime\":\"bad\"}]}"));
        System.setProperty("mfcl.native.game.enabled", "false"); assertNull(NativeGameVersions.tryParse(json));
    }

    /// The version-fetch task consumes a local catalog through the native parser.
    @Test void fetchTaskUsesNativeParser() throws Exception {
        @Nullable String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"); assumeTrue(binary != null && !binary.isBlank());
        Path wrapper = temp.resolve("catalog-backend");
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
                            if request.get('method') == 'parse_game_versions' and 'result' in frame:
                                pathlib.Path(__file__ + '.catalog').touch()
                            sys.stdout.write(response); sys.stdout.flush()
                            if 'result' in frame or 'error' in frame: break
                finally:
                    child.stdin.close(); child.wait(timeout=5)
                """);
        assertTrue(wrapper.toFile().setExecutable(true)); System.setProperty("mfcl.native.path", wrapper.toString());
        System.clearProperty("mfcl.native.game.enabled"); System.setProperty("prism.order", "sw");
        org.jackhuang.hmcl.JavaFXLauncher.start(); assumeTrue(org.jackhuang.hmcl.JavaFXLauncher.isStarted());
        Path catalog = temp.resolve("catalog.json"); Files.writeString(catalog, """
                {"latest":{"release":"1.20.1","snapshot":"23w"},"versions":[
                {"id":"1.20.1","url":"https://example.test/1.20.1.json","type":"release",
                 "time":"2023-06-12T00:00:00+00:00","releaseTime":"2023-06-12T00:00:00+00:00"}]}
                """);
        DefaultCacheRepository cache = new DefaultCacheRepository(temp.resolve("cache")); CacheRepository.setInstance(cache);
        DownloadProvider provider = new DownloadProvider() {
            /// Redirects the version catalog to a local fixture.
            @Override public DownloadCandidates getGameVersionListCandidates() { return DownloadCandidates.of(catalog.toUri().toString()); }
            /// Uses the isolated cache.
            @Override public DefaultCacheRepository getCacheRepository() { return cache; }
        };
        var task = GameRemoteVersion.fetchAsync(provider);
        assertTrue(task.executor().test(), () -> "Version fetch failed: " + task.getException());
        assertNotNull(task.getResult().getRemoteVersion("1.20.1"));
        assertTrue(Files.exists(Path.of(wrapper + ".catalog")), "Native game catalog parser was not used");
    }
}
