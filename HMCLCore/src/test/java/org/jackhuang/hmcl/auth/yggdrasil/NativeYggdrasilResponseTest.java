/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.auth.yggdrasil;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Compares Rust Yggdrasil session planning against the original Java parser.
@NotNullByDefault
@EnabledOnOs({OS.LINUX, OS.MAC})
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class NativeYggdrasilResponseTest {
    /// Isolated native wrapper.
    @TempDir Path temp;
    /// Previous native executable setting.
    private final @Nullable String previousPath = System.getProperty("mfcl.native.path");
    /// Previous native Yggdrasil setting.
    private final @Nullable String previousEnabled = System.getProperty("mfcl.native.yggdrasil.enabled");

    /// Restores global process settings.
    @AfterEach void restore() {
        if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
        if (previousEnabled == null) System.clearProperty("mfcl.native.yggdrasil.enabled"); else System.setProperty("mfcl.native.yggdrasil.enabled", previousEnabled);
    }

    /// Sessions, provider errors, malformed nested values and legacy coercions match Java.
    @Test void sessionPlansMatchJavaAndPreserveFallbacks() throws Exception {
        Path wrapper = wrapper(); System.setProperty("mfcl.native.path", wrapper.toString());
        String profile = "{\"id\":\"0123456789abcdef0123456789abcdef\",\"name\":\"old\",\"name\":\"Steve\"}";
        String canonical = "{\"clientToken\":\"fixture-client\",\"accessToken\":\"fixture-access\","
                + "\"selectedProfile\":{\"id\":\"0123456789ABCDEF0123456789ABCDEF\",\"name\":\"Alex\"},"
                + "\"availableProfiles\":[null,{\"id\":\"01234567-89ab-cdef-0123-456789abcdef\",\"name\":\"Steve\"}],"
                + "\"user\":{\"id\":\"user\",\"properties\":[{\"name\":\"key\",\"value\":\"first\"},7,null,{\"name\":\"other\",\"value\":\"middle\"},{\"name\":\"key\",\"value\":\"second\"}]}}";
        @Unmodifiable List<String> responses = List.of(
                "{\"clientToken\":\"fixture-client\",\"accessToken\":\"fixture-access\",\"selectedProfile\":" + profile
                        + ",\"availableProfiles\":[" + profile + ",null],\"user\":{\"id\":\"user\",\"properties\":[{\"name\":\"a\",\"value\":\"b\"}]}}",
                canonical, "{\"clientToken\":\"fixture-client\",\"accessToken\":null}",
                "{\"clientToken\":\"fixture-client\",\"selectedProfile\":null,\"availableProfiles\":null,\"user\":null}",
                "{\"error\":\"ForbiddenOperationException\",\"errorMessage\":\"expired\",\"cause\":\"fixture\"}",
                "{\"clientToken\":\"different\",\"accessToken\":\"fixture-access\"}",
                "{\"clientToken\":\"fixture-client\",\"accessToken\":42}",
                "{\"clientToken\":\"old\",\"clientToken\":\"fixture-client\",\"accessToken\":\"fixture-access\"}",
                "{\"clientToken\":\"fixture-client\",\"selectedProfile\":{\"id\":\"bad\",\"name\":\"Alex\"}}",
                "{\"error\":\"ForbiddenOperationException\",\"user\":{\"id\":\"\"}}");
        Method method = YggdrasilService.class.getDeclaredMethod("handleAuthenticationResponse", String.class, String.class);
        method.setAccessible(true);
        for (String response : responses) {
            System.setProperty("mfcl.native.yggdrasil.enabled", "false"); Outcome java = invoke(method, response);
            System.clearProperty("mfcl.native.yggdrasil.enabled"); Outcome nativeResult = invoke(method, response);
            if (java.error() != null) {
                assertNotNull(nativeResult.error()); assertEquals(java.error().getClass(), nativeResult.error().getClass());
                assertEquals(java.error().getMessage(), nativeResult.error().getMessage());
            } else {
                assertNull(nativeResult.error()); assertNotNull(java.session()); assertNotNull(nativeResult.session());
                assertEquals(java.session().getClientToken(), nativeResult.session().getClientToken());
                assertEquals(java.session().getAccessToken(), nativeResult.session().getAccessToken());
                assertEquals(profileName(java.session().getSelectedProfile()), profileName(nativeResult.session().getSelectedProfile()));
                assertEquals(profileNames(java.session().getAvailableProfiles()), profileNames(nativeResult.session().getAvailableProfiles()));
                assertEquals(java.session().getUserProperties(), nativeResult.session().getUserProperties());
                if (java.session().getUserProperties() != null)
                    assertEquals(new java.util.ArrayList<>(java.session().getUserProperties().keySet()),
                            new java.util.ArrayList<>(Objects.requireNonNull(nativeResult.session().getUserProperties()).keySet()));
            }
        }
        @Nullable NativeYggdrasilResponse.Plan plan = NativeYggdrasilResponse.tryPlan(canonical, "fixture-client");
        assertNotNull(plan); assertTrue(plan.selectedState().decoded()); assertTrue(plan.availableState().decoded());
        assertTrue(plan.userState().decoded()); assertEquals("Alex", java.util.Objects.requireNonNull(plan.selectedState().value()).getName());
        assertEquals("second", java.util.Objects.requireNonNull(plan.userState().properties()).get("key"));
        assertEquals(responses.size() + 1, Files.readAllLines(Path.of(wrapper + ".plans")).size());
    }

    /// A parser result or its original failure.
    /// @param session successfully parsed session, or null
    /// @param error thrown cause, or null
    @NotNullByDefault
    private record Outcome(@Nullable YggdrasilSession session, @Nullable Throwable error) { }

    /// Invokes the internal session parser while retaining its original exception.
    /// @param method parser method
    /// @param response fixture response
    /// @return session or error
    /// @throws ReflectiveOperationException if reflective invocation fails
    private static Outcome invoke(Method method, String response) throws ReflectiveOperationException {
        try { return new Outcome((YggdrasilSession) method.invoke(null, response, "fixture-client"), null); }
        catch (InvocationTargetException e) { return new Outcome(null, java.util.Objects.requireNonNull(e.getCause())); }
    }

    /// Returns a nullable profile name.
    /// @param profile parsed profile, or null
    /// @return profile name, or null
    private static @Nullable String profileName(@Nullable GameProfile profile) { return profile == null ? null : profile.getName(); }

    /// Returns profile names while preserving null elements.
    /// @param profiles parsed profiles, or null
    /// @return names, or null
    private static @Nullable @Unmodifiable List<@Nullable String> profileNames(@Nullable List<@Nullable GameProfile> profiles) {
        return profiles == null ? null : profiles.stream().map(NativeYggdrasilResponseTest::profileName).toList();
    }

    /// Creates a wrapper that counts requests without recording response contents.
    /// @return wrapper path
    /// @throws IOException if creation fails
    private Path wrapper() throws IOException {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"); assumeTrue(executable != null && !executable.isBlank());
        Path wrapper = temp.resolve("backend");
        Files.writeString(wrapper, "#!/bin/sh\nIFS= read -r line\ncase \"$line\" in *yggdrasil_plan_response*) printf '%s\\n' run >> \"$0.plans\" ;; esac\n"
                + "printf '%s\\n' \"$line\" | exec '" + executable.replace("'", "'\\''") + "'\n");
        assertTrue(wrapper.toFile().setExecutable(true)); return wrapper;
    }
}
