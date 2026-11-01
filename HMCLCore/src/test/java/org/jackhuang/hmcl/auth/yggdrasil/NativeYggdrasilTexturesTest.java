/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.auth.yggdrasil;

import org.jackhuang.hmcl.auth.ServerResponseMalformedException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Compares Rust texture decoding with the existing Java account parser.
@NotNullByDefault
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class NativeYggdrasilTexturesTest {
    /// Previous backend path.
    private final @Nullable String previousPath = System.getProperty("mfcl.native.path");
    /// Previous account integration setting.
    private final @Nullable String previousEnabled = System.getProperty("mfcl.native.yggdrasil.enabled");

    /// Restores global process settings.
    @AfterEach void restore() {
        if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
        if (previousEnabled == null) System.clearProperty("mfcl.native.yggdrasil.enabled"); else System.setProperty("mfcl.native.yggdrasil.enabled", previousEnabled);
    }

    /// Supported and unusual texture maps retain Java's value, order and fallback behavior.
    @Test void textureMapsMatchJavaAndUnsupportedInputsFallBack() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"); assumeTrue(executable != null && !executable.isBlank());
        System.setProperty("mfcl.native.path", Path.of(executable).toString()); System.clearProperty("mfcl.native.yggdrasil.enabled");
        String ordinary = "{\"textures\":{\"SKIN\":{\"url\":\"https://example.invalid/skin\",\"metadata\":{\"model\":\"slim\",\"optional\":null}},\"CAPE\":null}}";
        @Unmodifiable List<String> fixtures = List.of(ordinary, "{\"timestamp\":1}", "{\"textures\":{}}",
                "{\"textures\":{\"ELYTRA\":{\"url\":\"https://example.invalid\"}}}",
                "{\"textures\":{\"SKIN\":{\"url\":42}}}");
        for (String json : fixtures) {
            String encoded = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
            CompleteGameProfile profile = new CompleteGameProfile(UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"), "Steve", Map.of("textures", encoded));
            System.setProperty("mfcl.native.yggdrasil.enabled", "false"); var javaResult = YggdrasilService.getTextures(profile);
            System.clearProperty("mfcl.native.yggdrasil.enabled"); var nativeResult = YggdrasilService.getTextures(profile);
            assertEquals(javaResult, nativeResult);
            if (javaResult.isPresent()) assertEquals(new ArrayList<>(javaResult.orElseThrow().keySet()), new ArrayList<>(nativeResult.orElseThrow().keySet()));
        }
        @Nullable NativeYggdrasilTextures.Result parsed = NativeYggdrasilTextures.tryParse(Base64.getEncoder().encodeToString(ordinary.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(parsed); assertTrue(parsed.present()); assertNotNull(parsed.textures()); assertTrue(parsed.textures().containsKey(TextureType.CAPE));
        assertNotNull(NativeYggdrasilTextures.tryParse(Base64.getEncoder().withoutPadding().encodeToString(ordinary.getBytes(StandardCharsets.UTF_8))));
        String unknown = Base64.getEncoder().encodeToString("{\"textures\":{\"ELYTRA\":{}}}".getBytes(StandardCharsets.UTF_8));
        assertNull(NativeYggdrasilTextures.tryParse(unknown));
        CompleteGameProfile invalid = new CompleteGameProfile(UUID.randomUUID(), "Steve", Map.of("textures", "not base64!"));
        assertThrows(ServerResponseMalformedException.class, () -> YggdrasilService.getTextures(invalid));
        assertEquals(Optional.empty(), YggdrasilService.getTextures(new CompleteGameProfile(UUID.randomUUID(), "Steve", Map.of())));
    }
}
