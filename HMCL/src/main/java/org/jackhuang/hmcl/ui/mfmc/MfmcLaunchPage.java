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
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.text.TextAlignment;
import org.jackhuang.hmcl.addon.mod.ModLoaderType;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.game.HMCLGameInstance;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.HMCLGameRepositorySnapshot;
import org.jackhuang.hmcl.game.TexturesLoader;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.SVGContainer;
import org.jackhuang.hmcl.ui.account.CreateAccountPane;
import org.jackhuang.hmcl.ui.instances.Instances;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.javafx.BindingMapping;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/// The MFMC launch tab.
///
/// The left column contains the selected account, the square launch button, and
/// the version selector. The right column contains the news feed.
@NotNullByDefault
public final class MfmcLaunchPage extends GridPane {
    /// The selected repository snapshot used to refresh the version list.
    private final ObservableValue<HMCLGameRepositorySnapshot> repositorySnapshot =
            BindingMapping.of(GameDirectoryManager.selectedRepositoryProperty())
                    .flatMap(HMCLGameRepository::snapshotProperty);

    /// Creates the launch tab.
    ///
    /// @param showDownload action that opens the download tab
    /// @param showSettings action that opens the settings tab
    public MfmcLaunchPage(Runnable showDownload, Runnable showSettings) {
        getStyleClass().add("mfmc-launch-page");
        setHgap(16);
        setVgap(16);
        setPadding(new Insets(0));

        ColumnConstraints leftColumn = new ColumnConstraints();
        leftColumn.setPercentWidth(40);
        leftColumn.setHgrow(Priority.ALWAYS);

        ColumnConstraints rightColumn = new ColumnConstraints();
        rightColumn.setPercentWidth(60);
        rightColumn.setHgrow(Priority.ALWAYS);

        getColumnConstraints().addAll(leftColumn, rightColumn);

        VBox leftCard = createLeftCard(showDownload);
        MfmcNewsPane newsPane = new MfmcNewsPane(showDownload, showSettings);

        GridPane.setHgrow(leftCard, Priority.ALWAYS);
        GridPane.setVgrow(leftCard, Priority.ALWAYS);
        GridPane.setHgrow(newsPane, Priority.ALWAYS);
        GridPane.setVgrow(newsPane, Priority.ALWAYS);

        add(leftCard, 0, 0);
        add(newsPane, 1, 0);
    }

    /// Builds the left column.
    private VBox createLeftCard(Runnable showDownload) {
        VBox card = new VBox(10);
        card.getStyleClass().add("mfmc-card");
        card.setPadding(new Insets(16));
        card.setMinWidth(0);

        Canvas avatar = new Canvas(60, 60);
        avatar.setClip(new Circle(30, 30, 30));

        StackPane avatarFrame = new StackPane(avatar);
        avatarFrame.getStyleClass().add("mfmc-avatar-frame");
        avatarFrame.setPrefSize(64, 64);
        avatarFrame.setMinSize(64, 64);
        avatarFrame.setMaxSize(64, 64);

        Label accountName = new Label();
        accountName.getStyleClass().add("mfmc-account-name");

        Label accountType = new Label();
        accountType.getStyleClass().add("mfmc-account-type");

        VBox accountInfo = new VBox(2, accountName, accountType);
        accountInfo.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(accountInfo, Priority.ALWAYS);

        SVGContainer chevron = SVG.ARROW_DROP_DOWN.createIcon(18);
        chevron.getStyleClass().add("mfmc-chevron");

        HBox accountBox = new HBox(12, avatarFrame, accountInfo, chevron);
        accountBox.setAlignment(Pos.CENTER_LEFT);
        accountBox.getStyleClass().add("mfmc-account-box");
        accountBox.setCursor(Cursor.HAND);
        accountBox.setOnMouseClicked(event -> showAccountMenu(accountBox));

        FXUtils.onChangeAndOperate(Accounts.selectedAccountProperty(), account -> {
            TexturesLoader.unbindAvatar(avatar);
            if (account == null) {
                TexturesLoader.drawAvatar(avatar, TexturesLoader.getDefaultSkinImage());
                accountName.setText("未登录");
                accountType.setText("点击添加正版或离线账号");
            } else {
                TexturesLoader.bindAvatar(avatar, account);
                String profileName = account.getProfileName();
                accountName.setText(StringUtils.isBlank(profileName)
                        ? account.getProfileID().toString()
                        : profileName);
                accountType.setText(Accounts.getLocalizedLoginTypeName(Accounts.getAccountFactory(account)));
            }
        });

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        Button launchButton = createLaunchButton(showDownload);
        HBox launchRow = new HBox(launchButton);
        launchRow.setAlignment(Pos.CENTER);

        Label versionTitle = new Label("选择版本");
        versionTitle.getStyleClass().add("mfmc-section-title");

        Region versionSpacer = new Region();
        HBox.setHgrow(versionSpacer, Priority.ALWAYS);

        Button downloadButton = new Button("下载新版本");
        downloadButton.getStyleClass().add("mfmc-link-button");
        downloadButton.setOnAction(event -> showDownload.run());

        HBox versionHeader = new HBox(8, versionTitle, versionSpacer, downloadButton);
        versionHeader.setAlignment(Pos.CENTER_LEFT);

        ObservableList<HMCLGameInstance> versions = FXCollections.observableArrayList();
        ListView<HMCLGameInstance> versionList = createVersionList(versions);

        FXUtils.onChangeAndOperate(repositorySnapshot, snapshot -> refreshVersions(versions, versionList));
        FXUtils.onChangeAndOperate(GameDirectoryManager.selectedInstanceProperty(), instance -> {
            if (instance != null && versionList.getSelectionModel().getSelectedItem() != instance) {
                versionList.getSelectionModel().select(instance);
            }
        });

        card.getChildren().setAll(accountBox, spacer, launchRow, versionHeader, versionList);
        return card;
    }

