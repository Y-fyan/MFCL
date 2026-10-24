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

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXTextField;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.download.ComponentRemoteVersion;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.download.game.GameRemoteVersion;
import org.jackhuang.hmcl.game.GameComponentType;
import org.jackhuang.hmcl.game.GameInstanceID;
import org.jackhuang.hmcl.game.HMCLDependencyManager;
import org.jackhuang.hmcl.game.HMCLGameBuilder;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.setting.DownloadProviders;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.setting.GameInstanceIconType;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.InstallerItem;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.construct.RequiredValidator;
import org.jackhuang.hmcl.ui.construct.Validator;
import org.jackhuang.hmcl.ui.download.VersionsPage;
import org.jackhuang.hmcl.ui.wizard.Navigation;
import org.jackhuang.hmcl.util.SettingsMap;
import org.jackhuang.hmcl.util.TaskCancellationAction;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CancellationException;

import static javafx.beans.binding.Bindings.createBooleanBinding;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The MFMC-owned page shown after selecting a Minecraft version in the download tab.
///
/// The classic HMCL wizard is deliberately not used here. This page keeps the
/// selected game version, collects an instance name and optional mod loaders,
/// and starts the same backend installation task that the classic download page
/// would have started. Selecting a loader version reuses the existing
/// [VersionsPage] component, but it is hosted inside the MFMC page.
@NotNullByDefault
public final class MfmcGameInstallPage extends BorderPane {
    /// The selected Minecraft version.
    private final GameRemoteVersion gameVersion;

    /// Returns to the download list.
    private final Runnable backAction;

    /// Called after a successful installation.
    private final Runnable onInstalled;

    /// Installer selections, keyed by [GameComponentType#getPatchId()].
    private final SettingsMap settings = new SettingsMap();

    /// Loader cards and their compatibility rules.
    private final InstallerItem.InstallerItemGroup group;

    /// The instance name field.
    private final JFXTextField nameField = new JFXTextField();

    /// Whether the instance name currently passes all validators.
    private final BooleanProperty installable = new SimpleBooleanProperty();

    /// Whether an installation task is already running.
    private final BooleanProperty installing = new SimpleBooleanProperty();

    /// The main form view.
    private final BorderPane mainView;

    /// The embedded loader-version selection view.
    private final BorderPane selectionView = new BorderPane();

    /// Swaps between the main form and loader-version selection.
    private final StackPane viewStack = new StackPane();

    /// The page title in the shared header.
    private final Label titleLabel = new Label(i18n("install.new_game"));

    /// The selected game version shown on the right side of the header.
    private final Label versionTag = new Label();

    /// Whether the loader-version selection view is currently visible.
    private boolean showingSelection;

    /// Whether the user has manually changed the instance name.
    private boolean nameModifiedByUser;

    /// Creates an install page for the selected game version.
    public MfmcGameInstallPage(GameRemoteVersion gameVersion, Runnable backAction, Runnable onInstalled) {
        this.gameVersion = gameVersion;
        this.backAction = backAction;
        this.onInstalled = onInstalled;
        this.group = new InstallerItem.InstallerItemGroup(gameVersion.getGameVersion(), InstallerItem.Style.LIST_ITEM);

        getStyleClass().add("mfmc-install-page");

        settings.put(GameComponentType.GAME.getPatchId(), gameVersion);

        versionTag.setText(I18n.getDisplayVersion(gameVersion));
        versionTag.getStyleClass().add("mfmc-install-tag");

        HBox header = createHeader();
        mainView = createMainView();
        selectionView.getStyleClass().add("mfmc-install-selection");

        viewStack.getChildren().setAll(mainView);

        setTop(header);
        setCenter(viewStack);

        configureLoaderCards();
        configureNameField();
        reloadLoaders();
        showMain();
    }

