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

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.download.DownloadPage;
import org.jackhuang.hmcl.ui.instances.Instances;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.function.Consumer;

/// The MFMCL download hub.
///
/// Presents the download categories as cards and opens the matching page in an
/// embedded view, so the MFMCL sidebar stays in charge of navigation.
@NotNullByDefault
public final class MfmclDownloadsPage extends VBox {
    /// Opens the requested download category.
    private final Consumer<DownloadPage.Category> openCategory;

    /// Creates the download hub.
    public MfmclDownloadsPage(Consumer<DownloadPage.Category> openCategory) {
        this.openCategory = openCategory;
        getStyleClass().addAll("mfmcl-page", "mfmcl-downloads-page");
        setSpacing(16);

        Label eyebrow = new Label("下载中心");
        eyebrow.getStyleClass().add("mfmcl-eyebrow");
        Label title = new Label("想玩什么，就从这里开始。");
        title.getStyleClass().add("mfmcl-page-title");
        Label description = new Label("游戏版本、模组加载器、整合包与内容资源，都可以在同一个地方找到。");
        description.getStyleClass().add("mfmcl-page-description");
        description.setWrapText(true);
        VBox heading = new VBox(4, eyebrow, title, description);

        Label gridTitle = new Label("下载分类");
        gridTitle.getStyleClass().add("mfmcl-section-title");

        GridPane grid = new GridPane();
        grid.getStyleClass().add("mfmcl-download-grid");
        grid.setHgap(12);
        grid.setVgap(12);
        for (int i = 0; i < 3; i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(100.0 / 3.0);
            column.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(column);
        }

        grid.add(categoryCard("游戏版本", "正式版、快照与历史版本，可一并选择模组加载器",
                SVG.STADIA_CONTROLLER, () -> openCategory.accept(DownloadPage.Category.GAME)), 0, 0);
        grid.add(categoryCard("整合包", "一键安装完整整合包，自动补齐版本与模组",
                SVG.PACKAGE2, () -> openCategory.accept(DownloadPage.Category.MODPACK)), 1, 0);
        grid.add(categoryCard("模组", "搜索 Modrinth 与 CurseForge，直接安装到当前实例",
                SVG.EXTENSION, () -> openCategory.accept(DownloadPage.Category.MOD)), 2, 0);
        grid.add(categoryCard("资源包", "为方块、物品和界面换一套外观",
                SVG.TEXTURE, () -> openCategory.accept(DownloadPage.Category.RESOURCE_PACK)), 0, 1);
        grid.add(categoryCard("光影", "安装光影包，让世界的光线更自然",
                SVG.WB_SUNNY, () -> openCategory.accept(DownloadPage.Category.SHADER)), 1, 1);
        grid.add(categoryCard("世界", "下载社区地图与冒险存档",
                SVG.PUBLIC, () -> openCategory.accept(DownloadPage.Category.WORLD)), 2, 1);

        Label shortcutTitle = new Label("常用操作");
        shortcutTitle.getStyleClass().add("mfmcl-section-title");

        VBox shortcuts = new VBox(10);
        shortcuts.getStyleClass().add("mfmcl-panel");
        shortcuts.getChildren().setAll(
                shortcutRow("从本地导入整合包", "选择 .zip 或 .mrpack 文件，导入为新的实例",
                        SVG.FOLDER_OPEN, Instances::importModpack),
                shortcutRow("打开游戏目录", "查看当前选中的游戏目录与实例文件",
                        SVG.FOLDER, () -> FXUtils.openFolder(
                                GameDirectoryManager.getSelectedRepository().getBaseDirectory()))
        );

        getChildren().setAll(heading, gridTitle, grid, shortcutTitle, shortcuts);
    }

    /// Creates a clickable category card.
    private VBox categoryCard(String title, String description, SVG icon, Runnable action) {
        VBox card = new VBox(8);
        card.getStyleClass().add("mfmcl-category-card");
        HBox top = new HBox(10);
        top.setAlignment(Pos.CENTER_LEFT);
        StackPane iconFrame = new StackPane(icon.createIcon(17));
        iconFrame.getStyleClass().add("mfmcl-category-icon");
        Label name = new Label(title);
        name.getStyleClass().add("mfmcl-category-title");
        top.getChildren().setAll(iconFrame, name);

        Label text = new Label(description);
        text.getStyleClass().add("mfmcl-muted");
        text.setWrapText(true);

        Label open = new Label("打开 →");
        open.getStyleClass().add("mfmcl-category-open");

        card.getChildren().setAll(top, text, open);
        card.setOnMouseClicked(event -> {
            if (event.getClickCount() == 1) {
                action.run();
            }
        });
        return card;
    }

    /// Creates a shortcut row.
    private HBox shortcutRow(String title, String description, SVG icon, Runnable action) {
        Label name = new Label(title);
        name.getStyleClass().add("mfmcl-shortcut-title");
        Label text = new Label(description);
        text.getStyleClass().add("mfmcl-muted");
        VBox copy = new VBox(2, name, text);
        HBox.setHgrow(copy, Priority.ALWAYS);

        Button button = new Button("打开");
        button.getStyleClass().add("mfmcl-row-secondary");
        button.setOnAction(event -> action.run());

        HBox row = new HBox(12, icon.createIcon(18), copy, button);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("mfmcl-shortcut-row");
        return row;
    }
}
