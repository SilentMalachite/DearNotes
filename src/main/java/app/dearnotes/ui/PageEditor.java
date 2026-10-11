package app.dearnotes.ui;

import app.dearnotes.editor.BlockOps;
import app.dearnotes.editor.Marks;
import app.dearnotes.editor.Outline;
import app.dearnotes.editor.PageLinks;
import app.dearnotes.editor.PasteParser;
import app.dearnotes.editor.SlashCommands.Command;
import app.dearnotes.model.Block;
import app.dearnotes.model.BlockType;
import app.dearnotes.model.Mark;
import app.dearnotes.model.Page;
import app.dearnotes.model.SortKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
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

    /** PageEditor から MainWindow に頼むこと。 */
    interface Navigator {
        /** 名前やアイコンが変わった。 */
        void pageChanged(Page p);

        /** リンク先のページへ移る。blockId があれば、その行へ。 */
        void openPage(String pageId, String blockId);

        /** parentId の子ページを作る。開かない。作れなければ空。 */
        Optional<Page> newLinkedPage(String parentId, String title);
    }

    private final Services s;
    private final Navigator nav;
    private final Button iconButton = new Button();
    private final TextField title = new TextField();
    private final VBox blockBox = new VBox();
    private final List<BlockView> views = new ArrayList<>();
    private final SlashMenu slash = new SlashMenu(this::applySlash);
    private final PageLinkMenu links = new PageLinkMenu(this::applyPageLink);
    private final Backlinks backlinks;
    private final PauseTransition renameLater = new PauseTransition(Duration.millis(300));
    private ScrollPane scrollPane;
    private Page page;
    private BlockView dragging;
    private BlockView lastFocused;
    private Runnable outlineListener = () -> {};
    private boolean loadingTitle;

    PageEditor(Services services, Navigator nav) {
        this.s = services;
        this.nav = nav;
        this.backlinks = new Backlinks(b -> nav.openPage(b.pageId(), b.blockId()));
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

        getChildren().addAll(new HBox(8, iconButton), title, blockBox, backlinks, filler);
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

    Backlinks backlinks() {
        return backlinks;
    }

    /** 見出しや並び、カーソルのある節が変わったときに呼ぶ。目次を作り直すため。 */
    void setOutlineListener(Runnable listener) {
        outlineListener = listener;
    }

    List<Outline.Heading> headings() {
        return Outline.headings(blocks());
    }

    /** カーソルのある行が入っている節の見出し。なければ null。 */
    String currentHeadingId() {
        int idx = views.indexOf(lastFocused);
        if (idx < 0) return null;
        List<Block> blocks = blocks();
        int h = Outline.currentHeading(blocks, idx);
        return h < 0 ? null : blocks.get(h).id();
    }

    /** 目次から見出しへ移る。畳んだ節に入っていれば、先に開く。 */
    void jumpTo(String blockId) {
        views.stream().filter(v -> v.block().id().equals(blockId)).findFirst().ifPresent(v -> {
            lastFocused = v;
            reveal(v);
            v.focusEnd();
            outlineListener.run();
            Platform.runLater(() -> scrollToTop(v));
        });
    }

    /**
     * ほかのページへ移る前に、名前と書きかけの変更を DB に入れる。
     * 書けなかったら知らせて false。そのときはページを作ったり戻したりもしない。
     */
    boolean flushBeforeLeaving() {
        saveTitle();
        slash.hide();
        links.hide();
        if (s.saver().flushAndWait(3, TimeUnit.SECONDS) || page == null) return true;
        Errors.warn("保存できていない変更があります",
            "いまのページの変更をまだ保存できていないので、ページを切り替えませんでした。少し待ってからもう一度開いてください。");
        return false;
    }

    /**
     * ページを開く。開く前に書きかけの変更を DB に入れておく。
     * 書けなかったり、開くページを読めなかったりしたら、今のページのままにして false。
     */
    boolean open(Page p, String focusBlockId) {
        if (!flushBeforeLeaving()) return false;
        // 読めなかったときは、今のページを消さずに残す（空の行で中身を上書きしないように）
        var loaded = Errors.get("ページを開く", () -> s.blocks().listByPage(p.id()));
        if (loaded.isEmpty()) return false;
        // 開く前に書いた名前を反映させるため、読み直す
        String id = p.id();
        this.page = Errors.get("ページを読む", () -> s.pages().get(id).orElse(null)).orElse(p);
        loadingTitle = true;
        title.setText(page.title());
        loadingTitle = false;
        iconButton.setText(page.displayIcon());

        views.clear();
        blockBox.getChildren().clear();
        lastFocused = null;
        for (Block b : loaded.get()) addView(views.size(), b);
        if (views.isEmpty()) {
            Block first = Block.empty(page.id(), BlockType.PARAGRAPH, SortKeys.GAP);
            addView(0, first);
            s.saver().changed(first);
        }
        refreshLayout(false);
        String pageId = page.id();
        backlinks.show(Errors.get("このページへのリンクを読む", () -> s.blocks().linksTo(pageId)).orElse(List.of()));
        if (scrollPane != null) scrollPane.setVvalue(0);

        Page opened = page;
        Platform.runLater(() -> {
            if (focusBlockId != null) {
                views.stream().filter(v -> v.block().id().equals(focusBlockId)).findFirst()
                    .ifPresent(v -> {
                        reveal(v);
                        v.focusEnd();
                        Platform.runLater(() -> ensureVisible(v));
                    });
            } else if (opened.title().isBlank() && !opened.isDaily()) {
                title.requestFocus();
            } else {
                views.getFirst().focusEnd();
            }
        });
        return true;
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
        if (Outline.level(v.block().type()) > 0) outlineListener.run();
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

        BlockType type = b.type() == BlockType.DIVIDER ? BlockType.PARAGRAPH : b.type().next();
        int at = idx + 1;
        if (b.collapsed()) {
            // 畳んだ行で分けたら、隠れた行の後に入れる。
            // 見出しの後に段落を入れるとその節に入って隠れるので、同じ段の見出しにする
            at = Outline.end(blocks(), idx);
            if (Outline.level(b.type()) > 0) type = b.type();
        }
        Block next = Block.empty(page.id(), type, 0)
            .withIndent(b.indent())
            .withContent(right, Marks.slice(marks, caret, text.length()));
        BlockView nv = insertBlock(at, next);
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
            if (v.text().isEmpty() && wouldBeHidden(idx, b.withType(BlockType.PARAGRAPH))) {
                // 畳んだ節の後の空の見出しは、段落にすると節に入って開いてしまう。そのまま消す
                // （後ろに本文があれば、それを隠さないよう refreshLayout が節を開く）
                remove(v);
                focusNear(idx - 1, true);
                return;
            }
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
        // 前の行が畳んだ節に隠れていたら、先に開いてから結合する
        reveal(prev);
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
        // 次の行が隠れていたら（この行を畳んでいたら）、先に開いてから結合する
        reveal(next);
        if (next.block().type() == BlockType.DIVIDER) {
            remove(next);
            return;
        }
        int caret = v.text().length();
        mergeInto(v, next);
        v.focusAt(caret);
    }

    public void indent(BlockView v, int delta) {
        Block b = v.block();
        int idx = views.indexOf(v);
        // 1つ上より2段以上深くはしない
        int max = idx == 0 ? 0 : views.get(idx - 1).block().indent() + 1;
        // 畳んだ行は隠れた行も一緒に字下げを変える。見出しの節は字下げで決まらないので自分だけ
        boolean withChildren = b.collapsed() && Outline.level(b.type()) == 0;
        int end = withChildren ? Outline.end(blocks(), idx) : idx + 1;
        int deepest = b.indent();
        for (int i = idx + 1; i < end; i++) deepest = Math.max(deepest, views.get(i).block().indent());
        int next = Math.clamp(b.indent() + delta, 0, Math.min(max, Block.MAX_INDENT - (deepest - b.indent())));
        if (next == b.indent()) return;
        int d = next - b.indent();
        for (int i = idx + 1; i < end; i++) {
            BlockView c = views.get(i);
            c.setBlock(c.block().withIndent(c.block().indent() + d));
            s.saver().changed(c.block());
        }
        change(v, b.withIndent(next));
    }

    public void focusPrevious(BlockView v) {
        int idx = visibleBefore(views.indexOf(v));
        if (idx >= 0) {
            views.get(idx).focusEnd();
            ensureVisible(views.get(idx));
        } else {
            title.requestFocus();
            title.end();
        }
    }

    public void focusNext(BlockView v) {
        int idx = visibleAfter(views.indexOf(v));
        if (idx >= 0) {
            views.get(idx).focusAt(0);
            ensureVisible(views.get(idx));
        }
    }

    public void moveBy(BlockView v, int delta) {
        int from = views.indexOf(v);
        int count = groupSize(from);
        // 隣の畳んだ行は、隠れた行ごと飛び越える
        int insertAt;
        if (delta < 0) {
            insertAt = visibleBefore(from);
            if (insertAt < 0) return;
        } else {
            int below = from + count;
            if (below >= views.size()) return;
            int end = below + groupSize(below);
            while (end < views.size() && views.get(end).hidden()) end++;
            insertAt = end - count;
        }
        int caret = v.caret();
        move(v, insertAt);
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
        return links.handleKey(v, e) || slash.handleKey(v, e);
    }

    @Override
    public void linkQuery(BlockView v) {
        // 変更の知らせのあとでカーソルが動くことがあるので、次の回で読む
        Platform.runLater(() -> {
            if (!views.contains(v) || v.block().type() == BlockType.CODE) {
                links.close(v);
                return;
            }
            PageLinks.queryAt(v.text(), v.caret()).ifPresentOrElse(q -> {
                String name = q.text().strip();
                var items = new ArrayList<PageLinkMenu.Choice>();
                Errors.get("ページを探す", () -> s.pages().findByTitle(name, 8)).ifPresent(found -> {
                    for (Page p : found) items.add(new PageLinkMenu.Choice(p, p.displayTitle()));
                });
                boolean exact = items.stream().anyMatch(c -> c.page().title().equals(name));
                if (!name.isEmpty() && !exact) items.add(new PageLinkMenu.Choice(null, name));
                links.show(v, q.from(), items);
            }, () -> links.close(v));
        });
    }

    @Override
    public void blurred(BlockView v) {
        slash.update(v, null);
        links.close(v);
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
        if (!tail.isEmpty() && last.block().type() == BlockType.DIVIDER) {
            // 区切り線の本文は見えないので、カーソルより後ろは下の新しい行に入れる
            Block rest = Block.empty(page.id(), BlockType.PARAGRAPH, 0).withIndent(baseIndent)
                .withContent(tail, tailMarks);
            last = insertBlock(views.indexOf(last) + 1, rest);
            end = 0;
        } else if (!tail.isEmpty()) {
            List<Mark> marks = last.block().type() == BlockType.CODE
                ? List.of() : Marks.concat(last.block().marks(), end, tailMarks);
            last.setContent(last.text() + tail, marks);
            // setContent は編集の知らせを出さないので、保存と目次の更新をここでする
            edited(last);
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

    public void focused(BlockView v) {
        if (slash.isShowing() && slash.target() != v) slash.hide();
        lastFocused = v;
        outlineListener.run();
    }

    @Override
    public void openLink(String url) {
        s.openLink().accept(url);
    }

    @Override
    public void openPage(String pageId) {
        nav.openPage(pageId, null);
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

    @Override
    public void toggleFold(BlockView v) {
        int idx = views.indexOf(v);
        if (idx < 0 || !Outline.hasChildren(blocks(), idx)) return;
        boolean collapse = !v.block().collapsed();
        int end = Outline.end(blocks(), idx);
        int focused = views.indexOf(lastFocused);
        boolean focusInside = collapse && focused > idx && focused < end
            && lastFocused.area().isFocused();
        setCollapsed(v, collapse);
        refreshLayout(false);
        // 隠れる行にカーソルがあったら、畳んだ行へ移す
        if (focusInside) v.focusEnd();
    }

    // ---- 並べ替えと変更 ----

    private void applySlash(BlockView v, Command c) {
        // 「/語句」を消してから種類を変える
        convert(v, c.type(), v.text().length());
    }

    /** 「[[語句」を、選んだページへのリンクに置き換える。新しいページの行なら、先に子ページを作る。 */
    private void applyPageLink(BlockView v, int from, PageLinkMenu.Choice c) {
        Page target = c.page();
        if (target == null) {
            target = nav.newLinkedPage(page.id(), c.name()).orElse(null);
            if (target == null) return;
        }
        int caret = v.caret();
        if (from < 0 || from > caret) return;
        v.insertPageLink(from, caret, target.displayTitle().replace('\n', ' '), target.id());
    }

    private void change(BlockView v, Block b) {
        // 見出しの段が変わると範囲の決め方も変わるので、畳んだ印を外す
        if (b.collapsed() && Outline.level(b.type()) != Outline.level(v.block().type())) b = b.withCollapsed(false);
        v.setBlock(b);
        s.saver().changed(v.block());
        refreshLayout();
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
        refreshLayout();   // 畳んだ節の中に入ったら、新しい行が見えるように開く
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
        refreshLayout();
    }

    /**
     * v を insertAt の位置に移す。insertAt は動かす行を抜いた後の並びでの位置。
     * 畳んだ行は隠れた行と一緒に動く。書き換わるのは動かした行の sort_key だけ。
     */
    private void move(BlockView v, int insertAt) {
        int from = views.indexOf(v);
        int count = groupSize(from);
        List<Double> keys = BlockOps.keysForMove(blocks(), from, count, insertAt);
        if (keys == null) {
            respace();
            keys = BlockOps.keysForMove(blocks(), from, count, insertAt);
        }
        var group = new ArrayList<>(views.subList(from, from + count));
        views.subList(from, from + count).clear();
        blockBox.getChildren().removeAll(group);
        int at = Math.clamp(insertAt, 0, views.size());
        views.addAll(at, group);
        blockBox.getChildren().addAll(at, group);
        for (int i = 0; i < count; i++) {
            BlockView g = group.get(i);
            g.setBlock(g.block().withSortKey(keys.get(i)));
            s.saver().changed(g.block());
        }
        refreshLayout();
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
        int count = groupSize(from);
        int on = views.indexOf(v);
        // 畳んだ行の下に落としたら、隠れた行の後に入れる
        int target = above ? on : on + groupSize(on);
        // 自分たちの中や、すぐ前後に落としたときは動かさない
        if (target < from || target > from + count) {
            move(dragging, target > from ? target - count : target);
        }
        e.setDropCompleted(true);
        e.consume();
    }

    private void refreshNumbers() {
        List<Block> blocks = blocks();
        for (int i = 0; i < views.size(); i++) {
            if (blocks.get(i).type() == BlockType.NUMBERED) views.get(i).setListNumber(BlockOps.listNumber(blocks, i));
        }
    }

    /** 並びが変わったら呼ぶ。見えていた行は隠さない（足した行、動かした行、字下げの変わった行も）。 */
    private void refreshLayout() {
        refreshLayout(true);
    }

    /**
     * 番号、畳める行と隠す行、目次を今の並びに合わせる。
     * keepVisible のときは、見えていた行が畳んだ範囲に入ったら、その範囲を開いて見えたままにする。
     * 畳む操作とページを開いたときだけ false にする。
     */
    private void refreshLayout(boolean keepVisible) {
        refreshNumbers();
        List<Block> blocks = blocks();
        for (int i = 0; i < views.size(); i++) {
            BlockView v = views.get(i);
            boolean foldable = Outline.hasChildren(blocks, i);
            // 子をなくした行は、畳んだ印を自分で外す
            if (!foldable && v.block().collapsed()) setCollapsed(v, false);
            v.setFoldable(foldable);
        }
        blocks = blocks();
        boolean[] hidden = Outline.hidden(blocks);
        if (keepVisible) {
            var open = new java.util.HashSet<Integer>();
            for (int i = 0; i < views.size(); i++) {
                if (hidden[i] && !views.get(i).hidden()) open.addAll(Outline.hiders(blocks, i));
            }
            if (!open.isEmpty()) {
                for (int i : open) setCollapsed(views.get(i), false);
                hidden = Outline.hidden(blocks());
            }
        }
        for (int i = 0; i < views.size(); i++) views.get(i).setHidden(hidden[i]);
        outlineListener.run();
    }

    /** v を隠している畳んだ行をすべて開く。 */
    private void reveal(BlockView v) {
        int idx = views.indexOf(v);
        if (idx < 0) return;
        List<Integer> hiders = Outline.hiders(blocks(), idx);
        if (hiders.isEmpty()) return;
        for (int i : hiders) setCollapsed(views.get(i), false);
        refreshLayout();
    }

    /** idx の行を b に変えたら、畳んだ節に入って隠れるか。 */
    private boolean wouldBeHidden(int idx, Block b) {
        var after = new ArrayList<>(blocks());
        after.set(idx, b);
        return !Outline.hiders(after, idx).isEmpty();
    }

    private void setCollapsed(BlockView v, boolean collapsed) {
        v.setBlock(v.block().withCollapsed(collapsed));
        s.saver().changed(v.block());
    }

    /** idx の行を動かすときに一緒に動く行の数。畳んでいれば隠れた行も含む。 */
    private int groupSize(int idx) {
        return views.get(idx).block().collapsed() ? Outline.end(blocks(), idx) - idx : 1;
    }

    private int visibleBefore(int idx) {
        for (int i = idx - 1; i >= 0; i--) {
            if (!views.get(i).hidden()) return i;
        }
        return -1;
    }

    private int visibleAfter(int idx) {
        for (int i = idx + 1; i < views.size(); i++) {
            if (!views.get(i).hidden()) return i;
        }
        return -1;
    }

    private List<Block> blocks() {
        return views.stream().map(BlockView::block).toList();
    }

    private void focusNear(int idx, boolean atEnd) {
        if (views.isEmpty()) return;
        int i = Math.clamp(idx, 0, views.size() - 1);
        if (views.get(i).hidden()) i = visibleBefore(i);
        BlockView v = views.get(Math.max(i, 0));
        if (atEnd) v.focusEnd();
        else v.focusAt(0);
    }

    void clickBelowLastBlock() {
        if (page == null || views.isEmpty()) return;
        BlockView last = views.get(Math.max(visibleBefore(views.size()), 0));
        if (last.block().type() == BlockType.PARAGRAPH && last.text().isEmpty() && !last.block().collapsed()) {
            last.focusAt(0);
            return;
        }
        // 畳んだ見出しが最後の節なら、段落はその節に入って開いてしまう。Enter と同じく同じ段の見出しにする
        Block lb = last.block();
        BlockType type = lb.collapsed() && Outline.level(lb.type()) > 0 && lb.indent() == 0
            ? lb.type() : BlockType.PARAGRAPH;
        insertBlock(views.size(), Block.empty(page.id(), type, 0)).focusAt(0);
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

    /** node が画面の上の方に来るように送る。開いたばかりの行も測れるよう、先に配置を済ませる。 */
    private void scrollToTop(Node node) {
        if (scrollPane == null || scrollPane.getContent() == null) return;
        scrollPane.applyCss();
        scrollPane.layout();
        Node content = scrollPane.getContent();
        Bounds b = content.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
        double contentHeight = content.getBoundsInLocal().getHeight();
        double viewport = scrollPane.getViewportBounds().getHeight();
        if (contentHeight <= viewport) return;
        scrollPane.setVvalue(Math.clamp((b.getMinY() - 24) / (contentHeight - viewport), 0, 1));
    }

    /** 名前の変更を書く。書けなかったら false（変えていなければ true）。 */
    boolean saveTitle() {
        renameLater.stop();
        if (page == null || title.getText().equals(page.title())) return true;
        String t = title.getText();
        if (!Errors.run("名前を変更", () -> s.pages().rename(page.id(), t))) return false;
        Errors.get("ページを読む", () -> s.pages().get(page.id()).orElse(null)).ifPresent(p -> {
            page = p;
            nav.pageChanged(p);
        });
        return true;
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
                    nav.pageChanged(p);
                });
            }
        });
    }
}
