package app.dearnotes.ui

import app.dearnotes.editor.SlashCommands
import app.dearnotes.editor.SlashCommands.Command
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.stage.Popup

/** 「/」で出る種類の候補。フォーカスはエリアに残し、キーは BlockView から受け取る。 */
internal class SlashMenu(
    private val onPick: (BlockView, Command) -> Unit,
) {

    private val popup = Popup()
    private val list = ListView<Command>()
    private var target: BlockView? = null

    init {
        list.styleClass.add("slash-menu")
        list.isFocusTraversable = false
        list.prefWidth = 240.0
        list.fixedCellSize = 28.0
        list.setCellFactory {
            object : ListCell<Command>() {
                override fun updateItem(c: Command?, empty: Boolean) {
                    super.updateItem(c, empty)
                    if (empty || c == null) {
                        graphic = null
                        return
                    }
                    val label = Label(c.label)
                    val shortcut = Label(c.shortcut)
                    shortcut.styleClass.add("slash-shortcut")
                    val spacer = Region()
                    HBox.setHgrow(spacer, Priority.ALWAYS)
                    graphic = HBox(8.0, label, spacer, shortcut)
                }
            }
        }
        list.setOnMouseClicked { pick() }
        popup.content.add(list)
        popup.isAutoHide = true
        popup.setOnHidden { target = null }
    }

    fun isShowing(): Boolean = popup.isShowing

    fun target(): BlockView? = target

    fun update(v: BlockView, query: String?) {
        if (query == null) {
            if (v == target) hide()
            return
        }
        val items = SlashCommands.filter(query)
        if (items.isEmpty()) {
            hide()
            return
        }
        target = v
        list.items.setAll(items)
        list.selectionModel.selectFirst()
        list.prefHeight = (Math.min(items.size, 10) * 28 + 4).toDouble()
        val b = v.caretBounds() ?: return
        if (popup.isShowing) {
            popup.x = b.minX
            popup.y = b.maxY + 4
        } else {
            popup.show(v.scene!!.window, b.minX, b.maxY + 4)
        }
    }

    /** メニューが出ているときの ↑↓ Enter Esc。使ったら true。 */
    fun handleKey(v: BlockView, e: KeyEvent): Boolean {
        if (!popup.isShowing || v != target) return false
        val sel = list.selectionModel
        when (e.code) {
            KeyCode.UP -> sel.select(Math.max(0, sel.selectedIndex - 1))
            KeyCode.DOWN -> sel.select(Math.min(list.items.size - 1, sel.selectedIndex + 1))
            KeyCode.ENTER, KeyCode.TAB -> pick()
            KeyCode.ESCAPE -> hide()
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
        hide()
        if (c != null && v != null) onPick(v, c)
    }
}
