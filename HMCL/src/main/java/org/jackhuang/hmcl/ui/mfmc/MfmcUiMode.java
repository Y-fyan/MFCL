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

import org.jackhuang.hmcl.util.io.JarUtils;
import org.jetbrains.annotations.NotNullByDefault;

/// Selects the MFMC launcher UI while leaving the classic HMCL UI untouched.
///
/// The UI can be selected at runtime with `-Dhmcl.ui=mfmc` or baked into the
/// artifact through the `hmcl.ui` property in `assets/hmcl.properties`.
@NotNullByDefault
public final class MfmcUiMode {
    /// The system property or jar attribute used to select the launcher UI.
    public static final String PROPERTY = "hmcl.ui";

    /// The value that enables the MFMC top-tab UI.
    public static final String MFMC = "mfmc";

    /// The value that enables the MFMCL sidebar UI.
    public static final String MFMCL = "mfmcl";

    /// Returns whether an MFMC-family UI is selected.
    ///
    /// @return `true` when the classic root page should be replaced
    public static boolean isEnabled() {
        String value = System.getProperty(PROPERTY, JarUtils.getAttribute(PROPERTY, ""));
        return MFMC.equalsIgnoreCase(value) || MFMCL.equalsIgnoreCase(value);
    }

    /// Returns whether the MFMCL sidebar UI is selected.
    ///
    /// @return `true` for the MFMCL shell
    public static boolean isMfmcl() {
        return MFMCL.equalsIgnoreCase(System.getProperty(PROPERTY, JarUtils.getAttribute(PROPERTY, "")));
    }

    private MfmcUiMode() {
    }
}
