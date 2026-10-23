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
package org.jackhuang.hmcl;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;

/// Resolves the isolated MFMC launcher directories.
///
/// The launcher uses its own `~/.mfcl` directory by default instead of the
/// classic HMCL configuration locations, so it cannot read or overwrite an
/// existing HMCL installation's settings.
@NotNullByDefault
public final class MfclPaths {
    /// System property that overrides the MFMC home directory.
    public static final String HOME_PROPERTY = "mfcl.home";

    /// System property that overrides the MFMC configuration directory.
    public static final String DIR_PROPERTY = "mfcl.dir";

    /// Environment variable that overrides the MFMC home directory.
    public static final String HOME_ENV = "MFCL_HOME";

    /// Environment variable that overrides the MFMC configuration directory.
    public static final String DIR_ENV = "MFCL_DIR";

    /// The isolated MFMC home directory.
    public static final Path HOME = resolveHome();

    /// The isolated MFMC configuration directory.
    public static final Path CONFIG = resolveConfig();

    /// The isolated default Minecraft directory.
    public static final Path MINECRAFT = HOME.resolve("minecraft");

    /// Resolves the MFMC home directory.
    private static Path resolveHome() {
        String value = System.getProperty(HOME_PROPERTY, System.getenv(HOME_ENV));
        if (value != null && !value.isBlank()) {
            return Path.of(value).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home", "."), ".mfcl").toAbsolutePath().normalize();
    }

    /// Resolves the MFMC configuration directory.
    private static Path resolveConfig() {
        String value = System.getProperty(DIR_PROPERTY, System.getenv(DIR_ENV));
        if (value != null && !value.isBlank()) {
            return Path.of(value).toAbsolutePath().normalize();
        }
        return HOME;
    }

    private MfclPaths() {
    }
}
