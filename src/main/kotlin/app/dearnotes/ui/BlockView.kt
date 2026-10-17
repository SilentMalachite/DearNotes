package app.dearnotes.ui

import app.dearnotes.strip
import app.dearnotes.stripLeading

import app.dearnotes.editor.MarkdownShortcuts
import app.dearnotes.editor.Marks
import app.dearnotes.editor.SlashCommands
import app.dearnotes.model.Block
import app.dearnotes.model.BlockType
import app.dearnotes.model.Mark
import java.util.ArrayList
import javafx.application.Platform
import javafx.beans.value.ChangeListener
import javafx.geometry.Bounds
import javafx.geometry.Pos
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.Separator
import javafx.scene.input.Clipboard
import javafx.scene.input.DataFormat
import javafx.scene.input.InputMethodEvent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseEvent
import javafx.scene.input.ScrollEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import org.fxmisc.richtext.StyleClassedTextArea
import org.fxmisc.richtext.model.ReadOnlyStyledDocument

/** ブロック1つ分の行。左からハンドル、記号（点・番号・チェック）、本文。 */
internal class BlockView(
    block: Block,
    private val host: Host,
) : HBox() {

    /** キー操作の結果を PageEditor に渡す。 */
    internal interface Host {
        fun edited(v: BlockView)
        fun enter(v: BlockView)
        fun backspaceAtStart(v: BlockView)
        fun deleteAtEnd(v: BlockView)
        fun indent(v: BlockView, delta: Int)
        fun focusPrevious(v: BlockView)
        fun focusNext(v: BlockView)
        fun moveBy(v: BlockView, delta: Int)
        fun convert(v: BlockView, type: BlockType, prefixLength: Int)
        fun slashQuery(v: BlockView, query: String?)
        /** 文字が変わった。「[[語句」を打っていればページの候補を出す。 */
        fun linkQuery(v: BlockView)
        /** フォーカスが外れた。候補の窓を閉じる。 */
        fun blurred(v: BlockView)
        fun slashKey(v: BlockView, e: KeyEvent): Boolean
        fun pasteLines(v: BlockView, text: String)
        fun scroll(deltaY: Double)
        fun focused(v: BlockView)
        fun openLink(url: String)
        fun openPage(pageId: String)
        fun dragStarted(v: BlockView, e: MouseEvent)
        fun toggleFold(v: BlockView)
    }

    private val area = ImeArea()
    private val handle = Label("⋮⋮")
    private val indentSpacer = Region()
    private val fold = StackPane()
    private val foldArrow = Region()
    private val gutter = StackPane()
    private val bullet = Label()
    private val check = CheckBox()
    private val divider = Separator()
    private val content = StackPane()
    private val placeholder = Label()
    private var block: Block = block
    private var loading = false
    /** insertPageLink や装飾ごとの貼り付けの最中。入れたリンクを unlink で外さないため。 */
    private var linking = false
    private var foldable = false
    private var hidden = false

    init {
        styleClass.add("block")
        alignment = Pos.TOP_LEFT

        handle.styleClass.add("block-handle")
        handle.setOnDragDetected { e -> host.dragStarted(this, e) }

        // ▸▾ の枠は、畳めない行でも幅を取って本文の位置をそろえる
        // 文字の ▸ は字体によって点になるので、図形で描く。向きは CSS で変える
        fold.styleClass.add("block-fold")
        foldArrow.styleClass.add("block-fold-arrow")
        fold.children.add(foldArrow)
        fold.setOnMouseClicked { e ->
            host.toggleFold(this)
            e.consume()
        }

        gutter.styleClass.add("block-gutter")
        bullet.styleClass.add("block-bullet")
        check.isFocusTraversable = false
        check.setOnAction {
            this.block = this.block.withChecked(check.isSelected)
            pseudoUpdate()
            host.edited(this)
        }
        gutter.children.addAll(bullet, check)

        area.styleClass.add("block-text")
        area.isWrapText = true
        area.isAutoHeight = true
        area.setPlaceholder(placeholder)
        placeholder.styleClass.add("block-placeholder")
        placeholder.isMouseTransparent = true
        divider.styleClass.add("block-divider")
        divider.isMouseTransparent = true
        content.styleClass.add("block-content")
        content.children.addAll(area, divider)
        setHgrow(content, Priority.ALWAYS)
        content.maxWidth = Double.MAX_VALUE

        children.addAll(handle, indentSpacer, fold, gutter, content)

        area.multiRichChanges().subscribe {
            if (!loading) onEdited()
        }
        area.plainTextChanges().subscribe { ch ->
            // 変換中の置き換えでは調べない。確定した文字は finishImeCommit で調べる。
            // 日本語入力では「＃　」がまとめて確定されることもあるので、短い入力なら調べる
            if (loading || area.isApplyingIme()) return@subscribe
            val n = ch.inserted.length
            // 元に戻すときは、消す前の装飾ごと戻すので触らない。
            // 記号の変換は行頭を消して位置がずれるので、リンクの印を外してから調べる
            if (!linking && n >= 1 && !area.undoManager.isPerformingAction) {
                Platform.runLater { unlink(ch.position, n) }
            }
            if (n >= 1 && n <= 4) Platform.runLater { detectShortcut() }
        }
        // ←→ やクリックでカーソルが「[[」から離れたら、ページの候補を閉じる
        area.caretPositionProperty().addListener(ChangeListener { _, _, _ ->
            if (!loading) host.linkQuery(this)
        })
        area.focusedProperty().addListener(ChangeListener { _, _, now ->
            updatePlaceholder()
            if (now == true) host.focused(this) else host.blurred(this)
        })
        // 確定のあと、変換中に触れなかったリンクの印と行頭の記号を調べる
        area.addEventFilter(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED) { e ->
            if (e.composed != null && e.composed.isNotEmpty()) return@addEventFilter
            Platform.runLater { finishImeCommit() }
        }
        area.addEventFilter(KeyEvent.KEY_PRESSED, ::onKeyPressed)
        area.addEventFilter(ScrollEvent.SCROLL) { e ->
            e.consume()
            host.scroll(e.deltaY)
        }
        area.addEventFilter(MouseEvent.MOUSE_CLICKED, ::onClicked)

        load()
    }

    fun block(): Block = block

    fun area(): StyleClassedTextArea = area

    fun caret(): Int = area.caretPosition

    fun text(): String = area.text

    /** 種類や字下げなど、本文以外が変わったブロックを受け取る。 */
    fun setBlock(b: Block) {
        val contentChanged = b.text != area.text || b.marks != StyledText.read(area)
        val typeChanged = b.type != block.type
        block = b
        if (contentChanged) {
            load()
        } else if (typeChanged) {
            applyType()
        } else {
            applyIndent()
            pseudoUpdate()
        }
    }

    /** 本文を差し替える（分割・結合のとき）。 */
    fun setContent(text: String, marks: List<Mark>) {
        block = block.withContent(text, marks)
        load()
    }

    /** from から to を、target のページへのリンクにした name に置き換える。カーソルはリンクの後ろ。 */
    fun insertPageLink(from: Int, to: Int, name: String, target: String) {
        linking = true
        try {
            area.replaceText(from, to, name)
            // 太字などは残し、リンクだけ付け替える
            area.setStyleSpans(from, area.getStyleSpans(from, from + name.length).mapStyles { classes ->
                val next = ArrayList(StyledText.withoutPage(classes))
                next.addAll(StyledText.pageClasses(target))
                next
            })
        } finally {
            linking = false
        }
        focusAt(from + name.length)
    }

    fun setListNumber(n: Int) {
        if (block.type == BlockType.NUMBERED) bullet.text = "$n."
    }

    /** 子の行があって畳めるか。PageEditor が並び全体から決める。 */
    fun setFoldable(f: Boolean) {
        foldable = f
        pseudoUpdate()
    }

    fun foldable(): Boolean = foldable

    /** 畳んだ行の範囲に入っていれば隠す。 */
    fun setHidden(h: Boolean) {
        hidden = h
        isVisible = !h
        isManaged = !h
    }

    fun hidden(): Boolean = hidden

    fun focusAt(position: Int) {
        area.requestFocus()
        area.moveTo(position.coerceIn(0, area.length))
        area.requestFollowCaret()
    }

    fun focusEnd() {
        focusAt(area.length)
    }

    fun caretBounds(): Bounds? = area.caretBounds.orElse(null)

    fun setDropHint(hint: String?) {
        styleClass.removeAll(listOf("drop-above", "drop-below"))
        if (hint != null) styleClass.add(hint)
    }

    private fun load() {
        loading = true
        try {
            StyledText.load(area, block.text, if (block.type == BlockType.CODE) emptyList() else block.marks)
            area.undoManager.forgetHistory()
            area.noteDocumentReplaced()
        } finally {
            loading = false
        }
        applyType()
    }

    private fun applyType() {
        val t = block.type
        for (other in BlockType.values()) styleClass.remove(other.dbName())
        styleClass.add(t.dbName())

        bullet.isVisible = t == BlockType.BULLET || t == BlockType.NUMBERED
        check.isVisible = t == BlockType.TODO
        check.isSelected = block.checked
        gutter.isManaged = t.isList()
        gutter.isVisible = t.isList()
        if (t == BlockType.BULLET) bullet.text = if (block.indent % 2 == 0) "•" else "◦"

        val isDivider = t == BlockType.DIVIDER
        divider.isVisible = isDivider
        area.isEditable = !isDivider
        area.opacity = if (isDivider) 0.0 else 1.0
        placeholder.text = when (t) {
            BlockType.H1, BlockType.H2, BlockType.H3 -> t.label
            BlockType.TODO -> "ToDo"
            BlockType.BULLET, BlockType.NUMBERED -> "リスト"
            BlockType.QUOTE -> "引用"
            BlockType.CODE -> "コード"
            BlockType.DIVIDER -> ""
            BlockType.PARAGRAPH -> "「/」でコマンド"
        }
        applyIndent()
        pseudoUpdate()
        updatePlaceholder()
    }

    private fun applyIndent() {
        // CSS の padding に上書きされないよう、字下げは専用の余白で付ける
        val w = block.indent * INDENT_WIDTH
        indentSpacer.minWidth = w
        indentSpacer.prefWidth = w
        indentSpacer.maxWidth = w
        if (block.type == BlockType.BULLET) bullet.text = if (block.indent % 2 == 0) "•" else "◦"
    }

    private fun pseudoUpdate() {
        styleClass.removeAll(listOf("checked", "foldable", "collapsed"))
        if (block.type == BlockType.TODO && block.checked) styleClass.add("checked")
        if (foldable) styleClass.add("foldable")
        if (foldable && block.collapsed) styleClass.add("collapsed")
    }

    private fun updatePlaceholder() {
        val heading = when (block.type) {
            BlockType.H1, BlockType.H2, BlockType.H3 -> true
            else -> false
        }
        placeholder.isVisible = heading || area.isFocused
    }

    private fun onEdited() {
        val text = area.text
        val marks = if (block.type == BlockType.CODE) emptyList() else StyledText.read(area)
        if (text == block.text && marks == block.marks) return
        block = block.withContent(text, marks)
        host.edited(this)
        // コードの中の「/」はただの文字。候補は出さず、出ていれば閉じる
        host.slashQuery(this, if (block.type == BlockType.CODE) null else SlashCommands.queryOf(text))
        host.linkQuery(this)
    }

    /**
     * リンクの外に打った文字から、ページへのリンクの印を外す。
     * RichTextFX は前の文字（行頭では後ろの文字）の装飾を引き継ぐので、そのままだと打った文字もリンクになる。
     * 前と後ろの文字が同じリンクなら、リンクの途中の入力なので残す。
     */
    private fun unlink(at: Int, length: Int) {
        val end = at + length
        if (end > area.length) return
        val target = StyledText.targetOf(area.getStyleOfChar(at)) ?: return
        val text = area.text
        val before = at > 0 && text[at - 1] != '\n'
            && target == StyledText.targetOf(area.getStyleOfChar(at - 1))
        val after = end < text.length && text[end] != '\n'
            && target == StyledText.targetOf(area.getStyleOfChar(end))
        if (before && after) return
        area.setStyleSpans(at, area.getStyleSpans(at, end).mapStyles { StyledText.withoutPage(it) })
    }

    /** 変換が終わった文字について、リンクの印と行頭の記号を調べる。 */
    private fun finishImeCommit() {
        if (area.isComposing()) return
        val n = area.commitLength()
        if (n > 0) unlink(area.commitStart(), n)
        detectShortcut()
    }

    /** 行頭に「# 」などを打ったら、その種類に変える。 */
    private fun detectShortcut() {
        if (block.type != BlockType.PARAGRAPH || area.isComposing()) return
        val hit = MarkdownShortcuts.detect(area.text, area.caretPosition) ?: return
        // 「---」「#」などの名前のページへのリンクは記号ではない。変えるとリンクが消える
        if (StyledText.read(area).any { m -> m.style == Mark.Style.PAGE && m.start < hit.prefixLength }) return
        host.convert(this, hit.type, hit.prefixLength)
    }

    private fun onKeyPressed(e: KeyEvent) {
        // 変換中と、確定に使った Enter は行の分割に使わない。消費しないと改行が入る
        if (area.claimsKey(e)) {
            e.consume()
            return
        }
        if (host.slashKey(this, e)) {
            e.consume()
            return
        }
        val shortcut = e.isShortcutDown
        val noSelection = area.selection.length == 0
        val caret = area.caretPosition
        val length = area.length

        if (shortcut && !e.isAltDown) {
            val style: Mark.Style? = when (e.code) {
                KeyCode.B -> if (e.isShiftDown) null else Mark.Style.BOLD
                KeyCode.I -> if (e.isShiftDown) null else Mark.Style.ITALIC
                KeyCode.E -> if (e.isShiftDown) null else Mark.Style.CODE
                KeyCode.S -> if (e.isShiftDown) Mark.Style.STRIKE else null
                KeyCode.L -> if (e.isShiftDown) Mark.Style.LINK else null
                else -> null
            }
            if (style != null && block.type != BlockType.CODE) {
                StyledText.toggle(area, style)
                e.consume()
                return
            }
            if (e.code == KeyCode.ENTER && !e.isShiftDown) {
                host.toggleFold(this)
                e.consume()
                return
            }
            if (e.isShiftDown && (e.code == KeyCode.UP || e.code == KeyCode.DOWN)) {
                host.moveBy(this, if (e.code == KeyCode.UP) -1 else 1)
                e.consume()
                return
            }
            if (e.code == KeyCode.V && !e.isShiftDown) {
                val clip = Clipboard.getSystemClipboard().string
                if (clip != null && clip.contains("\n") && block.type != BlockType.CODE) {
                    host.pasteLines(this, clip)
                    e.consume()
                } else if (styledClipboard()) {
                    // コピーした装飾ごと貼るときは、ページへのリンクも貼ったまま残す
                    linking = true
                    try {
                        area.paste()
                    } finally {
                        linking = false
                    }
                    e.consume()
                }
                return
            }
            return
        }

        when (e.code) {
            KeyCode.ENTER -> {
                if (e.isShiftDown) {
                    area.replaceSelection("\n")
                } else if (block.type == BlockType.CODE && !(caret == length && area.text.endsWith("\n"))) {
                    area.replaceSelection("\n")
                } else {
                    if (block.type == BlockType.CODE) area.deleteText(length - 1, length)
                    // 選んだ文字があれば、消してから分ける
                    if (!noSelection) area.replaceSelection("")
                    host.enter(this)
                }
                e.consume()
            }
            KeyCode.BACK_SPACE -> {
                if (noSelection && caret == 0) {
                    host.backspaceAtStart(this)
                    e.consume()
                }
            }
            KeyCode.DELETE -> {
                if (noSelection && caret == length) {
                    host.deleteAtEnd(this)
                    e.consume()
                }
            }
            KeyCode.TAB -> {
                host.indent(this, if (e.isShiftDown) -1 else 1)
                e.consume()
            }
            // Shift+↑↓ は範囲選択。ほかのブロックへは移らず、端の行では本文の先頭・末尾まで選ぶ
            KeyCode.UP -> {
                if (onFirstLine()) {
                    if (e.isShiftDown) area.selectRange(area.anchor, 0) else host.focusPrevious(this)
                    e.consume()
                }
            }
            KeyCode.DOWN -> {
                if (onLastLine()) {
                    if (e.isShiftDown) area.selectRange(area.anchor, length) else host.focusNext(this)
                    e.consume()
                }
            }
            KeyCode.LEFT -> {
                if (noSelection && caret == 0 && !e.isShiftDown) {
                    host.focusPrevious(this)
                    e.consume()
                }
            }
            KeyCode.RIGHT -> {
                if (noSelection && caret == length && !e.isShiftDown) {
                    host.focusNext(this)
                    e.consume()
                }
            }
            else -> {}
        }
    }

    /** クリップボードに、RichTextFX がコピーした装飾つきの文字があるか（ほかのアプリの文字は装飾なし）。 */
    fun styledClipboard(): Boolean {
        return area.styleCodecs.map { codecs ->
            val name = ReadOnlyStyledDocument.codec(codecs._1, codecs._2, area.segOps).name
            val format = DataFormat.lookupMimeType(name)
            format != null && Clipboard.getSystemClipboard().hasContent(format)
        }.orElse(false)
    }

    private fun onFirstLine(): Boolean {
        if (area.currentParagraph != 0) return false
        return area.lineIndex(0, area.caretColumn) == 0
    }

    private fun onLastLine(): Boolean {
        val last = area.paragraphs.size - 1
        if (area.currentParagraph != last) return false
        return area.lineIndex(last, area.caretColumn) == area.getParagraphLinesCount(last) - 1
    }

    /** Cmd/Ctrl を押しながらリンクを押すと開く。ページへのリンクなら、そのページへ移る。 */
    private fun onClicked(e: MouseEvent) {
        if (!e.isShortcutDown) return
        // 文字と文字の間ではなく、押した文字そのものを見る（行の文字より右を押したときは何もしない）
        val at = area.hit(e.x, e.y).characterIndex
        if (at.isEmpty) return
        val m = Marks.linkAt(block.marks, at.asInt) ?: return
        if (m.style == Mark.Style.PAGE) host.openPage(m.target!!)
        else host.openLink(block.text.substring(m.start, m.end).strip())
        e.consume()
    }

    companion object {
        internal const val INDENT_WIDTH = 24.0
    }
}
