package app.dearnotes.ui;

import app.dearnotes.db.SearchRepo.Hit;
import app.dearnotes.model.Page;
import java.time.LocalDate;
import java.util.List;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/** 左にサイドバー、右に開いているページ。 */
public final class MainWindow implements Sidebar.Actions {

    // 別のデータフォルダで試すときは、前回のページも別に覚える
    private static final String LAST_PAGE = System.getProperty("dearnotes.dataDir") == null
        ? "lastPageId" : "lastPageId@" + Integer.toHexString(System.getProperty("dearnotes.dataDir").hashCode());

    private final Services s;
    private final Stage stage;
    private final Preferences prefs = Preferences.userNodeForPackage(MainWindow.class);
    private final Sidebar sidebar;
    private final PageEditor editor;
    private final Label breadcrumb = new Label();
    private final SaveIndicator saveIndicator = new SaveIndicator();
    private SearchDialog searchDialog;
    private TrashDialog trashDialog;

    public MainWindow(Stage stage, Services services) {
        this.stage = stage;
        this.s = services;
        this.sidebar = new Sidebar(services, this);
        this.editor = new PageEditor(services, this::pageChanged);

        breadcrumb.getStyleClass().add("breadcrumb");
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var header = new HBox(8, breadcrumb, spacer, saveIndicator);
        header.getStyleClass().add("page-header");
        header.setAlignment(Pos.CENTER_LEFT);

        // 本文は読みやすい幅で中央に置く
        var column = new StackPane(editor);
        column.getStyleClass().add("page-column");
        StackPane.setAlignment(editor, Pos.TOP_CENTER);
        editor.setMaxWidth(760);
        var scroll = new ScrollPane(column);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("page-scroll");
        editor.setScrollPane(scroll);

        var main = new BorderPane(scroll);
        main.setTop(header);
        var split = new SplitPane(sidebar, main);
        split.setDividerPositions(0.24);
        SplitPane.setResizableWithParent(sidebar, false);
        sidebar.setMinWidth(180);

        var scene = new Scene(split, 1100, 720);
        scene.getStylesheets().add(MainWindow.class.getResource("dearnotes.css").toExternalForm());
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN), this::showSearch);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN), () -> newPage(null));
        scene.getAccelerators().put(
            new KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN), this::openToday);
        stage.setScene(scene);
        stage.setTitle("DearNotes");
        stage.setMinWidth(640);
        stage.setMinHeight(400);

        services.saver().setListener(saveIndicator::show);
        stage.setOnCloseRequest(e -> {
            if (!flushAll()) e.consume();
        });
    }

    /**
     * 閉じる前に、名前と書きかけのブロックを書く。
     * 書けなかったら尋ね、閉じるのをやめるなら false。
     */
    public void flushTitle() {
        editor.saveTitle();
    }

    public boolean flushAll() {
        editor.saveTitle();
        if (s.saver().flushAndWait(5, java.util.concurrent.TimeUnit.SECONDS)) return true;
        var alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION,
            "保存できていない変更があります。いま終了すると、その変更は失われます。",
            javafx.scene.control.ButtonType.CANCEL, javafx.scene.control.ButtonType.OK);
        alert.initOwner(stage);
        alert.setHeaderText("保存できませんでした");
        ((javafx.scene.control.Button) alert.getDialogPane().lookupButton(javafx.scene.control.ButtonType.OK))
            .setText("終了する");
        return alert.showAndWait().filter(b -> b == javafx.scene.control.ButtonType.OK).isPresent();
    }

    PageEditor editor() {
        return editor;
    }

    Sidebar sidebar() {
        return sidebar;
    }

    /** 起動時: 前回のページ、なければ今日の日記を開く。 */
    public void openInitialPage() {
        sidebar.reload(null);
        String last = prefs.get(LAST_PAGE, null);
        Page page = last == null ? null
            : Errors.get("ページを読む", () -> s.pages().get(last).orElse(null))
                .filter(p -> !p.isTrashed()).orElse(null);
        if (page == null) {
            List<Page> alive = Errors.get("ページの一覧を読む", s.pages()::listAlive).orElse(List.of());
            page = alive.stream().filter(p -> !p.isDaily()).findFirst().orElse(null);
        }
        if (page == null) {
            openToday();
        } else {
            open(page);
        }
    }

    // ---- Sidebar.Actions ----

    @Override
    public void open(Page p) {
        open(p, null);
    }

    private void open(Page p, String blockId) {
        editor.open(p, blockId);
        prefs.put(LAST_PAGE, p.id());
        sidebar.select(p.id());
        updateHeader(p);
    }

    @Override
    public void openToday() {
        Errors.get("今日の日記を開く", () -> s.pages().openDaily(LocalDate.now())).ifPresent(p -> {
            sidebar.reload(p.id());
            open(p);
        });
    }

    @Override
    public void newPage(String parentId) {
        Errors.get("ページを作る", () -> s.pages().create(parentId, "")).ifPresent(p -> {
            sidebar.reload(p.id());
            open(p);
            editor.focusTitle();
        });
    }

    @Override
    public void trash(Page p) {
        s.saver().flushAndWait(2, java.util.concurrent.TimeUnit.SECONDS);
        if (!Errors.run("ゴミ箱へ移動", () -> s.pages().trash(p.id(), System.currentTimeMillis()))) return;
        Page current = editor.page();
        sidebar.reload(current == null ? null : current.id());
        boolean currentGone = current != null && Errors.get("ページを読む",
            () -> s.pages().get(current.id()).map(Page::isTrashed).orElse(true)).orElse(true);
        if (currentGone) openInitialPage();
    }

    @Override
    public void showTrash() {
        if (trashDialog == null) trashDialog = new TrashDialog(stage, s, () -> {
            Page current = editor.page();
            sidebar.reload(current == null ? null : current.id());
            boolean gone = current == null || Errors.get("ページを読む",
                () -> s.pages().get(current.id()).map(Page::isTrashed).orElse(true)).orElse(true);
            if (gone) openInitialPage();
        });
        trashDialog.show();
    }

    @Override
    public void showSearch() {
        if (searchDialog == null) searchDialog = new SearchDialog(stage, s, this::openHit);
        searchDialog.show();
    }

    private void openHit(Hit h) {
        Errors.get("ページを開く", () -> s.pages().get(h.pageId()).orElse(null)).ifPresent(p -> open(p, h.blockId()));
    }

    private void pageChanged(Page p) {
        sidebar.reload(p.id());
        editor.pageUpdated(p);
        updateHeader(p);
    }

    private void updateHeader(Page p) {
        breadcrumb.setText(sidebar.pathTo(p).stream()
            .map(x -> x.displayIcon() + " " + x.displayTitle())
            .collect(Collectors.joining("  /  ")));
        stage.setTitle(p.displayTitle() + " — DearNotes");
    }
}
