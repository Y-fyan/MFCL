/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.forge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.download.forge.ForgeNewInstallProfile.Processor;
import org.jackhuang.hmcl.game.Artifact;
import org.jackhuang.hmcl.game.GameRepositoryLayout;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
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
import java.util.function.BooleanSupplier;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Plans option maps for Forge-family installer processors through Rust.
@NotNullByDefault
public final class NativeProcessorOptions {
    /// Prevents instantiation.
    private NativeProcessorOptions() { }

    /// Attempts to plan all processor options without changing installer files.
    /// @param processors client processors in execution order
    /// @param variables resolved installer variables
    /// @param layout repository layout for artifact paths
    /// @param cancelled owning task cancellation predicate
    /// @return immutable option maps, or null when Java must plan them
    /// @throws InterruptedException if cancelled
    public static @Nullable @Unmodifiable List<@Unmodifiable Map<String, @Nullable String>> tryPlan(
            List<Processor> processors, Map<String, @Nullable String> variables, GameRepositoryLayout layout,
            BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        @Nullable String configured = System.getProperty("mfcl.native.path");
        if (configured == null || configured.isBlank()
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.processors.enabled", "true"))) return null;
        try {
            JsonArray groups = new JsonArray(); List<String> allArguments = new ArrayList<>();
            List<List<String>> namesByGroup = new ArrayList<>(processors.size());
            for (Processor processor : processors) {
                JsonArray args = new JsonArray(); List<String> names = new ArrayList<>();
                for (String arg : processor.getArgs()) {
                    checkUnicode(arg); args.add(arg);
                    allArguments.add(arg);
                    if (arg.startsWith("--")) names.add(arg.substring(2));
                }
                groups.add(args); namesByGroup.add(names);
            }
            JsonObject vars = new JsonObject();
            for (var entry : variables.entrySet()) {
                checkUnicode(entry.getKey());
                @Nullable String value = entry.getValue(); if (value != null) checkUnicode(value);
                vars.addProperty(entry.getKey(), value);
            }
            JsonObject params = new JsonObject(); params.add("groups", groups); params.add("variables", vars);
            @Nullable JsonObject nativePaths = NativeProcessorLiterals.encodeWithRoot(allArguments, variables, layout);
            @Nullable JsonObject response = null;
            if (nativePaths != null) {
                params.add("artifact_root", nativePaths.get("artifact_root"));
                try { response = NativeBackend.planProcessorOptionsV2(Path.of(configured), params, cancelled); }
                catch (IOException | RuntimeException e) { if (cancelled.getAsBoolean()) throw new InterruptedException(); }
            }
            if (response == null) {
                JsonObject artifacts = new JsonObject();
                for (String arg : allArguments) {
                    if (arg.startsWith("[") && arg.endsWith("]")) {
                        String descriptor = arg.substring(1, arg.length() - 1);
                        String path = layout.getArtifactFile(Artifact.fromDescriptor(descriptor)).toString();
                        checkUnicode(path); artifacts.addProperty(arg, path);
                    }
                }
                params.add("artifacts", artifacts);
                response = NativeBackend.planProcessorOptions(Path.of(configured), params, cancelled);
            }
            @Nullable JsonArray plannedGroups = response.getAsJsonArray("groups");
            if (plannedGroups == null || plannedGroups.size() != processors.size()) throw new IOException("Invalid native processor group count");
            List<@Unmodifiable Map<String, @Nullable String>> result = new ArrayList<>(processors.size());
            for (int i = 0; i < plannedGroups.size(); i++) {
                JsonArray pairs = plannedGroups.get(i).getAsJsonArray(); List<String> names = namesByGroup.get(i);
                if (pairs.size() != names.size()) throw new IOException("Invalid native processor option count");
                Map<String, @Nullable String> planned = new LinkedHashMap<>();
                for (int j = 0; j < pairs.size(); j++) {
                    JsonObject pair = pairs.get(j).getAsJsonObject();
                    @Nullable JsonElement name = pair.get("name");
                    @Nullable JsonElement value = pair.get("value");
                    if (name == null || !name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()
                            || !names.get(j).equals(name.getAsString()) || value == null)
                        throw new IOException("Invalid native processor option");
                    if (value.isJsonNull()) planned.put(names.get(j), null);
                    else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) planned.put(names.get(j), value.getAsString());
                    else throw new IOException("Invalid native processor value");
                }
                result.add(Collections.unmodifiableMap(planned));
            }
            return List.copyOf(result);
        } catch (IOException | RuntimeException e) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            LOG.warning("Native processor option planning failed; using Java parser");
            return null;
        }
    }

    /// Rejects strings that cannot round-trip through the UTF-8 protocol.
    /// @param value string to inspect
    /// @throws IOException if it contains an unpaired surrogate
    private static void checkUnicode(String value) throws IOException {
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(value)) throw new IOException("Invalid UTF-16");
    }
}
