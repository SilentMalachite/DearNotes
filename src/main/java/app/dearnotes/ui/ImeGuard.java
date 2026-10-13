package app.dearnotes.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.input.InputMethodEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * 変換中のキーと、確定に使った Enter を本文の操作に渡さない。
 * 確定のイベントでは未確定文字列が空なので、その後に届く Enter は変換中に見えない。
 * ここで止める。次のパルスまでで、クリック確定のあとの本来の改行は通す。
 */
final class ImeGuard {

    private boolean composing;
    private boolean confirmEnter;

    void install(Node node) {
        node.addEventFilter(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, e -> {
            boolean now = e.getComposed() != null && !e.getComposed().isEmpty();
            if (composing && !now) {
                confirmEnter = true;
                Platform.runLater(() -> confirmEnter = false);
            }
            composing = now;
        });
        node.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (claims(e)) e.consume();
        });
        node.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            if (composing || (confirmEnter && isNewline(e.getCharacter()))) e.consume();
        });
    }

    boolean isComposing() {
        return composing;
    }

    /** 変換中のキー、または確定直後の Enter。何回呼んでも結果は同じ。 */
    boolean claims(KeyEvent e) {
        if (composing) return true;
        return confirmEnter && e.getCode() == KeyCode.ENTER;
    }

    private static boolean isNewline(String ch) {
        return "\n".equals(ch) || "\r".equals(ch);
    }
}
