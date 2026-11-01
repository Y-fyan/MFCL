/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.game;

import org.jackhuang.hmcl.util.Lang;
import org.jackhuang.hmcl.util.io.NetworkUtils;
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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Compares isolated Rust OAuth callback parsing with the launcher's Java parser.
@NotNullByDefault
@EnabledOnOs({OS.LINUX, OS.MAC})
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class NativeOAuthQueryTest {
    /// Test-owned wrapper directory.
    @TempDir Path temp;
    /// Original native backend path.
    private final @Nullable String oldPath = System.getProperty("mfcl.native.path");
    /// Original Microsoft setting.
    private final @Nullable String oldEnabled = System.getProperty("mfcl.native.microsoft.enabled");

    /// Restores global settings.
    @AfterEach void restore() {
        if (oldPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", oldPath);
        if (oldEnabled == null) System.clearProperty("mfcl.native.microsoft.enabled"); else System.setProperty("mfcl.native.microsoft.enabled", oldEnabled);
    }

    /// Callback handling keeps state validation for successful and rejected responses.
    @Test void callbackHandlerRetainsStateValidation() throws Exception {
        @Nullable String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"); assumeTrue(binary != null && !binary.isBlank());
        Path wrapper = temp.resolve("oauth-live-backend");
        Files.writeString(wrapper, "#!/bin/sh\nprintf '%s\\n' run >> \"$0.pids\"\nexec '" + binary.replace("'", "'\\''") + "' \"$@\"\n");
        assertTrue(wrapper.toFile().setExecutable(true)); System.setProperty("mfcl.native.path", wrapper.toString());
        System.clearProperty("mfcl.native.microsoft.enabled");
        for (boolean correct : new boolean[]{true, false}) {
            var constructor = OAuthServer.class.getDeclaredConstructor(int.class); constructor.setAccessible(true);
            OAuthServer server = constructor.newInstance(29111);
            try {
                String state = correct ? server.getState() : "wrong-state";
                server.handleQuery("code=fixture-code&state=" + state);
                if (correct) assertEquals("fixture-code", server.waitFor());
                else assertTrue(assertThrows(java.util.concurrent.ExecutionException.class, server::waitFor).getCause()
                        instanceof org.jackhuang.hmcl.auth.AuthenticationException);
            } finally { server.close(); }
        }
        assertEquals(2, Files.readAllLines(Path.of(wrapper + ".pids")).size());
    }

    /// Normal callbacks match Java; ambiguous forms fall back and workers are not reused.
    @Test void callbackParsingMatchesJava() throws Exception {
        @Nullable String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"); assumeTrue(binary != null && !binary.isBlank());
        Path wrapper = temp.resolve("oauth-backend");
        Files.writeString(wrapper, "#!/bin/sh\nprintf '%s\\n' run >> \"$0.pids\"\nexec '" + binary.replace("'", "'\\''") + "' \"$@\"\n");
        assertTrue(wrapper.toFile().setExecutable(true)); System.setProperty("mfcl.native.path", wrapper.toString());
        System.clearProperty("mfcl.native.microsoft.enabled");
        for (String raw : new String[]{"code=sample%2Bcode&state=sample-state&id_token=header.payload.sig", "error=denied&state=sample-state"}) {
            Map<String, String> expected = Lang.mapOf(NetworkUtils.parseQuery(raw));
            expected.keySet().retainAll(Set.of("code", "state", "id_token"));
            assertEquals(expected, NativeOAuthQuery.tryParse(raw));
        }
        assertEquals(2, Files.readAllLines(Path.of(wrapper + ".pids")).size());
        assertNull(NativeOAuthQuery.tryParse("code=one&code=two&state=x"));
        assertNull(NativeOAuthQuery.tryParse("code=&state=x"));
        assertNull(NativeOAuthQuery.tryParse("code=%FF&state=x"));
        System.setProperty("mfcl.native.microsoft.enabled", "false");
        assertNull(NativeOAuthQuery.tryParse("code=sample&state=x"));
    }
}
