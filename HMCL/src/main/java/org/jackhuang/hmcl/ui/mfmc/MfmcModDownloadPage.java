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

import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.jackhuang.hmcl.addon.AddonLoader;
import org.jackhuang.hmcl.addon.NativeAddonCatalog;
import org.jackhuang.hmcl.addon.RemoteAddon;
import org.jackhuang.hmcl.addon.RemoteAddonRepository;
import org.jackhuang.hmcl.addon.repository.CurseForgeRemoteAddonRepository;
import org.jackhuang.hmcl.addon.repository.ModrinthRemoteAddonRepository;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.game.GameInstanceID;
import org.jackhuang.hmcl.game.HMCLGameInstance;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.LocalizedRemoteAddonRepository;
import org.jackhuang.hmcl.setting.DownloadProviders;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.download.DownloadPage;
import org.jackhuang.hmcl.ui.download.ModDownloadPage;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.versioning.GameVersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/// The MFMC-owned mod browser used by the download tab.
///
/// It searches Modrinth/CurseForge, renders its own result list, and shows the
/// available files for the selected project without navigating to the classic
/// HMCL download page.
@NotNullByDefault
public final class MfmcModDownloadPage extends javafx.scene.layout.BorderPane implements ModDownloadPage {
    private static final int PAGE_SIZE = 20;
    private static final String ALL = "全部";

    private final DownloadProvider downloadProvider = DownloadProviders.getDownloadProvider();
    private final LocalizedRepository repository = new LocalizedRepository();

    private final ObservableList<RemoteAddon> mods = FXCollections.observableArrayList();
    private final ListView<RemoteAddon> modList = new ListView<>(mods);
    private ListView<RemoteAddon.Version> versionList = new ListView<>();
    private final VBox detailContent = new VBox(10);
    private final StackPane listStack = new StackPane();
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Label pageLabel = new Label();
    /// Result count shown in the list header.
    private final Label resultLabel = new Label();
    private final Button previousPage = new Button("上一页");
    private final Button nextPage = new Button("下一页");
    private final TextField searchField = new TextField();
    private final TextField gameVersionField = new TextField();
    private final ComboBox<RemoteAddon.Source> sourceBox = new ComboBox<>();
    private final ComboBox<String> categoryBox = new ComboBox<>();
    private final ComboBox<String> loaderBox = new ComboBox<>();
    private final ComboBox<RemoteAddonRepository.SortType> sortBox = new ComboBox<>();
    private final PauseTransition searchDebounce = new PauseTransition(Duration.millis(350));
    private final Map<String, RemoteAddonRepository.Category> categories = new HashMap<>();
    private final Map<String, Image> iconCache = new HashMap<>();
    private final Map<String, List<ImageView>> iconWaiters = new HashMap<>();

    private @Nullable HMCLGameInstance.Optional targetInstance;
    private @Nullable RemoteAddon selectedAddon;
    private @Nullable List<RemoteAddon.Version> loadedVersions;
    private int searchId;
    /// Invalidates older asynchronous version selections when filters or projects change.
    private int versionFilterId;
    /// Distinguishes repeated detail requests, including retries for the same project object.
    private int detailLoadId;
    private int categoryLoadId;
    private int pageOffset;
    private int pageCount = 1;

