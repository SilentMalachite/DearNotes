package app.dearnotes.ui;

import app.dearnotes.editor.MarkdownShortcuts;
import app.dearnotes.editor.Marks;
import app.dearnotes.editor.SlashCommands;
import app.dearnotes.model.Block;
import app.dearnotes.model.BlockType;
import app.dearnotes.model.Mark;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DataFormat;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import org.fxmisc.richtext.StyleClassedTextArea;
import org.fxmisc.richtext.model.ReadOnlyStyledDocument;

/** ブロック1つ分の行。左からハンドル、記号（点・番号・チェック）、本文。 */
final class BlockView extends HBox {

    static final double INDENT_WIDTH = 24;

    /** キー操作の結果を PageEditor に渡す。 */
    interface Host {
        void edited(BlockView v);
        void enter(BlockView v);
        void backspaceAtStart(BlockView v);
        void deleteAtEnd(BlockView v);
        void indent(BlockView v, int delta);
        void focusPrevious(BlockView v);
        void focusNext(BlockView v);
        void moveBy(BlockView v, int delta);
        void convert(BlockView v, BlockType type, int prefixLength);
        void slashQuery(BlockView v, String query);
        /** 文字が変わった。「[[語句」を打っていればページの候補を出す。 */
        void linkQuery(BlockView v);
        /** フォーカスが外れた。候補の窓を閉じる。 */
        void blurred(BlockView v);
        boolean slashKey(BlockView v, KeyEvent e);
        void pasteLines(BlockView v, String text);
        void scroll(double deltaY);
        void focused(BlockView v);
        void openLink(String url);
        void openPage(String pageId);
        void dragStarted(BlockView v, MouseEvent e);
        void toggleFold(BlockView v);
    }

    private final Host host;
    private final StyleClassedTextArea area = new StyleClassedTextArea();
    private final Label handle = new Label("⋮⋮");
    private final Region indentSpacer = new Region();
    private final StackPane fold = new StackPane();
    private final Region foldArrow = new Region();
    private final StackPane gutter = new StackPane();
    private final Label bullet = new Label();
    private final CheckBox check = new CheckBox();
    private final Separator divider = new Separator();
    private final StackPane content = new StackPane();
    private final Label placeholder = new Label();
    private Block block;
    private boolean loading;
    /** insertPageLink や装飾ごとの貼り付けの最中。入れたリンクを unlink で外さないため。 */
    private boolean linking;
    private boolean composing;
    private boolean foldable;
    private boolean hidden;

    BlockView(Block block, Host host) {
        this.block = block;
        this.host = host;
        getStyleClass().add("block");
        setAlignment(Pos.TOP_LEFT);

        handle.getStyleClass().add("block-handle");
        handle.setOnDragDetected(e -> host.dragStarted(this, e));

        // ▸▾ の枠は、畳めない行でも幅を取って本文の位置をそろえる
        // 文字の ▸ は字体によって点になるので、図形で描く。向きは CSS で変える
        fold.getStyleClass().add("block-fold");
        foldArrow.getStyleClass().add("block-fold-arrow");
        fold.getChildren().add(foldArrow);
        fold.setOnMouseClicked(e -> {
            host.toggleFold(this);
            e.consume();
        });

        gutter.getStyleClass().add("block-gutter");
        bullet.getStyleClass().add("block-bullet");
        check.setFocusTraversable(false);
        check.setOnAction(e -> {
            this.block = this.block.withChecked(check.isSelected());
            pseudoUpdate();
            host.edited(this);
        });
        gutter.getChildren().addAll(bullet, check);

        area.getStyleClass().add("block-text");
        area.setWrapText(true);
        area.setAutoHeight(true);
        area.setPlaceholder(placeholder);
        placeholder.getStyleClass().add("block-placeholder");
        placeholder.setMouseTransparent(true);
        divider.getStyleClass().add("block-divider");
        divider.setMouseTransparent(true);
        content.getStyleClass().add("block-content");
        content.getChildren().addAll(area, divider);
        HBox.setHgrow(content, Priority.ALWAYS);
        content.setMaxWidth(Double.MAX_VALUE);

        getChildren().addAll(handle, indentSpacer, fold, gutter, content);

        area.multiRichChanges().subscribe(changes -> {
            if (!loading) onEdited();
        });
        area.plainTextChanges().subscribe(ch -> {
            // 日本語入力では「＃　」がまとめて確定されることもあるので、短い入力なら調べる
            int n = ch.getInserted().length();
            // 元に戻すときは、消す前の装飾ごと戻すので触らない。
            // 記号の変換は行頭を消して位置がずれるので、リンクの印を外してから調べる
            if (!loading && !linking && n >= 1 && !area.getUndoManager().isPerformingAction()) {
                Platform.runLater(() -> unlink(ch.getPosition(), n));
            }
            if (!loading && n >= 1 && n <= 4) Platform.runLater(this::detectShortcut);
        });
        // ←→ やクリックでカーソルが「[[」から離れたら、ページの候補を閉じる
        area.caretPositionProperty().addListener((obs, old, now) -> {
            if (!loading) host.linkQuery(this);
        });
        area.focusedProperty().addListener((obs, was, now) -> {
            updatePlaceholder();
            if (now) host.focused(this);
            else host.blurred(this);
        });
        // 日本語入力で変換中の文字があるあいだは、ショートカットも Enter も IME に任せる
        area.addEventFilter(javafx.scene.input.InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
            e -> composing = !e.getComposed().isEmpty());
        area.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        area.addEventFilter(ScrollEvent.SCROLL, e -> {
            e.consume();
            host.scroll(e.getDeltaY());
        });
        area.addEventFilter(MouseEvent.MOUSE_CLICKED, this::onClicked);

        load();
    }

