/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.auth.yggdrasil;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jackhuang.hmcl.util.StringUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Plans Yggdrasil session construction in an isolated native child.
@NotNullByDefault
final class NativeYggdrasilResponse {
    /// Prevents instantiation.
    private NativeYggdrasilResponse() { }

    /// Parsed scalar values and exact nested JSON for existing typed adapters.
    /// @param outcome native session outcome
    /// @param error remote error name, or null
    /// @param errorMessage remote message, or null
    /// @param cause remote cause, or null
    /// @param clientToken returned client token, or null
    /// @param accessToken returned access token, or null
    /// @param selectedProfile raw selected profile JSON, or null
    /// @param availableProfiles raw profile-list JSON, or null
    /// @param user raw user JSON, or null
    /// @param selectedState decoded selected-profile state
    /// @param availableState decoded profile-list state
    /// @param userState decoded user-properties state
    @NotNullByDefault
    record Plan(String outcome, @Nullable String error, @Nullable String errorMessage, @Nullable String cause,
                @Nullable String clientToken, @Nullable String accessToken, @Nullable String selectedProfile,
                @Nullable String availableProfiles, @Nullable String user, ProfileState selectedState,
                ProfileListState availableState, UserState userState) { }

    /// Native profile result or a request to use the Java adapter.
    /// @param decoded whether Java may use the supplied value
    /// @param value decoded profile, or null
    @NotNullByDefault
    record ProfileState(boolean decoded, @Nullable GameProfile value) { }

    /// Native profile-list result or a request to use the Java adapter.
    /// @param decoded whether Java may use the supplied list
    /// @param value immutable profile list, or null
    @NotNullByDefault
    record ProfileListState(boolean decoded, @Nullable @Unmodifiable List<@Nullable GameProfile> value) { }

    /// Native user-properties result or a request to use the Java adapter.
    /// @param decoded whether Java may use the supplied properties
    /// @param properties immutable user properties, or null
    @NotNullByDefault
    record UserState(boolean decoded, @Nullable @Unmodifiable Map<String, String> properties) { }

    /// Attempts a native response plan without logging response contents or tokens.
    /// @param response provider response text
    /// @param expectedClientToken client token supplied to the provider
    /// @return validated native plan, or null when Java must parse the response
    static @Nullable Plan tryPlan(String response, String expectedClientToken) {
        @Nullable String configured = System.getProperty("mfcl.native.path");
        if (configured == null || configured.isBlank()
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.yggdrasil.enabled", "true"))) return null;
        try {
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(response) || !StandardCharsets.UTF_8.newEncoder().canEncode(expectedClientToken)
                    || response.getBytes(StandardCharsets.UTF_8).length > 768 * 1024
                    || expectedClientToken.getBytes(StandardCharsets.UTF_8).length > 65_536) return null;
            JsonObject params = new JsonObject(); params.addProperty("response", response); params.addProperty("client_token", expectedClientToken);
            JsonObject envelope = new JsonObject(); envelope.addProperty("version", 1); envelope.addProperty("id", 1);
            envelope.addProperty("method", "yggdrasil_plan_response"); envelope.add("params", params);
            if ((envelope + "\n").getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) return null;
            JsonObject result = NativeBackend.planYggdrasilResponse(Path.of(configured), params);
            String outcome = requiredString(result, "outcome");
            @Nullable String error = optionalString(result, "error"); @Nullable String errorMessage = optionalString(result, "error_message");
            @Nullable String cause = optionalString(result, "cause"); @Nullable String clientToken = optionalString(result, "client_token");
            @Nullable String accessToken = optionalString(result, "access_token");
            @Nullable String selected = optionalString(result, "selected_profile");
            @Nullable String available = optionalString(result, "available_profiles"); @Nullable String user = optionalString(result, "user");
            String expectedOutcome = StringUtils.isNotBlank(error) ? "remote_error"
                    : !expectedClientToken.equals(clientToken) ? "token_mismatch" : "ok";
            if (!outcome.equals(expectedOutcome)) throw new IOException("Invalid native session outcome");
            ProfileState selectedState = profileState(result, "selected_decoded", selected);
            ProfileListState availableState = profileListState(result, "available_decoded", available);
            UserState userState = userState(result, "user_decoded", user);
            return new Plan(outcome, error, errorMessage, cause, clientToken, accessToken, selected, available, user,
                    selectedState, availableState, userState);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | RuntimeException e) {
            LOG.debug("Native Yggdrasil response planning unavailable; using Java parser");
            return null;
        }
    }

    /// Validates whether a nested response is absent, decoded or requires fallback.
    /// @param root native result object
    /// @param key nested state key
    /// @param raw original raw JSON, or null
    /// @return nested state object
    /// @throws IOException if the response is inconsistent
    private static JsonObject stateObject(JsonObject root, String key, @Nullable String raw) throws IOException {
        @Nullable JsonElement element = root.get(key);
        if (element == null || !element.isJsonObject()) throw new IOException("Invalid native session state");
        JsonObject state = element.getAsJsonObject(); String name = requiredString(state, "state");
        @Nullable JsonElement decoded = state.get("decoded");
        if (decoded == null || !("absent".equals(name) || "decoded".equals(name) || "fallback".equals(name))
                || ("absent".equals(name) != (raw == null))
                || ("decoded".equals(name) == decoded.isJsonNull())) throw new IOException("Invalid native session state");
        return state;
    }