    /// Creates the mod download page.
    public MfmcModDownloadPage() {
        getStyleClass().add("mfmc-mod-page");
        setMinSize(0, 0);
        setPadding(new Insets(16, 24, 18, 24));

        Label title = new Label("模组下载");
        title.getStyleClass().add("mfmc-mod-page-title");
        Label subtitle = new Label("搜索模组，选择适配当前游戏版本和加载器的文件，下载到实例的 mods 目录。");
        subtitle.getStyleClass().add("mfmc-mod-page-subtitle");
        subtitle.setWrapText(true);
        VBox header = new VBox(3, title, subtitle);

        configureFilters();
        VBox toolbar = createToolbar();

        configureList();
        configureDetail();
        configurePagination();

        VBox listCard = new VBox(0, createListHeader(), listStack, createPaginationBar());
        listCard.getStyleClass().addAll("mfmc-card", "mfmc-mod-list-card");
        HBox.setHgrow(listCard, Priority.ALWAYS);
        listCard.setMaxWidth(Double.MAX_VALUE);
        listCard.setMinWidth(0);
        listCard.setMinHeight(0);

        VBox detailCard = new VBox(0, createDetailHeader(), detailContent);
        detailCard.getStyleClass().addAll("mfmc-card", "mfmc-mod-detail-card");
        detailCard.setPrefWidth(380);
        detailCard.setMinWidth(320);
        detailCard.setMaxWidth(430);
        detailCard.setMinHeight(0);
        detailContent.setMinHeight(0);
        VBox.setVgrow(detailContent, Priority.ALWAYS);

        HBox body = new HBox(14, listCard, detailCard);
        body.setAlignment(Pos.TOP_LEFT);
        body.setFillHeight(true);
        body.setMinHeight(0);

        setTop(new VBox(14, header, toolbar));
        setCenter(body);

        loadInstance(HMCLGameInstance.Optional.of(
                GameDirectoryManager.getSelectedRepository(),
                GameDirectoryManager.getSelectedInstance() != null
                        ? GameDirectoryManager.getSelectedInstance().getId()
                        : null
        ));
        loadCategories();
        search(0);
    }

    /// Builds the two-row search and filter toolbar.
    private VBox createToolbar() {
        Label searchIcon = new Label();
        searchIcon.setGraphic(SVG.SEARCH.createIcon(14));
        searchIcon.getStyleClass().add("mfmc-mod-search-icon");
        searchIcon.setMouseTransparent(true);
        searchField.setPadding(new Insets(6, 10, 6, 34));

        StackPane searchBox = new StackPane(searchField, searchIcon);
        searchBox.setAlignment(Pos.CENTER_LEFT);
        StackPane.setMargin(searchIcon, new Insets(0, 0, 0, 12));
        searchBox.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(searchBox, Priority.ALWAYS);

        HBox searchRow = new HBox(10, searchBox, createSearchButton());
        searchRow.setAlignment(Pos.CENTER_LEFT);

        FlowPane filterRow = new FlowPane(16, 8,
                filterGroup("来源", sourceBox),
                filterGroup("游戏版本", gameVersionField),
                filterGroup("分类", categoryBox),
                filterGroup("排序", sortBox));
        filterRow.setAlignment(Pos.CENTER_LEFT);
        filterRow.setRowValignment(VPos.CENTER);

        VBox toolbar = new VBox(10, searchRow, filterRow);
        toolbar.getStyleClass().addAll("mfmc-card", "mfmc-mod-toolbar");
        return toolbar;
    }

