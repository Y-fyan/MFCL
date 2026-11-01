/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.launch;

import org.jackhuang.hmcl.game.Arguments;
import org.jackhuang.hmcl.game.Argument;
import org.jackhuang.hmcl.game.StringArgument;
import org.jackhuang.hmcl.game.RuledArgument;
import org.jackhuang.hmcl.game.CompatibilityRule;
import org.jackhuang.hmcl.game.OSRestriction;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Compares native launch argument expansion with the existing Java parser.
@NotNullByDefault
@EnabledOnOs({OS.LINUX, OS.MAC})
@ResourceLock(Resources.SYSTEM_PROPERTIES)
public final class NativeArgumentTest {
    /// Test-owned temporary directory.
    @TempDir Path tempDir;
    /// Previous native backend setting.
    private final @Nullable String previousPath = System.getProperty("mfcl.native.path");
    /// Previous native argument setting.
    private final @Nullable String previousEnabled = System.getProperty("mfcl.native.arguments.enabled");

    /// Restores global settings.
    @AfterEach void restore() {
        if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
        if (previousEnabled == null) System.clearProperty("mfcl.native.arguments.enabled"); else System.setProperty("mfcl.native.arguments.enabled", previousEnabled);
    }

    /// Native expansion matches Java for multiple groups, nested values, secrets and Unicode.
    @Test void matchesJavaForGeneratedArguments() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        Map<String, String> keys = Map.of("${a}", "${b}", "${b}", "done", "${name}", "玩家",
                "${auth_access_token}", "sample $ \\\" token", "${}", "empty-key");
        List<@Nullable String> first = Arrays.asList("${a}/${b}", "${name}-${name}", null,
                "${unknown}", "${bad\nname}", "${bad\rname}", "${bad\u0085name}", "${bad\u2028name}",
                "${bad\u2029name}", "${}", "", "before-${auth_access_token}-after");
        String[] parts = {"literal", "${a}", "${b}", "${name}", "${unknown}", "${}", "${bad\nname}", " ", "é", "玩家", "${a${b}}"};
        Random random = new Random(713);
        List<String> generated = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            StringBuilder value = new StringBuilder();
            for (int j = 0; j < 1 + random.nextInt(6); j++) value.append(parts[random.nextInt(parts.length)]);
            generated.add(value.toString());
        }
        List<List<@Nullable String>> groups = List.of(first, generated);
        List<List<String>> expected = new ArrayList<>();
        for (List<@Nullable String> group : groups) expected.add(Arguments.parseStringArguments(group, keys));
        List<List<String>> nativeGroups = NativeBackend.expandArgumentGroups(Path.of(executable), groups, keys);
        assertEquals(expected, nativeGroups);
        System.setProperty("mfcl.native.path", executable);
        System.clearProperty("mfcl.native.arguments.enabled");
        assertEquals(expected, DefaultLauncher.expandStringArgumentGroups(new ArrayList<>(groups), keys));
    }

    /// Rule-filtered JVM, game, auth, legacy, default and custom groups match Java individually.
    @Test void expandsMixedLaunchArgumentsWithRules() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        System.setProperty("mfcl.native.path", executable);
        System.clearProperty("mfcl.native.arguments.enabled");
        Map<String, String> keys = Map.of("${a}", "${b}", "${b}", "done", "${auth_access_token}", "secret");
        Map<String, Boolean> features = Map.of("has_custom_resolution", true);
        List<Argument> jvm = List.of(new StringArgument("-Dfoo=${a}/${b}"));
        List<Argument> authJvm = List.of(new StringArgument("-Dauth=${auth_access_token}"));
        List<String> legacy = List.of("--legacy", "${a}");
        List<Argument> game = List.of(
                new RuledArgument(List.of(new CompatibilityRule(CompatibilityRule.Action.ALLOW, null, Map.of("has_custom_resolution", true))),
                        List.of("--width", "${b}")),
                new RuledArgument(List.of(new CompatibilityRule(CompatibilityRule.Action.ALLOW, null, Map.of("has_custom_resolution", false))),
                        List.of("should-not-appear")));
        List<Argument> defaults = List.of(new StringArgument("default-${a}"));
        List<Argument> authGame = List.of(new StringArgument("${auth_access_token}"));
        List<String> custom = List.of("--custom=${a}");
        List<List<String>> expected = List.of(Arguments.parseArguments(jvm, keys), Arguments.parseArguments(authJvm, keys),
                Arguments.parseStringArguments(legacy, keys), Arguments.parseArguments(game, keys, features),
                Arguments.parseArguments(defaults, keys, features), Arguments.parseArguments(authGame, keys, features),
                Arguments.parseStringArguments(custom, keys));
        List<List<Argument>> nativeGroups = List.of(jvm, authJvm, new ArrayList<>(legacy.stream().map(StringArgument::new).toList()),
                game, defaults, authGame, new ArrayList<>(custom.stream().map(StringArgument::new).toList()));
        List<Map<String, Boolean>> featureGroups = List.of(Map.of(), Map.of(), Map.of(), features, features, features, Map.of());
        assertEquals(expected, NativeBackend.expandRuledArgumentGroups(Path.of(executable), nativeGroups, featureGroups, keys));
        assertEquals(expected, DefaultLauncher.expandLaunchArgumentGroups(jvm, authJvm, legacy, game, defaults, authGame, custom, keys, features));
    }

    /// Rule ordering, OS checks, malformed regex and null feature values match Java.
    @Test void nativeRuleSelectionMatchesJavaEdgeCases() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        Map<String, @Nullable Boolean> nullFeature = new java.util.HashMap<>();
        nullFeature.put("missing", null);
        List<Argument> arguments = List.of(
                new RuledArgument(List.of(new CompatibilityRule(CompatibilityRule.Action.ALLOW, null),
                        new CompatibilityRule(CompatibilityRule.Action.DISALLOW, null)), List.of("denied")),
                new RuledArgument(List.of(new CompatibilityRule(CompatibilityRule.Action.DISALLOW, null),
                        new CompatibilityRule(CompatibilityRule.Action.ALLOW, null)), List.of("allowed")),
                new RuledArgument(List.of(new CompatibilityRule(CompatibilityRule.Action.ALLOW,
                        new OSRestriction("windows", "^10\\..*", null))), List.of("windows-only")),
                new RuledArgument(List.of(new CompatibilityRule(CompatibilityRule.Action.ALLOW,
                        new OSRestriction("unknown", "(?<=invalid", null))), List.of("invalid-regex")),
                new RuledArgument(List.of(new CompatibilityRule(CompatibilityRule.Action.ALLOW, null, nullFeature)), List.of("null-feature")),
                new RuledArgument(List.of(new CompatibilityRule(null, null)), List.of("null-action")),
                new RuledArgument(null, Arrays.asList("unrestricted", null)));
        List<String> expected = Arguments.parseArguments(arguments, Map.of(), Map.of());
        assertEquals(List.of(expected), NativeBackend.expandRuledArgumentGroups(Path.of(executable), List.of(arguments), List.of(Map.of()), Map.of()));
    }

    /// Portable OS and architecture regexes are sent to Rust; unusual patterns retain Java matching.
    @Test void simpleOsRulesAreEvaluatedInRust() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        Path wrapper = tempDir.resolve("backend");
        Files.writeString(wrapper, "#!/bin/sh\nread -r line\nprintf '%s' \"$line\" > \"$0.request\"\nprintf '%s\\n' \"$line\" | exec '" + executable.replace("'", "'\\''") + "'\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        List<Argument> arguments = new ArrayList<>();
        for (String name : List.of("linux", " WINDOWS ", "Darwin", "osx", "freebsd", "solaris", "unknown", "linux-x86_64", "windows-amd64", "mac-aarch64", "linux-arm")) {
            arguments.add(new RuledArgument(List.of(new CompatibilityRule(CompatibilityRule.Action.ALLOW,
                    new OSRestriction(name, null, null))), List.of(name)));
        }
        arguments.add(new RuledArgument(List.of(new CompatibilityRule(CompatibilityRule.Action.ALLOW,
                new OSRestriction("linux", ".*", null))), List.of("regex")));
        arguments.add(new RuledArgument(List.of(new CompatibilityRule(CompatibilityRule.Action.ALLOW,
                new OSRestriction("unknown", "^1\\..*", "x86_64"))), List.of("regex-architecture")));
        arguments.add(new RuledArgument(List.of(new CompatibilityRule(CompatibilityRule.Action.ALLOW,
                new OSRestriction("unknown", "(?i)linux", null))), List.of("java-only-regex")));
        arguments.add(new RuledArgument(List.of(new CompatibilityRule(CompatibilityRule.Action.ALLOW,
                new OSRestriction("linux-x86_64", "(?invalid", "(?invalid"))), List.of("compound-ignores-regex")));
        List<String> expected = Arguments.parseArguments(arguments, Map.of(), Map.of());
        assertEquals(List.of(expected), NativeBackend.expandRuledArgumentGroups(wrapper, List.of(arguments), List.of(Map.of()), Map.of()));
        String request = Files.readString(Path.of(wrapper + ".request"));
        assertTrue(request.contains("\"os_name\":\"linux\""));
        assertTrue(request.contains("\"system_os\":"));
        assertTrue(request.contains("\"os_name\":\"linux-x86_64\""));
        assertTrue(request.contains("\"system_arch_enum\":"));
        assertTrue(request.contains("\"version_regex\":\".*\""));
        assertTrue(request.contains("\"arch_regex\":\"x86_64\""));
        assertTrue(request.contains("\"os_matches\":"));
    }

    /// Generated ordered rules agree with Java across OS restrictions and feature values.
    @Test void generatedRuleSetsMatchJava() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        Random random = new Random(9182);
        Map<String, Boolean> features = Map.of("custom", true, "demo", false);
        List<Argument> arguments = new ArrayList<>();
        String[] osNames = {"linux", "windows", "osx", "linux-x86_64", "unknown"};
        for (int i = 0; i < 1000; i++) {
            List<CompatibilityRule> rules = new ArrayList<>();
            int ruleCount = random.nextInt(5);
            for (int j = 0; j < ruleCount; j++) {
                int actionId = random.nextInt(3);
                @Nullable CompatibilityRule.Action action = actionId == 0 ? null :
                        actionId == 1 ? CompatibilityRule.Action.ALLOW : CompatibilityRule.Action.DISALLOW;
                @Nullable OSRestriction os = random.nextBoolean() ? null :
                        new OSRestriction(osNames[random.nextInt(osNames.length)], random.nextBoolean() ? null : ".*", null);
                @Nullable Map<String, Boolean> required = null;
                if (random.nextBoolean()) {
                    required = new java.util.HashMap<>();
                    @Nullable Boolean expected = switch (random.nextInt(3)) { case 0 -> null; case 1 -> true; default -> false; };
                    required.put(random.nextBoolean() ? "custom" : "missing", expected);
                }
                rules.add(new CompatibilityRule(action, os, required));
            }
            arguments.add(new RuledArgument(rules, Arrays.asList("item-" + i + "-${a}", null)));
        }
        Map<String, String> keys = Map.of("${a}", "value");
        List<String> expected = Arguments.parseArguments(arguments, keys, features);
        assertEquals(List.of(expected), NativeBackend.expandRuledArgumentGroups(Path.of(executable), List.of(arguments), List.of(features), keys));
    }

    /// A configured native backend is selected for supported rule groups.
    @Test void supportedRulesUseNativeBackend() throws Exception {
        Path executable = script("printf '%s\\n' '{\"version\":1,\"id\":1,\"result\":{\"groups\":[[\"native-used\"],[],[],[],[],[],[]]}}'");
        System.setProperty("mfcl.native.path", executable.toString());
        System.clearProperty("mfcl.native.arguments.enabled");
        List<List<String>> result = DefaultLauncher.expandLaunchArgumentGroups(List.of(new StringArgument("java")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());
        assertEquals(List.of("native-used"), result.get(0));
        assertTrue(Files.exists(Path.of(executable + ".called")));
    }

    /// Unknown argument implementations use their existing Java behavior and do not invoke Rust.
    @Test void unknownArgumentTypeUsesOriginalJavaParser() throws Exception {
        Path executable = script("exit 1");
        System.setProperty("mfcl.native.path", executable.toString());
        Argument customArgument = (keys, features) -> List.of("plugin-" + keys.get("${a}"));
        List<List<String>> result = DefaultLauncher.expandLaunchArgumentGroups(List.of(customArgument), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), Map.of("${a}", "value"), Map.of());
        assertEquals(List.of("plugin-value"), result.get(0));
        assertFalse(Files.exists(Path.of(executable + ".called")));
    }

    /// A malformed native response falls back to Java without changing argument values.
    @Test void invalidResponseFallsBackToJava() throws Exception {
        Path executable = script("printf '%s\\n' '{\"version\":1,\"id\":1,\"result\":{\"groups\":[]}}'");
        System.setProperty("mfcl.native.path", executable.toString());
        List<List<String>> groups = List.of(List.of("--token=${auth_access_token}"));
        Map<String, String> keys = Map.of("${auth_access_token}", "secret");
        assertEquals(List.of(List.of("--token=secret")), DefaultLauncher.expandStringArgumentGroups(groups, keys));
        assertTrue(Files.exists(Path.of(executable + ".called")));
    }

    /// The explicit feature switch bypasses the native process.
    @Test void disabledFeatureUsesJava() throws Exception {
        Path executable = script("exit 1");
        System.setProperty("mfcl.native.path", executable.toString());
        System.setProperty("mfcl.native.arguments.enabled", "false");
        assertEquals(List.of(List.of("x")), DefaultLauncher.expandStringArgumentGroups(List.of(List.of("${a}")), Map.of("${a}", "x")));
        assertFalse(Files.exists(Path.of(executable + ".called")));
    }

    /// Creates a native-process fixture that records invocation.
    ///
    /// @param body fixture body
    /// @return executable path
    /// @throws IOException if fixture creation fails
    private Path script(String body) throws IOException {
        Path path = tempDir.resolve("backend");
        Files.writeString(path, "#!/bin/sh\nread line\n: > \"$0.called\"\n" + body + "\n");
        if (!path.toFile().setExecutable(true)) throw new IOException("Cannot mark test executable");
        return path;
    }
}
