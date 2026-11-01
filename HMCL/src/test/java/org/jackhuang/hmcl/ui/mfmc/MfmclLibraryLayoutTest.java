/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.ui.mfmc;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class MfmclLibraryLayoutTest {
    @Test void emptyStateStaysCenteredAcrossViewportSizes() throws Exception {
        org.jackhuang.hmcl.JavaFXLauncher.start(); assumeTrue(org.jackhuang.hmcl.JavaFXLauncher.isStarted());
        FutureTask<Void> task = new FutureTask<>(() -> {
            Region empty = new Region(); empty.setPrefSize(300, 180); empty.setMaxSize(300, 180);
            GridPane grid = new GridPane(); grid.getChildren().add(empty); VBox.setVgrow(grid, Priority.ALWAYS);
            VBox body = new VBox(grid); ScrollPane scroll = MfmclUi.scroll(body);
            VBox root = new VBox(scroll); new Scene(root);
            MfmclLibraryPage.setEmptyLayout(scroll, grid, true);
            for (double[] size : new double[][]{{900, 600}, {1400, 800}, {500, 400}}) {
                root.resize(size[0], size[1]); root.applyCss(); root.layout();
                Bounds item = empty.localToScene(empty.getBoundsInLocal());
                Bounds viewport = scroll.localToScene(scroll.getViewportBounds());
                assertEquals((viewport.getMinX() + viewport.getMaxX()) / 2, (item.getMinX() + item.getMaxX()) / 2, 2.0);
                assertEquals((viewport.getMinY() + viewport.getMaxY()) / 2, (item.getMinY() + item.getMaxY()) / 2, 2.0);
            }
            MfmclLibraryPage.setEmptyLayout(scroll, grid, false); assertFalse(scroll.isFitToHeight());
            return null;
        });
        Platform.runLater(task); task.get(30, TimeUnit.SECONDS);
    }
}
