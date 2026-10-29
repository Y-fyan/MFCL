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
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.game.HMCLGameInstance;
import org.jackhuang.hmcl.addon.mod.ModLoaderType;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.instances.Instances;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.stream.Collectors;

/// The MFMCL instance detail page.
///
/// Keeps instance management inside the sidebar shell instead of navigating to
/// the classic window pages.
@NotNullByDefault
public final class MfmclInstancePage extends VBox {
    /// The instance being inspected.
    private final HMCLGameInstance instance;

    /// Returns to the library after an operation removed or renamed the instance.
    private final Runnable onChanged;

    /// Creates the instance detail page.
    ///
    /// @param instance  the instance to inspect
    /// @param onChanged called after a destructive operation
    public MfmclInstancePage(HMCLGameInstance instance, Runnable onChanged) {
        this.instance = instance;
        this.onChanged = onChanged;
        getStyleClass().addAll("mfmcl-page", "mfmcl-instance-page");
        setSpacing(16);

        ImageView icon = new ImageView(instance.getIconImage());
        icon.setFitWidth(64);
        icon.setFitHeight(64);
        icon.setPreserveRatio(true);
        icon.setSmooth(true);

        Label name = new Label(instance.getId().toString());
        name.getStyleClass().add("mfmcl-page-title");
        Label meta = new Label("Minecraft " + instance.getVersion() + loaderSuffix(instance));
        meta.getStyleClass().add("mfmcl-page-description");

        Button launch = new Button("启动游戏");
        launch.setGraphic(SVG.ROCKET_LAUNCH.createIcon(16));
        launch.getStyleClass().add("mfmcl-primary-button");
        launch.setOnAction(event -> {
            GameDirectoryManager.setSelectedInstance(instance);
            Instances.launch(instance);
        });

        VBox copy = new VBox(4, name, meta, launch);
        copy.setAlignment(Pos.CENTER_LEFT);
        HBox header = new HBox(16, icon, copy);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("mfmcl-instance-header");

        VBox details = new VBox(0);
        details.getStyleClass().add("mfmcl-panel");
        Label detailsTitle = new Label("实例信息");
        detailsTitle.getStyleClass().add("mfmcl-panel-title");
        details.getChildren().setAll(
                detailsTitle,
                infoRow("游戏版本", instance.getVersion().toString()),
                infoRow("模组加载器", loaderNames(instance)),
                infoRow("实例目录", instance.getInstanceRoot().toAbsolutePath().toString()),
                infoRow("运行目录", instance.getRunDirectory().toAbsolutePath().toString())
        );

        Label manageTitle = new Label("管理操作");
        manageTitle.getStyleClass().add("mfmcl-section-title");

        VBox actions = new VBox(10);
        actions.getStyleClass().add("mfmcl-panel");
        actions.getChildren().setAll(
                actionRow("打开游戏目录", "打开该实例的 mods、resourcepacks 与世界目录",
                        SVG.FOLDER, () -> Instances.openFolder(instance)),
                actionRow("导出实例", "打包为整合包，便于备份或分享",
                        SVG.PACKAGE2, () -> Instances.exportInstance(instance)),
                actionRow("重命名实例", "只修改实例名称，不影响已安装的内容",
                        SVG.EDIT, () -> Instances.renameInstance(instance).thenRun(onChanged)),
                actionRow("删除实例", "从磁盘移除该实例及其运行目录",
                        SVG.DELETE, () -> {
                            Instances.deleteInstance(instance);
                            onChanged.run();
                        }),
                actionRow("高级设置", "进入经典实例设置页，调整内存、Java 与启动参数",
                        SVG.SETTINGS, () -> Instances.modifyGameSettings(instance))
        );

        getChildren().setAll(header, details, manageTitle, actions);
    }

    /// Creates an information row.
    private static HBox infoRow(String label, String value) {
        Label key = new Label(label);
        key.getStyleClass().add("mfmcl-info-key");
        Label text = new Label(value);
        text.getStyleClass().add("mfmcl-info-value");
        text.setWrapText(true);
        HBox.setHgrow(text, Priority.ALWAYS);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(12, key, spacer, text);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("mfmcl-info-row");
        return row;
    }

    /// Creates an action row.
    private static HBox actionRow(String title, String description, SVG icon, Runnable action) {
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

    /// Returns the loader display names of the instance.
    private static String loaderNames(HMCLGameInstance instance) {
        String names = instance.getModLoaders().stream()
                .map(ModLoaderType::displayName)
                .sorted()
                .collect(Collectors.joining(" + "));
        return names.isEmpty() ? "原版" : names;
    }

    /// Returns the loader suffix used in the header.
    private static String loaderSuffix(HMCLGameInstance instance) {
        return instance.getModLoaders().isEmpty() ? "" : " · " + loaderNames(instance);
    }
}
