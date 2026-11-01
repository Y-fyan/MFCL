/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.auth.yggdrasil;

import org.glavo.url.WebURL;
import org.jetbrains.annotations.Nullable;

/// Exposes the compatible read-only authlib metadata transport.
public final class NativeAuthlibTransport {
    private NativeAuthlibTransport() { }

    public static @Nullable String tryGetMetadata(WebURL url) throws InterruptedException {
        return NativeYggdrasilTransport.tryGetAuthlibMetadata(url);
    }

    public static @Nullable String tryLocate(WebURL url) throws InterruptedException {
        return NativeYggdrasilTransport.tryLocateAuthlib(url);
    }
}
