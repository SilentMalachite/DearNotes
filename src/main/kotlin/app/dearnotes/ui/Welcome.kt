package app.dearnotes.ui

import app.dearnotes.db.BlockRepo
import app.dearnotes.db.PageRepo
import app.dearnotes.model.Block
import app.dearnotes.model.BlockType
import app.dearnotes.model.Mark
import app.dearnotes.model.Page
import app.dearnotes.model.SortKeys

/** 初めて起動したときに作る、使い方のページ。 */
object Welcome {

    fun createIfEmpty(pages: PageRepo, blocks: BlockRepo) {
        if (pages.listAlive().isNotEmpty() || pages.listTrash().isNotEmpty()) return
        val p = pages.create(null, "DearNotes へようこそ")
        pages.setIcon(p.id, "👋")

        val lines = ArrayList<Block>()
        val intro = "Notion のように、1行ずつブロックで書くメモアプリです。変更は自動で保存されます。"
        add(lines, p, BlockType.PARAGRAPH, intro, listOf(mark(intro, "自動で保存", Mark.Style.BOLD)))
        add(lines, p, BlockType.H2, "書き方", emptyList())
        add(lines, p, BlockType.BULLET, "行頭に「# 」「- 」「[] 」「> 」などを打つと、ブロックの種類が変わります", emptyList())
        add(lines, p, BlockType.BULLET, "「/」を打つとブロックの種類を選べます", emptyList())
        add(lines, p, BlockType.BULLET, "Tab / Shift+Tab で字下げ、左端の ⋮⋮ をドラッグして並べ替え", emptyList())
        val styles = "文字を選んで Cmd/Ctrl+B で太字、+I で斜体、+E でコード、+Shift+S で取り消し線"
        add(lines, p, BlockType.BULLET, styles, listOf(
            mark(styles, "太字", Mark.Style.BOLD),
            mark(styles, "斜体", Mark.Style.ITALIC),
            mark(styles, "コード", Mark.Style.CODE),
            mark(styles, "取り消し線", Mark.Style.STRIKE),
        ))
        add(lines, p, BlockType.H2, "便利なキー", emptyList())
        add(lines, p, BlockType.TODO, "Cmd/Ctrl+K で全ページを検索する", emptyList())
        add(lines, p, BlockType.TODO, "Cmd/Ctrl+N で新しいページを作る", emptyList())
        add(lines, p, BlockType.TODO, "Cmd/Ctrl+Shift+D で今日の日記を開く", emptyList())
        add(lines, p, BlockType.DIVIDER, "", emptyList())
        add(lines, p, BlockType.QUOTE, "サイドバーのページを右クリックすると、子ページの追加やゴミ箱への移動ができます。", emptyList())
        blocks.save(lines, emptyList())
    }

    private fun add(out: MutableList<Block>, p: Page, type: BlockType, text: String, marks: List<Mark>) {
        val key = SortKeys.GAP * (out.size + 1)
        out.add(Block.empty(p.id, type, key).withContent(text, marks))
    }

    private fun mark(text: String, part: String, style: Mark.Style): Mark {
        val at = text.indexOf(part)
        return Mark(at, at + part.length, style)
    }
}
