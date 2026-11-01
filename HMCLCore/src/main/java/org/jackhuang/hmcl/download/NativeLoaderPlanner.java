/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.game.Arguments;
import org.jackhuang.hmcl.game.Artifact;
import org.jackhuang.hmcl.game.GameComponentType;
import org.jackhuang.hmcl.game.GameInstancePatch;
import org.jackhuang.hmcl.game.Library;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Adapts native Fabric-family metadata plans to existing typed loader patches.
@NotNullByDefault
public final class NativeLoaderPlanner {
    /// Prevents instantiation.
    private NativeLoaderPlanner() { }

    /// Attempts to plan a Fabric-family loader patch, returning null for the Java fallback.
    /// @param kind Fabric-family component kind
    /// @param metadata original metadata JSON
    /// @param version loader version
    /// @param cancelled cancellation predicate
    /// @return planned patch or null when unsupported
    /// @throws InterruptedException if cancelled
    public static @Nullable GameInstancePatch tryPlan(GameComponentType kind, @Nullable String metadata, String version,
                                                       BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank() || metadata == null
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.loaders.enabled", "true"))) return null;
        @Nullable String nativeKind = switch (kind) {
            case FABRIC -> "fabric";
            case LEGACY_FABRIC -> "legacyfabric";
            case QUILT -> "quilt";
            default -> null;
        };
        if (nativeKind == null) return null;
        try {
            JsonObject result = NativeBackend.planLoader(Path.of(executable), nativeKind, metadata, cancelled);
            String mainClass = string(result.get("main_class"));
            @Nullable String tweaker = result.get("tweaker").isJsonNull() ? null : string(result.get("tweaker"));
            JsonArray rawLibraries = result.getAsJsonArray("libraries");
            JsonArray coordinates = result.getAsJsonArray("coordinates");
            if (rawLibraries.size() > 20_000 || coordinates.size() > 2
                    || (kind == GameComponentType.QUILT ? coordinates.isEmpty() : coordinates.size() != 2))
                throw new IOException("Invalid native loader plan size");
            List<Library> libraries = new ArrayList<>();
            for (JsonElement element : rawLibraries) {
                if (!element.isJsonObject()) throw new IOException("Invalid native loader library");
                @Nullable Library library = LoaderLibraryParser.parse(element);
                if (library == null) throw new IOException("Invalid native loader library");
                libraries.add(library);
            }
            for (JsonElement coordinate : coordinates) {
                Artifact artifact = Artifact.fromDescriptor(string(coordinate));
                libraries.add(new Library(artifact, repository(kind, artifact.getGroup()), null));
            }
            Arguments arguments = new Arguments();
            if (tweaker != null) arguments = arguments.addGameArguments("--tweakClass", tweaker);
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            return new GameInstancePatch(kind.getPatchId(), version, GameInstancePatch.PRIORITY_LOADER, arguments, mainClass, libraries);
        } catch (IOException | RuntimeException e) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            LOG.warning("Native loader planning failed; using Java metadata parser");
            return null;
        }
    }

    /// Reads a JSON string without accepting numeric or boolean coercion.
    /// @param value JSON value
    /// @return string value
    /// @throws IOException if the value is not a string
    private static String string(@Nullable JsonElement value) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IOException("Invalid native loader string");
        return value.getAsString();
    }

    /// Selects the same Maven repository as each existing loader installer.
    /// @param kind loader kind
    /// @param group Maven group
    /// @return repository URL
    private static String repository(GameComponentType kind, String group) {
        if (kind == GameComponentType.QUILT && "org.quiltmc".equals(group))
            return "https://maven.quiltmc.org/repository/release/";
        if (kind == GameComponentType.LEGACY_FABRIC && "net.legacyfabric".equals(group))
            return "https://maven.legacyfabric.net/";
        return "https://maven.fabricmc.net/";
    }
}
