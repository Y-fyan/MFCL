/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.game;

import org.glavo.url.WebURL;
import org.jackhuang.hmcl.download.DefaultCacheRepository;
import org.jackhuang.hmcl.download.DefaultDependencyManager;
import org.jackhuang.hmcl.download.DefaultGameBuilder;
import org.jackhuang.hmcl.download.game.GameDownloadTask;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.UrlResponseInfo;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.http.HttpHeaders;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests exclusive repository drafts and their filesystem visibility boundary.
@NotNullByDefault
public final class DefaultGameRepositoryDraftTest {

    /// NeoForge and Cleanroom local installer entry points accept native profile projections.
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.LINUX, org.junit.jupiter.api.condition.OS.MAC})
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    public void loaderInstallEntryPointsUseNativeProfiles(@TempDir Path tempDirectory) throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        org.junit.jupiter.api.Assumptions.assumeTrue(executable != null && !executable.isBlank());
        Path wrapper = tempDirectory.resolve("backend");
        Files.writeString(wrapper, "#!/bin/sh\nIFS= read -r line\ncase \"$line\" in *read_forge_profiles*) : > \"$0.called\" ;; esac\n"
                + "printf '%s\\n' \"$line\" | exec '" + executable.replace("'", "'\\''") + "'\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        @Nullable String previous = System.getProperty("mfcl.native.path");
        try {
            System.setProperty("mfcl.native.path", wrapper.toString());
            TestRepository repo = new TestRepository(tempDirectory.resolve("game"));
            DefaultCacheRepository cache = new DefaultCacheRepository(tempDirectory.resolve("cache"));
            DefaultDependencyManager manager = new DefaultDependencyManager(repo, new TestDownloadProvider(cache), cache);
            GameInstanceManifest manifest = new GameInstanceManifest(new GameInstanceID("fixture"));
            for (String name : List.of("neoforge", "cleanroom")) {
                Path installer = tempDirectory.resolve(name + ".jar");
                try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(installer))) {
                    zip.putNextEntry(new java.util.zip.ZipEntry("install_profile.json"));
                    zip.write(("{\"profile\":\"" + name + "\",\"minecraft\":\"1.20.1\",\"json\":\"/version.json\",\"version\":\"" + name + "-1\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
                    zip.putNextEntry(new java.util.zip.ZipEntry("version.json")); zip.write("{\"id\":\"fixture\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
                }
                var parsed = org.jackhuang.hmcl.download.forge.NativeForgeProfiles.tryReadForInstall(installer);
                assertNotNull(parsed); assertEquals(name, parsed.profileName());
                Files.deleteIfExists(Path.of(wrapper + ".called"));
                if (name.equals("neoforge")) {
                    assertNotNull(org.jackhuang.hmcl.download.neoforge.NeoForgeInstallTask.install(manager, manifest, "1.20.1", installer));
                    assertThrows(org.jackhuang.hmcl.download.VersionMismatchException.class, () ->
                            org.jackhuang.hmcl.download.neoforge.NeoForgeInstallTask.install(manager, manifest, "1.19.4", installer));
                } else {
                    assertNotNull(org.jackhuang.hmcl.download.cleanroom.CleanroomInstallTask.install(manager, manifest, "1.20.1", installer));
                    assertThrows(org.jackhuang.hmcl.download.VersionMismatchException.class, () ->
                            org.jackhuang.hmcl.download.cleanroom.CleanroomInstallTask.install(manager, manifest, "1.19.4", installer));
                }
                assertTrue(Files.exists(Path.of(wrapper + ".called")));
            }
        } finally {
            if (previous == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previous);
        }
    }

    /// The legacy Forge installation task publishes its embedded JAR through the native staging path.
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.LINUX, org.junit.jupiter.api.condition.OS.MAC})
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    public void legacyForgeInstallerUsesNativeStaging(@TempDir Path tempDirectory) throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        org.junit.jupiter.api.Assumptions.assumeTrue(executable != null && !executable.isBlank());
        Path wrapper = tempDirectory.resolve("backend"), installer = tempDirectory.resolve("installer.jar");
        Files.writeString(wrapper, "#!/bin/sh\nIFS= read -r line\ncase \"$line\" in *stage_forge_data*) : > \"$0.staged\" ;; esac\n"
                + "printf '%s\\n' \"$line\" | exec '" + executable.replace("'", "'\\''") + "'\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(installer))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("install_profile.json"));
            zip.write("{\"install\":{\"minecraft\":\"1.12.2\",\"path\":\"g:forge:1\",\"filePath\":\"forge.jar\"},\"versionInfo\":{\"id\":\"forge\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("forge.jar")); zip.write("legacy-jar".getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
        }
        @Nullable String previous = System.getProperty("mfcl.native.path");
        try {
            System.setProperty("mfcl.native.path", wrapper.toString());
            TestRepository repo = new TestRepository(tempDirectory.resolve("game"));
            DefaultCacheRepository cache = new DefaultCacheRepository(tempDirectory.resolve("cache"));
            DefaultDependencyManager manager = new DefaultDependencyManager(repo, new TestDownloadProvider(cache), cache);
            GameInstanceManifest manifest = new GameInstanceManifest(new GameInstanceID("fixture"));
            var constructor = org.jackhuang.hmcl.download.forge.ForgeOldInstallTask.class.getDeclaredConstructor(
                    DefaultDependencyManager.class, GameInstanceManifest.class, String.class, Path.class);
            constructor.setAccessible(true);
            var task = constructor.newInstance(manager, manifest, "1", installer);
            org.jackhuang.hmcl.JavaFXLauncher.start();
            org.junit.jupiter.api.Assumptions.assumeTrue(org.jackhuang.hmcl.JavaFXLauncher.isStarted());
            task.execute();
            assertEquals("legacy-jar", Files.readString(repo.getLayout().getArtifactFile(Artifact.fromDescriptor("g:forge:1"))));
            assertTrue(Files.exists(Path.of(wrapper + ".staged")));
            assertNotNull(task.getResult());
        } finally {
            if (previous == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previous);
        }
    }

    /// Runs a test-owned Forge processor using the native main-class and classpath plan.
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.LINUX, org.junit.jupiter.api.condition.OS.MAC})
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    public void forgeProcessorUsesNativeCommandPreparation(@TempDir Path tempDirectory) throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        org.junit.jupiter.api.Assumptions.assumeTrue(executable != null && !executable.isBlank());
        @Nullable String previousPath = System.getProperty("mfcl.native.path");
        @Nullable String previousPool = System.getProperty("mfcl.native.pool.enabled");
        @Nullable String previousEnabled = System.getProperty("mfcl.native.processors.enabled");
        Path wrapper = tempDirectory.resolve("backend");
        String quoted = "'" + executable.replace("'", "'\\''") + "'";
        Files.writeString(wrapper, "#!/bin/sh\nif [ \"${1-}\" = \"--exec-child\" ] || [ \"${1-}\" = \"--exec-child-v2\" ]; then printf '%s\\n' run >> \"$0.exec\"; exec " + quoted + " \"$@\"; fi\n"
                + "IFS= read -r line\ncase \"$line\" in *processor_main_class*) : > \"$0.main\" ;; *processor_classpath*) : > \"$0.classpath\" ;; *processor_command*) : > \"$0.combined\" ;; esac\n"
                + "printf '%s\\n' \"$line\" | exec " + quoted + "\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        try {
            System.setProperty("mfcl.native.path", wrapper.toString()); System.setProperty("mfcl.native.pool.enabled", "false");
            System.clearProperty("mfcl.native.processors.enabled");
            TestRepository repository = new TestRepository(tempDirectory.resolve("game"));
            DefaultCacheRepository cache = new DefaultCacheRepository(tempDirectory.resolve("cache"));
            DefaultDependencyManager manager = new DefaultDependencyManager(repository, new TestDownloadProvider(cache), cache);
            var outer = new org.jackhuang.hmcl.download.forge.ForgeNewInstallTask(manager,
                    new GameInstanceManifest(new GameInstanceID("fixture")), tempDirectory.resolve("client.jar"), "test", tempDirectory.resolve("installer.jar"));
            Artifact processorArtifact = Artifact.fromDescriptor("fixture:processor:1"), dependency = Artifact.fromDescriptor("fixture:dependency:1");
            Path jar = repository.getLayout().getArtifactFile(processorArtifact), dependencyJar = repository.getLayout().getArtifactFile(dependency);
            Files.createDirectories(jar.getParent()); Files.createDirectories(dependencyJar.getParent());
            var manifest = new java.util.jar.Manifest(); manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MAIN_CLASS, NativeProcessorFixture.class.getName());
            try (var archive = new java.util.jar.JarOutputStream(Files.newOutputStream(jar), manifest);
                 @Nullable var input = NativeProcessorFixture.class.getResourceAsStream("NativeProcessorFixture.class")) {
                assertNotNull(input);
                archive.putNextEntry(new java.util.jar.JarEntry("org/jackhuang/hmcl/game/NativeProcessorFixture.class"));
                input.transferTo(archive); archive.closeEntry();
            }
            try (var archive = new java.util.zip.ZipOutputStream(Files.newOutputStream(dependencyJar))) { archive.finish(); }
            assertEquals(NativeProcessorFixture.class.getName(), org.jackhuang.hmcl.download.forge.NativeProcessorCommand.tryMainClass(jar, () -> false));
            assertEquals(String.join(java.io.File.pathSeparator, dependencyJar.toString(), jar.toString()),
                    org.jackhuang.hmcl.download.forge.NativeProcessorCommand.tryClasspath(jar, List.of(dependency), repository.getLayout(), () -> false));
            Files.deleteIfExists(Path.of(wrapper + ".main")); Files.deleteIfExists(Path.of(wrapper + ".classpath"));
            Path output = tempDirectory.resolve("processor-output");
            var processor = new org.jackhuang.hmcl.download.forge.ForgeNewInstallProfile.Processor(null, processorArtifact,
                    List.of(dependency), List.of("{OUT}", "'abc'"), Map.of("{OUT}", "'a9993e364706816aba3e25717850c26c9cd0d89d'"));
            Class<?> inner = java.util.Arrays.stream(outer.getClass().getDeclaredClasses())
                    .filter(type -> type.getSimpleName().equals("ProcessorTask")).findFirst().orElseThrow();
            var constructor = inner.getDeclaredConstructor(outer.getClass(), processor.getClass(), Map.class); constructor.setAccessible(true);
            @SuppressWarnings("unchecked") Task<Void> task = (Task<Void>) constructor.newInstance(outer, processor, Map.of("OUT", output.toString()));
            task.execute(); assertEquals("abc", Files.readString(output));
            assertTrue(Files.exists(Path.of(wrapper + ".combined")), "Rust did not prepare the combined processor command");
            assertFalse(Files.exists(Path.of(wrapper + ".main")), "Processor main class was queried separately");
            assertFalse(Files.exists(Path.of(wrapper + ".classpath")), "Processor classpath was queried separately");
            task.execute(); assertEquals(List.of("run"), Files.readAllLines(Path.of(wrapper + ".exec")));
            Files.delete(output); Files.delete(Path.of(wrapper + ".combined"));
            var neoConstructor = org.jackhuang.hmcl.download.neoforge.NeoForgeOldInstallTask.class.getDeclaredConstructor(
                    DefaultDependencyManager.class, GameInstanceManifest.class, Path.class, String.class, Path.class);
            neoConstructor.setAccessible(true);
            Object neo = neoConstructor.newInstance(manager, new GameInstanceManifest(new GameInstanceID("fixture")),
                    tempDirectory.resolve("client.jar"), "test", tempDirectory.resolve("installer.jar"));
            Class<?> neoInner = java.util.Arrays.stream(neo.getClass().getDeclaredClasses())
                    .filter(type -> type.getSimpleName().equals("ProcessorTask")).findFirst().orElseThrow();
            var neoTaskConstructor = neoInner.getDeclaredConstructor(neo.getClass(), processor.getClass(), Map.class);
            neoTaskConstructor.setAccessible(true);
            @SuppressWarnings("unchecked") Task<Void> neoTask = (Task<Void>) neoTaskConstructor.newInstance(neo, processor, Map.of("OUT", output.toString()));
            neoTask.execute(); assertEquals("abc", Files.readString(output));
            assertTrue(Files.exists(Path.of(wrapper + ".combined")), "NeoForge did not use the combined Rust command");
            assertEquals(List.of("run", "run"), Files.readAllLines(Path.of(wrapper + ".exec")));
        } finally {
            if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
            if (previousPool == null) System.clearProperty("mfcl.native.pool.enabled"); else System.setProperty("mfcl.native.pool.enabled", previousPool);
            if (previousEnabled == null) System.clearProperty("mfcl.native.processors.enabled"); else System.setProperty("mfcl.native.processors.enabled", previousEnabled);
        }
    }

    /// Forge and NeoForge installer tasks consume the native-read profile pair.
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.LINUX, org.junit.jupiter.api.condition.OS.MAC})
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    public void forgeInstallersReadNativeProfiles(@TempDir Path tempDirectory) throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        org.junit.jupiter.api.Assumptions.assumeTrue(executable != null && !executable.isBlank());
        Path wrapper = tempDirectory.resolve("profiles-backend");
        Files.writeString(wrapper, "#!/bin/sh\nread line\ncase \"$line\" in *read_forge_profiles*) : > \"$0.called\" ;; *stage_forge_entries*) : > \"$0.staged\" ;; *stage_forge_data*) : > \"$0.data\" ;; esac\n"
                + "printf '%s\\n' \"$line\" | exec '" + executable.replace("'", "'\\''") + "'\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        Path installer = tempDirectory.resolve("installer.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(installer))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("install_profile.json"));
            zip.write("{\"minecraft\":\"1.20.1\",\"json\":\"/version.json\",\"version\":\"fixture\",\"path\":\"g:main:1\",\"libraries\":[{\"name\":\"g:lib:1\"},{\"name\":\"g:missing:1\"}],\"processors\":[],\"data\":{\"PATCH\":{\"client\":\"/data/client.bin\"}}}".getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("version.json"));
            zip.write("{\"id\":\"fixture\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
            var libraryEntry = new java.util.zip.ZipEntry("maven/g/lib/1/lib-1.jar"); libraryEntry.setTime(1700000000000L);
            zip.putNextEntry(libraryEntry); zip.write("embedded-library".getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
            var mainEntry = new java.util.zip.ZipEntry("maven/g/main/1/main-1.jar"); mainEntry.setTime(1700000000000L);
            zip.putNextEntry(mainEntry); zip.write("embedded-main".getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("data/client.bin")); zip.write("patch-data".getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
        }
        @Nullable String previous = System.getProperty("mfcl.native.path");
        @Nullable String previousEmbedded = System.getProperty("mfcl.native.forge.embedded.enabled");
        @Nullable String previousPool = System.getProperty("mfcl.native.pool.enabled");
        try {
            System.setProperty("mfcl.native.path", wrapper.toString());
            System.setProperty("mfcl.native.pool.enabled", "false");
            var parsed = org.jackhuang.hmcl.download.forge.NativeForgeProfiles.tryRead(installer, () -> false); assertNotNull(parsed);
            Files.deleteIfExists(Path.of(wrapper + ".called"));
            TestRepository repo = new TestRepository(tempDirectory.resolve("game"));
            DefaultCacheRepository cache = new DefaultCacheRepository(tempDirectory.resolve("cache"));
            DefaultDependencyManager manager = new DefaultDependencyManager(repo, new TestDownloadProvider(cache), cache);
            GameInstanceManifest manifest = new GameInstanceManifest(new GameInstanceID("fixture"));
            Files.writeString(tempDirectory.resolve("client.jar"), "client-fixture");
            org.jackhuang.hmcl.JavaFXLauncher.start();
            org.junit.jupiter.api.Assumptions.assumeTrue(org.jackhuang.hmcl.JavaFXLauncher.isStarted());
            try (var fs = org.jackhuang.hmcl.util.io.CompressingUtils.createReadOnlyZipFileSystem(installer)) {
                assertTrue(org.jackhuang.hmcl.download.forge.NativeForgeProfiles.tryCopyEmbedded(installer, fs, parsed.profile(), manifest.id(), repo.getLayout(), () -> false));
            }
            assertTrue(Files.exists(Path.of(wrapper + ".staged")));
            Path stagedLibrary = repo.getLayout().getArtifactFile(Artifact.fromDescriptor("g:lib:1"));
            Path stagedMain = repo.getLayout().getArtifactFile(Artifact.fromDescriptor("g:main:1"));
            assertEquals("embedded-library", Files.readString(stagedLibrary)); assertEquals("embedded-main", Files.readString(stagedMain));
            TestRepository javaRepo = new TestRepository(tempDirectory.resolve("java-game"));
            DefaultDependencyManager javaManager = new DefaultDependencyManager(javaRepo, new TestDownloadProvider(cache), cache);
            System.setProperty("mfcl.native.forge.embedded.enabled", "false");
            new org.jackhuang.hmcl.download.forge.ForgeNewInstallTask(javaManager, manifest, tempDirectory.resolve("client.jar"), "fixture", installer).preExecute();
            Path javaLibrary = javaRepo.getLayout().getArtifactFile(Artifact.fromDescriptor("g:lib:1"));
            assertEquals(Files.getLastModifiedTime(javaLibrary), Files.getLastModifiedTime(stagedLibrary));
            assertEquals(Files.getPosixFilePermissions(javaLibrary), Files.getPosixFilePermissions(stagedLibrary));
            System.clearProperty("mfcl.native.forge.embedded.enabled");
            var forge = new org.jackhuang.hmcl.download.forge.ForgeNewInstallTask(manager, manifest, tempDirectory.resolve("client.jar"), "fixture", installer);
            forge.preExecute(); assertTrue(Files.exists(Path.of(wrapper + ".called")));
            forge.execute(); assertTrue(Files.exists(Path.of(wrapper + ".data")), "Forge did not request native data staging"); forge.postExecute();
            Files.delete(Path.of(wrapper + ".data"));
            Files.delete(Path.of(wrapper + ".called"));
            var ctor = org.jackhuang.hmcl.download.neoforge.NeoForgeOldInstallTask.class.getDeclaredConstructor(
                    DefaultDependencyManager.class, GameInstanceManifest.class, Path.class, String.class, Path.class);
            ctor.setAccessible(true);
            var neo = (org.jackhuang.hmcl.download.neoforge.NeoForgeOldInstallTask) ctor.newInstance(manager, manifest, tempDirectory.resolve("client.jar"), "fixture", installer);
            neo.preExecute(); assertTrue(Files.exists(Path.of(wrapper + ".called")));
            neo.execute(); assertTrue(Files.exists(Path.of(wrapper + ".data")), "NeoForge did not request native data staging"); neo.postExecute();
            Path legacy = tempDirectory.resolve("legacy-installer.jar");
            try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(legacy))) {
                zip.putNextEntry(new java.util.zip.ZipEntry("install_profile.json"));
                zip.write("{\"minecraft\":\"1.20.1\",\"json\":\"/unused.json\",\"json\":\"/version.json\",\"version\":\"fixture\",\"libraries\":[],\"processors\":[],\"data\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
                zip.putNextEntry(new java.util.zip.ZipEntry("version.json")); zip.write("{\"id\":\"fixture\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
            }
            assertNull(org.jackhuang.hmcl.download.forge.NativeForgeProfiles.tryRead(legacy, () -> false));
            new org.jackhuang.hmcl.download.forge.ForgeNewInstallTask(manager, manifest, tempDirectory.resolve("client.jar"), "fixture", legacy).preExecute();
        } finally {
            if (previous == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previous);
            if (previousEmbedded == null) System.clearProperty("mfcl.native.forge.embedded.enabled"); else System.setProperty("mfcl.native.forge.embedded.enabled", previousEmbedded);
            if (previousPool == null) System.clearProperty("mfcl.native.pool.enabled"); else System.setProperty("mfcl.native.pool.enabled", previousPool);
        }
    }

    /// Native processor option planning preserves both installer implementations' Java behavior.
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.LINUX, org.junit.jupiter.api.condition.OS.MAC})
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    public void nativeProcessorOptionsMatchForgeAndNeoForge(@TempDir Path tempDirectory) throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        org.junit.jupiter.api.Assumptions.assumeTrue(executable != null && !executable.isBlank());
        @Nullable String previousPath = System.getProperty("mfcl.native.path");
        @Nullable String previousEnabled = System.getProperty("mfcl.native.processors.enabled");
        @Nullable String previousPool = System.getProperty("mfcl.native.pool.enabled");
        try {
            Path wrapper = tempDirectory.resolve("option-backend");
            Files.writeString(wrapper, "#!/bin/sh\nread line\nprintf '%s\\n' \"$line\" >> \"$0.requests\"\n"
                    + "if [ -f \"$0.legacy\" ]; then case \"$line\" in *plan_processor_options_v2*) printf '%s\\n' '{\"version\":1,\"id\":1,\"error\":{\"code\":\"unknown_method\"}}'; exit 0 ;; esac; fi\n"
                    + "printf '%s\\n' \"$line\" | exec '"
                    + executable.replace("'", "'\\''") + "'\n");
            assertTrue(wrapper.toFile().setExecutable(true));
            System.setProperty("mfcl.native.path", wrapper.toString()); System.setProperty("mfcl.native.pool.enabled", "false");
            System.clearProperty("mfcl.native.processors.enabled");
            TestRepository repository = new TestRepository(tempDirectory.resolve("game with space"));
            DefaultCacheRepository cache = new DefaultCacheRepository(tempDirectory.resolve("cache"));
            DefaultDependencyManager manager = new DefaultDependencyManager(repository, new TestDownloadProvider(cache), cache);
            GameInstanceManifest manifest = new GameInstanceManifest(new GameInstanceID("fixture"));
            Path client = tempDirectory.resolve("client.jar"), installer = tempDirectory.resolve("installer.jar");
            var forge = new org.jackhuang.hmcl.download.forge.ForgeNewInstallTask(manager, manifest, client, "test", installer);
            var neoConstructor = org.jackhuang.hmcl.download.neoforge.NeoForgeOldInstallTask.class.getDeclaredConstructor(
                    DefaultDependencyManager.class, GameInstanceManifest.class, Path.class, String.class, Path.class);
            neoConstructor.setAccessible(true);
            Object neo = neoConstructor.newInstance(manager, manifest, client, "test", installer);
            Map<String, @Nullable String> vars = new java.util.HashMap<>(); vars.put("SIDE", "client"); vars.put("N", null);
            vars.put("OUT", tempDirectory.resolve("mappings.txt").toString());
            @Unmodifiable List<@Unmodifiable List<String>> groups = List.of(
                    List.of("ignored-{missing}", "--task", "'DOWNLOAD_MOJMAPS'", "--side", "{SIDE}", "--version", "1.20.2", "--output", "{OUT}", "--flag", "--dup", "first", "--dup", "second",
                            "--missing", "{missing}", "--path", "[g:a:1]", "--nullText", "x{N}", "--escaped", "hello-'世界'", "--trailing"),
                    List.of("--ignored", "--", "--tail"), List.of());
            List<org.jackhuang.hmcl.download.forge.ForgeNewInstallProfile.Processor> processors = new java.util.ArrayList<>();
            for (List<String> args : groups) processors.add(new org.jackhuang.hmcl.download.forge.ForgeNewInstallProfile.Processor(
                    null, Artifact.fromDescriptor("g:processor:1"), List.of(), args, Map.of()));
            @Nullable @Unmodifiable List<@Unmodifiable Map<String, @Nullable String>> planned = org.jackhuang.hmcl.download.forge.NativeProcessorOptions.tryPlan(
                    processors, vars, repository.getLayout(), () -> false);
            assertNotNull(planned); assertEquals(groups.size(), planned.size());
            String requests = Files.readString(Path.of(wrapper + ".requests"));
            assertTrue(requests.contains("\"plan_processor_options_v2\""));
            assertFalse(requests.contains("\"artifacts\""));
            Files.createFile(Path.of(wrapper + ".legacy"));
            assertEquals(planned, org.jackhuang.hmcl.download.forge.NativeProcessorOptions.tryPlan(
                    processors, vars, repository.getLayout(), () -> false));
            assertTrue(Files.readString(Path.of(wrapper + ".requests")).contains("\"plan_processor_options\""));
            for (Object task : List.of(forge, neo)) {
                var method = task.getClass().getDeclaredMethod("parseOptions", List.class, Map.class); method.setAccessible(true);
                for (int i = 0; i < groups.size(); i++) {
                    @SuppressWarnings("unchecked") Map<String, @Nullable String> expected = (Map<String, @Nullable String>) method.invoke(task, groups.get(i), vars);
                    assertEquals(expected, planned.get(i));
                    assertEquals(List.copyOf(expected.keySet()), List.copyOf(planned.get(i).keySet()));
                }
                var patch = task.getClass().getDeclaredMethod("patchDownloadMojangMappingsTask",
                        org.jackhuang.hmcl.download.forge.ForgeNewInstallProfile.Processor.class, Map.class, Map.class);
                patch.setAccessible(true);
                @Nullable Object javaTask = patch.invoke(task, processors.get(0), vars, null);
                @Nullable Object nativeTask = patch.invoke(task, processors.get(0), vars, planned.get(0));
                assertNotNull(javaTask); assertNotNull(nativeTask); assertEquals(javaTask.getClass(), nativeTask.getClass());
            }
            var invalid = new org.jackhuang.hmcl.download.forge.ForgeNewInstallProfile.Processor(null,
                    Artifact.fromDescriptor("g:processor:1"), List.of(), List.of("--bad", "x{missing}"), Map.of());
            assertNull(org.jackhuang.hmcl.download.forge.NativeProcessorOptions.tryPlan(List.of(invalid), vars, repository.getLayout(), () -> false));
            assertThrows(InterruptedException.class, () -> org.jackhuang.hmcl.download.forge.NativeProcessorOptions.tryPlan(processors, vars, repository.getLayout(), () -> true));
        } finally {
            if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
            if (previousEnabled == null) System.clearProperty("mfcl.native.processors.enabled"); else System.setProperty("mfcl.native.processors.enabled", previousEnabled);
            if (previousPool == null) System.clearProperty("mfcl.native.pool.enabled"); else System.setProperty("mfcl.native.pool.enabled", previousPool);
        }
    }

    /// Processor output hashes skip completed work and preserve mismatch cleanup.
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.LINUX, org.junit.jupiter.api.condition.OS.MAC})
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    public void forgeProcessorUsesNativeOutputHash(@TempDir Path tempDirectory) throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        org.junit.jupiter.api.Assumptions.assumeTrue(executable != null && !executable.isBlank());
        @Nullable String previousPath = System.getProperty("mfcl.native.path");
        @Nullable String previousPool = System.getProperty("mfcl.native.pool.enabled");
        @Nullable String previousEnabled = System.getProperty("mfcl.native.processors.enabled");
        Path wrapper = tempDirectory.resolve("backend");
        Files.writeString(wrapper, "#!/bin/sh\nIFS= read -r line\ncase \"$line\" in *check_files*) : > \"$0.hash\" ;; esac\n"
                + "printf '%s\\n' \"$line\" | exec '" + executable.replace("'", "'\\''") + "'\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        try {
            System.setProperty("mfcl.native.path", wrapper.toString());
            System.setProperty("mfcl.native.pool.enabled", "false");
            System.clearProperty("mfcl.native.processors.enabled");
            TestRepository repository = new TestRepository(tempDirectory.resolve("game"));
            DefaultCacheRepository cache = new DefaultCacheRepository(tempDirectory.resolve("cache"));
            DefaultDependencyManager manager = new DefaultDependencyManager(repository, new TestDownloadProvider(cache), cache);
            var outer = new org.jackhuang.hmcl.download.forge.ForgeNewInstallTask(manager,
                    new GameInstanceManifest(new GameInstanceID("fixture")), tempDirectory.resolve("client.jar"), "test", tempDirectory.resolve("installer.jar"));
            Path output = tempDirectory.resolve("processor-output"); Files.writeString(output, "abc");
            var processor = new org.jackhuang.hmcl.download.forge.ForgeNewInstallProfile.Processor(null,
                    Artifact.fromDescriptor("fixture:missing:1"), List.of(), List.of("{missing}"),
                    Map.of("{OUT}", "'a9993e364706816aba3e25717850c26c9cd0d89d'"));
            Class<?> inner = java.util.Arrays.stream(outer.getClass().getDeclaredClasses())
                    .filter(type -> type.getSimpleName().equals("ProcessorTask")).findFirst().orElseThrow();
            var constructor = inner.getDeclaredConstructor(outer.getClass(), processor.getClass(), Map.class);
            constructor.setAccessible(true);
            @SuppressWarnings("unchecked") Task<Void> task = (Task<Void>) constructor.newInstance(outer, processor, Map.of("OUT", output.toString()));
            task.execute();
            assertTrue(Files.exists(Path.of(wrapper + ".hash")), "Processor output was not checked by Rust");
            Files.writeString(output, "corrupt output");
            assertThrows(java.io.FileNotFoundException.class, task::execute);
            assertFalse(Files.exists(output), "Corrupt output was not removed before attempting the processor");
        } finally {
            if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
            if (previousPool == null) System.clearProperty("mfcl.native.pool.enabled"); else System.setProperty("mfcl.native.pool.enabled", previousPool);
            if (previousEnabled == null) System.clearProperty("mfcl.native.processors.enabled"); else System.setProperty("mfcl.native.processors.enabled", previousEnabled);
        }
    }

    /// Opt-in integration check against official Minecraft metadata in an isolated temporary root.
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "MFCL_LIVE_GAME_INSTALL", matches = "1")
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.LINUX, org.junit.jupiter.api.condition.OS.MAC})
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    @org.junit.jupiter.api.Timeout(value = 30, unit = java.util.concurrent.TimeUnit.MINUTES)
    public void liveVanillaInstallUsesNativeDownloads(@TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path tempDirectory) throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        org.junit.jupiter.api.Assumptions.assumeTrue(executable != null && !executable.isBlank());
        @Nullable String previousPath = System.getProperty("mfcl.native.path");
        @Nullable String previousPrism = System.getProperty("prism.order");
        @Nullable String previousInstances = System.getProperty("mfcl.native.instances.enabled");
        org.jackhuang.hmcl.util.CacheRepository previousCache = org.jackhuang.hmcl.util.CacheRepository.getInstance();
        int previousConcurrency = org.jackhuang.hmcl.task.FetchTask.getDownloadExecutorConcurrency();
        @Nullable com.sun.net.httpserver.HttpServer fixtureServer = null;
        Path wrapper = tempDirectory.resolve("backend");
        Files.writeString(wrapper, """
                #!/usr/bin/env python3
                import json, os, pathlib, subprocess, sys
                native = os.environ['MFCL_NATIVE_TEST_EXECUTABLE']
                if sys.argv[1:] in (['--exec-child'], ['--exec-child-v2']):
                    pathlib.Path(__file__ + '.launched').touch()
                    os.execv(native, [native] + sys.argv[1:])
                child = subprocess.Popen([native], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
                try:
                    for request_line in sys.stdin:
                        request = json.loads(request_line)
                        child.stdin.write(request_line)
                        child.stdin.flush()
                        while True:
                            line = child.stdout.readline()
                            if not line:
                                sys.exit(1)
                            frame = json.loads(line)
                            if request.get('method') == 'download' and 'result' in frame:
                                with open(__file__ + '.downloads', 'a') as marker:
                                    marker.write(str(os.getpid()) + '\\n')
                                if frame['result'].get('parallel'):
                                    with open(__file__ + '.parallel', 'a') as marker:
                                        marker.write('success\\n')
                                if 'resumed_chunks' in frame['result']:
                                    with open(__file__ + '.resume', 'a') as marker:
                                        marker.write('success\\n')
                                if request.get('params', {}).get('metadata'):
                                    with open(__file__ + '.metadata', 'a') as marker:
                                        marker.write('success\\n')
                            if request.get('method') == 'merge_manifest' and 'result' in frame:
                                with open(__file__ + '.merges', 'a') as marker:
                                    marker.write('success\\n')
                            if request.get('method') == 'apply_manifest_patches' and 'result' in frame:
                                with open(__file__ + '.patches', 'a') as marker:
                                    marker.write('success\\n')
                            if request.get('method') == 'plan_loader' and 'result' in frame:
                                with open(__file__ + '.loader', 'a') as marker:
                                    marker.write('success\\n')
                            if request.get('method') == 'project_game_manifest' and 'result' in frame:
                                pathlib.Path(__file__ + '.game-manifest').touch()
                            if request.get('method') == 'plan_asset_install' and 'result' in frame:
                                pathlib.Path(__file__ + '.asset-install').touch()
                            if request.get('method') == 'expand_processor_literals' and 'result' in frame:
                                with open(__file__ + '.processor', 'a') as marker:
                                    marker.write('success\\n')
                                if request.get('params', {}).get('variables') == {}:
                                    pathlib.Path(__file__ + '.processor-data').touch()
                            if request.get('method') == 'plan_processor_options' and 'result' in frame:
                                pathlib.Path(__file__ + '.processor-options').touch()
                            if request.get('method') == 'select_library_rules' and 'result' in frame:
                                with open(__file__ + '.library-rules', 'a') as marker:
                                    marker.write('success\\n')
                            if request.get('method') == 'expand_ruled_arguments' and 'result' in frame:
                                pathlib.Path(__file__ + '.launch-rules').touch()
                            if request.get('method') == 'assemble_launch_sections' and 'result' in frame:
                                pathlib.Path(__file__ + '.launch-assembly').touch()
                            if request.get('method') == 'plan_jvm_initial' and 'result' in frame:
                                pathlib.Path(__file__ + '.jvm-initial').touch()
                            if request.get('method') == 'plan_jvm_platform' and 'result' in frame:
                                pathlib.Path(__file__ + '.jvm-platform').touch()
                            sys.stdout.write(line)
                            sys.stdout.flush()
                            if 'result' in frame or 'error' in frame:
                                break
                finally:
                    child.stdin.close()
                    try:
                        child.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        child.kill()
                        child.wait()
                """);
        assertTrue(wrapper.toFile().setExecutable(true));
        try {
            System.setProperty("prism.order", "sw");
            org.jackhuang.hmcl.JavaFXLauncher.start();
            assertTrue(org.jackhuang.hmcl.JavaFXLauncher.isStarted(), "JavaFX toolkit did not start");
            org.jackhuang.hmcl.task.FetchTask.setDownloadExecutorConcurrency(16);
            System.setProperty("mfcl.native.path", wrapper.toString());
            Path gameDirectory = tempDirectory.resolve("game");
            TestRepository repository = new TestRepository(gameDirectory);
            // Missing assets may still be fetched directly from their official URLs.
            @Nullable String offlineSeed = System.getenv("MFCL_SEEDED_INSTALL_FIXTURE");
            @Nullable String offlineManifestUrl = null;
            if (offlineSeed != null && !offlineSeed.isBlank()) {
                Path source = Path.of(offlineSeed);
                org.jackhuang.hmcl.util.io.FileUtils.copyDirectory(source.resolve("game/assets"), gameDirectory.resolve("assets"));
                org.jackhuang.hmcl.util.io.FileUtils.copyDirectory(source.resolve("game/libraries"), gameDirectory.resolve("libraries"));
                org.jackhuang.hmcl.util.io.FileUtils.copyDirectory(source.resolve("cache/cache/SHA-1"), tempDirectory.resolve("cache/cache/SHA-1"));
                org.jackhuang.hmcl.util.io.FileUtils.copyDirectory(source.resolve("cache/libraries"), tempDirectory.resolve("cache/libraries"));
                fixtureServer = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
                String base = "http://127.0.0.1:" + fixtureServer.getAddress().getPort();
                com.google.gson.JsonObject fixture = com.google.gson.JsonParser.parseString(Files.readString(Path.of(
                        java.util.Objects.requireNonNull(System.getenv("MFCL_SEEDED_MANIFEST"))))).getAsJsonObject();
                String indexId = fixture.getAsJsonObject("assetIndex").get("id").getAsString();
                byte[] index = Files.readAllBytes(source.resolve("game/assets/indexes").resolve(indexId + ".json"));
                fixture.getAsJsonObject("assetIndex").addProperty("url", base + "/index");
                byte[] metadata = fixture.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                fixtureServer.createContext("/manifest", exchange -> {
                    exchange.sendResponseHeaders(200, metadata.length); try (var output = exchange.getResponseBody()) { output.write(metadata); }
                });
                fixtureServer.createContext("/index", exchange -> {
                    exchange.sendResponseHeaders(200, index.length); try (var output = exchange.getResponseBody()) { output.write(index); }
                });
                fixtureServer.start();
                offlineManifestUrl = base + "/manifest";
            }
            @Nullable String seed = System.getenv("MFCL_LIVE_CACHE_SEED");
            if (seed != null && !seed.isBlank()) {
                Path sha1Cache = Path.of(seed).resolve("cache/SHA-1");
                if (Files.isDirectory(sha1Cache)) {
                    org.jackhuang.hmcl.util.io.FileUtils.copyDirectory(sha1Cache,
                            tempDirectory.resolve("cache/cache/SHA-1"));
                }
                Path libraries = Path.of(seed).resolve("libraries");
                if (Files.isDirectory(libraries)) {
                    org.jackhuang.hmcl.util.io.FileUtils.copyDirectory(libraries, tempDirectory.resolve("cache/libraries"));
                }
            }
            if ("1".equals(System.getenv("MFCL_LIVE_FORCE_RANGE"))) {
                Files.deleteIfExists(tempDirectory.resolve("cache/cache/SHA-1/95/9527e6b0d2449408958fd1302594dc65ec5ade9c"));
                Files.deleteIfExists(tempDirectory.resolve("cache/libraries/net/fabricmc/sponge-mixin/0.13.3+mixin.0.8.5/sponge-mixin-0.13.3+mixin.0.8.5.jar"));
            }
            DefaultCacheRepository cache = new DefaultCacheRepository(tempDirectory.resolve("cache"));
            org.jackhuang.hmcl.util.CacheRepository.setInstance(cache);
            DefaultDependencyManager manager = new DefaultDependencyManager(repository, new TestDownloadProvider(cache), cache);
            GameInstanceID id = new GameInstanceID("native-live-test");
            String gameVersion = System.getenv().getOrDefault("MFCL_LIVE_GAME_VERSION", "1.20.1");
            try (DefaultGameBuilder builder = manager.newGameBuilder(id)) {
                builder.enableIsolation();
                if (offlineManifestUrl == null) builder.component(GameComponentType.GAME, gameVersion);
                else builder.component(new org.jackhuang.hmcl.download.game.GameRemoteVersion(
                        org.jackhuang.hmcl.util.versioning.GameVersionNumber.asGameVersion(gameVersion), List.of(offlineManifestUrl),
                        ReleaseType.RELEASE, java.time.Instant.EPOCH));
                @Nullable String fabricVersion = System.getenv("MFCL_LIVE_FABRIC_VERSION");
                @Nullable String quiltVersion = System.getenv("MFCL_LIVE_QUILT_VERSION");
                @Nullable String forgeVersion = System.getenv("MFCL_LIVE_FORGE_VERSION");
                @Nullable String neoForgeVersion = System.getenv("MFCL_LIVE_NEOFORGE_VERSION");
                if (java.util.stream.Stream.of(fabricVersion, quiltVersion, forgeVersion, neoForgeVersion)
                        .filter(value -> value != null && !value.isBlank()).count() > 1)
                    throw new IllegalArgumentException("Select only one live loader fixture");
                if (fabricVersion != null && !fabricVersion.isBlank()) {
                    if ("1".equals(System.getenv("MFCL_LIVE_FABRIC_METADATA_FIXTURE"))) {
                        if (!gameVersion.equals("1.20.1")) throw new IllegalArgumentException("Fabric fixture requires 1.20.1");
                        Path metadata = tempDirectory.resolve("fabric-metadata.json");
                        try (var input = DefaultGameRepositoryDraftTest.class.getResourceAsStream("/loader-metadata/fabric-1.20.1-0.15.11.json")) {
                            assertNotNull(input); Files.copy(input, metadata);
                        }
                        builder.component(new org.jackhuang.hmcl.download.fabric.FabricRemoteVersion(
                                org.jackhuang.hmcl.util.versioning.GameVersionNumber.asGameVersion(gameVersion), fabricVersion,
                                List.of(metadata.toUri().toString())));
                    } else builder.component(GameComponentType.FABRIC, fabricVersion);
                }
                if (quiltVersion != null && !quiltVersion.isBlank()) builder.component(GameComponentType.QUILT, quiltVersion);
                if (forgeVersion != null && !forgeVersion.isBlank()) {
                    builder.component(new org.jackhuang.hmcl.download.forge.ForgeRemoteVersion(
                            org.jackhuang.hmcl.util.versioning.GameVersionNumber.asGameVersion(gameVersion), forgeVersion, null,
                            List.of("https://maven.minecraftforge.net/net/minecraftforge/forge/" + gameVersion + "-" + forgeVersion
                                    + "/forge-" + gameVersion + "-" + forgeVersion + "-installer.jar")));
                }
                if (neoForgeVersion != null && !neoForgeVersion.isBlank()) {
                    boolean old = gameVersion.equals("1.20.1");
                    String fullVersion = old ? gameVersion + "-" + neoForgeVersion : neoForgeVersion;
                    String artifact = old ? "forge" : "neoforge";
                    builder.component(new org.jackhuang.hmcl.download.neoforge.NeoForgeRemoteVersion(
                            org.jackhuang.hmcl.util.versioning.GameVersionNumber.asGameVersion(gameVersion), neoForgeVersion, fullVersion,
                            List.of("https://maven.neoforged.net/releases/net/neoforged/" + artifact + "/" + fullVersion
                                    + "/" + artifact + "-" + fullVersion + "-installer.jar")));
                }
                org.jackhuang.hmcl.task.Task<?> task = builder.buildAsync();
                org.jackhuang.hmcl.task.TaskExecutor executor = task.executor();
                try {
                    assertTrue(executor.test(), () -> "Installation failed: " + task.getException());
                } finally {
                    if (Thread.currentThread().isInterrupted()) executor.cancel();
                }
            }
            assertTrue(Files.exists(Path.of(wrapper + ".game-manifest")), "Installation did not use native game manifest projection");
            assertTrue(repository.hasInstance(id));
            assertTrue(Files.isRegularFile(repository.getLayout().getInstanceJarFile(id)));
            DefaultGameInstance installed = repository.getInstance(id);
            @Nullable String installedFabric = System.getenv("MFCL_LIVE_FABRIC_VERSION");
            if (installedFabric != null && !installedFabric.isBlank()) {
                Library mixin = installed.getResolvedManifest().getLibraries().stream()
                        .filter(lib -> lib.is("net.fabricmc", "sponge-mixin")).findFirst()
                        .orElseThrow(() -> new AssertionError("Fabric Mixin library is absent from the resolved manifest"));
                Path mixinFile = repository.getLayout().getLibraryFile(id, mixin);
                assertTrue(Files.isRegularFile(mixinFile), () -> "Fabric Mixin library is missing: " + mixinFile);
                try (var archive = new java.util.zip.ZipFile(mixinFile.toFile())) {
                    assertNotNull(archive.getEntry("org/spongepowered/asm/launch/MixinBootstrap.class"),
                            "Downloaded Mixin JAR does not contain its bootstrap class");
                }
                assertTrue(org.jackhuang.hmcl.nativebridge.NativeBackend.planClasspath(wrapper, repository.getLayout(), id,
                                installed.getResolvedManifest().getLibraries(), false, false)
                        .contains(mixinFile.toAbsolutePath().normalize().toString()), "Fabric Mixin JAR is absent from native classpath");
            }
            AssetIndex assets = installed.getAssetIndex(installed.getResolvedManifest().getAssetIndex().getId());
            assertFalse(assets.getObjects().isEmpty());
            for (AssetObject object : assets.getObjects().values()) {
                Path assetFile = repository.getLayout().getAssetObject(object);
                assertTrue(Files.isRegularFile(assetFile) && object.validateChecksum(assetFile, false),
                        () -> "Missing or corrupt asset: " + object.hash());
            }
            Path nativeDownloads = Path.of(wrapper + ".downloads");
            assertTrue(Files.exists(nativeDownloads), "No verified download completed through Rust");
            List<String> downloadWorkers = Files.readAllLines(nativeDownloads);
            if (offlineSeed == null || offlineSeed.isBlank()) {
                assertTrue(downloadWorkers.size() > 1);
                assertTrue(new java.util.HashSet<>(downloadWorkers).size() < downloadWorkers.size(), "Rust download process was not reused");
            }
            assertTrue(Files.exists(Path.of(wrapper + ".metadata")), "No metadata request completed through Rust");
            assertTrue(Files.exists(Path.of(wrapper + ".asset-install")), "No combined asset installation plan completed through Rust");
            GameInstanceManifest inherited = new GameInstanceManifest(new GameInstanceID("inherited-fixture"))
                    .withInheritsFrom(id).withMainClass("fixture.Main");
            System.setProperty("mfcl.native.instances.enabled", "false");
            GameInstanceManifest javaResolved = repository.getSnapshot().resolve(inherited);
            GameInstanceManifest installedManifest = installed.getManifest();
            assertTrue(installedManifest.isRoot()); assertFalse(installedManifest.getPatches().isEmpty());
            GameInstanceManifest base = new GameInstanceManifest(installedManifest.id())
                    .withPatches(installedManifest.patches())
                    .withJar(installedManifest.jar() == null ? installedManifest.id() : installedManifest.jar());
            assertEquals(repository.getSnapshot().resolve(installedManifest), NativeManifestMerge.applyPatches(wrapper, installedManifest, base));
            GameInstanceManifest parentResolved = installed.getResolvedManifest();
            assertEquals(inherited.mergeJava(parentResolved), NativeManifestMerge.merge(wrapper, inherited, parentResolved));
            System.clearProperty("mfcl.native.instances.enabled");
            assertEquals(javaResolved, repository.getSnapshot().resolve(inherited));
            assertTrue(Files.exists(Path.of(wrapper + ".merges")), "No real manifest merge completed through Rust");
            assertTrue(Files.exists(Path.of(wrapper + ".patches")), "No real patch application completed through Rust");
            @Nullable String fabricVersion = System.getenv("MFCL_LIVE_FABRIC_VERSION");
            @Nullable String quiltVersion = System.getenv("MFCL_LIVE_QUILT_VERSION");
            if ((fabricVersion != null && !fabricVersion.isBlank()) || (quiltVersion != null && !quiltVersion.isBlank()))
                assertTrue(Files.exists(Path.of(wrapper + ".loader")), "No loader metadata plan completed through Rust");
            @Nullable String forgeVersion = System.getenv("MFCL_LIVE_FORGE_VERSION");
            @Nullable String neoForgeVersion = System.getenv("MFCL_LIVE_NEOFORGE_VERSION");
            if ((forgeVersion != null && !forgeVersion.isBlank()) || (neoForgeVersion != null && !neoForgeVersion.isBlank())) {
                assertTrue(Files.exists(Path.of(wrapper + ".processor")), "No loader processor parameters were expanded through Rust");
                assertTrue(Files.exists(Path.of(wrapper + ".processor-data")), "No loader profile data was expanded through Rust");
                assertTrue(Files.exists(Path.of(wrapper + ".processor-options")), "No loader processor options were planned through Rust");
                assertTrue(Files.exists(Path.of(wrapper + ".launched")), "No loader processor was started through Rust");
            }
            if ("1".equals(System.getenv("MFCL_LIVE_FORCE_RANGE")))
                assertTrue(Files.exists(Path.of(wrapper + ".parallel")), "No verified file completed using parallel Rust ranges");
            if ("1".equals(System.getenv("MFCL_LIVE_FORCE_RANGE")))
                assertTrue(Files.exists(Path.of(wrapper + ".resume")), "No Rust download used the restart-safe checkpoint path");
            assertTrue(Files.exists(Path.of(wrapper + ".library-rules")), "Installation did not use native library rule selection");
            if ("1".equals(System.getenv("MFCL_LIVE_GAME_LAUNCH"))) {
                int installRuleRequests = Files.readAllLines(Path.of(wrapper + ".library-rules")).size();
                launchLiveFixture(installed, wrapper);
                assertTrue(Files.readAllLines(Path.of(wrapper + ".library-rules")).size() > installRuleRequests,
                        "Launch did not use native classpath library selection");
                assertTrue(Files.exists(Path.of(wrapper + ".launch-rules")), "Launch did not use native argument rules");
                assertTrue(Files.exists(Path.of(wrapper + ".launch-assembly")), "Launch did not use native command assembly");
                assertTrue(Files.exists(Path.of(wrapper + ".jvm-initial")), "No initial JVM plan completed through Rust");
                assertTrue(Files.exists(Path.of(wrapper + ".jvm-platform")), "No platform JVM plan completed through Rust");
            }
        } finally {
            if (fixtureServer != null) fixtureServer.stop(0);
            org.jackhuang.hmcl.task.FetchTask.setDownloadExecutorConcurrency(previousConcurrency);
            org.jackhuang.hmcl.util.CacheRepository.setInstance(previousCache);
            if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
            if (previousPrism == null) System.clearProperty("prism.order"); else System.setProperty("prism.order", previousPrism);
            if (previousInstances == null) System.clearProperty("mfcl.native.instances.enabled"); else System.setProperty("mfcl.native.instances.enabled", previousInstances);
        }
    }

    private static String readFixtureIfExists(Path path) {
        try { return Files.exists(path) ? Files.readString(path) : "absent"; }
        catch (java.io.IOException e) { return e.toString(); }
    }

    /// Installs a complete small game fixture using only local services and native transfers.
    @Test
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    public void completeLocalInstallationUsesNativeBatch(@TempDir Path tempDirectory) throws Exception {
        @Nullable String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        org.junit.jupiter.api.Assumptions.assumeTrue(binary != null && !binary.isBlank());
        org.jackhuang.hmcl.JavaFXLauncher.start();
        org.junit.jupiter.api.Assumptions.assumeTrue(org.jackhuang.hmcl.JavaFXLauncher.isStarted());
        byte[] client = "client-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] asset = "asset-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var zipBytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(zipBytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("fixture.txt")); zip.write(asset); zip.closeEntry();
        }
        byte[] library = zipBytes.toByteArray();
        String clientHash = org.jackhuang.hmcl.util.DigestUtils.digestToString("SHA-1", client);
        String assetHash = org.jackhuang.hmcl.util.DigestUtils.digestToString("SHA-1", asset);
        String libraryHash = org.jackhuang.hmcl.util.DigestUtils.digestToString("SHA-1", library);
        var responses = new java.util.concurrent.ConcurrentHashMap<String, byte[]>();
        responses.put("/client", client); responses.put("/asset", asset); responses.put("/library", library);
        responses.put("/index", ("{\"objects\":{\"sound\":{\"hash\":\"" + assetHash + "\",\"size\":" + asset.length + "}}}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] data = responses.get(exchange.getRequestURI().getPath());
            if (data == null) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
            exchange.sendResponseHeaders(200, data.length); try (var out = exchange.getResponseBody()) { out.write(data); }
        });
        server.start(); String base = "http://127.0.0.1:" + server.getAddress().getPort();
        String manifestJson = """
                {"id":"1.20.1","mainClass":"fixture.Main","assets":"fixture",
                 "assetIndex":{"id":"fixture","url":"%s/index"},
                 "downloads":{"client":{"url":"https://fixture.invalid/client","sha1":"%s","size":%d}},
                 "libraries":[{"name":"org.example:fixture:1","downloads":{"artifact":
                   {"path":"org/example/fixture/1/fixture-1.jar","url":"https://fixture.invalid/library","sha1":"%s","size":%d}}}]}
                """.formatted(base, clientHash, client.length, libraryHash, library.length);
        responses.put("/manifest", manifestJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        @Nullable String oldPath = System.getProperty("mfcl.native.path");
        org.jackhuang.hmcl.util.CacheRepository previousCache = org.jackhuang.hmcl.util.CacheRepository.getInstance();
        try {
            Path wrapper = tempDirectory.resolve("integration-backend");
            Files.writeString(wrapper, """
                    #!/usr/bin/env python3
                    import json, os, pathlib, subprocess, sys, urllib.parse
                    for line in sys.stdin:
                        request = json.loads(line); method = request['method']; params = request.get('params', {}); source_paths = {}
                        if method == 'download_batch':
                            params['allow_http_loopback'] = True
                            for item in params['items']:
                                source_paths[item['index']] = urllib.parse.urlsplit(item['urls'][0]).path.strip('/')
                                item['urls'] = ['http://127.0.0.1:PORT' + urllib.parse.urlsplit(url).path for url in item['urls']]
                        if method == 'download' and params.get('url', '').startswith('https://fixture.invalid/'):
                            params['allow_http_loopback'] = True
                            params['url'] = 'http://127.0.0.1:PORT' + urllib.parse.urlsplit(params['url']).path
                        child = subprocess.run([os.environ['MFCL_NATIVE_TEST_EXECUTABLE']], input=json.dumps(request) + '\\n', text=True, stdout=subprocess.PIPE, check=True)
                        result = json.loads(child.stdout.splitlines()[-1]).get('result')
                        if result is not None and method == 'project_game_manifest': pathlib.Path(__file__ + '.manifest').touch()
                        if result is not None and method == 'download': pathlib.Path(__file__ + '.download').touch()
                        if result is not None and method == 'download_batch':
                            pathlib.Path(__file__ + '.batch').touch()
                            for item in result['results']:
                                if item['ok']: pathlib.Path(__file__ + '.' + source_paths[item['index']]).touch()
                        sys.stdout.write(child.stdout); sys.stdout.flush()
                    """.replace("PORT", Integer.toString(server.getAddress().getPort())));
            assertTrue(wrapper.toFile().setExecutable(true)); System.setProperty("mfcl.native.path", wrapper.toString());
            TestRepository repository = new TestRepository(tempDirectory.resolve("game"));
            DefaultCacheRepository cache = new DefaultCacheRepository(tempDirectory.resolve("cache"));
            org.jackhuang.hmcl.util.CacheRepository.setInstance(cache);
            var provider = new org.jackhuang.hmcl.download.DownloadProvider() {
                @Override public DefaultCacheRepository getCacheRepository() { return cache; }
                @Override public org.jackhuang.hmcl.download.DownloadCandidates getAssetObjectCandidates(AssetObject object) {
                    return org.jackhuang.hmcl.download.DownloadCandidates.of("https://fixture.invalid/asset");
                }
            };
            DefaultDependencyManager manager = new DefaultDependencyManager(repository, provider, cache);
            GameInstanceID id = new GameInstanceID("local-install");
            try (DefaultGameBuilder builder = manager.newGameBuilder(id)) {
                builder.component(new org.jackhuang.hmcl.download.game.GameRemoteVersion(
                        org.jackhuang.hmcl.util.versioning.GameVersionNumber.asGameVersion("1.20.1"), List.of(base + "/manifest"),
                        ReleaseType.RELEASE, java.time.Instant.EPOCH));
                var task = builder.buildAsync(); assertTrue(task.executor().test(), () -> "Local install failed: " + task.getException());
            }
            assertTrue(Files.exists(Path.of(wrapper + ".manifest"))); assertTrue(Files.exists(Path.of(wrapper + ".batch")));
            assertTrue(Files.exists(Path.of(wrapper + ".asset"))); assertTrue(Files.exists(Path.of(wrapper + ".library")));
            assertTrue(Files.exists(Path.of(wrapper + ".download")));
            assertTrue(repository.hasInstance(id));
            org.junit.jupiter.api.Assertions.assertArrayEquals(client, Files.readAllBytes(repository.getLayout().getInstanceJarFile(id)));
            org.junit.jupiter.api.Assertions.assertArrayEquals(asset, Files.readAllBytes(repository.getLayout().getAssetObject(new AssetObject(assetHash, asset.length))));
            org.junit.jupiter.api.Assertions.assertArrayEquals(library, Files.readAllBytes(repository.getLayout().getLibraryFile(id,
                    repository.getInstance(id).getResolvedManifest().getLibraries().get(0))));
        } finally {
            server.stop(0); org.jackhuang.hmcl.nativebridge.NativeTransferTestSupport.clearIdle();
            org.jackhuang.hmcl.util.CacheRepository.setInstance(previousCache);
            if (oldPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", oldPath);
        }
    }

    /// Real library installation uses native staging and retains JAR validation and caching.
    @Test
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    public void libraryInstallUsesRustBatch(@TempDir Path tempDirectory) throws Exception {
        @Nullable String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        org.junit.jupiter.api.Assumptions.assumeTrue(binary != null && !binary.isBlank());
        org.jackhuang.hmcl.JavaFXLauncher.start();
        org.junit.jupiter.api.Assumptions.assumeTrue(org.jackhuang.hmcl.JavaFXLauncher.isStarted());
        var archive = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(archive)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("content.txt")); zip.write("test".getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
        }
        byte[] data = archive.toByteArray();
        String hash = org.jackhuang.hmcl.util.DigestUtils.digestToString("SHA-1", data);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/library", exchange -> {
            exchange.sendResponseHeaders(200, data.length); try (var out = exchange.getResponseBody()) { out.write(data); }
        });
        server.start();
        @Nullable String previous = System.getProperty("mfcl.native.path");
        try {
            Path wrapper = tempDirectory.resolve("library-backend");
            Files.writeString(wrapper, """
                    #!/usr/bin/env python3
                    import json, os, pathlib, subprocess, sys
                    for line in sys.stdin:
                        request = json.loads(line)
                        if request['method'] == 'download_batch':
                            pathlib.Path(__file__ + '.batch').touch()
                            request['params']['allow_http_loopback'] = True
                            for item in request['params']['items']: item['urls'] = ['http://127.0.0.1:PORT/library']
                        result = subprocess.run([os.environ['MFCL_NATIVE_TEST_EXECUTABLE']], input=json.dumps(request) + '\\n', text=True, stdout=subprocess.PIPE, check=True)
                        sys.stdout.write(result.stdout); sys.stdout.flush()
                    """.replace("PORT", Integer.toString(server.getAddress().getPort())));
            assertTrue(wrapper.toFile().setExecutable(true)); System.setProperty("mfcl.native.path", wrapper.toString());
            TestRepository repository = new TestRepository(tempDirectory.resolve("game"));
            DefaultCacheRepository cache = new DefaultCacheRepository(tempDirectory.resolve("cache"));
            DefaultDependencyManager manager = new DefaultDependencyManager(repository, new TestDownloadProvider(cache), cache);
            var library = new Library(new Artifact("org.example", "test", "1"), null,
                    new LibrariesDownloadInfo(new LibraryDownloadInfo("org/example/test/1/test-1.jar", "https://fixture.invalid/library", hash, data.length)),
                    null, null, null, null, null, null);
            var manifest = new GameInstanceManifest(new GameInstanceID("library-test")).withLibraries(List.of(library));
            var task = new org.jackhuang.hmcl.download.game.GameLibrariesTask(manager, manifest, true);
            assertTrue(task.executor().test(), () -> "Library installation failed: " + task.getException());
            assertTrue(Files.exists(Path.of(wrapper + ".batch")), "Native library batch was not invoked");
            Path file = repository.getLayout().getLibraryFile(manifest.id(), library);
            org.junit.jupiter.api.Assertions.assertArrayEquals(data, Files.readAllBytes(file));
            assertTrue(cache.mayHaveLibrary(library)); assertTrue(cache.getLibrary(library).isPresent());
        } finally {
            server.stop(0); org.jackhuang.hmcl.nativebridge.NativeTransferTestSupport.clearIdle();
            if (previous == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previous);
        }
    }

    /// Launches a seeded offline vanilla game through the current native command and archive planners.
    @Test
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    public void assetInstallUsesRustBatchWithCacheHit(@TempDir Path tempDirectory) throws Exception {
        @Nullable String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        org.junit.jupiter.api.Assumptions.assumeTrue(binary != null && !binary.isBlank());
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var hits = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/download", exchange -> {
            hits.incrementAndGet(); byte[] data = "download".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, data.length); try (var out = exchange.getResponseBody()) { out.write(data); }
        });
        server.start();
        @Nullable String oldPath = System.getProperty("mfcl.native.path");
        @Nullable String oldBatch = System.getProperty("mfcl.native.assets.batch.enabled");
        try {
            org.jackhuang.hmcl.JavaFXLauncher.start();
            org.junit.jupiter.api.Assumptions.assumeTrue(org.jackhuang.hmcl.JavaFXLauncher.isStarted());
            String downloadHash = org.jackhuang.hmcl.util.DigestUtils.digestToString("SHA-1", "download".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String cachedHash = org.jackhuang.hmcl.util.DigestUtils.digestToString("SHA-1", "cached".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Path root = tempDirectory.resolve("game"); TestRepository repository = new TestRepository(root);
            Path index = repository.getLayout().getAssetIndexFile("batch-test"); Files.createDirectories(index.getParent());
            Files.writeString(index, "{\"objects\":{\"cached\":{\"hash\":\"" + cachedHash + "\",\"size\":6},"
                    + "\"download\":{\"hash\":\"" + downloadHash + "\",\"size\":8},"
                    + "\"download-copy\":{\"hash\":\"" + downloadHash + "\",\"size\":8}}}");
            DefaultCacheRepository cache = new DefaultCacheRepository(tempDirectory.resolve("cache"));
            Path cached = tempDirectory.resolve("cached"); Files.writeString(cached, "cached");
            cache.cacheFile(cached, org.jackhuang.hmcl.util.CacheRepository.SHA1, cachedHash);
            var provider = new org.jackhuang.hmcl.download.DownloadProvider() {
                @Override public DefaultCacheRepository getCacheRepository() { return cache; }
                @Override public org.jackhuang.hmcl.download.DownloadCandidates getAssetObjectCandidates(AssetObject object) {
                    return org.jackhuang.hmcl.download.DownloadCandidates.of("https://fixture.invalid/" + object.hash());
                }
            };
            Path wrapper = tempDirectory.resolve("backend");
            Files.writeString(wrapper, """
                    #!/usr/bin/env python3
                    import json, os, pathlib, subprocess, sys
                    for line in sys.stdin:
                        request = json.loads(line)
                        if request['method'] == 'download_batch':
                            pathlib.Path(__file__ + '.batch').touch()
                            request['params']['allow_http_loopback'] = True
                            for item in request['params']['items']: item['urls'] = [os.environ['MFCL_TEST_ASSET_URL']]
                        result = subprocess.run([os.environ['MFCL_NATIVE_TEST_EXECUTABLE']], input=json.dumps(request) + '\\n', text=True, stdout=subprocess.PIPE, check=True)
                        if request['method'] == 'download_batch': pathlib.Path(__file__ + '.response').write_text(result.stdout)
                        sys.stdout.write(result.stdout); sys.stdout.flush()
                    """.replace("os.environ['MFCL_TEST_ASSET_URL']", "'http://127.0.0.1:" + server.getAddress().getPort() + "/download'"));
            assertTrue(wrapper.toFile().setExecutable(true)); System.setProperty("mfcl.native.path", wrapper.toString());
            System.clearProperty("mfcl.native.assets.batch.enabled");
            DefaultDependencyManager manager = new DefaultDependencyManager(repository, provider, cache);
            GameInstanceManifest manifest = new GameInstanceManifest(new GameInstanceID("batch-test"))
                    .withAssetIndex(new AssetIndexInfo("batch-test", "https://fixture.invalid/index", null, 0, 0));
            var task = new org.jackhuang.hmcl.download.game.GameAssetDownloadTask(manager, manifest, false, true);
            var counter = new java.util.concurrent.atomic.AtomicInteger();
            var executor = task.executor();
            executor.addTaskListener(new org.jackhuang.hmcl.task.TaskListener() {
                @Override public void onPropertiesUpdate(org.jackhuang.hmcl.task.Task<?> item) {
                    if (item instanceof org.jackhuang.hmcl.task.Task<?>.CountTask) counter.incrementAndGet();
                }
            });
            assertTrue(executor.test(), () -> "Asset installation failed: " + task.getException()
                    + "; batch called=" + Files.exists(Path.of(wrapper + ".batch"))
                    + "; response=" + readFixtureIfExists(Path.of(wrapper + ".response")));
            assertEquals(2, task.getProperties().get("total"));
            assertEquals(2, counter.get(), "Cached and native resource tasks should both update the stage counter");
            assertTrue(Files.exists(Path.of(wrapper + ".batch")), "Native asset batch was not invoked");
            assertEquals(1, hits.get(), "Cached and duplicate resources should not trigger extra downloads");
            assertEquals("cached", Files.readString(repository.getLayout().getAssetObject(new AssetObject(cachedHash, 6))));
            assertEquals("download", Files.readString(repository.getLayout().getAssetObject(new AssetObject(downloadHash, 8))));
        } finally {
            server.stop(0);
            org.jackhuang.hmcl.nativebridge.NativeTransferTestSupport.clearIdle();
            if (oldPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", oldPath);
            if (oldBatch == null) System.clearProperty("mfcl.native.assets.batch.enabled"); else System.setProperty("mfcl.native.assets.batch.enabled", oldBatch);
        }
    }

    /// Launches a seeded offline vanilla game through the current native command and archive planners.
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "MFCL_SEEDED_GAME_LAUNCH", matches = "1")
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.LINUX, org.junit.jupiter.api.condition.OS.MAC})
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    @org.junit.jupiter.api.Timeout(value = 5, unit = java.util.concurrent.TimeUnit.MINUTES)
    public void seededOfflineGameUsesNativeLaunchAssembly(@TempDir Path tempDirectory) throws Exception {
        @Nullable String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        @Nullable String seedText = System.getenv("MFCL_LIVE_CACHE_SEED");
        @Nullable String manifestText = System.getenv("MFCL_SEEDED_MANIFEST");
        org.junit.jupiter.api.Assumptions.assumeTrue(binary != null && !binary.isBlank() && seedText != null && !seedText.isBlank()
                && manifestText != null && !manifestText.isBlank());
        Path seed = Path.of(seedText); Path root = tempDirectory.resolve("game"); Files.createDirectories(root);
        org.jackhuang.hmcl.util.io.FileUtils.copyDirectory(seed.resolve("game/assets"), root.resolve("assets"));
        org.jackhuang.hmcl.util.io.FileUtils.copyDirectory(seed.resolve("game/libraries"), root.resolve("libraries"));
        GameInstanceID id = new GameInstanceID("1.20.1");
        String manifestJson = Files.readString(Path.of(manifestText));
        Path client = seed.resolve("cache/cache/SHA-1/0c/0c3ec587af28e5a785c0b4a7b8a30f9a8f78f838");
        Path jar = root.resolve("versions/1.20.1/1.20.1.jar"); Files.createDirectories(jar.getParent()); Files.copy(client, jar);
        Path wrapper = tempDirectory.resolve("backend");
        Files.writeString(wrapper, """
                #!/usr/bin/env python3
                import json, os, pathlib, subprocess, sys
                native = os.environ['MFCL_NATIVE_TEST_EXECUTABLE']
                if sys.argv[1:] in (['--exec-child'], ['--exec-child-v2']):
                    pathlib.Path(__file__ + '.launched').touch(); os.execv(native, [native] + sys.argv[1:])
                child = subprocess.Popen([native], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
                try:
                    for line in sys.stdin:
                        request = json.loads(line); child.stdin.write(line); child.stdin.flush()
                        while True:
                            response = child.stdout.readline()
                            if not response: sys.exit(1)
                            frame = json.loads(response)
                            if 'result' in frame and request.get('method') in ('project_game_manifest', 'assemble_launch_sections', 'plan_native_archives_with_rules', 'plan_classpath_with_rules'):
                                pathlib.Path(__file__ + '.' + request['method']).touch()
                            sys.stdout.write(response); sys.stdout.flush()
                            if 'result' in frame or 'error' in frame: break
                finally:
                    child.stdin.close(); child.wait(timeout=5)
                """);
        assertTrue(wrapper.toFile().setExecutable(true));
        @Nullable String oldPath = System.getProperty("mfcl.native.path"), oldPrism = System.getProperty("prism.order");
        try {
            System.setProperty("prism.order", "sw"); org.jackhuang.hmcl.JavaFXLauncher.start();
            org.junit.jupiter.api.Assumptions.assumeTrue(org.jackhuang.hmcl.JavaFXLauncher.isStarted());
            System.setProperty("mfcl.native.path", wrapper.toString());
            // Use the same patch builder as an installation, then launch its reconstructed manifest.
            var createPatch = org.jackhuang.hmcl.download.game.GameInstallTask.class.getDeclaredMethod("createPatch", String.class, String.class);
            createPatch.setAccessible(true);
            GameInstancePatch patch = (GameInstancePatch) createPatch.invoke(null, manifestJson, "1.20.1");
            var nativeParser = Class.forName("org.jackhuang.hmcl.download.game.NativeGameManifest")
                    .getDeclaredMethod("tryPatch", String.class, String.class);
            nativeParser.setAccessible(true);
            GameInstancePatch nativePatch = (GameInstancePatch) nativeParser.invoke(null, manifestJson, "1.20.1");
            assertNotNull(nativePatch, "Installation patch fell back from native projection");
            assertEquals(patch.toJsonObject(), nativePatch.toJsonObject());
            GameInstanceManifest manifest = new GameInstanceManifest(id).addPatch(patch).reconstructByPatches();
            TestRepository repository = new TestRepository(root);
            DefaultGameRepositorySnapshot snapshot = new DefaultGameRepositorySnapshot(repository, repository.getLayout());
            TestGameInstance instance = new TestGameInstance(snapshot, id, manifest, (@Nullable Path) null);
            snapshot.put(instance); repository.publishSnapshot(snapshot);
            launchLiveFixture(instance, wrapper);
            assertTrue(Files.exists(Path.of(wrapper + ".project_game_manifest")), "Native game manifest projection was not used");
            assertTrue(Files.exists(Path.of(wrapper + ".assemble_launch_sections")), "Native launch assembly was not used");
            assertTrue(Files.exists(Path.of(wrapper + ".plan_native_archives_with_rules")), "Combined native archive planner was not used");
            assertTrue(Files.exists(Path.of(wrapper + ".plan_classpath_with_rules")), "Combined native classpath planner was not used");
        } finally {
            org.jackhuang.hmcl.nativebridge.NativeTransferTestSupport.clearIdle();
            if (oldPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", oldPath);
            if (oldPrism == null) System.clearProperty("prism.order"); else System.setProperty("prism.order", oldPrism);
        }
    }

    /// Starts the isolated offline game through Rust and waits for a render initialization signal.
    /// @param instance successfully installed test instance
    /// @param wrapper native backend wrapper recording process replacement
    /// @throws Exception if game startup fails
    private static void launchLiveFixture(DefaultGameInstance instance, Path wrapper) throws Exception {
        Path javaBinary = Path.of(System.getenv().getOrDefault("MFCL_LIVE_GAME_JAVA", "/usr/bin/java"));
        var info = new org.jackhuang.hmcl.java.JavaInfo(org.jackhuang.hmcl.util.platform.Platform.CURRENT_PLATFORM, "17", "fixture");
        var runtime = org.jackhuang.hmcl.java.JavaRuntime.of(javaBinary, info, false);
        var options = new LaunchOptions.Builder().setInstanceId(instance.getId()).setGameDir(instance.getRepository().getBaseDirectory())
                .setJava(runtime).setVersionName(System.getenv().getOrDefault("MFCL_LIVE_GAME_VERSION", "1.20.1"))
                .setProfileName("MFCL test").setMaxMemory(1024)
                .setWidth(854).setHeight(480).setEnvironmentVariables(Map.of("LIBGL_ALWAYS_SOFTWARE", "1", "ALSOFT_DRIVERS", "null"))
                .create();
        String username = "MFCLTest";
        var auth = new org.jackhuang.hmcl.auth.AuthInfo(username,
                java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "0", org.jackhuang.hmcl.auth.AuthInfo.USER_TYPE_LEGACY, "{}");
        java.util.List<String> logs = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
        var listener = new org.jackhuang.hmcl.launch.ProcessListener() {
            /// Records game initialization without retaining an unbounded log.
            @Override public void onLog(String log, boolean error) {
                if (logs.size() < 2000) logs.add(log);
                if (log.contains("Created:") && log.contains("blocks.png-atlas")) ready.set(true);
            }
            /// The test inspects the raw process exit status.
            @Override public void onExit(int exitCode, ExitType exitType) { }
        };
        var launchManifest = LaunchManifestNormalizer.repairForLaunch(instance.getResolvedManifest());
        var launcher = new org.jackhuang.hmcl.launch.DefaultLauncher(instance, launchManifest, auth, options, listener, true);
        Files.deleteIfExists(Path.of(wrapper + ".launched"));
        var process = launcher.launch();
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(120);
            while (!ready.get() && process.isRunning() && System.nanoTime() < deadline) Thread.sleep(200);
            assertTrue(Files.exists(Path.of(wrapper + ".launched")), "Rust process replacement was not used");
            assertTrue(ready.get(), () -> "Game did not reach initialization: " + logs);
        } finally {
            process.getProcess().destroy();
            if (!process.getProcess().waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.getProcess().destroyForcibly(); process.getProcess().waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }

    /// Native chain planning preserves Java manifest merging, patch order and cycle behavior.
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.LINUX, org.junit.jupiter.api.condition.OS.MAC})
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    public void nativeInheritancePlanMatchesJavaResolution(@TempDir Path tempDirectory) throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        org.junit.jupiter.api.Assumptions.assumeTrue(executable != null && !executable.isBlank());
        Path wrapper = tempDirectory.resolve("backend");
        Files.writeString(wrapper, "#!/bin/sh\nIFS= read -r line\nprintf '%s\\n' \"$line\" >> \"$0.request\"\nprintf '%s\\n' \"$line\" | exec '" + executable.replace("'", "'\\''") + "'\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        TestRepository repository = new TestRepository(tempDirectory.resolve("game"));
        DefaultGameRepositorySnapshot snapshot = new DefaultGameRepositorySnapshot(repository, repository.getLayout());
        GameInstanceID base = new GameInstanceID("base"), child = new GameInstanceID("child"), a = new GameInstanceID("a"), b = new GameInstanceID("b");
        List<GameInstanceManifest> manifests = List.of(
                new GameInstanceManifest(base).withMainClass("base.Main").withPatches(List.of(
                        new GameInstancePatch("late", null, 20, null, "late.Main", null),
                        new GameInstancePatch("early", null, 10, null, "early.Main", null))),
                new GameInstanceManifest(child).withInheritsFrom(base),
                new GameInstanceManifest(a).withInheritsFrom(b).withMainClass("a.Main"),
                new GameInstanceManifest(b).withInheritsFrom(a).withMainClass("b.Main"));
        for (GameInstanceManifest manifest : manifests)
            snapshot.put(new TestGameInstance(snapshot, manifest.id(), manifest, (@Nullable Path) null));
        @Nullable String previousPath = System.getProperty("mfcl.native.path");
        @Nullable String previousEnabled = System.getProperty("mfcl.native.instances.enabled");
        try {
            System.setProperty("mfcl.native.instances.enabled", "false");
            List<GameInstanceManifest> expected = new java.util.ArrayList<>();
            for (GameInstanceManifest manifest : manifests) expected.add(snapshot.resolve(manifest));
            GameInstanceManifest override = new GameInstanceManifest(a).withInheritsFrom(base).withMainClass("override.Main");
            GameInstanceManifest expectedOverride = snapshot.resolve(override);
            System.setProperty("mfcl.native.path", wrapper.toString()); System.clearProperty("mfcl.native.instances.enabled");
            for (int i = 0; i < manifests.size(); i++) assertEquals(expected.get(i), snapshot.resolve(manifests.get(i)));
            assertEquals(expectedOverride, snapshot.resolve(override));
            assertTrue(Files.readString(Path.of(wrapper + ".request")).contains("plan_inheritance"));
            GameInstanceManifest missing = new GameInstanceManifest(new GameInstanceID("missing-child"))
                    .withInheritsFrom(new GameInstanceID("missing-parent"));
            assertThrows(NoSuchGameInstanceException.class, () -> snapshot.resolve(missing));
            Path invalid = tempDirectory.resolve("invalid-plan");
            Files.writeString(invalid, "#!/bin/sh\nread line\nprintf '%s\\n' '{\"version\":1,\"id\":1,\"result\":{\"chain\":[\"child\",\"a\"],\"cycle\":false}}'\n");
            assertTrue(invalid.toFile().setExecutable(true));
            System.setProperty("mfcl.native.path", invalid.toString());
            assertEquals(expected.get(1), snapshot.resolve(manifests.get(1)));
        } finally {
            if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
            if (previousEnabled == null) System.clearProperty("mfcl.native.instances.enabled"); else System.setProperty("mfcl.native.instances.enabled", previousEnabled);
        }
    }

    /// An invalid native patch response falls back to the original Java patch application.
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.LINUX, org.junit.jupiter.api.condition.OS.MAC})
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    public void invalidNativePatchesFallBack(@TempDir Path tempDirectory) throws Exception {
        TestRepository repository = new TestRepository(tempDirectory.resolve("game"));
        DefaultGameRepositorySnapshot snapshot = new DefaultGameRepositorySnapshot(repository, repository.getLayout());
        GameInstanceManifest manifest = new GameInstanceManifest(new GameInstanceID("fixture")).withPatches(List.of(
                new GameInstancePatch("loader", null, 20, null, "loader.Main", null)));
        Path wrapper = tempDirectory.resolve("backend");
        Files.writeString(wrapper, "#!/bin/sh\nread line\nprintf '%s' \"$line\" > \"$0.request\"\nprintf '%s\\n' '{\"version\":1,\"id\":1,\"result\":{\"main_class\":999999}}'\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        @Nullable String previousPath = System.getProperty("mfcl.native.path");
        @Nullable String previousEnabled = System.getProperty("mfcl.native.instances.enabled");
        try {
            System.setProperty("mfcl.native.instances.enabled", "false"); GameInstanceManifest expected = snapshot.resolve(manifest);
            System.setProperty("mfcl.native.path", wrapper.toString()); System.clearProperty("mfcl.native.instances.enabled");
            assertEquals(expected, snapshot.resolve(manifest));
            assertTrue(Files.readString(Path.of(wrapper + ".request")).contains("apply_manifest_patches"));
        } finally {
            if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
            if (previousEnabled == null) System.clearProperty("mfcl.native.instances.enabled"); else System.setProperty("mfcl.native.instances.enabled", previousEnabled);
        }
    }

    /// Native inheritance pruning agrees with Java for generated missing chains and cycles.
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.LINUX, org.junit.jupiter.api.condition.OS.MAC})
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    public void nativeInheritanceMatchesJava(@TempDir Path tempDirectory) throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        org.junit.jupiter.api.Assumptions.assumeTrue(executable != null && !executable.isBlank());
        Path wrapper = tempDirectory.resolve("backend");
        Files.writeString(wrapper, "#!/bin/sh\nread line\nprintf '%s\\n' \"$line\" >> \"$0.request\"\nprintf '%s\\n' \"$line\" | exec '" + executable.replace("'", "'\\''") + "'\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        TestRepository repository = new TestRepository(tempDirectory.resolve("game"));
        DefaultGameRepositorySnapshot javaSnapshot = new DefaultGameRepositorySnapshot(repository, repository.getLayout());
        DefaultGameRepositorySnapshot nativeSnapshot = new DefaultGameRepositorySnapshot(repository, repository.getLayout());
        java.util.Random random = new java.util.Random(7331);
        for (int i = 0; i < 300; i++) {
            GameInstanceID id = new GameInstanceID("instance-" + i);
            int parent = random.nextInt(400);
            @Nullable GameInstanceID parentId = parent < 40 ? null : new GameInstanceID("instance-" + parent);
            GameInstanceManifest manifest = new GameInstanceManifest(id).withInheritsFrom(parentId);
            javaSnapshot.put(new TestGameInstance(javaSnapshot, id, manifest, (@Nullable Path) null));
            nativeSnapshot.put(new TestGameInstance(nativeSnapshot, id, manifest, (@Nullable Path) null));
        }
        @Nullable String previousPath = System.getProperty("mfcl.native.path");
        @Nullable String previousEnabled = System.getProperty("mfcl.native.instances.enabled");
        try {
            System.setProperty("mfcl.native.instances.enabled", "false"); javaSnapshot.removeInvalidInstances();
            System.setProperty("mfcl.native.path", wrapper.toString()); System.clearProperty("mfcl.native.instances.enabled");
            nativeSnapshot.removeInvalidInstances();
            assertEquals(javaSnapshot.getInstances().stream().map(DefaultGameInstance::getId).collect(java.util.stream.Collectors.toSet()),
                    nativeSnapshot.getInstances().stream().map(DefaultGameInstance::getId).collect(java.util.stream.Collectors.toSet()));
            assertTrue(Files.readString(Path.of(wrapper + ".request")).contains("find_invalid_inheritance"));
        } finally {
            if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
            if (previousEnabled == null) System.clearProperty("mfcl.native.instances.enabled"); else System.setProperty("mfcl.native.instances.enabled", previousEnabled);
        }
    }

    /// Draft publication uses native pruning before sealing the published snapshot.
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.LINUX, org.junit.jupiter.api.condition.OS.MAC})
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    public void nativeInheritanceWorksDuringDraftCommit(@TempDir Path tempDirectory) throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        org.junit.jupiter.api.Assumptions.assumeTrue(executable != null && !executable.isBlank());
        Path wrapper = tempDirectory.resolve("backend");
        Files.writeString(wrapper, "#!/bin/sh\nread line\nprintf '%s' \"$line\" > \"$0.request\"\nprintf '%s\\n' \"$line\" | exec '" + executable.replace("'", "'\\''") + "'\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        @Nullable String previousPath = System.getProperty("mfcl.native.path");
        @Nullable String previousEnabled = System.getProperty("mfcl.native.instances.enabled");
        try {
            System.setProperty("mfcl.native.path", wrapper.toString()); System.clearProperty("mfcl.native.instances.enabled");
            TestRepository repository = new TestRepository(tempDirectory.resolve("game"));
            GameInstanceID parent = new GameInstanceID("parent"); GameInstanceID child = new GameInstanceID("child");
            GameInstanceID orphan = new GameInstanceID("orphan");
            try (DefaultGameRepositoryDraft draft = repository.openDraft()) {
                draft.put(new GameInstanceManifest(parent));
                draft.put(new GameInstanceManifest(child).withInheritsFrom(parent));
                draft.put(new GameInstanceManifest(orphan).withInheritsFrom(new GameInstanceID("missing")));
                draft.commit();
            }
            assertTrue(repository.hasInstance(parent)); assertTrue(repository.hasInstance(child)); assertFalse(repository.hasInstance(orphan));
            assertTrue(Files.readString(Path.of(wrapper + ".request")).contains("find_invalid_inheritance"));
        } finally {
            if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
            if (previousEnabled == null) System.clearProperty("mfcl.native.instances.enabled"); else System.setProperty("mfcl.native.instances.enabled", previousEnabled);
        }
    }

    /// Native enumeration preserves repository refresh results, including Classic and inheritance.
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.LINUX, org.junit.jupiter.api.condition.OS.MAC})
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    public void nativeDirectoryEnumerationPreservesRefresh(@TempDir Path tempDirectory) throws Exception {
        @Nullable String executable = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        org.junit.jupiter.api.Assumptions.assumeTrue(executable != null && !executable.isBlank());
        Path root = tempDirectory.resolve("game"); Path versions = root.resolve("versions");
        for (String name : List.of("base", "child", "custom", "broken", "orphan", "tlauncher")) Files.createDirectories(versions.resolve(name));
        Files.writeString(versions.resolve("base/base.json"), "{\"id\":\"other\"}");
        Files.writeString(versions.resolve("child/child.json"), "{\"id\":\"child\",\"inheritsFrom\":\"base\",\"custom\":{\"retained\":true},"
                + "\"patches\":[{\"id\":\"game\",\"version\":\"1.20.1\",\"priority\":0,\"mainClass\":\"example.Main\","
                + "\"libraries\":[{\"name\":\"example:library:1\"}],\"customPatch\":true},null,42,{\"id\":\"fabric\",\"priority\":10}]}");
        Files.writeString(versions.resolve("custom/alternate.json"), "{\"id\":\"custom\"}");
        Files.writeString(versions.resolve("broken/broken.json"), "{");
        Files.writeString(versions.resolve("orphan/orphan.json"), "{\"id\":\"orphan\",\"inheritsFrom\":\"missing\"}");
        Files.writeString(versions.resolve("tlauncher/tlauncher.json"), "{\"id\":\"tlauncher\",\"tlauncherVersion\":1,\"libraries\":["
                + "{\"name\":\"example:legacy:1\",\"artifact\":{\"url\":\"https://example.test/lib\",\"size\":3},"
                + "\"classifies\":{\"z\":{\"url\":\"https://example.test/z\"},\"a\":{\"url\":\"https://example.test/a\"}},"
                + "\"natives\":{\"linux\":\"z\"},\"MMC-hint\":\"ignored\"}, {\"name\":\"example:empty:1\"}]} ");
        Path bin = root.resolve("bin"); Files.createDirectories(bin);
        for (String file : List.of("lwjgl.jar", "jinput.jar", "lwjgl_util.jar")) Files.writeString(bin.resolve(file), "fixture");
        Path wrapper = tempDirectory.resolve("backend");
        Files.writeString(wrapper, "#!/bin/sh\nIFS= read -r line\nprintf '%s\\n' \"$line\" >> \"$0.request\"\nprintf '%s\\n' \"$line\" | exec '" + executable.replace("'", "'\\''") + "'\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        @Nullable String previousPath = System.getProperty("mfcl.native.path");
        @Nullable String previousEnabled = System.getProperty("mfcl.native.instances.enabled");
        @Nullable String previousPool = System.getProperty("mfcl.native.pool.enabled");
        try {
            System.setProperty("mfcl.native.pool.enabled", "false");
            System.setProperty("mfcl.native.instances.enabled", "false");
            TestRepository javaRepository = new TestRepository(root); javaRepository.refresh();
            System.setProperty("mfcl.native.path", wrapper.toString());
            System.clearProperty("mfcl.native.instances.enabled");
            TestRepository nativeRepository = new TestRepository(root); nativeRepository.refresh();
            var expected = javaRepository.getSnapshot().getInstances().stream().map(DefaultGameInstance::getId).collect(java.util.stream.Collectors.toSet());
            var actual = nativeRepository.getSnapshot().getInstances().stream().map(DefaultGameInstance::getId).collect(java.util.stream.Collectors.toSet());
            assertEquals(expected, actual);
            assertEquals(java.util.Set.of(new GameInstanceID("base"), new GameInstanceID("child"),
                    new GameInstanceID("custom"), new GameInstanceID("tlauncher"), new GameInstanceID("Classic")), actual);
            assertTrue(Files.readString(Path.of(wrapper + ".request")).contains("list_instance_dirs"));
            assertTrue(Files.readString(Path.of(wrapper + ".request")).contains("project_instance_manifest"));
            String childJson = Files.readString(versions.resolve("child/child.json"));
            assertNotNull(org.jackhuang.hmcl.download.game.NativeGameManifest.decodeLoaderManifest(
                    org.jackhuang.hmcl.nativebridge.NativeBackend.projectInstanceManifest(wrapper, childJson), childJson));
            assertNotNull(org.jackhuang.hmcl.download.game.NativeGameManifest.tryInstanceManifest(childJson));
            assertNotNull(org.jackhuang.hmcl.download.game.NativeGameManifest.tryInstanceManifest(
                    Files.readString(versions.resolve("tlauncher/tlauncher.json"))));
            assertNull(org.jackhuang.hmcl.download.game.NativeGameManifest.tryInstanceManifest(
                    "{\"id\":\"child\",\"patches\":[{\"id\":\"x\",\"priority\":1.5}]}"));
            for (GameInstanceID id : actual)
                assertEquals(javaRepository.getInstance(id).getManifest(), nativeRepository.getInstance(id).getManifest());
            System.setProperty("mfcl.native.path", tempDirectory.resolve("missing-backend").toString());
            TestRepository fallback = new TestRepository(root); fallback.refresh();
            assertEquals(expected, fallback.getSnapshot().getInstances().stream()
                    .map(DefaultGameInstance::getId).collect(java.util.stream.Collectors.toSet()));
            Path invalid = tempDirectory.resolve("invalid-backend");
            Files.writeString(invalid, "#!/bin/sh\nread line\nprintf '%s\\n' '{\"version\":1,\"id\":1,\"result\":{\"directories\":[\"../escape\"]}}'\n");
            assertTrue(invalid.toFile().setExecutable(true));
            System.setProperty("mfcl.native.path", invalid.toString());
            TestRepository rejected = new TestRepository(root); rejected.refresh();
            assertEquals(expected, rejected.getSnapshot().getInstances().stream()
                    .map(DefaultGameInstance::getId).collect(java.util.stream.Collectors.toSet()));
        } finally {
            if (previousPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", previousPath);
            if (previousEnabled == null) System.clearProperty("mfcl.native.instances.enabled"); else System.setProperty("mfcl.native.instances.enabled", previousEnabled);
            if (previousPool == null) System.clearProperty("mfcl.native.pool.enabled"); else System.setProperty("mfcl.native.pool.enabled", previousPool);
        }
    }

    /// Native enumeration must retain filesystem semantics for a symlink followed by `..`.
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.LINUX, org.junit.jupiter.api.condition.OS.MAC})
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    public void nativeInstanceEnumerationPreservesSymlinkTraversal(@TempDir Path tempDirectory) throws Exception {
        @Nullable String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        org.junit.jupiter.api.Assumptions.assumeTrue(binary != null && !binary.isBlank());
        Path base = tempDirectory.resolve("base"), actual = tempDirectory.resolve("actual");
        Files.createDirectories(base); Files.createDirectories(actual.resolve("child"));
        Files.createSymbolicLink(base.resolve("link"), actual.resolve("child"));
        Path realVersions = actual.resolve("game/versions"), decoyVersions = base.resolve("game/versions");
        Files.createDirectories(realVersions.resolve("real")); Files.createDirectories(decoyVersions.resolve("decoy"));
        Files.writeString(realVersions.resolve("real/real.json"), "{\"id\":\"real\"}");
        Files.writeString(decoyVersions.resolve("decoy/decoy.json"), "{\"id\":\"decoy\"}");
        Path root = base.resolve("link/../game"), versions = root.resolve("versions");
        List<Path> dirs = org.jackhuang.hmcl.nativebridge.NativeBackend.listInstanceDirectories(Path.of(binary), versions);
        assertEquals(List.of("real"), dirs.stream().map(path -> path.getFileName().toString()).toList());
        @Nullable String oldPath = System.getProperty("mfcl.native.path"), oldEnabled = System.getProperty("mfcl.native.instances.enabled");
        try {
            System.setProperty("mfcl.native.path", binary); System.clearProperty("mfcl.native.instances.enabled");
            TestRepository repository = new TestRepository(root); repository.refresh();
            assertTrue(repository.hasInstance(new GameInstanceID("real")));
            assertFalse(repository.hasInstance(new GameInstanceID("decoy")));
        } finally {
            if (oldPath == null) System.clearProperty("mfcl.native.path"); else System.setProperty("mfcl.native.path", oldPath);
            if (oldEnabled == null) System.clearProperty("mfcl.native.instances.enabled"); else System.setProperty("mfcl.native.instances.enabled", oldEnabled);
        }
    }

    /// Rejects path-segment instance ids before they can reach repository deletion code.
    @Test
    public void testRejectsSpecialPathSegmentIds() {
        assertThrows(IllegalArgumentException.class, () -> new GameInstanceID("."));
        assertThrows(IllegalArgumentException.class, () -> new GameInstanceID(".."));
    }

    /// Keeps a new manifest in memory until commit and publishes the resulting instance once committed.
    @Test
    public void testCommitPublishesModifiedManifest(@TempDir Path tempDirectory) throws IOException {
        TestRepository repository = new TestRepository(tempDirectory);
        GameInstanceID id = new GameInstanceID("instance");
        GameInstanceManifest manifest = new GameInstanceManifest(id).withMainClass("example.Main");
        Path manifestFile = repository.getLayout().getInstanceJson(id);
        Path instanceRoot = repository.getLayout().getInstanceRoot(id);
        Path draftStorage = tempDirectory.resolve(".hmcl").resolve("repository-drafts");

        try (DefaultGameRepositoryDraft draft = repository.openDraft()) {
            draft.put(manifest);

            assertFalse(repository.hasInstance(id));
            assertFalse(Files.exists(instanceRoot));
            assertFalse(Files.exists(manifestFile));
            assertFalse(Files.exists(draftStorage));

            GameRepositorySnapshot committed = draft.commit();
            assertEquals(GameRepositoryDraft.State.COMMITTED, draft.getState());
            assertEquals(manifest, committed.getInstance(id).getManifest());
        }

        assertTrue(repository.hasInstance(id));
        GameInstanceManifest stored = JsonUtils.fromNonNullJson(
                Files.readString(manifestFile),
                GameInstanceManifest.class);
        assertEquals(id, stored.id());
        assertEquals("example.Main", stored.mainClass());
    }

    /// Materializes a completed client JAR only while committing the new instance.
    @Test
    public void testCommitMaterializesPrimaryJar(@TempDir Path tempDirectory) throws IOException {
        TestRepository repository = new TestRepository(tempDirectory.resolve("game"));
        GameInstanceID id = new GameInstanceID("instance");
        GameInstanceManifest manifest = new GameInstanceManifest(id);
        Path source = tempDirectory.resolve("cache/client.jar");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "client");
        Path target = repository.getLayout().getInstanceJarFile(id);

        try (DefaultGameRepositoryDraft draft = repository.openDraft()) {
            draft.put(manifest);
            draft.putPrimaryJar(id, source);

            assertFalse(Files.exists(repository.getLayout().getInstanceRoot(id)));
            assertFalse(Files.exists(target));

            draft.commit();
        }

        assertEquals("client", Files.readString(target));
        assertEquals("client", Files.readString(source));
    }

    /// Aborting removes files below a root that was first created by the draft.
    @Test
    public void testAbortRemovesDraftCreatedInstanceRoot(@TempDir Path tempDirectory) throws IOException {
        TestRepository repository = new TestRepository(tempDirectory);
        GameInstanceID id = new GameInstanceID("instance");
        Path root = repository.getLayout().getInstanceRoot(id);

        try (DefaultGameRepositoryDraft draft = repository.openDraft()) {
            draft.put(new GameInstanceManifest(id));
            Files.createDirectories(root);
            Files.writeString(root.resolve("downloaded.jar"), "content");
        }

        assertFalse(Files.exists(root));
        assertFalse(repository.hasInstance(id));

        try (DefaultGameRepositoryDraft ignored = repository.openDraft()) {
            assertTrue(ignored.isOpen());
        }
    }

    /// Leaves the published manifest and its JSON unchanged when an update is aborted.
    @Test
    public void testAbortDoesNotOverwriteExistingManifest(@TempDir Path tempDirectory) throws IOException {
        TestRepository repository = new TestRepository(tempDirectory);
        GameInstanceID id = new GameInstanceID("instance");
        GameInstanceManifest original = new GameInstanceManifest(id).withMainClass("original.Main");
        GameInstanceManifest updated = original.withMainClass("updated.Main");
        repository.save(original);

        try (DefaultGameRepositoryDraft draft = repository.openDraft()) {
            draft.put(updated);
            assertEquals(original, repository.getInstance(id).getManifest());
        }

        Path manifestFile = repository.getLayout().getInstanceJson(id);
        assertEquals(original, repository.getInstance(id).getManifest());
        GameInstanceManifest stored = JsonUtils.fromNonNullJson(
                Files.readString(manifestFile),
                GameInstanceManifest.class);
        assertEquals(id, stored.id());
        assertEquals("original.Main", stored.mainClass());
    }

    /// Rejects overlapping drafts and direct snapshot writes while a draft owns the repository.
    @Test
    public void testDraftExcludesOtherRepositoryWrites(@TempDir Path tempDirectory) throws IOException {
        TestRepository repository = new TestRepository(tempDirectory);
        try (DefaultGameRepositoryDraft draft = repository.openDraft()) {
            assertThrows(IllegalStateException.class, repository::openDraft);
            assertThrows(IllegalStateException.class, repository::refresh);
            assertThrows(
                    IllegalStateException.class,
                    () -> repository.setBaseDirectory(tempDirectory.resolve("other")));
            assertTrue(draft.isOpen());
        }

        repository.refresh();
    }

    /// Refuses to claim and later delete an unregistered directory that predates the draft.
    @Test
    public void testPutRejectsPreexistingUnregisteredDirectory(@TempDir Path tempDirectory) throws IOException {
        TestRepository repository = new TestRepository(tempDirectory);
        GameInstanceID id = new GameInstanceID("instance");
        Path root = repository.getLayout().getInstanceRoot(id);
        Files.createDirectories(root);
        Path retained = root.resolve("retained.txt");
        Files.writeString(retained, "content");

        try (DefaultGameRepositoryDraft draft = repository.openDraft()) {
            assertThrows(FileAlreadyExistsException.class, () -> draft.put(new GameInstanceManifest(id)));
        }

        assertTrue(Files.exists(retained));
    }

    /// Keeps a pending removal private and preserves the published files when the draft aborts.
    @Test
    public void testAbortPreservesRemovedInstance(@TempDir Path tempDirectory) throws IOException {
        TestRepository repository = new TestRepository(tempDirectory);
        GameInstanceID id = new GameInstanceID("instance");
        repository.save(new GameInstanceManifest(id));
        Path root = repository.getLayout().getInstanceRoot(id);

        try (DefaultGameRepositoryDraft draft = repository.openDraft()) {
            draft.remove(id);
            assertTrue(repository.hasInstance(id));
            assertTrue(Files.isDirectory(root));
        }

        assertTrue(repository.hasInstance(id));
        assertTrue(Files.isDirectory(root));
    }

    /// Renames an instance and its direct inheritance references in one draft commit.
    @Test
    public void testRenameCommitsFilesAndReferences(@TempDir Path tempDirectory) throws IOException {
        TestRepository repository = new TestRepository(tempDirectory);
        GameInstanceID parentId = new GameInstanceID("parent");
        GameInstanceID renamedId = new GameInstanceID("renamed");
        GameInstanceID childId = new GameInstanceID("child");
        repository.save(new GameInstanceManifest(parentId));
        repository.save(new GameInstanceManifest(childId).withInheritsFrom(parentId));

        assertTrue(repository.renameInstance(parentId, renamedId));

        assertFalse(repository.hasInstance(parentId));
        assertTrue(repository.hasInstance(renamedId));
        assertEquals(renamedId, repository.getInstance(childId).getManifest().inheritsFrom());
        assertFalse(Files.exists(repository.getLayout().getInstanceRoot(parentId)));
        assertTrue(Files.isRegularFile(repository.getLayout().getInstanceJson(renamedId)));
    }

    /// Removes a registered instance through a one-shot draft.
    @Test
    public void testRemoveInstanceUsesDraftCommit(@TempDir Path tempDirectory) throws IOException {
        TestRepository repository = new TestRepository(tempDirectory);
        GameInstanceID id = new GameInstanceID("instance");
        repository.save(new GameInstanceManifest(id));
        Path root = repository.getLayout().getInstanceRoot(id);

        assertTrue(repository.removeInstanceFromDisk(id));

        assertFalse(repository.hasInstance(id));
        assertFalse(Files.exists(root));
    }

    /// Restores modified manifests and releases exclusivity when publication fails after replacement.
    @Test
    public void testCommitFailureRollsBackAndReleasesDraft(@TempDir Path tempDirectory) throws IOException {
        FailingRepository repository = new FailingRepository(tempDirectory);
        GameInstanceID id = new GameInstanceID("instance");
        GameInstanceManifest original = new GameInstanceManifest(id).withMainClass("original.Main");
        repository.save(original);
        repository.failDraftPublish = true;

        DefaultGameRepositoryDraft draft = repository.openDraft();
        draft.put(original.withMainClass("updated.Main"));
        assertThrows(IllegalStateException.class, draft::commit);

        assertEquals(GameRepositoryDraft.State.FAILED, draft.getState());
        assertEquals(original, repository.getInstance(id).getManifest());
        GameInstanceManifest stored = JsonUtils.fromNonNullJson(
                Files.readString(repository.getLayout().getInstanceJson(id)),
                GameInstanceManifest.class);
        assertEquals("original.Main", stored.mainClass());
        try (DefaultGameRepositoryDraft ignored = repository.openDraft()) {
            assertTrue(ignored.isOpen());
        }
    }

    /// Restores an existing primary JAR when snapshot publication fails after replacement.
    @Test
    public void testCommitFailureRollsBackPrimaryJar(@TempDir Path tempDirectory) throws IOException {
        FailingRepository repository = new FailingRepository(tempDirectory.resolve("game"));
        GameInstanceID id = new GameInstanceID("instance");
        GameInstanceManifest original = new GameInstanceManifest(id).withMainClass("original.Main");
        repository.save(original);
        Path target = repository.getLayout().getInstanceJarFile(id);
        Files.writeString(target, "original");
        Path source = tempDirectory.resolve("cache/replacement.jar");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "replacement");
        repository.failDraftPublish = true;

        DefaultGameRepositoryDraft draft = repository.openDraft();
        draft.put(original.withMainClass("updated.Main"));
        draft.putPrimaryJar(id, source);
        assertThrows(IllegalStateException.class, draft::commit);

        assertEquals("original", Files.readString(target));
        assertEquals("replacement", Files.readString(source));
        assertEquals(GameRepositoryDraft.State.FAILED, draft.getState());
    }

    /// A manifest-only update preserves an existing client JAR.
    @Test
    public void testUpdateInstanceAsyncCommitsWorkingManifest(@TempDir Path tempDirectory) throws Exception {
        TestRepository repository = new TestRepository(tempDirectory);
        GameInstanceID id = new GameInstanceID("instance");
        repository.save(new GameInstanceManifest(id).withMainClass("original.Main"));
        Path target = repository.getLayout().getInstanceJarFile(id);
        Files.writeString(target, "custom client");

        Task<?> update = createDependencyManager(repository).updateInstanceAsync(id, workingInstance -> Task.supplyAsync(() ->
                workingInstance.getManifest().withMainClass("updated.Main")));
        assertTrue(update.executor().test());

        assertEquals("updated.Main", repository.getInstance(id).getManifest().mainClass());
        assertEquals("custom client", Files.readString(target));
    }

    /// A version change replaces an existing client JAR with the file selected by the new manifest.
    @Test
    public void testUpdateInstanceAsyncReplacesClientJar(@TempDir Path tempDirectory) throws Exception {
        TestRepository repository = new TestRepository(tempDirectory.resolve("game"));
        GameInstanceID id = new GameInstanceID("instance");
        GameInstanceManifest original = new GameInstanceManifest(id).withMainClass("original.Main");
        repository.save(original);
        Path target = repository.getLayout().getInstanceJarFile(id);
        Files.writeString(target, "old client");
        Path source = tempDirectory.resolve("new-client.jar");
        Files.writeString(source, "new client");
        String sha1 = DigestUtils.digestToString("SHA-1", source);
        DefaultCacheRepository cache = new DefaultCacheRepository(tempDirectory.resolve("cache"));
        Path cached = cache.cacheFile(source, "SHA-1", sha1);
        DefaultDependencyManager manager = new DefaultDependencyManager(
                repository, new TestDownloadProvider(cache), cache);
        GameInstanceManifest replacement = original.withDownloads(Map.of(DownloadType.CLIENT,
                new DownloadInfo("https://example.invalid/client.jar", sha1)));

        Task<?> update = manager.updateInstanceAsync(id, instance ->
                new GameDownloadTask(manager, replacement).thenApplyAsync(jar -> {
                    assertEquals("old client", Files.readString(target));
                    assertEquals(original, repository.getInstance(id).getManifest());
                    return replacement;
                }));
        assertTrue(update.executor().test());

        assertEquals(replacement, repository.getInstance(id).getManifest());
        assertTrue(replacement.getDownloadInfo().validateChecksum(target, false));
        assertEquals("new client", Files.readString(cached));
    }

    /// Equivalent checksums preserve a customized client even when the download URL changes.
    @Test
    public void testUpdateInstanceAsyncPreservesClientWithSameChecksum(@TempDir Path tempDirectory) throws Exception {
        TestRepository repository = new TestRepository(tempDirectory);
        GameInstanceID id = new GameInstanceID("instance");
        GameInstanceManifest original = new GameInstanceManifest(id).withDownloads(Map.of(DownloadType.CLIENT,
                new DownloadInfo("https://example.invalid/old.jar", "a".repeat(40))));
        repository.save(original);
        Path target = repository.getLayout().getInstanceJarFile(id);
        Files.writeString(target, "custom client");
        GameInstanceManifest replacement = original.withDownloads(Map.of(DownloadType.CLIENT,
                new DownloadInfo("https://example.invalid/new.jar", "A".repeat(40))));

        assertTrue(createDependencyManager(repository).updateInstanceAsync(id,
                instance -> Task.completed(replacement)).executor().test());

        assertEquals(replacement, repository.getInstance(id).getManifest());
        assertEquals("custom client", Files.readString(target));
    }

    /// A changed URL selects a replacement when neither client download declares a checksum.
    @Test
    public void testUpdateInstanceAsyncReplacesClientWithoutChecksum(@TempDir Path tempDirectory) throws Exception {
        TestRepository repository = new TestRepository(tempDirectory);
        GameInstanceID id = new GameInstanceID("instance");
        GameInstanceManifest original = new GameInstanceManifest(id).withDownloads(Map.of(DownloadType.CLIENT,
                new DownloadInfo("https://example.invalid/old.jar")));
        repository.save(original);
        Path target = repository.getLayout().getInstanceJarFile(id);
        Files.writeString(target, "old client");
        String newUrl = "https://example.invalid/new.jar";
        DefaultDependencyManager manager = createDependencyManager(repository);
        manager.getCacheRepository().cacheText(new UrlResponseInfo(200, WebURL.parse(newUrl),
                HttpHeaders.of(Map.of("etag", List.of("new-client"),
                        "cache-control", List.of("max-age=3600")), (name, value) -> true)), "new client");
        GameInstanceManifest replacement = original.withDownloads(Map.of(DownloadType.CLIENT, new DownloadInfo(newUrl)));

        assertTrue(manager.updateInstanceAsync(id, instance -> Task.completed(replacement)).executor().test());

        assertEquals(replacement, repository.getInstance(id).getManifest());
        assertEquals("new client", Files.readString(target));
    }

    /// Client changes supplied only by patches are resolved before choosing the replacement JAR.
    @Test
    public void testUpdateInstanceAsyncResolvesClientPatch(@TempDir Path tempDirectory) throws Exception {
        TestRepository repository = new TestRepository(tempDirectory);
        GameInstanceID id = new GameInstanceID("instance");
        GameInstanceManifest original = new GameInstanceManifest(id);
        repository.save(original);
        Path target = repository.getLayout().getInstanceJarFile(id);
        Files.writeString(target, "old client");
        Path source = tempDirectory.resolve("new-client.jar");
        Files.writeString(source, "new client");
        String sha1 = DigestUtils.digestToString("SHA-1", source);
        DefaultDependencyManager manager = createDependencyManager(repository);
        manager.getCacheRepository().cacheFile(source, "SHA-1", sha1);
        GameInstanceManifest replacement = original.withPatches(List.of(GameInstancePatch.fromManifest(
                original.withDownloads(Map.of(DownloadType.CLIENT,
                        new DownloadInfo("https://example.invalid/client.jar", sha1))),
                GameComponentType.GAME.getPatchId(), "new-version", GameInstancePatch.PRIORITY_MC)));

        assertTrue(manager.updateInstanceAsync(id, instance -> Task.completed(replacement)).executor().test());

        assertEquals(replacement, repository.getInstance(id).getManifest());
        assertEquals("new client", Files.readString(target));
    }

    /// Switching JAR ownership materializes an owned client but never replaces a referenced client.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testUpdateInstanceAsyncSwitchesJarOwnership(boolean useOwnJar, @TempDir Path tempDirectory) throws Exception {
        TestRepository repository = new TestRepository(tempDirectory);
        GameInstanceID id = new GameInstanceID("instance");
        GameInstanceID sharedId = new GameInstanceID("shared");
        repository.save(new GameInstanceManifest(sharedId));
        Path sharedJar = repository.getLayout().getInstanceJarFile(sharedId);
        Files.writeString(sharedJar, "shared client");
        String sha1 = DigestUtils.digestToString("SHA-1", sharedJar);
        DownloadInfo download = new DownloadInfo("https://example.invalid/client.jar", sha1);
        GameInstanceManifest original = new GameInstanceManifest(id)
                .withJar(useOwnJar ? sharedId : id)
                .withDownloads(useOwnJar ? Map.of(DownloadType.CLIENT, download) : null);
        repository.save(original);
        Path target = repository.getLayout().getInstanceJarFile(id);
        Files.writeString(target, "custom client");
        DefaultDependencyManager manager = createDependencyManager(repository);
        manager.getCacheRepository().cacheFile(sharedJar, "SHA-1", sha1);
        GameInstanceManifest replacement = original.withJar(useOwnJar ? id : sharedId)
                .withDownloads(Map.of(DownloadType.CLIENT, download));

        assertTrue(manager.updateInstanceAsync(id, instance -> Task.completed(replacement)).executor().test());

        assertEquals(useOwnJar ? "shared client" : "custom client", Files.readString(target));
        assertEquals("shared client", Files.readString(sharedJar));
        assertEquals(useOwnJar ? target : sharedJar, repository.getInstance(id).getInstanceJarFile());
    }

    /// A failed JAR lookup leaves both the old manifest and its client JAR intact.
    @Test
    public void testUpdateInstanceAsyncPreservesFilesWhenJarTaskFails(@TempDir Path tempDirectory) throws Exception {
        TestRepository repository = new TestRepository(tempDirectory);
        GameInstanceID id = new GameInstanceID("instance");
        GameInstanceManifest original = new GameInstanceManifest(id).withMainClass("original.Main");
        repository.save(original);
        Path manifestFile = repository.getLayout().getInstanceJson(id);
        String originalJson = Files.readString(manifestFile);
        Path target = repository.getLayout().getInstanceJarFile(id);
        Files.writeString(target, "original");

        GameInstanceManifest replacement = original.withDownloads(Map.of(DownloadType.CLIENT,
                new DownloadInfo("file:///missing-client.jar", "0".repeat(40))));
        Task<?> update = createDependencyManager(repository).updateInstanceAsync(id,
                instance -> Task.completed(replacement));
        assertFalse(update.executor().test());

        assertEquals(original, repository.getInstance(id).getManifest());
        assertEquals(originalJson, Files.readString(manifestFile));
        assertEquals("original", Files.readString(target));
        try (DefaultGameRepositoryDraft draft = repository.openDraft()) {
            assertTrue(draft.isOpen());
        }
    }

    /// Publication failure rolls back both files after an asynchronous update stages a new JAR.
    @Test
    public void testUpdateInstanceAsyncRollsBackManifestAndJar(@TempDir Path tempDirectory) throws Exception {
        FailingRepository repository = new FailingRepository(tempDirectory.resolve("game"));
        GameInstanceID id = new GameInstanceID("instance");
        GameInstanceManifest original = new GameInstanceManifest(id).withMainClass("original.Main");
        repository.save(original);
        Path manifestFile = repository.getLayout().getInstanceJson(id);
        String originalJson = Files.readString(manifestFile);
        Path target = repository.getLayout().getInstanceJarFile(id);
        Files.writeString(target, "original");
        Path source = tempDirectory.resolve("replacement.jar");
        Files.writeString(source, "replacement");
        repository.failDraftPublish = true;

        DefaultDependencyManager manager = createDependencyManager(repository);
        String sha1 = DigestUtils.digestToString("SHA-1", source);
        Path cached = manager.getCacheRepository().cacheFile(source, "SHA-1", sha1);
        GameInstanceManifest replacement = original.withMainClass("updated.Main")
                .withDownloads(Map.of(DownloadType.CLIENT,
                        new DownloadInfo("https://example.invalid/client.jar", sha1)));
        Task<?> update = manager.updateInstanceAsync(id, instance -> Task.completed(replacement));
        assertFalse(update.executor().test());

        assertEquals(original, repository.getInstance(id).getManifest());
        assertEquals(originalJson, Files.readString(manifestFile));
        assertEquals("original", Files.readString(target));
        assertEquals("replacement", Files.readString(source));
        assertEquals("replacement", Files.readString(cached));
        try (DefaultGameRepositoryDraft draft = repository.openDraft()) {
            assertTrue(draft.isOpen());
        }
    }

    /// Rejects an asynchronous update that attempts to create a different instance id.
    @Test
    public void testUpdateInstanceAsyncRejectsChangedId(@TempDir Path tempDirectory) throws Exception {
        TestRepository repository = new TestRepository(tempDirectory);
        GameInstanceID id = new GameInstanceID("instance");
        GameInstanceID otherId = new GameInstanceID("other");
        repository.save(new GameInstanceManifest(id).withMainClass("original.Main"));

        Task<?> update = createDependencyManager(repository).updateInstanceAsync(id, workingInstance ->
                Task.supplyAsync(() -> workingInstance.getManifest().withId(otherId)));
        assertFalse(update.executor().test());

        assertEquals("original.Main", repository.getInstance(id).getManifest().mainClass());
        assertFalse(repository.hasInstance(otherId));
        try (DefaultGameRepositoryDraft ignored = repository.openDraft()) {
            assertTrue(ignored.isOpen());
        }
    }

    /// Releases the draft when the target disappears before the updater is invoked.
    @Test
    public void testUpdateInstanceAsyncAbortsDraftWhenTargetIsMissing(@TempDir Path tempDirectory) throws Exception {
        TestRepository repository = new TestRepository(tempDirectory);
        GameInstanceID id = new GameInstanceID("missing");

        Task<?> update = createDependencyManager(repository).updateInstanceAsync(id, workingInstance ->
                Task.completed(workingInstance.getManifest()));
        assertFalse(update.executor().test());

        try (DefaultGameRepositoryDraft ignored = repository.openDraft()) {
            assertTrue(ignored.isOpen());
        }
    }

    /// Releases the draft when the updater throws before returning its task.
    @Test
    public void testUpdateInstanceAsyncAbortsDraftWhenUpdaterThrows(@TempDir Path tempDirectory) throws Exception {
        TestRepository repository = new TestRepository(tempDirectory);
        GameInstanceID id = new GameInstanceID("instance");
        repository.save(new GameInstanceManifest(id));

        Task<?> update = createDependencyManager(repository).updateInstanceAsync(id, workingInstance -> {
            throw new IOException("Simulated updater failure");
        });
        assertFalse(update.executor().test());

        try (DefaultGameRepositoryDraft ignored = repository.openDraft()) {
            assertTrue(ignored.isOpen());
        }
    }

    /// Creates a dependency manager with a cache isolated inside the test repository.
    private static DefaultDependencyManager createDependencyManager(TestRepository repository) {
        DefaultCacheRepository cacheRepository =
                new DefaultCacheRepository(repository.getBaseDirectory().resolve("cache"));
        return new DefaultDependencyManager(repository, new TestDownloadProvider(cacheRepository), cacheRepository);
    }

    /// Minimal repository implementation for draft tests.
    @NotNullByDefault
    private static class TestRepository extends DefaultGameRepository {

        /// Creates a test repository rooted at `baseDirectory`.
        ///
        /// @param baseDirectory the repository base directory
        private TestRepository(Path baseDirectory) {
            super(baseDirectory);
        }

        /// {@inheritDoc}
        @Override
        protected DefaultGameRepositoryLayout createLayout(Path baseDirectory) {
            return new DefaultGameRepositoryLayout(baseDirectory);
        }

        /// {@inheritDoc}
        @Override
        protected TestGameInstance createInstance(
                DefaultGameRepositorySnapshot snapshot,
                GameInstanceID id,
                GameInstanceManifest manifest,
                @Nullable Path manifestFile) {
            return new TestGameInstance(snapshot, id, manifest, manifestFile);
        }
    }

    /// Repository that can fail draft publication before changing the published snapshot.
    @NotNullByDefault
    private static final class FailingRepository extends TestRepository {

        /// Whether the next draft publication should fail.
        private boolean failDraftPublish;

        /// Creates a failing test repository rooted at `baseDirectory`.
        ///
        /// @param baseDirectory the repository base directory
        private FailingRepository(Path baseDirectory) {
            super(baseDirectory);
        }

        /// {@inheritDoc}
        @Override
        void publishDraftSnapshot(
                DefaultGameRepositoryDraft draft,
                DefaultGameRepositorySnapshot newSnapshot) {
            if (failDraftPublish) {
                failDraftPublish = false;
                throw new IllegalStateException("Simulated publication failure");
            }
            super.publishDraftSnapshot(draft, newSnapshot);
        }
    }

    /// Minimal snapshot-bound instance implementation for draft tests.
    @NotNullByDefault
    private static final class TestGameInstance extends DefaultGameInstance {

        /// Creates a test instance.
        ///
        /// @param snapshot     the owning snapshot
        /// @param id           the instance id
        /// @param manifest     the stored manifest
        /// @param manifestFile the non-conventional manifest path, or `null`
        private TestGameInstance(
                DefaultGameRepositorySnapshot snapshot,
                GameInstanceID id,
                GameInstanceManifest manifest,
                @Nullable Path manifestFile) {
            super(snapshot, id, manifest, manifestFile);
        }

        /// Creates a test instance that may reuse compatible state.
        ///
        /// @param snapshot     the owning snapshot
        /// @param id           the instance id
        /// @param manifest     the stored manifest
        /// @param shareSession the prior snapshot member
        private TestGameInstance(
                DefaultGameRepositorySnapshot snapshot,
                GameInstanceID id,
                GameInstanceManifest manifest,
                TestGameInstance shareSession) {
            super(snapshot, id, manifest, shareSession);
        }

        /// {@inheritDoc}
        @Override
        protected TestGameInstance withNewSnapshot(DefaultGameRepositorySnapshot newSnapshot) {
            return new TestGameInstance(newSnapshot, id, manifest, this);
        }

        /// {@inheritDoc}
        @Override
        protected TestGameInstance withManifest(
                DefaultGameRepositorySnapshot newSnapshot,
                GameInstanceManifest manifest) {
            return new TestGameInstance(newSnapshot, id, manifest, this);
        }
    }
}
