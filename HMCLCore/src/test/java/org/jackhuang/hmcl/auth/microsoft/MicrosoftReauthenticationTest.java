/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.auth.microsoft;

import org.jackhuang.hmcl.auth.OAuth;
import org.jackhuang.hmcl.auth.OAuthAccount;
import org.jackhuang.hmcl.auth.ServerResponseMalformedException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class MicrosoftReauthenticationTest {
    private static final OAuth.Callback CALLBACK = new OAuth.Callback() {
        @Override public OAuth.Session startServer() { throw new UnsupportedOperationException(); }
        @Override public void grantDeviceCode(String code, String uri) { }
        @Override public void loginCompletedDeviceCode() { }
        @Override public void openBrowser(OAuth.GrantFlow flow, String url) { }
        @Override public String getClientId() { return "fixture"; }
    };

    @Test void resolvesMissingProfileBeforeVerifyingIdentity() throws Exception {
        UUID expected = UUID.randomUUID(), other = UUID.randomUUID();
        var pending = session(null, null);
        var service = new StubService(pending, session(expected, "Steve"));
        assertEquals(expected, MicrosoftAccount.reauthenticate(service, expected).profile().id());
        assertEquals(1, service.refreshes);
        service.refreshed = session(other, "Alex");
        assertThrows(OAuthAccount.WrongAccountException.class, () -> MicrosoftAccount.reauthenticate(service, expected));
        service.refreshed = pending;
        assertThrows(ServerResponseMalformedException.class, () -> MicrosoftAccount.reauthenticate(service, expected));
        service.refreshed = session(expected, " ");
        assertThrows(ServerResponseMalformedException.class, () -> MicrosoftAccount.reauthenticate(service, expected));
    }

    private static MicrosoftSession session(UUID id, String name) {
        return new MicrosoftSession("Bearer", "test-token", 0, "test-refresh", new MicrosoftSession.User("test"),
                id == null ? null : new MicrosoftSession.GameProfile(id, name));
    }

    private static final class StubService extends MicrosoftService {
        final MicrosoftSession pending;
        MicrosoftSession refreshed;
        int refreshes;
        StubService(MicrosoftSession pending, MicrosoftSession refreshed) { super(CALLBACK); this.pending = pending; this.refreshed = refreshed; }
        @Override public MicrosoftSession authenticate(OAuth.GrantFlow flow) { return pending; }
        @Override public MicrosoftSession refresh(MicrosoftSession old) { refreshes++; return refreshed; }
    }
}
