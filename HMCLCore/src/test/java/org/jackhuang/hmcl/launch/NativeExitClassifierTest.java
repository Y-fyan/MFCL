/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.launch;

import org.jackhuang.hmcl.util.StringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
@org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.LINUX, org.junit.jupiter.api.condition.OS.MAC})
final class NativeExitClassifierTest {
    @TempDir Path temp;

    @Test void classificationMatchesJavaAcrossExitCodes() throws Exception {
        String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"); assumeTrue(binary != null && !binary.isBlank());
        Path wrapper = temp.resolve("backend");
        Files.writeString(wrapper, "#!/bin/sh\nIFS= read -r line\ncase \"$line\" in *classify_game_exit*) : > \"$0.called\" ;; esac\n"
                + "printf '%s\\n' \"$line\" | exec '" + binary.replace("'", "'\\''") + "'\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        String previous = System.getProperty("mfcl.native.path"), enabled = System.getProperty("mfcl.native.launch.enabled");
        try {
            System.setProperty("mfcl.native.path", wrapper.toString()); System.clearProperty("mfcl.native.launch.enabled");
            for (int code : List.of(0, 1, 137)) for (boolean linux : List.of(false, true))
                for (List<String> lines : List.of(List.<String>of(), List.of("Error occurred DURING initialization of VM"),
                        List.of("中文 CRASH REPORT saved TO /tmp/private"), List.of("Unable to launch", "could not create the java virtual machine."),
                        List.of("normal"))) {
                    boolean jvm = code != 0 && StringUtils.containsOne(lines, "Could not create the Java Virtual Machine.",
                            "Error occurred during initialization of VM", "A fatal exception has occurred. Program will exit.");
                    boolean crash = code != 0 || StringUtils.containsOne(lines, "Crash report saved to", "Could not save crash report to",
                            "This crash report has been saved to:", "Unable to launch", "An exception was thrown, the game will display an error screen and halt.");
                    var expected = jvm ? ProcessListener.ExitType.JVM_ERROR : crash ? code == 137 && linux
                            ? ProcessListener.ExitType.SIGKILL : ProcessListener.ExitType.APPLICATION_ERROR : ProcessListener.ExitType.NORMAL;
                    assertEquals(expected, NativeExitClassifier.tryClassify(code, lines, linux));
                }
            assertTrue(Files.exists(Path.of(wrapper + ".called")));
            for (String header : List.of("[12:34:56] [main/ERROR]", "[12:34:56] [main/INFO]", "[12:34:56] [main/WARN] [STDERR]",
                    "[12:34:56] [main/ERROR] [STDOUT]", "[12:34:56] [main/INFO] [STDERR/NO]", "[SEVERE]",
                    "[STDERR] [WARNING]", "[DEBUG]", "plain", "overwriting existing")) {
                String line = header + " Crash report saved to example";
                boolean included = org.jackhuang.hmcl.util.Log4jLevel.guessLogLineError(line);
                assertEquals(included ? ProcessListener.ExitType.APPLICATION_ERROR : ProcessListener.ExitType.NORMAL,
                        NativeExitClassifier.tryClassifyAll(0, List.of(line), true), header);
            }
            assertEquals(ProcessListener.ExitType.JVM_ERROR, NativeExitClassifier.tryClassifyAll(1,
                    List.of("[12:00:00] [thread/ERROR] Could not create the Java Virtual Machine."), true));
            assertNull(NativeExitClassifier.tryClassify(1, List.of("\ud800"), true));
            System.setProperty("mfcl.native.path", temp.resolve("missing").toString());
            assertNull(NativeExitClassifier.tryClassify(1, List.of("private"), true));
        } finally {
            if (previous == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previous);
            if (enabled == null) System.clearProperty("mfcl.native.launch.enabled"); else System.setProperty("mfcl.native.launch.enabled", enabled);
        }
    }
}
