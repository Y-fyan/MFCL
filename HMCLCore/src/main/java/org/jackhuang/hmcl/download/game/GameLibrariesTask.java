/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.download.game;

import org.jackhuang.hmcl.download.AbstractDependencyManager;
import org.jackhuang.hmcl.download.DownloadCandidates;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.game.*;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jackhuang.hmcl.task.FetchTask;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jetbrains.annotations.Unmodifiable;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jackhuang.hmcl.util.io.CompressingUtils;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jackhuang.hmcl.util.versioning.GameVersionNumber;
import org.jackhuang.hmcl.util.versioning.VersionNumber;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Objects;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * This task is to download game libraries.
 * This task should be executed last(especially after game downloading, Forge, LiteLoader and OptiFine install task).
 *
 * @author huangyuhui
 */
public final class GameLibrariesTask extends Task<Void> {

    private final AbstractDependencyManager dependencyManager;
    private final GameInstanceManifest manifest;
    private final boolean integrityCheck;
    private final List<Library> libraries;
    private final List<Task<?>> dependencies = new ArrayList<>();

    /**
     * Constructor.
     *
     * @param dependencyManager the dependency manager that can provides {@link GameRepository}
     * @param manifest          the game version
     */
    public GameLibrariesTask(AbstractDependencyManager dependencyManager, GameInstanceManifest manifest, boolean integrityCheck) {
        this(dependencyManager, manifest, integrityCheck, manifest.getLibraries());
    }

    /**
     * Constructor.
     *
     * @param dependencyManager the dependency manager that can provides {@link GameRepository}
     * @param manifest          the game version
     */
    public GameLibrariesTask(AbstractDependencyManager dependencyManager, GameInstanceManifest manifest, boolean integrityCheck, List<Library> libraries) {
        this.dependencyManager = dependencyManager;
        this.manifest = manifest;
        this.integrityCheck = integrityCheck;
        this.libraries = libraries;

        setStage("hmcl.install.libraries");
        setSignificance(TaskSignificance.MODERATE);
    }

    @Override
    public List<Task<?>> getDependencies() {
        return dependencies;
    }

    public static boolean shouldDownloadLibrary(GameRepository gameRepository, GameInstanceManifest manifest, Library library, boolean integrityCheck) {
        return shouldDownloadLibrary(gameRepository, manifest, library, integrityCheck, null);
    }

    /// Checks one library using a native SHA-1 observation only when its file identity is unchanged.
    ///
    /// Additional Forge checksums and ZIP structure remain validated by the existing Java logic.
    ///
    /// @param gameRepository owning repository
    /// @param manifest resolved game manifest
    /// @param library library to inspect
    /// @param integrityCheck whether checksums and JAR structure should be checked
    /// @param observation optional native hash observation
    /// @return whether the library should be downloaded
    static boolean shouldDownloadLibrary(GameRepository gameRepository, GameInstanceManifest manifest, Library library,
                                         boolean integrityCheck, @Nullable NativeBackend.FileHashResult observation) {
        Path file = gameRepository.getLayout().getLibraryFile(manifest.id(), library);
        if (!Files.isRegularFile(file)) return true;

        if (!integrityCheck) {
            return false;
        }
        try {
            @Nullable String expectedSha1 = library.getDownload().getSha1();
            boolean useObservation = observation != null && expectedSha1 != null
                    && expectedSha1.equalsIgnoreCase(observation.expectedSha1())
                    && NativeBackend.isFileHashObservationCurrent(file, observation);
            if (useObservation ? !observation.matches() : !library.getDownload().validateChecksum(file, true)) {
                return true;
            }
            if (library.checksums() != null) {
                if (!library.checksums().isEmpty()) {
                    if (!LibraryDownloadTask.checksumValid(file, library.checksums())) {
                        return true;
                    }
                }
            }
            if (FileUtils.getExtension(file).equals("jar")) {
                try {
                    FileDownloadTask.ZIP_INTEGRITY_CHECK_HANDLER.checkIntegrity(file, file);
                } catch (IOException ignored) {
                    // the Jar file is malformed, so re-download it.
                    return true;
                }
            }
        } catch (IOException e) {
            LOG.warning("Unable to calc hash value of file " + file, e);
        }

        return false;
    }

