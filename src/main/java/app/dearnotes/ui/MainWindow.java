package app.dearnotes.ui;

import app.dearnotes.db.SearchRepo.Hit;
import app.dearnotes.export.ExportTree;
import app.dearnotes.export.MarkdownWriter;
import app.dearnotes.model.Page;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

/** 左にサイドバー、右に開いているページ。 */
public final class MainWindow implements Sidebar.Actions {

    // 別のデータフォルダで試すときは、前回のページも別に覚える
    private static final String LAST_PAGE = System.getProperty("dearnotes.dataDir") == null
        ? "lastPageId" : "lastPageId@" + Integer.toHexString(System.getProperty("dearnotes.dataDir").hashCode());
    private static final String EXPORT_DIR = "exportDir";
    private static final List<Integer> ICON_SIZES = List.of(16, 32, 48, 64, 128, 256);

    private final Services s;
    private final Stage stage;
    private final Preferences prefs = Preferences.userNodeForPackage(MainWindow.class);
    private final Sidebar sidebar;
    private final PageEditor editor;
    private final OutlinePanel outline;
    private final Label breadcrumb = new Label();
    private final Button outlineButton = new Button("☰ 目次");
    private final SaveIndicator saveIndicator = new SaveIndicator();
    private SearchDialog searchDialog;
    private TrashDialog trashDialog;

    public MainWindow(Stage stage, Services services) {
        this.stage = stage;
        this.s = services;
        this.sidebar = new Sidebar(services, this);
        this.editor = new PageEditor(services, this::pageChanged);
        this.outline = new OutlinePanel(editor::jumpTo);
        // 目次は初めは閉じておく
        outline.setVisible(false);
        outline.setManaged(false);
        editor.setOutlineListener(this::refreshOutline);

        breadcrumb.getStyleClass().add("breadcrumb");
        outlineButton.getStyleClass().add("outline-button");
        outlineButton.setFocusTraversable(false);
        outlineButton.setOnAction(e -> toggleOutline());
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var header = new HBox(8, breadcrumb, spacer, saveIndicator, outlineButton);
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
        main.setRight(outline);
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
        scene.getAccelerators().put(
            new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN), this::toggleOutline);
        scene.getAccelerators().put(
            new KeyCodeCombination(KeyCode.E, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN), () -> {
                Page current = editor.page();
                if (current != null) exportPage(current);
            });
        stage.setScene(scene);
        stage.setTitle("DearNotes");
        stage.getIcons().setAll(icons());
        stage.setMinWidth(640);
        stage.setMinHeight(400);

