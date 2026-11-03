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

import org.jetbrains.annotations.NotNullByDefault;

/// Bounded pixel displacement with frame-rate-independent exponential smoothing.
@NotNullByDefault
final class ScrollMomentum {
    /// Maximum queued movement during a burst of wheel events.
    private static final double MAX_PENDING = 2048;
    /// Exponential time constant in seconds; about 95% completes within 180 ms.
    private static final double TIME_CONSTANT = 0.06;
    /// Remaining signed pixels; this is displacement, not a synthetic acceleration chain.
    private double pending;

    /// Adds finite wheel pixels, dropping old movement immediately when direction reverses.
    void add(double pixels) {
        if (!Double.isFinite(pixels) || pixels == 0) return;
        if (Math.signum(pixels) != Math.signum(pending)) pending = 0;
        pending = Math.max(-MAX_PENDING, Math.min(MAX_PENDING, pending + pixels));
    }

    /// Advances one rendered frame, preserving total displacement across refresh rates.
    double advance(double seconds) {
        if (!(seconds > 0) || !Double.isFinite(seconds)) return 0;
        double delta = pending * -Math.expm1(-seconds / TIME_CONSTANT);
        pending -= delta;
        if (Math.abs(pending) < 0.25) {
            delta += pending;
            pending = 0;
        }
        return delta;
    }

    /// Whether another rendered frame is needed.
    boolean isActive() { return pending != 0; }

    /// Cancels pending movement on drag, touchpad input, detachment or an edge.
    void clear() { pending = 0; }
}