    Block block() {
        return block;
    }

    StyleClassedTextArea area() {
        return area;
    }

    int caret() {
        return area.getCaretPosition();
    }

    String text() {
        return area.getText();
    }

    /** 種類や字下げなど、本文以外が変わったブロックを受け取る。 */
    void setBlock(Block b) {
        boolean contentChanged = !b.text().equals(area.getText()) || !b.marks().equals(StyledText.read(area));
        boolean typeChanged = b.type() != block.type();
        block = b;
        if (contentChanged) {
            load();
        } else if (typeChanged) {
            applyType();
        } else {
            applyIndent();
            pseudoUpdate();
        }
    }

    /** 本文を差し替える（分割・結合のとき）。 */
    void setContent(String text, List<Mark> marks) {
        block = block.withContent(text, marks);
        load();
    }

    /** from から to を、target のページへのリンクにした name に置き換える。カーソルはリンクの後ろ。 */
    void insertPageLink(int from, int to, String name, String target) {
        linking = true;
        try {
            area.replaceText(from, to, name);
            // 太字などは残し、リンクだけ付け替える
            area.setStyleSpans(from, area.getStyleSpans(from, from + name.length()).mapStyles(classes -> {
                var next = new java.util.ArrayList<>(StyledText.withoutPage(classes));
                next.addAll(StyledText.pageClasses(target));
                return next;
            }));
        } finally {
            linking = false;
        }
        focusAt(from + name.length());
    }

    void setListNumber(int n) {
        if (block.type() == BlockType.NUMBERED) bullet.setText(n + ".");
    }

    /** 子の行があって畳めるか。PageEditor が並び全体から決める。 */
    void setFoldable(boolean f) {
        foldable = f;
        pseudoUpdate();
    }

    boolean foldable() {
        return foldable;
    }

    /** 畳んだ行の範囲に入っていれば隠す。 */
    void setHidden(boolean h) {
        hidden = h;
        setVisible(!h);
        setManaged(!h);
    }

    boolean hidden() {
        return hidden;
    }

    void focusAt(int position) {
        area.requestFocus();
        area.moveTo(Math.clamp(position, 0, area.getLength()));
        area.requestFollowCaret();
    }

    void focusEnd() {
        focusAt(area.getLength());
    }

    Optional<Bounds> caretBounds() {
        return area.getCaretBounds();
    }

    void setDropHint(String hint) {
        getStyleClass().removeAll("drop-above", "drop-below");
        if (hint != null) getStyleClass().add(hint);
    }

    // ---- 内部 ----

    private void load() {
        loading = true;
        try {
            StyledText.load(area, block.text(), block.type() == BlockType.CODE ? List.of() : block.marks());
            area.getUndoManager().forgetHistory();
        } finally {
            loading = false;
        }
        applyType();
    }