    /// Prepares native SHA-1 observations for ordinary existing libraries.
    ///
    /// OptiFine and bundled bootstrap files are excluded because the installation loop may modify
    /// them. Forge-style additional checksums are checked separately. Any unsupported path,
    /// unstable file, backend error or oversized batch retains the Java verification path.
    ///
    /// @param repository game repository
    /// @param manifest resolved game manifest
    /// @param libraries library entries in installation order
    /// @param integrityCheck whether verification is enabled
    /// @param cancelled task cancellation predicate
    /// @return immutable map keyed by library index
    /// @throws InterruptedException if cancelled
    static @Unmodifiable Map<Integer, NativeBackend.FileHashResult> inspectLibraryHashes(
            GameRepository repository, GameInstanceManifest manifest, List<Library> libraries,
            boolean integrityCheck, BooleanSupplier cancelled) throws InterruptedException {
        return inspectLibraryHashes(repository, manifest, libraries, integrityCheck, cancelled, null);
    }

    /// Inspects ordinary libraries and optionally reports native hash-batch progress.
    ///
    /// @param repository game repository
    /// @param manifest resolved game manifest
    /// @param libraries library entries in installation order
    /// @param integrityCheck whether verification is enabled
    /// @param cancelled task cancellation predicate
    /// @param progress optional callback receiving completed and total native checks
    /// @return immutable map keyed by library index
    /// @throws InterruptedException if cancelled
    static @Unmodifiable Map<Integer, NativeBackend.FileHashResult> inspectLibraryHashes(
            GameRepository repository, GameInstanceManifest manifest, List<Library> libraries,
            boolean integrityCheck, BooleanSupplier cancelled, @Nullable BiConsumer<Long, Long> progress) throws InterruptedException {
        return inspectLibraryHashes(repository, manifest, libraries, integrityCheck, cancelled, progress, null);
    }

