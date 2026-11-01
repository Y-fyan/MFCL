/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.modpack.modrinth;

import org.jackhuang.hmcl.nativebridge.NativeTransferTestSupport;
import org.jackhuang.hmcl.modpack.NativeManagedRemoval;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Compares native Modrinth selection to the original Java conditions.
@NotNullByDefault
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class NativeModrinthSelectionTest {
    /// Isolated game directory.
    @TempDir Path temp;
    /// Original executable setting.
    private final @Nullable String oldPath = System.getProperty("mfcl.native.path");
    /// Original feature setting.
    private final @Nullable String oldEnabled = System.getProperty("mfcl.native.modpack.enabled");

    /// Restores global settings and closes idle workers.
    @AfterEach void restore() {
        NativeTransferTestSupport.clearIdle();
        if (oldPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", oldPath);
        if (oldEnabled == null) System.clearProperty("mfcl.native.modpack.enabled"); else System.setProperty("mfcl.native.modpack.enabled", oldEnabled);
    }

    /// Update comparison matches Java equality, including changed metadata and duplicate entries.
    @Test void updateComparisonMatchesJava() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"); assumeTrue(executable != null && !executable.isBlank());
        System.setProperty("mfcl.native.path", executable); System.clearProperty("mfcl.native.modpack.enabled");
        String a = "a".repeat(128), b = "b".repeat(128);
        var original = new ModrinthManifest.File("mods/a.jar", Map.of("sha512", a, "sha1", "x"), null, List.of("one", "two"), 10);
        var equal = new ModrinthManifest.File("mods/a.jar", Map.of("sha1", "x", "sha512", a), null, List.of("one", "two"), 10);
        var changedSize = new ModrinthManifest.File("mods/a.jar", Map.of("sha512", a, "sha1", "x"), null, List.of("one", "two"), 11);
        var changedHash = new ModrinthManifest.File("mods/a.jar", Map.of("sha512", b, "sha1", "x"), null, List.of("one", "two"), 10);
        var changedEnv = new ModrinthManifest.File("mods/a.jar", Map.of("sha512", a, "sha1", "x"), Map.of(), List.of("one", "two"), 10);
        var changedOrder = new ModrinthManifest.File("mods/a.jar", Map.of("sha512", a, "sha1", "x"), null, List.of("two", "one"), 10);
        List<ModrinthManifest.File> oldFiles = List.of(original, changedSize, changedHash, changedEnv, changedOrder, original);
        List<ModrinthManifest.File> newFiles = List.of(equal, changedHash);
        boolean @Nullable [] removed = NativeModrinthUpdate.tryFindRemoved(oldFiles, newFiles, () -> false);
        assertNotNull(removed); assertEquals(oldFiles.size(), removed.length);
        for (int i = 0; i < oldFiles.size(); i++) {
            ModrinthManifest.File old = oldFiles.get(i);
            assertEquals(newFiles.stream().noneMatch(old::equals), removed[i]);
        }
        assertThrows(InterruptedException.class, () -> NativeModrinthUpdate.tryFindRemoved(oldFiles, newFiles, () -> true));
        System.setProperty("mfcl.native.modpack.enabled", "false");
        assertNull(NativeModrinthUpdate.tryFindRemoved(oldFiles, newFiles, () -> false));
    }

    /// An isolated update removes exactly the entries selected by the native comparison.
    @Test void nativeUpdateRemovesOnlyObsoleteFiles() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"); assumeTrue(executable != null && !executable.isBlank());
        Path wrapper = temp.resolve("update-backend");
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
                            if request.get('method') == 'select_modrinth_removed' and 'result' in frame:
                                pathlib.Path(__file__ + '.compared').touch()
                            if request.get('method') == 'remove_managed_files' and 'result' in frame:
                                pathlib.Path(__file__ + '.removed').touch()
                            sys.stdout.write(response); sys.stdout.flush()
                            if 'result' in frame or 'error' in frame: break
                finally:
                    child.stdin.close(); child.wait(timeout=5)
                """);
        assertTrue(wrapper.toFile().setExecutable(true)); System.setProperty("mfcl.native.path", wrapper.toString());
        System.clearProperty("mfcl.native.modpack.enabled");
        String hash = "a".repeat(128); Map<String, String> hashes = Map.of("sha512", hash);
        var keep = new ModrinthManifest.File("mods/keep.jar", hashes, null, List.of("one"), 1);
        var obsolete = new ModrinthManifest.File("mods/old.jar", hashes, null, List.of("one"), 1);
        var changed = new ModrinthManifest.File("mods/changed.jar", hashes, null, List.of("one"), 1);
        var replacement = new ModrinthManifest.File("mods/changed.jar", hashes, null, List.of("two"), 1);
        Path root = temp.resolve("game"); Files.createDirectories(root.resolve("mods"));
        for (var file : List.of(keep, obsolete, changed)) Files.writeString(root.resolve(file.path()), "keep content");
        ModrinthInstallTask.removeObsoleteFiles(root, List.of(keep, obsolete, changed), List.of(keep, replacement), () -> false);
        assertTrue(Files.exists(Path.of(wrapper + ".compared")));
        if (org.jackhuang.hmcl.util.platform.OperatingSystem.CURRENT_OS == org.jackhuang.hmcl.util.platform.OperatingSystem.LINUX)
            assertTrue(Files.exists(Path.of(wrapper + ".removed")), "Native removal was not used");
        assertEquals("keep content", Files.readString(root.resolve(keep.path())));
        assertFalse(Files.exists(root.resolve(obsolete.path()))); assertFalse(Files.exists(root.resolve(changed.path())));
    }

    /// A partially completed native removal reports failure instead of retrying in Java.
    @Test void nativeRemovalDoesNotReplayAfterPartialFailure() throws Exception {
        assumeTrue(org.jackhuang.hmcl.util.platform.OperatingSystem.CURRENT_OS == org.jackhuang.hmcl.util.platform.OperatingSystem.LINUX);
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"); assumeTrue(executable != null && !executable.isBlank());
        System.setProperty("mfcl.native.path", executable); System.clearProperty("mfcl.native.modpack.enabled");
        Path root = temp.resolve("partial").toAbsolutePath().normalize(); Files.createDirectories(root.resolve("nonempty"));
        Path first = root.resolve("first.txt"), retained = root.resolve("nonempty/keep.txt");
        Files.writeString(first, "first"); Files.writeString(retained, "keep");
        assertThrows(java.io.IOException.class, () -> NativeManagedRemoval.tryRemove(root, List.of(first, root.resolve("nonempty")), () -> false));
        assertFalse(Files.exists(first)); assertEquals("keep", Files.readString(retained));
    }

    /// Invalid removal paths cannot escape the instance or cause earlier partial deletion.
    @Test void updateRejectsTraversalAndSymlinkAncestors() throws Exception {
        System.setProperty("mfcl.native.modpack.enabled", "false");
        Path root = temp.resolve("game"), outside = temp.resolve("outside"); Files.createDirectories(root); Files.createDirectories(outside);
        Files.writeString(root.resolve("safe.txt"), "safe"); Files.writeString(outside.resolve("secret.txt"), "secret");
        String hash = "a".repeat(128); Map<String, String> hashes = Map.of("sha512", hash);
        var safe = new ModrinthManifest.File("safe.txt", hashes, null, List.of("url"), 1);
        var traversal = new ModrinthManifest.File("../outside/secret.txt", hashes, null, List.of("url"), 1);
        assertThrows(java.io.IOException.class, () -> ModrinthInstallTask.removeObsoleteFiles(root, List.of(safe, traversal), List.of(), () -> false));
        assertEquals("safe", Files.readString(root.resolve("safe.txt")));
        assertEquals("secret", Files.readString(outside.resolve("secret.txt")));
        Path link = root.resolve("redirect");
        try { Files.createSymbolicLink(link, outside); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException e) { return; }
        var linked = new ModrinthManifest.File("redirect/secret.txt", hashes, null, List.of("url"), 1);
        assertThrows(java.io.IOException.class, () -> ModrinthInstallTask.removeObsoleteFiles(root, List.of(safe, linked), List.of(), () -> false));
        assertEquals("safe", Files.readString(root.resolve("safe.txt")));
        assertEquals("secret", Files.readString(outside.resolve("secret.txt")));
    }

    /// Null map values and missing map keys remain distinct during update comparison.
    @Test void updateComparisonPreservesNullMapValues() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"); assumeTrue(executable != null && !executable.isBlank());
        System.setProperty("mfcl.native.path", executable); System.clearProperty("mfcl.native.modpack.enabled");
        Map<String, String> withNull = new java.util.HashMap<>(); withNull.put("sha512", "a"); withNull.put("other", null);
        var left = new ModrinthManifest.File("mods/a.jar", withNull, null, List.of("url"), 1);
        var right = new ModrinthManifest.File("mods/a.jar", Map.of("sha512", "a"), null, List.of("url"), 1);
        assertFalse(left.equals(right));
        assertArrayEquals(new boolean[]{true}, NativeModrinthUpdate.tryFindRemoved(List.of(left), List.of(right), () -> false));
    }

    /// Environment, download availability and exclusions preserve Java ordering.
    @Test void selectionMatchesJavaAndFallsBack() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"); assumeTrue(executable != null && !executable.isBlank());
        System.setProperty("mfcl.native.path", executable); System.clearProperty("mfcl.native.modpack.enabled");
        String hash = "a".repeat(128); Map<String, String> hashes = Map.of("sha512", hash);
        List<ModrinthManifest.File> files = List.of(
                new ModrinthManifest.File("mods/required.jar", hashes, null, List.of("https://example.test/a"), 1),
                new ModrinthManifest.File("mods/server.jar", hashes, Map.of("client", "unsupported"), List.of("https://example.test/b"), 1),
                new ModrinthManifest.File("mods/no-url.jar", hashes, null, List.of(), 1),
                new ModrinthManifest.File("mods/optional.jar", hashes, Map.of("client", "optional"), List.of("https://example.test/c"), 1));
        Set<String> excluded = Set.of(files.get(3).key());
        boolean @Nullable [] selected = NativeModrinthSelection.trySelect(files, excluded, () -> false);
        assertNotNull(selected); assertArrayEquals(new boolean[]{true, false, false, false}, selected);
        assertArrayEquals(new boolean[]{true, false, false, true}, NativeModrinthSelection.trySelect(files, null, () -> false));
        Map<Integer, NativeBackend.ModrinthFilePlan> plan = NativeModrinthSelection.tryPlan(temp, files, excluded, () -> false);
        assertNotNull(plan); assertEquals(Map.of(0, new NativeBackend.ModrinthFilePlan(temp.resolve("mods/required.jar"), hash, true)), plan);
        List<ModrinthManifest.File> traversal = List.of(new ModrinthManifest.File("../outside", hashes, null, List.of("https://example.test/x"), 1));
        assertNull(NativeModrinthSelection.tryPlan(temp, traversal, null, () -> false));
        List<ModrinthManifest.File> invalidHash = List.of(new ModrinthManifest.File("config/bad.txt", Map.of("sha512", "xyz"), null,
                List.of("https://example.test/x"), 1));
        var invalidPlan = NativeModrinthSelection.tryPlan(temp, invalidHash, null, () -> false);
        assertNotNull(invalidPlan); assertFalse(invalidPlan.get(0).validSha512());
        assertThrows(InterruptedException.class, () -> NativeModrinthSelection.trySelect(files, excluded, () -> true));
        System.setProperty("mfcl.native.modpack.enabled", "false");
        assertNull(NativeModrinthSelection.trySelect(files, excluded, () -> false));
    }
}
