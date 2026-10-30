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

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.image.ImageView;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.game.HMCLGameInstance;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.setting.GameSettings;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.game.GameSettingsPage;
import org.jackhuang.hmcl.ui.main.JavaManagementPage;
import org.jackhuang.hmcl.util.Lazy;
import org.jetbrains.annotations.NotNullByDefault;

/// The MFMC settings tab.
///
/// This page intentionally exposes only the settings needed by the internal
/// launcher: Java management, version isolation, and local about information.
@NotNullByDefault
public final class MfmcSettingsPage extends VBox {
    /// Creates the settings tab with its own title.
    public MfmcSettingsPage() {
        this(true);
    }

    /// Creates the settings tab.
    ///
    /// @param showTitle whether to render the local "设置" heading; embedded
    ///                  workspaces supply their own page header
    public MfmcSettingsPage(boolean showTitle) {
        getStyleClass().add("mfmc-card");
        setPadding(new Insets(16));
        setSpacing(12);

        Label title = new Label("设置");
        title.getStyleClass().add("mfmc-section-title");

        Lazy<Node> javaPage = new Lazy<>(JavaManagementPage::new);
        Lazy<Node> gamePage = new Lazy<>(() -> {
            GameSettingsPage<GameSettings.Preset> page =
                    new GameSettingsPage<>(GameSettings.Preset.class, null);
            page.loadInstance(HMCLGameInstance.Optional.empty(GameDirectoryManager.getSelectedRepository()));
            return page;
        });

        Node aboutPage = createAboutPane();

        ToggleGroup group = new ToggleGroup();
        ToggleButton javaTab = createTab("Java 管理", group);
        ToggleButton gameTab = createTab("版本隔离", group);
        ToggleButton aboutTab = createTab("关于", group);

        StackPane content = new StackPane();
        content.getStyleClass().add("mfmc-settings-content");
        VBox.setVgrow(content, Priority.ALWAYS);

        javaTab.setOnAction(event -> {
            if (javaTab.isSelected()) {
                content.getChildren().setAll(javaPage.get());
            } else {
                javaTab.setSelected(true);
            }
        });
        gameTab.setOnAction(event -> {
            if (gameTab.isSelected()) {
                content.getChildren().setAll(gamePage.get());
            } else {
                gameTab.setSelected(true);
            }
        });
        aboutTab.setOnAction(event -> {
            if (aboutTab.isSelected()) {
                content.getChildren().setAll(aboutPage);
            } else {
                aboutTab.setSelected(true);
            }
        });

        HBox tabs = new HBox(4, javaTab, gameTab, aboutTab);
        tabs.setAlignment(Pos.CENTER_LEFT);

        switch (System.getProperty("mfmc.ui.settings.tab", "java")) {
            case "game" -> {
                gameTab.setSelected(true);
                content.getChildren().setAll(gamePage.get());
            }
            case "about" -> {
                aboutTab.setSelected(true);
                content.getChildren().setAll(aboutPage);
            }
            default -> {
                javaTab.setSelected(true);
                content.getChildren().setAll(javaPage.get());
            }
        }

        getChildren().setAll(showTitle ? new Node[]{title, tabs, content} : new Node[]{tabs, content});
    }

    /// Creates a settings sub-tab.
    private ToggleButton createTab(String text, ToggleGroup group) {
        ToggleButton tab = new ToggleButton(text);
        tab.getStyleClass().add("mfmc-subtab");
        tab.setToggleGroup(group);
        tab.setFocusTraversable(false);
        return tab;
    }

    /// Creates the local about pane.
    private VBox createAboutPane() {
        Label name = new Label(Metadata.FULL_NAME);
        name.getStyleClass().add("mfmc-about-name");

        Label version = new Label("版本 " + Metadata.VERSION);
        version.getStyleClass().add("mfmc-about-version");

        ImageView logo = new ImageView(FXUtils.newBuiltinImage("/assets/img/icon-mfmc.png"));
        logo.setFitWidth(72);
        logo.setFitHeight(72);
        logo.setPreserveRatio(true);
        logo.setSmooth(true);

        VBox identity = new VBox(2, name, version);
        HBox header = new HBox(14, logo, identity);
        header.setAlignment(Pos.CENTER_LEFT);

        Label notice = new Label("MFMC 内部研发版本；基于 HMCL 框架开发，非 HMCL 官方发布。");
        notice.getStyleClass().add("mfmc-about-notice");
        notice.setWrapText(true);

        Hyperlink upstream = new Hyperlink("Hello Minecraft! Launcher (HMCL)");
        upstream.getStyleClass().add("mfmc-link");
        upstream.setOnAction(event -> FXUtils.openLink("https://github.com/HMCL-dev/HMCL"));

        VBox pane = new VBox(10, header, notice, upstream);
        pane.setPadding(new Insets(16));
        return pane;
    }
}
