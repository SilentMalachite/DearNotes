package app.dearnotes.ui

import app.dearnotes.model.Page
import app.dearnotes.model.SortKeys
import java.time.LocalDate
import java.util.Comparator
import javafx.scene.control.Button
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.control.OverrunStyle
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeView
import javafx.scene.input.ClipboardContent
import javafx.scene.input.DragEvent
import javafx.scene.input.TransferMode
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox

/** ページの木。上に「検索」「今日」「新しいページ」、下に「ゴミ箱」。 */
internal class Sidebar(
    private val s: Services,
    private val actions: Actions,
) : VBox() {

    /** 木の1行。見出し（ページ / デイリー）かページ。 */
    sealed interface Entry

    data class Section(val label: String, val daily: Boolean) : Entry

    data class PageEntry(val page: Page) : Entry

    interface Actions {
        fun open(p: Page)
        fun openToday()
        fun newPage(parentId: String?)
        fun trash(p: Page)
        fun showTrash()
        fun showSearch()
        fun exportPage(p: Page)
        fun exportTree(p: Page)
    }

    enum class Drop { BEFORE, INTO, AFTER }

    private val tree = TreeView<Entry>()
    private val pagesSection = TreeItem<Entry>(Section("ページ", false))
    private val dailySection = TreeItem<Entry>(Section("デイリー", true))
    private val expanded = HashSet<String>()
    private var alive: List<Page> = emptyList()
    private var dragging: Page? = null
    private var selecting = false
    private var currentId: String? = null

    init {
        styleClass.add("sidebar")

        val search = navButton("🔍  検索", actions::showSearch)
        val today = navButton("📅  今日 · " + todayLabel(), actions::openToday)
        val newPage = navButton("＋  新しいページ") { actions.newPage(null) }

        val root = TreeItem<Entry>()
        root.children.addAll(pagesSection, dailySection)
        pagesSection.isExpanded = true
        dailySection.isExpanded = true
        tree.root = root
        tree.isShowRoot = false
        tree.styleClass.add("page-tree")
        tree.setCellFactory { PageCell() }
        tree.selectionModel.selectedItemProperty().addListener { _, _, item ->
            if (selecting || item == null) return@addListener
            // 木の選択イベントの最中に木を作り直さないよう、開くのは後にする
            val entry = item.value
            if (entry is PageEntry) javafx.application.Platform.runLater { actions.open(entry.page) }
        }
        VBox.setVgrow(tree, Priority.ALWAYS)

        val trash = navButton("🗑  ゴミ箱", actions::showTrash)
        children.addAll(search, today, newPage, tree, trash)
    }

    /** DB から読み直す。開いている枝と選択は残す。 */
    fun reload(selectedId: String?) {
        rememberExpanded(pagesSection)
        rememberExpanded(dailySection)
        alive = Errors.get("ページの一覧を読む") { s.pages.listAlive() } ?: emptyList()

        val children = java.util.HashMap<String?, MutableList<Page>>()
        for (p in alive) children.getOrPut(p.parentId) { ArrayList() }.add(p)
        children.values.forEach { it.sortWith(Comparator.comparingDouble(Page::sortKey)) }

        val top = children[null] ?: emptyList()
        pagesSection.children.setAll(top.filter { !it.isDaily() }.map { build(it, children) })
        dailySection.children.setAll(
            top.filter { it.isDaily() }
                .sortedWith(compareByDescending { it.dailyDate })
                .map { build(it, children) },
        )
        select(selectedId)
    }

    fun select(pageId: String?) {
        currentId = pageId
        selecting = true
        try {
            val item = find(tree.root, pageId)
            if (item == null) {
                tree.selectionModel.clearSelection()
                return
            }
            var parent = item.parent
            while (parent != null) {
                parent.isExpanded = true
                parent = parent.parent
            }
            tree.selectionModel.select(item)
            val row = tree.getRow(item)
            if (row >= 0) tree.scrollTo(maxOf(0, row - 3))
        } finally {
            selecting = false
        }
    }

    /** ページの親をたどった道筋。パンくず用。 */
    fun pathTo(page: Page): List<Page> {
        val byId = HashMap<String, Page>()
        for (p in alive) byId[p.id] = p
        val path = ArrayList<Page>()
        var p: Page? = page
        while (p != null) {
            path.add(0, p)
            val parentId = p.parentId
            p = if (parentId == null) null else byId[parentId]
            if (path.size > 32) break
        }
        return path
    }

    private fun build(p: Page, children: Map<String?, List<Page>>): TreeItem<Entry> {
        val item = TreeItem<Entry>(PageEntry(p))
        for (c in children[p.id] ?: emptyList()) item.children.add(build(c, children))
        item.isExpanded = expanded.contains(p.id)
        item.expandedProperty().addListener { _, _, now ->
            if (now) expanded.add(p.id) else expanded.remove(p.id)
        }
        return item
    }

    private fun rememberExpanded(item: TreeItem<Entry>) {
        for (child in item.children) {
            val value = child.value
            if (value is PageEntry && child.isExpanded) expanded.add(value.page.id)
            rememberExpanded(child)
        }
    }

    private fun navButton(text: String, action: () -> Unit): Button {
        val b = Button(text)
        b.styleClass.add("nav-button")
        b.maxWidth = Double.MAX_VALUE
        b.isFocusTraversable = false
        b.setOnAction { action() }
        return b
    }

    private fun dropOnto(target: Page, where: Drop) {
        val moving = dragging ?: return
        if (moving.id == target.id) return
        val parentId = if (where == Drop.INTO) target.id else target.parentId
        var key = keyFor(alive, parentId, moving, target, where)
        if (key == null) {
            Errors.run("並べ替え") { s.pages.respaceChildren(parentId) }
            alive = Errors.get("ページの一覧を読む") { s.pages.listAlive() } ?: emptyList()
            val fresh = alive.firstOrNull { it.id == target.id } ?: target
            key = keyFor(alive, parentId, moving, fresh, where)
        }
        val k = key ?: SortKeys.GAP
        if (Errors.run("ページを移動") { s.pages.move(moving.id, parentId, k) }) {
            if (where == Drop.INTO) expanded.add(target.id)
            reload(currentId)
        }
    }

    private inner class PageCell : TreeCell<Entry>() {
        init {
            // 長い名前で木が横に広がらないよう、幅は木に合わせて「…」で切る
            prefWidth = 0.0
            textOverrun = OverrunStyle.ELLIPSIS
            setOnDragDetected { e ->
                val entry = item
                if (entry is PageEntry && !entry.page.isDaily()) {
                    dragging = entry.page
                    val db = startDragAndDrop(TransferMode.MOVE)
                    val content = ClipboardContent()
                    content.putString(entry.page.displayTitle())
                    db.setContent(content)
                    e.consume()
                }
            }
            setOnDragOver { dragOver(it) }
            setOnDragExited { hint(null) }
            setOnDragDropped { e ->
                hint(null)
                val where = dropPosition(e)
                val moving = dragging
                if (moving != null && where != null) {
                    val entry = item
                    if (entry is PageEntry) {
                        dropOnto(entry.page, where)
                    } else if (entry is Section && !entry.daily) {
                        val key = SortKeys.between(lastTopKey(moving), null)
                        val k = key ?: SortKeys.GAP
                        if (Errors.run("ページを移動") { s.pages.move(moving.id, null, k) }) reload(currentId)
                    }
                    e.isDropCompleted = true
                }
                e.consume()
            }
            setOnDragDone { dragging = null }
        }

        private fun lastTopKey(moving: Page): Double? =
            alive.asSequence()
                .filter { it.parentId == null && !it.isDaily() && it.id != moving.id }
                .map { it.sortKey }
                .maxOrNull()

        private fun dragOver(e: DragEvent) {
            val where = dropPosition(e)
            if (dragging != null && where != null) {
                e.acceptTransferModes(TransferMode.MOVE)
                hint(where)
            }
            e.consume()
        }

        private fun dropPosition(e: DragEvent): Drop? {
            val moving = dragging ?: return null
            if (isEmpty || item == null) return null
            val entry = item
            if (entry is Section) return if (entry.daily) null else Drop.INTO
            val target = (entry as PageEntry).page
            if (target.id == moving.id) return null
            // 自分の子孫には落とせない
            var node = treeItem
            while (node != null) {
                val value = node.value
                if (value is PageEntry && value.page.id == moving.id) return null
                node = node.parent
            }
            val y = e.y / height
            if (target.isDaily() && target.parentId == null) return Drop.INTO
            if (y < 0.25) return Drop.BEFORE
            if (y > 0.75) return Drop.AFTER
            return Drop.INTO
        }

        private fun hint(where: Drop?) {
            styleClass.removeAll("drop-before", "drop-into", "drop-after")
            if (where != null) styleClass.add("drop-" + where.name.lowercase())
        }

        override fun updateItem(entry: Entry?, empty: Boolean) {
            super.updateItem(entry, empty)
            styleClass.removeAll("section-cell", "page-cell")
            contextMenu = null
            if (empty || entry == null) {
                text = null
                return
            }
            when (entry) {
                is Section -> {
                    text = entry.label
                    styleClass.add("section-cell")
                    if (!entry.daily) {
                        val add = MenuItem("新しいページ")
                        add.setOnAction { actions.newPage(null) }
                        contextMenu = ContextMenu(add)
                    }
                }
                is PageEntry -> {
                    val p = entry.page
                    text = p.displayIcon() + "  " + p.displayTitle()
                    styleClass.add("page-cell")
                    contextMenu = menuFor(p)
                }
            }
        }

        private fun menuFor(p: Page): ContextMenu {
            val child = MenuItem("子ページを追加")
            child.setOnAction {
                expanded.add(p.id)
                actions.newPage(p.id)
            }
            val one = MenuItem("Markdown で書き出す…")
            one.setOnAction { actions.exportPage(p) }
            val treeItem = MenuItem("子ページごと書き出す…")
            treeItem.setOnAction { actions.exportTree(p) }
            treeItem.isVisible = alive.any { p.id == it.parentId }
            val trash = MenuItem("ゴミ箱へ移動")
            trash.setOnAction { actions.trash(p) }
            return ContextMenu(child, SeparatorMenuItem(), one, treeItem, SeparatorMenuItem(), trash)
        }
    }

    companion object {
        private fun todayLabel(): String {
            val d = LocalDate.now()
            return d.monthValue.toString() + "月" + d.dayOfMonth + "日"
        }

        internal fun keyFor(alive: List<Page>, parentId: String?, moving: Page, target: Page, where: Drop): Double? {
            val siblings = alive.asSequence()
                .filter { it.parentId == parentId && it.id != moving.id && !it.isDaily() }
                .sortedWith(Comparator.comparingDouble(Page::sortKey))
                .toList()
            val last = if (siblings.isEmpty()) null else siblings.last().sortKey
            val i = siblings.indexOf(target)
            if (where == Drop.INTO || i < 0) return SortKeys.between(last, null)
            // 条件演算子を入れ子にすると null が double に変換されて落ちるので、if で分ける
            val prev: Double?
            val next: Double?
            if (where == Drop.BEFORE) {
                prev = if (i > 0) siblings[i - 1].sortKey else null
                next = siblings[i].sortKey
            } else {
                prev = siblings[i].sortKey
                next = if (i + 1 < siblings.size) siblings[i + 1].sortKey else null
            }
            return SortKeys.between(prev, next)
        }

        private fun find(item: TreeItem<Entry>, id: String?): TreeItem<Entry>? {
            if (id == null) return null
            val value = item.value
            if (value is PageEntry && value.page.id == id) return item
            for (child in item.children) {
                val hit = find(child, id)
                if (hit != null) return hit
            }
            return null
        }
    }
}
