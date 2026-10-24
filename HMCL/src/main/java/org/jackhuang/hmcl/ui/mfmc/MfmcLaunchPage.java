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

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;
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
public final class MfmcLaunchPage extends StackPane {
    /// The selected repository snapshot used to refresh the version list.
    private final ObservableValue<HMCLGameRepositorySnapshot> repositorySnapshot =
            BindingMapping.of(GameDirectoryManager.selectedRepositoryProperty())
                    .flatMap(HMCLGameRepository::snapshotProperty);

    /// The two-column launch layout.
    private final GridPane layout = new GridPane();

    /// The vertical scroll host for short windows.
    private final ScrollPane launcherScroll = new ScrollPane();

    /// The left account and launch card.
    private VBox leftCard;

    /// The right news column.
    private MfmcNewsPane newsPane;

    /// The installed-version selector.
    private ListView<HMCLGameInstance> versionList;

    /// The flexible gap between the account block and the launch button.
    private Region launchSpacer;

    /// The in-scene overlay used for the account menu.
    private final Pane overlay = new Pane();

    /// Catches clicks outside the account menu.
    private final Region menuBackdrop = new Region();

    /// The Escape handler installed while the account menu is open.
    private @Nullable EventHandler<KeyEvent> escapeHandler;

    /// Creates the launch tab.
    ///
    /// @param showDownload action that opens the download tab
    /// @param showSettings action that opens the settings tab
    public MfmcLaunchPage(Runnable showDownload, Runnable showSettings) {
        getStyleClass().add("mfmc-launch-page");

        layout.setHgap(18);
        layout.setVgap(16);
        layout.setPadding(new Insets(0));
        layout.setMinWidth(760);
        layout.setMinHeight(400);

        ColumnConstraints leftColumn = new ColumnConstraints();
        leftColumn.setPercentWidth(40);
        leftColumn.setHgrow(Priority.ALWAYS);

        ColumnConstraints rightColumn = new ColumnConstraints();
        rightColumn.setPercentWidth(60);
        rightColumn.setHgrow(Priority.ALWAYS);

        layout.getColumnConstraints().addAll(leftColumn, rightColumn);

        leftCard = createLeftCard(showDownload);
        newsPane = new MfmcNewsPane(showDownload, showSettings);

        GridPane.setHgrow(leftCard, Priority.ALWAYS);
        GridPane.setVgrow(leftCard, Priority.ALWAYS);
        GridPane.setHgrow(newsPane, Priority.ALWAYS);
        GridPane.setVgrow(newsPane, Priority.ALWAYS);

        layout.add(leftCard, 0, 0);
        layout.add(newsPane, 1, 0);

        launcherScroll.setContent(layout);
        launcherScroll.setFitToWidth(true);
        launcherScroll.setFitToHeight(true);
        launcherScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        launcherScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        launcherScroll.setMinSize(0, 0);
        launcherScroll.getStyleClass().add("mfmc-launch-scroll");

        overlay.setPickOnBounds(false);
        overlay.setVisible(false);
        overlay.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        menuBackdrop.prefWidthProperty().bind(overlay.widthProperty());
        menuBackdrop.prefHeightProperty().bind(overlay.heightProperty());
        menuBackdrop.setOnMousePressed(event -> hideAccountMenu());

        getChildren().setAll(launcherScroll, overlay);

        widthProperty().addListener((observable, oldValue, newValue) -> updateResponsiveLayout());
        heightProperty().addListener((observable, oldValue, newValue) -> updateResponsiveLayout());
        launcherScroll.vvalueProperty().addListener((observable, oldValue, newValue) -> hideAccountMenu());
        launcherScroll.hvalueProperty().addListener((observable, oldValue, newValue) -> hideAccountMenu());
        updateResponsiveLayout();
    }

    /// Applies compact spacing when the launch page is resized down.
    private void updateResponsiveLayout() {
        boolean compact = getWidth() < 940 || getHeight() < 580;
        layout.setHgap(compact ? 12 : 18);
        leftCard.setSpacing(compact ? 6 : 10);
        newsPane.setCompact(compact);

        if (launchSpacer != null) {
            if (compact) {
                VBox.setVgrow(launchSpacer, Priority.NEVER);
                launchSpacer.setMinHeight(4);
                launchSpacer.setPrefHeight(4);
                launchSpacer.setMaxHeight(4);
            } else {
                VBox.setVgrow(launchSpacer, Priority.ALWAYS);
                launchSpacer.setMinHeight(16);
                launchSpacer.setPrefHeight(Region.USE_COMPUTED_SIZE);
                launchSpacer.setMaxHeight(Double.MAX_VALUE);
            }
        }

        if (versionList != null) {
            versionList.setPrefHeight(compact ? 100 : 110);
            VBox.setVgrow(versionList, compact ? Priority.ALWAYS : Priority.NEVER);
            versionList.setMaxHeight(compact ? Double.MAX_VALUE : 140);
        }
        hideAccountMenu();
    }

    /// Builds the left column.
    private VBox createLeftCard(Runnable showDownload) {
        VBox card = new VBox(10);
        card.getStyleClass().add("mfmc-card");
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

        launchSpacer = new Region();
        VBox.setVgrow(launchSpacer, Priority.ALWAYS);

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
        versionList = createVersionList(versions);

        FXUtils.onChangeAndOperate(repositorySnapshot, snapshot -> refreshVersions(versions, versionList));
        FXUtils.onChangeAndOperate(GameDirectoryManager.selectedInstanceProperty(), instance -> {
            if (instance != null && versionList.getSelectionModel().getSelectedItem() != instance) {
                versionList.getSelectionModel().select(instance);
            }
        });

        card.getChildren().setAll(accountBox, launchSpacer, launchRow, versionHeader, versionList);
        return card;
    }

