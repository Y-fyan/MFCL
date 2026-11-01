/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.auth.offline;

import org.jetbrains.annotations.NotNullByDefault;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorArtifactInfo;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorArtifactProvider;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Tests native offline profile comparison and its Java fallback in isolation.
@NotNullByDefault
@EnabledOnOs({OS.LINUX, OS.MAC})
@ResourceLock(Resources.SYSTEM_PROPERTIES)
public final class NativeOfflineUuidTest {
    /// Test-owned directory for child process fixtures.
    @TempDir
    Path tempDir;
    /// Original native backend configuration.
    private final @Nullable String previousPath = System.getProperty("mfcl.native.path");

    /// Restores the native backend configuration after each test.
    @AfterEach
    void restoreProperty() {
        if (previousPath == null) System.clearProperty("mfcl.native.path");
        else System.setProperty("mfcl.native.path", previousPath);
    }

    /// Matching native output is accepted and the child process was invoked.
    @Test
    void acceptsMatchingResult() throws IOException {
        Path executable = backend("5627dd98-e6be-3c21-b8a8-e92344183641");
        System.setProperty("mfcl.native.path", executable.toString());
        assertEquals(OfflineAccountFactory.getUUIDFromUserName("Steve"), OfflineAccountFactory.configuredOfflineUuid("Steve"));
        assertTrue(Files.isRegularFile(Path.of(executable + ".called")));
    }

    /// An incorrect native result does not change the calculated account identity.
    @Test
    void fallsBackOnMismatch() throws IOException {
        Path executable = backend("00000000-0000-3000-8000-000000000000");
        System.setProperty("mfcl.native.path", executable.toString());
        assertEquals(OfflineAccountFactory.getUUIDFromUserName("Steve"), OfflineAccountFactory.configuredOfflineUuid("Steve"));
        assertTrue(Files.isRegularFile(Path.of(executable + ".called")));
    }

    /// A missing backend leaves offline account identity unchanged.
    @Test
    void fallsBackOnMissingBackend() {
        System.setProperty("mfcl.native.path", tempDir.resolve("missing").toString());
        assertEquals(OfflineAccountFactory.getUUIDFromUserName("Steve"), OfflineAccountFactory.configuredOfflineUuid("Steve"));
    }

    /// Compares the real native executable with the account factory when explicitly supplied.
    @Test
    void realBackendMatchesJava() throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        System.setProperty("mfcl.native.path", executable);
        for (String username : new String[]{"Steve", "玩家", "A B"}) {
            UUID expected = OfflineAccountFactory.getUUIDFromUserName(username);
            assertEquals(expected, NativeBackend.calculateOfflineUuid(Path.of(executable), username));
            assertEquals(expected, OfflineAccountFactory.configuredOfflineUuid(username));
        }
    }

    /// Account creation uses the configured backend for an automatically generated UUID.
    @Test
    void accountFactoryUsesNativeForGeneratedUuid() throws IOException {
        Path executable = backend("5627dd98-e6be-3c21-b8a8-e92344183641");
        System.setProperty("mfcl.native.path", executable.toString());
        OfflineAccountFactory factory = new OfflineAccountFactory(new UnusedProvider());
        OfflineAccount account = factory.create(null, "Steve", "", null, null);
        assertEquals(OfflineAccountFactory.getUUIDFromUserName("Steve"), account.getProfileID());
        assertTrue(Files.isRegularFile(Path.of(executable + ".called")));
    }

    /// Explicit profile UUIDs are preserved without calling the native backend.
    @Test
    void accountFactoryPreservesExplicitUuid() throws IOException {
        Path executable = backend("5627dd98-e6be-3c21-b8a8-e92344183641");
        System.setProperty("mfcl.native.path", executable.toString());
        OfflineAccountFactory factory = new OfflineAccountFactory(new UnusedProvider());
        UUID chosen = UUID.fromString("00000000-0000-4000-8000-000000000000");
        OfflineAccount account = factory.create(null, "Steve", "", null, new OfflineAccountFactory.AdditionalData(chosen, null));
        assertEquals(chosen, account.getProfileID());
        assertFalse(Files.exists(Path.of(executable + ".called")));
    }

    /// Artifact provider unused by these account creation tests.
    private static final class UnusedProvider implements AuthlibInjectorArtifactProvider {
        /// Throws because no artifact should be requested during account creation.
        @Override
        public AuthlibInjectorArtifactInfo getArtifactInfo() throws IOException {
            throw new IOException("Unexpected artifact request");
        }

        /// Returns no cached artifact.
        @Override
        public Optional<AuthlibInjectorArtifactInfo> getArtifactInfoImmediately() {
            return Optional.empty();
        }
    }

    /// Creates a temporary backend that records its invocation and returns a chosen UUID.
    ///
    /// @param uuid response UUID
    /// @return path to the executable
    /// @throws IOException if the fixture cannot be created
    private Path backend(String uuid) throws IOException {
        Path path = tempDir.resolve("backend");
        String response = "{\"version\":1,\"id\":1,\"result\":{\"username\":\"Steve\",\"uuid\":\"" + uuid + "\"}}";
        Files.writeString(path, "#!/bin/sh\nread line\n: > \"$0.called\"\nprintf '%s\\n' '" + response + "'\n");
        if (!path.toFile().setExecutable(true)) throw new IOException("Cannot mark test executable");
        return path;
    }
}
