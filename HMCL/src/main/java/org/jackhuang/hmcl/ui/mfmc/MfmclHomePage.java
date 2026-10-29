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
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.game.HMCLGameInstance;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.HMCLGameRepositorySnapshot;
import org.jackhuang.hmcl.game.TexturesLoader;
import org.jackhuang.hmcl.java.JavaRuntime;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.util.javafx.BindingMapping;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// The MFMCL overview page.
@NotNullByDefault
public final class MfmclHomePage extends VBox {
    private final Runnable launchSelected;
    private final Label badge = new Label();
    private final Label instanceTitle = new Label();
    private final Label instanceDescription = new Label();
    private final Label instanceCount = new Label();
    private final Label javaValue = new Label();
    private final Label accountName = new Label();
    private final Label accountType = new Label();
    private final Button launchButton = new Button();
    private final Canvas avatar = new Canvas(34, 34);

    /// The repository snapshot used to refresh the overview after loading.
    private final ObservableValue<HMCLGameRepositorySnapshot> repositorySnapshot =
            BindingMapping.of(GameDirectoryManager.selectedRepositoryProperty())
                    .flatMap(HMCLGameRepository::snapshotProperty);

    public MfmclHomePage(Runnable showDownloads, Runnable showLibrary, Runnable showSettings,
                         Runnable showAccounts, Runnable launchSelected) {
        this.launchSelected = launchSelected;
        getStyleClass().addAll("mfmcl-page", "mfmcl-home-page");
        setSpacing(16);

        Label eyebrow = new Label("你的世界，由此开始");
        eyebrow.getStyleClass().add("mfmcl-eyebrow");
        Label title = new Label("欢迎回来。");
        title.getStyleClass().add("mfmcl-page-title");
        Label description = new Label("把准备工作交给 MFMCL，留更多时间给你的世界。");
        description.getStyleClass().add("mfmcl-page-description");
        VBox heading = new VBox(4, eyebrow, title, description);

        VBox heroContent = new VBox(10);
        heroContent.getStyleClass().add("mfmcl-hero-content");
        badge.getStyleClass().add("mfmcl-hero-badge");
        Label kicker = new Label("MINECRAFT JAVA EDITION");
        kicker.getStyleClass().add("mfmcl-hero-kicker");
        instanceTitle.getStyleClass().add("mfmcl-hero-title");
        instanceTitle.setWrapText(true);
        instanceDescription.getStyleClass().add("mfmcl-hero-description");
        instanceDescription.setWrapText(true);
        launchButton.getStyleClass().add("mfmcl-primary-button");
        Button browse = new Button("查看实例库");
        browse.getStyleClass().add("mfmcl-quiet-button");
        browse.setOnAction(event -> showLibrary.run());
        HBox actions = new HBox(10, launchButton, browse);
        actions.setAlignment(Pos.CENTER_LEFT);
        heroContent.getChildren().setAll(badge, kicker, instanceTitle, instanceDescription, actions);

        VBox note = new VBox(4);
        note.getStyleClass().add("mfmcl-hero-note");
        Label noteTitle = new Label("每个实例都拥有独立的模组与世界目录");
        noteTitle.getStyleClass().add("mfmcl-hero-note-title");
        Label noteText = new Label("你的世界设置彼此独立，不会互相覆盖。");
        noteText.getStyleClass().add("mfmcl-muted");
        note.getChildren().setAll(noteTitle, noteText);

        VBox hero = new VBox(heroContent, note);
        hero.getStyleClass().add("mfmcl-hero");

        Label quickTitle = new Label("快速开始");
        quickTitle.getStyleClass().add("mfmcl-section-title");
        Label quickCaption = new Label("常用操作都在这里");
        quickCaption.getStyleClass().add("mfmcl-muted");
        HBox quickHeader = new HBox(10, new VBox(2, quickTitle, quickCaption));
        quickHeader.setAlignment(Pos.CENTER_LEFT);

        HBox quick = new HBox(12,
                quickCard("创建游戏实例", "选择版本与模组加载器", SVG.ADD, showDownloads),
                quickCard("打开实例库", "查看已安装版本、模组与运行目录", SVG.PACKAGE2, showLibrary),
                quickCard("检查启动环境", "Java、存储空间与下载源", SVG.SETTINGS, showSettings));
        quick.setFillHeight(true);

        VBox readiness = new VBox(10);
        readiness.getStyleClass().add("mfmcl-panel");
        Label readinessTitle = new Label("启动环境");
        readinessTitle.getStyleClass().add("mfmcl-panel-title");
        javaValue.getStyleClass().add("mfmcl-readiness-value");
        instanceCount.getStyleClass().add("mfmcl-readiness-value");
        readiness.getChildren().setAll(readinessTitle,
                readinessRow("Java 运行环境", javaValue),
                readinessRow("游戏实例", instanceCount));

        VBox accountPanel = new VBox(10);
        accountPanel.getStyleClass().add("mfmcl-panel");
        Label accountTitle = new Label("账户");
        accountTitle.getStyleClass().add("mfmcl-panel-title");
        StackPane avatarFrame = new StackPane(avatar);
        avatarFrame.getStyleClass().add("mfmcl-home-avatar");
        accountName.getStyleClass().add("mfmcl-account-name");
        accountType.getStyleClass().add("mfmcl-muted");
        VBox accountCopy = new VBox(1, accountName, accountType);
        HBox accountRow = new HBox(10, avatarFrame, accountCopy);
        accountRow.setAlignment(Pos.CENTER_LEFT);
        Button manageAccount = new Button("管理账户");
        manageAccount.getStyleClass().add("mfmcl-secondary-button");
        manageAccount.setOnAction(event -> showAccounts.run());
        accountPanel.getChildren().setAll(accountTitle, accountRow, manageAccount);

        HBox lower = new HBox(12, readiness, accountPanel);
        HBox.setHgrow(readiness, Priority.ALWAYS);
        HBox.setHgrow(accountPanel, Priority.ALWAYS);

        getChildren().setAll(heading, hero, quickHeader, quick, lower);

        FXUtils.onChangeAndOperate(Accounts.selectedAccountProperty(), this::updateAccount);
        FXUtils.onChangeAndOperate(repositorySnapshot, snapshot -> refresh());
    }

