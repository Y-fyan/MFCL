/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.game;

import org.jackhuang.hmcl.game.GameComponentType;
import org.jackhuang.hmcl.game.GameInstanceManifest;
import org.jackhuang.hmcl.game.GameInstancePatch;
import org.jackhuang.hmcl.game.DownloadInfo;
import org.jackhuang.hmcl.game.DownloadType;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Compares native base-game manifest projection with Java patch construction.
@NotNullByDefault
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class NativeGameManifestTest {
    /// Isolated test directory.
    @TempDir Path temp;
    /// Original backend path.
    private final @Nullable String oldPath = System.getProperty("mfcl.native.path");
    /// Original game feature setting.
    private final @Nullable String oldEnabled = System.getProperty("mfcl.native.game.enabled");

    /// Restores global settings.
    @AfterEach void restore() {
        if (oldPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", oldPath);
        if (oldEnabled == null) System.clearProperty("mfcl.native.game.enabled"); else System.setProperty("mfcl.native.game.enabled", oldEnabled);
    }

    /// The installation task's patch builder invokes the native projection.
    @Test void installationPatchBuilderUsesRust() throws Exception {
        @Nullable String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"), fixture = System.getenv("MFCL_GAME_MANIFEST_FIXTURE");
        assumeTrue(binary != null && !binary.isBlank() && fixture != null && !fixture.isBlank());
        Path wrapper = temp.resolve("manifest-backend");
        Files.writeString(wrapper, """
                #!/usr/bin/env python3
                import json, os, pathlib, subprocess, sys
                line = sys.stdin.readline()
                child = subprocess.run([os.environ['MFCL_NATIVE_TEST_EXECUTABLE']], input=line, text=True, stdout=subprocess.PIPE)
                frame = json.loads(child.stdout)
                if 'result' in frame:
                    pathlib.Path(__file__ + '.called').touch()
                    # Verify the Java adapter consumes native nested descriptors.
                    frame['result']['fields'].pop('assetIndex', None)
                    frame['result']['fields'].pop('downloads', None)
                    frame['result']['fields'].pop('libraries', None)
                    frame['result']['fields'].pop('arguments', None)
                    frame['result']['fields'].pop('javaVersion', None)
                    frame['result']['fields'].pop('logging', None)
                sys.stdout.write(json.dumps(frame) + '\\n'); sys.stdout.flush(); sys.exit(child.returncode)
                """);
        assertTrue(wrapper.toFile().setExecutable(true)); System.setProperty("mfcl.native.path", wrapper.toString());
        System.clearProperty("mfcl.native.game.enabled");
        String json = Files.readString(Path.of(fixture));
        GameInstancePatch expected = GameInstancePatch.fromManifest(JsonUtils.fromNonNullJson(json, GameInstanceManifest.class),
                GameComponentType.GAME.getPatchId(), "1.20.1", GameInstancePatch.PRIORITY_MC).withJar(null);
        @Nullable GameInstancePatch projected = NativeGameManifest.tryPatch(json, "1.20.1");
        assertNotNull(projected); assertEquals(expected.toJsonObject(), projected.toJsonObject());
        assertEquals(expected.toJsonObject(), GameInstallTask.createPatch(json, "1.20.1").toJsonObject());
        Files.delete(Path.of(wrapper + ".called"));
        DownloadInfo expectedMappings = JsonUtils.fromNonNullJson(json, GameInstanceManifest.class).getDownloads().get(DownloadType.CLIENT_MAPPINGS);
        @Nullable DownloadInfo actualMappings = GameInstallTask.readClientMappings(json, "1.20.1");
        assertNotNull(expectedMappings); assertNotNull(actualMappings);
        assertEquals(JsonUtils.GSON.toJsonTree(expectedMappings), JsonUtils.GSON.toJsonTree(actualMappings));
        assertTrue(Files.exists(Path.of(wrapper + ".called")), "Native mappings projection was not used");
        String classified = """
                {"id":"classified","javaVersion":{"component":"runtime","majorVersion":17},
                 "logging":{"server":null,"CLIENT":{"file":{"id":"log.xml","url":"https://example.test/log","size":3},"argument":"--log","type":"xml"}},
                 "arguments":{"game":["--demo",null,{"rules":[{"action":"allow"}],"value":"--flag"},
                  {"values":["one",null,"two"],"value":"ignored"}],"jvm":[]},"libraries":[{"name":"example:lib:1.0:native@zip",
                  "downloads":{"artifact":{"path":"main.jar","url":"https://example.test/main","size":5},
                    "classifiers":{"z-native":{"path":"z.jar","url":"https://example.test/z"},
                      "a-native":{"path":"a.jar","url":"https://example.test/a","sha1":"abc","size":7},"missing":null}},
                  "rules":[{"action":"ALLOW","os":{"version":"^10\\\\.","arch":"x86"},"features":{"demo":true,"custom":false}},
                           {"action":"unknown","os":{"name":null}},{},null],"url":"https://example.test/repository/",
                  "checksums":["a",null,"b"],"extract":{"exclude":["META-INF/","ignore/"]},
                  "natives":{"windows":"natives-windows","linux":"natives-linux","osx":null},
                  "MMC-hint":"local","MMC-filename":"override.jar"}]}
                """;
        GameInstancePatch classifiedExpected = GameInstancePatch.fromManifest(JsonUtils.fromNonNullJson(classified, GameInstanceManifest.class),
                GameComponentType.GAME.getPatchId(), "classified", GameInstancePatch.PRIORITY_MC).withJar(null);
        @Nullable GameInstancePatch classifiedActual = NativeGameManifest.tryPatch(classified, "classified");
        assertNotNull(classifiedActual);
        assertEquals(classifiedExpected.toJsonObject(), classifiedActual.toJsonObject());
        assertEquals(java.util.List.of("z-native", "a-native", "missing"), new java.util.ArrayList<>(
                java.util.Objects.requireNonNull(java.util.Objects.requireNonNull(classifiedActual.libraries()).get(0).downloads()).classifiers().keySet()));
        assertEquals(java.util.List.of("windows", "linux", "osx"), new java.util.ArrayList<>(
                java.util.Objects.requireNonNull(java.util.Objects.requireNonNull(classifiedActual.libraries()).get(0).natives()).keySet()));
        assertTrue(Files.exists(Path.of(wrapper + ".called")), "Native manifest projection was not used");
    }

    /// Official and legacy-shaped manifests produce the same Java patch.
    @Test void nativePatchMatchesJava() throws Exception {
        @Nullable String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"), fixture = System.getenv("MFCL_GAME_MANIFEST_FIXTURE");
        assumeTrue(binary != null && !binary.isBlank() && fixture != null && !fixture.isBlank());
        System.setProperty("mfcl.native.path", binary); System.clearProperty("mfcl.native.game.enabled");
        String official = Files.readString(Path.of(fixture));
        String legacy = """
                {"id":"legacy-test","minecraftArguments":"--demo","mainClass":"example.Main","assets":"legacy",
                 "libraries":[null],"downloads":{},"logging":{},"type":"old_alpha","time":"invalid",
                 "releaseTime":"2020-01-01T00:00:00Z","minimumLauncherVersion":4,"hidden":false,"ignored":{"x":1}}
                """;
        String descriptors = """
                {"id":"descriptor-test","mainClass":"example.Main",
                 "assetIndex":{"id":"assets","url":"https://example.test/assets","totalSize":3000000000},
                 "downloads":{"server":{"url":"https://example.test/server","size":2},
                              "CLIENT":{"url":"https://example.test/first","sha1":"invalid"},
                              "client":{"url":"https://example.test/final"},"unknown":{"ignored":true}}}
                """;
        for (String json : new String[]{official, legacy, descriptors}) {
            GameInstancePatch expected = GameInstancePatch.fromManifest(JsonUtils.fromNonNullJson(json, GameInstanceManifest.class),
                    GameComponentType.GAME.getPatchId(), "fixture-version", GameInstancePatch.PRIORITY_MC).withJar(null);
            @Nullable GameInstancePatch actual = NativeGameManifest.tryPatch(json, "fixture-version");
            assertNotNull(actual);
            assertEquals(expected.toJsonObject(), actual.toJsonObject());
            if (expected.downloads() != null) assertEquals(new java.util.ArrayList<>(expected.downloads().keySet()),
                    new java.util.ArrayList<>(java.util.Objects.requireNonNull(actual.downloads()).keySet()));
        }
        assertNull(NativeGameManifest.tryPatch("{\"id\":\"x\",\"downloads\":{\"client\":{\"url\":\"x\",\"size\":1.5}}}", "x"));
        assertNull(NativeGameManifest.tryPatch("{\"id\":\"x\",\"inheritsFrom\":\"parent\"}", "x"));
        String inheritedMappings = "{\"id\":\"x\",\"inheritsFrom\":\"parent\",\"downloads\":{\"client_mappings\":{\"url\":\"https://example.test/mappings\"}}}";
        @Nullable DownloadInfo fallbackMappings = GameInstallTask.readClientMappings(inheritedMappings, "x");
        assertNotNull(fallbackMappings); assertEquals("https://example.test/mappings", fallbackMappings.getUrl());
        assertNull(GameInstallTask.readClientMappings("{\"id\":\"x\",\"downloads\":{}}", "x"));
        assertNull(NativeGameManifest.tryPatch("{\"id\":\"x\",\"libraries\":[{\"name\":\"a:b:c\",\"downloads\":{\"classifiers\":[]}}]}", "x"));
        assertNull(NativeGameManifest.tryPatch("{\"id\":\"x\",\"libraries\":[{\"name\":\"a:b:c\",\"hint\":\"a\",\"MMC-hint\":\"b\"}]}", "x"));
        assertNull(NativeGameManifest.tryPatch("{\"id\":\"x\",\"arguments\":{\"game\":[{\"rules\":[]}]}}", "x"));
        assertNull(NativeGameManifest.tryPatch("{\"id\":\"x\",\"libraries\":[{\"name\":\"a:b:c\",\"rules\":[{\"features\":{\"demo\":\"true\"}}]}]}", "x"));
        assertNull(NativeGameManifest.tryPatch("{\"id\":\"x\",\"javaVersion\":{\"majorVersion\":1.5}}", "x"));
        assertNull(NativeGameManifest.tryPatch("{\"id\":\"x\",\"id\":\"y\"}", "x"));
        assertNull(NativeGameManifest.tryPatch("{\"id\":\"x\",\"tlauncherVersion\":1}", "x"));
        System.setProperty("mfcl.native.game.enabled", "false"); assertNull(NativeGameManifest.tryPatch(official, "x"));
    }
}
