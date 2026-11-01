/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.auth.microsoft;

import org.jackhuang.hmcl.auth.NoSelectedCharacterException;
import org.jackhuang.hmcl.auth.OAuth;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies native device-flow failure and cancellation without remote services.
@NotNullByDefault
@EnabledOnOs({OS.LINUX, OS.MAC})
final class NativeMicrosoftDeviceTest {
    /// Isolated backend scripts and markers.
    @TempDir Path temp;

    /// A declined device request is surfaced once and is never replayed.
    @Test void declinedAuthorizationIsNotReplayed() throws Exception {
        Path backend = backend(false); Callback callback = new Callback();
        assertThrows(NoSelectedCharacterException.class, () -> new NativeMicrosoftAuth(backend, callback).authenticate());
        assertEquals("ABCD", callback.code); assertEquals(1, callback.opened); assertFalse(callback.completed);
        assertEquals(1, Files.readAllLines(Path.of(backend + ".starts")).size());
    }

    /// Native exchange failures preserve the launcher's license and profile error types.
    @Test void exchangeFailuresPreserveErrorTypes() throws Exception {
        for (String code : new String[]{"license_not_found", "profile_not_found", "xbox_family_required", "xbox_bad_request", "xbox_authorization"}) {
            Path script = temp.resolve(code);
            Files.writeString(script, """
                    #!/usr/bin/env python3
                    import json, sys
                    if sys.argv[1:] == ['--microsoft-device-session-child']:
                        sys.stdin.readline()
                        print(json.dumps({'event':'device','user_code':'ABCD','verification_uri':'https://microsoft.com/devicelogin'}), flush=True)
                        sys.stdin.readline()
                        print(json.dumps({'event':'authorized'}), flush=True)
                        sys.stdin.readline()
                        print(json.dumps({'event':'error','code':'ERROR_CODE','xbox':{'error_code':2148999999,'redirect':'https://example.test/help'}}), flush=True)
                    else:
                        request = json.loads(sys.stdin.readline())
                        print(json.dumps({'version':1,'id':request['id'],'result':{'name':'mfcl-native','protocol':1,'capabilities':['microsoft_device_session_v2']}}), flush=True)
                    """.replace("ERROR_CODE", code));
            assertTrue(script.toFile().setExecutable(true)); Callback callback = new Callback();
            if ("license_not_found".equals(code))
                assertThrows(MicrosoftService.MinecraftJavaEditionLicenseNotFoundException.class, () -> new NativeMicrosoftAuth(script, callback).authenticate());
            else if ("profile_not_found".equals(code))
                assertThrows(MicrosoftService.MinecraftJavaEditionProfileNotFoundException.class, () -> new NativeMicrosoftAuth(script, callback).authenticate());
            else if ("xbox_bad_request".equals(code))
                assertThrows(MicrosoftService.XBox400Exception.class, () -> new NativeMicrosoftAuth(script, callback).authenticate());
            else if ("xbox_authorization".equals(code)) {
                var error = assertThrows(MicrosoftService.XboxAuthorizationException.class, () -> new NativeMicrosoftAuth(script, callback).authenticate());
                assertEquals(2148999999L, error.getErrorCode()); assertEquals("https://example.test/help", error.getRedirect());
            } else {
                var error = assertThrows(MicrosoftService.XboxAuthorizationException.class, () -> new NativeMicrosoftAuth(script, callback).authenticate());
                assertEquals(MicrosoftService.XboxAuthorizationException.ADD_FAMILY, error.getErrorCode());
            }
            assertTrue(callback.completed); assertEquals(1, callback.opened);
        }
    }

    /// Interrupting the waiting caller terminates the dedicated device process.
    @Test void cancellationTerminatesDeviceProcess() throws Exception {
        Path backend = backend(true); Callback callback = new Callback(); AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try { new NativeMicrosoftAuth(backend, callback).authenticate(); failure.set(new AssertionError("Expected interruption")); }
            catch (InterruptedException expected) { }
            catch (Throwable e) { failure.set(e); }
        });
        thread.setDaemon(true); thread.start(); Path pidFile = Path.of(backend + ".pid");
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(pidFile) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(pidFile)); long pid = Long.parseLong(Files.readString(pidFile));
            thread.interrupt(); thread.join(5000); assertFalse(thread.isAlive()); assertNull(failure.get());
            ProcessHandle.of(pid).ifPresent(process -> assertDoesNotThrow(() -> process.onExit().get(3, TimeUnit.SECONDS)));
            assertFalse(callback.completed);
        } finally { thread.interrupt(); thread.join(5000); }
    }

    /// Creates a fake backend advertising device streaming.
    /// @param block whether polling should block until cancellation
    /// @return executable path
    /// @throws Exception if fixture creation fails
    private Path backend(boolean block) throws Exception {
        Path script = temp.resolve(block ? "blocking-device" : "declined-device");
        String code = """
                #!/usr/bin/env python3
                import json, os, pathlib, sys, time
                if sys.argv[1:] == ['--microsoft-device-session-child']:
                    with open(__file__ + '.starts', 'a') as f: f.write('start\\n')
                    sys.stdin.readline()
                    print(json.dumps({'event':'device','user_code':'ABCD','verification_uri':'https://microsoft.com/devicelogin'}), flush=True)
                    sys.stdin.readline()
                    pathlib.Path(__file__ + '.pid').write_text(str(os.getpid()))
                    if BLOCK: time.sleep(30)
                    print(json.dumps({'event':'error','code':'authorization_declined'}), flush=True)
                else:
                    request = json.loads(sys.stdin.readline())
                    print(json.dumps({'version':1,'id':request['id'],'result':{'name':'mfcl-native','protocol':1,'capabilities':['microsoft_device_session_v2']}}), flush=True)
                """.replace("BLOCK", block ? "True" : "False");
        Files.writeString(script, code); assertTrue(script.toFile().setExecutable(true)); return script;
    }

    /// Records synthetic user-facing device events.
    private static final class Callback implements OAuth.Callback {
        /// Last displayed code.
        private String code = "";
        /// Browser-open count.
        private int opened;
        /// Whether the flow reported completion.
        private boolean completed;
        /// Device flow does not use a local server.
        @Override public OAuth.Session startServer() { throw new AssertionError("Unexpected authorization-code flow"); }
        /// Records the displayed code.
        @Override public void grantDeviceCode(String value, String uri) { code = value; }
        /// Records completion.
        @Override public void loginCompletedDeviceCode() { completed = true; }
        /// Records the browser event without opening a browser.
        @Override public void openBrowser(OAuth.GrantFlow flow, String url) { opened++; }
        /// Returns a synthetic client identifier.
        @Override public String getClientId() { return "fixture-client"; }
    }
}
