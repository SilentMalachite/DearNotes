package app.dearnotes.ui

import app.dearnotes.model.Page
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.stage.Popup

/** 「[[」で出るページの候補。フォーカスはエリアに残し、キーは BlockView から受け取る。 */
internal class PageLinkMenu(
    private val onPick: (BlockView, Int, Choice) -> Unit,
) {

    /** 候補の1行。page が null なら、name で新しいページを作る行。 */
    data class Choice(val page: Page?, val name: String)

    private val popup = Popup()
    private val list = ListView<Choice>()
    private var target: BlockView? = null
    private var from = 0
    /** Esc で閉じた「[[」。同じ「[[」の後ろで打ち続けても、候補を出し直さない。 */
    private var dismissedView: BlockView? = null
    private var dismissedFrom = -1

    init {
        list.styleClass.addAll("slash-menu", "page-link-menu")
        list.isFocusTraversable = false
        list.prefWidth = 280.0
        list.fixedCellSize = ROW
        list.setCellFactory {
            object : ListCell<Choice>() {
                override fun updateItem(c: Choice?, empty: Boolean) {
                    super.updateItem(c, empty)
                    if (empty || c == null) {
                        graphic = null
                        return
                    }
                    val page = c.page
                    graphic = Label(
                        if (page == null) "＋  新しいページ「" + c.name + "」を作る"
                        else page.displayIcon() + "  " + page.displayTitle(),
                    )
                }
            }
        }
        list.setOnMouseClicked { pick() }
        popup.content.add(list)
        popup.isAutoHide = true
        // Esc は Popup が先に取ってしまうので、handleKey で受けて閉じたことを覚える
        popup.isHideOnEscape = false
        popup.setOnHidden { target = null }
    }

    fun isShowing(): Boolean = popup.isShowing

    /** v のカーソルの下に候補を出す。from は「[[」の位置。候補がなければ閉じる。 */
    fun show(v: BlockView, from: Int, items: List<Choice>) {
        if (items.isEmpty()) {
            close(v)
            return
        }
        val b = v.caretBounds() ?: return
        val scene = v.scene ?: return
        target = v
        this.from = from
        list.items.setAll(items)
        list.selectionModel.selectFirst()
        list.prefHeight = Math.min(items.size, 10) * ROW + 4
        if (popup.isShowing) {
            popup.x = b.minX
            popup.y = b.maxY + 4
        } else {
            popup.show(scene.window, b.minX, b.maxY + 4)
        }
    }

    /** v に出ていれば閉じる。 */
    fun close(v: BlockView) {
        if (v == target) hide()
    }

    /** v の from の「[[」を Esc で閉じたか。 */
    fun dismissed(v: BlockView, from: Int): Boolean = v == dismissedView && from == dismissedFrom

    /** v の「[[」が無くなった。閉じて、Esc で閉じたことも忘れる。 */
    fun forget(v: BlockView) {
        close(v)
        if (v == dismissedView) {
            dismissedView = null
            dismissedFrom = -1
        }
    }

    /** 候補が出ているときの ↑↓ Enter Tab Esc。使ったら true。 */
    fun handleKey(v: BlockView, e: KeyEvent): Boolean {
        if (!popup.isShowing || v != target) return false
        val sel = list.selectionModel
        when (e.code) {
            KeyCode.UP -> sel.select(Math.max(0, sel.selectedIndex - 1))
            KeyCode.DOWN -> sel.select(Math.min(list.items.size - 1, sel.selectedIndex + 1))
            KeyCode.ENTER, KeyCode.TAB -> pick()
            KeyCode.ESCAPE -> {
                dismissedView = v
                dismissedFrom = from
                hide()
            }
            else -> return false
        }
        list.scrollTo(sel.selectedIndex)
        return true
    }

    fun hide() {
        popup.hide()
        target = null
    }

    private fun pick() {
        val c = list.selectionModel.selectedItem
        val v = target
        val at = from
        hide()
        if (c != null && v != null) onPick(v, at, c)
    }

    companion object {
        private const val ROW = 28.0
    }
}
