/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.launch;

import org.jackhuang.hmcl.game.Artifact;
import org.jackhuang.hmcl.game.CompatibilityRule;
import org.jackhuang.hmcl.game.DefaultGameRepositoryLayout;
import org.jackhuang.hmcl.game.GameInstanceID;
import org.jackhuang.hmcl.game.LibrariesDownloadInfo;
import org.jackhuang.hmcl.game.Library;
import org.jackhuang.hmcl.game.LibraryDownloadInfo;
import org.jackhuang.hmcl.game.OSRestriction;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Compares native classpath planning against the existing Java library resolution.
@NotNullByDefault
@EnabledOnOs({OS.LINUX, OS.MAC})
@ResourceLock(Resources.SYSTEM_PROPERTIES)
public final class NativeClasspathTest {
    /// Isolated game directory.
    @TempDir Path tempDir;
    /// Previous native executable setting.
    private final @Nullable String previousPath = System.getProperty("mfcl.native.path");
    /// Previous native classpath setting.
    private final @Nullable String previousEnabled = System.getProperty("mfcl.native.classpath.enabled");
    /// Synthetic instance identifier.
    private static final GameInstanceID ID = new GameInstanceID("test");

    /// Restores system properties after each test.
    @AfterEach void restore() {
        org.jackhuang.hmcl.nativebridge.NativeTransferTestSupport.clearIdle();
        if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
        if (previousEnabled == null) System.clearProperty("mfcl.native.classpath.enabled"); else System.setProperty("mfcl.native.classpath.enabled", previousEnabled);
    }

    /// Native planning preserves Java library order, overrides, local files, filters and deduplication.
    @Test void nativeMatchesJavaForLibraryVariants() throws Exception {
        Path executable = executable();
        DefaultGameRepositoryLayout layout = new DefaultGameRepositoryLayout(tempDir);
        Library ordinary = new Library("org.example", "core", "1");
        Library classifier = new Library(new Artifact("org.example", "extra", "2", "client", "zip"));
        Library override = new Library(new Artifact("org.example", "override", "3"), null,
                new LibrariesDownloadInfo(new LibraryDownloadInfo("custom/override.jar", "https://example.invalid/")));
        Library local = new Library(new Artifact("org.example", "local", "4"), null, null, null, null, null, null, "local", "custom-local.jar");
        Library excluded = new Library(new Artifact("org.example", "excluded", "5"), null, null, null, null, null,
                List.of(new CompatibilityRule(CompatibilityRule.Action.DISALLOW, null)), null, null);
        Library nativeLibrary = new Library(new Artifact("org.example", "native", "6"), null, null, null, null,
                Map.of("linux", "natives-linux"), null, null, null);
        Library missing = new Library("org.example", "missing", "7");
        for (Library library : List.of(ordinary, classifier, override, local, excluded, nativeLibrary)) createFile(layout.getLibraryFile(ID, library));
        List<Library> libraries = List.of(ordinary, classifier, override, local, ordinary, excluded, nativeLibrary, missing);
        assertNativeMatchesJava(executable, layout, libraries, false, false);
    }

