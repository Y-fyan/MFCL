/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.util;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import org.jackhuang.hmcl.countly.CrashReport;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.ui.CrashWindow;

import static org.jackhuang.hmcl.util.Pair.pair;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/**
 * @author huangyuhui
 */
public final class CrashReporter implements Thread.UncaughtExceptionHandler {

    /// Prevents a failing handler from re-entering itself and flooding the log.
    private static final ThreadLocal<Boolean> HANDLING_EXCEPTION = ThreadLocal.withInitial(() -> Boolean.FALSE);

    // Lazy initialization resources
    private static final class Hole {
        @SuppressWarnings("unchecked")
        static final Pair<String, String>[] SOURCE = (Pair<String, String>[]) new Pair<?, ?>[]{
                pair("Location is not set", i18n("crash.NoClassDefFound")),
                pair("UnsatisfiedLinkError", i18n("crash.user_fault")),
                pair("java.time.zone.ZoneRulesException: Unable to load TZDB time-zone rules", i18n("crash.user_fault")),
                pair("java.lang.NoClassDefFoundError", i18n("crash.NoClassDefFound")),
                pair("org.jackhuang.hmcl.util.ResourceNotFoundError", i18n("crash.NoClassDefFound")),
                pair("java.lang.VerifyError", i18n("crash.NoClassDefFound")),
                pair("java.lang.NoSuchMethodError", i18n("crash.NoClassDefFound")),
                pair("java.lang.NoSuchFieldError", i18n("crash.NoClassDefFound")),
                pair("javax.imageio.IIOException", i18n("crash.NoClassDefFound")),
                pair("netscape.javascript.JSException", i18n("crash.NoClassDefFound")),
                pair("java.lang.IncompatibleClassChangeError", i18n("crash.NoClassDefFound")),
                pair("java.lang.ClassFormatError", i18n("crash.NoClassDefFound")),
                pair("com.sun.javafx.css.StyleManager.findMatchingStyles", i18n("launcher.update_java")),
                pair("NoSuchAlgorithmException", "Has your operating system been installed completely or is a ghost system?")
        };
    }

    private boolean checkThrowable(Throwable e) {
        String s = StringUtils.getStackTrace(e);
        for (Pair<String, String> entry : Hole.SOURCE)
            if (s.contains(entry.getKey())) {
                if (StringUtils.isNotBlank(entry.getValue())) {
                    String info = entry.getValue();
                    LOG.error(info);
                    try {
                        Alert alert = new Alert(AlertType.INFORMATION, info);
                        alert.setTitle(i18n("message.info"));
                        alert.setHeaderText(i18n("message.info"));
                        alert.showAndWait();
                    } catch (Throwable t) {
                        try {
                            SwingUtils.showErrorDialog(info);
                        } catch (Throwable t2) {
                            t.addSuppressed(t2);
                        }
                        LOG.error("Unable to show message", t);
                    }
                }
                return false;
            }
        return true;
    }

    private final boolean showCrashWindow;

    public CrashReporter(boolean showCrashWindow) {
        this.showCrashWindow = showCrashWindow;
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        if (HANDLING_EXCEPTION.get()) {
            return;
        }
        HANDLING_EXCEPTION.set(true);
        boolean shouldShutdown = true;
        try {
            try {
                LOG.error("Uncaught exception in thread " + t.getName(), e);

                CrashReport report = new CrashReport(t, e);
                if (report.shouldBeReport()) {
                    LOG.error(report.getDisplayText());
                    Platform.runLater(() -> {
                        if (checkThrowable(e) && showCrashWindow) {
                            new CrashWindow(report).show();
                        }
                    });
                } else {
                    shouldShutdown = false;
                }
            } catch (Throwable handlingException) {
                try {
                    LOG.error("Unable to handle uncaught exception", handlingException);
                } catch (Throwable ignored) {
                    // The class loader or logger may already be unusable.
                }
            } finally {
                if (shouldShutdown) {
                    shutdownAfterCrash();
                }
            }
        } finally {
            HANDLING_EXCEPTION.set(false);
        }
    }

    /// Runs shutdown work without allowing a failure to trigger the handler again.
    private static void shutdownAfterCrash() {
        try {
            SettingsManager.shutdown();
        } catch (Throwable ignored) {
            // A broken class loader must not turn shutdown into another crash.
        }

        try {
            LOG.shutdown();
        } catch (Throwable ignored) {
            // Logging may already be closed or unloadable.
        }
    }
}
