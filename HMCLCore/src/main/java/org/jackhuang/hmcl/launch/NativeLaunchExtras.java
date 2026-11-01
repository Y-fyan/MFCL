/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.game.ProxyOption;
import org.jackhuang.hmcl.game.QuickPlayOption;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jackhuang.hmcl.util.StringUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Plans optional game arguments through Rust while keeping Java compatibility fallback.
@NotNullByDefault
final class NativeLaunchExtras {
    /// Prevents instantiation.
    private NativeLaunchExtras() { }

    /// Attempts native argument planning without logging the supplied proxy credentials.
    /// @param quick selected quick-play option, or null
    /// @param quickSupported whether the game supports quick play
    /// @param fullscreen whether full screen is requested
    /// @param proxy selected proxy, or null
    /// @param graphics game graphics argument, or null
    /// @return immutable planned arguments, or null when Java should plan them
    /// @throws IOException if planning is interrupted
    static @Nullable @Unmodifiable List<String> tryPlan(@Nullable QuickPlayOption quick, boolean quickSupported,
            boolean fullscreen, @Nullable ProxyOption proxy, @Nullable String graphics) throws IOException {
        @Nullable String configured = System.getProperty("mfcl.native.path");
        if (configured == null || configured.isBlank()
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.launch.enabled", "true"))) return null;
        try {
            JsonObject params = new JsonObject();
            if (quick == null) params.add("quick", JsonNull.INSTANCE);
            else {
                JsonObject encoded = new JsonObject();
                if (quick instanceof QuickPlayOption.MultiPlayer multiplayer) {
                    encoded.addProperty("kind", "multiplayer"); addString(encoded, "value", multiplayer.serverIP());
                } else if (quick instanceof QuickPlayOption.SinglePlayer singleplayer) {
                    encoded.addProperty("kind", "singleplayer"); addString(encoded, "value", singleplayer.worldFolderName());
                } else if (quick instanceof QuickPlayOption.Realm realm) {
                    encoded.addProperty("kind", "realm"); addString(encoded, "value", realm.realmID());
                } else return null;
                params.add("quick", encoded);
            }
            params.addProperty("quick_supported", quickSupported); params.addProperty("fullscreen", fullscreen);
            if (proxy instanceof ProxyOption.Socks socks) {
                JsonObject encoded = new JsonObject(); addString(encoded, "host", socks.host());
                encoded.addProperty("port", Integer.toString(socks.port()));
                if (StringUtils.isNotBlank(socks.username())) {
                    addString(encoded, "username", Objects.requireNonNull(socks.username()));
                    addString(encoded, "password", Objects.requireNonNullElse(socks.password(), ""));
                } else encoded.add("username", JsonNull.INSTANCE);
                params.add("socks", encoded);
            } else params.add("socks", JsonNull.INSTANCE);
            if (graphics == null) params.add("graphics", JsonNull.INSTANCE); else addString(params, "graphics", graphics);
            JsonObject response = NativeBackend.planLaunchExtras(Path.of(configured), params);
            JsonArray values = response.getAsJsonArray("arguments");
            if (values.size() > 20) throw new IOException("Invalid native launch option count");
            List<String> result = new ArrayList<>(values.size());
            for (JsonElement value : values) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Invalid native launch option");
                result.add(value.getAsString());
            }
            return List.copyOf(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Native launch option planning interrupted", e);
        } catch (IOException | RuntimeException e) {
            LOG.warning("Native launch option planning unavailable; using Java implementation");
            return null;
        }
    }

    /// Adds a value only when it round-trips through the UTF-8 protocol.
    /// @param object destination object
    /// @param name property name
    /// @param value property value
    /// @throws IOException if UTF-16 contains an unpaired surrogate
    private static void addString(JsonObject object, String name, String value) throws IOException {
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(value)) throw new IOException("Invalid launch option text");
        object.addProperty(name, value);
    }
}
