/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.download.neoforge;

import org.jackhuang.hmcl.download.ArtifactMalformedException;
import org.jackhuang.hmcl.download.DefaultDependencyManager;
import org.jackhuang.hmcl.download.DownloadCandidates;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.download.forge.ForgeNewInstallProfile.Processor;
import org.jackhuang.hmcl.download.forge.ForgeNewInstallProfile;
import org.jackhuang.hmcl.download.forge.NativeProcessorLiterals;
import org.jackhuang.hmcl.download.forge.NativeProcessorHashes;
import org.jackhuang.hmcl.download.forge.NativeProcessorOptions;
import org.jackhuang.hmcl.download.forge.NativeProcessorCommand;
import org.jackhuang.hmcl.download.forge.NativeForgeProfiles;
import org.jackhuang.hmcl.download.game.GameInstanceJsonDownloadTask;
import org.jackhuang.hmcl.download.game.GameInstallTask;
import org.jackhuang.hmcl.download.game.GameLibrariesTask;
import org.jackhuang.hmcl.game.*;
import org.jackhuang.hmcl.java.JavaRuntime;
import org.jackhuang.hmcl.nativebridge.NativeProcessorRunner;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.function.ExceptionalFunction;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.ChecksumMismatchException;
import org.jackhuang.hmcl.util.io.CompressingUtils;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jackhuang.hmcl.util.platform.CommandBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.zip.ZipException;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

public class NeoForgeOldInstallTask extends Task<GameInstancePatch> {

    private class ProcessorTask extends Task<Void> {

        private final Processor processor;
        /// Resolved variables supplied to the processor; missing literal values may be null.
        private final Map<String, @Nullable String> vars;

        /// Creates a processor task using the resolved installer variables.
        /// @param processor processor to execute
        /// @param vars resolved variables
        public ProcessorTask(@NotNull Processor processor, @NotNull Map<String, @Nullable String> vars) {
            this.processor = processor;
            this.vars = vars;
            setSignificance(TaskSignificance.MODERATE);
        }