        services.saver().setListener(saveIndicator::show);
        stage.setOnCloseRequest(e -> {
            if (!flushAll()) e.consume();
        });
    }

    /** ウィンドウのアイコン（Windows のタイトルバーとタスクバー）。packaging/icon/build-icons.py が作る。 */
    static List<Image> icons() {
        return ICON_SIZES.stream()
            .map(px -> MainWindow.class.getResource("/app/dearnotes/icon-" + px + ".png"))
            .filter(java.util.Objects::nonNull)
            .map(url -> new Image(url.toExternalForm()))
            .toList();
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

    OutlinePanel outline() {
        return outline;
    }

    /** 本文の右の目次を出し入れする。 */
    void toggleOutline() {
        boolean show = !outline.isVisible();
        outline.setVisible(show);
        outline.setManaged(show);
        outlineButton.getStyleClass().remove("active");
        if (show) outlineButton.getStyleClass().add("active");
        refreshOutline();
    }

    private void refreshOutline() {
        if (outline.isVisible()) outline.show(editor.headings(), editor.currentHeadingId());
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

    /** 開けなかったら（書きかけを保存できないとき）、サイドバーの選択を今のページに戻して false。 */
    private boolean open(Page p, String blockId) {
        if (!editor.open(p, blockId)) {
            Page current = editor.page();
            sidebar.select(current == null ? null : current.id());
            return false;
        }
        prefs.put(LAST_PAGE, p.id());
        sidebar.select(p.id());
        updateHeader(p);
        return true;
    }

    @Override
    public void openToday() {
        // 移れないときに日記を作ったりゴミ箱から戻したりしないよう、先に保存する
        if (!editor.flushBeforeLeaving()) return;
        Errors.get("今日の日記を開く", () -> s.pages().openDaily(LocalDate.now())).ifPresent(p -> {
            sidebar.reload(p.id());
            open(p);
        });
    }

    @Override
    public void newPage(String parentId) {
        if (!editor.flushBeforeLeaving()) return;
        Errors.get("ページを作る", () -> s.pages().create(parentId, "")).ifPresent(p -> {
            sidebar.reload(p.id());
            if (open(p, null)) editor.focusTitle();
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
    public void exportPage(Page p) {
        Page page = beforeExport(p);
        if (page == null) return;
        var chooser = new FileChooser();
        chooser.setTitle("Markdown で書き出す");
        chooser.setInitialFileName(ExportTree.fileName(page) + ".md");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Markdown", "*.md"));
        lastExportDir().ifPresent(chooser::setInitialDirectory);
        // 同じ名前のファイルがあれば、上書きするかは OS の窓が尋ねる
        File file = chooser.showSaveDialog(stage);
        if (file == null) return;
        prefs.put(EXPORT_DIR, file.getParent());
        Errors.get("ページを読む", () -> s.blocks().listByPage(page.id())).ifPresent(blocks -> {
            try {
                ExportTree.writeReplacing(file.toPath(), MarkdownWriter.write(page, blocks));
            } catch (IOException e) {
                Errors.show("書き出し", e);
            }
        });
    }

    @Override
    public void exportTree(Page p) {
        Page page = beforeExport(p);
        if (page == null) return;
        var chooser = new DirectoryChooser();
        chooser.setTitle("子ページごと書き出す先のフォルダ");
        lastExportDir().ifPresent(chooser::setInitialDirectory);
        File dir = chooser.showDialog(stage);
        if (dir == null) return;
        prefs.put(EXPORT_DIR, dir.getPath());
        List<Page> alive = Errors.get("ページの一覧を読む", s.pages()::listAlive).orElse(null);
        if (alive == null) return;
        try {
            List<Path> written = ExportTree.write(dir.toPath(), page, alive, s.blocks()::listByPage);
            var done = new Alert(Alert.AlertType.INFORMATION);
            done.initOwner(stage);
            done.setTitle("DearNotes");
            done.setHeaderText(written.size() + " ページを書き出しました");
            done.setContentText(written.getFirst().getParent().toString());
            done.show();
        } catch (ExportTree.ExportException e) {
            // 書けたファイルは残すので、どこに何ページ書けたかも知らせる
            String message = e.written().isEmpty() ? e.getMessage()
                : e.written().size() + " ページは " + e.written().getFirst().getParent() + " に書けています。\n"
                    + e.getMessage();
            Errors.show("書き出し", new IOException(message, e));
        }
    }

    /** 書きかけを保存し、名前を DB から読み直す。保存できなければ書き出さずに null。 */
    private Page beforeExport(Page p) {
        // 名前を変えられなかったら、古い名前で書き出さない（エラーの窓は editor が出す）
        if (!editor.saveTitle()) return null;
        if (!s.saver().flushAndWait(3, TimeUnit.SECONDS)) {
            Errors.warn("書き出せませんでした",
                "まだ保存できていない変更があります。少し待ってからもう一度試してください。");
            return null;
        }
        return Errors.get("ページを読む", () -> s.pages().get(p.id()).orElse(null)).orElse(null);
    }

    /** 前に書き出したフォルダ。消えていれば空。 */
    private Optional<File> lastExportDir() {
        return Optional.ofNullable(prefs.get(EXPORT_DIR, null)).map(File::new).filter(File::isDirectory);
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
