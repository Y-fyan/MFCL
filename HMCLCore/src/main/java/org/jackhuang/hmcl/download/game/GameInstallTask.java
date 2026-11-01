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

import org.jackhuang.hmcl.download.DefaultDependencyManager;
import org.jackhuang.hmcl.game.GameComponentType;
import org.jackhuang.hmcl.game.GameInstanceManifest;
import org.jackhuang.hmcl.game.GameInstancePatch;
import org.jackhuang.hmcl.game.DownloadInfo;
import org.jackhuang.hmcl.game.DownloadType;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.GetTask;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/// Downloads the base game component and returns its manifest patch without publishing it.
///
/// The vanilla client JAR is downloaded into shared cache storage; libraries and assets use their
/// repository-wide stores. The caller owns the working manifest and must stage the returned patch
/// in its repository draft.
@NotNullByDefault
public class GameInstallTask extends Task<GameInstancePatch> {

    /// Dependency manager used by the download tasks.
    private final DefaultDependencyManager dependencyManager;

    /// Working instance manifest that will receive the game patch.
    private final GameInstanceManifest manifest;

    /// Selected remote game version.
    private final GameRemoteVersion remote;

    /// Task that downloads the selected version's manifest JSON.
    private final Task<String> downloadTask;

    /// Downloads scheduled after the remote manifest has been decoded.
    private final List<Task<?>> dependencies = new ArrayList<>(1);

    /// Creates a base-game installation task.
    ///
    /// @param dependencyManager the dependency manager for the target repository
    /// @param manifest          the working instance manifest
    /// @param remoteVersion     the selected remote game version
    public GameInstallTask(DefaultDependencyManager dependencyManager, GameInstanceManifest manifest, GameRemoteVersion remoteVersion) {
        this.dependencyManager = dependencyManager;
        this.manifest = manifest;
        this.remote = remoteVersion;
        this.downloadTask = new GetTask(dependencyManager.getDownloadProvider().getDownloadCandidates(remoteVersion));
    }

    /// {@inheritDoc}
    @Override
    public Collection<Task<?>> getDependents() {
        return Collections.singleton(downloadTask);
    }

    /// {@inheritDoc}
    @Override
    public Collection<Task<?>> getDependencies() {
        return dependencies;
    }

    /// {@inheritDoc}
    @Override
    public boolean isRelyingOnDependencies() {
        return true;
    }

    /// Builds a base-game patch using Rust projection or the original Java parser.
    /// @param json downloaded game manifest
    /// @param version selected game version
    /// @return base-game patch
    /// @throws java.io.IOException if interrupted
    static GameInstancePatch createPatch(String json, String version) throws java.io.IOException {
        @Nullable GameInstancePatch nativePatch = NativeGameManifest.tryPatch(json, version);
        return nativePatch != null ? nativePatch : GameInstancePatch.fromManifest(
                JsonUtils.fromNonNullJson(json, GameInstanceManifest.class), GameComponentType.GAME.getPatchId(),
                version, GameInstancePatch.PRIORITY_MC).withJar(null);
    }

    /// Reads client mappings using the native base-game projection when supported.
    public static @Nullable DownloadInfo readClientMappings(String json, String version) throws java.io.IOException {
        @Nullable GameInstancePatch nativePatch = NativeGameManifest.tryPatch(json, version);
        if (nativePatch != null) return nativePatch.downloads() == null ? null : nativePatch.downloads().get(DownloadType.CLIENT_MAPPINGS);
        return JsonUtils.fromNonNullJson(json, GameInstanceManifest.class).getDownloads().get(DownloadType.CLIENT_MAPPINGS);
    }

    /// Decodes the downloaded game manifest and schedules its files without saving repository state.
    @Override
    public void execute() throws Exception {
        GameInstancePatch patch = createPatch(downloadTask.getResult(), remote.getGameVersion().toString());
        setResult(patch);

        GameInstanceManifest newManifest = new GameInstanceManifest(this.manifest.id()).addPatch(patch).reconstructByPatches();
        dependencies.add(Task.allOf(
                new GameDownloadTask(dependencyManager, newManifest),
                Task.allOf(
                        new GameAssetDownloadTask(dependencyManager, newManifest, GameAssetDownloadTask.DOWNLOAD_INDEX_FORCIBLY, true),
                        new GameLibrariesTask(dependencyManager, newManifest, true)
                )
        ));
    }

}