        /// Verifies existing outputs, prepares and runs the processor, then checks its outputs.
        @Override
        public void execute() throws Exception {
            Map<String, String> outputs = new HashMap<>();
            boolean miss = false;

            List<Map.Entry<String, String>> outputEntries = new ArrayList<>(processor.getOutputs().entrySet());
            List<String> outputLiterals = new ArrayList<>(outputEntries.size() * 2);
            for (var entry : outputEntries) { outputLiterals.add(entry.getKey()); outputLiterals.add(entry.getValue()); }
            @Nullable List<@Nullable String> nativeOutputs = outputLiterals.isEmpty() ? null :
                    NativeProcessorLiterals.tryExpand(outputLiterals, vars, gameRepository.getLayout(), this::isCancelled);
            var inspectedOutputs = NativeProcessorHashes.inspectLiterals(nativeOutputs, this::isCancelled);
            for (int i = 0; i < outputEntries.size(); i++) {
                var entry = outputEntries.get(i);
                @Nullable String key = nativeOutputs == null ? parseLiteral(entry.getKey(), vars) : nativeOutputs.get(i * 2);
                @Nullable String value = nativeOutputs == null ? parseLiteral(entry.getValue(), vars) : nativeOutputs.get(i * 2 + 1);

                if (key == null || value == null) {
                    throw new ArtifactMalformedException("Invalid forge installation configuration");
                }

                outputs.put(key, value);

                Path artifact = Paths.get(key);
                if (Files.exists(artifact)) {
                    String code;
                    if (NativeProcessorHashes.isVerified(artifact, value, inspectedOutputs.get(i))) code = value;
                    else try (InputStream stream = Files.newInputStream(artifact)) {
                        code = (DigestUtils.digestToString("SHA-1", stream));
                    }

                    if (!Objects.equals(code, value)) {
                        Files.delete(artifact);
                        LOG.info("Found existing file is not valid: " + artifact);

                        miss = true;
                    }
                } else {
                    miss = true;
                }
            }

            if (!processor.getOutputs().isEmpty() && !miss) {
                return;
            }

            Path jar = gameRepository.getLayout().getArtifactFile(processor.getJar());
            if (!Files.isRegularFile(jar))
                throw new FileNotFoundException("Game processor file not found, should be downloaded in preprocess");

            @Nullable NativeProcessorCommand.Prepared prepared = NativeProcessorCommand.tryCombined(jar, processor.getClasspath(),
                    gameRepository.getLayout(), processor.getArgs(), vars, this::isCancelled);
            @Nullable String mainClass = prepared == null ? NativeProcessorCommand.tryMainClass(jar, this::isCancelled) : prepared.mainClass();
            if (mainClass == null) {
                try (JarFile jarFile = new JarFile(jar.toFile())) {
                    mainClass = jarFile.getManifest().getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
                }
            }

            if (StringUtils.isBlank(mainClass))
                throw new Exception("Game processor jar does not have main class " + jar);

            List<String> command = new ArrayList<>();
            command.add(JavaRuntime.getDefault().getBinary().toString());
            command.add("-cp");

            @Nullable String nativeClasspath = prepared == null ? NativeProcessorCommand.tryClasspath(jar, processor.getClasspath(),
                    gameRepository.getLayout(), this::isCancelled) : prepared.classpath();
            if (nativeClasspath != null) command.add(nativeClasspath);
            else {
                List<String> classpath = new ArrayList<>(processor.getClasspath().size() + 1);
                for (Artifact artifact : processor.getClasspath()) {
                    Path file = gameRepository.getLayout().getArtifactFile(artifact);
                    if (!Files.isRegularFile(file))
                        throw new Exception("Game processor dependency missing");
                    classpath.add(file.toString());
                }
                classpath.add(jar.toString());
                command.add(String.join(File.pathSeparator, classpath));
            }

            command.add(mainClass);

            List<String> args = new ArrayList<>(processor.getArgs().size());
            @Nullable List<@Nullable String> nativeArgs = prepared != null ? prepared.arguments() : null;
            if (nativeArgs == null && !processor.getArgs().isEmpty())
                nativeArgs = NativeProcessorLiterals.tryExpand(processor.getArgs(), vars, gameRepository.getLayout(), this::isCancelled);
            for (int i = 0; i < processor.getArgs().size(); i++) {
                @Nullable String parsed = nativeArgs == null ? parseLiteral(processor.getArgs().get(i), vars) : nativeArgs.get(i);
                if (parsed == null)
                    throw new ArtifactMalformedException("Invalid forge installation configuration");
                args.add(parsed);
            }

            command.addAll(args);

            LOG.info("Executing external processor " + processor.getJar().toString() + ", command line: " + new CommandBuilder().addAll(command).toString());
            int exitCode = NativeProcessorRunner.run(command);
            if (exitCode != 0)
                throw new IOException("Game processor exited abnormally with code " + exitCode);

            List<Map.Entry<String, String>> completedOutputs = new ArrayList<>(outputs.entrySet());
            var inspectedCompleted = NativeProcessorHashes.inspectEntries(completedOutputs, this::isCancelled);
            for (int i = 0; i < completedOutputs.size(); i++) {
                var entry = completedOutputs.get(i);
                Path artifact = Paths.get(entry.getKey());
                if (!Files.isRegularFile(artifact))
                    throw new FileNotFoundException("File missing: " + artifact);

                String code;
                if (NativeProcessorHashes.isVerified(artifact, entry.getValue(), inspectedCompleted.get(i))) code = entry.getValue();
                else try (InputStream stream = Files.newInputStream(artifact)) {
                    code = DigestUtils.digestToString("SHA-1", stream);
                }

                if (!Objects.equals(code, entry.getValue())) {
                    Files.delete(artifact);
                    throw new ChecksumMismatchException("SHA-1", entry.getValue(), code);
                }
            }
        }
    }

    private final DefaultDependencyManager dependencyManager;
    private final DefaultGameRepository gameRepository;
    private final GameInstanceManifest manifest;
    /// Source vanilla client JAR copied before processors are invoked.
    private final Path minecraftJar;
    private final Path installer;
    private final List<Task<?>> dependents = new ArrayList<>(1);
    private final List<Task<?>> dependencies = new ArrayList<>(1);

    private ForgeNewInstallProfile profile;
    private List<Processor> processors;
    private GameInstanceManifest neoForgeVersion;
    private final String selfVersion;

    private Path tempDir;
    private AtomicInteger processorDoneCount = new AtomicInteger(0);