    /// Inspects library files with an optional precomputed native rule selection.
    /// @param repository game repository
    /// @param manifest resolved game manifest
    /// @param libraries library entries in installation order
    /// @param integrityCheck whether verification is enabled
    /// @param cancelled task cancellation predicate
    /// @param progress optional native hash progress callback
    /// @param applicability optional rule-selection flags
    /// @return immutable map keyed by library index
    /// @throws InterruptedException if cancelled
    private static @Unmodifiable Map<Integer, NativeBackend.FileHashResult> inspectLibraryHashes(
            GameRepository repository, GameInstanceManifest manifest, List<Library> libraries, boolean integrityCheck,
            BooleanSupplier cancelled, @Nullable BiConsumer<Long, Long> progress, boolean @Nullable [] applicability) throws InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (!integrityCheck || executable == null || executable.isBlank()
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.libraries.enabled", "true"))) return Map.of();
        List<NativeBackend.FileHashInput> inputs = new ArrayList<>();
        for (int i = 0; i < libraries.size(); i++) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            try {
                Library library = libraries.get(i);
                if (!(applicability == null ? library.appliesToCurrentEnvironment() : applicability[i]) || "optifine".equals(library.groupId())
                        || library.is("org.jackhuang.hmcl", "mmc-bootstrap")
                        || library.is("org.jackhuang.hmcl", "transformer-discovery-service")) continue;
                @Nullable String sha1 = library.getDownload().getSha1();
                if (!DigestUtils.isSha1Digest(sha1)) continue;
                Path file = repository.getLayout().getLibraryFile(manifest.id(), library);
                if (file.getFileSystem() != FileSystems.getDefault() || !Files.isRegularFile(file)) continue;
                inputs.add(new NativeBackend.FileHashInput(i, file, sha1));
            } catch (RuntimeException ignored) {
                // Preserve the original per-library error handling for unusual metadata or layouts.
            }
        }
        if (inputs.isEmpty()) return Map.of();
        try {
            return NativeBackend.checkFileHashes(Path.of(executable), inputs, cancelled, progress);
        } catch (IOException | RuntimeException e) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            LOG.warning("Native library hash inspection failed; using Java verification");
            return Map.of();
        }
    }

    /// Selects applicable libraries in Rust or retains the original Java checks.
    /// @param libraries libraries in installation order
    /// @param cancelled owning task cancellation predicate
    /// @return native flags, or null when the Java path should be used
    /// @throws InterruptedException when cancelled
    static boolean @Nullable [] selectLibraryApplicability(List<Library> libraries, BooleanSupplier cancelled) throws InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank()
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.libraries.enabled", "true"))) return null;
        try {
            boolean[] selected = NativeBackend.selectLibraryApplicability(Path.of(executable), libraries, cancelled);
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            return selected;
        } catch (IOException | RuntimeException e) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            return null;
        }
    }

    private static boolean shouldDownloadFMLLib(FMLLib fmlLib, Path file) {
        if (!Files.isRegularFile(file))
            return true;

        try {
            return !DigestUtils.digestToString("SHA-1", file).equalsIgnoreCase(fmlLib.sha1);
        } catch (IOException e) {
            LOG.warning("Unable to calc hash value of file " + file, e);
            return true;
        }
    }

    /// {@inheritDoc}
    @Override
    public void execute() throws IOException, InterruptedException {
        int progress = 0;
        @Nullable String nativeExecutable = System.getProperty("mfcl.native.path");
        boolean batchEnabled = nativeExecutable != null && !nativeExecutable.isBlank()
                && Boolean.parseBoolean(System.getProperty("mfcl.native.download.enabled", "true"))
                && Boolean.parseBoolean(System.getProperty("mfcl.native.libraries.batch.enabled", "true"));
        List<NativeDownloadBatchTask.Entry> batch = new ArrayList<>();
        GameRepository gameRepository = dependencyManager.getGameRepository();
        boolean @Nullable [] applicability = selectLibraryApplicability(libraries, this::isCancelled);
        AtomicBoolean nativeProgressReported = new AtomicBoolean();
        Map<Integer, NativeBackend.FileHashResult> observations = inspectLibraryHashes(
                gameRepository, manifest, libraries, integrityCheck, this::isCancelled, (completed, total) -> {
                    if (total > 0) {
                        nativeProgressReported.set(true);
                        updateProgress(0.5 * completed / total);
                    }
                }, applicability);
        for (int libraryIndex = 0; libraryIndex < libraries.size(); libraryIndex++) {
            if (isCancelled()) throw new InterruptedException();
            Library library = libraries.get(libraryIndex);
            if (!(applicability == null ? library.appliesToCurrentEnvironment() : applicability[libraryIndex])) {
                continue;
            }

            // https://github.com/HMCL-dev/HMCL/issues/3975
            if (library.is("net.minecraftforge", "minecraftforge")
                    && gameRepository instanceof DefaultGameRepository defaultGameRepository) {
                List<FMLLib> fmlLibs = getFMLLibs(library.version());
                if (fmlLibs != null) {
                    Path libDir = defaultGameRepository.getBaseDirectory().resolve("lib")
                            .toAbsolutePath().normalize();

                    for (FMLLib fmlLib : fmlLibs) {
                        Path file = libDir.resolve(fmlLib.name);
                        if (shouldDownloadFMLLib(fmlLib, file)) {
                            DownloadProvider downloadProvider = dependencyManager.getDownloadProvider();
                            DownloadCandidates urls = downloadProvider.getDownloadCandidates(fmlLib.downloadUrl());
                            dependencies.add(new FileDownloadTask(urls, file)
                                    .withCounter("hmcl.install.libraries"));
                        }
                    }
                }
            }

            Path file = gameRepository.getLayout().getLibraryFile(manifest.id(), library);
            if ("optifine".equals(library.groupId()) && Files.exists(file)) {
                if (Files.exists(file) && libraries.stream().filter(it -> it.is("optifine", "OptiFine"))
                        .anyMatch(it -> it.version().startsWith("1.20.4_"))) {
                    @Nullable String forgeVersion = GameComponentAnalyzer.analyze(manifest, GameVersionNumber.asGameVersion("1.20.4"))
                            .getVersion(GameComponentType.FORGE);
                    if (forgeVersion != null && GameComponentAnalyzer.FORGE_OPTIFINE_BROKEN_RANGE.contains(VersionNumber.asVersion(forgeVersion))) {
                        try (FileSystem fs2 = CompressingUtils.createWritableZipFileSystem(file)) {
                            Files.deleteIfExists(fs2.getPath("/META-INF/mods.toml"));
                        } catch (IOException e) {
                            throw new IOException("Cannot fix optifine", e);
                        }
                    }
                }
            } else if (library.is("org.jackhuang.hmcl", "mmc-bootstrap")) {
                if (!Files.exists(file)) {
                    try (InputStream input = Objects.requireNonNull(
                            GameLibrariesTask.class.getResourceAsStream(
                                    "/assets/game/HMCLMultiMCBootstrap-1.0.jar"),
                            "Bundled HMCLMultiMCBootstrap is missing.")) {
                        Files.createDirectories(file.getParent());
                        Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } else if (library.is("org.jackhuang.hmcl", "transformer-discovery-service")) {
                try (InputStream input = Objects.requireNonNull(
                        GameLibrariesTask.class.getResourceAsStream(
                                "/assets/game/HMCLTransformerDiscoveryService-1.0.jar"),
                        "Bundled HMCLTransformerDiscoveryService is missing.")) {
                    Files.createDirectories(file.getParent());
                    Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            if (shouldDownloadLibrary(gameRepository, manifest, library, integrityCheck, observations.get(libraryIndex)) && (library.hasDownloadURL() || !"optifine".equals(library.groupId()))) {
                LibraryDownloadTask fallback = new LibraryDownloadTask(dependencyManager, file, library);
                @Nullable NativeDownloadBatchTask.Entry entry = batchEnabled ? batchLibrary(file, library, fallback) : null;
                if (entry == null) dependencies.add(fallback.withCounter("hmcl.install.libraries"));
                else batch.add(entry);
            } else {
                dependencyManager.getCacheRepository().tryCacheLibrary(library, file);
            }

            progress++;
            if (nativeProgressReported.get()) updateProgress(0.5 + 0.5 * progress / libraries.size());
            else updateProgress(progress, libraries.size());
        }

        int individualCount = dependencies.size();
        for (int start = 0; start < batch.size(); start += 16) {
            dependencies.add(new NativeDownloadBatchTask(Path.of(Objects.requireNonNull(nativeExecutable)),
                    batch.subList(start, Math.min(start + 16, batch.size())), "hmcl.install.libraries"));
        }
        if (!dependencies.isEmpty()) {
            getProperties().put("total", individualCount + batch.size());
            notifyPropertiesChanged();
        }
    }

    /// Selects ordinary libraries whose existing cache and validation behavior can be retained.
    private @Nullable NativeDownloadBatchTask.Entry batchLibrary(Path file, Library library, LibraryDownloadTask fallback) {
        if (library.is("net.minecraftforge", "forge") || "optifine".equals(library.groupId())
                || (library.checksums() != null && !library.checksums().isEmpty())) return null;
        try {
            @Nullable String sha1 = library.getDownload().getSha1();
            if (!NativeDownloadBatchTask.isBatchSizeEligible(library.getDownload().getSize())
                    || !DigestUtils.isSha1Digest(sha1) || dependencyManager.getCacheRepository().mayHaveLibrary(library)) return null;
            DownloadCandidates candidates = dependencyManager.getDownloadProvider().getDownloadCandidates(library.getDownload().getUrl());
            List<String> urls = new ArrayList<>();
            for (var source : candidates.getCandidates()) {
                if (source.url() == null || source.connectTimeout() != null || source.readTimeout() != null
                        || source.retry() >= 0 || !FetchTask.isNativeSourceEligible(source.url())) return null;
                urls.add(source.url().href());
            }
            if (urls.isEmpty() || urls.size() > 8) return null;
            FileDownloadTask transfer = new FileDownloadTask(candidates, file, new FileDownloadTask.IntegrityCheck("SHA-1", sha1));
            transfer.setCacheRepository(dependencyManager.getCacheRepository()); transfer.setCaching(true);
            transfer.addIntegrityCheckHandler(FileDownloadTask.ZIP_INTEGRITY_CHECK_HANDLER);
            return new NativeDownloadBatchTask.Entry(transfer, fallback, sha1, List.copyOf(urls), () -> {
                try { dependencyManager.getCacheRepository().cacheLibrary(library, file, false); }
                catch (IOException e) { LOG.warning("Failed to cache downloaded library " + library, e); }
            });
        } catch (RuntimeException e) { return null; }
    }

    private static @Nullable List<FMLLib> getFMLLibs(String forgeVersion) {
        if (forgeVersion == null)
            return null;

        // Minecraft 1.5.2
        if (forgeVersion.startsWith("7.8.1.")) {
            return List.of(
                    new FMLLib("argo-small-3.2.jar", "58912ea2858d168c50781f956fa5b59f0f7c6b51"),
                    new FMLLib("guava-14.0-rc3.jar", "931ae21fa8014c3ce686aaa621eae565fefb1a6a",
                            "https://repo1.maven.org/maven2/com/google/guava/guava/14.0-rc3/guava-14.0-rc3.jar"),
                    new FMLLib("asm-all-4.1.jar", "054986e962b88d8660ae4566475658469595ef58",
                            "https://repo1.maven.org/maven2/org/ow2/asm/asm-all/4.1/asm-all-4.1.jar"),
                    new FMLLib("bcprov-jdk15on-148.jar", "960dea7c9181ba0b17e8bab0c06a43f0a5f04e65",
                            "https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk15on/1.48/bcprov-jdk15on-1.48.jar"),
                    new FMLLib("deobfuscation_data_1.5.2.zip", "446e55cd986582c70fcf12cb27bc00114c5adfd9"),
                    new FMLLib("scala-library.jar", "458d046151ad179c85429ed7420ffb1eaf6ddf85")
            );
        }

        return null;
    }

    private record FMLLib(String name, String sha1, String downloadUrl) {
        FMLLib(String name, String sha1) {
            this(name, sha1, "https://hmcl.glavo.site/metadata/fmllibs/" + name);
        }
    }
}
