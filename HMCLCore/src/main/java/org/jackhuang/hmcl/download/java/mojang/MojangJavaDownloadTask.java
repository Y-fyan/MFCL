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
package org.jackhuang.hmcl.download.java.mojang;

import org.jackhuang.hmcl.download.ArtifactMalformedException;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.game.DownloadInfo;
import org.jackhuang.hmcl.game.GameJavaVersion;
import org.jackhuang.hmcl.java.JavaInfo;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jackhuang.hmcl.task.GetTask;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.ChecksumMismatchException;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jackhuang.hmcl.util.platform.UnsupportedPlatformException;
import org.tukaani.xz.LZMAInputStream;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.*;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

public final class MojangJavaDownloadTask extends Task<MojangJavaDownloadTask.Result> {

    private static final String JAVA_LIST_URL = "https://piston-meta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

    private final DownloadProvider downloadProvider;
    private final Path target;
    private final Path tempDir;
    private final Task<MojangJavaRemoteFiles> javaDownloadsTask;
    private final List<Task<?>> dependencies = new ArrayList<>();

    private volatile MojangJavaDownloads.JavaDownload download;

    public MojangJavaDownloadTask(DownloadProvider downloadProvider, Path target, Path tempDir, GameJavaVersion javaVersion, String platform) {
        this.target = target;
        this.tempDir = tempDir;
        this.downloadProvider = downloadProvider;
        this.javaDownloadsTask = new GetTask(downloadProvider.getDownloadCandidates(JAVA_LIST_URL))
                .thenComposeAsync(javaDownloadsJson -> {
                    @Nullable MojangJavaDownloads.JavaDownload nativeDownload = NativeRuntimeList.trySelect(javaDownloadsJson, platform, javaVersion, this::isCancelled);
                    if (nativeDownload != null) {
                        this.download = nativeDownload;
                        return new GetTask(downloadProvider.getDownloadCandidates(nativeDownload.manifest().getUrl()));
                    }
                    MojangJavaDownloads allDownloads = JsonUtils.fromNonNullJson(javaDownloadsJson, MojangJavaDownloads.class);

                    Map<String, List<MojangJavaDownloads.JavaDownload>> osDownloads = allDownloads.downloads().get(platform);
                    if (osDownloads == null || !osDownloads.containsKey(javaVersion.component()))
                        throw new UnsupportedPlatformException("Unsupported platform: " + platform);
                    List<MojangJavaDownloads.JavaDownload> candidates = osDownloads.get(javaVersion.component());
                    for (MojangJavaDownloads.JavaDownload download : candidates) {
                        if (JavaInfo.parseVersion(download.version().name()) >= javaVersion.majorVersion()) {
                            this.download = download;
                            return new GetTask(downloadProvider.getDownloadCandidates(download.manifest().getUrl()));
                        }
                    }
                    throw new UnsupportedPlatformException("Candidates: " + JsonUtils.GSON.toJson(candidates));
                })
                .thenApplyAsync(javaDownloadJson -> {
                    @Nullable MojangJavaRemoteFiles nativeFiles = NativeRuntimeManifest.tryParse(javaDownloadJson, this::isCancelled);
                    return nativeFiles != null ? nativeFiles : JsonUtils.fromNonNullJson(javaDownloadJson, MojangJavaRemoteFiles.class);
                });
    }

    @Override
    public Collection<Task<?>> getDependents() {
        return Collections.singleton(javaDownloadsTask);
    }