    /// Creates a legacy NeoForge processor installation task.
    ///
    /// @param dependencyManager repository-scoped download services
    /// @param manifest          working manifest receiving the NeoForge patch
    /// @param minecraftJar      source vanilla client JAR copied for processor use
    /// @param selfVersion       NeoForge version recorded in the returned patch
    /// @param installer         NeoForge installer JAR
    NeoForgeOldInstallTask(
            DefaultDependencyManager dependencyManager,
            GameInstanceManifest manifest,
            Path minecraftJar,
            String selfVersion,
            Path installer) {
        this.dependencyManager = dependencyManager;
        this.gameRepository = dependencyManager.getGameRepository();
        this.manifest = manifest;
        this.minecraftJar = minecraftJar;
        this.installer = installer;
        this.selfVersion = selfVersion;

        setSignificance(TaskSignificance.MAJOR);
    }

    private static String replaceTokens(Map<String, String> tokens, String value) {
        StringBuilder buf = new StringBuilder();
        for (int x = 0; x < value.length(); x++) {
            char c = value.charAt(x);
            if (c == '\\') {
                if (x == value.length() - 1)
                    throw new IllegalArgumentException("Illegal pattern (Bad escape): " + value);
                buf.append(value.charAt(++x));
            } else if (c == '{' || c == '\'') {
                StringBuilder key = new StringBuilder();
                for (int y = x + 1; y <= value.length(); y++) {
                    if (y == value.length())
                        throw new IllegalArgumentException("Illegal pattern (Unclosed " + c + "): " + value);
                    char d = value.charAt(y);
                    if (d == '\\') {
                        if (y == value.length() - 1)
                            throw new IllegalArgumentException("Illegal pattern (Bad escape): " + value);
                        key.append(value.charAt(++y));
                    } else {
                        if (c == '{' && d == '}') {
                            x = y;
                            break;
                        }
                        if (c == '\'' && d == '\'') {
                            x = y;
                            break;
                        }
                        key.append(d);
                    }
                }
                if (c == '\'') {
                    buf.append(key);
                } else {
                    if (!tokens.containsKey(key.toString()))
                        throw new IllegalArgumentException("Illegal pattern: " + value + " Missing Key: " + key);
                    buf.append(tokens.get(key.toString()));
                }
            } else {
                buf.append(c);
            }
        }
        return buf.toString();
    }

    private <E extends Exception> String parseLiteral(String literal, Map<String, String> var, ExceptionalFunction<String, String, E> plainConverter) throws E {
        if (StringUtils.isSurrounded(literal, "{", "}"))
            return var.get(StringUtils.removeSurrounding(literal, "{", "}"));
        else if (StringUtils.isSurrounded(literal, "'", "'"))
            return StringUtils.removeSurrounding(literal, "'");
        else if (StringUtils.isSurrounded(literal, "[", "]"))
            return gameRepository.getLayout().getArtifactFile(Artifact.fromDescriptor(StringUtils.removeSurrounding(literal, "[", "]"))).toString();
        else
            return plainConverter.apply(replaceTokens(var, literal));
    }

    private String parseLiteral(String literal, Map<String, String> var) {
        return parseLiteral(literal, var, ExceptionalFunction.identity());
    }

    @Override
    public Collection<Task<?>> getDependents() {
        return dependents;
    }

    @Override
    public Collection<Task<?>> getDependencies() {
        return dependencies;
    }

    @Override
    public boolean doPreExecute() {
        return true;
    }

    @Override
    public void preExecute() throws Exception {
        @Nullable NativeForgeProfiles.Parsed nativeProfiles = NativeForgeProfiles.tryRead(installer, this::isCancelled);
        try (FileSystem fs = CompressingUtils.createReadOnlyZipFileSystem(installer)) {
            if (nativeProfiles != null && org.jackhuang.hmcl.nativebridge.NativeBackend.isFileHashObservationCurrent(installer, nativeProfiles.observation())) {
                profile = nativeProfiles.profile(); neoForgeVersion = nativeProfiles.version();
            } else {
                profile = JsonUtils.fromNonNullJson(Files.readString(fs.getPath("install_profile.json")), ForgeNewInstallProfile.class);
                neoForgeVersion = JsonUtils.fromNonNullJson(Files.readString(fs.getPath(profile.getJson())), GameInstanceManifest.class);
            }
            processors = profile.getProcessors();

            if (!NativeForgeProfiles.tryCopyEmbedded(installer, fs, profile, manifest.id(), gameRepository.getLayout(), this::isCancelled)) {
                for (Library library : profile.getLibraries()) {
                    Path file = fs.getPath("maven").resolve(library.getPath());
                    if (Files.exists(file)) {
                        Path dest = gameRepository.getLayout().getLibraryFile(manifest.id(), library);
                        FileUtils.copyFile(file, dest);
                    }
                }
                if (profile.getPath().isPresent()) {
                    Path mainJar = profile.getPath().get().getPath(fs.getPath("maven"));
                    if (Files.exists(mainJar)) {
                        Path dest = gameRepository.getLayout().getArtifactFile(profile.getPath().get());
                        FileUtils.copyFile(mainJar, dest);
                    }
                }
            }
        } catch (ZipException ex) {
            throw new ArtifactMalformedException("Malformed forge installer file", ex);
        }

        dependents.add(new GameLibrariesTask(dependencyManager, manifest, true, profile.getLibraries()));
    }