    /// Classifier and native decisions preserve descriptor precedence and existing classifiers.
    @Test void nativeClassifierSelectionMatchesJava() throws Exception {
        Path executable = executable(); DefaultGameRepositoryLayout layout = new DefaultGameRepositoryLayout(tempDir);
        String os = org.jackhuang.hmcl.util.platform.OperatingSystem.CURRENT_OS.getMojangName();
        LibraryDownloadInfo classifierInfo = new LibraryDownloadInfo("classifiers/irrelevant.jar", "https://example.invalid/");
        Library dynamic = new Library(new Artifact("org.example", "dynamic", "1"), null,
                new LibrariesDownloadInfo(null, Map.of(os, classifierInfo)), null, null, null, null, null, null);
        Library explicit = new Library(new Artifact("org.example", "explicit", "2", "custom"), null,
                new LibrariesDownloadInfo(null, Map.of(os, classifierInfo)), null, null, null, null, null, null);
        Library nativeClassifier = new Library(new Artifact("org.example", "native", "3"), null,
                new LibrariesDownloadInfo(null, Map.of("natives-" + os, classifierInfo)), null, null, null, null, null, null);
        Library nativeMap = new Library(new Artifact("org.example", "mapped", "4"), null, null, null, null, Map.of(), null, null, null);
        String bits = org.jackhuang.hmcl.util.platform.Architecture.SYSTEM_ARCH.getBits().getBit();
        Library mappedOverride = new Library(new Artifact("org.example", "override-native", "5"), null,
                new LibrariesDownloadInfo(null, Map.of("natives-" + os + "-" + bits, new LibraryDownloadInfo("custom/native.jar", "https://example.invalid/"))),
                null, null, Map.of(os, "natives-" + os + "-${arch}"), null, null, null);
        Library localNative = new Library(new Artifact("org.example", "local-native", "6", "custom"), null, null, null, null,
                Map.of(), null, "local", "my-native.jar");
        List<Library> libraries = List.of(dynamic, explicit, nativeClassifier, nativeMap, mappedOverride, localNative);
        for (Library library : libraries) createFile(layout.getLibraryFile(ID, library));
        assertNativeMatchesJava(executable, layout, libraries, false, false);
        boolean[] nativeSelection = NativeBackend.selectNativeLibraries(executable, libraries);
        boolean @Nullable [] launchSelection = DefaultLauncher.selectNativeLibraries(libraries);
        assertNotNull(launchSelection); assertArrayEquals(nativeSelection, launchSelection);
        for (int i = 0; i < libraries.size(); i++) assertEquals(libraries.get(i).isNative(), nativeSelection[i]);
        Map<Integer, Path> archivePlan = NativeBackend.planNativeArchives(executable, layout, ID, libraries);
        assertEquals(archivePlan, DefaultLauncher.planNativeArchives(layout, ID, libraries));
        for (int i = 0; i < libraries.size(); i++) {
            if (libraries.get(i).isNative()) assertEquals(layout.getLibraryFile(ID, libraries.get(i)), archivePlan.get(i));
            else assertFalse(archivePlan.containsKey(i));
        }
    }

    /// Library rules and classpath planning share one Rust request.
    @Test void nativeClassPathEvaluatesLibraryRules() throws Exception {
        executable(); Path wrapper = tempDir.resolve("classpath-backend");
        Files.writeString(wrapper, """
                #!/usr/bin/env python3
                import json, os, subprocess, sys
                child = subprocess.Popen([os.environ['MFCL_NATIVE_TEST_EXECUTABLE']], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
                try:
                    for line in sys.stdin:
                        with open(__file__ + '.requests', 'a') as log: log.write(line)
                        child.stdin.write(line); child.stdin.flush()
                        while True:
                            response = child.stdout.readline()
                            if not response: sys.exit(1)
                            sys.stdout.write(response); sys.stdout.flush()
                            frame = json.loads(response)
                            if 'result' in frame or 'error' in frame: break
                finally:
                    child.stdin.close(); child.wait(timeout=5)
                """);
        assertTrue(wrapper.toFile().setExecutable(true));
        DefaultGameRepositoryLayout layout = new DefaultGameRepositoryLayout(tempDir);
        Library include = new Library(new Artifact("org.example", "os-check", "1"), null, null, null, null, null,
                List.of(new CompatibilityRule(CompatibilityRule.Action.ALLOW, new OSRestriction("linux-x86_64", "bad[", "bad["))), null, null);
        Library exclude = new Library(new Artifact("org.example", "feature-check", "2"), null, null, null, null, null,
                List.of(new CompatibilityRule(CompatibilityRule.Action.ALLOW, null, Map.of("demo", true))), null, null);
        createFile(layout.getLibraryFile(ID, include)); createFile(layout.getLibraryFile(ID, exclude));
        assertNativeMatchesJava(wrapper, layout, List.of(include, exclude), false, false);
        Library nativeLibrary = new Library(new Artifact("org.example", "native", "1"), null, null, null, null,
                Map.of("linux", "natives-linux"), null, null, null);
        assertEquals(layout.getLibraryFile(ID, nativeLibrary), NativeBackend.planNativeArchives(wrapper, layout, ID, List.of(nativeLibrary)).get(0));
        String requests = Files.readString(Path.of(wrapper + ".requests"));
        assertTrue(requests.contains("\"method\":\"plan_classpath_with_rules\""));
        assertTrue(requests.contains("\"method\":\"plan_native_archives_with_rules\""));
        assertTrue(requests.contains("\"os_name\":\"linux-x86_64\""));
        assertFalse(requests.contains("\"method\":\"select_library_rules\""));
    }

