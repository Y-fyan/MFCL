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
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;

/// The news column shown on the launch tab.
@NotNullByDefault
public final class MfmcNewsPane extends VBox {
    /// The list of news cards.
    private final VBox newsList = new VBox(10);

    /// Creates the news column.
    ///
    /// @param showDownload action that opens the download tab
    /// @param showSettings action that opens the settings tab
    public MfmcNewsPane(Runnable showDownload, Runnable showSettings) {
        getStyleClass().addAll("mfmc-card", "mfmc-news-pane");
        setPadding(new Insets(16));
        setSpacing(12);
        setFillWidth(true);

        Label title = new Label("新闻与公告");
        title.getStyleClass().add("mfmc-section-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refresh = new Button();
        refresh.setGraphic(SVG.REFRESH.createIcon(16));
        refresh.getStyleClass().add("mfmc-icon-button");
        refresh.setOnAction(event -> {
            refreshNews();
            Controllers.showToast("新闻已刷新");
        });

        HBox header = new HBox(8, title, spacer, refresh);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox hero = createHero(showDownload);

        newsList.setFillWidth(true);
        newsList.setMaxWidth(Double.MAX_VALUE);
        refreshNews();

        ScrollPane scroll = new ScrollPane(newsList);
        scroll.getStyleClass().add("mfmc-news-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        HBox links = createLinks(showDownload, showSettings);

        getChildren().setAll(header, hero, scroll, links);
    }

    /// Creates the hero card at the top of the news column.
    private VBox createHero(Runnable showDownload) {
        Label title = new Label("欢迎使用 MFMC Launcher");
        title.getStyleClass().add("mfmc-hero-title");

        Label body = new Label("选择版本，点击启动。拖入 .zip 或 .mrpack 整合包即可自动安装。");
        body.getStyleClass().add("mfmc-hero-text");
        body.setWrapText(true);

        Button button = new Button("浏览整合包");
        button.getStyleClass().add("mfmc-primary-button");
        button.setOnAction(event -> showDownload.run());

        VBox hero = new VBox(6, title, body, button);
        hero.getStyleClass().add("mfmc-hero");
        return hero;
    }

    /// Rebuilds the news cards.
    private void refreshNews() {
        newsList.getChildren().setAll(
                createNewsCard(
                        "公告",
                        "下载限制已解除",
                        "上游已在 main 分支移除下载页面的临时拦截，当前内部构建同时内置了旁路开关。",
                        "2026-10-22"
                ),
                createNewsCard(
                        "内部",
                        "微软正版登录已临时可用",
                        "当前构建已内置登录配置，仅限 MFMC 内部测试，请勿对外分发。",
                        "2026-10-23"
                ),
                createNewsCard(
                        "提示",
                        "整合包拖入窗口即可安装",
                        "支持 .zip 与 .mrpack，安装向导会自动下载缺失的依赖和游戏版本。",
                        "2026-10-23"
                ),
                createNewsCard(
                        "指南",
                        "如何选择游戏版本",
                        "在左侧版本列表中选择一个实例，然后点击方形启动按钮。",
                        "2026-10-23"
                )
        );
    }

    /// Creates one news card.
    private VBox createNewsCard(String tag, String title, String summary, String date) {
        Label tagLabel = new Label(tag);
        tagLabel.getStyleClass().add("mfmc-news-tag");

        Label dateLabel = new Label(date);
        dateLabel.getStyleClass().add("mfmc-news-date");

        HBox meta = new HBox(8, tagLabel, dateLabel);
        meta.setAlignment(Pos.CENTER_LEFT);

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("mfmc-news-title");
        titleLabel.setWrapText(true);

        Label summaryLabel = new Label(summary);
        summaryLabel.getStyleClass().add("mfmc-news-summary");
        summaryLabel.setWrapText(true);

        VBox card = new VBox(4, meta, titleLabel, summaryLabel);
        card.getStyleClass().add("mfmc-news-card");
        card.setFillWidth(true);
        return card;
    }

    /// Creates the common-link row.
    private HBox createLinks(Runnable showDownload, Runnable showSettings) {
        Hyperlink download = new Hyperlink("下载游戏");
        download.getStyleClass().add("mfmc-link");
        download.setOnAction(event -> showDownload.run());

        Hyperlink settings = new Hyperlink("打开设置");
        settings.getStyleClass().add("mfmc-link");
        settings.setOnAction(event -> showSettings.run());

        Hyperlink about = new Hyperlink("关于 MFMC");
        about.getStyleClass().add("mfmc-link");
        about.setOnAction(event -> Controllers.dialog(Metadata.FULL_NAME + "\n" + Metadata.VERSION));

        HBox links = new HBox(12, download, settings, about);
        links.setAlignment(Pos.CENTER_LEFT);
        return links;
    }
}