    @Override
    public void execute() throws Exception {
        List<Map.Entry<String, MojangJavaRemoteFiles.Remote>> entries = new ArrayList<>(javaDownloadsTask.getResult().files().entrySet());
        RuntimePathValidator.validate(tempDir, entries, this::isCancelled);
        @Unmodifiable Map<Integer, NativeBackend.FileHashResult> observations = NativeRuntimeFileHashes.inspect(tempDir, entries, this::isCancelled);
        boolean nativeTreePrepared = NativeRuntimeTree.tryPrepare(tempDir, entries, this::isCancelled);
        for (int index = 0; index < entries.size(); index++) {
            Map.Entry<String, MojangJavaRemoteFiles.Remote> entry = entries.get(index);
            Path dest = tempDir.resolve(entry.getKey());
            if (entry.getValue() instanceof MojangJavaRemoteFiles.RemoteFile file) {
                // Use local file if it already exists
                try {
                    BasicFileAttributes localFileAttributes = Files.readAttributes(dest, BasicFileAttributes.class);
                    if (localFileAttributes.isRegularFile() && file.getDownloads().containsKey("raw")) {
                        DownloadInfo downloadInfo = file.getDownloads().get("raw");
                        if (localFileAttributes.size() == downloadInfo.getSize()) {
                            @Nullable NativeBackend.FileHashResult observation = observations.get(index);
                            verifyRuntimeFile(dest, downloadInfo, observation);
                            LOG.info("Skip existing file: " + dest);
                            continue;
                        }
                    }
                } catch (IOException ignored) {
                }

                if (file.getDownloads().containsKey("lzma")) {
                    DownloadInfo download = file.getDownloads().get("lzma");
                    @Nullable DownloadInfo raw = file.getDownloads().get("raw");

                    @Nullable String rawSha1;
                    if (raw != null && raw.getSha1() != null) {
                        rawSha1 = raw.getSha1();
                    } else {
                        rawSha1 = null;
                    }
                    int rawSize = raw == null ? 0 : raw.getSize();

                    Path tempFile = tempDir.resolve(entry.getKey() + ".lzma");
                    var task = new FileDownloadTask(downloadProvider.getDownloadCandidates(download.getUrl()), tempFile,
                            new FileDownloadTask.IntegrityCheck("SHA-1", download.getSha1()));
                    task.setName(entry.getKey());
                    dependencies.add(task.thenRunAsync(() -> {
                        Path decompressed = tempDir.resolve(entry.getKey() + ".tmp");
                        if (!NativeRuntimeLzma.tryDecompress(tempFile, decompressed, rawSha1, rawSize, this::isCancelled)) {
                            var digest = MessageDigest.getInstance("SHA-1");
                            try (var input = new DigestInputStream(new LZMAInputStream(Files.newInputStream(tempFile)), digest)) {
                                Files.copy(input, decompressed, StandardCopyOption.REPLACE_EXISTING);
                            } catch (IOException e) {
                                throw new ArtifactMalformedException("File " + entry.getKey() + " is malformed", e);
                            }

                            String actualSha1 = HexFormat.of().formatHex(digest.digest());
                            if (rawSha1 != null && !actualSha1.equalsIgnoreCase(rawSha1)) {
                                throw new ArtifactMalformedException("File " + entry.getKey() + " has incorrect SHA-1 hash: expected " + rawSha1 + ", got " + actualSha1);
                            }
                        }

                        try {
                            Files.deleteIfExists(tempFile);
                        } catch (IOException e) {
                            LOG.warning("Failed to delete temporary file: " + tempFile, e);
                        }

                        Files.move(decompressed, dest, StandardCopyOption.REPLACE_EXISTING);
                        if (file.isExecutable()) {
                            FileUtils.setExecutable(dest);
                        }
                    }));
                } else if (file.getDownloads().containsKey("raw")) {
                    DownloadInfo download = file.getDownloads().get("raw");
                    var task = new FileDownloadTask(downloadProvider.getDownloadCandidates(download.getUrl()), dest, new FileDownloadTask.IntegrityCheck("SHA-1", download.getSha1()));
                    task.setName(entry.getKey());
                    if (file.isExecutable()) {
                        dependencies.add(task.thenRunAsync(() -> FileUtils.setExecutable(dest)));
                    } else {
                        dependencies.add(task);
                    }
                } else {
                    continue;
                }
            } else if (entry.getValue() instanceof MojangJavaRemoteFiles.RemoteDirectory) {
                if (!nativeTreePrepared) Files.createDirectories(dest);
            } else if (entry.getValue() instanceof MojangJavaRemoteFiles.RemoteLink link) {
                if (!nativeTreePrepared) {
                    Files.deleteIfExists(dest);
                    Files.createSymbolicLink(dest, Paths.get(link.getTarget()));
                }
            }
        }
    }

    /// Verifies an existing runtime file using a current native observation or the Java checksum path.
    /// @param file existing runtime file
    /// @param download expected raw download metadata
    /// @param observation optional native checksum observation
    /// @throws IOException if the file cannot be read or does not match
    static void verifyRuntimeFile(Path file, DownloadInfo download, @Nullable NativeBackend.FileHashResult observation) throws IOException {
        if (observation != null && Objects.equals(download.getSha1(), observation.expectedSha1())
                && NativeBackend.isFileHashObservationCurrent(file, observation)) {
            if (!observation.matches()) throw new IOException("Native runtime checksum mismatch");
        } else {
            ChecksumMismatchException.verifyChecksum(file, "SHA-1", download.getSha1());
        }
    }

    @Override
    public List<Task<?>> getDependencies() {
        return dependencies;
    }

    @Override
    public boolean doPostExecute() {
        return true;
    }

    @Override
    public void postExecute() throws Exception {
        if (isDependenciesSucceeded()) {
            FileUtils.cleanDirectory(target);

            if (Files.getFileStore(target).equals(Files.getFileStore(tempDir))) {
                Files.move(tempDir, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                FileUtils.copyDirectory(tempDir, target);
                FileUtils.deleteDirectory(tempDir);
            }
            setResult(new Result(download, javaDownloadsTask.getResult()));
        }
    }

    public record Result(MojangJavaDownloads.JavaDownload download, MojangJavaRemoteFiles remoteFiles) {
    }
}
