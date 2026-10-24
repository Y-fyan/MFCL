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

import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.jackhuang.hmcl.util.platform.windows.WinReg;

import java.nio.file.Path;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Requests the high-performance GPU for the MFMC launcher JVM on Windows.
///
/// Windows stores per-application GPU preferences under
/// `HKCU\Software\Microsoft\DirectX\UserGpuPreferences`. Setting the current
/// Java executable to `GpuPreference=2` makes laptops with hybrid graphics use
/// the discrete GPU on the next launch instead of defaulting to the integrated
/// GPU, which can make the JavaFX UI feel sluggish.
public final class WindowsGpuPreference {
    /// The registry key used by Windows Graphics Settings.
    private static final String USER_GPU_PREFERENCES = "Software\\Microsoft\\DirectX\\UserGpuPreferences";

    /// The value for "High performance".
    private static final String HIGH_PERFORMANCE = "GpuPreference=2;";

    /// The value for "Power saving".
    private static final String POWER_SAVING = "GpuPreference=1;";

    /// Whether the check has already run.
    private static boolean initialized;

    /// Whether this run changed the preference and needs a restart to take effect.
    private static boolean changed;

    /// Requests the high-performance GPU when the MFMC UI is active.
    ///
    /// @return `true` when the registry was changed during this process
    public static boolean ensureHighPerformance() {
        if (initialized) {
            return changed;
        }
        initialized = true;

        if (OperatingSystem.CURRENT_OS != OperatingSystem.WINDOWS
                || !MfmcUiMode.isEnabled()
                || WinReg.INSTANCE == null
                || !"high".equalsIgnoreCase(System.getProperty("mfmc.gpu.preference", "high"))) {
            return false;
        }

        String command = ProcessHandle.current().info().command().orElse(null);
        if (command == null || command.isBlank()) {
            return false;
        }

        String executable = Path.of(command).toAbsolutePath().normalize().toString();
        Object existing = WinReg.INSTANCE.queryValue(
                WinReg.HKEY.HKEY_CURRENT_USER,
                USER_GPU_PREFERENCES,
                executable
        );

        if (existing instanceof String value) {
            // Respect an explicit "power saving" choice, but replace the
            // default/unspecified value so hybrid laptops pick the fast GPU.
            if (value.contains(HIGH_PERFORMANCE) || value.contains(POWER_SAVING)) {
                return false;
            }
        }

        if (WinReg.INSTANCE.setValue(
                WinReg.HKEY.HKEY_CURRENT_USER,
                USER_GPU_PREFERENCES,
                executable,
                HIGH_PERFORMANCE
        )) {
            changed = true;
            LOG.info("Enabled high-performance GPU preference for " + executable + " (restart required)");
        }

        return changed;
    }

    /// Returns and clears the "restart required" flag.
    ///
    /// @return `true` when the GPU preference was changed during this process
    public static boolean consumeChanged() {
        boolean result = changed;
        changed = false;
        return result;
    }

    private WindowsGpuPreference() {
    }
}
