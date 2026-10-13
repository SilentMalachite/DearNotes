package app.dearnotes.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.function.UnaryOperator;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.input.InputMethodEvent;
import javafx.scene.input.InputMethodRequests;
import javafx.scene.input.InputMethodTextRun;
import javafx.scene.input.KeyEvent;
import org.fxmisc.richtext.StyleClassedTextArea;
import org.fxmisc.richtext.model.StyleSpans;

/**
 * 日本語入力の変換中の文字を、本文の中に下線つきで置く。
 * RichTextFX の位置問い合わせは、空の行や改行では画面の左上 (10, 10) を返す。
 * 候補窓はキャレットか、この行の画面上の位置に出す。
 */
final class ImeArea extends StyleClassedTextArea {

    static final String COMPOSED = "ime-composed";

    private final ImeGuard ime = new ImeGuard();
    private final InputMethodRequests requests = new Requests();
    /** いま変換中の文字の先頭と長さ。変換が終わると長さは 0。 */
    private int imStart;
    private int imLength;
    private boolean applying;
    /** 直前の確定で入った文字。変換が終わったあとにリンクの印を外すため。 */
    private int commitStart;
    private int commitLength;

    ImeArea() {
        // 親が機能の有無で付けないことがあるので、ここで必ず付け替える
        setOnInputMethodTextChanged(this::handleInputMethodEvent);
        setInputMethodRequests(requests);
        ime.install(this);
    }

    boolean isComposing() {
        return imLength > 0 || ime.isComposing();
    }

    boolean isApplyingIme() {
        return applying;
    }

    boolean claimsKey(KeyEvent e) {
        return ime.claims(e);
    }

    int commitStart() {
        return commitStart;
    }

    int commitLength() {
        return commitLength;
    }

    /** 本文をまとめて入れ替えた。変換中の範囲はもう無い。 */
    void noteDocumentReplaced() {
        imStart = 0;
        imLength = 0;
        commitStart = 0;
        commitLength = 0;
    }

    @Override
    protected void handleInputMethodEvent(InputMethodEvent event) {
        if (!isEditable() || isDisabled()) return;
        applying = true;
        commitStart = 0;
        commitLength = 0;
        try {
            if (imLength > 0) {
                int start = Math.clamp(imStart, 0, getLength());
                int end = Math.clamp(imStart + imLength, start, getLength());
                selectRange(start, end);
            }
            String committed = event.getCommitted() == null ? "" : event.getCommitted();
            if (!committed.isEmpty()) {
                commitStart = getSelection().getStart();
                replaceText(getSelection(), committed);
                commitLength = committed.length();
            }
            // 確定の後ろ、または今選んでいる範囲を、新しい未確定文字列で置き換える
            imStart = getSelection().getStart();
            String composed = composedText(event);
            replaceText(getSelection(), composed);
            imLength = composed.length();
            if (imLength > 0) {
                mapStyle(imStart, imStart + imLength, ImeArea::withComposed);
                int caretPos = event.getCaretPosition();
                int caret = caretPos >= 0 && caretPos < imLength ? imStart + caretPos : imStart + imLength;
                selectRange(caret, caret);
            } else if (commitLength > 0) {
                int caret = getCaretPosition();
                mapStyle(commitStart, commitStart + commitLength, ImeArea::withoutComposed);
                selectRange(caret, caret);
            }
        } finally {
            applying = false;
        }
    }

    private static String composedText(InputMethodEvent event) {
        if (event.getComposed() == null || event.getComposed().isEmpty()) return "";
        var sb = new StringBuilder();
        for (InputMethodTextRun run : event.getComposed()) {
            if (run.getText() != null) sb.append(run.getText());
        }
        return sb.toString();
    }

    private void mapStyle(int from, int to, UnaryOperator<Collection<String>> fn) {
        if (from < 0 || to > getLength() || from >= to) return;
        StyleSpans<Collection<String>> spans = getStyleSpans(from, to);
        boolean changed = false;
        var updated = spans.mapStyles(classes -> {
            Collection<String> next = fn.apply(classes);
            return next;
        });
        for (int i = 0; i < spans.getSpanCount(); i++) {
            if (!spans.getStyleSpan(i).getStyle().equals(updated.getStyleSpan(i).getStyle())) {
                changed = true;
                break;
            }
        }
        if (changed) setStyleSpans(from, updated);
    }

