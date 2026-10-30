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

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskExecutor;
import org.jackhuang.hmcl.task.TaskListener;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;

/// A session-scoped registry of launcher tasks shown by the MFMCL download page.
///
/// HMCL starts installs and downloads through `Controllers.taskDialog`, which
/// only owns a short-lived progress dialog. The MFMCL workspace needs the same
/// work to remain visible after that dialog closes, so this registry attaches a
/// listener to every task executor and mirrors its state into JavaFX properties.
///
/// The registry is intentionally memory-only: it is a view of the current
/// session, not a persistent download queue.
@NotNullByDefault
public final class MfmclTaskCenter {
    /// The maximum number of task entries kept for the session.
    private static final int MAX_ENTRIES = 20;

    /// All tasks started in this session, newest first.
    private static final ObservableList<TaskEntry> entries = FXCollections.observableArrayList();

    /// The number of tasks that have not stopped yet.
    private static final ReadOnlyIntegerWrapper activeCount = new ReadOnlyIntegerWrapper();

    static {
        activeCount.set(0);
    }

    private MfmclTaskCenter() {
    }

    /// Returns the session task list. The list is mutated on the JavaFX thread.
    public static ObservableList<TaskEntry> getEntries() {
        return entries;
    }

    /// Returns the number of tasks that are still running.
    public static ReadOnlyIntegerProperty activeCountProperty() {
        return activeCount.getReadOnlyProperty();
    }

    /// Starts mirroring a task executor into the task list.
    ///
    /// @param executor the executor started by the launcher
    /// @param title    the localized dialog title used as the task name
    /// @param rootTask the root task whose progress should drive the card, or
    ///                 `null` when only the executor is available
    public static void register(TaskExecutor executor, String title, @Nullable Task<?> rootTask) {
        TaskEntry entry = new TaskEntry(title, executor);

        if (rootTask != null) {
            entry.progress.bind(Bindings.createDoubleBinding(
                    () -> clampProgress(rootTask.progressProperty().get()),
                    rootTask.progressProperty()));
        }

        executor.addTaskListener(new TaskListener() {
            /// File tasks currently shown under the entry, keyed by identity.
            private final Map<Task<?>, FileEntry> runningFiles = new IdentityHashMap<>();

            @Override
            public void onStart() {
                Platform.runLater(() -> entry.setStatus("排队中"));
            }

            @Override
            public void onRunning(Task<?> task) {
                if (task instanceof FileDownloadTask fileTask) {
                    Platform.runLater(() -> {
                        FileEntry file = new FileEntry(
                                fileTask.getName(),
                                fileTask.getPrimaryDisplayUrl(),
                                clampProgress(fileTask.progressProperty().get()));
                        file.progress.bind(Bindings.createDoubleBinding(
                                () -> clampProgress(fileTask.progressProperty().get()),
                                fileTask.progressProperty()));
                        runningFiles.put(fileTask, file);
                        entry.files.add(file);
                        entry.setStatus("正在下载");
                        if (rootTask == null) {
                            // Without a root task, the average of the active
                            // file transfers is the best available progress.
                            file.progress.addListener((observable, oldValue, newValue) -> entry.progress.set(
                                    entry.files.stream()
                                            .mapToDouble(fileEntry -> fileEntry.progress.get())
                                            .average()
                                            .orElse(0.0)));
                        }
                    });
                } else {
                    Platform.runLater(() -> entry.setStatus("处理中"));
                }
            }

            @Override
            public void onFinished(Task<?> task) {
                FileEntry file = runningFiles.remove(task);
                if (file != null) {
                    Platform.runLater(() -> entry.files.remove(file));
                }
            }

            @Override
            public void onStop(boolean success, TaskExecutor taskExecutor) {
                Platform.runLater(() -> {
                    entry.files.clear();
                    if (success) {
                        entry.setStatus("已完成");
                        if (!entry.progress.isBound()) {
                            entry.progress.set(1.0);
                        }
                    } else if (taskExecutor.isCancelled()) {
                        entry.setStatus("已取消");
                    } else {
                        entry.setStatus("失败");
                    }
                    entry.active.set(false);
                    updateActiveCount();
                });
            }
        });

        Platform.runLater(() -> {
            entries.add(0, entry);
            while (entries.size() > MAX_ENTRIES) {
                TaskEntry oldest = entries.get(entries.size() - 1);
                if (oldest.active.get()) {
                    break;
                }
                entries.remove(oldest);
            }
            updateActiveCount();
        });
    }

    /// Recomputes the number of running tasks.
    private static void updateActiveCount() {
        int count = 0;
        for (TaskEntry entry : entries) {
            if (entry.active.get()) {
                count++;
            }
        }
        activeCount.set(count);
    }

    /// Clamps a task progress value into the `[0, 1]` range.
    private static double clampProgress(double value) {
        if (Double.isNaN(value) || value < 0) {
            return 0.0;
        }
        return Math.min(value, 1.0);
    }

    /// One launcher task shown as a card on the download page.
    public static final class TaskEntry {
        /// The dialog title of the task.
        private final StringProperty title;

        /// Overall progress in the `[0, 1]` range.
        private final DoubleProperty progress = new SimpleDoubleProperty(0.0);

        /// The localized task state.
        private final StringProperty status = new SimpleStringProperty("排队中");

        /// Whether the task is still running.
        private final BooleanProperty active = new SimpleBooleanProperty(true);

        /// Files currently being transferred by this task.
        private final ObservableList<FileEntry> files = FXCollections.observableArrayList();

        /// The executor used to cancel the task.
        private final TaskExecutor executor;

        private TaskEntry(String title, TaskExecutor executor) {
            this.title = new SimpleStringProperty(title);
            this.executor = executor;
        }

        /// Returns the task title.
        public StringProperty titleProperty() {
            return title;
        }

        /// Returns the overall progress property.
        public DoubleProperty progressProperty() {
            return progress;
        }

        /// Returns the localized state property.
        public StringProperty statusProperty() {
            return status;
        }

        /// Returns whether the task is still running.
        public ReadOnlyBooleanProperty activeProperty() {
            return active;
        }

        /// Returns the files currently being transferred.
        public ObservableList<FileEntry> getFiles() {
            return files;
        }

        /// Cancels the task if it is still running.
        public void cancel() {
            if (active.get()) {
                executor.cancel();
            }
        }

        /// Updates the localized state.
        private void setStatus(String value) {
            status.set(value);
        }
    }

    /// One file transfer inside a task.
    public static final class FileEntry {
        /// The file name shown as the row title.
        private final StringProperty name;

        /// The remote URL shown under the title.
        private final StringProperty detail;

        /// Transfer progress in the `[0, 1]` range.
        private final DoubleProperty progress = new SimpleDoubleProperty(0.0);

        private FileEntry(String name, String detail, double progress) {
            this.name = new SimpleStringProperty(name);
            this.detail = new SimpleStringProperty(detail);
            this.progress.set(progress);
        }

        /// Returns the file name property.
        public StringProperty nameProperty() {
            return name;
        }

        /// Returns the local path property.
        public StringProperty detailProperty() {
            return detail;
        }

        /// Returns the transfer progress property.
        public DoubleProperty progressProperty() {
            return progress;
        }
    }
}
