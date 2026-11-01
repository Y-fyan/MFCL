/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.forge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.game.Artifact;
import org.jackhuang.hmcl.game.GameRepositoryLayout;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Prepares Forge-family processor commands through Rust when inputs are supported.
@NotNullByDefault
public final class NativeProcessorCommand {
    /// Prevents instantiation.
    private NativeProcessorCommand() { }

    /// Native-prepared processor entry point and ordered classpath.
    public record Prepared(String mainClass, String classpath, @Nullable List<@Nullable String> arguments) { }

    /// Prepares the common Forge-family command in one native request.
    public static @Nullable Prepared tryCombined(Path jar, List<Artifact> libraries, GameRepositoryLayout layout,
            BooleanSupplier cancelled) throws InterruptedException {
        return tryCombined(jar, libraries, layout, null, null, cancelled);
    }

    /// Optionally expands processor arguments in the same request.
    public static @Nullable Prepared tryCombined(Path jar, List<Artifact> libraries, GameRepositoryLayout layout,
            @Nullable List<String> arguments, @Nullable Map<String, @Nullable String> variables,
            BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        @Nullable String executable = executable(); if (executable == null) return null;
        @Nullable Prepared coordinates = tryCoordinateCommand(Path.of(executable), jar, libraries, layout, arguments, variables, cancelled);
        if (coordinates != null) return coordinates;
        try {
            if (layout.getClass().getMethod("getArtifactFile", Artifact.class).getDeclaringClass() != GameRepositoryLayout.class) return null;
            String archive = jar.toString(); checkUnicode(archive);
            JsonArray encoded = new JsonArray(); List<String> expected = new ArrayList<>(libraries.size() + 1);
            for (Artifact artifact : libraries) {
                String path = layout.getArtifactFile(artifact).toString(); checkUnicode(path); encoded.add(path); expected.add(path);
            }
            expected.add(archive);
            JsonObject params = new JsonObject(); params.addProperty("jar", archive); params.add("libraries", encoded);
            params.addProperty("separator", File.pathSeparator);
            @Nullable JsonObject nativePaths = arguments == null || variables == null ? null
                    : NativeProcessorLiterals.encodeWithRoot(arguments, variables, layout);
            JsonObject result;
            if (nativePaths != null) {
                params.add("arguments", nativePaths);
                try {
                    result = NativeBackend.prepareProcessorCommand(Path.of(executable), "processor_command_v2", params, cancelled);
                } catch (IOException | RuntimeException e) {
                    if (cancelled.getAsBoolean()) throw new InterruptedException();
                    params.add("arguments", NativeProcessorLiterals.encode(arguments, variables, layout));
                    result = NativeBackend.prepareProcessorCommand(Path.of(executable), "processor_command", params, cancelled);
                }
            } else {
                if (arguments != null && variables != null) params.add("arguments", NativeProcessorLiterals.encode(arguments, variables, layout));
                result = NativeBackend.prepareProcessorCommand(Path.of(executable), "processor_command", params, cancelled);
            }
            if (!result.has("main_class") || !result.get("main_class").isJsonPrimitive() || !result.get("main_class").getAsJsonPrimitive().isString()
                    || !result.has("classpath") || !result.get("classpath").isJsonPrimitive() || !result.get("classpath").getAsJsonPrimitive().isString())
                throw new IOException("Invalid native processor command");
            String main = result.get("main_class").getAsString(), classpath = result.get("classpath").getAsString();
            if (!main.matches("[a-zA-Z0-9_.$]+") || !classpath.equals(String.join(File.pathSeparator, expected)))
                throw new IOException("Invalid native processor command");
            @Nullable List<@Nullable String> expanded = arguments != null && result.has("arguments") && result.get("arguments").isJsonObject()
                    ? NativeProcessorLiterals.decode(result.getAsJsonObject("arguments"), arguments.size()) : null;
            return new Prepared(main, classpath, expanded);
        } catch (IOException | ReflectiveOperationException | RuntimeException e) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            LOG.debug("Combined native processor preparation unavailable; using compatible preparation");
            return null;
        }
    }

    /// Resolves standard processor dependency coordinates directly in Rust on Unix layouts.
    private static @Nullable Prepared tryCoordinateCommand(Path executable, Path jar, List<Artifact> libraries,
            GameRepositoryLayout layout, @Nullable List<String> arguments, @Nullable Map<String, @Nullable String> variables,
            BooleanSupplier cancelled) throws InterruptedException {
        if (File.separatorChar != '/') return null;
        try {
            if (layout.getClass().getMethod("getArtifactFile", Artifact.class).getDeclaringClass() != GameRepositoryLayout.class) return null;
            Path root = layout.getLibrariesDirectory();
            if (root.getFileSystem() != FileSystems.getDefault() || root.toString().isEmpty()) return null;
            checkUnicode(root.toString()); checkUnicode(jar.toString());
            JsonArray encoded = new JsonArray();
            for (Artifact artifact : libraries) { String value = artifact.toString(); checkUnicode(value); encoded.add(value); }
            JsonObject params = new JsonObject(); params.addProperty("jar", jar.toString());
            params.addProperty("libraries_root", root.toString()); params.add("coordinates", encoded);
            params.addProperty("separator", File.pathSeparator);
            if (arguments != null && variables != null) {
                @Nullable JsonObject values = NativeProcessorLiterals.encodeWithRoot(arguments, variables, layout);
                params.add("arguments", values == null ? NativeProcessorLiterals.encode(arguments, variables, layout) : values);
            }
            JsonObject result = NativeBackend.prepareProcessorCommand(executable, "processor_command_v3", params, cancelled);
            if (!result.has("main_class") || !result.get("main_class").isJsonPrimitive() || !result.get("main_class").getAsJsonPrimitive().isString()
                    || !result.has("classpath") || !result.get("classpath").isJsonPrimitive() || !result.get("classpath").getAsJsonPrimitive().isString())
                throw new IOException("Invalid native processor command");
            String main = result.get("main_class").getAsString(), classpath = result.get("classpath").getAsString();
            JsonArray paths = result.getAsJsonArray("library_paths");
            if (!main.matches("[a-zA-Z0-9_.$]+") || paths.size() != libraries.size()) throw new IOException("Invalid native processor command");
            List<String> expected = new ArrayList<>(paths.size() + 1);
            for (var entry : paths) {
                if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) throw new IOException("Invalid native processor path");
                String text = entry.getAsString(); checkUnicode(text); Path path = Path.of(text);
                if (!path.startsWith(root) || path.equals(root) || !Files.isRegularFile(path)) throw new IOException("Invalid native processor path");
                for (Path segment : root.relativize(path))
                    if (segment.toString().equals(".") || segment.toString().equals("..")) throw new IOException("Invalid native processor path");
                expected.add(text);
            }
            expected.add(jar.toString());
            if (!classpath.equals(String.join(File.pathSeparator, expected))) throw new IOException("Invalid native processor classpath");
            @Nullable List<@Nullable String> expanded = arguments != null && result.has("arguments") && result.get("arguments").isJsonObject()
                    ? NativeProcessorLiterals.decode(result.getAsJsonObject("arguments"), arguments.size()) : null;
            return new Prepared(main, classpath, expanded);
        } catch (IOException | ReflectiveOperationException | RuntimeException e) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            return null;
        }
    }

    /// Reads a simple processor main class through the native backend.
    /// @param jar processor archive
    /// @param cancelled owning task cancellation predicate
    /// @return native main class, or null for the Java JAR reader
    /// @throws InterruptedException if cancelled
    public static @Nullable String tryMainClass(Path jar, BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        @Nullable String executable = executable(); if (executable == null) return null;
        try {
            String path = jar.toString(); checkUnicode(path);
            JsonObject params = new JsonObject(); params.addProperty("jar", path);
            JsonObject result = NativeBackend.prepareProcessorCommand(Path.of(executable), "processor_main_class", params, cancelled);
            if (!result.has("main_class") || !result.get("main_class").isJsonPrimitive()
                    || !result.get("main_class").getAsJsonPrimitive().isString()) throw new IOException("Invalid native processor class");
            String main = result.get("main_class").getAsString();
            if (!main.matches("[a-zA-Z0-9_.$]+")) throw new IOException("Invalid native processor class");
            return main;
        } catch (IOException | RuntimeException e) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            LOG.debug("Native processor manifest preparation unavailable; using Java");
            return null;
        }
    }

    /// Validates and joins processor dependencies using the standard artifact layout.
    /// @param jar processor archive, appended after dependencies
    /// @param libraries processor dependency coordinates
    /// @param layout repository layout
    /// @param cancelled owning task cancellation predicate
    /// @return joined classpath, or null for Java preparation
    /// @throws InterruptedException if cancelled
    public static @Nullable String tryClasspath(Path jar, List<Artifact> libraries, GameRepositoryLayout layout,
            BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        @Nullable String executable = executable(); if (executable == null) return null;
        try {
            if (layout.getClass().getMethod("getArtifactFile", Artifact.class).getDeclaringClass() != GameRepositoryLayout.class) return null;
            String archive = jar.toString(); checkUnicode(archive);
            JsonArray encoded = new JsonArray(); List<String> expected = new ArrayList<>(libraries.size() + 1);
            for (Artifact artifact : libraries) {
                String path = layout.getArtifactFile(artifact).toString(); checkUnicode(path); encoded.add(path); expected.add(path);
            }
            expected.add(archive);
            JsonObject params = new JsonObject(); params.addProperty("jar", archive); params.add("libraries", encoded);
            params.addProperty("separator", File.pathSeparator);
            JsonObject result = NativeBackend.prepareProcessorCommand(Path.of(executable), "processor_classpath", params, cancelled);
            if (!result.has("classpath") || !result.get("classpath").isJsonPrimitive()
                    || !result.get("classpath").getAsJsonPrimitive().isString()) throw new IOException("Invalid native processor classpath");
            String classpath = result.get("classpath").getAsString();
            if (!classpath.equals(String.join(File.pathSeparator, expected))) throw new IOException("Invalid native processor classpath");
            return classpath;
        } catch (IOException | ReflectiveOperationException | RuntimeException e) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            LOG.debug("Native processor classpath preparation unavailable; using Java");
            return null;
        }
    }

    /// Returns the configured backend when processor integration is enabled.
    /// @return executable path text, or null when unavailable
    private static @Nullable String executable() {
        @Nullable String value = System.getProperty("mfcl.native.path");
        return value == null || value.isBlank() || !Boolean.parseBoolean(System.getProperty("mfcl.native.processors.enabled", "true"))
                ? null : value;
    }

    /// Rejects paths that cannot round-trip through the UTF-8 protocol.
    /// @param value path text
    /// @throws IOException if the path contains unpaired surrogates
    private static void checkUnicode(String value) throws IOException {
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(value)) throw new IOException("Invalid UTF-16 path");
    }
}
