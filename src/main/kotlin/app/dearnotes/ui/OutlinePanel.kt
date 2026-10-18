package app.dearnotes.ui

import app.dearnotes.isJavaBlank

import app.dearnotes.editor.Outline
import javafx.scene.control.Label
import javafx.scene.control.OverrunStyle
import javafx.scene.control.ScrollPane
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox

/** 本文の右に出す目次。H1〜H3 を段ごとに字下げして並べ、押すとその見出しへ移る。 */
internal class OutlinePanel(
    private val onJump: (String) -> Unit,
) : VBox() {

    private val items = VBox()

    init {
        styleClass.add("outline-panel")
        minWidth = 160.0
        prefWidth = 220.0

        val title = Label("目次")
        title.styleClass.add("outline-title")
        items.styleClass.add("outline-items")
        val scroll = ScrollPane(items)
        scroll.isFitToWidth = true
        scroll.styleClass.add("outline-scroll")
        setVgrow(scroll, Priority.ALWAYS)
        children.addAll(title, scroll)
    }

    /** 見出しの一覧を作り直す。currentId の見出しを太字にする。 */
    fun show(headings: List<Outline.Heading>, currentId: String?) {
        items.children.clear()
        if (headings.isEmpty()) {
            val none = Label("見出しがありません")
            none.styleClass.add("outline-empty")
            items.children.add(none)
            return
        }
        for (h in headings) {
            val blank = h.text.isJavaBlank()
            val item = Label(if (blank) "無題の見出し" else h.text.replace('\n', ' '))
            item.styleClass.addAll("outline-item", "outline-h" + h.level)
            if (blank) item.styleClass.add("blank")
            if (h.blockId == currentId) item.styleClass.add("current")
            item.maxWidth = Double.MAX_VALUE
            item.textOverrun = OverrunStyle.ELLIPSIS
            item.setOnMouseClicked { onJump(h.blockId) }
            items.children.add(item)
        }
    }

    fun items(): List<Label> = items.children.filterIsInstance<Label>()
}