    /// Workers without the combined capability retain the older two-request protocol.
    @Test void olderWorkerFallsBackToSeparateRuleAndClasspathRequests() throws Exception {
        executable(); Path wrapper = tempDir.resolve("old-classpath-backend");
        Files.writeString(wrapper, """
                #!/usr/bin/env python3
                import json, os, subprocess, sys
                child = subprocess.Popen([os.environ['MFCL_NATIVE_TEST_EXECUTABLE']], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
                try:
                    for line in sys.stdin:
                        request = json.loads(line)
                        with open(__file__ + '.methods', 'a') as out: out.write(request['method'] + '\\n')
                        child.stdin.write(line); child.stdin.flush()
                        response = json.loads(child.stdout.readline())
                        if request['method'] == 'health':
                            caps = response['result']['capabilities']
                            if 'classpath_rules_combined_v1' in caps: caps.remove('classpath_rules_combined_v1')
                            if 'native_archive_rules_combined_v1' in caps: caps.remove('native_archive_rules_combined_v1')
                        print(json.dumps(response), flush=True)
                finally:
                    child.stdin.close(); child.wait(timeout=5)
                """);
        assertTrue(wrapper.toFile().setExecutable(true));
        DefaultGameRepositoryLayout layout = new DefaultGameRepositoryLayout(tempDir);
        Library library = new Library("org.example", "library", "1"); createFile(layout.getLibraryFile(ID, library));
        assertNativeMatchesJava(wrapper, layout, List.of(library), false, false);
        Library nativeLibrary = new Library(new Artifact("org.example", "native", "1"), null, null, null, null,
                Map.of("linux", "natives-linux"), null, null, null);
        assertEquals(layout.getLibraryFile(ID, nativeLibrary), NativeBackend.planNativeArchives(wrapper, layout, ID, List.of(nativeLibrary)).get(0));
        List<String> methods = Files.readAllLines(Path.of(wrapper + ".methods"));
        assertTrue(methods.contains("select_library_rules")); assertTrue(methods.contains("plan_classpath"));
        assertFalse(methods.contains("plan_classpath_with_rules"));
        assertTrue(methods.contains("plan_native_archives")); assertFalse(methods.contains("plan_native_archives_with_rules"));
    }

    /// OptiFine installer placement and ModLauncher exclusion match Java.
    @Test void nativeMatchesOptifineHandling() throws Exception {
        Path executable = executable();
        DefaultGameRepositoryLayout layout = new DefaultGameRepositoryLayout(tempDir);
        Library patch = new Library("optifine", "OptiFine", "X");
        Library installer = new Library(new Artifact("optifine", "OptiFine", "X", "installer"));
        Library wrapper = new Library("optifine", "launchwrapper-of", "2");
        Library forge = new Library("net.minecraftforge", "forge", "1");
        for (Library library : List.of(patch, installer, wrapper, forge)) createFile(layout.getLibraryFile(ID, library));
        List<Library> libraries = List.of(patch, wrapper, forge);
        assertNativeMatchesJava(executable, layout, libraries, true, false);
        assertNativeMatchesJava(executable, layout, libraries, true, true);
    }

    /// Many generated descriptors preserve Java selection and ordering.
    @Test void generatedLibraryDescriptorsMatchJava() throws Exception {
        Path executable = executable();
        DefaultGameRepositoryLayout layout = new DefaultGameRepositoryLayout(tempDir);
        List<Library> libraries = new ArrayList<>();
        Random random = new Random(4821);
        for (int i = 0; i < 600; i++) {
            @Nullable String classifier = random.nextBoolean() ? null : "client";
            Artifact artifact = new Artifact("org.example.g" + (i % 7), "lib" + i, "1." + (i % 4), classifier,
                    random.nextBoolean() ? "jar" : "zip");
            @Nullable LibrariesDownloadInfo downloads = random.nextBoolean() ? null :
                    new LibrariesDownloadInfo(new LibraryDownloadInfo("overrides/" + i + ".jar", "https://example.invalid/"));
            boolean local = random.nextInt(5) == 0;
            @Nullable String filename = local && random.nextBoolean() ? "local-" + i + ".jar" : null;
            @Nullable List<CompatibilityRule> rules = random.nextInt(5) == 0
                    ? List.of(new CompatibilityRule(CompatibilityRule.Action.DISALLOW, null)) : null;
            Library library = new Library(artifact, null, downloads, null, null, null, rules, local ? "local" : null, filename);
            libraries.add(library);
            if (random.nextBoolean()) createFile(layout.getLibraryFile(ID, library));
        }
        libraries.addAll(libraries.subList(0, 20));
        assertNativeMatchesJava(executable, layout, libraries, false, false);
    }

