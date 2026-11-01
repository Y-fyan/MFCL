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
package org.jackhuang.hmcl.util;

import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import org.glavo.url.WebURL;
import org.jackhuang.hmcl.util.function.ExceptionalSupplier;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jackhuang.hmcl.util.io.UrlResponseInfo;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.jackhuang.hmcl.util.gson.JsonUtils.GSON;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

public class CacheRepository {
    private Path commonDirectory;
    private Path cacheDirectory;
    private Path indexFile;
    private FileTime indexFileLastModified;
    /// Remote entries keyed by URLs without queries or fragments; initialized by [#changeDirectory(Path)].
    private @Nullable LinkedHashMap<WebURL, ETagItem> index;
    protected final ReadWriteLock lock = new ReentrantReadWriteLock();

    public void changeDirectory(Path commonDir) {
        commonDirectory = commonDir;
        cacheDirectory = commonDir.resolve("cache");
        indexFile = cacheDirectory.resolve("etag.json");

        lock.writeLock().lock();
        try {
            if (Files.isRegularFile(indexFile)) {
                try (FileChannel channel = FileChannel.open(indexFile, StandardOpenOption.READ);
                     @SuppressWarnings("unused") FileLock lock = channel.tryLock(0, Long.MAX_VALUE, true)) {
                    FileTime lastModified = Lang.ignoringException(() -> Files.getLastModifiedTime(indexFile));
                    ETagIndex raw = JsonUtils.GSON.fromJson(new BufferedReader(Channels.newReader(channel, UTF_8)), ETagIndex.class);
                    index = raw != null ? joinETagIndexes(raw.eTag) : new LinkedHashMap<>();
                    indexFileLastModified = lastModified;
                }
            } else {
                index = new LinkedHashMap<>();
                indexFileLastModified = null;
            }
        } catch (Exception e) {
            LOG.warning("Unable to read index file", e);
            index = new LinkedHashMap<>();
            indexFileLastModified = null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Path getCommonDirectory() {
        return commonDirectory;
    }

    public Path getCacheDirectory() {
        return cacheDirectory;
    }

    protected Path getFile(String algorithm, String hash) {
        hash = hash.toLowerCase(Locale.ROOT);
        return getCacheDirectory().resolve(algorithm).resolve(hash.substring(0, 2)).resolve(hash);
    }

    /// Checks only whether a cache candidate exists; callers must still verify its content before use.
    public boolean mayHaveCachedFile(@Nullable Path candidate, String algorithm, String hash) {
        if (!supportedHash(algorithm, hash)) return false;
        return Files.exists(getFile(algorithm, hash)) || (candidate != null && Files.exists(candidate));
    }

    /// Returns whether a supported digest identifies an intact cached file.
    /// @param algorithm checksum algorithm
    /// @param hash expected digest
    /// @return whether the cached file exists and matches
    protected boolean fileExists(String algorithm, @Nullable String hash) {
        if (!supportedHash(algorithm, hash)) return false;
        String expected = Objects.requireNonNull(hash);
        Path file = getFile(algorithm, expected);
        if (Files.exists(file)) {
            try {
                @Nullable Boolean nativeResult = tryNativeCacheVerification(file, algorithm, expected);
                if (nativeResult != null) return nativeResult;
                return DigestUtils.digestToString(algorithm, file).equalsIgnoreCase(expected);
            } catch (IOException e) {
                return false;
            }
        } else {
            return false;
        }
    }

    /// Verifies a stable cached file in Rust for supported digest algorithms.
    /// @param file cached file path
    /// @param algorithm expected digest algorithm
    /// @param hash expected digest value
    /// @return verification result, or null when Java must perform the check
    protected static @Nullable Boolean tryNativeCacheVerification(Path file, String algorithm, String hash) {
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank()
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.cache.enabled", "true"))
                || file.getFileSystem() != java.nio.file.FileSystems.getDefault()) return null;
        try {
            Path absolute = file.toAbsolutePath(); String value = absolute.toString();
            // Avoid a process round trip for small files, which dominate asset caches.
            if (Files.size(absolute) < 1024 * 1024) return null;
            if (!java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(value) || !Path.of(value).equals(absolute)) return null;
            Path backend = Path.of(executable);
            if ("SHA-1".equalsIgnoreCase(algorithm) && DigestUtils.isSha1Digest(hash)) {
                var input = new NativeBackend.FileHashInput(0, absolute, hash);
                @Nullable NativeBackend.FileHashResult result = NativeBackend.checkFileHashes(backend, List.of(input),
                        () -> Thread.currentThread().isInterrupted()).get(0);
                return result != null && NativeBackend.isFileHashObservationCurrent(absolute, result) ? result.matches() : null;
            }
            if ("SHA-512".equalsIgnoreCase(algorithm) && DigestUtils.isSha512Digest(hash)) {
                @Nullable Map<String, String> result = NativeBackend.hashModpackFile(backend, absolute,
                        () -> Thread.currentThread().isInterrupted());
                return result == null ? null : hash.equalsIgnoreCase(result.get("sha512"));
            }
            if ("SHA-256".equalsIgnoreCase(algorithm) && isHexDigest(hash, 64))
                return NativeBackend.checkCacheDigest(backend, absolute, "SHA-256", hash);
            if ("MD5".equalsIgnoreCase(algorithm) && isHexDigest(hash, 32))
                return NativeBackend.checkCacheDigest(backend, absolute, "MD5", hash);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException ignored) {
            // Existing Java verification remains authoritative when native inspection is unavailable.
        }
        return null;
    }

    /// Computes a stable SHA-1 in Rust for a sufficiently large cache source.
    /// @param file file to inspect
    /// @return lowercase digest, or null for Java fallback
    protected static @Nullable String tryNativeCacheSha1(Path file) {
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank()
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.cache.enabled", "true"))
                || file.getFileSystem() != java.nio.file.FileSystems.getDefault()) return null;
        try {
            if (Files.size(file) < 1024 * 1024) return null;
            return NativeBackend.hashCacheSha1(Path.of(executable), file);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException ignored) {
            // The original Java digest remains available when native inspection fails.
        }
        return null;
    }

