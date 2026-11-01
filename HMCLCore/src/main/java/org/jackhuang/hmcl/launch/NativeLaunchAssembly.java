/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Plans ordered launch sections in an isolated Rust process.
@NotNullByDefault
final class NativeLaunchAssembly {
    /// Planned arguments surrounding optional launch extras.
    /// @param before parsed JVM, agent, main-class and game arguments
    /// @param after unparsed custom game arguments
    @NotNullByDefault
    record Plan(@Unmodifiable List<String> before, @Unmodifiable List<String> after) { }

    /// Prevents instantiation.
    private NativeLaunchAssembly() { }

    /// Plans arguments without logging or pooling credential-bearing values.
    /// @param groups expanded launch argument groups
    /// @param agents configured Java agent paths
    /// @param main main class
    /// @return plan, or null to retain Java assembly
    /// @throws IOException if interrupted
    static @Nullable Plan tryPlan(List<List<String>> groups, List<String> agents, String main) throws IOException {
        @Nullable String binary = System.getProperty("mfcl.native.path");
        if (binary == null || binary.isBlank() || !Boolean.parseBoolean(System.getProperty("mfcl.native.launch.enabled", "true"))
                || groups.size() != 7) return null;
        try {
            JsonObject params = new JsonObject(); JsonArray encodedGroups = new JsonArray(); int beforeCount = agents.size() + 1;
            for (int i = 0; i < groups.size(); i++) {
                JsonArray values = new JsonArray();
                for (String value : groups.get(i)) {
                    if (value == null || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) return null;
                    values.add(value);
                }
                if (i < 6) beforeCount = Math.addExact(beforeCount, groups.get(i).size());
                encodedGroups.add(values);
            }
            JsonArray encodedAgents = new JsonArray();
            for (@Nullable String agent : agents) {
                String value = String.valueOf(agent);
                if (!StandardCharsets.UTF_8.newEncoder().canEncode(value)) return null;
                encodedAgents.add(value);
            }
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(main)) return null;
            params.add("groups", encodedGroups); params.add("agents", encodedAgents); params.addProperty("main", main);
            if (params.toString().getBytes(StandardCharsets.UTF_8).length > 900 * 1024) return null;
            JsonObject response = NativeBackend.assembleLaunchSections(Path.of(binary), params);
            return new Plan(readSection(response.getAsJsonArray("before"), beforeCount, true),
                    readSection(response.getAsJsonArray("after"), groups.get(6).size(), false));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new InterruptedIOException("Native launch assembly was interrupted");
        } catch (IOException | RuntimeException e) { return null; }
    }

    /// Validates a returned section and its quoting behavior.
    /// @param items native section entries
    /// @param expected expected item count
    /// @param parse expected quoting flag
    /// @return immutable ordered argument values
    /// @throws IOException on malformed output
    private static @Unmodifiable List<String> readSection(JsonArray items, int expected, boolean parse) throws IOException {
        if (items == null || items.size() != expected || expected > 20_001) throw new IOException("Invalid native launch section");
        List<String> values = new ArrayList<>(expected);
        for (JsonElement raw : items) {
            JsonObject item = raw.getAsJsonObject(); JsonElement value = item.get("value"), flag = item.get("parse");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || !flag.isJsonPrimitive()
                    || !flag.getAsJsonPrimitive().isBoolean() || flag.getAsBoolean() != parse) throw new IOException("Invalid native launch item");
            values.add(value.getAsString());
        }
        return List.copyOf(values);
    }
}
