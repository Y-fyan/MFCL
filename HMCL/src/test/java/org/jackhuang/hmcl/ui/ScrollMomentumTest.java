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

import javafx.scene.input.ScrollEvent;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Deterministic checks of wheel displacement, settling and native gesture routing.
@NotNullByDefault
final class ScrollMomentumTest {
    /// Refresh rate affects samples, not distance, and no long-running tail survives settling.
    @Test void preservesPixelsAtDifferentRefreshRates() {
        for (int rate : new int[] {30, 60, 90, 120, 144, 240}) {
            ScrollMomentum momentum = new ScrollMomentum();
            momentum.add(120);
            double distance = 0;
            int frames = 0;
            while (momentum.isActive() && frames++ < rate) distance += momentum.advance(1.0 / rate);
            assertFalse(momentum.isActive());
            assertEquals(120, distance, 1e-8);
            assertTrue(frames <= rate / 2 + 1, "wheel tail should settle in under half a second");
        }
    }

    /// Reversing the wheel must not fight a backlog from the previous direction.
    @Test void reversesImmediatelyAndBoundsBursts() {
        ScrollMomentum momentum = new ScrollMomentum();
        momentum.add(1000);
        momentum.advance(0.016);
        momentum.add(-40);
        assertTrue(momentum.advance(0.016) < 0);
        momentum.clear();
        momentum.add(1e20);
        double total = 0;
        for (int i = 0; i < 100; i++) total += momentum.advance(1.0 / 60);
        assertEquals(2048, total, 1e-6);
        assertFalse(momentum.isActive());
        momentum.add(Double.NaN);
        momentum.add(Double.POSITIVE_INFINITY);
        assertEquals(0, momentum.advance(1));
    }

    /// Touchpad pixels, OS inertia and touch gestures bypass custom smoothing unchanged.
    @Test void doesNotConsumeNativeGestures() {
        assertTrue(FrameScrollSupport.isDiscreteWheel(event(false, false, ScrollEvent.VerticalTextScrollUnits.LINES)));
        assertFalse(FrameScrollSupport.isDiscreteWheel(event(false, false, ScrollEvent.VerticalTextScrollUnits.NONE)));
        assertFalse(FrameScrollSupport.isDiscreteWheel(event(false, true, ScrollEvent.VerticalTextScrollUnits.LINES)));
        assertFalse(FrameScrollSupport.isDiscreteWheel(event(true, false, ScrollEvent.VerticalTextScrollUnits.LINES)));
    }

    /// Builds an input event without requiring a rendered scene or system mouse.
    private static ScrollEvent event(boolean direct, boolean inertia, ScrollEvent.VerticalTextScrollUnits units) {
        return new ScrollEvent(ScrollEvent.SCROLL, 0, 0, 0, 0, false, false, false, false,
                direct, inertia, 0, -40, 0, -40, ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                units, -3, 0, null);
    }
}
