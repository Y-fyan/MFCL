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

import com.google.gson.JsonParseException;
import org.jackhuang.hmcl.download.AbstractDependencyManager;
import org.jackhuang.hmcl.download.DownloadCandidates;
import org.jackhuang.hmcl.game.*;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jackhuang.hmcl.task.FetchTask;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.util.CacheRepository;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jackhuang.hmcl.util.gson.JsonUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 *
 * @author huangyuhui
 */
public final class GameAssetDownloadTask extends Task<Void> {
    
    private final AbstractDependencyManager dependencyManager;
    private final GameInstanceManifest manifest;
    private final AssetIndexInfo assetIndexInfo;
    private final Path assetIndexFile;
    private final boolean integrityCheck;
    private final List<Task<?>> dependents = new ArrayList<>(1);
    private final List<Task<?>> dependencies = new ArrayList<>();

    /// Constructor.
    ///
    /// @param dependencyManager the dependency manager that can provides [GameRepository]
    /// @param manifest the game version
    public GameAssetDownloadTask(AbstractDependencyManager dependencyManager, GameInstanceManifest manifest, boolean forceDownloadingIndex, boolean integrityCheck) {
        this.dependencyManager = dependencyManager;
        this.manifest = manifest;
        this.assetIndexInfo = this.manifest.getAssetIndex();
        GameRepository gameRepository = dependencyManager.getGameRepository();
        String assetId = assetIndexInfo.getId();
        this.assetIndexFile = gameRepository.getLayout().getAssetIndexFile(assetId);
        this.integrityCheck = integrityCheck;

        setStage("hmcl.install.assets");
        dependents.add(new GameAssetIndexDownloadTask(dependencyManager, this.manifest, forceDownloadingIndex));
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
    public void execute() throws Exception {
        GameRepository gameRepository = dependencyManager.getGameRepository();
        AtomicBoolean nativeProgressReported = new AtomicBoolean();
        BiConsumer<Long, Long> inspectionProgress = (completed, total) -> {
            if (total > 0) {
                nativeProgressReported.set(true);
                updateProgress(0.5 * completed / total);
            }
        };
        @Nullable @Unmodifiable List<NativeBackend.AssetInstallEntry> plan = tryNativeInstallPlan(
                gameRepository, assetIndexFile, integrityCheck, this::isCancelled, inspectionProgress);
        @Unmodifiable List<AssetObject> objects = plan == null ? loadAssetObjects(assetIndexFile, this::isCancelled)
                : plan.stream().map(NativeBackend.AssetInstallEntry::asset).toList();
        @Unmodifiable Map<Integer, NativeBackend.FileHashResult> observations = plan == null
                ? inspectAssetHashes(gameRepository, objects, integrityCheck, this::isCancelled, inspectionProgress) : Map.of();
        @Nullable String nativeExecutable = System.getProperty("mfcl.native.path");
        boolean batchEnabled = nativeExecutable != null && !nativeExecutable.isBlank()
                && Boolean.parseBoolean(System.getProperty("mfcl.native.download.enabled", "true"))
                && Boolean.parseBoolean(System.getProperty("mfcl.native.assets.batch.enabled", "true"));
        List<NativeDownloadBatchTask.Entry> batch = new ArrayList<>();
        Map<Path, NativeDownloadBatchTask.Entry> batchedFiles = new HashMap<>();
        int progress = 0;
        for (int index = 0; index < objects.size(); index++) {
            if (isCancelled()) throw new InterruptedException();
            AssetObject assetObject = objects.get(index);
            Path file = gameRepository.getLayout().getAssetObject(assetObject);
            @Nullable NativeBackend.AssetInstallEntry entry = plan == null ? null : plan.get(index);
            boolean download = entry == null ? shouldDownloadAsset(file, assetObject, integrityCheck, observations.get(index))
                    : shouldDownloadPlannedAsset(file, entry, integrityCheck);
            if (download) {
                DownloadCandidates candidates = dependencyManager.getDownloadProvider().getAssetObjectCandidates(assetObject);
                var task = new FileDownloadTask(candidates, file, new FileDownloadTask.IntegrityCheck("SHA-1", assetObject.hash()));
                task.setName(assetObject.hash());
                Path candidate = dependencyManager.getCacheRepository().getCommonDirectory()
                        .resolve("assets").resolve("objects").resolve(assetObject.getLocation());
                task.setCandidate(candidate);
                task.setCacheRepository(dependencyManager.getCacheRepository());
                task.setCaching(true);
                List<String> urls = new ArrayList<>();
                boolean eligible = batchEnabled && NativeDownloadBatchTask.isBatchSizeEligible(assetObject.size()) && DigestUtils.isSha1Digest(assetObject.hash())
                        && dependencyManager.getCacheRepository().getClass() == org.jackhuang.hmcl.download.DefaultCacheRepository.class;
                for (var source : candidates.getCandidates()) {
                    if (source.url() == null || source.connectTimeout() != null || source.readTimeout() != null
                            || source.retry() >= 0 || !FetchTask.isNativeSourceEligible(source.url())) { eligible = false; break; }
                    urls.add(source.url().href());
                }
                if (urls.size() > 8 || urls.isEmpty()) eligible = false;
                if (eligible && dependencyManager.getCacheRepository().mayHaveCachedFile(candidate, CacheRepository.SHA1, assetObject.hash())) eligible = false;
                if (eligible) {
                    var batchEntry = new NativeDownloadBatchTask.Entry(task, task, assetObject.hash(), List.copyOf(urls), () -> { });
                    @Nullable NativeDownloadBatchTask.Entry previous = batchedFiles.putIfAbsent(file.toAbsolutePath(), batchEntry);
                    if (previous == null) batch.add(batchEntry);
                    else if (!previous.sha1().equals(batchEntry.sha1()) || !previous.urls().equals(batchEntry.urls()))
                        dependencies.add(task.withCounter("hmcl.install.assets"));
                }
                else dependencies.add(task.withCounter("hmcl.install.assets"));
            } else {
                dependencyManager.getCacheRepository().tryCacheFile(file, CacheRepository.SHA1, assetObject.hash());
            }

            progress++;
            if (nativeProgressReported.get()) updateProgress(0.5 + 0.5 * progress / objects.size());
            else updateProgress(progress, objects.size());
        }

        int individualCount = dependencies.size();
        for (int start = 0; start < batch.size(); start += 16) {
            dependencies.add(new NativeDownloadBatchTask(Path.of(java.util.Objects.requireNonNull(nativeExecutable)),
                    batch.subList(start, Math.min(start + 16, batch.size())), "hmcl.install.assets"));
        }

        if (!dependencies.isEmpty()) {
            getProperties().put("total", individualCount + batch.size());
            notifyPropertiesChanged();
        }
    }

    /// Rechecks a native asset plan before deciding whether to download a file.
    /// @param file current asset path
    /// @param entry native planning entry
    /// @param integrityCheck whether checksums are required
    /// @return whether the asset needs downloading
    static boolean shouldDownloadPlannedAsset(Path file, NativeBackend.AssetInstallEntry entry, boolean integrityCheck) {
        if (entry.missing() && !Files.isRegularFile(file)) return true;
        return shouldDownloadAsset(file, entry.asset(), integrityCheck, entry.observation());
    }

    /// Attempts combined native index parsing and file inspection for the standard asset layout.
    /// @param repository game repository
    /// @param indexFile asset index path
    /// @param integrity whether existing files need checksum verification
    /// @param cancelled task cancellation predicate
    /// @param progress native inspection progress callback
    /// @return immutable native plan, or null when the Java workflow must be used
    /// @throws InterruptedException if cancelled
    static @Nullable @Unmodifiable List<NativeBackend.AssetInstallEntry> tryNativeInstallPlan(GameRepository repository,
            Path indexFile, boolean integrity, BooleanSupplier cancelled, BiConsumer<Long, Long> progress) throws InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank()
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.assets.enabled", "true"))
                || (integrity && !Boolean.parseBoolean(System.getProperty("mfcl.native.assets.hash.enabled", "true")))) return null;
        try {
            GameRepositoryLayout layout = repository.getLayout();
            if (!(layout instanceof DefaultGameRepositoryLayout)
                    || layout.getClass().getMethod("getAssetObject", AssetObject.class).getDeclaringClass() != DefaultGameRepositoryLayout.class
                    || layout.getAssetDirectory().getFileSystem() != FileSystems.getDefault()
                    || indexFile.getFileSystem() != FileSystems.getDefault()) return null;
            return NativeBackend.planAssetInstall(Path.of(executable), indexFile, layout.getAssetDirectory(), integrity, cancelled, progress);
        } catch (IOException | ReflectiveOperationException | RuntimeException e) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            LOG.warning("Native asset installation planning failed; using existing workflow");
            return null;
        }
    }

    /// Identifies one unique file and case-insensitive digest within an asset batch.
    ///
    /// @param path absolute file path
    /// @param normalizedHash lowercase SHA-1 digest
    private record AssetHashKey(Path path, String normalizedHash) { }

    /// Decides whether one asset needs downloading, revalidating any native observation first.
    ///
    /// @param file current asset object path
    /// @param asset expected asset descriptor
    /// @param integrityCheck whether content should be verified
    /// @param observation optional native hash observation
    /// @return whether the asset needs downloading
    static boolean shouldDownloadAsset(Path file, AssetObject asset, boolean integrityCheck,
                                       @Nullable NativeBackend.FileHashResult observation) {
        if (!Files.isRegularFile(file)) return true;
        if (!integrityCheck) return false;
        try {
            boolean useObservation = observation != null && asset.hash().equalsIgnoreCase(observation.expectedSha1())
                    && NativeBackend.isFileHashObservationCurrent(file, observation);
            return useObservation ? !observation.matches() : !asset.validateChecksum(file, true);
        } catch (IOException e) {
            LOG.warning("Unable to calc hash value of file " + file, e);
            return false;
        }
    }

    /// Inspects existing assets in bounded, deduplicated native batches.
    ///
    /// File identity is checked again before using an observation. Missing, unsupported, changed,
    /// unreadable or unprocessed entries retain the original Java verification path. Each chunk
    /// preserves successful results if a later chunk fails.
    ///
    /// @param repository game repository
    /// @param objects asset descriptors in installation order
    /// @param integrityCheck whether verification is enabled
    /// @param cancelled task cancellation predicate
    /// @param progress optional callback for completed and total unique checks
    /// @return immutable observations keyed by original asset index
    /// @throws InterruptedException if cancelled
    static @Unmodifiable Map<Integer, NativeBackend.FileHashResult> inspectAssetHashes(
            GameRepository repository, List<AssetObject> objects, boolean integrityCheck,
            BooleanSupplier cancelled, @Nullable BiConsumer<Long, Long> progress) throws InterruptedException {
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (!integrityCheck || executable == null || executable.isBlank()
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.assets.hash.enabled", "true"))) return Map.of();
        List<NativeBackend.FileHashInput> inputs = new ArrayList<>();
        Map<AssetHashKey, Integer> representatives = new HashMap<>();
        Map<Integer, Integer> duplicates = new HashMap<>();
        for (int index = 0; index < objects.size(); index++) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            try {
                AssetObject asset = objects.get(index);
                if (!DigestUtils.isSha1Digest(asset.hash())) continue;
                Path file = repository.getLayout().getAssetObject(asset);
                if (file.getFileSystem() != FileSystems.getDefault() || !Files.isRegularFile(file)) continue;
                AssetHashKey key = new AssetHashKey(file.toAbsolutePath(), asset.hash().toLowerCase(Locale.ROOT));
                @Nullable Integer representative = representatives.putIfAbsent(key, index);
                if (representative == null) inputs.add(new NativeBackend.FileHashInput(index, file, asset.hash()));
                else duplicates.put(index, representative);
            } catch (RuntimeException ignored) {
                // Preserve the original per-asset error handling for unusual metadata or layouts.
            }
        }
        if (inputs.isEmpty()) return Map.of();
        Map<Integer, NativeBackend.FileHashResult> results = new HashMap<>();
        final int batchSize = 500;
        for (int offset = 0; offset < inputs.size(); offset += batchSize) {
            if (cancelled.getAsBoolean()) throw new InterruptedException();
            int start = offset;
            int end = Math.min(offset + batchSize, inputs.size());
            @Nullable BiConsumer<Long, Long> batchProgress = progress == null ? null :
                    (completed, total) -> progress.accept(start + completed, (long) inputs.size());
            try {
                results.putAll(NativeBackend.checkFileHashes(Path.of(executable), inputs.subList(start, end), cancelled, batchProgress));
            } catch (IOException | RuntimeException e) {
                if (cancelled.getAsBoolean()) throw new InterruptedException();
                LOG.warning("Native asset hash inspection failed; using Java verification for remaining assets");
                break;
            }
        }
        duplicates.forEach((index, representative) -> {
            @Nullable NativeBackend.FileHashResult observation = results.get(representative);
            if (observation != null) results.put(index, observation);
        });
        return Map.copyOf(results);
    }

    /// Loads asset objects through the configured native backend with Java fallback.
    ///
    /// File checks and download task construction remain in the owning task; this method only
    /// parses the index. Unsupported indexes or backend failures use the existing Java parser.
    ///
    /// @param indexFile asset index path
    /// @param cancelled cancellation predicate
    /// @return asset objects, retaining duplicate logical entries
    /// @throws InterruptedException if cancelled
    /// @throws GameAssetIndexDownloadTask.GameAssetIndexMalformedException if the Java parser fails
    static List<AssetObject> loadAssetObjects(Path indexFile, BooleanSupplier cancelled)
            throws InterruptedException, GameAssetIndexDownloadTask.GameAssetIndexMalformedException {
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (cancelled.getAsBoolean()) throw new InterruptedException();
        if (executable != null && !executable.isBlank()
                && Boolean.parseBoolean(System.getProperty("mfcl.native.assets.enabled", "true"))) {
            try {
                return NativeBackend.planAssets(Path.of(executable), indexFile, cancelled);
            } catch (IOException | RuntimeException e) {
                if (cancelled.getAsBoolean()) throw new InterruptedException();
                LOG.warning("Native asset index parsing failed; using Java parser");
            }
        }
        try {
            AssetIndex index = JsonUtils.fromNonNullJson(Files.readString(indexFile), AssetIndex.class);
            return List.copyOf(index.getObjects().values());
        } catch (IOException | JsonParseException e) {
            throw new GameAssetIndexDownloadTask.GameAssetIndexMalformedException();
        }
    }

    public static final boolean DOWNLOAD_INDEX_FORCIBLY = true;
    public static final boolean DOWNLOAD_INDEX_IF_NECESSARY = false;
}
