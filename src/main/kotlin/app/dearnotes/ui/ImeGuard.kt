package app.dearnotes.ui

import javafx.application.Platform
import javafx.scene.Node
import javafx.scene.input.InputMethodEvent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent

/**
 * 変換中のキーと、確定に使った Enter を本文の操作に渡さない。
 * 確定のイベントでは未確定文字列が空なので、その後に届く Enter は変換中に見えない。
 * ここで止める。次のパルスまでで、クリック確定のあとの本来の改行は通す。
 */
internal class ImeGuard {

    private var composing = false
    private var confirmEnter = false

    fun install(node: Node) {
        node.addEventFilter(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED) { e ->
            val now = e.composed != null && e.composed.isNotEmpty()
            if (composing && !now) {
                confirmEnter = true
                Platform.runLater { confirmEnter = false }
            }
            composing = now
        }
        node.addEventFilter(KeyEvent.KEY_PRESSED) { e ->
            if (claims(e)) e.consume()
        }
        node.addEventFilter(KeyEvent.KEY_TYPED) { e ->
            if (composing || (confirmEnter && isNewline(e.character))) e.consume()
        }
    }

    fun isComposing(): Boolean = composing

    /** 変換中のキー、または確定直後の Enter。何回呼んでも結果は同じ。 */
    fun claims(e: KeyEvent): Boolean {
        if (composing) return true
        return confirmEnter && e.code == KeyCode.ENTER
    }

    private fun isNewline(ch: String): Boolean = ch == "\n" || ch == "\r"
}
