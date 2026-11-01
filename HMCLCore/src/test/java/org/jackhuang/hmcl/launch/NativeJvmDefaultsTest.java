/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.launch;

import org.jackhuang.hmcl.util.platform.CommandBuilder;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jackhuang.hmcl.game.ProxyOption;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Compares native JVM defaults with the existing command builder.
@NotNullByDefault
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class NativeJvmDefaultsTest {
    /// Test-owned option file.
    @TempDir Path temp;
    /// Previous backend setting.
    private final @Nullable String oldPath = System.getProperty("mfcl.native.path");
    /// Previous launch setting.
    private final @Nullable String oldEnabled = System.getProperty("mfcl.native.launch.enabled");

    /// Restores global launcher settings.
    @AfterEach void restore() {
        if (oldPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", oldPath);
        if (oldEnabled == null) System.clearProperty("mfcl.native.launch.enabled"); else System.setProperty("mfcl.native.launch.enabled", oldEnabled);
    }

    /// Representative versions, architectures and external options produce exactly the Java defaults.
    @Test void nativeDefaultsMatchJavaBuilder() throws Exception {
        @Nullable String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(binary != null && !binary.isBlank());
        System.setProperty("mfcl.native.path", binary); System.clearProperty("mfcl.native.launch.enabled");
        Path options = temp.resolve("java.args");
        Files.writeString(options, "-XX:-UseG1GC\n-XX:MaxInlineLevel=3\n-Xss2m\n-Dfml.ignorePatchDiscrepancies=false\n");
        for (int version : new int[]{7, 8, 16, 24, 25, 26}) {
            for (boolean is64 : new boolean[]{false, true}) {
                CommandBuilder original = new CommandBuilder();
                original.add("java").addAllWithoutParsingAndReadExternal(List.of("@" + options));
                if (version == 16) original.add("-XX:+UseZGC");
                if (version == 24) original.add("-XX:MaxNodeLimit=9");
                List<String> prefix = original.asList();
                boolean optimize = version != 26;
                @Nullable List<String> actual = NativeJvmDefaults.tryPlan(original, version, is64, is64, optimize);
                assertNotNull(actual);
                DefaultLauncher.appendJvmOptimizationDefaults(original, version, is64, is64, optimize);
                assertEquals(original.asList().subList(prefix.size(), original.asList().size()), actual,
                        "Java version " + version + ", 64-bit=" + is64);
            }
        }
    }

    /// Memory and ordinary defaults retain override precedence and command-script escaping.
    @Test void initialOptionsMatchJavaBuilderAndQuoting() throws Exception {
        @Nullable String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(binary != null && !binary.isBlank());
        System.setProperty("mfcl.native.path", binary); System.clearProperty("mfcl.native.launch.enabled");
        Path options = temp.resolve("override.args"); Files.writeString(options, "-Xmx1024m\n-XX:-UseG1GC\n-DfromFile=override\n");
        List<String> defaults = List.of("-Xmx4096m", "-Xms512m", "-DfromFile=default", "-Dflag", "-Dflag=override",
                "-XX:+UseG1GC", "-XX:MaxInlineLevel=8", "-XX:MaxInlineLevel=9", "-Dquoted=hello world", "-custom", "-custom");
        for (OperatingSystem os : List.of(OperatingSystem.LINUX, OperatingSystem.WINDOWS)) {
            for (int version : new int[]{7, 17}) {
                CommandBuilder expected = new CommandBuilder(os); CommandBuilder actual = new CommandBuilder(os);
                for (CommandBuilder builder : List.of(expected, actual)) {
                    builder.add("java").addAllWithoutParsingAndReadExternal(List.of("@" + options));
                    builder.addWithoutParsing("-Duser=value");
                }
                DefaultLauncher.appendInitialJvmDefaults(expected, version, 256, 2048, 128, defaults);
                @Nullable List<NativeJvmDefaults.Addition> additions = NativeJvmDefaults.tryPlanInitial(actual, version, 256, 2048, 128, defaults);
                assertNotNull(additions);
                for (var addition : additions) {
                    if (addition.parse()) actual.add(addition.argument()); else actual.addWithoutParsing(addition.argument());
                }
                assertEquals(expected.asList(), actual.asList()); assertEquals(expected.toString(), actual.toString());
            }
        }
    }

    /// Platform and game proxy options retain ordering, overrides and optimization behavior.
    @Test void platformDefaultsMatchJavaBuilder() throws Exception {
        @Nullable String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(binary != null && !binary.isBlank());
        System.setProperty("mfcl.native.path", binary); System.clearProperty("mfcl.native.launch.enabled");
        Path external = temp.resolve("platform.args"); Files.writeString(external, "-Duser.home=/override\n-XX:-UseG1GC\n");
        List<@Nullable ProxyOption> proxies = java.util.Arrays.asList(null, ProxyOption.Default.INSTANCE, ProxyOption.Direct.INSTANCE,
                new ProxyOption.Http("example.invalid", 8080, "user", "secret"),
                new ProxyOption.Socks("example.invalid", 1080, "   ", null));
        for (@Nullable ProxyOption proxy : proxies) {
            for (boolean customProxy : new boolean[]{false, true}) {
                CommandBuilder expected = new CommandBuilder(); CommandBuilder actual = new CommandBuilder();
                for (CommandBuilder builder : List.of(expected, actual)) {
                    builder.add("java").addAllWithoutParsingAndReadExternal(List.of("@" + external));
                    if (customProxy) builder.add("-Dhttp.proxyHost=custom");
                }
                DefaultLauncher.appendPlatformJvmDefaults(expected, "/game.jar", "Minecraft test", "/icon.icns", "/home/game",
                        proxy, 25, true, true, true);
                @Nullable List<String> additions = NativeJvmDefaults.tryPlanPlatform(actual, "/game.jar", "Minecraft test",
                        "/icon.icns", "/home/game", proxy, 25, true, true, true);
                assertNotNull(additions); actual.addAll(additions);
                assertEquals(expected.asList(), actual.asList()); assertEquals(expected.toString(), actual.toString());
            }
        }
    }
}
