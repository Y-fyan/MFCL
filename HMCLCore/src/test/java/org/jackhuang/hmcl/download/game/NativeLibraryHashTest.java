/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download.game;

import org.jackhuang.hmcl.game.Artifact;
import org.jackhuang.hmcl.game.CompatibilityRule;
import org.jackhuang.hmcl.game.OSRestriction;
import org.jackhuang.hmcl.game.DefaultGameInstance;
import org.jackhuang.hmcl.game.DefaultGameRepository;
import org.jackhuang.hmcl.game.DefaultGameRepositoryLayout;
import org.jackhuang.hmcl.game.DefaultGameRepositorySnapshot;
import org.jackhuang.hmcl.game.GameInstanceID;
import org.jackhuang.hmcl.game.GameInstanceManifest;
import org.jackhuang.hmcl.game.LibrariesDownloadInfo;
import org.jackhuang.hmcl.game.Library;
import org.jackhuang.hmcl.game.LibraryDownloadInfo;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Compares native library hash observations with the existing Java installation checks.
@NotNullByDefault
@EnabledOnOs({OS.LINUX, OS.MAC})
@ResourceLock(Resources.SYSTEM_PROPERTIES)
public final class NativeLibraryHashTest {
    /// Isolated test directory.
    @TempDir Path tempDir;
    /// Existing native executable setting.
    private final @Nullable String previousPath = System.getProperty("mfcl.native.path");
    /// Existing native library inspection setting.
    private final @Nullable String previousEnabled = System.getProperty("mfcl.native.libraries.enabled");
    /// Test game manifest.
    private static final GameInstanceManifest MANIFEST = new GameInstanceManifest(new GameInstanceID("fixture"));
    /// SHA-1 of `abc`.
    private static final String ABC = "a9993e364706816aba3e25717850c26c9cd0d89d";

    /// Restores global settings.
    @AfterEach void restore() {
        if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
        if (previousEnabled == null) System.clearProperty("mfcl.native.libraries.enabled"); else System.setProperty("mfcl.native.libraries.enabled", previousEnabled);
    }

    /// Installation rules match Java and remain cancellable and configurable.
    @Test void nativeInstallationRuleSelectionMatchesJava() throws Exception {
        configureNative();
        List<Library> libraries = List.of(new Library("org.example", "plain", "1"),
                new Library(new Artifact("org.example", "platform", "2"), null, null, null, null, null,
                        List.of(new CompatibilityRule(CompatibilityRule.Action.ALLOW, new OSRestriction("linux-x86_64", null, null))), null, null),
                new Library(new Artifact("org.example", "feature", "3"), null, null, null, null, null,
                        List.of(new CompatibilityRule(CompatibilityRule.Action.ALLOW, null, Map.of("demo", true))), null, null));
        boolean @Nullable [] selected = GameLibrariesTask.selectLibraryApplicability(libraries, () -> false);
        assertNotNull(selected); assertEquals(libraries.size(), selected.length);
        for (int i = 0; i < selected.length; i++) assertEquals(libraries.get(i).appliesToCurrentEnvironment(), selected[i]);
        assertThrows(InterruptedException.class, () -> GameLibrariesTask.selectLibraryApplicability(libraries, () -> true));
        System.setProperty("mfcl.native.libraries.enabled", "false");
        assertNull(GameLibrariesTask.selectLibraryApplicability(libraries, () -> false));
    }

