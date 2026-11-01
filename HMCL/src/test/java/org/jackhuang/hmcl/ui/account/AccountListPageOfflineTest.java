/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.ui.account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class AccountListPageOfflineTest {
    @Test void mfclOffersOfflineAccountsEvenWhenRestrictedPropertyIsSet() {
        org.jackhuang.hmcl.JavaFXLauncher.start();
        assumeTrue(org.jackhuang.hmcl.JavaFXLauncher.isStarted(), "JavaFX toolkit unavailable");
        String previous = System.getProperty("hmcl.offline.auth.restricted");
        try {
            System.setProperty("hmcl.offline.auth.restricted", "true");
            assertFalse(AccountListPage.RESTRICTED.get());
        } finally {
            if (previous == null) System.clearProperty("hmcl.offline.auth.restricted");
            else System.setProperty("hmcl.offline.auth.restricted", previous);
        }
    }
}