    /// Parses processor option names and resolved values using the original Java rules.
    /// @param args processor arguments
    /// @param vars resolved variables, which may contain null values
    /// @return insertion-ordered option map
    private Map<String, @Nullable String> parseOptions(List<String> args, Map<String, @Nullable String> vars) {
        Map<String, @Nullable String> options = new LinkedHashMap<>();
        @Nullable String optionName = null;
        for (String arg : args) {
            if (arg.startsWith("--")) {
                if (optionName != null) {
                    options.put(optionName, "");
                }
                optionName = arg.substring(2);
            } else {
                if (optionName == null) {
                    // ignore
                } else {
                    options.put(optionName, parseLiteral(arg, vars));
                    optionName = null;
                }
            }
        }
        if (optionName != null) {
            options.put(optionName, "");
        }
        return options;
    }

    /// Creates a direct mappings task when this processor requests Mojang client mappings.
    /// @param processor installer processor
    /// @param vars resolved processor variables
    /// @param planned native option map, or null to use the Java parser
    /// @return mappings download task, or null for ordinary processors
    private @Nullable Task<?> patchDownloadMojangMappingsTask(Processor processor, Map<String, @Nullable String> vars,
            @Nullable Map<String, @Nullable String> planned) {
        Map<String, @Nullable String> options = planned == null ? parseOptions(processor.getArgs(), vars) : planned;
        if (!"DOWNLOAD_MOJMAPS".equals(options.get("task")) || !"client".equals(options.get("side")))
            return null;
        @Nullable String version = options.get("version");
        @Nullable String output = options.get("output");
        if (version == null || output == null)
            return null;

        LOG.info("Patching DOWNLOAD_MOJMAPS task");
        return new GameInstanceJsonDownloadTask(version, dependencyManager)
                .thenComposeAsync(json -> {
                    DownloadInfo mappings = GameInstallTask.readClientMappings(json, version);
                    if (mappings == null) {
                        throw new Exception("client_mappings download info not found");
                    }

                    DownloadProvider downloadProvider = dependencyManager.getDownloadProvider();
                    DownloadCandidates mappingsUri = downloadProvider.getDownloadCandidates(mappings.getUrl());
                    var mappingsTask = new FileDownloadTask(
                            mappingsUri,
                            Path.of(output),
                            FileDownloadTask.IntegrityCheck.of("SHA-1", mappings.getSha1()));
                    mappingsTask.setCaching(true);
                    mappingsTask.setCacheRepository(dependencyManager.getCacheRepository());
                    return mappingsTask;
                });
    }

    /// Creates one sequential installer task using a native option plan when available.
    /// @param processor installer processor
    /// @param vars resolved processor variables
    /// @param planned native option map, or null for Java planning
    /// @return processor or mappings task
    private Task<?> createProcessorTask(Processor processor, Map<String, @Nullable String> vars,
            @Nullable Map<String, @Nullable String> planned) {
        @Nullable Task<?> task = patchDownloadMojangMappingsTask(processor, vars, planned);
        if (task == null) {
            task = new ProcessorTask(processor, vars);
        }
        task.onDone().register(
                () -> updateProgress(processorDoneCount.incrementAndGet(), processors.size()));
        return task;
    }

