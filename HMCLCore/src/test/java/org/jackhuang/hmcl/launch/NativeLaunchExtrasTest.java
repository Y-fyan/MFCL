/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.launch;

import org.jackhuang.hmcl.game.ProxyOption;
import org.jackhuang.hmcl.game.QuickPlayOption;
import org.jackhuang.hmcl.util.ServerAddress;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Compares Rust optional launch arguments with the existing Java address parser.
@NotNullByDefault
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class NativeLaunchExtrasTest {
    /// Previous backend path.
    private final @Nullable String previousPath = System.getProperty("mfcl.native.path");
    /// Previous native launch setting.
    private final @Nullable String previousEnabled = System.getProperty("mfcl.native.launch.enabled");

    /// Restores global process settings.
    @AfterEach void restore() {
        if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
        if (previousEnabled == null) System.clearProperty("mfcl.native.launch.enabled"); else System.setProperty("mfcl.native.launch.enabled", previousEnabled);
    }

    /// Address parsing, option ordering, credentials and fullscreen match Java.
    @Test void matchesJavaAddressHandling() throws Exception {
        enableNative();
        var socks = new ProxyOption.Socks("代理.example", 1080, "user", "secret");
        for (String address : List.of("example.org", "example.org:25566", "[::1]", "[::1]:+0004", ":0", "example.org:-0", "玩家.example")) {
            ServerAddress parsed = ServerAddress.parse(address);
            for (boolean supported : List.of(false, true)) {
                List<String> expected = new ArrayList<>();
                if (supported) expected.addAll(List.of("--quickPlayMultiplayer", parsed.port() >= 0 ? address : parsed.host() + ":25565"));
                else expected.addAll(List.of("--server", parsed.host(), "--port", parsed.port() >= 0 ? String.valueOf(parsed.port()) : "25565"));
                expected.addAll(List.of("--fullscreen", "--proxyHost", "代理.example", "--proxyPort", "1080", "--proxyUser", "user", "--proxyPass", "secret", "--graphicsBackend", "vulkan"));
                assertEquals(expected, NativeLaunchExtras.tryPlan(new QuickPlayOption.MultiPlayer(address), supported, true, socks, "vulkan"));
            }
        }
        assertEquals(List.of("--quickPlaySingleplayer", "世界"), NativeLaunchExtras.tryPlan(new QuickPlayOption.SinglePlayer("世界"), true, false, null, null));
        assertEquals(List.of(), NativeLaunchExtras.tryPlan(new QuickPlayOption.Realm("123"), false, false, null, null));
        assertEquals(List.of("--quickPlayRealms", "123"), NativeLaunchExtras.tryPlan(new QuickPlayOption.Realm("123"), true, false, null, null));
    }

    /// Invalid and unsupported numeric input returns to the existing Java code.
    @Test void unsupportedAddressFallsBack() throws Exception {
        enableNative();
        assertNull(NativeLaunchExtras.tryPlan(new QuickPlayOption.MultiPlayer("host:bad"), true, false, null, null));
        assertNull(NativeLaunchExtras.tryPlan(new QuickPlayOption.MultiPlayer("host:１２"), true, false, null, null));
        assertNull(NativeLaunchExtras.tryPlan(new QuickPlayOption.SinglePlayer("\ud800"), true, false, null, null));
        System.setProperty("mfcl.native.launch.enabled", "false");
        assertNull(NativeLaunchExtras.tryPlan(new QuickPlayOption.SinglePlayer("world"), true, false, null, null));
    }

    /// Selects the actual local test backend.
    private void enableNative() {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        System.setProperty("mfcl.native.path", executable); System.clearProperty("mfcl.native.launch.enabled");
    }
}
