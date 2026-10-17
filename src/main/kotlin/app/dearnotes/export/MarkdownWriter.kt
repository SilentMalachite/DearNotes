package app.dearnotes.export

import app.dearnotes.isJavaBlank
import app.dearnotes.strip
import app.dearnotes.stripTrailing

import app.dearnotes.editor.BlockOps
import app.dearnotes.model.Block
import app.dearnotes.model.BlockType
import app.dearnotes.model.Page
import java.util.regex.Pattern
import kotlin.math.max

/**
 * ページを Markdown（CommonMark と GFM のタスクリスト）の文字列にする。
 * 先頭にページ名を「# 」で書く。畳んだ行も書く。改行は LF、末尾に改行を1つ。
 */
object MarkdownWriter {

    /** 見出しの末尾の # は、閉じの記号と読まれる。 */
    private val CLOSING_HASHES: Pattern = Pattern.compile("""(.*\s)(#+\s*)""")

    fun write(page: Page, blocks: List<Block>): String = write(page, blocks, Links.NONE)

    /** links はページへのリンクの行き先。行き先のないページへのリンクは、ふだんの文字にする。 */
    fun write(page: Page, blocks: List<Block>, links: Links): String {
        val out = StringBuilder()
        headingBody(out, "# ", Inline.escape(page.displayTitle().replace('\n', ' ')).strip())
        // 段ごとに、その段の最後の行の子を置く桁。-1 はまだない
        val childColumn = IntArray(Block.MAX_INDENT + 1)
        childColumn.fill(-1)
        var prev: Block? = null
        for (i in blocks.indices) {
            val b = blocks[i]
            if (b.type == BlockType.PARAGRAPH && b.text.isJavaBlank()) {
                prev = b
                continue
            }
            val depth = b.indent
            var base = 0
            for (k in depth - 1 downTo 0) {
                if (childColumn[k] >= 0) {
                    base = childColumn[k]
                    break
                }
            }
            // リストの項目は続けて書き、ほかは空行で分ける（段落が前の行とつながらないように）
            val previous = prev
            val tight = previous != null && previous.type.isList() && b.type.isList()
            if (!tight) out.append('\n')
            val pad = " ".repeat(base)
            if (b.type == BlockType.NUMBERED && continuesNumberedList(blocks, i)) {
                // 画面では空の行の後で 1 から数え直す。CommonMark は空行だけでは同じリストと読むので、間に区切りを入れる
                line(out, pad + "<!-- -->")
                out.append('\n')
            }
            // 子の行を置く桁は、リストの項目なら記号の後ろ
            var width = 0
            when (b.type) {
                BlockType.PARAGRAPH -> text(out, pad, "", "", b, links)
                BlockType.H1 -> heading(out, pad, "# ", b, links)
                BlockType.H2 -> heading(out, pad, "## ", b, links)
                BlockType.H3 -> heading(out, pad, "### ", b, links)
                BlockType.BULLET -> {
                    text(out, pad, "- ", "  ", b, links)
                    width = 2
                }
                BlockType.NUMBERED -> {
                    val marker = BlockOps.listNumber(blocks, i).toString() + ". "
                    width = marker.length
                    text(out, pad, marker, " ".repeat(width), b, links)
                }
                BlockType.TODO -> {
                    // 「[ ] 」は項目の文字の側なので、子は「- 」の後ろにそろえる
                    text(out, pad, if (b.checked) "- [x] " else "- [ ] ", "  ", b, links)
                    width = 2
                }
                BlockType.QUOTE -> text(out, pad, "> ", "> ", b, links)
                BlockType.CODE -> fence(out, pad, b.text)
                BlockType.DIVIDER -> line(out, pad + "---")
            }
            childColumn[depth] = base + width
            for (k in depth + 1 until childColumn.size) childColumn[k] = -1
            prev = b
        }
        return out.toString()
    }

    /** 文字のあるブロック。2行目からは continuation を前に付け、行末の \ で改行する。 */
    private fun text(
        out: StringBuilder,
        pad: String,
        marker: String,
        continuation: String,
        b: Block,
        links: Links,
    ) {
        val lines = trimNewlines(Inline.write(b.text, b.marks, links)).split("\n")
        for (j in lines.indices) {
            val lead = pad + if (j == 0) marker else continuation
            if (j == lines.size - 1) {
                content(out, lead, lines[j])
            } else {
                line(out, lead + Inline.escapeLineStart(lines[j]) + "\\")
            }
        }
    }

    /**
     * 番号を 1 から数え直す項目なのに、Markdown では前の番号付きリストの続きと読まれるか。
     * 間に空の段落しかなく、同じ段の番号付きの項目が前にあるとき。
     */
    private fun continuesNumberedList(blocks: List<Block>, i: Int): Boolean {
        if (BlockOps.listNumber(blocks, i) != 1) return false
        val depth = blocks[i].indent
        for (j in i - 1 downTo 0) {
            val b = blocks[j]
            if (b.type == BlockType.PARAGRAPH && b.text.isJavaBlank()) continue
            if (b.indent > depth) continue // 前の項目の子の行
            return b.indent == depth && b.type == BlockType.NUMBERED
        }
        return false
    }

    private fun heading(out: StringBuilder, pad: String, marker: String, b: Block, links: Links) {
        headingBody(
            out,
            pad + marker,
            trimNewlines(Inline.write(b.text, b.marks, links)).replace('\n', ' '),
        )
    }

    /** 見出しの1行。行頭と末尾の # が、見出しの記号と読まれないようにする。 */
    private fun headingBody(out: StringBuilder, lead: String, body: String) {
        var text = body
        val closing = CLOSING_HASHES.matcher(text)
        if (closing.matches()) text = closing.group(1) + "\\" + closing.group(2)
        content(out, lead, text)
    }

    /** 記号と本文の1行。本文が空なら、記号の後ろの空白を消す。 */
    private fun content(out: StringBuilder, lead: String, body: String) {
        line(out, if (body.isEmpty()) lead.stripTrailing() else lead + Inline.escapeLineStart(body))
    }

    /** 中の ``` より長い囲みで書く。中身は守らない。 */
    private fun fence(out: StringBuilder, pad: String, code: String) {
        var longest = 0
        var run = 0
        for (c in code) {
            run = if (c == '`') run + 1 else 0
            longest = max(longest, run)
        }
        val f = "`".repeat(max(3, longest + 1))
        line(out, pad + f)
        if (!code.isEmpty()) {
            for (l in code.split("\n")) line(out, if (l.isEmpty()) "" else pad + l)
        }
        line(out, pad + f)
    }

    private fun line(out: StringBuilder, s: String) {
        out.append(s).append('\n')
    }

    private fun trimNewlines(s: String): String {
        var start = 0
        var end = s.length
        while (start < end && s[start] == '\n') start++
        while (end > start && s[end - 1] == '\n') end--
        return s.substring(start, end)
    }
}
