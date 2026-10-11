package app.dearnotes.ui;

import app.dearnotes.editor.BlockOps;
import app.dearnotes.editor.Marks;
import app.dearnotes.editor.PasteParser;
import app.dearnotes.editor.SlashCommands.Command;
import app.dearnotes.model.Block;
import app.dearnotes.model.BlockType;
import app.dearnotes.model.Mark;
import app.dearnotes.model.Page;
import app.dearnotes.model.SortKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** 1ページ分の編集画面。タイトルとブロックの縦の並び。 */
final class PageEditor extends VBox implements BlockView.Host {

    private final Services s;
    private final Consumer<Page> onPageChanged;
    private final Button iconButton = new Button();
    private final TextField title = new TextField();
    private final VBox blockBox = new VBox();
    private final List<BlockView> views = new ArrayList<>();
    private final SlashMenu slash = new SlashMenu(this::applySlash);
    private final PauseTransition renameLater = new PauseTransition(Duration.millis(300));
    private ScrollPane scrollPane;
    private Page page;
    private BlockView dragging;
    private boolean loadingTitle;

    PageEditor(Services services, Consumer<Page> onPageChanged) {
        this.s = services;
        this.onPageChanged = onPageChanged;
        getStyleClass().add("page-editor");

        iconButton.getStyleClass().add("page-icon");
        iconButton.setFocusTraversable(false);
        iconButton.setOnAction(e -> chooseIcon());

        title.getStyleClass().add("page-title");
        title.setPromptText("無題");
        title.textProperty().addListener((obs, old, now) -> {
            if (!loadingTitle) renameLater.playFromStart();
        });
        renameLater.setOnFinished(e -> saveTitle());
        title.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.DOWN) {
                saveTitle();
                if (!views.isEmpty()) views.getFirst().focusAt(0);
                e.consume();
            }
        });

        blockBox.getStyleClass().add("block-list");

        // 最後のブロックより下を押したら、末尾で書き始められるようにする
        var filler = new Region();
        filler.getStyleClass().add("page-filler");
        filler.setMinHeight(160);
        filler.setOnMouseClicked(e -> clickBelowLastBlock());

        getChildren().addAll(new HBox(8, iconButton), title, blockBox, filler);
    }

    void setScrollPane(ScrollPane scrollPane) {
        this.scrollPane = scrollPane;
    }

    Page page() {
        return page;
    }

    List<BlockView> views() {
        return List.copyOf(views);
    }

    /** ページを開く。開く前に書きかけの変更を DB に入れておく。 */
    void open(Page p, String focusBlockId) {
        saveTitle();
        slash.hide();
        s.saver().flushAndWait(3, TimeUnit.SECONDS);
        // 開く前に書いた名前を反映させるため、読み直す
        String id = p.id();
        this.page = Errors.get("ページを読む", () -> s.pages().get(id).orElse(null)).orElse(p);
        loadingTitle = true;
        title.setText(page.title());
        loadingTitle = false;
        iconButton.setText(page.displayIcon());

        var loaded = Errors.get("ページを開く", () -> s.blocks().listByPage(page.id()));
        views.clear();
        blockBox.getChildren().clear();
        if (loaded.isEmpty()) return;   // 読めなかったときは空の行を足さない（中身を上書きしないように）
        for (Block b : loaded.get()) addView(views.size(), b);
        if (views.isEmpty()) {
            Block first = Block.empty(page.id(), BlockType.PARAGRAPH, SortKeys.GAP);
            addView(0, first);
            s.saver().changed(first);
        }
        refreshNumbers();
        if (scrollPane != null) scrollPane.setVvalue(0);

        Page opened = page;
        Platform.runLater(() -> {
            if (focusBlockId != null) {
                views.stream().filter(v -> v.block().id().equals(focusBlockId)).findFirst()
                    .ifPresent(v -> {
                        v.focusEnd();
                        ensureVisible(v);
                    });
            } else if (opened.title().isBlank() && !opened.isDaily()) {
                title.requestFocus();
            } else {
                views.getFirst().focusEnd();
            }
        });
    }

    /** サイドバーで名前やアイコンが変わったとき。 */
    void pageUpdated(Page p) {
        if (page == null || !page.id().equals(p.id())) return;
        page = p;
        iconButton.setText(p.displayIcon());
        if (!title.isFocused()) {
            loadingTitle = true;
            title.setText(p.title());
            loadingTitle = false;
        }
    }

    void focusTitle() {
        title.requestFocus();
        title.selectAll();
    }

    // ---- BlockView.Host ----

    @Override
    public void edited(BlockView v) {
        s.saver().changed(v.block());
        if (v.block().type() == BlockType.NUMBERED) refreshNumbers();
    }

    @Override
    public void enter(BlockView v) {
        int idx = views.indexOf(v);
        Block b = v.block();
        String text = v.text();
        int caret = v.caret();

        if (text.isEmpty() && b.type().isList()) {
            // 空のリスト項目で Enter: 字下げを戻す。一番左なら普通のテキストにする
            if (b.indent() > 0) indent(v, -1);
            else change(v, b.withType(BlockType.PARAGRAPH));
            return;
        }
        if (caret == 0 && !text.isEmpty()) {
            // 行頭で Enter: 上に空の行を入れ、カーソルはそのまま
            Block above = Block.empty(page.id(), b.type().next(), 0).withIndent(b.indent());
            insertBlock(idx, above);
            v.focusAt(0);
            return;
        }
        List<Mark> marks = b.marks();
        String left = text.substring(0, caret);
        String right = text.substring(caret);
        v.setContent(left, Marks.slice(marks, 0, caret));
        s.saver().changed(v.block());

        Block next = Block.empty(page.id(), b.type() == BlockType.DIVIDER ? BlockType.PARAGRAPH : b.type().next(), 0)
            .withIndent(b.indent())
            .withContent(right, Marks.slice(marks, caret, text.length()));
        BlockView nv = insertBlock(idx + 1, next);
        nv.focusAt(0);
    }

    @Override
    public void backspaceAtStart(BlockView v) {
        int idx = views.indexOf(v);
        Block b = v.block();
        if (b.type() == BlockType.DIVIDER) {
            remove(v);
            focusNear(idx - 1, true);
            return;
        }
        if (b.type() != BlockType.PARAGRAPH) {
            change(v, b.withType(BlockType.PARAGRAPH));
            return;
        }
        if (b.indent() > 0) {
            indent(v, -1);
            return;
        }
        if (idx == 0) {
            if (v.text().isEmpty() && views.size() > 1) {
                remove(v);
                views.getFirst().focusAt(0);
            }
            return;
        }
        BlockView prev = views.get(idx - 1);
        if (prev.block().type() == BlockType.DIVIDER) {
            remove(prev);
            return;
        }
        mergeInto(prev, v);
    }

    @Override
    public void deleteAtEnd(BlockView v) {
        int idx = views.indexOf(v);
        if (v.block().type() == BlockType.DIVIDER) {
            // 区切り線に他の行の文字を入れない。区切り線そのものを消す
            remove(v);
            focusNear(idx, false);
            return;
        }
        if (idx + 1 >= views.size()) return;
        BlockView next = views.get(idx + 1);
        if (next.block().type() == BlockType.DIVIDER) {
            remove(next);
            return;
        }
        int caret = v.text().length();
        mergeInto(v, next);
        v.focusAt(caret);
    }

    @Override
    public void indent(BlockView v, int delta) {
        Block b = v.block();
        int idx = views.indexOf(v);
        // 1つ上より2段以上深くはしない
        int max = idx == 0 ? 0 : views.get(idx - 1).block().indent() + 1;
        int next = Math.clamp(b.indent() + delta, 0, Math.min(max, Block.MAX_INDENT));
        if (next == b.indent()) return;
        change(v, b.withIndent(next));
    }

    @Override
    public void focusPrevious(BlockView v) {
        int idx = views.indexOf(v);
        if (idx > 0) {
            views.get(idx - 1).focusEnd();
            ensureVisible(views.get(idx - 1));
        } else {
            title.requestFocus();
            title.end();
        }
    }

    @Override
    public void focusNext(BlockView v) {
        int idx = views.indexOf(v);
        if (idx + 1 < views.size()) {
            views.get(idx + 1).focusAt(0);
            ensureVisible(views.get(idx + 1));
        }
    }

    @Override
    public void moveBy(BlockView v, int delta) {
        int from = views.indexOf(v);
        int to = from + delta;
        if (to < 0 || to >= views.size()) return;
        int caret = v.caret();
        move(v, to);
        v.focusAt(caret);
        ensureVisible(v);
    }

    @Override
    public void convert(BlockView v, BlockType type, int prefixLength) {
        Block b = v.block();
        String text = v.text();
        int cut = Math.min(prefixLength, text.length());
        String rest = text.substring(cut);
        List<Mark> marks = type == BlockType.CODE ? List.of() : Marks.slice(b.marks(), cut, text.length());
        if (type == BlockType.DIVIDER) {
            change(v, b.withType(type).withContent("", List.of()));
            int idx = views.indexOf(v);
            Block next = Block.empty(page.id(), BlockType.PARAGRAPH, 0).withIndent(b.indent())
                .withContent(rest, marks);
            insertBlock(idx + 1, next).focusAt(0);
            return;
        }
        change(v, b.withType(type).withContent(rest, marks));
        v.focusAt(0);
    }

    @Override
    public void slashQuery(BlockView v, String query) {
        slash.update(v, query);
    }

    @Override
    public boolean slashKey(BlockView v, KeyEvent e) {
        return slash.handleKey(v, e);
    }

    @Override
    public void pasteLines(BlockView v, String text) {
        List<PasteParser.Line> lines = PasteParser.parse(text);
        if (lines.isEmpty()) return;
        int baseIndent = v.block().indent();
        BlockView last = v;
        int from = 0;
        String tail = "";
        List<Mark> tailMarks = List.of();
        if (v.block().type() == BlockType.DIVIDER) {
            // 区切り線には文字を入れず、すべて下に新しい行として足す
            from = 0;
        } else if (v.text().isEmpty() && v.block().type() == BlockType.PARAGRAPH) {
            // 空の行に貼ったら、1行目もその行の種類ごと置き換える
            change(v, toBlock(v.block(), lines.getFirst(), baseIndent));
            from = 1;
        } else {
            // 1行目はカーソル位置に入れ、カーソルより後ろは最後の行の後ろへ回す
            v.area().replaceSelection(lines.getFirst().text());
            int caret = v.caret();
            tail = v.text().substring(caret);
            tailMarks = Marks.slice(v.block().marks(), caret, v.text().length());
            if (!tail.isEmpty()) v.area().deleteText(caret, v.text().length());
            from = 1;
        }
        for (int i = from; i < lines.size(); i++) {
            Block b = toBlock(Block.empty(page.id(), BlockType.PARAGRAPH, 0), lines.get(i), baseIndent);
            last = insertBlock(views.indexOf(last) + 1, b);
        }
        int end = last.text().length();
        if (!tail.isEmpty()) {
            last.setContent(last.text() + tail, Marks.concat(last.block().marks(), end, tailMarks));
            s.saver().changed(last.block());
        }
        last.focusAt(end);
        ensureVisible(last);
    }

    private static Block toBlock(Block base, PasteParser.Line line, int baseIndent) {
        return base.withType(line.type())
            .withContent(line.text(), List.of())
            .withChecked(line.checked())
            .withIndent(baseIndent + line.indent());
    }

    @Override
    public void scroll(double deltaY) {
        if (scrollPane == null || scrollPane.getContent() == null) return;
        double content = scrollPane.getContent().getBoundsInLocal().getHeight();
        double viewport = scrollPane.getViewportBounds().getHeight();
        if (content <= viewport) return;
        double v = scrollPane.getVvalue() - deltaY / (content - viewport);
        scrollPane.setVvalue(Math.clamp(v, 0, 1));
    }

    @Override
    public void focused(BlockView v) {
        if (slash.isShowing() && slash.target() != v) slash.hide();
    }

    @Override
    public void openLink(String url) {
        s.openLink().accept(url);
    }

    @Override
    public void dragStarted(BlockView v, MouseEvent e) {
        dragging = v;
        var db = v.startDragAndDrop(TransferMode.MOVE);
        var content = new ClipboardContent();
        content.putString(v.block().text());
        db.setContent(content);
        db.setDragView(v.snapshot(null, null), e.getX(), e.getY());
        e.consume();
    }

    // ---- 並べ替えと変更 ----

    private void applySlash(BlockView v, Command c) {
        // 「/語句」を消してから種類を変える
        convert(v, c.type(), v.text().length());
    }

    private void change(BlockView v, Block b) {
        v.setBlock(b);
        s.saver().changed(v.block());
        refreshNumbers();
    }

    private void mergeInto(BlockView target, BlockView source) {
        String a = target.text();
        String b = source.text();
        List<Mark> marks = Marks.concat(target.block().marks(), a.length(), source.block().marks());
        target.setContent(a + b, marks);
        s.saver().changed(target.block());
        remove(source);
        target.focusAt(a.length());
    }

    private BlockView insertBlock(int index, Block b) {
        Double key = BlockOps.keyAfter(blocks(), index - 1);
        if (key == null) {
            respace();
            key = BlockOps.keyAfter(blocks(), index - 1);
        }
        Block placed = b.withSortKey(key);
        BlockView v = addView(index, placed);
        s.saver().changed(placed);
        refreshNumbers();
        Platform.runLater(() -> ensureVisible(v));
        return v;
    }

    private void remove(BlockView v) {
        views.remove(v);
        blockBox.getChildren().remove(v);
        s.saver().deleted(v.block().id());
        if (views.isEmpty()) {
            Block first = Block.empty(page.id(), BlockType.PARAGRAPH, SortKeys.GAP);
            addView(0, first).focusAt(0);
            s.saver().changed(first);
        }
        refreshNumbers();
    }

    /** v を insertAt の位置に移す。書き換わるのは v の sort_key だけ。 */
    private void move(BlockView v, int insertAt) {
        int from = views.indexOf(v);
        Double key = BlockOps.keyForMove(blocks(), from, insertAt);
        if (key == null) {
            respace();
            key = BlockOps.keyForMove(blocks(), from, insertAt);
        }
        views.remove(from);
        blockBox.getChildren().remove(v);
        int at = Math.clamp(insertAt, 0, views.size());
        views.add(at, v);
        blockBox.getChildren().add(at, v);
        v.setBlock(v.block().withSortKey(key));
        s.saver().changed(v.block());
        refreshNumbers();
    }

    /** 間が詰まったら、ページの全ブロックを GAP 間隔で振り直す。 */
    private void respace() {
        List<Double> keys = SortKeys.spaced(views.size());
        for (int i = 0; i < views.size(); i++) {
            BlockView v = views.get(i);
            v.setBlock(v.block().withSortKey(keys.get(i)));
            s.saver().changed(v.block());
        }
    }

    private BlockView addView(int index, Block b) {
        var v = new BlockView(b, this);
        v.setOnDragOver(e -> dragOver(v, e));
        v.setOnDragExited(e -> v.setDropHint(null));
        v.setOnDragDropped(e -> dropOn(v, e));
        v.setOnDragDone(e -> dragging = null);
        views.add(index, v);
        blockBox.getChildren().add(index, v);
        return v;
    }

    private void dragOver(BlockView v, DragEvent e) {
        if (dragging == null || dragging == v) return;
        e.acceptTransferModes(TransferMode.MOVE);
        v.setDropHint(e.getY() < v.getHeight() / 2 ? "drop-above" : "drop-below");
        e.consume();
    }

    private void dropOn(BlockView v, DragEvent e) {
        if (dragging == null || dragging == v) return;
        boolean above = e.getY() < v.getHeight() / 2;
        v.setDropHint(null);
        int from = views.indexOf(dragging);
        int target = views.indexOf(v) + (above ? 0 : 1);
        if (target > from) target--;   // 自分を抜いた後の位置
        move(dragging, target);
        e.setDropCompleted(true);
        e.consume();
    }

    private void refreshNumbers() {
        List<Block> blocks = blocks();
        for (int i = 0; i < views.size(); i++) {
            if (blocks.get(i).type() == BlockType.NUMBERED) views.get(i).setListNumber(BlockOps.listNumber(blocks, i));
        }
    }

    private List<Block> blocks() {
        return views.stream().map(BlockView::block).toList();
    }

    private void focusNear(int idx, boolean atEnd) {
        if (views.isEmpty()) return;
        BlockView v = views.get(Math.clamp(idx, 0, views.size() - 1));
        if (atEnd) v.focusEnd();
        else v.focusAt(0);
    }

    private void clickBelowLastBlock() {
        if (page == null || views.isEmpty()) return;
        BlockView last = views.getLast();
        if (last.block().type() == BlockType.PARAGRAPH && last.text().isEmpty()) {
            last.focusAt(0);
        } else {
            insertBlock(views.size(), Block.empty(page.id(), BlockType.PARAGRAPH, 0)).focusAt(0);
        }
    }

    private void ensureVisible(Node node) {
        if (scrollPane == null || scrollPane.getContent() == null) return;
        Node content = scrollPane.getContent();
        Bounds b = content.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
        double contentHeight = content.getBoundsInLocal().getHeight();
        double viewport = scrollPane.getViewportBounds().getHeight();
        if (contentHeight <= viewport) return;
        double top = scrollPane.getVvalue() * (contentHeight - viewport);
        double y;
        if (b.getMinY() < top) y = b.getMinY() - 24;
        else if (b.getMaxY() > top + viewport) y = b.getMaxY() - viewport + 24;
        else return;
        scrollPane.setVvalue(Math.clamp(y / (contentHeight - viewport), 0, 1));
    }

    void saveTitle() {
        renameLater.stop();
        if (page == null || title.getText().equals(page.title())) return;
        String t = title.getText();
        if (Errors.run("名前を変更", () -> s.pages().rename(page.id(), t))) {
            Errors.get("ページを読む", () -> s.pages().get(page.id()).orElse(null)).ifPresent(p -> {
                page = p;
                onPageChanged.accept(p);
            });
        }
    }

    private void chooseIcon() {
        if (page == null) return;
        var dialog = new TextInputDialog(page.icon() == null ? "" : page.icon());
        dialog.setTitle("アイコン");
        dialog.setHeaderText("絵文字を1つ入れてください（空にすると元に戻ります）");
        dialog.initOwner(getScene().getWindow());
        dialog.showAndWait().ifPresent(icon -> {
            String value = icon.isBlank() ? null : icon.strip().codePoints().limit(2)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
            if (Errors.run("アイコンを変更", () -> s.pages().setIcon(page.id(), value))) {
                Errors.get("ページを読む", () -> s.pages().get(page.id()).orElse(null)).ifPresent(p -> {
                    pageUpdated(p);
                    onPageChanged.accept(p);
                });
            }
        });
    }
}
