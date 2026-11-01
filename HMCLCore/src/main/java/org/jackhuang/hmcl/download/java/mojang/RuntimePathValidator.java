/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.java.mojang;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/// Checks Mojang runtime entries before the installer schedules any file writes.
@NotNullByDefault
final class RuntimePathValidator {
    /// Reserved Windows device basenames, including names with file extensions.
    private static final @Unmodifiable Set<String> WINDOWS_RESERVED = Set.of("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5",
            "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
    /// One normalized entry and an optional resolved symbolic-link target.
    /// @param path path relative to the runtime root
    /// @param target normalized target relative to the runtime root, or null for a non-link
    @NotNullByDefault
    private record Entry(Path path, @Nullable Path target) { }

    /// Prevents instantiation.
    private RuntimePathValidator() { }

    /// Validates lexical paths, declared links and existing symlink ancestors before installation.
    /// @param root trusted runtime staging directory
    /// @param items ordered manifest entries
    /// @param cancelled owning task cancellation predicate
    /// @throws IOException if a path is unsafe or cannot be represented
    /// @throws InterruptedException if cancelled
    static void validate(Path root, List<Map.Entry<String, MojangJavaRemoteFiles.Remote>> items,
            BooleanSupplier cancelled) throws IOException, InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        tryNative(items, cancelled);
        Path absoluteRoot = root.toAbsolutePath();
        // Lexically collapsing a parent component across a symlink can name a different directory.
        for (Path part : absoluteRoot)
            if ("..".equals(part.toString())) throw new IOException("Runtime staging root contains a parent component");
        absoluteRoot = absoluteRoot.normalize();
        if (Files.isSymbolicLink(absoluteRoot)) throw new IOException("Runtime staging root is a symbolic link");
        List<Entry> entries = new ArrayList<>(items.size()); Set<String> names = new HashSet<>(); Set<String> links = new HashSet<>();
        try {
            for (Map.Entry<String, MojangJavaRemoteFiles.Remote> item : items) {
                if (cancelled.getAsBoolean()) throw new InterruptedException();
                Path path = Path.of(item.getKey());
                if (item.getKey().isEmpty() || path.isAbsolute()) throw new IOException("Invalid runtime entry path");
                for (Path part : path) validateComponent(part.toString());
                path = path.normalize();
                if (path.toString().isEmpty() || !absoluteRoot.resolve(path).normalize().startsWith(absoluteRoot))
                    throw new IOException("Runtime entry escapes its staging root");
                if (!names.add(key(path))) throw new IOException("Duplicate runtime entry path");
                @Nullable Path target = null;
                if (item.getValue() instanceof MojangJavaRemoteFiles.RemoteLink link) {
                    @Nullable String text = link.getTarget();
                    if (text == null || text.isEmpty()) throw new IOException("Invalid runtime link target");
                    Path linkPath = Path.of(text);
                    if (linkPath.isAbsolute()) throw new IOException("Absolute runtime link target");
                    boolean sawName = false;
                    for (Path part : linkPath) {
                        String component = part.toString();
                        if ("..".equals(component)) {
                            if (sawName) throw new IOException("Runtime link has unsafe parent traversal");
                        } else if (!".".equals(component) && !component.isEmpty()) {
                            validateComponent(component); sawName = true;
                        }
                    }
                    @Nullable Path parent = path.getParent();
                    target = (parent == null ? Path.of("") : parent).resolve(linkPath).normalize();
                    if (target.isAbsolute() || target.startsWith("..") || !absoluteRoot.resolve(target).normalize().startsWith(absoluteRoot))
                        throw new IOException("Runtime link escapes its staging root");
                    links.add(key(path));
                }
                entries.add(new Entry(path, target));
            }
            for (Entry entry : entries) {
                if (cancelled.getAsBoolean()) throw new InterruptedException();
                for (@Nullable Path parent = entry.path().getParent(); parent != null; parent = parent.getParent())
                    if (links.contains(key(parent))) throw new IOException("Runtime entry has a link ancestor");
                checkExistingLinks(absoluteRoot, entry.path(), entry.target() != null);
                if (entry.target() != null) {
                    for (@Nullable Path part = entry.target(); part != null; part = part.getParent())
                        if (links.contains(key(part))) throw new IOException("Runtime link targets another link");
                    checkExistingLinks(absoluteRoot, entry.target(), false);
                }
            }
        } catch (InvalidPathException e) { throw new IOException("Invalid runtime path", e); }
    }