    /// Builds the header above the result list.
    private HBox createListHeader() {
        Label heading = new Label("模组列表");
        heading.getStyleClass().add("mfmc-mod-section-title");
        resultLabel.getStyleClass().add("mfmc-mod-result-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, heading, spacer, resultLabel);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("mfmc-mod-list-header");
        return bar;
    }

    /// Builds the header above the version panel.
    private HBox createDetailHeader() {
        Label heading = new Label("模组详情");
        heading.getStyleClass().add("mfmc-mod-section-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, heading, spacer);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("mfmc-mod-list-header");
        return bar;
    }

    private void configureFilters() {
        searchField.setPromptText("搜索模组名称，回车开始搜索");
        searchField.setPrefWidth(420);
        searchField.textProperty().addListener((observable, oldValue, newValue) -> searchDebounce.playFromStart());
        searchField.setOnAction(event -> search(0));
        searchDebounce.setOnFinished(event -> search(0));

        gameVersionField.setPromptText("如 1.20.1");
        gameVersionField.setPrefWidth(100);
        gameVersionField.setOnAction(event -> search(0));

        List<RemoteAddon.Source> sources = new ArrayList<>();
        sources.add(RemoteAddon.Source.MODRINTH);
        if (CurseForgeRemoteAddonRepository.isAvailable()) {
            sources.add(RemoteAddon.Source.CURSEFORGE);
        }
        sourceBox.getItems().setAll(sources);
        sourceBox.setValue(sources.get(0));
        sourceBox.setPrefWidth(120);
        sourceBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(@Nullable RemoteAddon.Source source) {
                return source == RemoteAddon.Source.CURSEFORGE ? "CurseForge" : "Modrinth";
            }

            @Override
            public RemoteAddon.Source fromString(String string) {
                return RemoteAddon.Source.CURSEFORGE;
            }
        });
        sourceBox.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                repository.setSource(newValue);
                loadCategories();
                search(0);
            }
        });

        categoryBox.getItems().add(ALL);
        categoryBox.setValue(ALL);
        categoryBox.setPrefWidth(125);
        categoryBox.valueProperty().addListener((observable, oldValue, newValue) -> search(0));

        loaderBox.getItems().setAll(ALL, "Fabric", "Forge", "NeoForge", "Quilt");
        loaderBox.setValue(ALL);
        loaderBox.setPrefWidth(130);
        loaderBox.valueProperty().addListener((observable, oldValue, newValue) -> applyVersionFilter());

        sortBox.getItems().setAll(
                RemoteAddonRepository.SortType.RELEVANCY,
                RemoteAddonRepository.SortType.POPULARITY,
                RemoteAddonRepository.SortType.LAST_UPDATED
        );
        sortBox.setValue(RemoteAddonRepository.SortType.RELEVANCY);
        sortBox.setPrefWidth(115);
        sortBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(@Nullable RemoteAddonRepository.SortType type) {
                if (type == null) return "";
                return switch (type) {
                    case POPULARITY -> "下载量";
                    case LAST_UPDATED -> "最近更新";
                    default -> "相关度";
                };
            }

            @Override
            public RemoteAddonRepository.SortType fromString(String string) {
                return RemoteAddonRepository.SortType.RELEVANCY;
            }
        });
        sortBox.valueProperty().addListener((observable, oldValue, newValue) -> search(0));
    }

    private Button createSearchButton() {
        Button button = new Button("搜索");
        button.getStyleClass().add("mfmc-mod-search-button");
        button.setOnAction(event -> search(0));
        return button;
    }

    private Label filterLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("mfmc-mod-filter-label");
        return label;
    }

    private HBox filterGroup(String text, javafx.scene.Node control) {
        HBox group = new HBox(6, filterLabel(text), control);
        group.setAlignment(Pos.CENTER_LEFT);
        return group;
    }

    private void configureList() {
        modList.getStyleClass().addAll("mfmc-mod-list", "no-horizontal-scrollbar");
        modList.setFixedCellSize(88);
        modList.setMinHeight(0);
        modList.setCellFactory(ignored -> new ModCell());
        modList.setPlaceholder(emptyLabel("没有找到模组"));
        modList.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                showDetail(newValue);
            }
        });
        progress.setMaxSize(36, 36);
        progress.setVisible(false);
        listStack.setMinHeight(0);
        listStack.getChildren().setAll(modList, progress);
        VBox.setVgrow(listStack, Priority.ALWAYS);
    }

    /// Creates a muted label used by the list placeholders.
    private static Label emptyLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("mfmc-mod-empty-label");
        return label;
    }

    /// Collapses whitespace and limits a project description to one short paragraph.
    private static String summarize(String text, int maxLength) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength).stripTrailing() + "…";
    }

    private void configureDetail() {
        detailContent.setFillWidth(true);
        detailContent.getStyleClass().add("mfmc-mod-detail-content");
        detailContent.setMinHeight(0);
        showDetailPlaceholder();
    }

    private void configurePagination() {
        previousPage.getStyleClass().add("mfmc-mod-page-button");
        nextPage.getStyleClass().add("mfmc-mod-page-button");
        pageLabel.getStyleClass().add("mfmc-mod-page-label");
        previousPage.setOnAction(event -> search(Math.max(0, pageOffset - 1)));
        nextPage.setOnAction(event -> search(pageOffset + 1));
        updatePagination();
    }

    private HBox createPaginationBar() {
        Region left = new Region();
        Region right = new Region();
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox bar = new HBox(8, left, previousPage, pageLabel, nextPage, right);
        bar.setAlignment(Pos.CENTER);
        bar.getStyleClass().add("mfmc-mod-pagination");
        return bar;
    }

    private void updatePagination() {
        pageLabel.setText("第 " + (pageOffset + 1) + " / " + Math.max(1, pageCount) + " 页");
        previousPage.setDisable(pageOffset <= 0);
        nextPage.setDisable(pageOffset + 1 >= pageCount);
    }

    private void setLoading(boolean loading) {
        progress.setVisible(loading);
        progress.setManaged(loading);
    }

    /// Starts a new search and invalidates detail/filter work from the previous result set.
    private void search(int page) {
        int currentSearchId = ++searchId;
        versionFilterId++;
        detailLoadId++;
        selectedAddon = null;
        loadedVersions = null;
        modList.getSelectionModel().clearSelection();
        showDetailPlaceholder();
        String gameVersion = gameVersionField.getText().trim();
        RemoteAddonRepository.Category category = categories.get(categoryBox.getValue());
        RemoteAddonRepository.SortType sort = sortBox.getValue() != null
                ? sortBox.getValue()
                : RemoteAddonRepository.SortType.RELEVANCY;
        String query = searchField.getText().trim();

        setLoading(true);
        resultLabel.setText("加载中…");
        Task.supplyAsync(Schedulers.io(), () ->
                        repository.search(downloadProvider, RemoteAddon.Type.MOD, gameVersion, category, page, PAGE_SIZE, query, sort, RemoteAddonRepository.SortOrder.DESC))
                .whenComplete(Schedulers.javafx(), (result, exception) -> {
                    if (currentSearchId != searchId) {
                        return;
                    }
                    setLoading(false);
                    if (exception != null) {
                        mods.clear();
                        pageCount = 1;
                        pageOffset = 0;
                        updatePagination();
                        resultLabel.setText("加载失败");
                        modList.setPlaceholder(emptyLabel("加载失败，请检查网络后重试"));
                        Controllers.showToast("模组列表加载失败");
                        return;
                    }
                    mods.setAll(result.results().collect(Collectors.toList()));
                    modList.setPlaceholder(emptyLabel("没有找到模组"));
                    pageOffset = page;
                    pageCount = Math.max(1, result.totalPages());
                    resultLabel.setText(mods.size() + " 个结果");
                    modList.scrollTo(0);
                    updatePagination();
                })
                .start();
    }

    private void loadCategories() {
        int currentLoadId = ++categoryLoadId;
        Task.supplyAsync(Schedulers.io(), () -> repository.getCategories(RemoteAddon.Type.MOD).toList())
                .whenComplete(Schedulers.javafx(), (list, exception) -> {
                    if (currentLoadId != categoryLoadId) {
                        return;
                    }
                    categoryBox.getItems().setAll(ALL);
                    categories.clear();
                    if (exception == null) {
                        for (RemoteAddonRepository.Category category : list) {
                            categoryBox.getItems().add(category.id());
                            categories.put(category.id(), category);
                        }
                    }
                    categoryBox.setValue(ALL);
                })
                .start();
    }

    private void showDetailPlaceholder() {
        StackPane icon = new StackPane(SVG.PACKAGE2.createIcon(30));
        icon.getStyleClass().add("mfmc-mod-placeholder-icon");
        icon.setMinSize(64, 64);
        icon.setPrefSize(64, 64);
        icon.setMaxSize(64, 64);

        Label title = new Label("选择一个模组");
        title.getStyleClass().add("mfmc-mod-placeholder-title");
        Label hint = new Label("在左侧列表中选择模组，查看可用版本并下载到实例。");
        hint.getStyleClass().add("mfmc-mod-placeholder-text");
        hint.setWrapText(true);
        hint.setTextAlignment(TextAlignment.CENTER);
        hint.setMaxWidth(240);

        VBox box = new VBox(12, icon, title, hint);
        box.setAlignment(Pos.CENTER);
        box.setFillWidth(false);
        box.getStyleClass().add("mfmc-mod-placeholder");
        detailContent.setAlignment(Pos.CENTER);
        detailContent.getChildren().setAll(box);
    }

    /// Fetches versions for one project, invalidating pending selections of the previous project.
    private void showDetail(RemoteAddon addon) {
        versionFilterId++;
        int requestId = ++detailLoadId;
        selectedAddon = addon;
        loadedVersions = null;
        detailContent.setAlignment(Pos.CENTER);
        detailContent.getChildren().setAll(emptyLabel("正在加载版本…"));
        Task.supplyAsync(Schedulers.io(), () -> addon.data().loadVersions(repository, downloadProvider).toList())
                .whenComplete(Schedulers.javafx(), (versions, exception) -> {
                    if (requestId != detailLoadId || selectedAddon != addon) {
                        return;
                    }
                    if (exception != null) {
                        Label failed = emptyLabel("版本列表加载失败");
                        detailContent.setAlignment(Pos.CENTER);
                        detailContent.getChildren().setAll(failed);
                        return;
                    }
                    loadedVersions = versions;
                    buildDetail(addon, versions);
                })
                .start();
    }

    private void buildDetail(RemoteAddon addon, List<RemoteAddon.Version> versions) {
        ImageView icon = new ImageView(FXUtils.newBuiltinImage("/assets/img/grass.png"));
        icon.setFitWidth(48);
        icon.setFitHeight(48);
        icon.setPreserveRatio(true);
        icon.setSmooth(true);
        loadIcon(addon.iconUrl(), icon, 48);

        StackPane iconBox = new StackPane(icon);
        iconBox.getStyleClass().add("mfmc-mod-detail-icon");
        iconBox.setMinSize(58, 58);
        iconBox.setPrefSize(58, 58);
        iconBox.setMaxSize(58, 58);

        Label title = new Label(addon.title());
        title.getStyleClass().add("mfmc-mod-detail-title");
        title.setWrapText(true);
        Label author = new Label(StringUtils.isBlank(addon.author()) ? "未知作者" : addon.author());
        author.getStyleClass().add("mfmc-mod-detail-author");
        Label description = new Label(summarize(addon.description(), 220));
        description.getStyleClass().add("mfmc-mod-detail-desc");
        description.setWrapText(true);
        description.setMaxHeight(96);

        VBox identity = new VBox(2, title, author, description);
        HBox.setHgrow(identity, Priority.ALWAYS);
        HBox header = new HBox(12, iconBox, identity);
        header.setAlignment(Pos.TOP_LEFT);

        HBox tags = new HBox(6);
        for (String category : addon.categories().stream().limit(4).toList()) {
            Label tag = new Label(category);
            tag.getStyleClass().add("mfmc-mod-tag");
            tags.getChildren().add(tag);
        }
        tags.setAlignment(Pos.CENTER_LEFT);

        Label versionsHeading = new Label("可用版本");
        versionsHeading.getStyleClass().add("mfmc-mod-section-title");
        Region versionsSpacer = new Region();
        HBox.setHgrow(versionsSpacer, Priority.ALWAYS);
        HBox versionsBar = new HBox(8, versionsHeading, versionsSpacer, filterLabel("加载器"), loaderBox);
        versionsBar.setAlignment(Pos.CENTER_LEFT);
        versionsBar.getStyleClass().add("mfmc-mod-detail-section");

        versionList = new ListView<>();
        versionList.getStyleClass().addAll("mfmc-mod-version-list", "no-horizontal-scrollbar");
        versionList.setFixedCellSize(78);
        versionList.setMinHeight(120);
        versionList.setCellFactory(ignored -> new VersionCell());
        versionList.setPlaceholder(emptyLabel("当前筛选条件下没有可用版本"));

        VBox content = new VBox(12, header, tags, versionsBar, versionList);
        content.setMinHeight(0);
        VBox.setVgrow(versionList, Priority.ALWAYS);

        detailContent.setAlignment(Pos.TOP_LEFT);
        detailContent.getChildren().setAll(content);
        VBox.setVgrow(content, Priority.ALWAYS);
        applyVersionFilter();
    }

    /// Runs content-store selection off the UI thread and ignores stale selections.
    private void applyVersionFilter() {
        if (loadedVersions == null) {
            return;
        }
        String gameVersion = gameVersionField.getText().trim();
        @Nullable String loader = loaderBox.getValue();
        List<RemoteAddon.Version> versions = loadedVersions;
        int filterId = ++versionFilterId;
        // Native IPC can start a process. Keep it off the JavaFX application thread.
        Task.supplyAsync(Schedulers.io(), () -> NativeAddonCatalog.filterVersions(versions, gameVersion, ALL.equals(loader) ? "" : loader))
                .whenComplete(Schedulers.javafx(), (filtered, exception) -> {
                    if (filterId != versionFilterId) return;
                    if (exception != null) {
                        versionList.getItems().clear();
                        versionList.setPlaceholder(emptyLabel("版本筛选失败，请重试"));
                        return;
                    }
                    versionList.getItems().setAll(filtered);
                    versionList.setPlaceholder(emptyLabel("当前筛选条件下没有可用版本"));
                    versionList.scrollTo(0);
                }).start();
    }

    private void loadIcon(String url, ImageView view, double size) {
        if (StringUtils.isBlank(url)) {
            return;
        }

        Image cached = iconCache.get(url);
        if (cached != null) {
            view.setImage(cached);
            return;
        }

        List<ImageView> waiters = iconWaiters.get(url);
        if (waiters != null) {
            waiters.add(view);
            return;
        }

        waiters = new ArrayList<>();
        waiters.add(view);
        iconWaiters.put(url, waiters);

        FXUtils.getRemoteImageTask(url, (int) size, (int) size, true, true)
                .whenComplete(Schedulers.javafx(), (image, exception) -> {
                    List<ImageView> views = iconWaiters.remove(url);
                    if (exception == null && image != null) {
                        iconCache.put(url, image);
                        if (views != null) {
                            for (ImageView target : views) {
                                target.setImage(image);
                            }
                        }
                    }
                })
                .start();
    }

    @Override
    public void loadInstance(HMCLGameInstance.Optional instance) {
        targetInstance = instance;
        HMCLGameInstance gameInstance = instance.instance();
        if (gameInstance != null) {
            GameVersionNumber version = gameInstance.getVersion();
            if (version != GameVersionNumber.unknown()) {
                gameVersionField.setText(version.toString());
            }
        }
    }

    @Override
    public void selectInstance(GameInstanceID instanceId) {
        loadInstance(HMCLGameInstance.Optional.of(GameDirectoryManager.getSelectedRepository(), instanceId));
    }

    /// Downloads the selected file into the instance mods directory.
    private void download(RemoteAddon.Version version) {
        HMCLGameRepository repository = targetInstance != null
                ? targetInstance.repository()
                : GameDirectoryManager.getSelectedRepository();
        GameInstanceID instanceId = targetInstance != null ? targetInstance.instanceId() : null;
        DownloadPage.download(downloadProvider, repository, instanceId, version, "mods");
    }

    /// One mod project row.
    private final class ModCell extends ListCell<RemoteAddon> {
        private final ImageView icon = new ImageView(FXUtils.newBuiltinImage("/assets/img/grass.png"));
        private final StackPane iconBox = new StackPane(icon);
        private final Label title = new Label();
        private final Label author = new Label();
        private final Label description = new Label();
        private final HBox tags = new HBox(5);
        private final Label chevron = new Label("›");
        private final HBox row;

        private ModCell() {
            icon.setFitWidth(38);
            icon.setFitHeight(38);
            icon.setPreserveRatio(true);
            icon.setSmooth(true);
            iconBox.getStyleClass().add("mfmc-mod-icon");
            iconBox.setMinSize(46, 46);
            iconBox.setPrefSize(46, 46);
            iconBox.setMaxSize(46, 46);

            title.getStyleClass().add("mfmc-mod-row-title");
            author.getStyleClass().add("mfmc-mod-row-author");
            description.getStyleClass().add("mfmc-mod-row-desc");
            description.setWrapText(false);
            description.setMaxHeight(18);
            tags.setAlignment(Pos.CENTER_LEFT);

            VBox text = new VBox(2, title, author, description, tags);
            text.setFillWidth(true);
            text.setMinWidth(0);
            title.setMaxWidth(Double.MAX_VALUE);
            author.setMaxWidth(Double.MAX_VALUE);
            description.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(text, Priority.ALWAYS);

            chevron.getStyleClass().add("mfmc-mod-chevron");

            row = new HBox(12, iconBox, text, chevron);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setMaxWidth(Double.MAX_VALUE);
            row.getStyleClass().add("mfmc-mod-row");
            text.setMinWidth(0);
            title.setMinWidth(0);
            author.setMinWidth(0);
            description.setMinWidth(0);
            tags.setMinWidth(0);
            setPrefWidth(0);
            row.setOnMouseClicked(event -> {
                RemoteAddon item = getItem();
                if (item != null) {
                    if (getListView().getSelectionModel().getSelectedItem() == item) {
                        if (loadedVersions == null) showDetail(item);
                    } else {
                        getListView().getSelectionModel().select(item);
                    }
                }
            });
            setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        }

        @Override
        protected void updateItem(@Nullable RemoteAddon item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }

            icon.setImage(FXUtils.newBuiltinImage("/assets/img/grass.png"));
            loadIcon(item.iconUrl(), icon, 38);
            title.setText(item.title());
            author.setText(StringUtils.isBlank(item.author()) ? "未知作者" : item.author());
            description.setText(summarize(item.description(), 160));
            tags.getChildren().setAll(item.categories().stream().limit(3).map(category -> {
                Label label = new Label(category);
                label.getStyleClass().add("mfmc-mod-tag");
                return label;
            }).toList());
            setGraphic(row);
        }
    }

    /// One downloadable file row.
    private final class VersionCell extends ListCell<RemoteAddon.Version> {
        private final Label title = new Label();
        private final Label meta = new Label();
        private final Button downloadButton = new Button("下载");
        private final HBox row;

        private VersionCell() {
            title.getStyleClass().add("mfmc-mod-version-title");
            meta.getStyleClass().add("mfmc-mod-version-meta");
            meta.setWrapText(true);
            VBox labels = new VBox(2, title, meta);
            HBox.setHgrow(labels, Priority.ALWAYS);
            downloadButton.getStyleClass().add("mfmc-mod-download-button");
            row = new HBox(8, labels, downloadButton);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setMaxWidth(Double.MAX_VALUE);
            labels.setMinWidth(0);
            title.setMinWidth(0);
            meta.setMinWidth(0);
            setPrefWidth(0);
            setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        }

        @Override
        protected void updateItem(@Nullable RemoteAddon.Version item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            title.setText(StringUtils.isBlank(item.name()) ? item.version() : item.name());
            String gameVersions = item.gameVersions().isEmpty()
                    ? "通用"
                    : String.join(", ", item.gameVersions().stream().limit(3).toList());
            String loaders = item.loaders().isEmpty()
                    ? "通用"
                    : item.loaders().stream().map(AddonLoader::name).limit(3).collect(Collectors.joining(", "));
            Instant date = item.datePublished();
            meta.setText(gameVersions + " · " + loaders + (date != null ? " · " + date.toString().substring(0, 10) : ""));
            downloadButton.setOnAction(event -> MfmcModDownloadPage.this.download(item));
            setGraphic(row);
        }
    }

    /// Repository wrapper that adds Chinese search support.
    private static final class LocalizedRepository extends LocalizedRemoteAddonRepository {
        private RemoteAddon.Source source = RemoteAddon.Source.MODRINTH;

        private void setSource(RemoteAddon.Source source) {
            this.source = source;
        }

        @Override
        protected RemoteAddonRepository getBackedRemoteModRepository() {
            return source == RemoteAddon.Source.CURSEFORGE
                    ? CurseForgeRemoteAddonRepository.getInstance()
                    : ModrinthRemoteAddonRepository.getInstance();
        }
    }
}
