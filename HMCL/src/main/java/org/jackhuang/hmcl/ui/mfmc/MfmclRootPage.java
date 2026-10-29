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
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.Metadata;
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
import org.jackhuang.hmcl.ui.SVGContainer;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.download.DownloadPage;
import org.jackhuang.hmcl.ui.instances.Instances;
import org.jackhuang.hmcl.util.Lazy;
import org.jackhuang.hmcl.util.javafx.BindingMapping;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/// The MFMCL sidebar shell, based on the visual structure of the MFMCL React UI.
///
/// The shell keeps the classic HMCL pages available through the classic UI
/// switch, while offering a sidebar-driven workspace for the new pages.
@NotNullByDefault
public final class MfmclRootPage extends StackPane implements DecoratorPage {
    /// The title-bar state returned to the window decorator.
    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(this, "state");

    /// The area below the top bar.
    private final StackPane content = new StackPane();

    /// The breadcrumb showing the current page.
    private final Label breadcrumb = new Label();

    /// The selected account name in the sidebar footer.
    private final Label accountName = new Label("访客模式");

    /// The selected account type in the sidebar footer.
    private final Label accountType = new Label("尚未登录");

    /// The avatar drawn in the sidebar footer.
    private final Canvas accountAvatar = new Canvas(30, 30);

    /// All navigation buttons, used to clear the active style.
    private final List<Button> navButtons = new ArrayList<>();

    /// The overview page.
    private final MfmclHomePage homePage;

    /// The instance library.
    private final MfmclLibraryPage libraryPage;

    /// The download hub.
    private final MfmclDownloadsPage downloadsPage;

    /// The lazily created settings page.
    private final Lazy<MfmcSettingsPage> settingsPage = new Lazy<>(MfmcSettingsPage::new);

    /// Cached embedded download pages, so version listeners are not duplicated.
    private final Map<DownloadPage.Category, DownloadPage> downloadPages = new EnumMap<>(DownloadPage.Category.class);

    /// The repository snapshot used by the instance-detail diagnostic entry.
    private final ObservableValue<HMCLGameRepositorySnapshot> repositorySnapshot =
            BindingMapping.of(GameDirectoryManager.selectedRepositoryProperty())
                    .flatMap(HMCLGameRepository::snapshotProperty);

    /// Whether the instance-detail diagnostic page already opened.
    private boolean diagnosticInstanceOpened;

    private Button homeNav;
    private Button libraryNav;
    private Button downloadsNav;
    private Button settingsNav;

    /// The page that was visible before the account page opened.
    private @Nullable Node previousPage;

    /// The breadcrumb of the page that was visible before the account page opened.
    private String previousName = "概览";

    /// The active navigation entry before the account page opened.
    private @Nullable Button previousNav;

