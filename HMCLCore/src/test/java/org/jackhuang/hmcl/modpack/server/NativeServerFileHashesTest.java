/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.modpack.server;

import org.jackhuang.hmcl.modpack.ModpackConfiguration;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jackhuang.hmcl.nativebridge.NativeTransferTestSupport;
import org.jackhuang.hmcl.util.DigestUtils;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Verifies server-pack native observations preserve modified files.
@NotNullByDefault
@EnabledOnOs({OS.LINUX, OS.MAC})
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class NativeServerFileHashesTest {
    /// Isolated instance root.
    @TempDir Path temp;
    /// Original backend setting.
    private final @Nullable String oldPath = System.getProperty("mfcl.native.path");
    /// Original modpack setting.
    private final @Nullable String oldEnabled = System.getProperty("mfcl.native.modpack.enabled");

    /// Restores global properties and closes idle workers.
    @AfterEach void restore() {
        NativeTransferTestSupport.clearIdle();
        if (oldPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", oldPath);
        if (oldEnabled == null) System.clearProperty("mfcl.native.modpack.enabled"); else System.setProperty("mfcl.native.modpack.enabled", oldEnabled);
    }

    /// Server-pack removals use the shared native implementation and protect other files.
    @Test void serverRemovalUsesGuardedNativePath() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"); assumeTrue(executable != null && !executable.isBlank());
        Path wrapper = temp.resolve("remove-backend");
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
                            if request.get('method') == 'remove_managed_files' and 'result' in frame:
                                pathlib.Path(__file__ + '.removed').touch()
                            sys.stdout.write(response); sys.stdout.flush()
                            if 'result' in frame or 'error' in frame: break
                finally:
                    child.stdin.close(); child.wait(timeout=5)
                """);
        assertTrue(wrapper.toFile().setExecutable(true)); System.setProperty("mfcl.native.path", wrapper.toString());
        System.clearProperty("mfcl.native.modpack.enabled");
        Path root = temp.resolve("game").toAbsolutePath().normalize(); Files.createDirectories(root.resolve("mods"));
        Path kept = root.resolve("mods/keep.jar"), obsolete = root.resolve("mods/old.jar");
        Files.writeString(kept, "keep"); Files.writeString(obsolete, "old");
        var oldFiles = List.of(new ModpackConfiguration.FileInformation("mods/keep.jar", "a"),
                new ModpackConfiguration.FileInformation("mods/old.jar", "b"));
        ServerModpackCompletionTask.removeObsoleteFiles(root, oldFiles, Set.of("mods/keep.jar"), () -> false);
        assertEquals("keep", Files.readString(kept)); assertFalse(Files.exists(obsolete));
        if (org.jackhuang.hmcl.util.platform.OperatingSystem.CURRENT_OS == org.jackhuang.hmcl.util.platform.OperatingSystem.LINUX)
            assertTrue(Files.exists(Path.of(wrapper + ".removed")), "Native removal was not used");
        Path outside = temp.resolve("outside.txt"); Files.writeString(outside, "outside");
        var unsafe = new ModpackConfiguration.FileInformation("../outside.txt", "x");
        assertThrows(java.io.IOException.class, () -> ServerModpackCompletionTask.removeObsoleteFiles(root, List.of(unsafe), Set.of(), () -> false));
        assertEquals("outside", Files.readString(outside));
    }

    /// Native observations and stale-file fallback match Java's case-sensitive decisions.
    @Test void observationsPreserveExistingFileDecisions() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"); assumeTrue(executable != null && !executable.isBlank());
        System.setProperty("mfcl.native.path", executable); System.clearProperty("mfcl.native.modpack.enabled");
        Path root = temp.toAbsolutePath().normalize(), mods = root.resolve("mods"); Files.createDirectories(mods);
        Path good = root.resolve("good.txt"), modified = root.resolve("modified.txt"), upper = root.resolve("upper.txt"), disabled = mods.resolve("a.jar");
        Files.writeString(good, "original"); Files.writeString(modified, "changed"); Files.writeString(upper, "original");
        Files.writeString(disabled, "original"); Files.writeString(mods.resolve("a.jar.disabled"), "disabled");
        String originalHash = DigestUtils.digestToString("SHA-1", good);
        var oldGood = new ModpackConfiguration.FileInformation("good.txt", originalHash);
        var oldModified = new ModpackConfiguration.FileInformation("modified.txt", originalHash);
        var oldUpper = new ModpackConfiguration.FileInformation("upper.txt", originalHash.toUpperCase(Locale.ROOT));
        var oldDisabled = new ModpackConfiguration.FileInformation("mods/a.jar", originalHash);
        var old = Map.of("good.txt", oldGood, "modified.txt", oldModified, "upper.txt", oldUpper, "mods/a.jar", oldDisabled);
        var remote = List.of(oldGood, oldModified, oldUpper, oldDisabled, new ModpackConfiguration.FileInformation("missing", originalHash));
        Map<Integer, NativeBackend.FileHashResult> observed = NativeServerFileHashes.inspect(root, mods, old, remote, () -> false);
        assertTrue(observed.containsKey(0)); assertTrue(observed.containsKey(1));
        assertFalse(observed.containsKey(2)); assertFalse(observed.containsKey(3)); assertFalse(observed.containsKey(4));
        String nextHash = "0".repeat(40);
        assertTrue(ServerModpackCompletionTask.shouldReplaceExisting(good, originalHash, nextHash, observed.get(0)));
        assertFalse(ServerModpackCompletionTask.shouldReplaceExisting(modified, originalHash, nextHash, observed.get(1)));
        assertFalse(ServerModpackCompletionTask.shouldReplaceExisting(upper, oldUpper.getHash(), nextHash, null));
        Files.writeString(good, "different");
        assertFalse(ServerModpackCompletionTask.shouldReplaceExisting(good, originalHash, nextHash, observed.get(0)));
        assertThrows(InterruptedException.class, () -> NativeServerFileHashes.inspect(root, mods, old, remote, () -> true));
    }
}