    /// File checks preserve symlink-plus-parent semantics before lexical normalization.
    @Test void symlinkParentResolutionMatchesJava() throws Exception {
        Path executable = executable();
        Path base = tempDir.resolve("base"); Path real = tempDir.resolve("real/child");
        Files.createDirectories(base); Files.createDirectories(real);
        Files.createSymbolicLink(base.resolve("link"), real);
        DefaultGameRepositoryLayout layout = new DefaultGameRepositoryLayout(base.resolve("link/.."));
        Library library = new Library("org.example", "core", "1");
        Path actual = tempDir.resolve("real/libraries/org/example/core/1/core-1.jar");
        createFile(actual);
        assertTrue(Files.isRegularFile(layout.getLibraryFile(ID, library)));
        assertNativeMatchesJava(executable, layout, List.of(library), false, false);
    }

    /// A custom library layout remains on Java and does not invoke the native process.
    @Test void customLayoutFallsBackWithoutStartingBackend() throws Exception {
        Path fake = tempDir.resolve("fake-backend");
        Files.writeString(fake, "#!/bin/sh\n: > \"$0.called\"\nexit 1\n");
        assertTrue(fake.toFile().setExecutable(true));
        System.setProperty("mfcl.native.path", fake.toString());
        System.clearProperty("mfcl.native.classpath.enabled");
        DefaultGameRepositoryLayout layout = new CustomLayout(tempDir);
        Library library = new Library("org.example", "core", "1");
        Path expected = layout.getLibraryFile(ID, library); createFile(expected);
        assertEquals(List.of(expected.toAbsolutePath().normalize().toString()),
                new ArrayList<>(DefaultLauncher.resolveClasspath(ID, layout, List.of(library), false, false)));
        assertFalse(Files.exists(Path.of(fake + ".called")));
        assertNull(DefaultLauncher.planNativeArchives(layout, ID, List.of(library)));
        assertFalse(Files.exists(Path.of(fake + ".called")));
    }

    /// Compares both the direct native API and launcher-selected path to Java's ordered result.
    ///
    /// @param executable native executable
    /// @param layout repository layout
    /// @param libraries library fixtures
    /// @param optifine OptiFine compatibility flag
    /// @param modLauncher ModLauncher flag
    /// @throws Exception if planning fails
    private void assertNativeMatchesJava(Path executable, DefaultGameRepositoryLayout layout, List<Library> libraries,
                                         boolean optifine, boolean modLauncher) throws Exception {
        System.setProperty("mfcl.native.path", executable.toString());
        System.setProperty("mfcl.native.classpath.enabled", "false");
        List<String> expected = new ArrayList<>(DefaultLauncher.resolveClasspath(ID, layout, libraries, optifine, modLauncher));
        assertEquals(expected, NativeBackend.planClasspath(executable, layout, ID, libraries, optifine, modLauncher));
        System.clearProperty("mfcl.native.classpath.enabled");
        assertEquals(expected, new ArrayList<>(DefaultLauncher.resolveClasspath(ID, layout, libraries, optifine, modLauncher)));
    }

    /// Creates one synthetic library file.
    ///
    /// @param file target fixture
    /// @throws IOException if creation fails
    private static void createFile(Path file) throws IOException {
        Files.createDirectories(file.getParent()); Files.writeString(file, "fixture");
    }

    /// Returns the explicitly supplied native test executable.
    ///
    /// @return native executable path
    private static Path executable() {
        @Nullable String path = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(path != null && !path.isBlank());
        return Path.of(path);
    }

    /// Repository layout with a deliberately custom library location.
    private static final class CustomLayout extends DefaultGameRepositoryLayout {
        /// Creates a layout rooted in a test directory.
        ///
        /// @param root fixture root
        private CustomLayout(Path root) { super(root); }
        /// Resolves libraries using the custom layout.
        @Override public Path getLibraryFile(GameInstanceID owner, Library library) {
            return getBaseDirectory().resolve("custom").resolve(library.artifact().getFileName());
        }
    }
}