    /// Creates the sidebar shell.
    public MfmclRootPage() {
        getStyleClass().addAll("mfmc-root", "mfmcl-root");

        var lightSheet = MfmclRootPage.class.getResource("/assets/css/mfmc-light.css");
        if (lightSheet != null) {
            getStylesheets().add(lightSheet.toExternalForm());
        }
        var darkSheet = MfmclRootPage.class.getResource("/assets/css/mfmcl-dark.css");
        if (darkSheet != null) {
            getStylesheets().add(darkSheet.toExternalForm());
        }

        state.set(new State(null, createTitleNode(), false, false, true));

        homePage = new MfmclHomePage(this::showDownloads, this::showLibrary, this::showSettings,
                this::showAccounts, this::launchSelected);
        libraryPage = new MfmclLibraryPage(this::showDownloads, this::showInstance);
        downloadsPage = new MfmclDownloadsPage(this::showDownloadCategory);

        VBox sidebar = createSidebar();
        VBox main = createMain();

        HBox shell = new HBox(sidebar, main);
        HBox.setHgrow(main, Priority.ALWAYS);
        shell.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        shell.getStyleClass().add("mfmcl-shell");
        getChildren().setAll(shell);

        FXUtils.onChangeAndOperate(Accounts.selectedAccountProperty(), this::updateAccount);

        String initialTab = System.getProperty("mfmc.ui.tab", "launch");
        switch (initialTab) {
            case "library" -> showLibrary();
            case "download" -> showDownloads();
            case "settings" -> showSettings();
            case "accounts" -> showAccounts();
            default -> showHome();
        }

        if ("instance".equals(initialTab)) {
            FXUtils.onChangeAndOperate(repositorySnapshot, snapshot -> {
                if (!diagnosticInstanceOpened) {
                    snapshot.getInstances().stream().findFirst().ifPresent(instance -> {
                        diagnosticInstanceOpened = true;
                        showInstance(instance);
                    });
                }
            });
        }

        String category = System.getProperty("mfmc.ui.category");
        if (category != null && !category.isBlank()) {
            if ("mod".equalsIgnoreCase(category)) {
                showModDownloads();
            } else {
                try {
                    showDownloadCategory(DownloadPage.Category.valueOf(category.toUpperCase(java.util.Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    showDownloads();
                }
            }
        }
    }

    /// Creates the title node displayed by the window decorator.
    private Node createTitleNode() {
        HBox title = new HBox(8);
        title.setAlignment(Pos.CENTER_LEFT);
        ImageView icon = new ImageView(FXUtils.newBuiltinImage("/assets/img/icon-title-mfmc@2x.png"));
        icon.setFitWidth(18);
        icon.setFitHeight(18);
        icon.setPreserveRatio(true);
        Label label = new Label("MFMCL");
        label.getStyleClass().add("mfmcl-window-title");
        title.getChildren().setAll(icon, label);
        return title;
    }

    /// Builds the left sidebar.
    private VBox createSidebar() {
        VBox sidebar = new VBox();
        sidebar.getStyleClass().add("mfmcl-sidebar");
        sidebar.setPrefWidth(248);
        sidebar.setMinWidth(220);

        ImageView brandIcon = new ImageView(FXUtils.newBuiltinImage("/assets/img/icon-mfmc.png"));
        brandIcon.setFitWidth(32);
        brandIcon.setFitHeight(32);
        brandIcon.setPreserveRatio(true);

        Label brandName = new Label("MFMCL");
        brandName.getStyleClass().add("mfmcl-brand-name");
        Label brandSub = new Label("CRAFT YOUR WORLD");
        brandSub.getStyleClass().add("mfmcl-brand-sub");
        VBox brandText = new VBox(1, brandName, brandSub);
        HBox brand = new HBox(10, brandIcon, brandText);
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.getStyleClass().add("mfmcl-brand");

        homeNav = navButton("概览", SVG.HOME, this::showHome);
        libraryNav = navButton("实例库", SVG.PACKAGE2, this::showLibrary);
        downloadsNav = navButton("下载中心", SVG.DOWNLOAD, this::showDownloads);
        settingsNav = navButton("设置", SVG.SETTINGS, this::showSettings);

        Button create = navButton("创建新实例", SVG.ADD, this::showDownloads);
        create.getStyleClass().add("mfmcl-nav-muted");
        Button importPack = navButton("导入整合包", SVG.FOLDER_OPEN, Instances::importModpack);
        importPack.getStyleClass().add("mfmcl-nav-muted");

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        VBox help = new VBox(2);
        help.getStyleClass().add("mfmcl-help-card");
        HBox helpTop = new HBox(8, SVG.HELP.createIcon(18), label("需要一点帮助？"));
        helpTop.setAlignment(Pos.CENTER_LEFT);
        Label helpSub = new Label("看看新手指南与诊断");
        helpSub.getStyleClass().add("mfmcl-muted");
        help.getChildren().setAll(helpTop, helpSub);
        help.setOnMouseClicked(event -> Controllers.dialog(
                Metadata.FULL_NAME + "\n\n" + "基于 HMCL 框架开发\n" + Metadata.VERSION));

        VBox accountBox = new VBox(3, accountName, accountType);
        accountBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(accountBox, Priority.ALWAYS);
        StackPane avatarFrame = new StackPane(accountAvatar);
        avatarFrame.getStyleClass().add("mfmcl-avatar");
        FXUtils.onChangeAndOperate(Accounts.selectedAccountProperty(), account -> {
            TexturesLoader.unbindAvatar(accountAvatar);
            if (account == null) {
                TexturesLoader.drawAvatar(accountAvatar, TexturesLoader.getDefaultSkinImage());
            } else {
                TexturesLoader.bindAvatar(accountAvatar, account);
            }
        });
        HBox accountRow = new HBox(9, avatarFrame, accountBox);
        accountRow.setAlignment(Pos.CENTER_LEFT);
        accountRow.getStyleClass().add("mfmcl-account-footer");
        accountRow.setOnMouseClicked(event -> showAccounts());

        sidebar.getChildren().setAll(
                brand,
                sectionLabel("工作区"), homeNav, libraryNav, downloadsNav, settingsNav,
                sectionLabel("快捷入口"), create, importPack,
                spacer,
                help,
                accountRow
        );
        sidebar.setPadding(new Insets(22, 14, 14, 14));
        sidebar.setSpacing(6);
        return sidebar;
    }

    /// Builds the main column with its top bar and footer.
    private VBox createMain() {
        Label leading = new Label("工作区");
        leading.getStyleClass().add("mfmcl-breadcrumb-muted");
        Label separator = new Label("/");
        separator.getStyleClass().add("mfmcl-breadcrumb-muted");
        breadcrumb.getStyleClass().add("mfmcl-breadcrumb");

        HBox breadcrumbs = new HBox(7, leading, separator, breadcrumb);
        breadcrumbs.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button search = new Button("搜索实例");
        search.setGraphic(SVG.SEARCH.createIcon(15));
        search.getStyleClass().add("mfmcl-search-trigger");
        search.setOnAction(event -> {
            showLibrary();
            libraryPage.focusSearch();
        });

        Label status = new Label("●  本地运行");
        status.getStyleClass().add("mfmcl-status-pill");

        HBox topbar = new HBox(12, breadcrumbs, spacer, search, status);
        topbar.setAlignment(Pos.CENTER_LEFT);
        topbar.getStyleClass().add("mfmcl-topbar");

        content.getStyleClass().add("mfmcl-content");
        content.setMinHeight(0);
        VBox.setVgrow(content, Priority.ALWAYS);

        Label footerStatus = new Label("●  系统状态正常");
        footerStatus.getStyleClass().add("mfmcl-footer-status");
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        Label preview = new Label("预览构建 · " + Metadata.VERSION);
        preview.getStyleClass().add("mfmcl-muted");
        Label about = new Label("关于 MFMCL");
        about.getStyleClass().add("mfmcl-footer-link");
        about.setOnMouseClicked(event -> Controllers.dialog(Metadata.FULL_NAME + "\n" + Metadata.VERSION));
        HBox footer = new HBox(12, footerStatus, footerSpacer, preview, about);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("mfmcl-footer");

        VBox main = new VBox(topbar, content, footer);
        main.getStyleClass().add("mfmcl-main");
        return main;
    }

    private Label sectionLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("mfmcl-section-label");
        return label;
    }

    private Label label(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("mfmcl-help-title");
        return label;
    }

    private Button navButton(String text, SVG icon, Runnable action) {
        Button button = new Button(text);
        SVGContainer graphic = icon.createIcon(18);
        button.setGraphic(graphic);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setMaxWidth(Double.MAX_VALUE);
        button.getStyleClass().add("mfmcl-nav");
        button.setOnAction(event -> action.run());
        navButtons.add(button);
        return button;
    }

    private void selectNav(@Nullable Button button) {
        for (Button nav : navButtons) {
            nav.getStyleClass().remove("active");
        }
        if (button != null) {
            button.getStyleClass().add("active");
        }
    }

    private void showPage(Node page, String name, @Nullable Button nav) {
        content.getChildren().setAll(page);
        breadcrumb.setText(name);
        selectNav(nav);
        MfmcMotion.enter(page);
    }

    /// Wraps a flow page in a transparent scrolling viewport.
    private ScrollPane scrollable(Node page) {
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("mfmcl-scroll");
        return scroll;
    }

    /// Shows the overview page.
    public void showHome() {
        homePage.refresh();
        showPage(scrollable(homePage), "概览", homeNav);
    }

    /// Shows the instance library.
    public void showLibrary() {
        libraryPage.refresh();
        showPage(libraryPage, "实例库", libraryNav);
    }

    /// Shows the download hub.
    public void showDownloads() {
        showPage(scrollable(downloadsPage), "下载中心", downloadsNav);
    }

    /// Shows the settings page.
    public void showSettings() {
        showPage(settingsPage.get(), "设置", settingsNav);
    }

    /// Shows the account management page, remembering where the user came from.
    public void showAccounts() {
        if (!content.getChildren().isEmpty()) {
            previousPage = content.getChildren().get(0);
            previousName = breadcrumb.getText();
            previousNav = findActiveNav();
        }
        showPage(new MfmcAccountPage(this::restorePreviousPage), "账户管理", null);
    }

    /// Returns the currently active navigation button.
    private @Nullable Button findActiveNav() {
        for (Button nav : navButtons) {
            if (nav.getStyleClass().contains("active")) {
                return nav;
            }
        }
        return null;
    }

    /// Restores the page that was visible before the account page opened.
    private void restorePreviousPage() {
        if (previousPage != null) {
            showPage(previousPage, previousName, previousNav);
        } else {
            showHome();
        }
    }

    /// Shows an instance detail page.
    public void showInstance(HMCLGameInstance instance) {
        GameDirectoryManager.setSelectedInstance(instance);
        showPage(scrollable(new MfmclInstancePage(instance, this::showLibrary)),
                "实例库 / " + instance.getId(), libraryNav);
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
        showPage(embedded(page, "返回下载中心", this::showDownloads), "下载中心 / " + name, downloadsNav);
    }

    /// Shows the MFMC-owned mod browser.
    public void showModDownloads() {
        showPage(embedded(new MfmcModDownloadPage(), "返回下载中心", this::showDownloads),
                "下载中心 / 模组", downloadsNav);
    }

    /// Opens the MFMC-owned install page for the selected Minecraft version.
    private void showGameInstallPage(GameRemoteVersion gameVersion) {
        showPage(embedded(new MfmcGameInstallPage(gameVersion, this::showDownloads, this::showLaunchTab),
                "返回下载中心", this::showDownloads), "下载中心 / 安装", downloadsNav);
    }

    /// Wraps an embedded page with a back bar.
    private VBox embedded(Node page, String backText, Runnable backAction) {
        Button back = new Button(backText);
        back.setGraphic(SVG.ARROW_BACK.createIcon(16));
        back.getStyleClass().add("mfmcl-back-button");
        back.setOnAction(event -> backAction.run());

        HBox bar = new HBox(back);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("mfmcl-back-bar");

        VBox wrapper = new VBox(bar, page);
        wrapper.getStyleClass().add("mfmcl-embedded-page");
        VBox.setVgrow(page, Priority.ALWAYS);
        return wrapper;
    }

    /// Shows the launch page after an install completed.
    private void showLaunchTab() {
        showHome();
    }

    private void launchSelected() {
        HMCLGameInstance instance = GameDirectoryManager.getSelectedInstance();
        if (instance != null) {
            Instances.launch(instance);
        } else {
            showDownloads();
        }
    }

    private void updateAccount(@Nullable Account account) {
        if (account == null) {
            accountName.setText("访客模式");
            accountType.setText("尚未登录");
        } else {
            String name = account.getProfileName();
            accountName.setText(name == null || name.isBlank() ? account.getProfileID().toString() : name);
            accountType.setText(Accounts.getLocalizedLoginTypeName(Accounts.getAccountFactory(account)));
        }
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }
}
