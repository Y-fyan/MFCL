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
package org.jackhuang.hmcl.addon;

import com.google.gson.JsonParser;
import org.jackhuang.hmcl.addon.repository.ModrinthRemoteAddonRepository;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.MurmurHash2;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jackhuang.hmcl.util.io.HttpRequest;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Differential coverage of real Rust operations and non-native compatibility behavior.
@NotNullByDefault
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class NativeAddonCatalogTest {
    /// Configuration restored without changing other tests' native settings.
    private final @Nullable String previousPath = System.getProperty("mfcl.native.path");
    /// Feature switch restored after each isolated test.
    private final @Nullable String previousEnabled = System.getProperty("mfcl.native.catalog.enabled");
    /// Pool switch restored after one-shot compatibility checks.
    private final @Nullable String previousPool = System.getProperty("mfcl.native.pool.enabled");

    /// Restores process-wide configuration after every test, even on failure.
    @AfterEach
    void restore() {
        restore("mfcl.native.path", previousPath);
        restore("mfcl.native.catalog.enabled", previousEnabled);
        restore("mfcl.native.pool.enabled", previousPool);
    }

    /// Restores or removes one previously captured property.
    private static void restore(String key, @Nullable String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    /// Enables an explicitly provided real executable; ordinary offline runs may skip it.
    private static void useRealBackend() {
        @Nullable String path = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE");
        assumeTrue(path != null && !path.isBlank());
        System.setProperty("mfcl.native.path", path);
        System.setProperty("mfcl.native.catalog.enabled", "true");
        System.setProperty("mfcl.native.pool.enabled", "true");
    }

    /// Feature-off and absent backends preserve Java selection without native prerequisites.
    @Test
    void javaFallbackKeepsStableOrderingAndUnicodeMatching() {
        System.setProperty("mfcl.native.catalog.enabled", "false");
        List<RemoteAddon.Version> versions = versions();
        for (String loader : List.of("", "fabric", "FORGE", "加载器")) {
            assertEquals(reference(versions, "1.20.1", loader), NativeAddonCatalog.filterVersions(versions, "1.20.1", loader));
        }
        assertNull(NativeAddonCatalog.modrinthVersions("[]", false));
        System.setProperty("mfcl.native.catalog.enabled", "true");
        System.setProperty("mfcl.native.path", "");
        assertEquals(reference(versions, "", ""), NativeAddonCatalog.filterVersions(versions, "", ""));
    }

    /// Checks stable ties, null timestamps, nanosecond precision and original object identity.
    @Test
    void realSelectionMatchesJavaWithAndWithoutPooling() {
        useRealBackend();
        List<RemoteAddon.Version> versions = versions();
        for (String pool : List.of("true", "false")) {
            System.setProperty("mfcl.native.pool.enabled", pool);
            for (String game : List.of("", "1.20.1", "1.21")) {
                for (String loader : List.of("", "FABRIC", "forge", "unknown")) {
                    List<RemoteAddon.Version> expected = reference(versions, game, loader);
                    List<RemoteAddon.Version> actual = NativeAddonCatalog.filterVersions(versions, game, loader);
                    assertEquals(expected, actual);
                    for (int i = 0; i < actual.size(); i++) assertSame(expected.get(i), actual.get(i));
                }
            }
        }
        RemoteAddon.Version base = versions.get(0);
        RemoteAddon.Version unicode = new RemoteAddon.Version(base.source(), base.versionId(), base.projectId(),
                base.name(), base.version(), base.datePublished(), base.versionType(), base.file(), base.dependencies(), List.of("I"), base.loaders());
        // Java's dotted/dotless-I comparison is intentionally not Rust ASCII comparison.
        assertEquals(List.of(unicode), NativeAddonCatalog.filterVersions(List.of(unicode), "ı", ""));
    }

    /// Converts raw API versions identically, including broken dependencies and first-file order.
    @Test
    void realVersionProjectionMatchesRepositoryConversion() {
        useRealBackend();
        String metadata = """
                [{"id":"v1","project_id":"project","name":"版本 一","version_number":"1.0","version_type":"beta",
                  "date_published":"2026-11-02T01:02:03.123456789Z","game_versions":["1.20.1"],
                  "loaders":["fabric","custom"],"dependencies":[
                    {"project_id":"required-project","dependency_type":"required"},
                    {"project_id":"optional-project","dependency_type":"optional"},
                    {"project_id":"embedded-project","dependency_type":"embedded"},
                    {"project_id":"conflict-project","dependency_type":"incompatible"},
                    {"version_id":"unresolved","project_id":null,"dependency_type":"required"}],
                  "files":[{"hashes":{"sha1":"abc","sha512":"def"},"url":"https://example.com/a.jar","filename":"a.jar","primary":false},
                           {"hashes":{},"url":"https://example.com/b.jar","filename":"b.jar","primary":true}]},
                 {"version_type":"release","files":[]}]
                """;
        List<ModrinthRemoteAddonRepository.ProjectVersion> parsed = JsonUtils.fromNonNullJson(metadata,
                JsonUtils.listTypeOf(ModrinthRemoteAddonRepository.ProjectVersion.class));
        var expected = parsed.stream().flatMap(v -> v.toVersion().stream()).toList();
        @Nullable var actual = NativeAddonCatalog.modrinthVersions(metadata, false);
        assertNotNull(actual, "real native projection must succeed, not silently fall back");
        assertEquals(expected, actual);
        assertEquals(expected, NativeAddonCatalog.modrinthVersions(JsonParser.parseString(metadata).getAsJsonArray().get(0).toString(), true));
        assertNull(NativeAddonCatalog.modrinthVersions("[{\"files\":[],\"files\":[]}]", false));
        assertNull(NativeAddonCatalog.modrinthVersions("not JSON", false));
        assertEquals(List.of(), NativeAddonCatalog.modrinthVersions("[]", false));
        assertThrows(UnsupportedOperationException.class, () -> actual.clear());
    }

    /// Query facets preserve Unicode and quotes; Java still owns URL escaping.
    @Test
    void realQueryAndCategorySelectionPreserveApiSemantics() {
        useRealBackend();
        for (var sort : RemoteAddonRepository.SortType.values()) {
            @Nullable var query = NativeAddonCatalog.modrinthQuery("mod", "1.20.1", "a\"b", 3, 20, "钠 & 光影", sort);
            assertNotNull(query);
            assertEquals("60", query.get("offset"));
            assertEquals("钠 & 光影", query.get("query"));
            assertEquals(JsonParser.parseString("[[\"project_type:mod\"],[\"versions:1.20.1\"],[\"categories:a\\\"b\"]]"),
                    JsonParser.parseString(query.get("facets")));
            for (var order : RemoteAddonRepository.SortOrder.values()) {
                @Nullable var curse = NativeAddonCatalog.curseQuery(6, 123, "1.20.1", 3, 20, "钠 & 光影", sort, order);
                assertNotNull(curse);
                assertEquals("432", curse.get("gameId"));
                assertEquals("6", curse.get("classId"));
                assertEquals("123", curse.get("categoryId"));
                assertEquals("60", curse.get("index"));
                assertEquals(order == RemoteAddonRepository.SortOrder.ASC ? "asc" : "desc", curse.get("sortOrder"));
                assertEquals(switch (sort) {
                    case RELEVANCY -> "1";
                    case POPULARITY -> "2";
                    case DATE_CREATED -> "11";
                    case LAST_UPDATED -> "3";
                    case TOTAL_DOWNLOADS -> "6";
                }, curse.get("sortField"));
            }
        }
        assertNull(NativeAddonCatalog.modrinthQuery("mod", "", null, Integer.MAX_VALUE, 100, "", RemoteAddonRepository.SortType.RELEVANCY));
        @Nullable var categories = NativeAddonCatalog.modrinthCategories("""
                [{"name":"optimization","project_type":"mod","icon":"svg"},
                 {"name":"adventure","project_type":"modpack","icon":""},
                 {"name":"library","project_type":"mod","icon":""}]
                """, "mod");
        assertNotNull(categories);
        assertEquals(List.of("optimization", "library"), categories.stream().map(RemoteAddonRepository.Category::id).toList());
        assertNull(NativeAddonCatalog.modrinthCategories("[{\"name\":\"a\",\"name\":\"b\"}]", "mod"));
    }

    /// Native two-pass fingerprints match independent Java hashes across read/tail boundaries.
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void realFingerprintsMatchJavaAndNeverModifySources(@TempDir Path directory) throws Exception {
        useRealBackend();
        Random random = new Random(42);
        for (int size : new int[]{0, 1, 2, 3, 4, 5, 65_535, 65_536, 65_537, 1024 * 1024 + 11}) {
            byte[] input = new byte[size];
            random.nextBytes(input);
            ByteArrayOutputStream filtered = new ByteArrayOutputStream();
            for (byte b : input) if (b != 9 && b != 10 && b != 13 && b != 32) filtered.write(b);
            long expected = Integer.toUnsignedLong(MurmurHash2.hash32(filtered.toByteArray(), filtered.size(), 1));
            Path path = directory.resolve("sample-" + size);
            Files.write(path, input);
            @Nullable Long actual = NativeAddonCatalog.curseFingerprint(path);
            assertNotNull(actual, "real native hashing must be used for regular Unix files");
            assertEquals(expected, actual.longValue());
            assertArrayEquals(input, Files.readAllBytes(path));
            assertEquals(DigestUtils.digestToString("SHA-1", path), NativeAddonCatalog.localFileSha1(path));
        }
        assertNull(NativeAddonCatalog.curseFingerprint(directory));
        assertNull(NativeAddonCatalog.curseFingerprint(directory.resolve("missing")));
    }

    /// Opt-in live metadata check; no accounts or installed instances are touched.
    @Test
    @Timeout(120)
    @EnabledIfEnvironmentVariable(named = "MFCL_LIVE_CATALOG", matches = "1")
    void liveModrinthMetadataMatchesExistingJavaParser() throws Exception {
        useRealBackend();
        String metadata = HttpRequest.GET("https://api.modrinth.com/v2/project/sodium/version?game_versions=%5B%221.20.1%22%5D&include_changelog=false").getString();
        List<ModrinthRemoteAddonRepository.ProjectVersion> parsed = JsonUtils.fromNonNullJson(metadata,
                JsonUtils.listTypeOf(ModrinthRemoteAddonRepository.ProjectVersion.class));
        @Nullable var nativeVersions = NativeAddonCatalog.modrinthVersions(metadata, false);
        assertNotNull(nativeVersions, "ordinary live metadata should use Rust");
        assertFalse(nativeVersions.isEmpty());
        assertEquals(parsed.stream().flatMap(v -> v.toVersion().stream()).toList(), nativeVersions);
        String categories = HttpRequest.GET("https://api.modrinth.com/v2/tag/category").getString();
        List<ModrinthRemoteAddonRepository.Category> parsedCategories = JsonUtils.fromNonNullJson(categories,
                JsonUtils.listTypeOf(ModrinthRemoteAddonRepository.Category.class));
        @Nullable var nativeCategories = NativeAddonCatalog.modrinthCategories(categories, "mod");
        assertNotNull(nativeCategories);
        assertEquals(parsedCategories.stream().filter(c -> c.projectType().equals("mod")).map(ModrinthRemoteAddonRepository.Category::toCategory).toList(), nativeCategories);
    }

    /// Generates mixed empty game constraints, dates, ties and loader combinations.
    private static @Unmodifiable List<RemoteAddon.Version> versions() {
        List<RemoteAddon.Version> versions = new ArrayList<>();
        for (int i = 0; i < 240; i++) {
            @Nullable Instant date = i % 9 == 0 ? null : Instant.ofEpochSecond(i % 7 - 2, i % 3);
            versions.add(new RemoteAddon.Version(RemoteAddon.Source.MODRINTH, "v" + i, "p", "name", "version", date,
                    RemoteAddon.VersionType.Release, new RemoteAddon.File(Map.of(), "https://example.com/file.jar", "file.jar"),
                    List.of(), i % 3 == 0 ? List.of() : List.of(i % 2 == 0 ? "1.20.1" : "1.21"),
                    List.of(AddonLoader.of(i % 2 == 0 ? "Fabric" : "forge"))));
        }
        return List.copyOf(versions);
    }

    /// Independent copy of the pre-migration UI predicate/comparator for differential tests.
    private static @Unmodifiable List<RemoteAddon.Version> reference(List<RemoteAddon.Version> versions, String game, String loader) {
        return versions.stream()
                .filter(v -> game.isEmpty() || v.gameVersions().isEmpty() || v.gameVersions().stream().anyMatch(game::equalsIgnoreCase))
                .filter(v -> loader.isEmpty() || v.loaders().stream().map(AddonLoader::name).anyMatch(loader::equalsIgnoreCase))
                .sorted(Comparator.comparing(RemoteAddon.Version::datePublished, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
    }
}
