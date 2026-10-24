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

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.util.Duration;
import org.jackhuang.hmcl.ui.animation.Motion;
import org.jetbrains.annotations.NotNullByDefault;

/// Small reusable motion helpers for the MFMC UI.
@NotNullByDefault
final class MfmcMotion {
    /// Vertical offset, in pixels, that entering pages slide up from.
    private static final double ENTER_OFFSET = 10;

    /// Fades a node in while sliding it up slightly; used when switching pages.
    ///
    /// @param node the node that has just been added to the scene graph
    static void enter(Node node) {
        node.setOpacity(0);
        node.setTranslateY(ENTER_OFFSET);

        FadeTransition fade = new FadeTransition(Duration.millis(260), node);
        fade.setToValue(1);
        fade.setInterpolator(Motion.EMPHASIZED_DECELERATE);

        TranslateTransition slide = new TranslateTransition(Duration.millis(260), node);
        slide.setToY(0);
        slide.setInterpolator(Motion.EMPHASIZED_DECELERATE);

        new ParallelTransition(fade, slide).play();
    }

    /// Adds a gentle scale-up on hover and a press-in scale to a node.
    ///
    /// @param node the node to animate
    /// @param hoverScale the scale applied while hovered
    static void installHoverScale(Node node, double hoverScale) {
        node.hoverProperty().addListener((obs, old, hovered) ->
                scaleTo(node, hovered ? hoverScale : 1.0, 160));
        node.pressedProperty().addListener((obs, old, pressed) ->
                scaleTo(node, pressed ? 0.97 : (node.isHover() ? hoverScale : 1.0), 90));
    }

    /// Animates a node's uniform scale to a target value.
    private static void scaleTo(Node node, double target, double millis) {
        ScaleTransition transition = new ScaleTransition(Duration.millis(millis), node);
        transition.setToX(target);
        transition.setToY(target);
        transition.setInterpolator(Motion.STANDARD);
        transition.play();
    }

    private MfmcMotion() {
    }
}