    private static Collection<String> withComposed(Collection<String> classes) {
        if (classes.contains(COMPOSED)) return classes;
        var next = new ArrayList<>(classes);
        next.add(COMPOSED);
        return next;
    }

    private static Collection<String> withoutComposed(Collection<String> classes) {
        if (!classes.contains(COMPOSED)) return classes;
        return classes.stream().filter(c -> !c.equals(COMPOSED)).toList();
    }

    private int positionForImeOffset(int offset) {
        if (imLength > 0) {
            return Math.clamp(imStart + Math.max(0, offset), 0, getLength());
        }
        return Math.clamp(getCaretPosition(), 0, getLength());
    }

    private OptionalBounds boundsOnScreen(int pos) {
        try {
            if (getLength() > 0 && pos < getLength() && !getText(pos, pos + 1).equals("\n")) {
                var chars = getCharacterBoundsOnScreen(pos, pos + 1);
                if (usable(chars)) return new OptionalBounds(chars.get());
            }
            var caret = getCaretBoundsOnScreen(getCaretSelectionBind().getUnderlyingCaret());
            if (usable(caret)) return new OptionalBounds(caret.get());
        } catch (RuntimeException ex) {
            return OptionalBounds.EMPTY;
        }
        return OptionalBounds.EMPTY;
    }

    private static boolean usable(java.util.Optional<Bounds> bounds) {
        if (bounds == null || bounds.isEmpty()) return false;
        Bounds b = bounds.get();
        return b.getHeight() > 0 && !Double.isNaN(b.getMinX()) && !Double.isNaN(b.getMinY());
    }

    private void layoutForIme() {
        if (getScene() != null && getScene().getRoot() != null) {
            getScene().getRoot().applyCss();
            getScene().getRoot().layout();
        } else {
            applyCss();
            layout();
        }
    }

    private Point2D selfOnScreen() {
        Bounds self = localToScreen(getBoundsInLocal());
        if (self != null) {
            return new Point2D(self.getMinX() + 2, self.getMinY() + Math.min(Math.max(self.getHeight(), 1), 18));
        }
        if (getScene() != null && getScene().getWindow() != null) {
            var window = getScene().getWindow();
            return new Point2D(window.getX() + 48, window.getY() + 80);
        }
        return new Point2D(0, 0);
    }

    /** 空の Optional を毎回作らないための小さな入れ物。 */
    private record OptionalBounds(Bounds bounds) {
        private static final OptionalBounds EMPTY = new OptionalBounds(null);
        private boolean isEmpty() { return bounds == null; }
    }

    private final class Requests implements InputMethodRequests {
        @Override
        public Point2D getTextLocation(int offset) {
            try {
                int pos = positionForImeOffset(offset);
                OptionalBounds bounds = boundsOnScreen(pos);
                if (bounds.isEmpty()) {
                    layoutForIme();
                    bounds = boundsOnScreen(pos);
                }
                if (!bounds.isEmpty()) {
                    Bounds b = bounds.bounds;
                    return new Point2D(b.getMinX(), b.getMaxY());
                }
            } catch (RuntimeException ignored) {
                // レイアウトできないときは、この行の画面上の位置に寄せる
            }
            return selfOnScreen();
        }

        @Override
        public int getLocationOffset(int x, int y) {
            if (imLength <= 0) return 0;
            try {
                Point2D local = screenToLocal(x, y);
                if (local == null) return 0;
                int index = hit(local.getX(), local.getY()).getInsertionIndex();
                return Math.clamp(index - imStart, 0, imLength);
            } catch (RuntimeException ex) {
                return 0;
            }
        }

        @Override
        public void cancelLatestCommittedText() {
            // 確定の取り消しは、次の変換イベントが同じ範囲を置き換える
        }

        @Override
        public String getSelectedText() {
            return ImeArea.this.getSelectedText();
        }
    }
}
