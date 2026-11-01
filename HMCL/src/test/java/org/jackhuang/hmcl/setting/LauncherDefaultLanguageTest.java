/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.setting;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class LauncherDefaultLanguageTest {
    @Test void defaultsToChineseAndPreservesExplicitSelections() {
        LauncherSettings fresh = new LauncherSettings();
        LauncherSettings existingWithoutLanguage = LauncherSettings.fromJson(JsonParser.parseString("{}").getAsJsonObject());
        String override = System.getenv("HMCL_LANGUAGE");
        if (override == null || override.isBlank()) {
            for (LauncherSettings settings : new LauncherSettings[]{fresh, existingWithoutLanguage}) {
                var language = settings.languageProperty().get();
                assertEquals("zh-Hans", language.getLocale().toLanguageTag());
                assertEquals("添加账户", language.i18n("account.create"));
                assertEquals("离线模式", language.i18n("account.methods.offline"));
                assertEquals("取消", language.i18n("button.cancel"));
            }
        } else {
            assertTrue(fresh.languageProperty().get().isDefault());
            assertTrue(existingWithoutLanguage.languageProperty().get().isDefault());
        }
        LauncherSettings english = LauncherSettings.fromJson(JsonParser.parseString("{\"language\":\"en\"}").getAsJsonObject());
        assertEquals("en", english.languageProperty().get().getLocale().getLanguage());
        assertEquals("Add Account", english.languageProperty().get().i18n("account.create"));
        LauncherSettings system = LauncherSettings.fromJson(JsonParser.parseString("{\"language\":\"default\"}").getAsJsonObject());
        assertTrue(system.languageProperty().get().isDefault());
        assertEquals("zh-Hans", LauncherSettings.defaultLanguage(null).getLocale().toLanguageTag());
        assertTrue(LauncherSettings.defaultLanguage("en").isDefault());
    }
}
