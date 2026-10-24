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
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.jackhuang.hmcl.addon.AddonLoader;
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
import org.jackhuang.hmcl.ui.download.DownloadPage;
import org.jackhuang.hmcl.ui.download.ModDownloadPage;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.versioning.GameVersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final ScrollPane detailScroll = new ScrollPane();
    private final StackPane listStack = new StackPane();
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Label pageLabel = new Label();
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
    private int categoryLoadId;
    private int pageOffset;
    private int pageCount = 1;

    /// Creates the mod download page.
    public MfmcModDownloadPage() {
        getStyleClass().add("mfmc-mod-page");

        Label title = new Label("模组下载");
        title.getStyleClass().add("mfmc-mod-page-title");
        Label subtitle = new Label("搜索模组，选择适配当前游戏版本和加载器的文件，下载到实例的 mods 目录。");
        subtitle.getStyleClass().add("mfmc-mod-page-subtitle");
        subtitle.setWrapText(true);
        VBox header = new VBox(2, title, subtitle);

        configureFilters();
        FlowPane filters = new FlowPane(10, 8,
                filterGroup("搜索", searchField),
                filterGroup("来源", sourceBox),
                filterGroup("游戏版本", gameVersionField),
                filterGroup("分类", categoryBox),
                filterGroup("排序", sortBox),
                createSearchButton());
        filters.setAlignment(Pos.CENTER_LEFT);
        filters.getStyleClass().add("mfmc-mod-filter");

        configureList();
        configureDetail();
        configurePagination();

        VBox listCard = new VBox(10, listStack, createPaginationBar());
        listCard.getStyleClass().addAll("mfmc-card", "mfmc-mod-list-card");
        HBox.setHgrow(listCard, Priority.ALWAYS);
        listCard.setMaxWidth(Double.MAX_VALUE);

        VBox detailCard = new VBox(detailScroll);
        detailCard.getStyleClass().addAll("mfmc-card", "mfmc-mod-detail-card");
        detailCard.setPrefWidth(370);
        detailCard.setMinWidth(310);
        detailCard.setMaxWidth(420);
        VBox.setVgrow(detailScroll, Priority.ALWAYS);

        HBox body = new HBox(12, listCard, detailCard);
        body.setAlignment(Pos.TOP_LEFT);
        body.setFillHeight(true);

        setTop(new VBox(12, header, filters));
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

    private void configureFilters() {
        searchField.setPromptText("模组名称");
        searchField.setPrefWidth(220);
        searchField.textProperty().addListener((observable, oldValue, newValue) -> searchDebounce.playFromStart());
        searchField.setOnAction(event -> search(0));
        searchDebounce.setOnFinished(event -> search(0));

        gameVersionField.setPromptText("如 1.20.1");
        gameVersionField.setPrefWidth(105);
        gameVersionField.setOnAction(event -> search(0));

        List<RemoteAddon.Source> sources = new ArrayList<>();
        sources.add(RemoteAddon.Source.MODRINTH);
        if (CurseForgeRemoteAddonRepository.isAvailable()) {
            sources.add(RemoteAddon.Source.CURSEFORGE);
        }
        sourceBox.getItems().setAll(sources);
        sourceBox.setValue(sources.get(0));
        sourceBox.setPrefWidth(125);
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
        categoryBox.setPrefWidth(130);
        categoryBox.valueProperty().addListener((observable, oldValue, newValue) -> search(0));

        loaderBox.getItems().setAll(ALL, "Fabric", "Forge", "NeoForge", "Quilt");
        loaderBox.setValue(ALL);
        loaderBox.setPrefWidth(120);
        loaderBox.valueProperty().addListener((observable, oldValue, newValue) -> applyVersionFilter());

        sortBox.getItems().setAll(
                RemoteAddonRepository.SortType.RELEVANCY,
                RemoteAddonRepository.SortType.POPULARITY,
                RemoteAddonRepository.SortType.LAST_UPDATED
        );
        sortBox.setValue(RemoteAddonRepository.SortType.RELEVANCY);
        sortBox.setPrefWidth(125);
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
        modList.setFixedCellSize(92);
        modList.setCellFactory(ignored -> new ModCell());
        modList.setPlaceholder(new Label("没有找到模组"));
        modList.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                showDetail(newValue);
            }
        });
        progress.setMaxSize(36, 36);
        progress.setVisible(false);
        listStack.getChildren().setAll(modList, progress);
        VBox.setVgrow(listStack, Priority.ALWAYS);
    }

    private void configureDetail() {
        detailContent.setFillWidth(true);
        detailContent.getStyleClass().add("mfmc-mod-detail-content");
        detailScroll.setContent(detailContent);
        detailScroll.setFitToWidth(true);
        detailScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        detailScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        detailScroll.getStyleClass().add("mfmc-mod-detail-scroll");
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
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, pageLabel, spacer, previousPage, nextPage);
        bar.setAlignment(Pos.CENTER_LEFT);
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

    private void search(int page) {
        int currentSearchId = ++searchId;
        String gameVersion = gameVersionField.getText().trim();
        RemoteAddonRepository.Category category = categories.get(categoryBox.getValue());
        RemoteAddonRepository.SortType sort = sortBox.getValue() != null
                ? sortBox.getValue()
                : RemoteAddonRepository.SortType.RELEVANCY;
        String query = searchField.getText().trim();

        setLoading(true);
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
                        modList.setPlaceholder(new Label("加载失败，请检查网络后重试"));
                        Controllers.showToast("模组列表加载失败");
                        return;
                    }
                    mods.setAll(result.results().collect(Collectors.toList()));
                    pageOffset = page;
                    pageCount = Math.max(1, result.totalPages());
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
        Label label = new Label("选择左侧模组查看可用版本");
        label.getStyleClass().add("mfmc-mod-detail-placeholder");
        label.setWrapText(true);
        detailContent.getChildren().setAll(label);
    }

    private void showDetail(RemoteAddon addon) {
        selectedAddon = addon;
        loadedVersions = null;
        detailContent.getChildren().setAll(new Label("正在加载版本..."));
        Task.supplyAsync(Schedulers.io(), () -> addon.data().loadVersions(repository, downloadProvider).toList())
                .whenComplete(Schedulers.javafx(), (versions, exception) -> {
                    if (selectedAddon != addon) {
                        return;
                    }
                    if (exception != null) {
                        Label failed = new Label("版本列表加载失败");
                        failed.getStyleClass().add("mfmc-mod-detail-placeholder");
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
        icon.setFitWidth(56);
        icon.setFitHeight(56);
        icon.setPreserveRatio(true);
        icon.setSmooth(true);
        loadIcon(addon.iconUrl(), icon, 56);

        Label title = new Label(addon.title());
        title.getStyleClass().add("mfmc-mod-detail-title");
        title.setWrapText(true);
        Label author = new Label(StringUtils.isBlank(addon.author()) ? "未知作者" : addon.author());
        author.getStyleClass().add("mfmc-mod-detail-author");
        Label description = new Label(addon.description());
        description.getStyleClass().add("mfmc-mod-detail-desc");
        description.setWrapText(true);

        VBox identity = new VBox(2, title, author, description);
        HBox.setHgrow(identity, Priority.ALWAYS);
        HBox header = new HBox(12, icon, identity);
        header.setAlignment(Pos.TOP_LEFT);

        HBox tags = new HBox(6);
        for (String category : addon.categories().stream().limit(4).toList()) {
            Label tag = new Label(category);
            tag.getStyleClass().add("mfmc-mod-tag");
            tags.getChildren().add(tag);
        }

        HBox loaderBar = new HBox(8, filterLabel("加载器"), loaderBox);
        loaderBar.setAlignment(Pos.CENTER_LEFT);

        versionList = new ListView<>();
        versionList.getStyleClass().addAll("mfmc-mod-version-list", "no-horizontal-scrollbar");
        versionList.setFixedCellSize(78);
        versionList.setCellFactory(ignored -> new VersionCell());
        versionList.setPlaceholder(new Label("当前筛选条件下没有可用版本"));

        VBox content = new VBox(10, header, tags, loaderBar, versionList);
        VBox.setVgrow(versionList, Priority.ALWAYS);

        detailContent.getChildren().setAll(content);
        applyVersionFilter();
    }

    private void applyVersionFilter() {
        if (loadedVersions == null) {
            return;
        }
        String gameVersion = gameVersionField.getText().trim();
        String loader = loaderBox.getValue();
        List<RemoteAddon.Version> filtered = loadedVersions.stream()
                .filter(version -> StringUtils.isBlank(gameVersion)
                        || version.gameVersions().isEmpty()
                        || version.gameVersions().stream().anyMatch(value -> value.equalsIgnoreCase(gameVersion)))
                .filter(version -> StringUtils.isBlank(loader)
                        || ALL.equals(loader)
                        || version.loaders().stream().map(AddonLoader::name).anyMatch(name -> name.equalsIgnoreCase(loader)))
                .sorted(Comparator.comparing(RemoteAddon.Version::datePublished, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
        versionList.getItems().setAll(filtered);
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
        private final Label title = new Label();
        private final Label author = new Label();
        private final Label description = new Label();
        private final HBox tags = new HBox(5);
        private final Button detail = new Button("查看版本");
        private final HBox row;

        private ModCell() {
            icon.setFitWidth(48);
            icon.setFitHeight(48);
            icon.setPreserveRatio(true);
            icon.setSmooth(true);

            title.getStyleClass().add("mfmc-mod-row-title");
            author.getStyleClass().add("mfmc-mod-row-author");
            description.getStyleClass().add("mfmc-mod-row-desc");
            description.setWrapText(true);
            description.setMaxHeight(34);
            tags.setAlignment(Pos.CENTER_LEFT);

            VBox text = new VBox(2, title, author, description, tags);
            text.setFillWidth(true);
            text.setMinWidth(0);
            title.setMaxWidth(Double.MAX_VALUE);
            author.setMaxWidth(Double.MAX_VALUE);
            description.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(text, Priority.ALWAYS);

            detail.getStyleClass().add("mfmc-mod-open-button");
            detail.setOnAction(event -> {
                RemoteAddon item = getItem();
                if (item != null) {
                    showDetail(item);
                }
            });

            row = new HBox(12, icon, text, detail);
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
                    getListView().getSelectionModel().select(item);
                    showDetail(item);
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
            loadIcon(item.iconUrl(), icon, 48);
            title.setText(item.title());
            author.setText(StringUtils.isBlank(item.author()) ? "未知作者" : item.author());
            description.setText(item.description());
            tags.getChildren().setAll(item.categories().stream().limit(3).map(category -> {
                Label label = new Label(category);
                label.getStyleClass().add("mfmc-mod-tag");
                return label;
            }).toList());
            detail.setText("查看版本");
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
