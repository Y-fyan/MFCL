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
package org.jackhuang.hmcl.ui.mfmc;

import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.game.GameInstanceID;
import org.jackhuang.hmcl.game.HMCLGameInstance;
import org.jackhuang.hmcl.ui.download.ModDownloadPage;
import org.jetbrains.annotations.NotNullByDefault;

/// Compatibility entry point for existing mod-download callers; shares the MFCL browser.
@NotNullByDefault
public final class MfmcModDownloadPage extends StackPane implements ModDownloadPage {
    /// One shared implementation, not a duplicate legacy page or nested scroll control.
    private final MfmcAddonDownloadPage browser = new MfmcAddonDownloadPage(MfmcAddonDownloadPage.Kind.MOD);

    /// Keeps existing constructor callers compatible while the browser serves other content kinds.
    public MfmcModDownloadPage() {
        setMinSize(0, 0);
        getChildren().setAll(browser);
    }

    /// Updates the selected installation target in the shared browser.
    @Override public void loadInstance(HMCLGameInstance.Optional instance) { browser.loadInstance(instance); }

    /// Selects the destination instance without constructing another page.
    @Override public void selectInstance(GameInstanceID instanceId) { browser.selectInstance(instanceId); }
}