    /// Native hashes match Java decisions while Java continues checking JAR structure.
    @Test void nativeObservationsPreserveJavaLibraryDecisions() throws Exception {
        configureNative(); TestRepository repository = new TestRepository(tempDir.resolve("game"));
        Artifact goodArtifact = new Artifact("org.test", "good", "1");
        Path goodPath = repository.getLayout().getLibraryFile(MANIFEST.id(), new Library(goodArtifact));
        createJar(goodPath); Library good = library(goodArtifact, DigestUtils.digestToString("SHA-1", goodPath), null);
        Artifact mismatchArtifact = new Artifact("org.test", "mismatch", "1");
        Path mismatchPath = repository.getLayout().getLibraryFile(MANIFEST.id(), new Library(mismatchArtifact));
        createJar(mismatchPath); Library mismatch = library(mismatchArtifact, "0000000000000000000000000000000000000000", null);
        Artifact corruptArtifact = new Artifact("org.test", "corrupt", "1");
        Path corruptPath = repository.getLayout().getLibraryFile(MANIFEST.id(), new Library(corruptArtifact));
        createFile(corruptPath, "abc"); Library corrupt = library(corruptArtifact, ABC, null);
        Artifact plainArtifact = new Artifact("org.test", "plain", "1", null, "bin");
        Path plainPath = repository.getLayout().getLibraryFile(MANIFEST.id(), new Library(plainArtifact));
        createFile(plainPath, "abc"); Library plain = library(plainArtifact, ABC, null);
        Artifact forgeArtifact = new Artifact("org.test", "forge-checksums", "1");
        Path forgePath = repository.getLayout().getLibraryFile(MANIFEST.id(), new Library(forgeArtifact));
        createJar(forgePath); String forgeHash = DigestUtils.digestToString("SHA-1", forgePath);
        Library forge = library(forgeArtifact, forgeHash, List.of(forgeHash));
        Artifact optifineArtifact = new Artifact("optifine", "OptiFine", "1");
        Path optifinePath = repository.getLayout().getLibraryFile(MANIFEST.id(), new Library(optifineArtifact));
        createJar(optifinePath); Library optifine = library(optifineArtifact, DigestUtils.digestToString("SHA-1", optifinePath), null);
        List<Library> libraries = List.of(good, mismatch, corrupt, plain, forge, optifine);
        AtomicLong completed = new AtomicLong(-1); AtomicLong total = new AtomicLong(-1);
        Map<Integer, NativeBackend.FileHashResult> observations = GameLibrariesTask.inspectLibraryHashes(repository, MANIFEST, libraries,
                true, () -> false, (done, expected) -> { completed.set(done); total.set(expected); });
        assertEquals(5, completed.get()); assertEquals(5, total.get());
        assertEquals(5, observations.size()); assertTrue(observations.containsKey(4)); assertFalse(observations.containsKey(5));
        assertTrue(NativeBackend.isFileHashObservationCurrent(goodPath, observations.get(0)));
        for (int i = 0; i < libraries.size(); i++) {
            boolean javaResult = GameLibrariesTask.shouldDownloadLibrary(repository, MANIFEST, libraries.get(i), true);
            boolean nativeResult = GameLibrariesTask.shouldDownloadLibrary(repository, MANIFEST, libraries.get(i), true, observations.get(i));
            assertEquals(javaResult, nativeResult, "library index " + i);
        }
        assertFalse(GameLibrariesTask.shouldDownloadLibrary(repository, MANIFEST, good, true, observations.get(0)));
        assertTrue(GameLibrariesTask.shouldDownloadLibrary(repository, MANIFEST, mismatch, true, observations.get(1)));
        assertTrue(GameLibrariesTask.shouldDownloadLibrary(repository, MANIFEST, corrupt, true, observations.get(2)));
    }