    /// Creates the square launch button.
    private Button createLaunchButton(Runnable showDownload) {
        Button button = new Button();
        button.getStyleClass().add("mfmc-launch-button");
        button.setPrefSize(96, 96);
        button.setMinSize(96, 96);
        button.setMaxSize(96, 96);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setFocusTraversable(true);

        SVGContainer icon = SVG.ROCKET_LAUNCH.createIcon(30);
        icon.getStyleClass().add("mfmc-launch-icon");

        Label label = new Label("启动游戏");
        label.getStyleClass().add("mfmc-launch-text");

        VBox graphic = new VBox(6, icon, label);
        graphic.setAlignment(Pos.CENTER);
        button.setGraphic(graphic);

        button.setOnAction(event -> {
            HMCLGameInstance instance = GameDirectoryManager.getSelectedInstance();
            if (instance == null) {
                Controllers.showToast("请先下载并选择一个游戏版本");
                showDownload.run();
                return;
            }
            Instances.launch(instance);
        });
        return button;
    }

    /// Creates the version selector list.
    private ListView<HMCLGameInstance> createVersionList(ObservableList<HMCLGameInstance> versions) {
        ListView<HMCLGameInstance> list = new ListView<>(versions);
        list.getStyleClass().add("mfmc-version-list");
        list.setFixedCellSize(46);
        list.setPrefHeight(100);
        list.setMinHeight(56);
        list.setMaxHeight(140);
        list.setFocusTraversable(false);
        list.setPlaceholder(createVersionPlaceholder());
        list.setCellFactory(ignored -> new VersionCell());
        list.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && newValue != GameDirectoryManager.getSelectedInstance()) {
                GameDirectoryManager.setSelectedInstance(newValue);
            }
        });
        return list;
    }

    /// Creates the placeholder shown when no instance is installed.
    private Label createVersionPlaceholder() {
        Label label = new Label("暂无版本\n点击“下载新版本”开始安装");
        label.getStyleClass().add("mfmc-empty-hint");
        label.setWrapText(true);
        label.setTextAlignment(TextAlignment.CENTER);
        return label;
    }

    /// Refreshes the version list from the current repository snapshot.
    private void refreshVersions(ObservableList<HMCLGameInstance> versions, ListView<HMCLGameInstance> list) {
        HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
        List<HMCLGameInstance> items = repository.getDisplayInstances().toList();
        versions.setAll(items);

        @Nullable HMCLGameInstance selected = repository.getSelectedInstance();
        if (selected != null) {
            list.getSelectionModel().select(selected);
        } else if (!items.isEmpty()) {
            repository.setSelectedInstance(items.get(0));
            list.getSelectionModel().select(0);
        }
    }

    /// Shows the account switcher below the account block.
    private void showAccountMenu(HBox anchor) {
        ContextMenu menu = new ContextMenu();

        for (Account account : Accounts.getAccounts()) {
            MenuItem item = new MenuItem(account.getProfileName());
            item.setOnAction(event -> Accounts.setSelectedAccount(account));
            menu.getItems().add(item);
        }

        if (!menu.getItems().isEmpty()) {
            menu.getItems().add(new SeparatorMenuItem());
        }

        MenuItem addAccount = new MenuItem("添加账号");
        addAccount.setOnAction(event -> Controllers.dialog(new CreateAccountPane()));
        menu.getItems().add(addAccount);

        MenuItem manageAccounts = new MenuItem("管理账号");
        manageAccounts.setOnAction(event -> Controllers.navigate(Controllers.getAccountListPage()));
        menu.getItems().add(manageAccounts);

        menu.show(anchor, javafx.geometry.Side.BOTTOM, 0, 8);
    }

    /// Describes an instance by its game version and mod loaders.
    private static String describeInstance(HMCLGameInstance instance) {
        Set<ModLoaderType> loaders = instance.getModLoaders();
        String version = instance.getVersion().toString();
        if (loaders.isEmpty()) {
            return version;
        }

        String loaderText = loaders.stream()
                .map(ModLoaderType::displayName)
                .sorted()
                .collect(Collectors.joining(" + "));
        return version + " · " + loaderText;
    }

    /// A compact version-list cell.
    private static final class VersionCell extends ListCell<HMCLGameInstance> {
        /// The instance name.
        private final Label title = new Label();

        /// The game version and mod-loader summary.
        private final Label subtitle = new Label();

        /// The cell layout.
        private final VBox box = new VBox(2, title, subtitle);

        /// Creates an empty version cell.
        private VersionCell() {
            getStyleClass().add("mfmc-version-cell");
            title.getStyleClass().add("mfmc-version-title");
            subtitle.getStyleClass().add("mfmc-version-subtitle");
        }

        @Override
        protected void updateItem(@Nullable HMCLGameInstance item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
            } else {
                title.setText(item.getId().toString());
                subtitle.setText(describeInstance(item));
                setGraphic(box);
            }
        }
    }
}
