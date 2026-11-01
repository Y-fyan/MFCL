/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.auth.microsoft;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class NativeMicrosoftStorageTest {
    @TempDir Path temp;

    @Test void canonicalAndLegacySessionsRetainStorageBehavior() throws Exception {
        String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"); assumeTrue(binary != null && !binary.isBlank());
        Path wrapper = temp.resolve("backend");
        Files.writeString(wrapper, """
                #!/usr/bin/env python3
                import json, os, pathlib, subprocess, sys
                line = sys.stdin.readline()
                request = json.loads(line)
                result = subprocess.run([os.environ['MFCL_NATIVE_TEST_EXECUTABLE']], input=line, text=True, stdout=subprocess.PIPE, check=True)
                if 'result' in json.loads(result.stdout): pathlib.Path(__file__ + '.success').touch()
                sys.stdout.write(result.stdout)
                """);
        assertTrue(wrapper.toFile().setExecutable(true));
        String previous = System.getProperty("mfcl.native.path");
        try {
            JsonObject metadata = new JsonObject(); metadata.addProperty("profileID", "00112233445566778899aabbccddeeff");
            JsonObject privateData = new JsonObject(); privateData.addProperty("profileName", "Steve");
            privateData.addProperty("tokenType", "Bearer"); privateData.addProperty("accessToken", "test-token");
            privateData.addProperty("refreshToken", "test-refresh"); privateData.addProperty("userid", "test-user");
            privateData.addProperty("notAfter", 123456789L);
            System.clearProperty("mfcl.native.path"); MicrosoftSession expected = MicrosoftSession.fromStorage(metadata, privateData);
            System.setProperty("mfcl.native.path", wrapper.toString());
            assertEquals(expected, MicrosoftSession.fromStorage(metadata, privateData));
            assertTrue(Files.exists(Path.of(wrapper + ".success")));
            Files.delete(Path.of(wrapper + ".success")); privateData.addProperty("userid", 17);
            assertEquals("17", MicrosoftSession.fromStorage(metadata, privateData).user().id());
            assertFalse(Files.exists(Path.of(wrapper + ".success")), "Legacy numeric data should use Java fallback");
            privateData.addProperty("userid", "\uD800");
            assertEquals("\uD800", MicrosoftSession.fromStorage(metadata, privateData).user().id());
            assertFalse(Files.exists(Path.of(wrapper + ".success")), "Unencodable strings should use Java fallback");
        } finally {
            if (previous == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previous);
        }
    }
}
