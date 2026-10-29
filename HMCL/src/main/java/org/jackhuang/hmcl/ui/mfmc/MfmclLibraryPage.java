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
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.game.HMCLGameInstance;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.HMCLGameRepositorySnapshot;
import org.jackhuang.hmcl.addon.mod.ModLoaderType;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.instances.Instances;
import org.jackhuang.hmcl.util.javafx.BindingMapping;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/// The MFMCL instance library page.
///
/// Lists every installed instance with search and loader filters, and offers
/// direct launch and management actions.
@NotNullByDefault
public final class MfmclLibraryPage extends VBox {
    /// Opens the download centre for creating a new instance.
    private final Runnable showDownloads;

    /// Called when the user selects an instance.
    private final Consumer<HMCLGameInstance> onSelect;

    /// The text used to filter instances.
    private final TextField searchField = new TextField();

    /// The complete instance list.
    private final ObservableList<HMCLGameInstance> instances = FXCollections.observableArrayList();

    /// The filtered instance list shown in the table.
    private final FilteredList<HMCLGameInstance> filtered = new FilteredList<>(instances);

    /// The instance table.
    private final ListView<HMCLGameInstance> list = new ListView<>(filtered);

    /// The count label under the heading.
    private final Label countLabel = new Label();

    /// The currently selected filter mode.
    private FilterMode filterMode = FilterMode.ALL;

    /// The repository snapshot used to refresh the library after loading.
    private final ObservableValue<HMCLGameRepositorySnapshot> repositorySnapshot =
            BindingMapping.of(GameDirectoryManager.selectedRepositoryProperty())
                    .flatMap(HMCLGameRepository::snapshotProperty);

    /// Creates the library page.
    public MfmclLibraryPage(Runnable showDownloads, Consumer<HMCLGameInstance> onSelect) {
        this.showDownloads = showDownloads;
        this.onSelect = onSelect;
        getStyleClass().addAll("mfmcl-page", "mfmcl-library-page");
        setSpacing(16);

        Label eyebrow = new Label("实例库");
        eyebrow.getStyleClass().add("mfmcl-eyebrow");
        Label title = new Label("你的每个世界，都独立生长。");
        title.getStyleClass().add("mfmcl-page-title");
        countLabel.getStyleClass().add("mfmcl-page-description");
        VBox heading = new VBox(4, eyebrow, title, countLabel);

        searchField.setPromptText("搜索实例名称或版本");
        searchField.getStyleClass().add("mfmcl-search-field");
        searchField.textProperty().addListener((observable, oldValue, newValue) -> applyFilter());
        HBox.setHgrow(searchField, Priority.ALWAYS);

        ToggleGroup filterGroup = new ToggleGroup();
        ToggleButton all = filterButton("全部", filterGroup, true, FilterMode.ALL);
        ToggleButton vanilla = filterButton("原版", filterGroup, false, FilterMode.VANILLA);
        ToggleButton modded = filterButton("模组", filterGroup, false, FilterMode.MODDED);

        Button create = new Button("创建实例");
        create.setGraphic(SVG.ADD.createIcon(16));
        create.getStyleClass().add("mfmcl-primary-button");
        create.setOnAction(event -> showDownloads.run());

        HBox toolbar = new HBox(8, searchField, all, vanilla, modded, create);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("mfmcl-toolbar");

        list.getStyleClass().add("mfmcl-instance-list");
        VBox.setVgrow(list, Priority.ALWAYS);
        list.setCellFactory(view -> new InstanceCell(this::launch, this::manage));
        list.setPlaceholder(createPlaceholder());

        getChildren().setAll(heading, toolbar, list);

        FXUtils.onChangeAndOperate(repositorySnapshot, snapshot -> refresh());
    }

    /// Creates a filter button.
    private ToggleButton filterButton(String text, ToggleGroup group, boolean selected, FilterMode mode) {
        ToggleButton button = new ToggleButton(text);
        button.setToggleGroup(group);
        button.setSelected(selected);
        button.getStyleClass().add("mfmcl-filter-button");
        button.setOnAction(event -> {
            filterMode = mode;
            applyFilter();
        });
        return button;
    }

    /// Applies the search and filter predicate.
    private void applyFilter() {
        String query = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        FilterMode mode = filterMode;
        filtered.setPredicate(instance -> matchesQuery(instance, query) && matchesMode(instance, mode));
        updateCount();
    }

