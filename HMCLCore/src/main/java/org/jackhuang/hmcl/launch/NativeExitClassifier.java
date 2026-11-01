/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/// Classifies an exited game without recording potentially private log contents.
final class NativeExitClassifier {
    private NativeExitClassifier() { }

    static @Nullable ProcessListener.ExitType tryClassify(int exitCode, List<String> lines, boolean linuxOrBsd)
            throws InterruptedException {
        return classify(exitCode, lines, linuxOrBsd, false);
    }

    static @Nullable ProcessListener.ExitType tryClassifyAll(int exitCode, List<String> lines, boolean linuxOrBsd)
            throws InterruptedException {
        return classify(exitCode, lines, linuxOrBsd, true);
    }

    private static @Nullable ProcessListener.ExitType classify(int exitCode, List<String> lines, boolean linuxOrBsd, boolean filter)
            throws InterruptedException {
        @Nullable String binary = System.getProperty("mfcl.native.path");
        if (binary == null || binary.isBlank() || lines.size() > 20_000
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.launch.enabled", "true"))) return null;
        try {
            JsonArray values = new JsonArray(), originals = new JsonArray(); int size = 0;
            for (String line : lines) {
                String normalized = line.toLowerCase(Locale.ROOT);
                if (!StandardCharsets.UTF_8.newEncoder().canEncode(normalized) || (filter && !StandardCharsets.UTF_8.newEncoder().canEncode(line))) return null;
                size = Math.addExact(size, normalized.getBytes(StandardCharsets.UTF_8).length);
                if (filter) { size = Math.addExact(size, line.getBytes(StandardCharsets.UTF_8).length); originals.add(line); }
                if (size > 512 * 1024) return null;
                values.add(normalized);
            }
            JsonObject params = new JsonObject(); params.addProperty("exit_code", exitCode);
            params.addProperty("linux_or_bsd", linuxOrBsd); params.add("lines", values);
            if (filter) params.add("original_lines", originals);
            var result = NativeBackend.classifyGameExit(Path.of(binary), params).get("exit_type");
            if (result == null || !result.isJsonPrimitive() || !result.getAsJsonPrimitive().isString()) return null;
            return switch (result.getAsString()) {
                case "jvm_error" -> ProcessListener.ExitType.JVM_ERROR;
                case "application_error" -> ProcessListener.ExitType.APPLICATION_ERROR;
                case "sigkill" -> ProcessListener.ExitType.SIGKILL;
                case "normal" -> ProcessListener.ExitType.NORMAL;
                default -> null;
            };
        } catch (IOException | RuntimeException ignored) { return null; }
    }
}