    private HBox readinessRow(String label, Label value) {
        Label name = new Label(label);
        name.getStyleClass().add("mfmcl-readiness-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(8, name, spacer, value);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("mfmcl-readiness-row");
        return row;
    }

    private VBox quickCard(String title, String subtitle, SVG icon, Runnable action) {
        VBox card = new VBox(6);
        card.getStyleClass().add("mfmcl-quick-card");
        HBox top = new HBox(8, icon.createIcon(18));
        top.setAlignment(Pos.CENTER_LEFT);
        Label name = new Label(title);
        name.getStyleClass().add("mfmcl-quick-title");
        Label note = new Label(subtitle);
        note.getStyleClass().add("mfmcl-muted");
        note.setWrapText(true);
        card.getChildren().setAll(top, name, note);
        card.setOnMouseClicked(event -> action.run());
        HBox.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    /// Refreshes the overview from the current launcher state.
    public void refresh() {
        HMCLGameInstance selected = GameDirectoryManager.getSelectedInstance();
        List<HMCLGameInstance> instances = GameDirectoryManager.getSelectedRepository()
                .getDisplayInstances().toList();

        if (selected == null) {
            badge.setText("准备好开始");
            instanceTitle.setText("一片空白，正好开始。");
            instanceDescription.setText("先创建一个游戏实例。版本、Java 和安装步骤，我们会陪你一起完成。");
            launchButton.setText("创建游戏实例");
            launchButton.setOnAction(event -> launchSelected.run());
        } else {
            badge.setText("可以启动");
            instanceTitle.setText(selected.getId().toString());
            String version = selected.getVersion().toString();
            String loaders = selected.getModLoaders().isEmpty()
                    ? "原版"
                    : selected.getModLoaders().stream().map(loader -> loader.displayName()).sorted().reduce((a, b) -> a + " + " + b).orElse("原版");
            instanceDescription.setText("Minecraft " + version + " · " + loaders + " · 独立游戏目录");
            launchButton.setText("启动游戏");
            launchButton.setOnAction(event -> launchSelected.run());
        }

        instanceCount.setText(instances.isEmpty() ? "尚未创建" : instances.size() + " 个实例");
        javaValue.setText("Java " + JavaRuntime.CURRENT_VERSION);
        updateAccount(Accounts.getSelectedAccount());
    }

    private void updateAccount(@Nullable Account account) {
        TexturesLoader.unbindAvatar(avatar);
        if (account == null) {
            TexturesLoader.drawAvatar(avatar, TexturesLoader.getDefaultSkinImage());
            accountName.setText("还没有添加账户");
            accountType.setText("添加账户后即可开始启动游戏");
        } else {
            TexturesLoader.bindAvatar(avatar, account);
            String name = account.getProfileName();
            accountName.setText(name == null || name.isBlank() ? account.getProfileID().toString() : name);
            accountType.setText(Accounts.getLocalizedLoginTypeName(Accounts.getAccountFactory(account)));
        }
    }
}
