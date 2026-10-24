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

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.download.game.GameRemoteVersion;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.ModpackHelper;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.download.DownloadPage;
import org.jackhuang.hmcl.ui.download.ModpackInstallWizardProvider;
import org.jackhuang.hmcl.util.Lazy;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.List;

import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The MFMC launcher root page.
///
/// The page owns a three-tab top navigation bar and keeps the classic HMCL root
/// page untouched. The launch, download, and settings tabs share the same
/// backend services as the classic UI.
@NotNullByDefault
public final class MfmcRootPage extends StackPane implements DecoratorPage {
    /// The title-bar state returned to the window decorator.
    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(this, "state");

    /// The area below the top navigation bar.
    private final StackPane content = new StackPane();

    /// The custom launch page.
    private final MfmcLaunchPage launchPage;

    /// The lazily created classic download page with its left sidebar hidden.
    private final Lazy<DownloadPage> downloadPage = new Lazy<>(this::createDownloadPage);

    /// The lazily created MFMC settings page.
    private final Lazy<MfmcSettingsPage> settingsPage = new Lazy<>(MfmcSettingsPage::new);

    /// The top-level tab group.
    private final ToggleGroup tabGroup = new ToggleGroup();

    /// The launch tab.
    private final ToggleButton launchTab;

    /// The download tab.
    private final ToggleButton downloadTab;

    /// The settings tab.
    private final ToggleButton settingsTab;

    /// Creates the MFMC root page.
    public MfmcRootPage() {
        LOG.info("Initializing MFMC UI root");
        LOG.info("MFMC isolated config home: " + Metadata.HMCL_LOCAL_HOME);
        LOG.info("MFMC isolated user home: " + Metadata.HMCL_USER_HOME);
        LOG.info("MFMC isolated game directory: " + Metadata.MINECRAFT_DIRECTORY);
        getStyleClass().add("mfmc-root");

        var stylesheet = MfmcRootPage.class.getResource("/assets/css/mfmc-light.css");
        if (stylesheet != null) {
            getStylesheets().add(stylesheet.toExternalForm());
        }

        state.set(new State(null, createTitleNode(), false, false, true));

        launchPage = new MfmcLaunchPage(this::showDownloadTab, this::showSettingsTab);

        launchTab = createTab("启动游戏", SVG.ROCKET_LAUNCH);
        downloadTab = createTab("下载", SVG.DOWNLOAD);
        settingsTab = createTab("设置", SVG.SETTINGS);

        HBox topBar = createTopBar();
        content.getStyleClass().add("mfmc-content");
        content.setPadding(new Insets(16, 20, 20, 20));
        VBox.setVgrow(content, Priority.ALWAYS);

        VBox root = new VBox(topBar, content);
        getChildren().setAll(root);

        FXUtils.applyDragListener(this, ModpackHelper::isFileModpackByExtension, this::installDroppedModpacks);

        switch (System.getProperty("mfmc.ui.tab", "launch")) {
            case "download" -> showDownloadTab();
            case "settings" -> showSettingsTab();
            default -> selectTab(launchTab);
        }
    }

    /// Creates a top-level tab button.
    private ToggleButton createTab(String text, SVG icon) {
        ToggleButton tab = new ToggleButton(text);
        tab.setGraphic(icon.createIcon(16));
        tab.setContentDisplay(ContentDisplay.LEFT);
        tab.setGraphicTextGap(6);
        tab.setFocusTraversable(false);
        tab.getStyleClass().add("mfmc-tab");
        tab.setToggleGroup(tabGroup);
        tab.setOnAction(event -> {
            if (tab.isSelected()) {
                selectTab(tab);
            } else {
                tab.setSelected(true);
            }
        });
        return tab;
    }

    /// Builds the top navigation bar.
    private HBox createTopBar() {
        Label brand = new Label("MFMC");
        brand.getStyleClass().add("mfmc-brand");

        Label brandSub = new Label("LAUNCHER");
        brandSub.getStyleClass().add("mfmc-brand-sub");

        HBox brandBox = new HBox(6, brand, brandSub);
        brandBox.setAlignment(Pos.CENTER_LEFT);

        HBox tabs = new HBox(4, launchTab, downloadTab, settingsTab);
        tabs.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label mode = new Label("INTERNAL");
        mode.getStyleClass().add("mfmc-mode-badge");

        HBox topBar = new HBox(20, brandBox, tabs, spacer, mode);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.getStyleClass().add("mfmc-topbar");
        return topBar;
    }

    /// Creates the title node displayed by the window decorator.
    private Node createTitleNode() {
        HBox titleNode = new HBox(8);
        titleNode.setAlignment(Pos.CENTER_LEFT);

        ImageView icon = new ImageView(FXUtils.newBuiltinImage("/assets/img/icon-title.png"));
        icon.setFitWidth(20);
        icon.setFitHeight(20);
        icon.setPreserveRatio(true);

        Label title = new Label("MFMC Launcher");
        title.getStyleClass().add("mfmc-window-title");

        titleNode.getChildren().setAll(icon, title);
        return titleNode;
    }

    /// Switches the content area to the requested tab.
    private void selectTab(ToggleButton tab) {
        if (tab == launchTab) {
            content.getChildren().setAll(launchPage);
        } else if (tab == downloadTab) {
            content.getChildren().setAll(downloadPage.get());
        } else if (tab == settingsTab) {
            content.getChildren().setAll(settingsPage.get());
        }

        tab.setSelected(true);
    }

    /// Shows the launch tab.
    public void showLaunchTab() {
        launchTab.setSelected(true);
        selectTab(launchTab);
    }

    /// Shows the download tab.
    public void showDownloadTab() {
        downloadTab.setSelected(true);
        selectTab(downloadTab);
    }

    /// Shows the settings tab.
    public void showSettingsTab() {
        settingsTab.setSelected(true);
        selectTab(settingsTab);
    }

    /// Opens the MFMC-owned install page for the selected Minecraft version.
    private void showGameInstallPage(GameRemoteVersion gameVersion) {
        downloadTab.setSelected(true);
        content.getChildren().setAll(new MfmcGameInstallPage(gameVersion, this::showDownloadTab, this::showLaunchTab));
    }

    /// Creates the classic download page inside the new top-level shell.
    private DownloadPage createDownloadPage() {
        DownloadPage page = new DownloadPage(null, this::showGameInstallPage);
        page.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        page.getStyleClass().add("mfmc-embedded-page");
        return page;
    }

    /// Installs the first dropped modpack and reports extra dropped files.
    private void installDroppedModpacks(List<Path> files) {
        if (files.isEmpty()) {
            return;
        }

        HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
        Controllers.getDecorator().startWizard(
                new ModpackInstallWizardProvider(repository, files.get(0)),
                i18n("install.modpack")
        );

        if (files.size() > 1) {
            Controllers.showToast("一次只能安装一个整合包，已处理第一个文件");
        }
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }
}
