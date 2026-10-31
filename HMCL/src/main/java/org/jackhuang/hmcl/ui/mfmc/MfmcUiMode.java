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

import org.jetbrains.annotations.NotNullByDefault;

/// Identifies the launcher interface shipped by MFCL.
///
/// MFCL exposes exactly one interface: the MFMCL sidebar workspace. The
/// classic HMCL pages and the earlier MFMC top-tab pages still exist in the
/// code base, but only as building blocks embedded inside MFMCL — they can no
/// longer be selected at runtime or through a build property.
@NotNullByDefault
public final class MfmcUiMode {
    private MfmcUiMode() {
    }

    /// Returns whether the MFMCL workspace owns the main window.
    ///
    /// @return always `true`
    public static boolean isEnabled() {
        return true;
    }

    /// Returns whether the active interface is the MFMCL workspace.
    ///
    /// @return always `true`
    public static boolean isMfmcl() {
        return true;
    }
}