    private void applyType() {
        BlockType t = block.type();
        for (BlockType other : BlockType.values()) getStyleClass().remove(other.dbName());
        getStyleClass().add(t.dbName());

        bullet.setVisible(t == BlockType.BULLET || t == BlockType.NUMBERED);
        check.setVisible(t == BlockType.TODO);
        check.setSelected(block.checked());
        gutter.setManaged(t.isList());
        gutter.setVisible(t.isList());
        if (t == BlockType.BULLET) bullet.setText(block.indent() % 2 == 0 ? "•" : "◦");

        boolean isDivider = t == BlockType.DIVIDER;
        divider.setVisible(isDivider);
        area.setEditable(!isDivider);
        area.setOpacity(isDivider ? 0 : 1);
        placeholder.setText(switch (t) {
            case H1, H2, H3 -> t.label();
            case TODO -> "ToDo";
            case BULLET, NUMBERED -> "リスト";
            case QUOTE -> "引用";
            case CODE -> "コード";
            case DIVIDER -> "";
            case PARAGRAPH -> "「/」でコマンド";
        });
        applyIndent();
        pseudoUpdate();
        updatePlaceholder();
    }

    private void applyIndent() {
        // CSS の padding に上書きされないよう、字下げは専用の余白で付ける
        double w = block.indent() * INDENT_WIDTH;
        indentSpacer.setMinWidth(w);
        indentSpacer.setPrefWidth(w);
        indentSpacer.setMaxWidth(w);
        if (block.type() == BlockType.BULLET) bullet.setText(block.indent() % 2 == 0 ? "•" : "◦");
    }

    private void pseudoUpdate() {
        getStyleClass().removeAll("checked", "foldable", "collapsed");
        if (block.type() == BlockType.TODO && block.checked()) getStyleClass().add("checked");
        if (foldable) getStyleClass().add("foldable");
        if (foldable && block.collapsed()) getStyleClass().add("collapsed");
    }

    private void updatePlaceholder() {
        boolean heading = switch (block.type()) {
            case H1, H2, H3 -> true;
            default -> false;
        };
        placeholder.setVisible(heading || area.isFocused());
    }

    private void onEdited() {
        String text = area.getText();
        List<Mark> marks = block.type() == BlockType.CODE ? List.of() : StyledText.read(area);
        if (text.equals(block.text()) && marks.equals(block.marks())) return;
        block = block.withContent(text, marks);
        host.edited(this);
        // コードの中の「/」はただの文字。候補は出さず、出ていれば閉じる
        host.slashQuery(this, block.type() == BlockType.CODE ? null : SlashCommands.queryOf(text));
        host.linkQuery(this);
    }

    /**
     * リンクの外に打った文字から、ページへのリンクの印を外す。
     * RichTextFX は前の文字（行頭では後ろの文字）の装飾を引き継ぐので、そのままだと打った文字もリンクになる。
     * 前と後ろの文字が同じリンクなら、リンクの途中の入力なので残す。
     */
    private void unlink(int at, int length) {
        int end = at + length;
        if (end > area.getLength()) return;
        String target = StyledText.targetOf(area.getStyleOfChar(at));
        if (target == null) return;
        String text = area.getText();
        boolean before = at > 0 && text.charAt(at - 1) != '\n'
            && target.equals(StyledText.targetOf(area.getStyleOfChar(at - 1)));
        boolean after = end < text.length() && text.charAt(end) != '\n'
            && target.equals(StyledText.targetOf(area.getStyleOfChar(end)));
        if (before && after) return;
        area.setStyleSpans(at, area.getStyleSpans(at, end).mapStyles(StyledText::withoutPage));
    }

    /** 行頭に「# 」などを打ったら、その種類に変える。 */
    private void detectShortcut() {
        if (block.type() != BlockType.PARAGRAPH || composing) return;
        MarkdownShortcuts.detect(area.getText(), area.getCaretPosition())
            // 「---」「#」などの名前のページへのリンクは記号ではない。変えるとリンクが消える
            .filter(hit -> StyledText.read(area).stream()
                .noneMatch(m -> m.style() == Mark.Style.PAGE && m.start() < hit.prefixLength()))
            .ifPresent(hit -> host.convert(this, hit.type(), hit.prefixLength()));
    }

