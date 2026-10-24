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

import com.jfoenix.controls.JFXButton;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.AccountFactory;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorAccount;
import org.jackhuang.hmcl.game.TexturesLoader;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.account.AccountListItem;
import org.jackhuang.hmcl.ui.account.CreateAccountPane;
import org.jackhuang.hmcl.util.StringUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// The MFMC-owned account management page.
///
/// This page deliberately stays inside the MFMC shell instead of navigating to
/// the classic HMCL account list.
@NotNullByDefault
public final class MfmcAccountPage extends BorderPane {
    /// Returns to the launch tab.
    private final Runnable backAction;

    /// The account list bound directly to the shared account store.
    private final ListView<Account> accountList = new ListView<>(Accounts.getAccounts());

    /// Creates the account management page.
    ///
    /// @param backAction action used by the back button
    public MfmcAccountPage(Runnable backAction) {
        this.backAction = backAction;
        getStyleClass().add("mfmc-account-page");

        accountList.getStyleClass().add("mfmc-account-list");
        accountList.setFixedCellSize(78);
        accountList.setCellFactory(ignored -> new AccountCell());
        accountList.setPlaceholder(createEmptyState());
        accountList.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && !isCurrentAccount(newValue)) {
                Accounts.setSelectedAccount(newValue);
            }
        });
        FXUtils.onWeakChangeAndOperate(Accounts.selectedAccountProperty(), account -> {
            if (account == null) {
                accountList.getSelectionModel().clearSelection();
            } else if (!isCurrentAccount(account)) {
                accountList.getSelectionModel().select(account);
            }
            accountList.refresh();
        });

        VBox card = new VBox(accountList);
        card.getStyleClass().add("mfmc-card");
        VBox.setVgrow(accountList, Priority.ALWAYS);

        setTop(createHeader());
        setCenter(card);
    }

    /// Builds the page header.
    private HBox createHeader() {
        JFXButton back = new JFXButton();
        back.setGraphic(SVG.ARROW_BACK.createIcon(18));
        back.setFocusTraversable(false);
        back.getStyleClass().add("mfmc-install-back");
        FXUtils.installFastTooltip(back, "返回");
        back.setOnAction(event -> backAction.run());

        Label title = new Label("账号管理");
        title.getStyleClass().add("mfmc-install-title");

        Label subtitle = new Label("选择启动时使用的账号，或添加新的正版、离线账号。");
        subtitle.getStyleClass().add("mfmc-account-page-subtitle");

        VBox titleBox = new VBox(2, title, subtitle);
        titleBox.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        JFXButton add = new JFXButton("添加账号");
        add.setGraphic(SVG.ADD.createIcon(16));
        add.setContentDisplay(ContentDisplay.LEFT);
        add.setGraphicTextGap(6);
        add.getStyleClass().add("mfmc-account-add");
        add.setOnAction(event -> Controllers.dialog(new CreateAccountPane()));

        HBox header = new HBox(10, back, titleBox, spacer, add);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("mfmc-install-header");
        return header;
    }

    /// Builds the empty state shown when no account exists.
    private static StackPane createEmptyState() {
        Label label = new Label("暂无账号\n点击右上角“添加账号”开始。");
        label.getStyleClass().add("mfmc-account-empty");
        label.setTextAlignment(TextAlignment.CENTER);
        label.setWrapText(true);

        StackPane placeholder = new StackPane(label);
        placeholder.setPrefHeight(220);
        return placeholder;
    }

    /// Returns whether the given account is the selected account.
    private static boolean isCurrentAccount(Account account) {
        @Nullable Account selected = Accounts.getSelectedAccount();
        return selected != null && selected.getAccountID().equals(account.getAccountID());
    }

    /// Returns the display name for an account.
    private static String displayName(Account account) {
        String name = account.getProfileName();
        return StringUtils.isBlank(name) ? account.getProfileID().toString() : name;
    }

    /// Returns the localized account type, including the authlib server when present.
    private static String displayType(Account account) {
        AccountFactory<?> factory = Accounts.getAccountFactory(account);
        String type = Accounts.getLocalizedLoginTypeName(factory);
        if (account instanceof AuthlibInjectorAccount authlibAccount) {
            type += " · " + authlibAccount.getServer().getName();
        }
        return type;
    }

    /// Refreshes the account credentials.
    private void refreshAccount(Account account) {
        Controllers.showToast("正在刷新账号...");
        new AccountListItem(account).refreshAsync()
                .whenComplete(Schedulers.javafx(), exception -> {
                    if (exception != null) {
                        Controllers.showToast(Accounts.localizeErrorMessage(exception));
                    } else {
                        Controllers.showToast("账号已刷新");
                        accountList.refresh();
                    }
                })
                .start();
    }

    /// Confirms and removes an account from the MFMC account store.
    private void removeAccount(Account account) {
        Controllers.confirm(
                "确定要删除账号“" + displayName(account) + "”吗？",
                "删除账号",
                () -> {
                    new AccountListItem(account).remove();
                    Controllers.showToast("账号已删除");
                },
                null
        );
    }

    /// One account row in the MFMC account list.
    private final class AccountCell extends ListCell<Account> {
        private final Canvas avatar = new Canvas(40, 40);
        private final StackPane avatarFrame = new StackPane(avatar);
        private final Label name = new Label();
        private final Label type = new Label();
        private final Label current = new Label("当前");
        private final JFXButton refresh = new JFXButton();
        private final JFXButton remove = new JFXButton();
        private final HBox row;

        private AccountCell() {
            avatarFrame.getStyleClass().add("mfmc-account-page-avatar");

            name.getStyleClass().add("mfmc-account-page-name");
            type.getStyleClass().add("mfmc-account-page-type");

            VBox labels = new VBox(2, name, type);
            HBox.setHgrow(labels, Priority.ALWAYS);

            current.getStyleClass().add("mfmc-account-current");

            refresh.setGraphic(SVG.REFRESH.createIcon(16));
            refresh.getStyleClass().add("mfmc-account-action");
            refresh.setFocusTraversable(false);
            FXUtils.installFastTooltip(refresh, "刷新账号");

            remove.setGraphic(SVG.DELETE_FOREVER.createIcon(16));
            remove.getStyleClass().addAll("mfmc-account-action", "danger");
            remove.setFocusTraversable(false);
            FXUtils.installFastTooltip(remove, "删除账号");

            HBox actions = new HBox(4, current, refresh, remove);
            actions.setAlignment(Pos.CENTER_RIGHT);

            row = new HBox(12, avatarFrame, labels, actions);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("mfmc-account-row");
            row.setCursor(Cursor.HAND);

            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        }

        @Override
        protected void updateItem(@Nullable Account account, boolean empty) {
            super.updateItem(account, empty);
            TexturesLoader.unbindAvatar(avatar);

            if (empty || account == null) {
                setGraphic(null);
                return;
            }

            TexturesLoader.bindAvatar(avatar, account);
            name.setText(displayName(account));
            type.setText(displayType(account));

            boolean selected = isCurrentAccount(account);
            current.setVisible(selected);
            current.setManaged(selected);
            row.getStyleClass().remove("selected");
            if (selected) {
                row.getStyleClass().add("selected");
            }

            refresh.setOnAction(event -> refreshAccount(account));
            remove.setOnAction(event -> removeAccount(account));
            setGraphic(row);
        }
    }
}
