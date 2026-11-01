/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.auth.yggdrasil;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.glavo.url.WebURL;
import org.glavo.uuid.UUIDs;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Parses complete Yggdrasil profiles in an isolated native process.
@NotNullByDefault
public final class NativeYggdrasilProfile {
    /// Prevents instantiation.
    private NativeYggdrasilProfile() { }

    /// Attempts native profile parsing without logging profile contents.
    /// @param response provider response text
    /// @return parsed profile, or null when the Java adapter must handle the response
    static @Nullable CompleteGameProfile tryParse(String response) {
        return tryParse(response, "mfcl.native.yggdrasil.enabled");
    }

    /// Reads public Mojang profile properties for a Microsoft account through the native backend.
    /// @param uuid requested game profile identifier
    /// @return parsed profile, or null for the existing Java path
    /// @throws InterruptedException if the read is interrupted
    public static @Nullable CompleteGameProfile tryMicrosoftProfile(UUID uuid) throws InterruptedException {
        return tryMicrosoftProfile(WebURL.parse("https://sessionserver.mojang.com/session/minecraft/profile/" + UUIDs.toCompactString(uuid)));
    }

    /// Reads and parses a profile through the Microsoft feature switch.
    /// @param url profile endpoint
    /// @return parsed profile, or null for Java handling
    /// @throws InterruptedException if the read is interrupted
    static @Nullable CompleteGameProfile tryMicrosoftProfile(WebURL url) throws InterruptedException {
        @Nullable String response = NativeYggdrasilTransport.tryGet(url, "mfcl.native.microsoft.enabled");
        if (response == null) return null;
        @Nullable CompleteGameProfile result = tryParse(response, "mfcl.native.microsoft.enabled");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Native profile parsing interrupted");
        return result;
    }

    /// Parses a profile using the feature switch of the requesting account type.
    /// @param response provider response text
    /// @param feature native feature property name
    /// @return parsed profile, or null for Java handling
    private static @Nullable CompleteGameProfile tryParse(String response, String feature) {
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank()
                || !Boolean.parseBoolean(System.getProperty(feature, "true"))) return null;
        try {
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(response) || response.getBytes(StandardCharsets.UTF_8).length > 768 * 1024) return null;
            JsonObject params = new JsonObject(); params.addProperty("response", response);
            JsonObject envelope = new JsonObject(); envelope.addProperty("version", 1); envelope.addProperty("id", 1);
            envelope.addProperty("method", "yggdrasil_plan_profile"); envelope.add("params", params);
            if ((envelope + "\n").getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) return null;
            JsonObject result = NativeBackend.planYggdrasilProfile(Path.of(executable), params);
            @Nullable JsonElement value = result.get("profile");
            if (value == null || !value.isJsonObject()) throw new IOException("Invalid native profile result");
            JsonObject profile = value.getAsJsonObject();
            @Nullable JsonElement properties = profile.get("properties");
            if (properties == null || !properties.isJsonArray()) throw new IOException("Invalid native profile properties");
            JsonArray pairs = properties.getAsJsonArray();
            return new CompleteGameProfile(NativeYggdrasilResponse.profile(profile), NativeYggdrasilResponse.properties(pairs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | RuntimeException e) {
            LOG.debug("Native Yggdrasil profile parsing unavailable; using Java adapter");
            return null;
        }
    }
}
