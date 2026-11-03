/*
 * MFCL Launcher
 * Copyright (C) 2026 MFCL contributors
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
package org.jackhuang.hmcl.ui.mfmc;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Region;
import javafx.scene.effect.DropShadow;
import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.util.List;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Offline checks that the shared MFCL browsers stay virtualized and route files correctly.
@NotNullByDefault
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class MfmcAddonBrowserTest {
    /// Each kind uses an explicit repository type and destination, never a copied mods path.
    @Test void categoryRoutingIsExplicit() {
        assertEquals(RemoteAddon.Type.MOD, MfmcAddonDownloadPage.Kind.MOD.type());
        assertEquals("mods", MfmcAddonDownloadPage.Kind.MOD.directory());
        assertEquals(RemoteAddon.Type.RESOURCE_PACK, MfmcAddonDownloadPage.Kind.RESOURCE_PACK.type());
        assertEquals("resourcepacks", MfmcAddonDownloadPage.Kind.RESOURCE_PACK.directory());
        assertEquals(RemoteAddon.Type.SHADER_PACK, MfmcAddonDownloadPage.Kind.SHADER.type());
        assertEquals("shaderpacks", MfmcAddonDownloadPage.Kind.SHADER.directory());
    }

    /// Queued operations retain their original catalog even when a cached page changes source.
    @Test void repositoryRequestsFreezeSourceAndOfflineControlsDoNotStartSearches() throws Exception {
        org.jackhuang.hmcl.JavaFXLauncher.start();
        assumeTrue(org.jackhuang.hmcl.JavaFXLauncher.isStarted());
        FutureTask<@Nullable Void> check = new FutureTask<>(() -> {
            MfmcAddonDownloadPage page = new MfmcAddonDownloadPage(MfmcAddonDownloadPage.Kind.RESOURCE_PACK, false);
            Method factory = MfmcAddonDownloadPage.class.getDeclaredMethod("currentRepository");
            factory.setAccessible(true);
            Object original = Objects.requireNonNull(factory.invoke(page));
            Field source = original.getClass().getDeclaredField("source");
            source.setAccessible(true);
            Field box = MfmcAddonDownloadPage.class.getDeclaredField("sourceBox");
            box.setAccessible(true);
            @SuppressWarnings("unchecked")
            ComboBox<RemoteAddon.Source> sources = (ComboBox<RemoteAddon.Source>) Objects.requireNonNull(box.get(page));
            sources.setValue(RemoteAddon.Source.CURSEFORGE);
            Object changed = Objects.requireNonNull(factory.invoke(page));
            assertEquals(RemoteAddon.Source.MODRINTH, source.get(original));
            assertEquals(RemoteAddon.Source.CURSEFORGE, source.get(changed));
            Field searches = MfmcAddonDownloadPage.class.getDeclaredField("searchId");
            searches.setAccessible(true);
            assertEquals(0, searches.getInt(page), "offline construction and filter events must not start IO");
            return null;
        });
        Platform.runLater(check);
        check.get(10, TimeUnit.SECONDS);
    }

    /// Only embedded legacy page surfaces lose shadows; dialog and popup surfaces retain elevation.
    @Test void legacySurfaceEffectsAreDisabledOnlyInsidePageBodies() throws Exception {
        org.jackhuang.hmcl.JavaFXLauncher.start();
        assumeTrue(org.jackhuang.hmcl.JavaFXLauncher.isStarted());
        FutureTask<@Nullable Void> check = new FutureTask<>(() -> {
            VBox root = new VBox();
            root.getStyleClass().add("mfmcl-root");
            Region dialogCard = new Region();
            dialogCard.getStyleClass().add("card");
            dialogCard.setEffect(new DropShadow());
            root.getChildren().add(dialogCard);
            for (String scope : new String[] {"mfmcl-embedded-page", "mfmc-settings-content"}) {
                VBox body = new VBox();
                body.getStyleClass().add(scope);
                for (String surface : new String[] {"card", "card-no-padding", "card-non-transparent", "options-list"}) {
                    Region card = new Region();
                    card.getStyleClass().add(surface);
                    card.setEffect(new DropShadow());
                    body.getChildren().add(card);
                }
                root.getChildren().add(body);
            }
            Scene scene = new Scene(root);
            scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/assets/css/mfmcl.css")).toExternalForm());
            root.applyCss();
            assertNotNull(dialogCard.getEffect());
            for (var body : root.getChildren().stream().filter(VBox.class::isInstance).map(VBox.class::cast).toList()) {
                for (var card : body.getChildren()) assertNull(card.getEffect());
            }
            return null;
        });
        Platform.runLater(check);
        check.get(10, TimeUnit.SECONDS);
    }

    /// Ten thousand rows must create only viewport cells, at both small and large window sizes.
    @Test void browsersUseBoundedVirtualCellsWithoutLegacyContainer() throws Exception {
        org.jackhuang.hmcl.JavaFXLauncher.start();
        assumeTrue(org.jackhuang.hmcl.JavaFXLauncher.isStarted());
        FutureTask<@Nullable Void> check = new FutureTask<>(() -> {
            for (var kind : MfmcAddonDownloadPage.Kind.values()) {
                MfmcAddonDownloadPage page = new MfmcAddonDownloadPage(kind, false);
                StackPane root = new StackPane(page);
                new Scene(root);
                root.resize(1100, 720);
                root.applyCss(); root.layout();
                assertTrue(page.lookupAll(".label").stream().map(n -> ((Label) n).getText()).anyMatch(s -> s.endsWith("详情")));
                assertTrue(page.lookupAll(".jfx-list-view").isEmpty());
                @SuppressWarnings("unchecked")
                @Nullable ListView<RemoteAddon> list = (ListView<RemoteAddon>) page.lookup(".mfmc-mod-list");
                assertNotNull(list);
                assertEquals(88, list.getFixedCellSize());
                @Unmodifiable List<RemoteAddon> items = IntStream.range(0, 10_000).mapToObj(i ->
                        new RemoteAddon("id" + i, "author", "Project " + i, "description", List.of(), "", "", RemoteAddon.BROKEN.data(), kind.type())).toList();
                list.getItems().setAll(items);
                for (double width : new double[] {1100, 760}) {
                    root.resize(width, 720);
                    root.layout();
                    list.scrollTo(5000);
                    root.layout();
                    assertTrue(list.lookupAll(".list-cell").size() < 40, "only the viewport may instantiate row graphics");
                    assertTrue(list.getHeight() > 80);
                    assertTrue(list.getWidth() > 80);
                }
                root.getChildren().clear();
            }
            return null;
        });
        Platform.runLater(check);
        check.get(30, TimeUnit.SECONDS);
    }
}
