package app.dearnotes.ui

import java.util.ArrayList
import java.util.Optional
import javafx.geometry.Bounds
import javafx.geometry.Point2D
import javafx.scene.input.InputMethodEvent
import javafx.scene.input.InputMethodRequests
import javafx.scene.input.InputMethodTextRun
import javafx.scene.input.KeyEvent
import org.fxmisc.richtext.StyleClassedTextArea
import org.fxmisc.richtext.model.StyleSpans

/**
 * 日本語入力の変換中の文字を、本文の中に下線つきで置く。
 * RichTextFX の位置問い合わせは、空の行や改行では画面の左上 (10, 10) を返す。
 * 候補窓はキャレットか、この行の画面上の位置に出す。
 */
internal class ImeArea : StyleClassedTextArea() {

    private val ime = ImeGuard()
    private val requests = Requests()
    /** いま変換中の文字の先頭と長さ。変換が終わると長さは 0。 */
    private var imStart = 0
    private var imLength = 0
    private var applying = false
    /** 直前の確定で入った文字。変換が終わったあとにリンクの印を外すため。 */
    private var commitStart = 0
    private var commitLength = 0

    init {
        // 親が機能の有無で付けないことがあるので、ここで必ず付け替える
        setOnInputMethodTextChanged(::handleInputMethodEvent)
        inputMethodRequests = requests
        ime.install(this)
    }

    fun isComposing(): Boolean = imLength > 0 || ime.isComposing()

    fun isApplyingIme(): Boolean = applying

    fun claimsKey(e: KeyEvent): Boolean = ime.claims(e)

    fun commitStart(): Int = commitStart

    fun commitLength(): Int = commitLength

    /** 本文をまとめて入れ替えた。変換中の範囲はもう無い。 */
    fun noteDocumentReplaced() {
        imStart = 0
        imLength = 0
        commitStart = 0
        commitLength = 0
    }

    override fun handleInputMethodEvent(event: InputMethodEvent) {
        if (!isEditable || isDisabled) return
        applying = true
        commitStart = 0
        commitLength = 0
        try {
            if (imLength > 0) {
                val start = imStart.coerceIn(0, length)
                val end = (imStart + imLength).coerceIn(start, length)
                selectRange(start, end)
            }
            val committed = event.committed ?: ""
            if (committed.isNotEmpty()) {
                commitStart = selection.start
                replaceText(selection, committed)
                commitLength = committed.length
            }
            // 確定の後ろ、または今選んでいる範囲を、新しい未確定文字列で置き換える
            imStart = selection.start
            val composed = composedText(event)
            replaceText(selection, composed)
            imLength = composed.length
            if (imLength > 0) {
                mapStyle(imStart, imStart + imLength, ::withComposed)
                val caretPos = event.caretPosition
                val caret = if (caretPos >= 0 && caretPos < imLength) imStart + caretPos else imStart + imLength
                selectRange(caret, caret)
            } else if (commitLength > 0) {
                val caret = caretPosition
                mapStyle(commitStart, commitStart + commitLength, ::withoutComposed)
                selectRange(caret, caret)
            }
        } finally {
            applying = false
        }
    }

    private fun composedText(event: InputMethodEvent): String {
        val composed = event.composed
        if (composed == null || composed.isEmpty()) return ""
        val sb = StringBuilder()
        for (run: InputMethodTextRun in composed) {
            if (run.text != null) sb.append(run.text)
        }
        return sb.toString()
    }

    private fun mapStyle(from: Int, to: Int, fn: (Iterable<*>) -> MutableCollection<String>) {
        if (from < 0 || to > length || from >= to) return
        val spans = getStyleSpans(from, to)
        val updated = spans.mapStyles { classes -> fn(classes) }
        var changed = false
        for (i in 0 until spans.spanCount) {
            if (spans.getStyleSpan(i).style != updated.getStyleSpan(i).style) {
                changed = true
                break
            }
        }
        if (changed) setStyleSpans(from, updated)
    }

    private fun withComposed(classes: Iterable<*>): MutableCollection<String> {
        val next = ArrayList<String>()
        for (item in classes) {
            val c = item as? String ?: continue
            next.add(c)
        }
        if (!next.contains(COMPOSED)) next.add(COMPOSED)
        return next
    }

    private fun withoutComposed(classes: Iterable<*>): MutableCollection<String> {
        val next = ArrayList<String>()
        for (item in classes) {
            val c = item as? String ?: continue
            if (c != COMPOSED) next.add(c)
        }
        return next
    }

    private fun positionForImeOffset(offset: Int): Int {
        if (imLength > 0) {
            return (imStart + maxOf(0, offset)).coerceIn(0, length)
        }
        return caretPosition.coerceIn(0, length)
    }

    private fun boundsOnScreen(pos: Int): OptionalBounds {
        try {
            if (length > 0 && pos < length && getText(pos, pos + 1) != "\n") {
                val chars = getCharacterBoundsOnScreen(pos, pos + 1)
                if (usable(chars)) return OptionalBounds(chars.get())
            }
            val caret = getCaretBoundsOnScreen(caretSelectionBind.underlyingCaret)
            if (usable(caret)) return OptionalBounds(caret.get())
        } catch (ex: RuntimeException) {
            return OptionalBounds.EMPTY
        }
        return OptionalBounds.EMPTY
    }

    private fun usable(bounds: Optional<Bounds>?): Boolean {
        if (bounds == null || bounds.isEmpty) return false
        val b = bounds.get()
        return b.height > 0 && !b.minX.isNaN() && !b.minY.isNaN()
    }

    private fun layoutForIme() {
        val root = scene?.root
        if (root != null) {
            root.applyCss()
            root.layout()
        } else {
            applyCss()
            layout()
        }
    }

    private fun selfOnScreen(): Point2D {
        val self = localToScreen(boundsInLocal)
        if (self != null) {
            return Point2D(self.minX + 2, self.minY + Math.min(Math.max(self.height, 1.0), 18.0))
        }
        val window = scene?.window
        if (window != null) {
            return Point2D(window.x + 48, window.y + 80)
        }
        return Point2D(0.0, 0.0)
    }

    /** 空の Optional を毎回作らないための小さな入れ物。 */
    private class OptionalBounds(val bounds: Bounds?) {
        fun isEmpty(): Boolean = bounds == null

        companion object {
            val EMPTY = OptionalBounds(null)
        }
    }

    private inner class Requests : InputMethodRequests {
        override fun getTextLocation(offset: Int): Point2D {
            try {
                val pos = positionForImeOffset(offset)
                var found = boundsOnScreen(pos)
                if (found.isEmpty()) {
                    layoutForIme()
                    found = boundsOnScreen(pos)
                }
                if (!found.isEmpty()) {
                    val b = found.bounds!!
                    return Point2D(b.minX, b.maxY)
                }
            } catch (ignored: RuntimeException) {
                // レイアウトできないときは、この行の画面上の位置に寄せる
            }
            return selfOnScreen()
        }

        override fun getLocationOffset(x: Int, y: Int): Int {
            if (imLength <= 0) return 0
            try {
                val local = screenToLocal(x.toDouble(), y.toDouble()) ?: return 0
                val index = hit(local.x, local.y).insertionIndex
                return (index - imStart).coerceIn(0, imLength)
            } catch (ex: RuntimeException) {
                return 0
            }
        }

        override fun cancelLatestCommittedText() {
            // 確定の取り消しは、次の変換イベントが同じ範囲を置き換える
        }

        override fun getSelectedText(): String = this@ImeArea.selectedText
    }

    companion object {
        internal const val COMPOSED = "ime-composed"
    }
}