    /// Returns whether an instance matches the search query.
    private static boolean matchesQuery(HMCLGameInstance instance, String query) {
        if (query.isEmpty()) {
            return true;
        }
        return instance.getId().toString().toLowerCase(Locale.ROOT).contains(query)
                || instance.getVersion().toString().toLowerCase(Locale.ROOT).contains(query)
                || loaderNames(instance).toLowerCase(Locale.ROOT).contains(query);
    }

    /// Returns whether an instance matches the requested filter mode.
    private static boolean matchesMode(HMCLGameInstance instance, FilterMode mode) {
        return switch (mode) {
            case ALL -> true;
            case VANILLA -> instance.getModLoaders().isEmpty();
            case MODDED -> !instance.getModLoaders().isEmpty();
        };
    }

    /// Refreshes the library from the selected repository.
    public void refresh() {
        instances.setAll(GameDirectoryManager.getSelectedRepository().getDisplayInstances().toList());
        updateCount();
    }

    /// Focuses the search field.
    public void focusSearch() {
        searchField.requestFocus();
    }

    /// Updates the instance count caption.
    private void updateCount() {
        int total = instances.size();
        int shown = filtered.size();
        String suffix = switch (filterMode) {
            case ALL -> "";
            case VANILLA -> " · 原版";
            case MODDED -> " · 模组";
        };
        countLabel.setText(shown == total
                ? total + " 个实例" + suffix
                : "显示 " + shown + " / " + total + " 个实例" + suffix);
    }

    /// Creates the empty-state placeholder.
    private static VBox createPlaceholder() {
        Label title = new Label("还没有实例");
        title.getStyleClass().add("mfmcl-empty-title");
        Label hint = new Label("从下载中心安装一个 Minecraft 版本，实例会自动出现在这里。");
        hint.getStyleClass().add("mfmcl-muted");
        hint.setWrapText(true);
        VBox box = new VBox(6, title, hint);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("mfmcl-empty");
        return box;
    }

    /// Launches the selected instance.
    private void launch(HMCLGameInstance instance) {
        GameDirectoryManager.setSelectedInstance(instance);
        Instances.launch(instance);
    }

    /// Opens the instance settings page.
    private void manage(HMCLGameInstance instance) {
        GameDirectoryManager.setSelectedInstance(instance);
        onSelect.accept(instance);
        Instances.modifyGameSettings(instance);
    }

    /// Returns the display names of the instance loaders.
    private static String loaderNames(HMCLGameInstance instance) {
        return instance.getModLoaders().stream()
                .map(ModLoaderType::displayName)
                .sorted()
                .collect(Collectors.joining(" + "));
    }

    /// The pre-set filters shown above the instance table.
    private enum FilterMode {
        ALL,
        VANILLA,
        MODDED
    }

    /// A single instance row.
    private static final class InstanceCell extends ListCell<HMCLGameInstance> {
        private final ImageView icon = new ImageView();
        private final Label name = new Label();
        private final Label meta = new Label();
        private final Label loaderTag = new Label();
        private final Button launch = new Button("启动");
        private final Button manage = new Button("管理");
        private final HBox row;

        private InstanceCell(Consumer<HMCLGameInstance> launchAction, Consumer<HMCLGameInstance> manageAction) {
            getStyleClass().add("mfmcl-instance-cell");
            icon.setFitWidth(44);
            icon.setFitHeight(44);
            icon.setPreserveRatio(true);
            icon.setSmooth(true);

            name.getStyleClass().add("mfmcl-instance-name");
            meta.getStyleClass().add("mfmcl-muted");
            loaderTag.getStyleClass().add("mfmcl-tag");

            VBox copy = new VBox(2, name, meta);
            copy.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(copy, Priority.ALWAYS);

            launch.getStyleClass().add("mfmcl-row-primary");
            manage.getStyleClass().add("mfmcl-row-secondary");

            row = new HBox(12, icon, copy, loaderTag, launch, manage);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("mfmcl-instance-row");

            launch.setOnAction(event -> {
                HMCLGameInstance item = getItem();
                if (item != null) {
                    launchAction.accept(item);
                }
            });
            manage.setOnAction(event -> {
                HMCLGameInstance item = getItem();
                if (item != null) {
                    manageAction.accept(item);
                }
            });
        }

        @Override
        protected void updateItem(@Nullable HMCLGameInstance item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                return;
            }

            icon.setImage(item.getIconImage());
            name.setText(item.getId().toString());
            meta.setText("Minecraft " + item.getVersion());

            String loaders = loaderNames(item);
            loaderTag.setText(loaders);
            loaderTag.setVisible(!loaders.isEmpty());
            loaderTag.setManaged(!loaders.isEmpty());

            setGraphic(row);
        }
    }
}