    /// Replacing a file with equal size and restored mtime invalidates its native observation.
    @Test void replacementWithSameSizeAndMtimeFallsBackToJava() throws Exception {
        configureNative(); TestRepository repository = new TestRepository(tempDir.resolve("game"));
        Artifact artifact = new Artifact("org.test", "plain", "1", null, "bin");
        Path file = repository.getLayout().getLibraryFile(MANIFEST.id(), new Library(artifact));
        createFile(file, "abc"); Library library = library(artifact, ABC, null);
        Map<Integer, NativeBackend.FileHashResult> observations = GameLibrariesTask.inspectLibraryHashes(repository, MANIFEST, List.of(library), true, () -> false);
        NativeBackend.FileHashResult observation = java.util.Objects.requireNonNull(observations.get(0));
        assertTrue(NativeBackend.isFileHashObservationCurrent(file, observation));
        FileTime originalTime = Files.getLastModifiedTime(file);
        Path replacement = tempDir.resolve("replacement"); Files.writeString(replacement, "xyz");
        Files.setLastModifiedTime(replacement, originalTime);
        Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING);
        assertEquals(originalTime, Files.getLastModifiedTime(file));
        assertFalse(NativeBackend.isFileHashObservationCurrent(file, observation));
        assertTrue(GameLibrariesTask.shouldDownloadLibrary(repository, MANIFEST, library, true, observation));
    }

    /// An in-place same-size change with restored mtime is detected through change time.
    @Test void inPlaceChangeWithRestoredMtimeFallsBackToJava() throws Exception {
        configureNative(); TestRepository repository = new TestRepository(tempDir.resolve("game"));
        Artifact artifact = new Artifact("org.test", "plain", "1", null, "bin");
        Path file = repository.getLayout().getLibraryFile(MANIFEST.id(), new Library(artifact));
        createFile(file, "abc"); Library library = library(artifact, ABC, null);
        NativeBackend.FileHashResult observation = java.util.Objects.requireNonNull(
                GameLibrariesTask.inspectLibraryHashes(repository, MANIFEST, List.of(library), true, () -> false).get(0));
        FileTime originalTime = Files.getLastModifiedTime(file);
        Files.writeString(file, "xyz"); Files.setLastModifiedTime(file, originalTime);
        FileTime changed = (FileTime) Files.getAttribute(file, "unix:ctime");
        assumeTrue(changed.to(TimeUnit.NANOSECONDS) != observation.changedNanos(), "Filesystem change time has insufficient resolution");
        assertEquals(originalTime, Files.getLastModifiedTime(file));
        assertFalse(NativeBackend.isFileHashObservationCurrent(file, observation));
        assertTrue(GameLibrariesTask.shouldDownloadLibrary(repository, MANIFEST, library, true, observation));
    }

    /// Disabled native inspection and disabled integrity checks do not start a backend.
    @Test void disabledInspectionUsesJavaPath() throws Exception {
        Path script = script("exit 1"); System.setProperty("mfcl.native.path", script.toString());
        TestRepository repository = new TestRepository(tempDir.resolve("game"));
        Artifact artifact = new Artifact("org.test", "plain", "1", null, "bin");
        Path file = repository.getLayout().getLibraryFile(MANIFEST.id(), new Library(artifact)); createFile(file, "abc");
        Library library = library(artifact, ABC, null);
        System.setProperty("mfcl.native.libraries.enabled", "false");
        assertTrue(GameLibrariesTask.inspectLibraryHashes(repository, MANIFEST, List.of(library), true, () -> false).isEmpty());
        System.clearProperty("mfcl.native.libraries.enabled");
        assertTrue(GameLibrariesTask.inspectLibraryHashes(repository, MANIFEST, List.of(library), false, () -> false).isEmpty());
        assertFalse(Files.exists(Path.of(script + ".called")));
    }

    /// Invalid backend output falls back to the Java verification path.
    @Test void malformedNativeResultFallsBack() throws Exception {
        Path script = script("printf '%s\\n' '{\"version\":1,\"id\":1,\"result\":{\"results\":[]}}'");
        System.setProperty("mfcl.native.path", script.toString());
        TestRepository repository = new TestRepository(tempDir.resolve("game"));
        Artifact artifact = new Artifact("org.test", "plain", "1", null, "bin");
        Path file = repository.getLayout().getLibraryFile(MANIFEST.id(), new Library(artifact)); createFile(file, "abc");
        Library library = library(artifact, ABC, null);
        assertTrue(GameLibrariesTask.inspectLibraryHashes(repository, MANIFEST, List.of(library), true, () -> false).isEmpty());
        assertTrue(Files.exists(Path.of(script + ".called")));
        assertFalse(GameLibrariesTask.shouldDownloadLibrary(repository, MANIFEST, library, true));
    }

    /// Cancellation stops an in-flight native hash batch.
    @Test void cancellationStopsHashBatch() throws Exception {
        Path script = script("printf '%s' \"$$\" > \"$0.pid\"\nwhile :; do :; done");
        System.setProperty("mfcl.native.path", script.toString());
        TestRepository repository = new TestRepository(tempDir.resolve("game"));
        Artifact artifact = new Artifact("org.test", "plain", "1", null, "bin");
        Path file = repository.getLayout().getLibraryFile(MANIFEST.id(), new Library(artifact)); createFile(file, "abc");
        Library library = library(artifact, ABC, null);
        AtomicBoolean cancelled = new AtomicBoolean(); AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { GameLibrariesTask.inspectLibraryHashes(repository, MANIFEST, List.of(library), true, cancelled::get); failure.set(new AssertionError("Expected cancellation")); }
            catch (Throwable e) { failure.set(e); }
        });
        worker.setDaemon(true); worker.start();
        try {
            Path pidFile = Path.of(script + ".pid"); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!Files.exists(pidFile) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(pidFile)); long pid = Long.parseLong(Files.readString(pidFile));
            cancelled.set(true); worker.join(5000); assertFalse(worker.isAlive());
            assertInstanceOf(InterruptedException.class, failure.get());
            ProcessHandle.of(pid).ifPresent(process -> assertDoesNotThrow(() -> process.onExit().get(3, TimeUnit.SECONDS)));
        } finally { cancelled.set(true); worker.interrupt(); worker.join(5000); }
    }

    /// Selects the real Rust executable supplied by the test environment.
    private void configureNative() {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(executable != null && !executable.isBlank());
        System.setProperty("mfcl.native.path", executable); System.clearProperty("mfcl.native.libraries.enabled");
    }

    /// Creates a library with an optional additional checksum list.
    /// @param artifact Maven artifact
    /// @param sha1 expected SHA-1
    /// @param checksums optional Forge-style checksums
    /// @return library descriptor
    private static Library library(Artifact artifact, String sha1, @Nullable List<String> checksums) {
        return new Library(artifact, null, new LibrariesDownloadInfo(new LibraryDownloadInfo(null, "https://example.invalid/file", sha1)),
                checksums, null, null, null, null, null);
    }

    /// Creates a simple ZIP/JAR fixture.
    /// @param file target path
    /// @throws IOException if creation fails
    private static void createJar(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("fixture.txt")); zip.write("abc".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
    }

    /// Creates a plain file fixture.
    /// @param file target path
    /// @param contents file content
    /// @throws IOException if creation fails
    private static void createFile(Path file, String contents) throws IOException {
        Files.createDirectories(file.getParent()); Files.writeString(file, contents);
    }

    /// Creates a shell fixture for native backend failures or cancellation.
    /// @param body shell body
    /// @return executable path
    /// @throws IOException if creation fails
    private Path script(String body) throws IOException {
        Path path = tempDir.resolve("backend"); Files.writeString(path, "#!/bin/sh\nread line\n: > \"$0.called\"\n" + body + "\n");
        if (!path.toFile().setExecutable(true)) throw new IOException("Cannot mark fixture executable");
        return path;
    }

    /// Minimal repository for isolated library paths.
    private static final class TestRepository extends DefaultGameRepository {
        /// Creates a repository rooted in the test directory.
        /// @param root repository root
        private TestRepository(Path root) { super(root); }
        /// Creates a standard layout.
        @Override protected DefaultGameRepositoryLayout createLayout(Path root) { return new DefaultGameRepositoryLayout(root); }
        /// No instance is materialized in these checks.
        @Override protected DefaultGameInstance createInstance(DefaultGameRepositorySnapshot snapshot, GameInstanceID id,
                                                               GameInstanceManifest manifest, @Nullable Path manifestFile) {
            throw new UnsupportedOperationException("No instance creation expected");
        }
    }
}
