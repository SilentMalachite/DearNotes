package app.dearnotes.ui

import app.dearnotes.strip
import app.dearnotes.stripLeading

import app.dearnotes.db.BlockRepo.Backlink
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox

/** ページの下に出す「このページへのリンク」。行を押すと、リンクのある行へ移る。 */
internal class Backlinks(
    private val onOpen: (Backlink) -> Unit,
) : VBox() {

    private val header = Label()
    private val shown = ArrayList<Backlink>()
    private val titles = ArrayList<String>()

    init {
        styleClass.add("backlinks")
        header.styleClass.add("backlinks-header")
        show(emptyList())
    }

    /** 一覧を作り直す。なければ隠す。 */
    fun show(links: List<Backlink>) {
        shown.clear()
        shown.addAll(links)
        titles.clear()
        children.setAll(header)
        header.text = "このページへのリンク · " + links.size
        for (i in links.indices) {
            val b = links[i]
            val pageTitle = b.pageTitle
            val shownTitle = if (pageTitle.isNullOrBlank()) "無題" else pageTitle
            val title = icon(b) + " " + shownTitle
            titles.add(title)
            val name = Label(title)
            name.styleClass.add("backlink-title")
            val snippet = Label(snippet(b.text))
            snippet.styleClass.add("backlink-snippet")
            val row = HBox(8.0, name, snippet)
            row.styleClass.add("backlink")
            val at = i
            row.setOnMouseClicked { open(at) }
            children.add(row)
        }
        isVisible = links.isNotEmpty()
        isManaged = links.isNotEmpty()
    }

    fun titles(): List<String> = java.util.List.copyOf(titles)

    fun open(index: Int) {
        if (index >= 0 && index < shown.size) onOpen(shown[index])
    }

    private fun icon(b: Backlink): String {
        val pageIcon = b.pageIcon
        if (!pageIcon.isNullOrBlank()) return pageIcon
        return if (b.daily) "📅" else "📝"
    }

    private fun snippet(text: String): String {
        val flat = text.replace('\n', ' ').strip()
        if (flat.codePointCount(0, flat.length) <= SNIPPET) return flat
        return flat.substring(0, flat.offsetByCodePoints(0, SNIPPET)) + "…"
    }

    companion object {
        private const val SNIPPET = 60
    }
}
