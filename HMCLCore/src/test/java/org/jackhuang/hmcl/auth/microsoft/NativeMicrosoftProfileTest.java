/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.auth.microsoft;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Checks native Microsoft profile validation using synthetic backend responses only.
@NotNullByDefault
@EnabledOnOs({OS.LINUX, OS.MAC})
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class NativeMicrosoftProfileTest {
    /// Test-owned backend and response fixtures.
    @TempDir Path temp;
    /// Original native path.
    private final @Nullable String oldPath = System.getProperty("mfcl.native.path");
    /// Original native Microsoft setting.
    private final @Nullable String oldEnabled = System.getProperty("mfcl.native.microsoft.enabled");
    /// Original JVM proxy selector.
    private final @Nullable ProxySelector oldSelector = ProxySelector.getDefault();

    /// Restores process-wide test settings.
    @AfterEach void restore() {
        if (oldPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", oldPath);
        if (oldEnabled == null) System.clearProperty("mfcl.native.microsoft.enabled"); else System.setProperty("mfcl.native.microsoft.enabled", oldEnabled);
        ProxySelector.setDefault(oldSelector);
    }

    /// Valid profiles and authorization errors retain the Java validator's semantics.
    @Test void profileStatusAndCompatibilityFallback() throws Exception {
        Path backend = temp.resolve("backend");
        Files.writeString(backend, """
                #!/usr/bin/env python3
                import base64, json, pathlib, sys
                req = json.loads(sys.stdin.readline())
                if req['method'] == 'health':
                    result = {'name':'mfcl-native', 'protocol':1, 'capabilities':['microsoft_profile_read']}
                else:
                    status = int(pathlib.Path(__file__ + '.status').read_text())
                    body = b'{"id":"00112233445566778899aabbccddeeff","name":"Steve","skins":[],"capes":[]}'
                    result = {'status':status, 'body_base64':base64.b64encode(body).decode(), 'content_type':'application/json'}
                    if pathlib.Path(__file__ + '.projected').exists():
                        result['body_base64'] = base64.b64encode(b'not-json').decode()
                        result['profile_v1'] = {'id':'00112233445566778899aabbccddeeff','name':'Steve',
                            'skins':[{'id':'s','state':'ACTIVE','url':'https://example.test/s','variant':'SLIM','alias':None}], 'capes':[{},None]}
                print(json.dumps({'version':1, 'id':req['id'], 'result':result}))
                """);
        assertTrue(backend.toFile().setExecutable(true));
        System.setProperty("mfcl.native.path", backend.toString()); System.clearProperty("mfcl.native.microsoft.enabled");
        ProxySelector.setDefault(new ProxySelector() {
            /// Selects direct routes for the synthetic test.
            @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
            /// No network requests are made by the synthetic backend.
            @Override public void connectFailed(URI uri, SocketAddress address, java.io.IOException failure) { }
        });
        Path status = Path.of(backend + ".status");
        Files.writeString(status, "200"); assertEquals(Boolean.TRUE, NativeMicrosoftProfile.tryValidate("Bearer", "fixture-token"));
        @Nullable MicrosoftService.MinecraftProfileResponse profile = NativeMicrosoftProfile.tryCompleteProfile("Bearer fixture-token");
        assertNotNull(profile); assertEquals("Steve", profile.name);
        Files.createFile(Path.of(backend + ".projected"));
        @Nullable MicrosoftService.MinecraftProfileResponse projected = NativeMicrosoftProfile.tryCompleteProfile("Bearer fixture-token");
        assertNotNull(projected); assertEquals("Steve", projected.name); assertEquals("SLIM", projected.skins.get(0).variant);
        assertEquals(2, projected.capes.size()); assertNull(projected.capes.get(1));
        assertEquals(Boolean.TRUE, NativeMicrosoftProfile.tryValidate("Bearer", "fixture-token"));
        Files.delete(Path.of(backend + ".projected"));
        Files.writeString(status, "401"); assertEquals(Boolean.FALSE, NativeMicrosoftProfile.tryValidate("Bearer", "fixture-token"));
        assertNull(NativeMicrosoftProfile.tryCompleteProfile("Bearer fixture-token"));
        Files.writeString(status, "404"); assertNull(NativeMicrosoftProfile.tryValidate("Bearer", "fixture-token"));
        assertNull(NativeMicrosoftProfile.tryValidate("Bearer", "bad\nheader"));
        assertNull(NativeMicrosoftProfile.tryCompleteProfile("no-separator"));
        assertNull(NativeMicrosoftProfile.tryCompleteProfile("Bearer  token"));
    }
}