    /// Accepts legacy SHA-1-length keys and supported extended digest keys.
    /// @param algorithm checksum algorithm
    /// @param hash digest to validate
    /// @return whether the key is supported
    private static boolean supportedHash(String algorithm, @Nullable String hash) {
        return DigestUtils.isSha1Digest(hash)
                || ("SHA-512".equalsIgnoreCase(algorithm) && DigestUtils.isSha512Digest(hash))
                || ("SHA-256".equalsIgnoreCase(algorithm) && isHexDigest(hash, 64))
                || ("MD5".equalsIgnoreCase(algorithm) && isHexDigest(hash, 32));
    }

    /// Checks an ASCII hexadecimal digest of the requested length.
    /// @param value candidate digest, or null
    /// @param length required character count
    /// @return whether the digest is valid
    private static boolean isHexDigest(@Nullable String value, int length) {
        if (value == null || value.length() != length) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
        }
        return true;
    }

    /// Rejects unsupported cache keys before constructing paths.
    /// @param algorithm checksum algorithm
    /// @param hash digest to validate
    /// @throws IOException if the key is unsupported
    private void checkHash(String algorithm, String hash) throws IOException {
        if (!supportedHash(algorithm, hash)) throw new IOException("Unsupported checksum for " + algorithm + ": " + hash);
    }

    public void tryCacheFile(Path path, String algorithm, String hash) throws IOException {
        checkHash(algorithm, hash);

        Path cache = getFile(algorithm, hash);
        if (Files.isRegularFile(cache)) return;
        if (tryNativeCacheCopy(path, cache, algorithm, hash)) return;
        FileUtils.copyFile(path, cache);
    }

    public Path cacheFile(Path path, String algorithm, String hash) throws IOException {
        checkHash(algorithm, hash);

        Path cache = getFile(algorithm, hash);
        if (tryNativeCacheCopy(path, cache, algorithm, hash)) return cache;
        FileUtils.copyFile(path, cache);
        return cache;
    }

    /// Stages a verified cache copy in Rust and publishes it only after Java checks file identity and attributes.
    /// @param source existing source file
    /// @param cache target cache file
    /// @param algorithm expected digest algorithm
    /// @param hash expected digest
    /// @return true when the cache entry was published, false for Java fallback
    private static boolean tryNativeCacheCopy(Path source, Path cache, String algorithm, String hash) {
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank()
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.cache.enabled", "true"))
                || source.getFileSystem() != java.nio.file.FileSystems.getDefault()
                || cache.getFileSystem() != java.nio.file.FileSystems.getDefault()) return false;
        String normalizedAlgorithm;
        if ("SHA-1".equalsIgnoreCase(algorithm) && DigestUtils.isSha1Digest(hash)) normalizedAlgorithm = "SHA-1";
        else if ("SHA-512".equalsIgnoreCase(algorithm) && DigestUtils.isSha512Digest(hash)) normalizedAlgorithm = "SHA-512";
        else if ("SHA-256".equalsIgnoreCase(algorithm) && isHexDigest(hash, 64)) normalizedAlgorithm = "SHA-256";
        else if ("MD5".equalsIgnoreCase(algorithm) && isHexDigest(hash, 32)) normalizedAlgorithm = "MD5";
        else return false;
        @Nullable Path stageDirectory = null;
        try {
            if (!Files.isRegularFile(source) || Files.size(source) < 1024 * 1024
                    || Files.exists(cache, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false;
            @Nullable Object mode = Files.getAttribute(source, "unix:mode");
            // PosixFilePermission does not represent setuid, setgid, or sticky bits.
            if (!(mode instanceof Number number) || (number.intValue() & 07000) != 0) return false;
            @Nullable UserDefinedFileAttributeView extra = Files.getFileAttributeView(source, UserDefinedFileAttributeView.class);
            if (extra != null && !extra.list().isEmpty()) return false;
            if (Files.getFileAttributeView(source, java.nio.file.attribute.AclFileAttributeView.class) != null) return false;
            @Nullable Path parent = cache.toAbsolutePath().getParent();
            if (parent == null) return false;
            Files.createDirectories(parent);
            stageDirectory = Files.createTempDirectory(parent, ".mfcl-cache-stage-");
            Path staged = stageDirectory.resolve("payload");
            @Nullable NativeBackend.FileHashResult observation = NativeBackend.stageCacheCopy(
                    Path.of(executable), source, staged, normalizedAlgorithm, hash);
            if (observation == null || !NativeBackend.isFileHashObservationCurrent(source, observation)) return false;
            PosixFileAttributes sourceAttributes = Files.readAttributes(source, PosixFileAttributes.class);
            PosixFileAttributes stagedAttributes = Files.readAttributes(staged, PosixFileAttributes.class);
            if (!sourceAttributes.owner().equals(stagedAttributes.owner()) || !sourceAttributes.group().equals(stagedAttributes.group())) return false;
            @Nullable PosixFileAttributeView posix = Files.getFileAttributeView(staged, PosixFileAttributeView.class);
            @Nullable BasicFileAttributeView basic = Files.getFileAttributeView(staged, BasicFileAttributeView.class);
            if (posix == null || basic == null) return false;
            posix.setPermissions(sourceAttributes.permissions());
            basic.setTimes(sourceAttributes.lastModifiedTime(), sourceAttributes.lastAccessTime(), sourceAttributes.creationTime());
            if (!NativeBackend.isFileHashObservationCurrent(source, observation)) return false;
            Files.move(staged, cache);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException ignored) {
            // Private staging can be discarded before the existing Java copy is attempted.
        } finally {
            if (stageDirectory != null) FileUtils.deleteDirectoryQuietly(stageDirectory);
        }
        return false;
    }

    public Optional<Path> checkExistentFile(@Nullable Path original, String algorithm, @Nullable String hash) {
        if (fileExists(algorithm, hash))
            return Optional.of(getFile(algorithm, Objects.requireNonNull(hash)));

        if (original != null && Files.exists(original)) {
            if (hash != null) {
                try {
                    @Nullable Boolean nativeResult = tryNativeCacheVerification(original, algorithm, hash);
                    boolean matches = nativeResult != null ? nativeResult
                            : DigestUtils.digestToString(algorithm, original).equalsIgnoreCase(hash);
                    if (matches)
                        return Optional.of(restore(original, () -> cacheFile(original, algorithm, hash)));
                } catch (IOException e) {
                    // we cannot check the hashcode.
                }
            } else {
                return Optional.of(original);
            }
        }

        return Optional.empty();
    }

    protected Path restore(Path original, ExceptionalSupplier<Path, ? extends IOException> cacheSupplier) throws IOException {
        Path cache = cacheSupplier.get();
        Files.delete(original);
        Files.createLink(original, cache);
        return cache;
    }

    /// Returns an existing cache file for the URL, ignoring its query and fragment.
    ///
    /// @param url the remote URL
    /// @param checkExpires whether to reject expired entries
    /// @throws IOException if the entry is missing, invalid, unreadable, or expired when checked
    public Path getCachedRemoteFile(WebURL url, boolean checkExpires) throws IOException {
        lock.readLock().lock();
        @Nullable ETagItem eTagItem;
        try {
            eTagItem = index.get(NetworkUtils.dropQuery(url));
        } finally {
            lock.readLock().unlock();
        }
        if (eTagItem == null) throw new IOException("Cannot find the URL");
        if (StringUtils.isBlank(eTagItem.hash) || !fileExists(SHA1, eTagItem.hash)) throw new FileNotFoundException();
        if (checkExpires && System.currentTimeMillis() > eTagItem.expires)
            throw new CacheExpiredException(eTagItem.expires);

        Path file = getFile(SHA1, eTagItem.hash);
        if (Files.getLastModifiedTime(file).toMillis() != eTagItem.localLastModified) {
            @Nullable String nativeHash = tryNativeCacheSha1(file);
            String hash = nativeHash == null ? DigestUtils.digestToString(SHA1, file) : nativeHash;
            if (!Objects.equals(hash, eTagItem.hash))
                throw new IOException("This file is modified");
        }
        return file;
    }

    /// Removes the URL's index entry, ignoring its query and fragment; the cache file is retained.
    public void removeRemoteEntry(WebURL url) {
        lock.writeLock().lock();
        try {
            index.remove(NetworkUtils.dropQuery(url));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /// Returns immutable conditional request headers for the URL's cached ETag, or an empty map.
    public @NotNull @Unmodifiable Map<String, String> injectConnection(WebURL url) {
        try {
            url = NetworkUtils.dropQuery(url);
        } catch (IllegalArgumentException e) {
            return Map.of();
        }

        @Nullable ETagItem eTagItem;
        lock.readLock().lock();
        try {
            eTagItem = index.get(url);
        } finally {
            lock.readLock().unlock();
        }
        if (eTagItem == null) return Map.of();
        if (eTagItem.eTag != null)
            return Map.of("if-none-match", eTagItem.eTag);
        // if (eTagItem.getRemoteLastModified() != null)
        //     conn.setRequestProperty("If-Modified-Since", eTagItem.getRemoteLastModified());
        return Map.of();
    }

    /// Adds a conditional request header if the URL has a cached ETag.
    public void injectConnection(WebURL url, HttpRequest.Builder requestBuilder) {
        try {
            url = NetworkUtils.dropQuery(url);
        } catch (IllegalArgumentException e) {
            return;
        }

        @Nullable ETagItem eTagItem;
        lock.readLock().lock();
        try {
            eTagItem = index.get(url);
        } finally {
            lock.readLock().unlock();
        }
        if (eTagItem == null) return;
        if (eTagItem.eTag != null)
            requestBuilder.header("if-none-match", eTagItem.eTag);
        // if (eTagItem.getRemoteLastModified() != null)
        //     conn.setRequestProperty("If-Modified-Since", eTagItem.getRemoteLastModified());
    }

    public Path cacheRemoteFile(UrlResponseInfo info, Path downloaded) throws IOException {
        return cacheData(info, () -> {
            @Nullable String nativeHash = tryNativeCacheSha1(downloaded);
            String hash = nativeHash == null ? DigestUtils.digestToString(SHA1, downloaded) : nativeHash;
            Path cached = cacheFile(downloaded, SHA1, hash);
            return new CacheResult(hash, cached);
        });
    }

    public Path cacheText(UrlResponseInfo info, String text) throws IOException {
        return cacheBytes(info, text.getBytes(UTF_8));
    }

    public Path cacheBytes(UrlResponseInfo info, byte[] bytes) throws IOException {
        return cacheData(info, () -> {
            String hash = DigestUtils.digestToString(SHA1, bytes);
            Path cached = getFile(SHA1, hash);
            Files.createDirectories(cached.getParent());
            Files.write(cached, bytes);
            return new CacheResult(hash, cached);
        });
    }

    private static final Pattern MAX_AGE = Pattern.compile("(s-maxage|max-age)=(?<time>[0-9]+)");

    private Path cacheData(UrlResponseInfo info, ExceptionalSupplier<CacheResult, IOException> cacheSupplier) throws IOException {
        String eTag = info.headers().firstValue("etag").orElse(null);
        if (StringUtils.isBlank(eTag)) return null;
        WebURL url = NetworkUtils.dropQuery(info.url());
        long expires = 0L;

        expires:
        try {
            String cacheControl = info.headers().firstValue("cache-control").orElse(null);
            if (StringUtils.isNotBlank(cacheControl)) {
                if (cacheControl.contains("no-store"))
                    return null;

                Matcher matcher = MAX_AGE.matcher(cacheControl);
                if (matcher.find()) {
                    long seconds = Long.parseLong(matcher.group("time"));
                    expires = Instant.now().plusSeconds(seconds).toEpochMilli();
                    break expires;
                }
            }

            String expiresHeader = info.headers().firstValue("expires").orElse(null);
            if (StringUtils.isNotBlank(expiresHeader)) {
                expires = ZonedDateTime.parse(expiresHeader.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli();
            }
        } catch (Throwable e) {
            LOG.warning("Failed to parse expires time", e);
        }

        String lastModified = info.headers().firstValue("last-modified").orElse(null);

        CacheResult cacheResult = cacheSupplier.get();
        ETagItem eTagItem = new ETagItem(url.toString(),
                eTag,
                cacheResult.hash,
                Files.getLastModifiedTime(cacheResult.cachedFile).toMillis(),
                lastModified,
                expires);
        lock.writeLock().lock();
        try {
            index.compute(url, updateEntity(eTagItem, true));
            saveETagIndex();
        } finally {
            lock.writeLock().unlock();
        }
        return cacheResult.cachedFile;
    }

    private static final class CacheResult {
        public String hash;
        public Path cachedFile;

        public CacheResult(String hash, Path cachedFile) {
            this.hash = hash;
            this.cachedFile = cachedFile;
        }
    }

    /// Creates a merge operation that selects an entry and removes replaced content when its hash differs.
    private BiFunction<WebURL, @Nullable ETagItem, ETagItem> updateEntity(ETagItem newItem, boolean force) {
        return (key, oldItem) -> {
            if (oldItem == null) {
                return newItem;
            } else if (force || oldItem.compareTo(newItem) < 0) {
                if (!oldItem.hash.equalsIgnoreCase(newItem.hash)) {
                    Path cached = getFile(SHA1, oldItem.hash);
                    try {
                        Files.deleteIfExists(cached);
                    } catch (IOException e) {
                        LOG.warning("Cannot delete old file");
                    }
                }
                return newItem;
            } else {
                return oldItem;
            }
        };
    }

    /// Merges persisted indexes using normalized URL keys and the entry replacement policy.
    @SafeVarargs
    private LinkedHashMap<WebURL, ETagItem> joinETagIndexes(@Nullable Collection<ETagItem>... indexes) {
        var eTags = new LinkedHashMap<WebURL, ETagItem>();
        for (@Nullable Collection<ETagItem> eTagItems : indexes) {
            if (eTagItems != null) {
                for (ETagItem eTag : eTagItems) {
                    eTags.compute(WebURL.parse(eTag.url), updateEntity(eTag, false));
                }
            }
        }
        return eTags;
    }

    public void saveETagIndex() throws IOException {
        try (FileChannel channel = FileChannel.open(indexFile, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
             @SuppressWarnings("unused") FileLock lock = channel.lock()) {
            FileTime lastModified = Lang.ignoringException(() -> Files.getLastModifiedTime(indexFile));
            if (indexFileLastModified == null || lastModified == null || indexFileLastModified.compareTo(lastModified) < 0) {
                try {
                    ETagIndex indexOnDisk = GSON.fromJson(
                            // Should not be closed
                            new BufferedReader(Channels.newReader(channel, UTF_8)),
                            ETagIndex.class
                    );
                    if (indexOnDisk != null) {
                        index = joinETagIndexes(index.values(), indexOnDisk.eTag);
                        indexFileLastModified = lastModified;
                    }
                } catch (JsonSyntaxException ignored) {
                }
            }

            channel.truncate(0);
            BufferedWriter writer = new BufferedWriter(Channels.newWriter(channel, UTF_8));
            JsonUtils.GSON.toJson(new ETagIndex(index.values()), writer);
            writer.flush();
            channel.force(true);

            this.indexFileLastModified = Lang.ignoringException(() -> Files.getLastModifiedTime(indexFile));
        }
    }

    private record ETagIndex(Collection<ETagItem> eTag) {
        public ETagIndex() {
            this(new HashSet<>());
        }

        private ETagIndex(Collection<ETagItem> eTag) {
            this.eTag = new HashSet<>(eTag);
        }
    }

    private record ETagItem(String url, String eTag, String hash, @SerializedName("local") long localLastModified,
                            @SerializedName("remote") String remoteLastModified, long expires) {

        /**
         * For Gson.
         */
        public ETagItem() {
            this(null, null, null, 0, null, 0L);
        }

        public int compareTo(ETagItem other) {
            if (!url.equals(other.url) && !WebURL.parse(url).equals(WebURL.parse(other.url)))
                throw new IllegalArgumentException();

            ZonedDateTime thisTime = Lang.ignoringException(() -> ZonedDateTime.parse(remoteLastModified, DateTimeFormatter.RFC_1123_DATE_TIME), null);
            ZonedDateTime otherTime = Lang.ignoringException(() -> ZonedDateTime.parse(other.remoteLastModified, DateTimeFormatter.RFC_1123_DATE_TIME), null);
            if (thisTime == null && otherTime == null) return 0;
            else if (thisTime == null) return 1;
            else if (otherTime == null) return -1;
            else return thisTime.compareTo(otherTime);
        }

        @Override
        public @NotNull String toString() {
            return "ETagItem[" +
                    "url='" + url + '\'' +
                    ", eTag='" + eTag + '\'' +
                    ", hash='" + hash + '\'' +
                    ", localLastModified=" + localLastModified +
                    ", remoteLastModified='" + remoteLastModified + '\'' +
                    ", expires=" + expires +
                    ']';
        }
    }

    private static CacheRepository instance = new CacheRepository();

    public static CacheRepository getInstance() {
        return instance;
    }

    public static void setInstance(CacheRepository instance) {
        CacheRepository.instance = instance;
    }

    public static final String SHA1 = "SHA-1";

    public static class CacheExpiredException extends IOException {
        private final long expires;

        public CacheExpiredException(long expires) {
            this.expires = expires;
        }

        public CacheExpiredException(String message, long expires) {
            super(message);
            this.expires = expires;
        }

        public long getExpires() {
            return expires;
        }
    }
}
