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

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ListView;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Exercises actual skin event routing and animation lifetime in a displayed JavaFX scene.
@NotNullByDefault
final class FrameScrollSupportTest {
    /// Wheel events must be smoothed exactly once, including after duplicate installation.
    @Test void scrollPaneSmoothsBeforeItsSkinConsumesWheelEvents() throws Exception {
        Fixture fixture = Objects.requireNonNull(onFx(Fixture::new));
        try {
            onFx(() -> {
                FrameScrollSupport.install(fixture.pane);
                FrameScrollSupport.install(fixture.pane);
                fixture.body.fireEvent(wheel());
                assertEquals(0, fixture.pane.getVvalue(), "the skin must not jump before the first pulse");
                return null;
            });
            settle();
            onFx(() -> {
                double overflow = fixture.body.getHeight() - fixture.pane.getViewportBounds().getHeight();
                assertEquals(40, fixture.pane.getVvalue() * overflow, 1,
                        "one wheel event must produce exactly one bounded displacement");
                return null;
            });
        } finally {
            onFx(() -> { fixture.stage.close(); return null; });
        }
    }

    /// Native pixel input cancels a queued wheel; detached controls cannot resume stale movement.
    @Test void nativeInputAndDetachmentCancelQueuedWheel() throws Exception {
        Fixture fixture = Objects.requireNonNull(onFx(Fixture::new));
        try {
            double nativePosition = Objects.requireNonNull(onFx(() -> {
                FrameScrollSupport.install(fixture.pane);
                fixture.body.fireEvent(wheel());
                fixture.body.fireEvent(event(ScrollEvent.VerticalTextScrollUnits.NONE));
                return fixture.pane.getVvalue();
            }));
            settle();
            onFx(() -> {
                assertEquals(nativePosition, fixture.pane.getVvalue(), 1e-8);
                fixture.body.fireEvent(wheel());
                fixture.root.getChildren().clear();
                fixture.root.getChildren().add(fixture.pane);
                return null;
            });
            settle();
            onFx(() -> { assertEquals(nativePosition, fixture.pane.getVvalue(), 1e-8); return null; });
        } finally {
            onFx(() -> { fixture.stage.close(); return null; });
        }
    }

    /// A child consuming wheel input must retain control rather than scrolling its ancestor.
    @Test void consumedChildInputDoesNotScrollAncestor() throws Exception {
        Fixture fixture = Objects.requireNonNull(onFx(Fixture::new));
        try {
            onFx(() -> {
                FrameScrollSupport.install(fixture.pane);
                Node child = new Region();
                fixture.body.getChildren().add(child);
                child.addEventHandler(ScrollEvent.SCROLL, ScrollEvent::consume);
                child.fireEvent(wheel());
                return null;
            });
            settle();
            onFx(() -> { assertEquals(0, fixture.pane.getVvalue()); return null; });
        } finally {
            onFx(() -> { fixture.stage.close(); return null; });
        }
    }

    /// Legacy virtualized lists smooth discrete wheels but preserve explicitly started gestures.
    @Test void virtualFlowSmoothsWheelsAndPassesGestureLifecycle() throws Exception {
        Fixture fixture = Objects.requireNonNull(onFx(Fixture::new));
        try {
            VirtualFlow<?> flow = Objects.requireNonNull(onFx(() -> {
                ListView<String> list = new ListView<>();
                for (int i = 0; i < 1000; i++) list.getItems().add("Row " + i);
                list.setFixedCellSize(30);
                fixture.root.getChildren().setAll(list);
                fixture.root.applyCss();
                fixture.root.layout();
                @Nullable VirtualFlow<?> result = (VirtualFlow<?>) list.lookup(".virtual-flow");
                assertNotNull(result);
                FrameScrollSupport.install(result);
                FrameScrollSupport.install(result);
                result.fireEvent(wheel());
                assertEquals(0, result.getPosition());
                return result;
            }));
            settle();
            onFx(() -> {
                assertTrue(flow.getPosition() > 0);
                double before = flow.getPosition();
                flow.fireEvent(wheel().copyFor(flow, flow, ScrollEvent.SCROLL_STARTED));
                flow.fireEvent(wheel());
                assertTrue(flow.getPosition() > before, "line events within an explicit gesture stay native");
                flow.fireEvent(wheel().copyFor(flow, flow, ScrollEvent.SCROLL_FINISHED));
                double gestureEnd = flow.getPosition();
                flow.fireEvent(wheel());
                assertEquals(gestureEnd, flow.getPosition(), "discrete wheels resume smoothing after the gesture");
                return null;
            });
        } finally {
            onFx(() -> { fixture.stage.close(); return null; });
        }
    }

    /// Waits through actual pulses without blocking the JavaFX application thread.
    private static void settle() throws Exception {
        CompletableFuture<@Nullable Void> completed = new CompletableFuture<>();
        onFx(() -> {
            PauseTransition pause = new PauseTransition(Duration.millis(600));
            pause.setOnFinished(event -> completed.complete(null));
            pause.play();
            return null;
        });
        completed.get(5, TimeUnit.SECONDS);
    }

    /// Runs assertions on the FX thread and propagates failures back to JUnit.
    private static <T> @Nullable T onFx(Callable<@Nullable T> action) throws Exception {
        org.jackhuang.hmcl.JavaFXLauncher.start();
        assumeTrue(org.jackhuang.hmcl.JavaFXLauncher.isStarted());
        FutureTask<@Nullable T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    /// Creates one discrete vertical mouse-wheel event.
    private static ScrollEvent wheel() { return event(ScrollEvent.VerticalTextScrollUnits.LINES); }

    /// Creates a native pixel or discrete line event targeted at the scroll content.
    private static ScrollEvent event(ScrollEvent.VerticalTextScrollUnits units) {
        return new ScrollEvent(ScrollEvent.SCROLL, 0, 0, 0, 0, false, false, false, false,
                false, false, 0, -40, 0, -40, ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                units, -3, 0, null);
    }

    /// A real skin and viewport, with no launcher settings, network or user data involved.
    @NotNullByDefault
    private static final class Fixture {
        /// Tall content that can scroll several wheel steps.
        private final StackPane body = new StackPane();
        /// Scroll owner under test.
        private final ScrollPane pane = new ScrollPane(body);
        /// Host allowing attachment and detachment in the same scene.
        private final StackPane root = new StackPane(pane);
        /// Displayed window required for animation callbacks.
        private final Stage stage = new Stage();

        /// Lays out a fixed viewport and keeps the shared toolkit alive after this window closes.
        private Fixture() {
            Platform.setImplicitExit(false);
            body.setMinHeight(2000);
            stage.setScene(new Scene(root, 400, 300));
            stage.show();
            root.applyCss();
            root.layout();
        }
    }
}
