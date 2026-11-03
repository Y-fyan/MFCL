/*
 * MFCL Launcher
 * Copyright (C) 2026 MFCL contributors
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
package org.jackhuang.hmcl.ui;

import javafx.animation.AnimationTimer;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.function.DoubleUnaryOperator;
import java.util.function.DoublePredicate;

/// Smooths discrete mouse wheels at most once per rendered frame, not every 3 ms.
/// Pixel/touchpad/inertia events retain JavaFX's native behavior and event routing.
@NotNullByDefault
final class FrameScrollSupport {
    /// Marks a control that already owns a scroller, preventing duplicate event handlers.
    private static final Object KEY = new Object();

    /// Prevents instantiation.
    private FrameScrollSupport() { }

    /// Installs wheel smoothing after child controls have had a chance to consume input.
    static void install(ScrollPane pane) {
        install(pane, pixels -> {
            if (pane.getContent() == null) return 0;
            double overflow = pane.getContent().getLayoutBounds().getHeight() - pane.getViewportBounds().getHeight();
            double range = pane.getVmax() - pane.getVmin();
            if (!(overflow > 0) || !(range > 0)) return 0;
            double before = pane.getVvalue();
            pane.setVvalue(Math.max(pane.getVmin(), Math.min(pane.getVmax(), before + pixels / overflow * range)));
            return (pane.getVvalue() - before) / range * overflow;
        }, pixels -> pane.getContent() != null
                && pane.getContent().getLayoutBounds().getHeight() > pane.getViewportBounds().getHeight()
                && (pixels > 0 ? pane.getVvalue() < pane.getVmax() : pane.getVvalue() > pane.getVmin()));
    }

    /// Installs a single pre-default handler on a virtualized list's flow.
    static void install(VirtualFlow<?> flow) {
        if (flow.isVertical()) install(flow, flow::scrollPixels,
                pixels -> pixels > 0 ? flow.getPosition() < 1 : flow.getPosition() > 0);
    }

    /// Distinguishes wheel line events from pixel-based or OS-supplied gesture inertia.
    static boolean isDiscreteWheel(ScrollEvent event) {
        return !event.isDirect() && !event.isInertia() && !event.isShiftDown()
                && event.getDeltaX() == 0 && event.getDeltaY() != 0 && Double.isFinite(event.getDeltaY())
                && event.getTextDeltaYUnits() == ScrollEvent.VerticalTextScrollUnits.LINES;
    }

    /// Attaches one lifecycle-bound scroller; no detached/hidden page keeps an active timer.
    private static void install(Node owner, DoubleUnaryOperator move, DoublePredicate canScroll) {
        if (owner.getProperties().containsKey(KEY)) return;
        Scroller scroller = new Scroller(owner, move);
        owner.getProperties().put(KEY, scroller);
        EventHandler<ScrollEvent> handler = event -> {
            if (scroller.nativeGesture || !isDiscreteWheel(event)) {
                scroller.cancel();
                return;
            }
            double pixels = -event.getDeltaY();
            if (!canScroll.test(pixels)) {
                scroller.cancel();
                return;
            }
            scroller.add(pixels);
            event.consume();
        };
        if (owner instanceof ScrollPane pane) {
            // The skin consumes at its viewport before the event bubbles to the pane.
            // Content is inside that viewport: handle after children, but before the skin.
            if (pane.getContent() != null) pane.getContent().addEventHandler(ScrollEvent.SCROLL, handler);
            pane.contentProperty().addListener((observable, before, after) -> {
                scroller.cancel();
                if (before != null) before.removeEventHandler(ScrollEvent.SCROLL, handler);
                if (after != null) after.addEventHandler(ScrollEvent.SCROLL, handler);
            });
        } else {
            owner.addEventFilter(ScrollEvent.SCROLL, handler);
        }
        owner.addEventFilter(ScrollEvent.SCROLL_STARTED, event -> {
            scroller.cancel();
            scroller.nativeGesture = true;
        });
        owner.addEventFilter(ScrollEvent.SCROLL_FINISHED, event -> scroller.nativeGesture = false);
        owner.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> scroller.cancel());
        owner.sceneProperty().addListener((observable, before, after) -> {
            if (after == null) {
                scroller.cancel();
                scroller.nativeGesture = false;
            }
        });
    }

    /// One frame timer per installed control, stopped whenever no movement remains.
    @NotNullByDefault
    private static final class Scroller extends AnimationTimer {
        /// Control whose visibility and attachment govern timer lifetime.
        private final Node owner;
        /// Performs a pixel move and returns actual movement (zero means an edge).
        private final DoubleUnaryOperator move;
        /// Pure displacement model, independently testable without JavaFX rendering.
        private final ScrollMomentum momentum = new ScrollMomentum();
        /// Previous animation timestamp, zero before the first frame.
        private long previous;
        /// Explicit gesture lifecycle overrides line-unit heuristics for precision touchpads.
        private boolean nativeGesture;

        /// Creates a stopped scroller for a control and its native scrolling primitive.
        private Scroller(Node owner, DoubleUnaryOperator move) { this.owner = owner; this.move = move; }

        /// Queues pixels and starts frame callbacks only while needed.
        private void add(double pixels) {
            momentum.add(pixels);
            start();
        }

        /// Stops work without leaving a long synthetic inertia tail.
        private void cancel() {
            stop();
            previous = 0;
            momentum.clear();
        }

        /// Checks ancestors too: cached pages can keep a Scene while becoming invisible.
        private boolean isDisplayed() {
            if (owner.getScene() == null || owner.getScene().getWindow() == null || !owner.getScene().getWindow().isShowing()) return false;
            for (@Nullable Node node = owner; node != null; node = node.getParent()) if (!node.isVisible()) return false;
            return true;
        }

        /// Integrates once per pulse, cancelling at boundaries or after page removal.
        @Override
        public void handle(long now) {
            if (!isDisplayed()) { cancel(); return; }
            double seconds = previous == 0 ? 1.0 / 60 : Math.min(0.05, (now - previous) / 1_000_000_000.0);
            previous = now;
            double pixels = momentum.advance(seconds);
            if (move.applyAsDouble(pixels) == 0 || !momentum.isActive()) cancel();
        }
    }
}