    private void onKeyPressed(KeyEvent e) {
        if (composing) return;
        if (host.slashKey(this, e)) {
            e.consume();
            return;
        }
        boolean shortcut = e.isShortcutDown();
        boolean noSelection = area.getSelection().getLength() == 0;
        int caret = area.getCaretPosition();
        int length = area.getLength();

        if (shortcut && !e.isAltDown()) {
            Mark.Style style = switch (e.getCode()) {
                case B -> e.isShiftDown() ? null : Mark.Style.BOLD;
                case I -> e.isShiftDown() ? null : Mark.Style.ITALIC;
                case E -> e.isShiftDown() ? null : Mark.Style.CODE;
                case S -> e.isShiftDown() ? Mark.Style.STRIKE : null;
                case L -> e.isShiftDown() ? Mark.Style.LINK : null;
                default -> null;
            };
            if (style != null && block.type() != BlockType.CODE) {
                StyledText.toggle(area, style);
                e.consume();
                return;
            }
            if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) {
                host.toggleFold(this);
                e.consume();
                return;
            }
            if (e.isShiftDown() && (e.getCode() == KeyCode.UP || e.getCode() == KeyCode.DOWN)) {
                host.moveBy(this, e.getCode() == KeyCode.UP ? -1 : 1);
                e.consume();
                return;
            }
            if (e.getCode() == KeyCode.V && !e.isShiftDown()) {
                String clip = Clipboard.getSystemClipboard().getString();
                if (clip != null && clip.contains("\n") && block.type() != BlockType.CODE) {
                    host.pasteLines(this, clip);
                    e.consume();
                } else if (styledClipboard()) {
                    // コピーした装飾ごと貼るときは、ページへのリンクも貼ったまま残す
                    linking = true;
                    try {
                        area.paste();
                    } finally {
                        linking = false;
                    }
                    e.consume();
                }
                return;
            }
            return;
        }

        switch (e.getCode()) {
            case ENTER -> {
                if (e.isShiftDown()) {
                    area.replaceSelection("\n");
                } else if (block.type() == BlockType.CODE && !(caret == length && area.getText().endsWith("\n"))) {
                    area.replaceSelection("\n");
                } else {
                    if (block.type() == BlockType.CODE) area.deleteText(length - 1, length);
                    // 選んだ文字があれば、消してから分ける
                    if (!noSelection) area.replaceSelection("");
                    host.enter(this);
                }
                e.consume();
            }
            case BACK_SPACE -> {
                if (noSelection && caret == 0) {
                    host.backspaceAtStart(this);
                    e.consume();
                }
            }
            case DELETE -> {
                if (noSelection && caret == length) {
                    host.deleteAtEnd(this);
                    e.consume();
                }
            }
            case TAB -> {
                host.indent(this, e.isShiftDown() ? -1 : 1);
                e.consume();
            }
            // Shift+↑↓ は範囲選択。ほかのブロックへは移らず、端の行では本文の先頭・末尾まで選ぶ
            case UP -> {
                if (onFirstLine()) {
                    if (e.isShiftDown()) area.selectRange(area.getAnchor(), 0);
                    else host.focusPrevious(this);
                    e.consume();
                }
            }
            case DOWN -> {
                if (onLastLine()) {
                    if (e.isShiftDown()) area.selectRange(area.getAnchor(), length);
                    else host.focusNext(this);
                    e.consume();
                }
            }
            case LEFT -> {
                if (noSelection && caret == 0 && !e.isShiftDown()) {
                    host.focusPrevious(this);
                    e.consume();
                }
            }
            case RIGHT -> {
                if (noSelection && caret == length && !e.isShiftDown()) {
                    host.focusNext(this);
                    e.consume();
                }
            }
            default -> { }
        }
    }

    /** クリップボードに、RichTextFX がコピーした装飾つきの文字があるか（ほかのアプリの文字は装飾なし）。 */
    boolean styledClipboard() {
        return area.getStyleCodecs().map(codecs -> {
            String name = ReadOnlyStyledDocument.codec(codecs._1, codecs._2, area.getSegOps()).getName();
            DataFormat format = DataFormat.lookupMimeType(name);
            return format != null && Clipboard.getSystemClipboard().hasContent(format);
        }).orElse(false);
    }

    private boolean onFirstLine() {
        if (area.getCurrentParagraph() != 0) return false;
        return area.lineIndex(0, area.getCaretColumn()) == 0;
    }

    private boolean onLastLine() {
        int last = area.getParagraphs().size() - 1;
        if (area.getCurrentParagraph() != last) return false;
        return area.lineIndex(last, area.getCaretColumn()) == area.getParagraphLinesCount(last) - 1;
    }

    /** Cmd/Ctrl を押しながらリンクを押すと開く。ページへのリンクなら、そのページへ移る。 */
    private void onClicked(MouseEvent e) {
        if (!e.isShortcutDown()) return;
        // 文字と文字の間ではなく、押した文字そのものを見る（行の文字より右を押したときは何もしない）
        var at = area.hit(e.getX(), e.getY()).getCharacterIndex();
        if (at.isEmpty()) return;
        Marks.linkAt(block.marks(), at.getAsInt()).ifPresent(m -> {
            if (m.style() == Mark.Style.PAGE) host.openPage(m.target());
            else host.openLink(block.text().substring(m.start(), m.end()).strip());
            e.consume();
        });
    }
}
