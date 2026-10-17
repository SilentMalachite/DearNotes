package app.dearnotes.ui

import app.dearnotes.isJavaBlank
import app.dearnotes.strip

import app.dearnotes.editor.BlockOps
import app.dearnotes.editor.Marks
import app.dearnotes.editor.Outline
import app.dearnotes.editor.PageLinks
import app.dearnotes.editor.PasteParser
import app.dearnotes.editor.SlashCommands.Command
import app.dearnotes.model.Block
import app.dearnotes.model.BlockType
import app.dearnotes.model.Mark
import app.dearnotes.model.Page
import app.dearnotes.model.SortKeys
import java.util.concurrent.TimeUnit
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.geometry.Bounds
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.control.TextInputDialog
import javafx.scene.input.ClipboardContent
import javafx.scene.input.DragEvent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseEvent
import javafx.scene.input.TransferMode
import javafx.scene.layout.HBox
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.util.Duration

/** 1ページ分の編集画面。タイトルとブロックの縦の並び。 */
internal class PageEditor(
    private val s: Services,
    private val nav: Navigator,
) : VBox(), BlockView.Host {

    /** PageEditor から MainWindow に頼むこと。 */
    interface Navigator {
        /** 名前やアイコンが変わった。 */
        fun pageChanged(p: Page)

        /** リンク先のページへ移る。blockId があれば、その行へ。 */
        fun openPage(pageId: String, blockId: String?)

        /** parentId の子ページを作る。開かない。作れなければ空。 */
        fun newLinkedPage(parentId: String?, title: String): Page?
    }

    private val iconButton = Button()
    private val title = TextField()
    private val blockBox = VBox()
    private val views = ArrayList<BlockView>()
    private val slash = SlashMenu(::applySlash)
    private val links = PageLinkMenu(::applyPageLink)
    private val backlinks = Backlinks { nav.openPage(it.pageId, it.blockId) }
    private val linkQueryPending = HashSet<BlockView>()
    private val renameLater = PauseTransition(Duration.millis(300.0))
    private var scrollPane: ScrollPane? = null
    private var page: Page? = null
    private var dragging: BlockView? = null
    private var lastFocused: BlockView? = null
    private var outlineListener: () -> Unit = {}
    private var loadingTitle = false

    init {
        styleClass.add("page-editor")

        iconButton.styleClass.add("page-icon")
        iconButton.isFocusTraversable = false
        iconButton.setOnAction { chooseIcon() }

        title.styleClass.add("page-title")
        title.promptText = "無題"
        title.textProperty().addListener { _, _, _ ->
            if (!loadingTitle) renameLater.playFromStart()
        }
        renameLater.setOnFinished { saveTitle() }
        // 変換の確定に使った Enter では、本文へ移さない
        ImeGuard().install(title)
        title.setOnKeyPressed { e ->
            if (e.code == KeyCode.ENTER || e.code == KeyCode.DOWN) {
                saveTitle()
                if (views.isNotEmpty()) views.first().focusAt(0)
                e.consume()
            }
        }

        blockBox.styleClass.add("block-list")

        // 最後のブロックより下を押したら、末尾で書き始められるようにする
        val filler = Region()
        filler.styleClass.add("page-filler")
        filler.minHeight = 160.0
        filler.setOnMouseClicked { clickBelowLastBlock() }

        children.addAll(HBox(8.0, iconButton), title, blockBox, backlinks, filler)
    }

    fun setScrollPane(scrollPane: ScrollPane) {
        this.scrollPane = scrollPane
    }

    fun page(): Page? = page

    fun views(): List<BlockView> = views.toList()

    fun backlinks(): Backlinks = backlinks

    /** 見出しや並び、カーソルのある節が変わったときに呼ぶ。目次を作り直すため。 */
    fun setOutlineListener(listener: () -> Unit) {
        outlineListener = listener
    }

    fun headings(): List<Outline.Heading> = Outline.headings(blocks())

    /** カーソルのある行が入っている節の見出し。なければ null。 */
    fun currentHeadingId(): String? {
        val idx = views.indexOf(lastFocused)
        if (idx < 0) return null
        val blocks = blocks()
        val h = Outline.currentHeading(blocks, idx)
        return if (h < 0) null else blocks[h].id
    }

    /** 目次から見出しへ移る。畳んだ節に入っていれば、先に開く。 */
    fun jumpTo(blockId: String) {
        val v = views.firstOrNull { it.block().id == blockId } ?: return
        lastFocused = v
        reveal(v)
        v.focusEnd()
        outlineListener()
        Platform.runLater { scrollToTop(v) }
    }

    /**
     * ほかのページへ移る前に、名前と書きかけの変更を DB に入れる。
     * 書けなかったら知らせて false。そのときはページを作ったり戻したりもしない。
     */
    fun flushBeforeLeaving(): Boolean {
        saveTitle()
        slash.hide()
        links.hide()
        if (s.saver.flushAndWait(3, TimeUnit.SECONDS) || page == null) return true
        Errors.warn(
            "保存できていない変更があります",
            "いまのページの変更をまだ保存できていないので、ページを切り替えませんでした。少し待ってからもう一度開いてください。",
        )
        return false
    }

    /**
     * ページを開く。開く前に書きかけの変更を DB に入れておく。
     * 書けなかったり、開くページを読めなかったりしたら、今のページのままにして false。
     */
    fun open(p: Page, focusBlockId: String?): Boolean {
        if (!flushBeforeLeaving()) return false
        // 読めなかったときは、今のページを消さずに残す（空の行で中身を上書きしないように）
        val loaded = Errors.get("ページを開く") { s.blocks.listByPage(p.id) } ?: return false
        // 開く前に書いた名前を反映させるため、読み直す
        val id = p.id
        page = Errors.get("ページを読む") { s.pages.get(id) } ?: p
        val opened = page!!
        loadingTitle = true
        title.text = opened.title ?: ""
        loadingTitle = false
        iconButton.text = opened.displayIcon()

        views.clear()
        blockBox.children.clear()
        lastFocused = null
        for (b in loaded) addView(views.size, b)
        if (views.isEmpty()) {
            val first = Block.empty(opened.id, BlockType.PARAGRAPH, SortKeys.GAP)
            addView(0, first)
            s.saver.changed(first)
        }
        refreshLayout(false)
        backlinks.show(Errors.get("このページへのリンクを読む") { s.blocks.linksTo(opened.id) } ?: emptyList())
        scrollPane?.vvalue = 0.0

        Platform.runLater {
            if (focusBlockId != null) {
                val target = views.firstOrNull { it.block().id == focusBlockId }
                if (target != null) {
                    reveal(target)
                    target.focusEnd()
                    Platform.runLater { ensureVisible(target) }
                }
            } else if ((opened.title == null || opened.title.isJavaBlank()) && !opened.isDaily()) {
                title.requestFocus()
            } else {
                views.first().focusEnd()
            }
        }
        return true
    }

    /** サイドバーで名前やアイコンが変わったとき。 */
    fun pageUpdated(p: Page) {
        val current = page ?: return
        if (current.id != p.id) return
        page = p
        iconButton.text = p.displayIcon()
        if (!title.isFocused) {
            loadingTitle = true
            title.text = p.title ?: ""
            loadingTitle = false
        }
    }

    fun focusTitle() {
        title.requestFocus()
        title.selectAll()
    }

    override fun edited(v: BlockView) {
        s.saver.changed(v.block())
        if (v.block().type == BlockType.NUMBERED) refreshNumbers()
        if (Outline.level(v.block().type) > 0) outlineListener()
    }

    override fun enter(v: BlockView) {
        val idx = views.indexOf(v)
        val b = v.block()
        val text = v.text()
        val caret = v.caret()
        val current = page ?: return

        if (text.isEmpty() && b.type.isList()) {
            // 空のリスト項目で Enter: 字下げを戻す。一番左なら普通のテキストにする
            if (b.indent > 0) indent(v, -1) else change(v, b.withType(BlockType.PARAGRAPH))
            return
        }
        if (caret == 0 && text.isNotEmpty()) {
            // 行頭で Enter: 上に空の行を入れ、カーソルはそのまま
            val above = Block.empty(current.id, b.type.next(), 0.0).withIndent(b.indent)
            insertBlock(idx, above)
            v.focusAt(0)
            return
        }
        val marks = b.marks
        val left = text.substring(0, caret)
        val right = text.substring(caret)
        v.setContent(left, Marks.slice(marks, 0, caret))
        s.saver.changed(v.block())

        var type = if (b.type == BlockType.DIVIDER) BlockType.PARAGRAPH else b.type.next()
        var at = idx + 1
        if (b.collapsed) {
            // 畳んだ行で分けたら、隠れた行の後に入れる。
            // 見出しの後に段落を入れるとその節に入って隠れるので、同じ段の見出しにする
            at = Outline.end(blocks(), idx)
            if (Outline.level(b.type) > 0) type = b.type
        }
        val next = Block.empty(current.id, type, 0.0)
            .withIndent(b.indent)
            .withContent(right, Marks.slice(marks, caret, text.length))
        val nv = insertBlock(at, next)
        nv.focusAt(0)
    }

    override fun backspaceAtStart(v: BlockView) {
        val idx = views.indexOf(v)
        val b = v.block()
        if (b.type == BlockType.DIVIDER) {
            remove(v)
            focusNear(idx - 1, true)
            return
        }
        if (b.type != BlockType.PARAGRAPH) {
            if (v.text().isEmpty() && wouldBeHidden(idx, b.withType(BlockType.PARAGRAPH))) {
                // 畳んだ節の後の空の見出しは、段落にすると節に入って開いてしまう。そのまま消す
                remove(v)
                focusNear(idx - 1, true)
                return
            }
            change(v, b.withType(BlockType.PARAGRAPH))
            return
        }
        if (b.indent > 0) {
            indent(v, -1)
            return
        }
        if (idx == 0) {
            if (v.text().isEmpty() && views.size > 1) {
                remove(v)
                views.first().focusAt(0)
            }
            return
        }
        val prev = views[idx - 1]
        // 前の行が畳んだ節に隠れていたら、先に開いてから結合する
        reveal(prev)
        if (prev.block().type == BlockType.DIVIDER) {
            remove(prev)
            return
        }
        mergeInto(prev, v)
    }

    override fun deleteAtEnd(v: BlockView) {
        val idx = views.indexOf(v)
        if (v.block().type == BlockType.DIVIDER) {
            // 区切り線に他の行の文字を入れない。区切り線そのものを消す
            remove(v)
            focusNear(idx, false)
            return
        }
        if (idx + 1 >= views.size) return
        val next = views[idx + 1]
        // 次の行が隠れていたら（この行を畳んでいたら）、先に開いてから結合する
        reveal(next)
        if (next.block().type == BlockType.DIVIDER) {
            remove(next)
            return
        }
        val caret = v.text().length
        mergeInto(v, next)
        v.focusAt(caret)
    }

    override fun indent(v: BlockView, delta: Int) {
        val b = v.block()
        val idx = views.indexOf(v)
        // 1つ上より2段以上深くはしない
        val max = if (idx == 0) 0 else views[idx - 1].block().indent + 1
        // 畳んだ行は隠れた行も一緒に字下げを変える。見出しの節は字下げで決まらないので自分だけ
        val withChildren = b.collapsed && Outline.level(b.type) == 0
        val end = if (withChildren) Outline.end(blocks(), idx) else idx + 1
        var deepest = b.indent
        for (i in idx + 1 until end) deepest = maxOf(deepest, views[i].block().indent)
        val next = (b.indent + delta).coerceIn(0, minOf(max, Block.MAX_INDENT - (deepest - b.indent)))
        if (next == b.indent) return
        val d = next - b.indent
        for (i in idx + 1 until end) {
            val c = views[i]
            c.setBlock(c.block().withIndent(c.block().indent + d))
            s.saver.changed(c.block())
        }
        change(v, b.withIndent(next))
    }

    override fun focusPrevious(v: BlockView) {
        val idx = visibleBefore(views.indexOf(v))
        if (idx >= 0) {
            views[idx].focusEnd()
            ensureVisible(views[idx])
        } else {
            title.requestFocus()
            title.end()
        }
    }

    override fun focusNext(v: BlockView) {
        val idx = visibleAfter(views.indexOf(v))
        if (idx >= 0) {
            views[idx].focusAt(0)
            ensureVisible(views[idx])
        }
    }

    override fun moveBy(v: BlockView, delta: Int) {
        val from = views.indexOf(v)
        val count = groupSize(from)
        // 隣の畳んだ行は、隠れた行ごと飛び越える
        val insertAt = if (delta < 0) {
            val before = visibleBefore(from)
            if (before < 0) return
            before
        } else {
            val below = from + count
            if (below >= views.size) return
            var end = below + groupSize(below)
            while (end < views.size && views[end].hidden()) end++
            end - count
        }
        val caret = v.caret()
        move(v, insertAt)
        v.focusAt(caret)
        ensureVisible(v)
    }

    override fun convert(v: BlockView, type: BlockType, prefixLength: Int) {
        val b = v.block()
        val text = v.text()
        val cut = minOf(prefixLength, text.length)
        val rest = text.substring(cut)
        val marks = if (type == BlockType.CODE) emptyList() else Marks.slice(b.marks, cut, text.length)
        val current = page ?: return
        if (type == BlockType.DIVIDER) {
            change(v, b.withType(type).withContent("", emptyList()))
            val idx = views.indexOf(v)
            val next = Block.empty(current.id, BlockType.PARAGRAPH, 0.0).withIndent(b.indent)
                .withContent(rest, marks)
            insertBlock(idx + 1, next).focusAt(0)
            return
        }
        change(v, b.withType(type).withContent(rest, marks))
        v.focusAt(0)
    }

    override fun slashQuery(v: BlockView, query: String?) {
        slash.update(v, query)
    }

    override fun slashKey(v: BlockView, e: KeyEvent): Boolean = links.handleKey(v, e) || slash.handleKey(v, e)

    override fun linkQuery(v: BlockView) {
        // 文字とカーソルの両方の変化で呼ばれるので、1回にまとめる。カーソルが動き終わってから読む
        if (!linkQueryPending.add(v)) return
        Platform.runLater {
            linkQueryPending.remove(v)
            val focused = v.scene != null && v.scene.focusOwner == v.area()
            if (!views.contains(v) || !focused || v.block().type == BlockType.CODE) {
                links.close(v)
                return@runLater
            }
            val q = PageLinks.queryAt(v.text(), v.caret())
            if (q == null) {
                links.forget(v)
                return@runLater
            }
            if (links.dismissed(v, q.from)) return@runLater
            val name = q.text.strip()
            // 探せなかったときに「新しく作る」を出すと、同じ名前のページを作ってしまう
            val found = Errors.get("ページを探す") { s.pages.findByTitle(name, 8) }
            if (found == null) {
                links.close(v)
                return@runLater
            }
            val items = ArrayList<PageLinkMenu.Choice>()
            for (p in found) items.add(PageLinkMenu.Choice(p, p.displayTitle()))
            val exact = items.any { it.page?.title == name }
            if (name.isNotEmpty() && !exact) items.add(PageLinkMenu.Choice(null, name))
            links.show(v, q.from, items)
        }
    }

    override fun blurred(v: BlockView) {
        slash.update(v, null)
        links.close(v)
    }

    override fun pasteLines(v: BlockView, text: String) {
        val lines = PasteParser.parse(text)
        if (lines.isEmpty()) return
        val current = page ?: return
        val baseIndent = v.block().indent
        var last = v
        var from = 0
        var tail = ""
        var tailMarks: List<Mark> = emptyList()
        if (v.block().type == BlockType.DIVIDER) {
            // 区切り線には文字を入れず、すべて下に新しい行として足す
            from = 0
        } else if (v.text().isEmpty() && v.block().type == BlockType.PARAGRAPH) {
            // 空の行に貼ったら、1行目もその行の種類ごと置き換える
            change(v, toBlock(v.block(), lines.first(), baseIndent))
            from = 1
        } else {
            // 1行目はカーソル位置に入れ、カーソルより後ろは最後の行の後ろへ回す
            v.area().replaceSelection(lines.first().text)
            val caret = v.caret()
            tail = v.text().substring(caret)
            tailMarks = Marks.slice(v.block().marks, caret, v.text().length)
            if (tail.isNotEmpty()) v.area().deleteText(caret, v.text().length)
            from = 1
        }
        for (i in from until lines.size) {
            val b = toBlock(Block.empty(current.id, BlockType.PARAGRAPH, 0.0), lines[i], baseIndent)
            last = insertBlock(views.indexOf(last) + 1, b)
        }
        var end = last.text().length
        if (tail.isNotEmpty() && last.block().type == BlockType.DIVIDER) {
            // 区切り線の本文は見えないので、カーソルより後ろは下の新しい行に入れる
            val rest = Block.empty(current.id, BlockType.PARAGRAPH, 0.0).withIndent(baseIndent)
                .withContent(tail, tailMarks)
            last = insertBlock(views.indexOf(last) + 1, rest)
            end = 0
        } else if (tail.isNotEmpty()) {
            val marks = if (last.block().type == BlockType.CODE) {
                emptyList()
            } else {
                Marks.concat(last.block().marks, end, tailMarks)
            }
            last.setContent(last.text() + tail, marks)
            // setContent は編集の知らせを出さないので、保存と目次の更新をここでする
            edited(last)
        }
        last.focusAt(end)
        ensureVisible(last)
    }

    override fun scroll(deltaY: Double) {
        val pane = scrollPane ?: return
        val content = pane.content ?: return
        val contentHeight = content.boundsInLocal.height
        val viewport = pane.viewportBounds.height
        if (contentHeight <= viewport) return
        val v = pane.vvalue - deltaY / (contentHeight - viewport)
        pane.vvalue = v.coerceIn(0.0, 1.0)
    }

    override fun focused(v: BlockView) {
        if (slash.isShowing() && slash.target() != v) slash.hide()
        lastFocused = v
        outlineListener()
    }

    override fun openLink(url: String) {
        s.openLink(url)
    }

    override fun openPage(pageId: String) {
        nav.openPage(pageId, null)
    }

    override fun dragStarted(v: BlockView, e: MouseEvent) {
        dragging = v
        val db = v.startDragAndDrop(TransferMode.MOVE)
        val content = ClipboardContent()
        content.putString(v.block().text)
        db.setContent(content)
        db.setDragView(v.snapshot(null, null), e.x, e.y)
        e.consume()
    }

    override fun toggleFold(v: BlockView) {
        val idx = views.indexOf(v)
        if (idx < 0 || !Outline.hasChildren(blocks(), idx)) return
        val collapse = !v.block().collapsed
        val end = Outline.end(blocks(), idx)
        val focused = views.indexOf(lastFocused)
        val focusInside = collapse && focused > idx && focused < end && lastFocused?.area()?.isFocused == true
        setCollapsed(v, collapse)
        refreshLayout(false)
        // 隠れる行にカーソルがあったら、畳んだ行へ移す
        if (focusInside) v.focusEnd()
    }

    private fun applySlash(v: BlockView, c: Command) {
        // 「/語句」を消してから種類を変える
        convert(v, c.type, v.text().length)
    }

    /** 「[[語句」を、選んだページへのリンクに置き換える。新しいページの行なら、先に子ページを作る。 */
    private fun applyPageLink(v: BlockView, from: Int, c: PageLinkMenu.Choice) {
        // 候補を出した後にカーソルが動いていたら、置き換える範囲が違うのでやめる（ページも作らない）
        val q = PageLinks.queryAt(v.text(), v.caret())
        if (q == null || q.from != from) return
        var target = c.page
        if (target == null) {
            val current = page ?: return
            target = nav.newLinkedPage(current.id, c.name) ?: return
        }
        v.insertPageLink(from, v.caret(), target.displayTitle().replace('\n', ' '), target.id)
    }

    private fun change(v: BlockView, raw: Block) {
        // 見出しの段が変わると範囲の決め方も変わるので、畳んだ印を外す
        var b = raw
        if (b.collapsed && Outline.level(b.type) != Outline.level(v.block().type)) b = b.withCollapsed(false)
        v.setBlock(b)
        s.saver.changed(v.block())
        refreshLayout()
    }

    private fun mergeInto(target: BlockView, source: BlockView) {
        val a = target.text()
        val b = source.text()
        val marks = Marks.concat(target.block().marks, a.length, source.block().marks)
        target.setContent(a + b, marks)
        s.saver.changed(target.block())
        remove(source)
        target.focusAt(a.length)
    }

    private fun insertBlock(index: Int, b: Block): BlockView {
        var key = BlockOps.keyAfter(blocks(), index - 1)
        if (key == null) {
            respace()
            key = BlockOps.keyAfter(blocks(), index - 1)
        }
        val placed = b.withSortKey(key!!)
        val v = addView(index, placed)
        s.saver.changed(placed)
        refreshLayout()
        Platform.runLater { ensureVisible(v) }
        return v
    }

    private fun remove(v: BlockView) {
        val current = page
        views.remove(v)
        blockBox.children.remove(v)
        s.saver.deleted(v.block().id)
        if (views.isEmpty() && current != null) {
            val first = Block.empty(current.id, BlockType.PARAGRAPH, SortKeys.GAP)
            addView(0, first).focusAt(0)
            s.saver.changed(first)
        }
        refreshLayout()
    }

    /**
     * v を insertAt の位置に移す。insertAt は動かす行を抜いた後の並びでの位置。
     * 畳んだ行は隠れた行と一緒に動く。書き換わるのは動かした行の sort_key だけ。
     */
    private fun move(v: BlockView, insertAt: Int) {
        val from = views.indexOf(v)
        val count = groupSize(from)
        var keys = BlockOps.keysForMove(blocks(), from, count, insertAt)
        if (keys == null) {
            respace()
            keys = BlockOps.keysForMove(blocks(), from, count, insertAt)
        }
        val resolved = keys!!
        val group = ArrayList(views.subList(from, from + count))
        views.subList(from, from + count).clear()
        blockBox.children.removeAll(group)
        val at = insertAt.coerceIn(0, views.size)
        views.addAll(at, group)
        blockBox.children.addAll(at, group)
        for (i in 0 until count) {
            val g = group[i]
            g.setBlock(g.block().withSortKey(resolved[i]))
            s.saver.changed(g.block())
        }
        refreshLayout()
    }

    /** 間が詰まったら、ページの全ブロックを GAP 間隔で振り直す。 */
    private fun respace() {
        val keys = SortKeys.spaced(views.size)
        for (i in views.indices) {
            val v = views[i]
            v.setBlock(v.block().withSortKey(keys[i]))
            s.saver.changed(v.block())
        }
    }

    private fun addView(index: Int, b: Block): BlockView {
        val v = BlockView(b, this)
        v.setOnDragOver { dragOver(v, it) }
        v.setOnDragExited { v.setDropHint(null) }
        v.setOnDragDropped { dropOn(v, it) }
        v.setOnDragDone { dragging = null }
        views.add(index, v)
        blockBox.children.add(index, v)
        return v
    }

    private fun dragOver(v: BlockView, e: DragEvent) {
        if (dragging == null || dragging == v) return
        e.acceptTransferModes(TransferMode.MOVE)
        v.setDropHint(if (e.y < v.height / 2) "drop-above" else "drop-below")
        e.consume()
    }

    private fun dropOn(v: BlockView, e: DragEvent) {
        val moving = dragging
        if (moving == null || moving == v) return
        val above = e.y < v.height / 2
        v.setDropHint(null)
        val from = views.indexOf(moving)
        val count = groupSize(from)
        val on = views.indexOf(v)
        // 畳んだ行の下に落としたら、隠れた行の後に入れる
        val target = if (above) on else on + groupSize(on)
        // 自分たちの中や、すぐ前後に落としたときは動かさない
        if (target < from || target > from + count) {
            move(moving, if (target > from) target - count else target)
        }
        e.isDropCompleted = true
        e.consume()
    }

    private fun refreshNumbers() {
        val blocks = blocks()
        for (i in views.indices) {
            if (blocks[i].type == BlockType.NUMBERED) views[i].setListNumber(BlockOps.listNumber(blocks, i))
        }
    }

    /** 並びが変わったら呼ぶ。見えていた行は隠さない（足した行、動かした行、字下げの変わった行も）。 */
    private fun refreshLayout() {
        refreshLayout(true)
    }

    /**
     * 番号、畳める行と隠す行、目次を今の並びに合わせる。
     * keepVisible のときは、見えていた行が畳んだ範囲に入ったら、その範囲を開いて見えたままにする。
     * 畳む操作とページを開いたときだけ false にする。
     */
    private fun refreshLayout(keepVisible: Boolean) {
        refreshNumbers()
        var blocks = blocks()
        for (i in views.indices) {
            val v = views[i]
            val foldable = Outline.hasChildren(blocks, i)
            // 子をなくした行は、畳んだ印を自分で外す
            if (!foldable && v.block().collapsed) setCollapsed(v, false)
            v.setFoldable(foldable)
        }
        blocks = blocks()
        var hidden = Outline.hidden(blocks)
        if (keepVisible) {
            val open = HashSet<Int>()
            for (i in views.indices) {
                if (hidden[i] && !views[i].hidden()) open.addAll(Outline.hiders(blocks, i))
            }
            if (open.isNotEmpty()) {
                for (i in open) setCollapsed(views[i], false)
                hidden = Outline.hidden(blocks())
            }
        }
        for (i in views.indices) views[i].setHidden(hidden[i])
        outlineListener()
    }

    /** v を隠している畳んだ行をすべて開く。 */
    private fun reveal(v: BlockView) {
        val idx = views.indexOf(v)
        if (idx < 0) return
        val hiders = Outline.hiders(blocks(), idx)
        if (hiders.isEmpty()) return
        for (i in hiders) setCollapsed(views[i], false)
        refreshLayout()
    }

    /** idx の行を b に変えたら、畳んだ節に入って隠れるか。 */
    private fun wouldBeHidden(idx: Int, b: Block): Boolean {
        val after = ArrayList(blocks())
        after[idx] = b
        return Outline.hiders(after, idx).isNotEmpty()
    }

    private fun setCollapsed(v: BlockView, collapsed: Boolean) {
        v.setBlock(v.block().withCollapsed(collapsed))
        s.saver.changed(v.block())
    }

    /** idx の行を動かすときに一緒に動く行の数。畳んでいれば隠れた行も含む。 */
    private fun groupSize(idx: Int): Int =
        if (views[idx].block().collapsed) Outline.end(blocks(), idx) - idx else 1

    private fun visibleBefore(idx: Int): Int {
        for (i in idx - 1 downTo 0) {
            if (!views[i].hidden()) return i
        }
        return -1
    }

    private fun visibleAfter(idx: Int): Int {
        for (i in idx + 1 until views.size) {
            if (!views[i].hidden()) return i
        }
        return -1
    }

    private fun blocks(): List<Block> = views.map { it.block() }

    private fun focusNear(idx: Int, atEnd: Boolean) {
        if (views.isEmpty()) return
        var i = idx.coerceIn(0, views.size - 1)
        if (views[i].hidden()) i = visibleBefore(i)
        val v = views[maxOf(i, 0)]
        if (atEnd) v.focusEnd() else v.focusAt(0)
    }

    fun clickBelowLastBlock() {
        val current = page ?: return
        if (views.isEmpty()) return
        val last = views[maxOf(visibleBefore(views.size), 0)]
        if (last.block().type == BlockType.PARAGRAPH && last.text().isEmpty() && !last.block().collapsed) {
            last.focusAt(0)
            return
        }
        // 畳んだ見出しが最後の節なら、段落はその節に入って開いてしまう。Enter と同じく同じ段の見出しにする
        val lb = last.block()
        val type = if (lb.collapsed && Outline.level(lb.type) > 0 && lb.indent == 0) lb.type else BlockType.PARAGRAPH
        insertBlock(views.size, Block.empty(current.id, type, 0.0)).focusAt(0)
    }

    private fun ensureVisible(node: Node) {
        val pane = scrollPane ?: return
        val content = pane.content ?: return
        val b = content.sceneToLocal(node.localToScene(node.boundsInLocal))
        val contentHeight = content.boundsInLocal.height
        val viewport = pane.viewportBounds.height
        if (contentHeight <= viewport) return
        val top = pane.vvalue * (contentHeight - viewport)
        val y = when {
            b.minY < top -> b.minY - 24
            b.maxY > top + viewport -> b.maxY - viewport + 24
            else -> return
        }
        pane.vvalue = (y / (contentHeight - viewport)).coerceIn(0.0, 1.0)
    }

    /** node が画面の上の方に来るように送る。開いたばかりの行も測れるよう、先に配置を済ませる。 */
    private fun scrollToTop(node: Node) {
        val pane = scrollPane ?: return
        val content = pane.content ?: return
        pane.applyCss()
        pane.layout()
        val b = content.sceneToLocal(node.localToScene(node.boundsInLocal))
        val contentHeight = content.boundsInLocal.height
        val viewport = pane.viewportBounds.height
        if (contentHeight <= viewport) return
        pane.vvalue = ((b.minY - 24) / (contentHeight - viewport)).coerceIn(0.0, 1.0)
    }

    /** 名前の変更を書く。書けなかったら false（変えていなければ true）。 */
    fun saveTitle(): Boolean {
        renameLater.stop()
        val current = page ?: return true
        if (title.text == current.title) return true
        val t = title.text
        if (!Errors.run("名前を変更") { s.pages.rename(current.id, t) }) return false
        val updated = Errors.get("ページを読む") { s.pages.get(current.id) }
        if (updated != null) {
            page = updated
            nav.pageChanged(updated)
        }
        return true
    }

    private fun chooseIcon() {
        val current = page ?: return
        val dialog = TextInputDialog(current.icon ?: "")
        dialog.title = "アイコン"
        dialog.headerText = "絵文字を1つ入れてください（空にすると元に戻ります）"
        dialog.initOwner(scene.window)
        val icon = dialog.showAndWait().orElse(null) ?: return
        val value = if (icon.isJavaBlank()) {
            null
        } else {
            val sb = StringBuilder()
            for (cp in icon.strip().codePoints().limit(2).toArray()) sb.appendCodePoint(cp)
            sb.toString()
        }
        if (Errors.run("アイコンを変更") { s.pages.setIcon(current.id, value) }) {
            val updated = Errors.get("ページを読む") { s.pages.get(current.id) }
            if (updated != null) {
                pageUpdated(updated)
                nav.pageChanged(updated)
            }
        }
    }

    companion object {
        private fun toBlock(base: Block, line: PasteParser.Line, baseIndent: Int): Block =
            base.withType(line.type)
                .withContent(line.text, emptyList())
                .withChecked(line.checked)
                .withIndent(baseIndent + line.indent)
    }
}
