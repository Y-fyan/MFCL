/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.auth.yggdrasil;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.jackhuang.hmcl.util.gson.ValidationTypeAdapterFactory;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Compares native complete-profile parsing with the existing Gson adapter.
@NotNullByDefault
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class NativeYggdrasilProfileTest {
    /// Previous native executable setting.
    private final @Nullable String previousPath = System.getProperty("mfcl.native.path");
    /// Previous native account setting.
    private final @Nullable String previousEnabled = System.getProperty("mfcl.native.yggdrasil.enabled");

    /// Restores global settings.
    @AfterEach void restore() {
        if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
        if (previousEnabled == null) System.clearProperty("mfcl.native.yggdrasil.enabled"); else System.setProperty("mfcl.native.yggdrasil.enabled", previousEnabled);
    }

    /// Supported profiles preserve UUID, name and ordered property overwrite behavior.
    @Test void completeProfilesMatchJavaAndUnsupportedInputsFallBack() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"); assumeTrue(executable != null && !executable.isBlank());
        System.setProperty("mfcl.native.path", Path.of(executable).toString()); System.clearProperty("mfcl.native.yggdrasil.enabled");
        Gson javaParser = new GsonBuilder().registerTypeAdapterFactory(ValidationTypeAdapterFactory.INSTANCE).create();
        String profile = "{\"id\":\"0123456789ABCDEF0123456789ABCDEF\",\"name\":\"Alex\",\"properties\":[null,7,"
                + "{\"name\":\"textures\",\"value\":\"first\"},{\"name\":\"other\",\"value\":\"middle\"},{\"name\":\"textures\",\"value\":\"second\"}]}";
        CompleteGameProfile expected = javaParser.fromJson(profile, CompleteGameProfile.class);
        @Nullable CompleteGameProfile nativeProfile = NativeYggdrasilProfile.tryParse(profile);
        assertNotNull(nativeProfile); assertEquals(expected.getId(), nativeProfile.getId()); assertEquals(expected.getName(), nativeProfile.getName());
        assertEquals(expected.getProperties(), nativeProfile.getProperties());
        assertEquals(new ArrayList<>(expected.getProperties().keySet()), new ArrayList<>(nativeProfile.getProperties().keySet()));
        assertEquals("second", nativeProfile.getProperties().get("textures"));
        assertThrows(UnsupportedOperationException.class, () -> nativeProfile.getProperties().put("unexpected", "value"));
        String coercion = "{\"id\":\"0123456789abcdef0123456789abcdef\",\"name\":\"Alex\",\"properties\":[{\"name\":7,\"value\":42}]}";
        assertNull(NativeYggdrasilProfile.tryParse(coercion));
        assertEquals("42", javaParser.fromJson(coercion, CompleteGameProfile.class).getProperties().get("7"));
        String duplicate = "{\"id\":\"00000000000000000000000000000000\",\"id\":\"0123456789abcdef0123456789abcdef\",\"name\":\"Alex\",\"properties\":[]}";
        assertNull(NativeYggdrasilProfile.tryParse(duplicate));
        assertEquals("Alex", javaParser.fromJson(duplicate, CompleteGameProfile.class).getName());
        String missing = "{\"id\":\"0123456789abcdef0123456789abcdef\",\"name\":\"Alex\"}";
        assertNull(NativeYggdrasilProfile.tryParse(missing));
        assertThrows(JsonParseException.class, () -> javaParser.fromJson(missing, CompleteGameProfile.class));
        assertNull(NativeYggdrasilProfile.tryParse("null"));
    }
}
