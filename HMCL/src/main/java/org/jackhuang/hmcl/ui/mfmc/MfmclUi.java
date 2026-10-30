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
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;

/// Shared building blocks for the MFMCL light workspace.
///
/// The official MFMCL design keeps the page title in a fixed header and lets
/// only the content below it scroll. These helpers encode that structure once
/// so every page produces the same spacing and typography.
@NotNullByDefault
final class MfmclUi {
    private MfmclUi() {
    }

    /// Builds a fixed page header with a title, subtitle and optional actions.
    ///
    /// @param title    the page heading
    /// @param subtitle the supporting sentence below the heading
    /// @param actions  controls aligned to the right edge
    /// @return the header row
    static HBox header(String title, String subtitle, Node... actions) {
        Label heading = new Label(title);
        heading.getStyleClass().add("mfmcl-page-heading");

        Label description = new Label(subtitle);
        description.getStyleClass().add("mfmcl-page-subheading");
        description.setWrapText(true);

        VBox copy = new VBox(6, heading, description);
        copy.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(copy, Priority.ALWAYS);

        HBox header = new HBox(16, copy);
        header.getChildren().addAll(actions);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("mfmcl-page-header");
        return header;
    }

    /// Wraps page content in the transparent scrolling viewport used by MFMCL.
    ///
    /// @param body the content node
    /// @return a scroll pane that grows with the page
    static ScrollPane scroll(Node body) {
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("mfmcl-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return scroll;
    }

    /// Creates a primary action button.
    ///
    /// @param text   the button label
    /// @param icon   the leading icon, or `null`
    /// @param action the click action
    /// @return the configured button
    static Button primaryButton(String text, SVG icon, Runnable action) {
        Button button = new Button(text);
        if (icon != null) {
            button.setGraphic(icon.createIcon(16));
        }
        button.getStyleClass().add("mfmcl-primary-button");
        button.setOnAction(event -> action.run());
        return button;
    }

    /// Creates a quiet secondary action button.
    ///
    /// @param text   the button label
    /// @param icon   the leading icon, or `null`
    /// @param action the click action
    /// @return the configured button
    static Button secondaryButton(String text, SVG icon, Runnable action) {
        Button button = new Button(text);
        if (icon != null) {
            button.setGraphic(icon.createIcon(16));
        }
        button.getStyleClass().add("mfmcl-secondary-button");
        button.setOnAction(event -> action.run());
        return button;
    }

    /// Creates a square icon tile used by cards and navigation rows.
    ///
    /// @param icon       the icon to draw
    /// @param styleClass the tile style class
    /// @param size       the tile edge length
    /// @return the icon tile
    static StackPane iconTile(SVG icon, String styleClass, double size) {
        StackPane tile = new StackPane(icon.createIcon(size * 0.5));
        tile.getStyleClass().add(styleClass);
        tile.setMinSize(size, size);
        tile.setPrefSize(size, size);
        tile.setMaxSize(size, size);
        return tile;
    }

    /// Creates a flexible spacer.
    ///
    /// @return the spacer region
    static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    /// Creates a label with the given style class.
    ///
    /// @param text       the label text
    /// @param styleClass the style class
    /// @return the label
    static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }
}
