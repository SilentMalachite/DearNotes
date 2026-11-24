package app.dearnotes.ui

import javafx.application.Platform
import javafx.scene.Node
import javafx.scene.input.InputMethodEvent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent

/**
 * 変換中のキーと、確定・取消の直後に届く Enter / Esc を操作に渡さない。
 * 確定・取消のイベントでは未確定文字列が空なので、その後に届く Enter や Esc は変換中に見えない。
 * ここで止める。次のパルスまでで、クリック確定のあとの本来の改行や Esc は通す。
 */
internal class ImeGuard {

    private var composing = false
    private var justEnded = false

    fun install(node: Node) {
        node.addEventFilter(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED) { e ->
            val now = e.composed != null && e.composed.isNotEmpty()
            if (composing && !now) {
                justEnded = true
                Platform.runLater { justEnded = false }
            }
            composing = now
        }
        node.addEventFilter(KeyEvent.KEY_PRESSED) { e ->
            if (claims(e)) e.consume()
        }
        node.addEventFilter(KeyEvent.KEY_TYPED) { e ->
            if (composing || (justEnded && isNewline(e.character))) e.consume()
        }
        // フォーカス喪失時に変換終了イベントが届かないことがあるため状態を初期化する
        node.focusedProperty().addListener { _, _, now ->
            if (!now) reset()
        }
    }

    /** 変換状態を初期化する（フォーカス喪失や本文入替で終了イベントを取りこぼしたとき用）。 */
    fun reset() {
        composing = false
        justEnded = false
    }

    fun isComposing(): Boolean = composing

    /** 変換中のキー、または確定・取消直後の Enter / Esc。何回呼んでも結果は同じ。 */
    fun claims(e: KeyEvent): Boolean {
        if (composing) return true
        return justEnded && (e.code == KeyCode.ENTER || e.code == KeyCode.ESCAPE)
    }

    private fun isNewline(ch: String): Boolean = ch == "\n" || ch == "\r"
}