    /// Converts one verified profile state to the launcher's existing model.
    /// @param root native result object
    /// @param key nested state key
    /// @param raw original raw JSON, or null
    /// @return profile state
    /// @throws IOException if the native result is invalid
    private static ProfileState profileState(JsonObject root, String key, @Nullable String raw) throws IOException {
        JsonObject state = stateObject(root, key, raw); String name = requiredString(state, "state");
        if ("fallback".equals(name)) return new ProfileState(false, null);
        if ("absent".equals(name)) return new ProfileState(true, null);
        return new ProfileState(true, profile(state.getAsJsonObject("decoded")));
    }

    /// Converts a verified profile-list state, preserving null entries.
    /// @param root native result object
    /// @param key nested state key
    /// @param raw original raw JSON, or null
    /// @return profile-list state
    /// @throws IOException if the native result is invalid
    private static ProfileListState profileListState(JsonObject root, String key, @Nullable String raw) throws IOException {
        JsonObject state = stateObject(root, key, raw); String name = requiredString(state, "state");
        if ("fallback".equals(name)) return new ProfileListState(false, null);
        if ("absent".equals(name)) return new ProfileListState(true, null);
        JsonArray values = state.getAsJsonArray("decoded");
        if (values.size() > 20_000) throw new IOException("Invalid native profile count");
        List<@Nullable GameProfile> profiles = new ArrayList<>(values.size());
        for (JsonElement value : values) profiles.add(value.isJsonNull() ? null : profile(value.getAsJsonObject()));
        return new ProfileListState(true, Collections.unmodifiableList(profiles));
    }

    /// Converts a verified user-properties state and preserves insertion order.
    /// @param root native result object
    /// @param key nested state key
    /// @param raw original raw JSON, or null
    /// @return user-properties state
    /// @throws IOException if the native result is invalid
    private static UserState userState(JsonObject root, String key, @Nullable String raw) throws IOException {
        JsonObject state = stateObject(root, key, raw); String name = requiredString(state, "state");
        if ("fallback".equals(name)) return new UserState(false, null);
        if ("absent".equals(name)) return new UserState(true, null);
        JsonObject decoded = state.getAsJsonObject("decoded"); @Nullable JsonElement values = decoded.get("properties");
        if (StringUtils.isBlank(requiredString(decoded, "id"))) throw new IOException("Invalid native user ID");
        if (values == null) throw new IOException("Invalid native user properties");
        if (values.isJsonNull()) return new UserState(true, null);
        return new UserState(true, properties(values.getAsJsonArray()));
    }

    /// Converts ordered native property pairs to the launcher's immutable property map.
    /// @param array native property pairs
    /// @return immutable insertion-ordered properties
    /// @throws IOException if a native property is invalid
    static @Unmodifiable Map<String, String> properties(JsonArray array) throws IOException {
        if (array.size() > 20_000) throw new IOException("Invalid native property count");
        Map<String, String> properties = new LinkedHashMap<>();
        for (JsonElement element : array) {
            JsonObject entry = element.getAsJsonObject(); String propertyName = requiredString(entry, "name"), value = requiredString(entry, "value");
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(propertyName) || !StandardCharsets.UTF_8.newEncoder().canEncode(value))
                throw new IOException("Invalid native user property");
            properties.put(propertyName, value);
        }
        return Collections.unmodifiableMap(properties);
    }

    /// Constructs a profile from validated native strings.
    /// @param object native profile object
    /// @return typed profile
    /// @throws IOException if the native result is invalid
    static GameProfile profile(JsonObject object) throws IOException {
        String id = requiredString(object, "id"), name = requiredString(object, "name");
        if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || !StandardCharsets.UTF_8.newEncoder().canEncode(name)) throw new IOException("Invalid native profile");
        try { return new GameProfile(UUID.fromString(id), name); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid native profile ID"); }
    }

    /// Reads a required string result field.
    /// @param object native result
    /// @param key field name
    /// @return string value
    /// @throws IOException if the field is absent or invalid
    private static String requiredString(JsonObject object, String key) throws IOException {
        @Nullable String value = optionalString(object, key);
        if (value == null) throw new IOException("Invalid native session response");
        return value;
    }

    /// Reads a nullable string result field.
    /// @param object native result
    /// @param key field name
    /// @return string value, or null
    /// @throws IOException if the field is absent or invalid
    private static @Nullable String optionalString(JsonObject object, String key) throws IOException {
        @Nullable JsonElement value = object.get(key);
        if (value == null) throw new IOException("Invalid native session response");
        if (value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Invalid native session response");
        return value.getAsString();
    }
}