    /// Creates the flattened launch button.
    private Button createLaunchButton(Runnable showDownload) {
        Button button = new Button();
        button.getStyleClass().add("mfmc-launch-button");
        button.setPrefSize(160, 80);
        button.setMinSize(160, 80);
        button.setMaxSize(160, 80);
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
        hideAccountMenu();

        VBox card = new VBox(2);
        card.getStyleClass().add("mfmc-account-menu");
        card.setPrefWidth(220);

        var stylesheet = MfmcLaunchPage.class.getResource("/assets/css/mfmc-light.css");
        if (stylesheet != null) {
            card.getStylesheets().add(stylesheet.toExternalForm());
        }

        Label header = new Label("账号");
        header.getStyleClass().add("mfmc-account-menu-header");
        card.getChildren().add(header);

        Runnable closeAction = this::hideAccountMenu;
        Account selected = Accounts.getSelectedAccount();

        for (Account account : Accounts.getAccounts()) {
            boolean isSelected = selected != null
                    && selected.getAccountID().equals(account.getAccountID());
            card.getChildren().add(createAccountMenuRow(account, isSelected, closeAction));
        }

        if (!Accounts.getAccounts().isEmpty()) {
            Region divider = new Region();
            divider.getStyleClass().add("mfmc-account-menu-divider");
            card.getChildren().add(divider);
        }

        card.getChildren().addAll(
                createAccountMenuAction("添加账号", SVG.ADD_CIRCLE, true, closeAction,
                        () -> Controllers.dialog(new CreateAccountPane())),
                createAccountMenuAction("管理账号", SVG.PERSON, false, closeAction,
                        () -> Controllers.navigate(Controllers.getAccountListPage()))
        );

        Point2D anchorInScene = anchor.localToScene(0, 0);
        Point2D anchorInOverlay = overlay.sceneToLocal(anchorInScene);
        card.setLayoutX(anchorInOverlay.getX());
        card.setLayoutY(anchorInOverlay.getY() + anchor.getHeight() + 4);
        card.setOpacity(0);
        card.setScaleX(0.96);
        card.setScaleY(0.96);

        overlay.getChildren().setAll(menuBackdrop, card);
        overlay.setVisible(true);
        overlay.setMouseTransparent(false);
        overlay.toFront();

        var scene = getScene();
        if (scene != null) {
            escapeHandler = event -> {
                if (event.getCode() == KeyCode.ESCAPE) {
                    hideAccountMenu();
                    event.consume();
                }
            };
            scene.addEventFilter(KeyEvent.KEY_PRESSED, escapeHandler);
        }

        Timeline animation = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(card.opacityProperty(), 0, Interpolator.EASE_OUT),
                        new KeyValue(card.scaleXProperty(), 0.96, Interpolator.EASE_OUT),
                        new KeyValue(card.scaleYProperty(), 0.96, Interpolator.EASE_OUT)),
                new KeyFrame(Duration.millis(120),
                        new KeyValue(card.opacityProperty(), 1, Interpolator.EASE_OUT),
                        new KeyValue(card.scaleXProperty(), 1, Interpolator.EASE_OUT),
                        new KeyValue(card.scaleYProperty(), 1, Interpolator.EASE_OUT))
        );
        animation.play();
    }

    /// Hides the account menu and releases its Escape handler.
    private void hideAccountMenu() {
        overlay.setVisible(false);
        overlay.getChildren().clear();

        var scene = getScene();
        if (scene != null && escapeHandler != null) {
            scene.removeEventFilter(KeyEvent.KEY_PRESSED, escapeHandler);
        }
        escapeHandler = null;
    }

    /// Creates one account row in the account menu.
    private HBox createAccountMenuRow(Account account, boolean selected, Runnable closeAction) {
        Label name = new Label(StringUtils.isBlank(account.getProfileName())
                ? account.getProfileID().toString()
                : account.getProfileName());
        name.getStyleClass().add("mfmc-account-menu-text");

        Label type = new Label(Accounts.getLocalizedLoginTypeName(Accounts.getAccountFactory(account)));
        type.getStyleClass().add("mfmc-account-menu-subtext");

        VBox labels = new VBox(1, name, type);
        HBox.setHgrow(labels, Priority.ALWAYS);

        HBox row = new HBox(10, labels);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("mfmc-account-menu-item");
        row.setCursor(Cursor.HAND);

        if (selected) {
            row.getStyleClass().add("selected");
            SVGContainer check = SVG.CHECK.createIcon(14);
            check.getStyleClass().add("mfmc-account-menu-check");
            row.getChildren().add(check);
        }

        row.setOnMouseClicked(event -> {
            Accounts.setSelectedAccount(account);
            closeAction.run();
        });
        return row;
    }

    /// Creates one action row in the account menu.
    private HBox createAccountMenuAction(String text, SVG icon, boolean accent, Runnable closeAction, Runnable action) {
        SVGContainer iconNode = icon.createIcon(16);
        iconNode.getStyleClass().add("mfmc-account-menu-icon");

        Label label = new Label(text);
        label.getStyleClass().add("mfmc-account-menu-text");
        if (accent) {
            label.getStyleClass().add("accent");
        }

        HBox row = new HBox(10, iconNode, label);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("mfmc-account-menu-item");
        row.setCursor(Cursor.HAND);
        row.setOnMouseClicked(event -> {
            closeAction.run();
            action.run();
        });
        return row;
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
