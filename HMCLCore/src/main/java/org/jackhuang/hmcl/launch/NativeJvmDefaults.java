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
import org.jackhuang.hmcl.game.ProxyOption;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.platform.CommandBuilder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Adapts native JVM defaults to the existing command builder without logging arguments.
@NotNullByDefault
final class NativeJvmDefaults {
    /// Prevents instantiation.
    private NativeJvmDefaults() { }

    /// One planned argument and whether command-script quoting is applied to it.
    /// @param argument argument value
    /// @param parse whether to escape it when generating a shell script
    @NotNullByDefault
    record Addition(String argument, boolean parse) { }

    /// Plans platform, game proxy and JVM optimization defaults in one isolated request.
    /// @param command current command builder
    /// @param clientJar absolute game JAR path
    /// @param dockName macOS dock label, or null
    /// @param dockIcon macOS dock icon path, or null
    /// @param userHome non-Windows home path, or null
    /// @param proxy selected game proxy, or null for default
    /// @param version Java major version
    /// @param is64 whether Java is 64-bit
    /// @param memoryLarge whether memory exceeds four GiB
    /// @param optimize whether optimizing defaults are enabled
    /// @return immutable planned arguments, or null for Java fallback
    /// @throws IOException when interrupted
    static @Nullable @Unmodifiable List<String> tryPlanPlatform(CommandBuilder command, String clientJar,
            @Nullable String dockName, @Nullable String dockIcon, @Nullable String userHome, @Nullable ProxyOption proxy,
            int version, boolean is64, boolean memoryLarge, boolean optimize) throws IOException {
        @Nullable String path = System.getProperty("mfcl.native.path");
        if (path == null || path.isBlank() || !Boolean.parseBoolean(System.getProperty("mfcl.native.launch.enabled", "true"))) return null;
        try {
            JsonObject params = new JsonObject(); params.add("raw", encode(command.asList()));
            params.add("external", encode(command.externalArgumentsSnapshot()));
            addString(params, "client_jar", clientJar); addString(params, "dock_name", dockName);
            addString(params, "dock_icon", dockIcon); addString(params, "user_home", userHome);
            JsonObject encoded = new JsonObject();
            if (proxy == null || proxy == ProxyOption.Default.INSTANCE) encoded.addProperty("kind", "default");
            else if (proxy == ProxyOption.Direct.INSTANCE) encoded.addProperty("kind", "direct");
            else {
                String host; int port; @Nullable String username; @Nullable String password;
                if (proxy instanceof ProxyOption.Http http) {
                    encoded.addProperty("kind", "http"); host = http.host(); port = http.port(); username = http.username(); password = http.password();
                } else if (proxy instanceof ProxyOption.Socks socks) {
                    encoded.addProperty("kind", "socks"); host = socks.host(); port = socks.port(); username = socks.username(); password = socks.password();
                } else return null;
                addString(encoded, "host", host); encoded.addProperty("port", port);
                if (StringUtils.isNotBlank(username)) {
                    addString(encoded, "username", username); addString(encoded, "password", Objects.requireNonNullElse(password, ""));
                } else encoded.addProperty("username", (String) null);
            }
            params.add("proxy", encoded); params.addProperty("java_version", version); params.addProperty("is_64_bit", is64);
            params.addProperty("memory_large", memoryLarge); params.addProperty("optimize", optimize);
            JsonArray values = NativeBackend.planJvmPlatform(Path.of(path), params).getAsJsonArray("arguments");
            if (values.size() > 64) throw new IOException("Invalid native JVM platform count");
            List<String> result = new ArrayList<>(values.size());
            for (JsonElement value : values) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Invalid native JVM platform argument");
                result.add(value.getAsString());
            }
            return List.copyOf(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IOException("Native JVM option planning interrupted", e);
        } catch (IOException | RuntimeException e) { return null; }
    }

    /// Encodes a nullable UTF-8 protocol value without silently replacing surrogates.
    /// @param object destination object
    /// @param key property name
    /// @param value value or null
    /// @throws IOException if the value is not valid Unicode
    private static void addString(JsonObject object, String key, @Nullable String value) throws IOException {
        if (value != null && !StandardCharsets.UTF_8.newEncoder().canEncode(value)) throw new IOException("Unsupported JVM argument encoding");
        object.addProperty(key, value);
    }

