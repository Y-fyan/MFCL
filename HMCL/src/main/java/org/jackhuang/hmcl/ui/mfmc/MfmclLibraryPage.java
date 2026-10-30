/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026  huangyuhui <huanghongxun2008@126.com> and contributors
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

import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.addon.mod.ModLoaderType;
import org.jackhuang.hmcl.game.HMCLGameInstance;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.HMCLGameRepositorySnapshot;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.instances.Instances;
import org.jackhuang.hmcl.util.javafx.BindingMapping;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/// The MFMCL game library.
///
/// Mirrors the official launcher: one card per instance, two columns on wide
/// windows and a single column on narrow ones. Each card exposes launch and
/// manage actions without leaving the sidebar workspace.
@NotNullByDefault
public final class MfmclLibraryPage extends VBox {
    /// Opens the content store when the user wants a new instance.
    private final Runnable createInstance;

    /// Opens the instance detail page.
    private final Consumer<HMCLGameInstance> onSelect;

    /// The instance count caption in the page header.
    private final Label countLabel = new Label();

    /// The card container.
    private final GridPane grid = new GridPane();

    /// The scrollable content wrapper.
    private final VBox body = new VBox();

    /// The repository snapshot used to refresh the library after loading.
    private final ObservableValue<HMCLGameRepositorySnapshot> repositorySnapshot =
            BindingMapping.of(GameDirectoryManager.selectedRepositoryProperty())
                    .flatMap(HMCLGameRepository::snapshotProperty);

    /// The current card column count.
    private int columns;

    /// Creates the library page.
    ///
    /// @param createInstance opens the store for creating a new instance
    /// @param onSelect       opens the detail page for an instance
    public MfmclLibraryPage(Runnable createInstance, Consumer<HMCLGameInstance> onSelect) {
        this.createInstance = createInstance;
        this.onSelect = onSelect;
        getStyleClass().addAll("mfmcl-page", "mfmcl-library-page");

        countLabel.getStyleClass().add("mfmcl-page-subheading");

        Label heading = new Label("游戏库");
        heading.getStyleClass().add("mfmcl-page-heading");
        VBox copy = new VBox(6, heading, countLabel);
        HBox.setHgrow(copy, Priority.ALWAYS);
        HBox header = new HBox(16, copy,
                MfmclUi.primaryButton("创建游戏", SVG.ADD, createInstance));
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("mfmcl-page-header");

        grid.getStyleClass().add("mfmcl-instance-grid");
        grid.setHgap(24);
        grid.setVgap(24);
        body.getStyleClass().add("mfmcl-page-body");
        body.getChildren().setAll(grid);

        getChildren().setAll(header, MfmclUi.scroll(body));

        widthProperty().addListener((observable, oldValue, newValue) -> rebuildIfNeeded());
        FXUtils.onChangeAndOperate(repositorySnapshot, snapshot -> refresh());
    }

    /// Refreshes the library from the selected repository.
    public void refresh() {
        List<HMCLGameInstance> instances = GameDirectoryManager.getSelectedRepository()
                .getDisplayInstances()
                .toList();

        countLabel.setText(instances.isEmpty()
                ? "还没有实例，先创建一个游戏。"
                : "共 " + instances.size() + " 个实例，选择启动或管理。");

        grid.getChildren().clear();
        grid.getColumnConstraints().clear();

        if (instances.isEmpty()) {
            grid.getChildren().add(emptyState());
            return;
        }

        columns = desiredColumns();
        for (int i = 0; i < columns; i++) {
            ColumnConstraints constraint = new ColumnConstraints();
            constraint.setPercentWidth(100.0 / columns);
            constraint.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(constraint);
        }

        for (int i = 0; i < instances.size(); i++) {
            HMCLGameInstance instance = instances.get(i);
            Node card = instanceCard(instance);
            GridPane.setHgrow(card, Priority.ALWAYS);
            grid.add(card, i % columns, i / columns);
        }
    }

    /// Rebuilds the grid when the window crosses the two-column threshold.
    private void rebuildIfNeeded() {
        if (columns > 0 && desiredColumns() != columns) {
            refresh();
        }
    }

    /// Returns the column count for the current page width.
    private int desiredColumns() {
        return getWidth() >= 820 ? 2 : 1;
    }

    /// Creates one instance card.
    private HBox instanceCard(HMCLGameInstance instance) {
        Image iconImage = instance.getIconImage();
        Node icon;
        if (iconImage != null) {
            ImageView image = new ImageView(iconImage);
            image.setFitWidth(40);
            image.setFitHeight(40);
            image.setPreserveRatio(true);
            image.setSmooth(true);
            icon = image;
        } else {
            icon = SVG.PACKAGE2.createIcon(28);
        }
        StackPane iconTile = new StackPane(icon);
        iconTile.getStyleClass().add("mfmcl-instance-icon");
        iconTile.setMinSize(64, 64);
        iconTile.setPrefSize(64, 64);
        iconTile.setMaxSize(64, 64);

        Label name = new Label(instance.getId().toString());
        name.getStyleClass().add("mfmcl-instance-name");
        name.setWrapText(true);

        Label meta = new Label(instanceMeta(instance));
        meta.getStyleClass().add("mfmcl-instance-meta");
        meta.setWrapText(true);

        Label location = new Label(instance.getInstanceRoot().toAbsolutePath().toString());
        location.getStyleClass().add("mfmcl-instance-location");
        location.setWrapText(true);

        VBox info = new VBox(4, name, meta, location);
        info.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(info, Priority.ALWAYS);

        HBox actions = new HBox(8,
                MfmclUi.primaryButton("启动", SVG.ROCKET_LAUNCH, () -> launch(instance)),
                MfmclUi.secondaryButton("管理", null, () -> onSelect.accept(instance)));
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.getStyleClass().add("mfmcl-instance-actions");

        HBox card = new HBox(16, iconTile, info, actions);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("mfmcl-instance-card");
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    /// Builds the empty state shown when no instance exists.
    private Node emptyState() {
        Label title = new Label("还没有游戏实例");
        title.getStyleClass().add("mfmcl-empty-title");
        Label hint = new Label("从内容商店安装一个 Minecraft 版本，实例会自动出现在这里。");
        hint.getStyleClass().add("mfmcl-empty-text");
        hint.setWrapText(true);

        VBox box = new VBox(8, SVG.PACKAGE2.createIcon(48), title, hint,
                MfmclUi.primaryButton("创建游戏", SVG.ADD, createInstance));
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("mfmcl-empty-state");
        box.setMaxWidth(420);
        GridPane.setColumnSpan(box, Math.max(1, columns));
        return box;
    }

    /// Launches an instance.
    private void launch(HMCLGameInstance instance) {
        GameDirectoryManager.setSelectedInstance(instance);
        Instances.launch(instance);
    }

    /// Returns the version and loader summary for a card.
    private static String instanceMeta(HMCLGameInstance instance) {
        String loaders = instance.getModLoaders().stream()
                .map(ModLoaderType::displayName)
                .sorted()
                .collect(Collectors.joining(" + "));
        return loaders.isEmpty()
                ? "Minecraft " + instance.getVersion()
                : "Minecraft " + instance.getVersion() + " · " + loaders;
    }
}
