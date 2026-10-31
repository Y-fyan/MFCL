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

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.ImageView;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.download.game.GameRemoteVersion;
import org.jackhuang.hmcl.game.HMCLGameInstance;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.HMCLGameRepositorySnapshot;
import org.jackhuang.hmcl.game.TexturesLoader;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.account.CreateAccountPane;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.download.DownloadPage;
import org.jackhuang.hmcl.util.Lazy;
import org.jackhuang.hmcl.util.javafx.BindingMapping;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/// The MFMCL light workspace.
///
/// The layout follows the official MFMCL launcher: a fixed 240 px sidebar with
/// a brand block, four primary destinations and an account/theme/settings
/// footer, next to a white content column. Every page owns its own header so
/// the title stays fixed while only the content scrolls.
@NotNullByDefault
public final class MfmclRootPage extends StackPane implements DecoratorPage {
    /// The title-bar state returned to the window decorator.
    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(this, "state");

    /// The page viewport below the window title bar.
    private final StackPane content = new StackPane();

    /// The instance library page.
    private final MfmclLibraryPage libraryPage;

    /// The content store landing page.
    private final MfmclStorePage storePage;

    /// The download task page.
    private final MfmclDownloadsPage downloadsPage;

    /// The launcher extension page.
    private final MfmclPluginsPage pluginsPage;

    /// The lazily created settings page.
    private final Lazy<MfmcSettingsPage> settingsPage = new Lazy<>(() -> new MfmcSettingsPage(false));

    /// Cached embedded download pages, so listeners are not duplicated.
    private final Map<DownloadPage.Category, DownloadPage> downloadPages = new EnumMap<>(DownloadPage.Category.class);

    /// The repository snapshot used to keep the library badge current.
    private final ObservableValue<HMCLGameRepositorySnapshot> repositorySnapshot =
            BindingMapping.of(GameDirectoryManager.selectedRepositoryProperty())
                    .flatMap(HMCLGameRepository::snapshotProperty);

    /// The avatar shown by the account footer entry.
    private final Canvas accountAvatar = new Canvas(24, 24);

    /// Every sidebar destination, used to clear the active style.
    private final List<NavItem> navItems = new ArrayList<>();

    private final NavItem libraryNav;
    private final NavItem storeNav;
    private final NavItem downloadsNav;
    private final NavItem pluginsNav;
    private final NavItem themeNav;
    private final NavItem loginNav;
    private final NavItem settingsNav;

    /// The instance count badge on the library entry.
    private final Label libraryBadge = new Label();

    /// The running-task badge on the download entry.
    private final Label downloadsBadge = new Label();

    /// Whether the shell currently uses its dark palette.
    private boolean dark;

    /// The window title label, styled directly because the decorator title bar
    /// is outside this page's stylesheet subtree.
    private final Label windowTitle = new Label("MFMCL");

    /// The page visible before the account page opened.
    private @Nullable Node previousPage;

    /// The navigation entry active before the account page opened.
    private @Nullable NavItem previousNav;

    /// The repository snapshot listener that opens the first instance for diagnostics.
    private boolean diagnosticInstanceOpened;