    @Override
    public void execute() throws Exception {
        if (!Files.isRegularFile(minecraftJar)) {
            throw new FileNotFoundException("Minecraft client JAR not found: " + minecraftJar);
        }
        tempDir = Files.createTempDirectory("neoforge_installer");
        // External processors must not receive the shared cache path.
        Path isolatedMinecraftJar = tempDir.resolve("minecraft.jar");
        FileUtils.copyFile(minecraftJar, isolatedMinecraftJar);

        Map<String, @Nullable String> vars = new HashMap<>();

        try (FileSystem fs = CompressingUtils.createReadOnlyZipFileSystem(installer)) {
            List<Map.Entry<String, String>> data = new ArrayList<>(profile.getData().entrySet());
            @Nullable List<@Nullable String> expanded = data.isEmpty() ? null : NativeProcessorLiterals.tryExpand(
                    data.stream().map(Map.Entry::getValue).toList(), Collections.emptyMap(), gameRepository.getLayout(), this::isCancelled);
            @Nullable List<String> stagedData = null;
            if (expanded != null) {
                List<String> paths = new ArrayList<>(); boolean eligible = true;
                for (int i = 0; i < data.size(); i++) {
                    String value = data.get(i).getValue();
                    boolean structured = StringUtils.isSurrounded(value, "{", "}") || StringUtils.isSurrounded(value, "'", "'") || StringUtils.isSurrounded(value, "[", "]");
                    if (!structured) {
                        @Nullable String path = expanded.get(i);
                        if (path == null) { eligible = false; break; }
                        paths.add(path);
                    }
                }
                if (eligible && !paths.isEmpty()) stagedData = NativeForgeProfiles.tryExtractData(installer, fs, paths, tempDir, this::isCancelled);
            }
            ExceptionalFunction<String, String, IOException> extract = str -> {
                Path dest = Files.createTempFile(tempDir, null, null);
                FileUtils.copyFile(fs.getPath(str), dest);
                return dest.toString();
            };
            int stagedIndex = 0;
            for (int i = 0; i < data.size(); i++) {
                var entry = data.get(i);
                String value = entry.getValue();
                boolean structured = StringUtils.isSurrounded(value, "{", "}")
                        || StringUtils.isSurrounded(value, "'", "'") || StringUtils.isSurrounded(value, "[", "]");
                @Nullable String parsed = expanded == null ? parseLiteral(value, Collections.emptyMap(), extract) : expanded.get(i);
                if (expanded != null && !structured)
                    parsed = parsed == null ? parseLiteral(value, Collections.emptyMap(), extract)
                            : stagedData == null ? extract.apply(parsed) : stagedData.get(stagedIndex++);
                vars.put(entry.getKey(), parsed);
            }
        } catch (ZipException ex) {
            throw new ArtifactMalformedException("Malformed neoforge installer file", ex);
        }

        vars.put("SIDE", "client");
        vars.put("MINECRAFT_JAR", FileUtils.getAbsolutePath(isolatedMinecraftJar));
        vars.put("MINECRAFT_VERSION", profile.getMinecraft());
        vars.put("ROOT", FileUtils.getAbsolutePath(gameRepository.getBaseDirectory()));
        vars.put("INSTALLER", installer.toAbsolutePath().toString());
        vars.put("LIBRARY_DIR", FileUtils.getAbsolutePath(gameRepository.getLayout().getLibrariesDirectory()));

        updateProgress(0, processors.size());

        @Nullable @Unmodifiable List<@Unmodifiable Map<String, @Nullable String>> plans = processors.isEmpty() ? null :
                NativeProcessorOptions.tryPlan(processors, vars, gameRepository.getLayout(), this::isCancelled);
        List<Task<?>> tasks = new ArrayList<>(processors.size());
        for (int i = 0; i < processors.size(); i++)
            tasks.add(createProcessorTask(processors.get(i), vars, plans == null ? null : plans.get(i)));
        Task<?> processorsTask = Task.runSequentially(tasks.toArray(Task<?>[]::new));

        dependencies.add(
                processorsTask.thenComposeAsync(
                        dependencyManager.checkComponentCompletionAsync(neoForgeVersion, true)));

        setResult(GameInstancePatch.fromManifest(
                neoForgeVersion,
                GameComponentType.NEO_FORGE.getPatchId(),
                selfVersion,
                GameInstancePatch.PRIORITY_LOADER));
    }

    @Override
    public boolean doPostExecute() {
        return true;
    }

    @Override
    public void postExecute() throws Exception {
        FileUtils.deleteDirectory(tempDir);
    }
}
