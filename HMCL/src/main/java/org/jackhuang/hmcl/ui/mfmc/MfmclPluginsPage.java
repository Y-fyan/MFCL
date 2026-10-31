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
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;

/// The MFMCL launcher extension page.
///
/// The internal build does not load third-party plugins yet, so this page is an
/// honest inventory of the extension modules that ship with the launcher. It
/// gives the sidebar entry a real destination without pretending that an
/// external plugin runtime exists.
@NotNullByDefault
public final class MfmclPluginsPage extends VBox {
    /// Creates the launcher extension page.
    public MfmclPluginsPage() {
        getStyleClass().addAll("mfmcl-page", "mfmcl-plugins-page");

        Node[] cards = {
                pluginCard("微软账户", "Microsoft 正版账户登录、令牌刷新与档案切换。",
                        SVG.MICROSOFT),
                pluginCard("离线账户", "无需网络即可创建本地账户，适合测试与局域网游戏。",
                        SVG.PERSON),
                pluginCard("外置登录", "通过 Authlib-Injector 连接第三方认证服务器。",
                        SVG.PUBLIC),
                pluginCard("Modrinth 内容", "搜索并安装模组、整合包、资源包与光影。",
                        SVG.EXTENSION),
                pluginCard("整合包导入", "从本地 .zip 或 .mrpack 文件导入为新的实例。",
                        SVG.ARCHIVE)
        };

        GridPane grid = new GridPane();
        grid.getStyleClass().add("mfmcl-plugin-grid");
        grid.setHgap(16);
        grid.setVgap(16);
        for (int i = 0; i < 2; i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(50);
            column.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(column);
        }
        for (int i = 0; i < cards.length; i++) {
            Node card = cards[i];
            GridPane.setHgrow(card, Priority.ALWAYS);
            GridPane.setFillWidth(card, true);
            GridPane.setFillHeight(card, true);
            grid.add(card, i % 2, i / 2);
        }

        Label noteTitle = new Label("关于第三方插件");
        noteTitle.getStyleClass().add("mfmcl-panel-title");
        Label note = new Label("当前内部构建尚未启用第三方插件加载。这里展示的是随启动器内置的扩展能力；"
                + "插件清单、权限与热重载会在插件运行时完成后开放。");
        note.getStyleClass().add("mfmcl-panel-text");
        note.setWrapText(true);

        VBox notePanel = new VBox(6, noteTitle, note);
        notePanel.getStyleClass().add("mfmcl-panel");

        VBox body = new VBox(24, grid, notePanel);
        body.getStyleClass().add("mfmcl-page-body");

        getChildren().setAll(
                MfmclUi.header("启动器插件", "查看随启动器提供的扩展能力。"),
                MfmclUi.scroll(body));
    }

    /// Creates one extension card.
    private VBox pluginCard(String title, String description, SVG icon) {
        Label name = new Label(title);
        name.getStyleClass().add("mfmcl-plugin-title");
        Label status = new Label("内置");
        status.getStyleClass().add("mfmcl-plugin-status");

        HBox titleRow = new HBox(8, name, MfmclUi.spacer(), status);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label text = new Label(description);
        text.getStyleClass().add("mfmcl-plugin-text");
        text.setWrapText(true);
        VBox.setVgrow(text, Priority.ALWAYS);

        HBox top = new HBox(12, MfmclUi.iconTile(icon, "mfmcl-plugin-icon", 40), titleRow);
        top.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titleRow, Priority.ALWAYS);

        VBox card = new VBox(10, top, text);
        card.getStyleClass().add("mfmcl-plugin-card");
        card.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        return card;
    }
}