    /// Rejects parent components and Windows names with ambiguous filesystem aliases.
    /// @param value one path component
    /// @throws IOException if the component is unsafe
    private static void validateComponent(String value) throws IOException {
        if ("..".equals(value)) throw new IOException("Runtime entry contains a parent component");
        if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
            if (value.endsWith(".") || value.endsWith(" ") || value.indexOf(':') >= 0)
                throw new IOException("Ambiguous Windows runtime path");
            String stem = value.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
            if (WINDOWS_RESERVED.contains(stem))
                throw new IOException("Reserved Windows runtime path");
        }
    }

    /// Checks existing components without following a link inside the staging root.
    /// @param root absolute runtime root
    /// @param relative relative path to inspect
    /// @param allowFinalLink whether an existing final link will be replaced
    /// @throws IOException if an unexpected link is present
    private static void checkExistingLinks(Path root, Path relative, boolean allowFinalLink) throws IOException {
        Path current = root; int index = 0; int count = relative.getNameCount();
        for (Path part : relative) {
            current = current.resolve(part); index++;
            if (Files.isSymbolicLink(current) && !(allowFinalLink && index == count))
                throw new IOException("Runtime staging path contains a symbolic link");
        }
    }

    /// Produces a platform-appropriate key for detecting duplicate and linked paths.
    /// @param path normalized relative path
    /// @return comparison key
    private static String key(Path path) {
        String value = path.toString();
        if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS) value = Normalizer.normalize(value, Normalizer.Form.NFD);
        return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS || OperatingSystem.CURRENT_OS == OperatingSystem.MACOS
                ? value.toLowerCase(Locale.ROOT) : value;
    }

    /// Uses Rust for an additional bounded lexical plan when the configured backend supports it.
    /// @param items runtime entries
    /// @param cancelled owning task cancellation predicate
    /// @throws IOException if Rust identifies an unsafe path
    /// @throws InterruptedException if cancelled
    private static void tryNative(List<Map.Entry<String, MojangJavaRemoteFiles.Remote>> items, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank() || !Boolean.parseBoolean(System.getProperty("mfcl.native.java.enabled", "true"))) return;
        try {
            JsonArray encoded = new JsonArray();
            for (Map.Entry<String, MojangJavaRemoteFiles.Remote> entry : items) {
                if (cancelled.getAsBoolean()) throw new InterruptedException();
                if (!StandardCharsets.UTF_8.newEncoder().canEncode(entry.getKey())) return;
                JsonObject item = new JsonObject(); item.addProperty("path", entry.getKey());
                if (entry.getValue() instanceof MojangJavaRemoteFiles.RemoteLink link) {
                    @Nullable String target = link.getTarget();
                    if (target == null || !StandardCharsets.UTF_8.newEncoder().canEncode(target)) return;
                    item.addProperty("target", target);
                }
                encoded.add(item);
            }
            JsonObject params = new JsonObject(); params.add("entries", encoded);
            NativeBackend.validateRuntimePaths(Path.of(executable), params, cancelled);
        } catch (NativeBackend.OperationException e) {
            if ("unsafe_runtime_path".equals(e.code())) throw new IOException("Unsafe runtime manifest path", e);
        } catch (IOException | RuntimeException ignored) {
            // Java validates the same paths when native planning is unavailable.
        }
    }
}
