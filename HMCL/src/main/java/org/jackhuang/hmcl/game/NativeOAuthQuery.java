/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.game;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/// Parses ordinary OAuth callback fields in an isolated native process.
@NotNullByDefault
final class NativeOAuthQuery {
    /// Callback fields needed by the launcher.
    private static final Set<String> FIELDS = Set.of("code", "state", "id_token");

    /// Prevents instantiation.
    private NativeOAuthQuery() { }

    /// Parses a query, retaining Java handling for ambiguous or unsupported inputs.
    /// @param query raw callback query, possibly containing credentials
    /// @return parsed fields, or null when Java should parse the query
    static @Nullable Map<String, String> tryParse(@Nullable String query) {
        @Nullable String binary = System.getProperty("mfcl.native.path");
        if (binary == null || binary.isBlank() || query == null
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.microsoft.enabled", "true"))) return null;
        try {
            if (query.length() > 64 * 1024 || !StandardCharsets.UTF_8.newEncoder().canEncode(query)) return null;
            return fromResponse(NativeBackend.parseOAuthCallback(Path.of(binary), query));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /// Validates decoded callback fields without exposing them to logs.
    /// @param response native parser result
    /// @return parsed fields, or null on malformed output
    static @Nullable Map<String, String> fromResponse(JsonObject response) {
        if (response.size() > FIELDS.size()) return null;
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> field : response.entrySet()) {
            JsonElement value = field.getValue();
            if (!FIELDS.contains(field.getKey()) || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
            result.put(field.getKey(), value.getAsString());
        }
        return result;
    }
}