    /// Creates the MFMCL workspace.
    public MfmclRootPage() {
        getStyleClass().addAll("mfmc-root", "mfmcl-root");

        var lightSheet = MfmclRootPage.class.getResource("/assets/css/mfmc-light.css");
        if (lightSheet != null) {
            getStylesheets().add(lightSheet.toExternalForm());
        }
        var sheet = MfmclRootPage.class.getResource("/assets/css/mfmcl.css");
        if (sheet != null) {
            getStylesheets().add(sheet.toExternalForm());
        }

        state.set(new State(null, createTitleNode(), false, false, true));

        libraryPage = new MfmclLibraryPage(this::showStore, this::showInstance);
        storePage = new MfmclStorePage(this::showDownloadCategory);
        downloadsPage = new MfmclDownloadsPage(this::showStore);
        pluginsPage = new MfmclPluginsPage();

        libraryNav = navItem(SVG.PACKAGE2.createIcon(20), "游戏库", this::showLibrary, libraryBadge, null);
        storeNav = navItem(SVG.PACKAGE.createIcon(20), "内容商店", this::toggleStoreMenu, null, SVG.ARROW_DROP_DOWN);
        downloadsNav = navItem(SVG.DOWNLOAD.createIcon(20), "下载任务", this::showDownloads, downloadsBadge, null);
        pluginsNav = navItem(SVG.EXTENSION.createIcon(20), "启动器插件", this::showPlugins, null, null);
        themeNav = navItem(SVG.MOON.createIcon(20), "深色", this::toggleTheme, null, null);
        loginNav = navItem(createAccountLeading(), "登录", this::showAccounts, null, null);
        settingsNav = navItem(SVG.SETTINGS.createIcon(20), "设置", this::showSettings, null, null);

        libraryBadge.getStyleClass().add("mfmcl-badge");
        downloadsBadge.getStyleClass().addAll("mfmcl-badge", "mfmcl-badge-accent");
        downloadsBadge.setVisible(false);
        downloadsBadge.setManaged(false);
        libraryBadge.setVisible(false);
        libraryBadge.setManaged(false);

        VBox sidebar = createSidebar();
        VBox main = new VBox(content);
        main.getStyleClass().add("mfmcl-main");
        VBox.setVgrow(content, Priority.ALWAYS);
        content.getStyleClass().add("mfmcl-content");
        content.setMinHeight(0);

        HBox shell = new HBox(sidebar, main);
        HBox.setHgrow(main, Priority.ALWAYS);
        shell.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        shell.getStyleClass().add("mfmcl-shell");
        getChildren().setAll(shell);

        FXUtils.onChangeAndOperate(Accounts.selectedAccountProperty(), this::updateAccount);
        FXUtils.onChangeAndOperate(repositorySnapshot, snapshot -> {
            libraryPage.refresh();
            int count = snapshot.getInstances().size();
            libraryBadge.setText(Integer.toString(count));
            libraryBadge.setVisible(count > 0);
            libraryBadge.setManaged(count > 0);
            if ("instance".equals(System.getProperty("mfmc.ui.tab")) && !diagnosticInstanceOpened) {
                snapshot.getInstances().stream().findFirst().ifPresent(instance -> {
                    diagnosticInstanceOpened = true;
                    showInstance(instance);
                });
            }
        });

        MfmclTaskCenter.activeCountProperty().addListener((observable, oldValue, newValue) -> {
            int count = newValue.intValue();
            downloadsBadge.setText(count > 99 ? "99+" : Integer.toString(count));
            downloadsBadge.setVisible(count > 0);
            downloadsBadge.setManaged(count > 0);
        });

        // Diagnostics switch used by the layout snapshot tooling.
        if (Boolean.getBoolean("mfmc.ui.dark")) {
            toggleTheme();
        }

        String initialTab = System.getProperty("mfmc.ui.tab", "library");
        switch (initialTab) {
            case "download", "downloads" -> showDownloads();
            case "store" -> showStore();
            case "plugins" -> showPlugins();
            case "settings" -> showSettings();
            case "accounts" -> showAccounts();
            default -> showLibrary();
        }

        String category = System.getProperty("mfmc.ui.category");
        if (category != null && !category.isBlank()) {
            if ("mod".equalsIgnoreCase(category)) {
                showModDownloads();
            } else {
                try {
                    showDownloadCategory(DownloadPage.Category.valueOf(category.toUpperCase(java.util.Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    showStore();
                }
            }
        }
    }

    /// Creates the title node displayed by the window decorator.
    private Node createTitleNode() {
        windowTitle.getStyleClass().add("mfmcl-window-title");
        windowTitle.setStyle("-fx-text-fill: " + (dark ? "#F9FAFB" : "#111827") + ";");
        HBox title = new HBox(windowTitle);
        title.setAlignment(Pos.CENTER);
        return title;
    }

    /// Builds the left sidebar.
    private VBox createSidebar() {
        VBox sidebar = new VBox();
        sidebar.getStyleClass().add("mfmcl-sidebar");
        sidebar.setPrefWidth(240);
        sidebar.setMinWidth(240);
        sidebar.setMaxWidth(240);

        ImageView logoImage = new ImageView(FXUtils.newBuiltinImage("/assets/img/icon-mfmc.png"));
        logoImage.setFitWidth(36);
        logoImage.setFitHeight(36);
        logoImage.setPreserveRatio(true);
        logoImage.setSmooth(true);
        StackPane logo = new StackPane(logoImage);
        logo.getStyleClass().add("mfmcl-logo");

        Label brandName = new Label("MFMC Launcher");
        brandName.getStyleClass().add("mfmcl-brand-name");

        HBox brand = new HBox(10, logo, brandName);
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.getStyleClass().add("mfmcl-sidebar-header");

        VBox nav = new VBox(4, libraryNav.node, storeNav.node, downloadsNav.node, pluginsNav.node);
        nav.getStyleClass().add("mfmcl-nav-list");

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        VBox footer = new VBox(4, themeNav.node, loginNav.node, settingsNav.node);
        footer.getStyleClass().add("mfmcl-sidebar-footer");

        sidebar.getChildren().setAll(brand, nav, spacer, footer);
        return sidebar;
    }

    /// Creates a sidebar destination row.
    private NavItem navItem(Node leading, String text, Runnable action,
                            @Nullable Label badge, @Nullable SVG chevron) {
        NavItem item = new NavItem(leading, text, action);
        if (badge != null) {
            item.trailing.getChildren().add(badge);
        }
        if (chevron != null) {
            item.trailing.getChildren().add(chevron.createIcon(14));
            item.trailing.getStyleClass().add("mfmcl-nav-chevron");
        }
        navItems.add(item);
        return item;
    }

    /// Creates the leading node used by the account footer entry.
    ///
    /// Signed-out users see the same person glyph as the official design;
    /// signed-in users see a circular head avatar instead.
    private StackPane createAccountLeading() {
        Node person = SVG.PERSON.createIcon(20);
        accountAvatar.setClip(new Circle(12, 12, 12));

        StackPane frame = new StackPane(person, accountAvatar);
        frame.getStyleClass().add("mfmcl-avatar");
        FXUtils.onChangeAndOperate(Accounts.selectedAccountProperty(), account -> {
            boolean signedIn = account != null;
            person.setVisible(!signedIn);
            person.setManaged(!signedIn);
            accountAvatar.setVisible(signedIn);
            accountAvatar.setManaged(signedIn);

            TexturesLoader.unbindAvatar(accountAvatar);
            if (signedIn) {
                TexturesLoader.bindAvatar(accountAvatar, account);
            }
        });
        return frame;
    }

    /// Shows the category menu attached to the store entry.
    private void toggleStoreMenu() {
        ContextMenu menu = new ContextMenu();
        menu.getStyleClass().add("mfmcl-store-menu");

        MenuItem landing = new MenuItem("内容商店");
        landing.setOnAction(event -> showStore());
        menu.getItems().addAll(landing, new SeparatorMenuItem());

        addStoreCategory(menu, "游戏版本", DownloadPage.Category.GAME);
        addStoreCategory(menu, "整合包", DownloadPage.Category.MODPACK);
        addStoreCategory(menu, "模组", DownloadPage.Category.MOD);
        addStoreCategory(menu, "资源包", DownloadPage.Category.RESOURCE_PACK);
        addStoreCategory(menu, "光影", DownloadPage.Category.SHADER);
        addStoreCategory(menu, "世界", DownloadPage.Category.WORLD);

        menu.show(storeNav.node, Side.BOTTOM, 0, 6);
    }

    /// Adds one category entry to the store menu.
    private void addStoreCategory(ContextMenu menu, String text, DownloadPage.Category category) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(event -> showDownloadCategory(category));
        menu.getItems().add(item);
    }

    /// Toggles the MFMCL dark palette.
    private void toggleTheme() {
        dark = !dark;
        if (dark) {
            if (!getStyleClass().contains("mfmcl-dark")) {
                getStyleClass().add("mfmcl-dark");
            }
            themeNav.setLeading(SVG.WB_SUNNY.createIcon(20));
            themeNav.setText("浅色");
        } else {
            getStyleClass().remove("mfmcl-dark");
            themeNav.setLeading(SVG.MOON.createIcon(20));
            themeNav.setText("深色");
        }

        // The window title bar lives outside this page's stylesheet subtree, so
        // publish a new state to let the decorator repaint it.
        state.set(new State(null, createTitleNode(), false, false, true));
    }

    /// Selects the active destination and clears the others.
    private void selectNav(@Nullable NavItem item) {
        for (NavItem nav : navItems) {
            nav.node.getStyleClass().remove("active");
        }
        if (item != null) {
            item.node.getStyleClass().add("active");
            // Keep keyboard traversal anchored on the selected destination so
            // the implicit focus never highlights a different row.
            item.node.requestFocus();
        }
    }

    /// Replaces the page viewport.
    private void showPage(Node page, @Nullable NavItem nav) {
        content.getChildren().setAll(page);
        selectNav(nav);
        MfmcMotion.enter(page);
    }

    /// Shows the instance library.
    public void showLibrary() {
        libraryPage.refresh();
        showPage(libraryPage, libraryNav);
    }

    /// Shows the instance library.
    public void showHome() {
        showLibrary();
    }

    /// Shows the content store landing page.
    public void showStore() {
        showPage(storePage, storeNav);
    }

    /// Shows the download task page.
    public void showDownloads() {
        showPage(downloadsPage, downloadsNav);
    }

    /// Shows the launcher extension page.
    public void showPlugins() {
        showPage(pluginsPage, pluginsNav);
    }

    /// Shows the settings page.
    public void showSettings() {
        VBox page = new VBox();
        page.getStyleClass().add("mfmcl-page");

        Node body = settingsPage.get();
        VBox bodyBox = new VBox(body);
        bodyBox.getStyleClass().add("mfmcl-page-body");
        VBox.setVgrow(body, Priority.ALWAYS);

        page.getChildren().setAll(
                MfmclUi.header("设置", "管理 Java 运行环境、版本隔离与启动器信息。"),
                MfmclUi.scroll(bodyBox));
        showPage(page, settingsNav);
    }

    /// Shows the account management page, remembering where the user came from.
    public void showAccounts() {
        // Re-entering the account page from its own footer entry must not
        // overwrite the page that "返回" restores.
        boolean alreadyOpen = loginNav.node.getStyleClass().contains("active");
        if (!alreadyOpen && !content.getChildren().isEmpty()) {
            previousPage = content.getChildren().get(0);
            previousNav = findActiveNav();
        }

        Button add = MfmclUi.primaryButton("添加账号", SVG.ADD,
                () -> Controllers.dialog(new CreateAccountPane()));

        MfmcAccountPage accounts = new MfmcAccountPage(this::restorePreviousPage, false);
        VBox bodyBox = new VBox(accounts);
        bodyBox.getStyleClass().add("mfmcl-page-body");
        VBox.setVgrow(accounts, Priority.ALWAYS);

        VBox page = new VBox(
                MfmclUi.header("账号管理", "选择启动时使用的账号，或添加新的正版、离线账号。", add),
                bodyBox);
        page.getStyleClass().add("mfmcl-page");
        showPage(page, loginNav);
    }

    /// Returns the currently active navigation entry.
    private @Nullable NavItem findActiveNav() {
        for (NavItem nav : navItems) {
            if (nav.node.getStyleClass().contains("active")) {
                return nav;
            }
        }
        return null;
    }

    /// Restores the page that was visible before the account page opened.
    private void restorePreviousPage() {
        if (previousPage != null) {
            showPage(previousPage, previousNav);
        } else {
            showLibrary();
        }
    }

    /// Shows an instance detail page.
    public void showInstance(HMCLGameInstance instance) {
        GameDirectoryManager.setSelectedInstance(instance);
        showPage(new MfmclInstancePage(instance, this::showLibrary), libraryNav);
    }

    /// Shows an embedded download page for the requested category.
    public void showDownloadCategory(DownloadPage.Category category) {
        if (category == DownloadPage.Category.MOD) {
            showModDownloads();
            return;
        }

        DownloadPage page = downloadPages.computeIfAbsent(category, key -> {
            DownloadPage downloadPage = new DownloadPage(null, this::showGameInstallPage);
            downloadPage.getLeft().setVisible(false);
            downloadPage.getLeft().setManaged(false);
            downloadPage.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            downloadPage.selectCategory(key);
            return downloadPage;
        });

        String name = switch (category) {
            case GAME -> "游戏版本";
            case MODPACK -> "整合包";
            case MOD -> "模组";
            case RESOURCE_PACK -> "资源包";
            case SHADER -> "光影";
            case WORLD -> "世界";
        };
        showPage(embedded(page, name, this::showStore), storeNav);
    }

    /// Shows the MFMC-owned mod browser.
    public void showModDownloads() {
        showPage(embedded(new MfmcModDownloadPage(), "模组", this::showStore), storeNav);
    }

    /// Opens the MFMC-owned install page for the selected Minecraft version.
    private void showGameInstallPage(GameRemoteVersion gameVersion) {
        showPage(embedded(new MfmcGameInstallPage(gameVersion, this::showStore, this::showLibrary),
                "安装游戏", this::showStore), storeNav);
    }

    /// Wraps an embedded page with a back bar.
    private VBox embedded(Node page, String title, Runnable backAction) {
        Button back = new Button("返回");
        back.setGraphic(SVG.ARROW_BACK.createIcon(16));
        back.getStyleClass().add("mfmcl-back-button");
        back.setOnAction(event -> backAction.run());

        Label heading = new Label(title);
        heading.getStyleClass().add("mfmcl-subpage-heading");

        HBox bar = new HBox(12, back, heading);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("mfmcl-subpage-bar");

        VBox wrapper = new VBox(bar, page);
        wrapper.getStyleClass().add("mfmcl-embedded-page");
        VBox.setVgrow(page, Priority.ALWAYS);
        return wrapper;
    }

    /// Updates the account label and avatar in the sidebar footer.
    private void updateAccount(@Nullable Account account) {
        if (account == null) {
            loginNav.setText("登录");
        } else {
            String name = account.getProfileName();
            loginNav.setText(name == null || name.isBlank() ? account.getProfileID().toString() : name);
        }
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    /// A clickable sidebar row with an optional trailing badge or chevron.
    private static final class NavItem {
        /// The row node added to the sidebar.
        private final HBox node = new HBox(12);

        /// The text label, also used by the account entry.
        private final Label text = new Label();

        /// The right-aligned slot for badges and chevrons.
        private final HBox trailing = new HBox(8);

        /// The leading icon or avatar.
        private Node leading;

        private NavItem(Node leading, String label, Runnable action) {
            this.leading = leading;
            this.text.setText(label);
            this.text.getStyleClass().add("mfmcl-nav-text");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            node.getStyleClass().add("mfmcl-nav-item");
            node.setAlignment(Pos.CENTER_LEFT);
            node.setFocusTraversable(true);
            node.setCursor(javafx.scene.Cursor.HAND);
            node.getChildren().setAll(leading, text, spacer, trailing);
            node.setOnMouseClicked(event -> action.run());
            node.setOnKeyPressed(event -> {
                if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                    action.run();
                    event.consume();
                }
            });
        }

        /// Replaces the leading icon.
        private void setLeading(Node node) {
            int index = this.node.getChildren().indexOf(leading);
            if (index >= 0) {
                this.node.getChildren().set(index, node);
            }
            leading = node;
        }

        /// Updates the row label.
        private void setText(String value) {
            text.setText(value);
        }
    }
}