    /// Builds the shared page header.
    private HBox createHeader() {
        JFXButton backButton = new JFXButton();
        backButton.setGraphic(SVG.ARROW_BACK.createIcon(18));
        backButton.setFocusTraversable(false);
        backButton.getStyleClass().add("mfmc-install-back");
        FXUtils.installFastTooltip(backButton, "返回");
        backButton.setOnAction(event -> {
            if (showingSelection) {
                showMain();
            } else {
                backAction.run();
            }
        });

        titleLabel.getStyleClass().add("mfmc-install-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(10, backButton, titleLabel, spacer, versionTag);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("mfmc-install-header");
        return header;
    }

    /// Builds the main form view.
    private BorderPane createMainView() {
        VBox content = new VBox(10);
        content.getStyleClass().add("mfmc-install-content");
        content.setPadding(new Insets(0, 4, 8, 2));

        content.getChildren().addAll(
                createVersionSummary(),
                createNameCard(),
                createLoaderSection()
        );

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.getStyleClass().add("mfmc-install-scroll");

        JFXButton installButton = new JFXButton(i18n("button.install"));
        installButton.getStyleClass().add("mfmc-install-button");
        installButton.setPrefHeight(38);
        installButton.setMinWidth(120);
        installButton.disableProperty().bind(installable.not().or(installing));
        installButton.setOnAction(event -> onInstall());

        Label pathLabel = new Label("安装到：" + GameDirectoryManager.getSelectedRepository().getGameDirectory().getPath());
        pathLabel.getStyleClass().add("mfmc-install-path");
        pathLabel.setMaxWidth(Double.MAX_VALUE);
        pathLabel.setTextOverrun(javafx.scene.control.OverrunStyle.CENTER_ELLIPSIS);
        FXUtils.installFastTooltip(pathLabel, pathLabel.getText());
        HBox.setHgrow(pathLabel, Priority.ALWAYS);

        HBox actionBar = new HBox(12, pathLabel, installButton);
        actionBar.setAlignment(Pos.CENTER_LEFT);
        actionBar.getStyleClass().add("mfmc-install-actionbar");

        BorderPane root = new BorderPane();
        root.setCenter(scrollPane);
        root.setBottom(actionBar);
        return root;
    }

    /// Builds the selected version summary card.
    private HBox createVersionSummary() {
        GameInstanceIconType iconType = gameVersion.getGameVersion().isAprilFools()
                ? GameInstanceIconType.APRIL_FOOLS
                : gameVersion.getVersionType() == ComponentRemoteVersion.Type.RELEASE
                        ? GameInstanceIconType.GRASS
                        : GameInstanceIconType.COMMAND;

        ImageView icon = new ImageView(iconType.getIcon());
        icon.setFitWidth(48);
        icon.setFitHeight(48);
        icon.setPreserveRatio(true);

        Label versionName = new Label(I18n.getDisplayVersion(gameVersion));
        versionName.getStyleClass().add("mfmc-install-version");

        StringBuilder metaText = new StringBuilder(getVersionTypeName());
        if (gameVersion.getReleaseDate() != null) {
            metaText.append(" · ").append(I18n.formatDateTime(gameVersion.getReleaseDate()));
        }

        Label meta = new Label(metaText.toString());
        meta.getStyleClass().add("mfmc-install-subtitle");

        VBox text = new VBox(3, versionName, meta);
        text.setAlignment(Pos.CENTER_LEFT);

        HBox summary = new HBox(14, icon, text);
        summary.setAlignment(Pos.CENTER_LEFT);
        summary.getStyleClass().add("mfmc-install-card");
        return summary;
    }

    /// Builds the instance-name card.
    private VBox createNameCard() {
        Label label = new Label(i18n("instance.name"));
        label.getStyleClass().add("mfmc-install-section-title");

        nameField.getStyleClass().add("mfmc-install-name-field");
        nameField.setPromptText("例如 1.20.1-Fabric");

        VBox card = new VBox(6, label, nameField);
        card.getStyleClass().add("mfmc-install-card");
        return card;
    }

    /// Builds the optional mod-loader section.
    private VBox createLoaderSection() {
        Label title = new Label("模组加载器（可选）");
        title.getStyleClass().add("mfmc-install-section-title");

        Label hint = new Label("选择 Fabric、Forge、NeoForge 等加载器，安装时会一并下载。");
        hint.getStyleClass().add("mfmc-install-section-hint");

        FlowPane loaderPane = new FlowPane(8, 8);
        loaderPane.getStyleClass().add("mfmc-install-loaders");
        loaderPane.setPrefWrapLength(560);
        for (InstallerItem component : group.getComponents()) {
            if (component.getComponentType() != GameComponentType.GAME) {
                loaderPane.getChildren().add(component);
            }
        }

        VBox section = new VBox(7, title, hint, loaderPane);
        return section;
    }

    /// Wires the loader cards to the embedded version selector and to removal.
    private void configureLoaderCards() {
        for (InstallerItem component : group.getComponents()) {
            GameComponentType type = component.getComponentType();
            if (type == GameComponentType.GAME) {
                continue;
            }

            component.setOnInstall(() -> showLoaderVersions(type));
            component.setOnRemove(() -> {
                settings.remove(type.getPatchId());
                reloadLoaders();
            });
        }
    }

    /// Adds validators and the default name behaviour to the name field.
    private void configureNameField() {
        HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
        nameField.getValidators().addAll(
                new RequiredValidator(),
                new Validator(i18n("install.new_game.already_exists"), str -> !repository.instanceIdConflicts(str)),
                new Validator(i18n("install.new_game.malformed"), HMCLGameRepository::isValidInstanceId));
        installable.bind(createBooleanBinding(nameField::validate, nameField.textProperty()));

        nameField.textProperty().addListener((observable, oldText, newText) -> nameModifiedByUser = true);
    }

    /// Refreshes loader card versions and the default instance name.
    private void reloadLoaders() {
        for (InstallerItem component : group.getComponents()) {
            GameComponentType type = component.getComponentType();
            if (type == GameComponentType.GAME) {
                continue;
            }

            @Nullable ComponentRemoteVersion selected =
                    (ComponentRemoteVersion) settings.get(type.getPatchId());
            component.versionProperty().set(selected != null
                    ? new InstallerItem.InstalledState(I18n.getDisplayVersion(selected), false, false)
                    : null);
        }

        if (!nameModifiedByUser) {
            updateDefaultName();
        }
    }

    /// Builds the instance name from the game version and selected loaders.
    private void updateDefaultName() {
        StringBuilder name = new StringBuilder(I18n.getDisplayVersion(gameVersion));
        for (InstallerItem component : group.getComponents()) {
            GameComponentType type = component.getComponentType();
            if (type == GameComponentType.GAME || !settings.containsKey(type.getPatchId())) {
                continue;
            }

            String loaderName = switch (type) {
                case FORGE -> "Forge";
                case NEO_FORGE -> "NeoForge";
                case CLEANROOM -> "Cleanroom";
                case LEGACY_FABRIC -> "LegacyFabric";
                case FABRIC -> "Fabric";
                case LITELOADER -> "LiteLoader";
                case QUILT -> "Quilt";
                case OPTIFINE -> "OptiFine";
                default -> null;
            };

            if (loaderName != null) {
                name.append('-').append(loaderName);
            }
        }

        nameField.setText(name.toString());
        nameModifiedByUser = false;
    }

    /// Shows the main form.
    private void showMain() {
        showingSelection = false;
        titleLabel.setText(i18n("install.new_game"));
        viewStack.getChildren().setAll(mainView);
    }

    /// Shows a loader-version selector embedded in this page.
    private void showLoaderVersions(GameComponentType type) {
        showingSelection = true;
        titleLabel.setText(i18n("install.installer.choose", i18n("install.installer." + type.getPatchId())));

        Navigation navigation = new Navigation() {
            @Override
            public void onStart() {
            }

            @Override
            public void onNext() {
            }

            @Override
            public void onPrev(boolean cleanUp) {
                showMain();
            }

            @Override
            public boolean canPrev() {
                return true;
            }

            @Override
            public void onFinish() {
            }

            @Override
            public void onEnd() {
            }

            @Override
            public void onCancel() {
                showMain();
            }

            @Override
            public SettingsMap getSettings() {
                return settings;
            }
        };

        VersionsPage page = new VersionsPage(
                navigation,
                titleLabel.getText(),
                gameVersion.getGameVersion(),
                DownloadProviders.getDownloadProvider(),
                type,
                () -> {
                    reloadLoaders();
                    showMain();
                });

        selectionView.setCenter(page);
        viewStack.getChildren().setAll(selectionView);
    }

    /// Validates the name and starts the installation task.
    private void onInstall() {
        if (!nameField.validate()) {
            return;
        }

        String name = nameField.getText().trim();
        GameInstanceID instanceId = new GameInstanceID(name);

        if (!checkName(instanceId.id())) {
            Controllers.dialog(new MessageDialogPane.Builder(
                    i18n("install.name.invalid"),
                    i18n("message.warning"),
                    MessageDialogPane.MessageType.QUESTION)
                    .yesOrNo(() -> startInstall(instanceId), () -> {
                        // The user selected Cancel; keep the form open.
                    })
                    .build());
        } else {
            startInstall(instanceId);
        }
    }

    /// Starts the backend installation task for the selected components.
    private void startInstall(GameInstanceID instanceId) {
        HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
        if (!repository.isLoaded()) {
            Controllers.dialog("游戏目录尚未加载完成，请稍后重试", i18n("message.warning"), MessageDialogPane.MessageType.WARNING);
            return;
        }

        DownloadProvider downloadProvider = DownloadProviders.getDownloadProvider();
        HMCLDependencyManager dependencyManager = repository.getDependency(downloadProvider);

        Task<?> task;
        try (HMCLGameBuilder builder = dependencyManager.newGameBuilder(instanceId)) {
            builder.component(GameComponentType.GAME, gameVersion.getGameVersion().toString());

            for (InstallerItem component : group.getComponents()) {
                GameComponentType type = component.getComponentType();
                if (type == GameComponentType.GAME) {
                    continue;
                }

                Object value = settings.get(type.getPatchId());
                if (value instanceof ComponentRemoteVersion remoteVersion) {
                    builder.component(remoteVersion);
                }
            }

            boolean modded = GameComponentType.MOD_LOADERS.stream()
                    .anyMatch(type -> settings.get(type.getPatchId()) instanceof ComponentRemoteVersion);
            if (repository.shouldIsolateNewInstance(modded)) {
                builder.enableIsolation();
            }

            task = builder.buildAsync();
        }

        installing.set(true);
        task = task.whenComplete(Schedulers.javafx(), (result, exception) -> {
            installing.set(false);
            if (exception != null) {
                if (!(exception instanceof CancellationException)) {
                    LOG.warning("Failed to install game", exception);
                    Controllers.dialog(I18n.localizeErrorMessage(exception), i18n("install.failed"), MessageDialogPane.MessageType.ERROR);
                }
            } else {
                repository.setSelectedInstance(repository.getInstance(instanceId));
                Controllers.showToast(i18n("install.success"));
                onInstalled.run();
            }
        });

        Controllers.taskDialog(task, i18n("install.new_game.installation"), TaskCancellationAction.NORMAL);
    }

    /// Returns the localized version type shown under the version name.
    private String getVersionTypeName() {
        return switch (gameVersion.getVersionType()) {
            case RELEASE -> i18n("instance.game.release");
            case SNAPSHOT -> gameVersion.getGameVersion().isAprilFools()
                    ? i18n("instance.game.april_fools")
                    : i18n("instance.game.snapshot");
            case OLD -> i18n("instance.game.old");
            case PENDING, UNOBFUSCATED -> i18n("instance.game.snapshot");
            default -> "";
        };
    }

    /// Checks whether the instance name only contains safe ASCII characters.
    private static boolean checkName(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(c >= '0' && c <= '9')
                    && !(c >= 'a' && c <= 'z')
                    && !(c >= 'A' && c <= 'Z')
                    && c != '-' && c != '_' && c != '.') {
                return false;
            }
        }

        return true;
    }
}
