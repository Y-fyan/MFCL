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

import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.HashSet;
import java.util.Set;

/// The MFMCL download task page.
///
/// Shows one card per launcher task and a shared list of the files currently
/// being transferred. Task state comes from [MfmclTaskCenter], so the page
/// reflects real installs and downloads instead of placeholder data.
@NotNullByDefault
public final class MfmclDownloadsPage extends VBox {
    /// Opens the content store from the empty state.
    private final Runnable openStore;

    /// The task card container.
    private final VBox taskList = new VBox(20);

    /// The "正在下载的文件" section.
    private final VBox fileSection = new VBox(14);

    /// The file row container.
    private final VBox fileList = new VBox(22);

    /// The empty-state node.
    private final Node emptyState;

    /// Entries whose file list is already observed.
    private final Set<MfmclTaskCenter.TaskEntry> observedEntries = new HashSet<>();

    /// Creates the download task page.
    ///
    /// @param openStore opens the content store from the empty state
    public MfmclDownloadsPage(Runnable openStore) {
        this.openStore = openStore;
        getStyleClass().addAll("mfmcl-page", "mfmcl-downloads-page");

        taskList.getStyleClass().add("mfmcl-task-list");
        fileSection.getStyleClass().add("mfmcl-file-section");

        Label fileTitle = new Label("正在下载的文件");
        fileTitle.getStyleClass().add("mfmcl-file-section-title");
        fileSection.getChildren().setAll(fileTitle, fileList);

        emptyState = createEmptyState();

        VBox body = new VBox(24, taskList, fileSection);
        body.getStyleClass().add("mfmcl-page-body");

        getChildren().setAll(
                MfmclUi.header("下载任务", "一个任务包含多个文件；下方「正在下载的文件」只显示当前传输的那几个。"),
                MfmclUi.scroll(body));

        MfmclTaskCenter.getEntries().addListener((ListChangeListener<MfmclTaskCenter.TaskEntry>) change -> render());
        render();
    }

    /// Rebuilds the task cards and file list.
    private void render() {
        taskList.getChildren().clear();

        if (MfmclTaskCenter.getEntries().isEmpty()) {
            taskList.getChildren().setAll(emptyState);
            fileSection.setVisible(false);
            fileSection.setManaged(false);
            return;
        }

        for (MfmclTaskCenter.TaskEntry entry : MfmclTaskCenter.getEntries()) {
            taskList.getChildren().add(taskCard(entry));
            if (observedEntries.add(entry)) {
                entry.getFiles().addListener((ListChangeListener<MfmclTaskCenter.FileEntry>) change -> renderFiles());
            }
        }

        renderFiles();
    }

    /// Rebuilds the shared list of in-flight files.
    private void renderFiles() {
        fileList.getChildren().clear();

        for (MfmclTaskCenter.TaskEntry entry : MfmclTaskCenter.getEntries()) {
            for (MfmclTaskCenter.FileEntry file : entry.getFiles()) {
                fileList.getChildren().add(fileRow(file));
            }
        }

        boolean visible = !fileList.getChildren().isEmpty();
        fileSection.setVisible(visible);
        fileSection.setManaged(visible);
    }

    /// Creates one task card.
    private Node taskCard(MfmclTaskCenter.TaskEntry entry) {
        Label name = new Label();
        name.textProperty().bind(entry.titleProperty());
        name.getStyleClass().add("mfmcl-task-title");
        name.setWrapText(true);

        ProgressBar progress = new ProgressBar();
        progress.progressProperty().bind(entry.progressProperty());
        progress.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(progress, Priority.ALWAYS);

        Label percent = new Label();
        percent.textProperty().bind(Bindings.createStringBinding(
                () -> Math.round(entry.progressProperty().get() * 100) + "%",
                entry.progressProperty()));
        percent.getStyleClass().add("mfmcl-task-percent");
        percent.setMinWidth(48);
        percent.setAlignment(Pos.CENTER_RIGHT);

        HBox progressRow = new HBox(16, progress, percent);
        progressRow.setAlignment(Pos.CENTER_LEFT);

        Label status = new Label();
        status.textProperty().bind(entry.statusProperty());
        status.getStyleClass().add("mfmcl-task-status");

        Button cancel = new Button("取消");
        cancel.getStyleClass().add("mfmcl-ghost-button");
        cancel.setOnAction(event -> entry.cancel());
        cancel.visibleProperty().bind(entry.activeProperty());
        cancel.managedProperty().bind(entry.activeProperty());

        HBox footer = new HBox(12, status, MfmclUi.spacer(), cancel);
        footer.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(14, name, progressRow, footer);
        card.getStyleClass().add("mfmcl-task-card");
        return card;
    }

    /// Creates one in-flight file row.
    private Node fileRow(MfmclTaskCenter.FileEntry file) {
        Label name = new Label();
        name.textProperty().bind(Bindings.createStringBinding(
                () -> "资源 " + file.nameProperty().get(),
                file.nameProperty()));
        name.getStyleClass().add("mfmcl-file-title");
        name.setWrapText(true);

        Label detail = new Label();
        detail.textProperty().bind(Bindings.createStringBinding(
                () -> file.detailProperty().get() + " · " + Math.round(file.progressProperty().get() * 100) + "%",
                file.detailProperty(), file.progressProperty()));
        detail.getStyleClass().add("mfmcl-file-detail");
        detail.setWrapText(true);

        VBox row = new VBox(4, name, detail);
        row.getStyleClass().add("mfmcl-file-row");
        return row;
    }

    /// Creates the empty state shown when no task has started this session.
    private Node createEmptyState() {
        Label title = new Label("暂无下载任务");
        title.getStyleClass().add("mfmcl-empty-title");
        Label hint = new Label("从内容商店安装游戏或模组后，任务会显示在这里。");
        hint.getStyleClass().add("mfmcl-empty-text");
        hint.setWrapText(true);

        VBox box = new VBox(8, SVG.DOWNLOAD.createIcon(48), title, hint,
                MfmclUi.primaryButton("前往内容商店", null, openStore));
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("mfmcl-empty-state");
        box.setMaxWidth(420);
        return box;
    }
}
