/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.game;

import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Differential tests for native inheritance manifest merging.
@NotNullByDefault
@EnabledOnOs({OS.LINUX, OS.MAC})
@ResourceLock(Resources.SYSTEM_PROPERTIES)
public final class NativeManifestMergeTest {
    /// Isolated fixture directory.
    @TempDir Path temp;

    /// Field precedence, nulls, ordered libraries, rules, arguments and patches match Java.
    @Test void nativeMergeMatchesJavaForTypedManifests() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        List<String> jsons = List.of(
                "{\"id\":\"base\"}",
                "{\"id\":\"base\",\"mainClass\":\"base.Main\",\"jar\":\"base\",\"assets\":\"legacy\",\"minimumLauncherVersion\":17,\"complianceLevel\":1,\"type\":\"release\"}",
                "{\"id\":\"base\",\"minecraftArguments\":\"--demo\",\"libraries\":[{\"name\":\"example:alpha:1\"}],\"arguments\":{\"game\":[\"--base\"],\"jvm\":[\"-Dbase=true\"]}}",
                "{\"id\":\"base\",\"mainClass\":\"child.Main\",\"libraries\":[{\"name\":\"example:beta:2\"}],\"arguments\":{\"game\":[\"--child\"]},\"patches\":[{\"id\":\"loader\",\"priority\":20,\"mainClass\":\"loader.Main\"}],\"minimumLauncherVersion\":3,\"hidden\":true}",
                "{\"id\":\"base\",\"assetIndex\":{\"id\":\"5\",\"url\":\"https://example.invalid/index\",\"sha1\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"size\":5},\"javaVersion\":{\"component\":\"java-runtime-gamma\",\"majorVersion\":17},\"downloads\":{\"client\":{\"url\":\"https://example.invalid/client\",\"sha1\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\",\"size\":2}}}",
                "{\"id\":\"base\",\"compatibilityRules\":[{\"action\":\"allow\",\"os\":{\"name\":\"linux\"}}],\"time\":\"2023-06-07T00:00:00Z\",\"releaseTime\":\"2023-06-08T00:00:00Z\",\"logging\":{\"client\":{\"argument\":\"-Dlog4j.configurationFile=${path}\",\"type\":\"log4j2-xml\",\"file\":{\"id\":\"log4j.xml\",\"url\":\"https://example.invalid/log\",\"sha1\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"size\":1}}}}"
        );
        for (String childJson : jsons) for (String parentJson : jsons) {
            GameInstanceManifest child = GameInstanceManifest.fromJson(JsonParser.parseString(childJson).getAsJsonObject(), false)
                    .withId(new GameInstanceID("child"));
            GameInstanceManifest parent = GameInstanceManifest.fromJson(JsonParser.parseString(parentJson).getAsJsonObject(), false)
                    .withId(new GameInstanceID("parent"));
            assertEquals(child.mergeJava(parent), NativeManifestMerge.merge(Path.of(executable), child, parent),
                    () -> "child=" + childJson + " parent=" + parentJson);
        }
    }

    /// Opaque custom argument implementations and null entries retain their Java values.
    @Test void customArgumentsAreNotSerializedOrLost() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        Argument custom = (keys, features) -> List.of("custom");
        GameInstanceManifest.Builder childBuilder = new GameInstanceManifest.Builder();
        childBuilder.setId(new GameInstanceID("child"));
        childBuilder.setArguments(new Arguments(Arrays.asList(custom, null), null));
        GameInstanceManifest.Builder parentBuilder = new GameInstanceManifest.Builder();
        parentBuilder.setId(new GameInstanceID("parent"));
        parentBuilder.setArguments(new Arguments(List.of(new StringArgument("parent")), List.of()));
        GameInstanceManifest child = childBuilder.toManifest(); GameInstanceManifest parent = parentBuilder.toManifest();
        assertEquals(child.mergeJava(parent), NativeManifestMerge.merge(Path.of(executable), child, parent));
        GameInstanceManifest empty = new GameInstanceManifest(new GameInstanceID("empty"));
        assertSame(child.arguments(), NativeManifestMerge.merge(Path.of(executable), child, empty).arguments());
        assertSame(parent.arguments(), NativeManifestMerge.merge(Path.of(executable), empty, parent).arguments());
    }

    /// Patch priority, equal-priority stability, fields and retained raw JSON match Java.
    @Test void nativePatchApplicationMatchesJava() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        GameInstanceManifest base = GameInstanceManifest.fromJson(JsonParser.parseString(
                "{\"id\":\"base\",\"mainClass\":\"base.Main\",\"customExtension\":{\"keep\":true},\"libraries\":[{\"name\":\"example:base:1\"}],\"arguments\":{\"game\":[\"--base\"]},\"minimumLauncherVersion\":9}")
                .getAsJsonObject(), false);
        List<GameInstancePatch> patches = List.of(
                GameInstancePatch.fromJson(JsonParser.parseString("{\"id\":\"late-one\",\"priority\":20,\"mainClass\":\"late.One\",\"libraries\":[{\"name\":\"example:late:1\"}],\"arguments\":{\"game\":[\"--late\"]}}").getAsJsonObject()),
                GameInstancePatch.fromJson(JsonParser.parseString("{\"id\":\"early\",\"priority\":10,\"mainClass\":\"early.Main\",\"minimumLauncherVersion\":22,\"complianceLevel\":2}").getAsJsonObject()),
                GameInstancePatch.fromJson(JsonParser.parseString("{\"id\":\"late-two\",\"priority\":20,\"mainClass\":\"late.Two\",\"compatibilityRules\":[{\"action\":\"allow\"}]}").getAsJsonObject()));
        GameInstanceManifest source = new GameInstanceManifest(new GameInstanceID("child")).withPatches(patches);
        GameInstanceManifest.Builder javaBuilder = new GameInstanceManifest.Builder(base);
        patches.stream().sorted(java.util.Comparator.comparing(GameInstancePatch::getPriority)).forEach(javaBuilder::merge);
        javaBuilder.setId(source.id()); javaBuilder.setPatches(null);
        GameInstanceManifest expected = javaBuilder.toManifest();
        GameInstanceManifest actual = NativeManifestMerge.applyPatches(Path.of(executable), source, base);
        assertEquals(expected, actual);
        assertEquals("late.Two", actual.mainClass()); assertEquals(22, actual.minimumLauncherVersion());
        assertEquals(base.rawJson().get("customExtension"), actual.rawJson().get("customExtension"));
    }

    /// Patch reconstruction uses original order rather than priority sorting.
    @Test void reconstructionKeepsOriginalPatchOrder() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        List<GameInstancePatch> patches = List.of(
                new GameInstancePatch("first", null, 20, null, "first.Main", null),
                new GameInstancePatch("second", null, 10, null, "second.Main", null));
        GameInstanceManifest source = new GameInstanceManifest(new GameInstanceID("fixture")).withPatches(patches);
        @Nullable String previous = System.getProperty("mfcl.native.path");
        @Nullable String previousEnabled = System.getProperty("mfcl.native.instances.enabled");
        try {
            System.setProperty("mfcl.native.instances.enabled", "false");
            GameInstanceManifest expected = source.reconstructByPatches();
            System.setProperty("mfcl.native.path", executable); System.clearProperty("mfcl.native.instances.enabled");
            GameInstanceManifest actual = source.reconstructByPatches();
            assertEquals(expected, actual); assertEquals("second.Main", actual.mainClass());
            GameInstanceManifest.Builder empty = new GameInstanceManifest.Builder(); empty.setId(source.id());
            GameInstanceManifest nativeRaw = NativeManifestMerge.applyPatches(Path.of(executable), source, empty.toManifest(), false);
            assertEquals("second.Main", nativeRaw.mainClass());
        } finally {
            if (previous == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previous);
            if (previousEnabled == null) System.clearProperty("mfcl.native.instances.enabled"); else System.setProperty("mfcl.native.instances.enabled", previousEnabled);
        }
    }

    /// Invalid native output returns to the established Java merge path.
    @Test void malformedNativeResponseFallsBack() throws Exception {
        Path wrapper = temp.resolve("backend");
        Files.writeString(wrapper, "#!/bin/sh\nread line\nprintf '%s\\n' '{\"version\":1,\"id\":1,\"result\":{\"id\":999999}}'\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        @Nullable String previous = System.getProperty("mfcl.native.path");
        @Nullable String previousEnabled = System.getProperty("mfcl.native.instances.enabled");
        try {
            System.setProperty("mfcl.native.path", wrapper.toString()); System.clearProperty("mfcl.native.instances.enabled");
            GameInstanceManifest child = new GameInstanceManifest(new GameInstanceID("child"));
            GameInstanceManifest parent = new GameInstanceManifest(new GameInstanceID("parent")).withMainClass("parent.Main");
            assertEquals(child.mergeJava(parent), child.merge(parent));
        } finally {
            if (previous == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previous);
            if (previousEnabled == null) System.clearProperty("mfcl.native.instances.enabled"); else System.setProperty("mfcl.native.instances.enabled", previousEnabled);
        }
    }
}