    /// Plans memory and ordinary user defaults while preserving command-script quoting.
    /// @param command current command builder
    /// @param version Java major version
    /// @param min minimum heap size in MiB, or null
    /// @param max maximum heap size in MiB, or null
    /// @param metaspace metaspace size in MiB, or null
    /// @param defaults ordinary JVM arguments
    /// @return immutable additions, or null when Java must handle them
    /// @throws IOException when interrupted
    static @Nullable @Unmodifiable List<Addition> tryPlanInitial(CommandBuilder command, int version, @Nullable Integer min,
            @Nullable Integer max, @Nullable Integer metaspace, List<String> defaults) throws IOException {
        @Nullable String path = System.getProperty("mfcl.native.path");
        if (path == null || path.isBlank() || !Boolean.parseBoolean(System.getProperty("mfcl.native.launch.enabled", "true"))) return null;
        try {
            JsonObject params = new JsonObject(); params.add("raw", encode(command.asList()));
            params.add("external", encode(command.externalArgumentsSnapshot())); params.add("defaults", encode(defaults));
            params.addProperty("java_version", version); params.addProperty("min_memory", min);
            params.addProperty("max_memory", max); params.addProperty("metaspace", metaspace);
            JsonArray values = NativeBackend.planJvmInitial(Path.of(path), params).getAsJsonArray("arguments");
            if (values.size() > defaults.size() + 3) throw new IOException("Invalid native JVM initial count");
            List<Addition> result = new ArrayList<>(values.size());
            for (JsonElement value : values) {
                JsonObject entry = value.getAsJsonObject();
                JsonElement argument = entry.get("argument"), parse = entry.get("parse");
                if (!argument.isJsonPrimitive() || !argument.getAsJsonPrimitive().isString()
                        || !parse.isJsonPrimitive() || !parse.getAsJsonPrimitive().isBoolean())
                    throw new IOException("Invalid native JVM initial option");
                result.add(new Addition(argument.getAsString(), parse.getAsBoolean()));
            }
            return List.copyOf(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Native JVM option planning interrupted", e);
        } catch (IOException | RuntimeException e) { return null; }
    }

    /// Plans defaults against raw arguments and those read from external JVM option files.
    /// @param command current command builder
    /// @param version Java major version
    /// @param is64 whether Java is 64-bit
    /// @param memoryLarge whether system memory exceeds four GiB
    /// @param optimize whether generated optimizing arguments are enabled
    /// @return immutable defaults, or null when Java must plan them
    /// @throws IOException when interrupted
    static @Nullable @Unmodifiable List<String> tryPlan(CommandBuilder command, int version, boolean is64,
            boolean memoryLarge, boolean optimize) throws IOException {
        @Nullable String path = System.getProperty("mfcl.native.path");
        if (path == null || path.isBlank() || !Boolean.parseBoolean(System.getProperty("mfcl.native.launch.enabled", "true"))) return null;
        try {
            JsonObject params = new JsonObject(); params.add("raw", encode(command.asList()));
            params.add("external", encode(command.externalArgumentsSnapshot()));
            params.addProperty("java_version", version); params.addProperty("is_64_bit", is64);
            params.addProperty("memory_large", memoryLarge); params.addProperty("optimize", optimize);
            JsonArray values = NativeBackend.planJvmDefaults(Path.of(path), params).getAsJsonArray("arguments");
            if (values.size() > 32) throw new IOException("Invalid native JVM defaults count");
            List<String> result = new ArrayList<>(values.size());
            for (JsonElement value : values) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Invalid native JVM default");
                result.add(value.getAsString());
            }
            return List.copyOf(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Native JVM option planning interrupted", e);
        } catch (IOException | RuntimeException e) { return null; }
    }

    /// Encodes protocol strings only when they round-trip through UTF-8.
    /// @param values ordered arguments
    /// @return encoded arguments
    /// @throws IOException if an argument is not valid Unicode
    private static JsonArray encode(List<String> values) throws IOException {
        JsonArray result = new JsonArray();
        for (String value : values) {
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(value)) throw new IOException("Unsupported JVM argument encoding");
            result.add(value);
        }
        return result;
    }
}
