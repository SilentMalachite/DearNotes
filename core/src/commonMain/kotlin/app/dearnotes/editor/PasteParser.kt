package app.dearnotes.editor

import app.dearnotes.isJavaBlank
import app.dearnotes.stripLeading

import app.dearnotes.model.Block
import app.dearnotes.model.BlockType

/** 貼り付けた複数行の文字列を、Markdown の書き方を見てブロックに分ける。 */
object PasteParser {
    data class Line(val type: BlockType, val text: String, val indent: Int, val checked: Boolean)

    private val task = Regex("^(?:[-*] )?\\[( |x|X)?\\] (.*)$")
    private val numbered = Regex("^\\d+[.)] (.*)$")

    fun parse(text: String): List<Line> {
        // Kotlin の split は末尾の空行も残す（Java の split("\n", -1) と同じ）
        val raw = text.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        val out = ArrayList<Line>()
        var i = 0
        while (i < raw.size) {
            val line = raw[i]
            val body = line.stripLeading()
            val indent = indentOf(line)
            if (body.startsWith("```")) {
                val code = ArrayList<String>()
                var j = i + 1
                while (j < raw.size && !raw[j].stripLeading().startsWith("```")) {
                    code.add(raw[j])
                    j++
                }
                out.add(Line(BlockType.CODE, code.joinToString("\n"), indent, false))
                i = j // 閉じの ``` を飛ばす（なければ末尾まで）
                i++
                continue
            }
            if (body.isJavaBlank()) {
                i++
                continue
            }
            out.add(classify(body, indent))
            i++
        }
        return out
    }

    internal fun classify(body: String, indent: Int): Line {
        if (body == "---" || body == "***" || body == "___") {
            return Line(BlockType.DIVIDER, "", indent, false)
        }
        val taskMatch = task.matchEntire(body)
        if (taskMatch != null) {
            val mark = taskMatch.groupValues[1]
            val checked = mark.equals("x", ignoreCase = true)
            return Line(BlockType.TODO, taskMatch.groupValues[2], indent, checked)
        }
        val num = numbered.matchEntire(body)
        if (num != null) return Line(BlockType.NUMBERED, num.groupValues[1], indent, false)
        for (prefix in listOf("### ", "## ", "# ", "- ", "* ", "> ")) {
            if (body.startsWith(prefix)) {
                val t = MarkdownShortcuts.match(prefix) ?: error(prefix)
                return Line(t, body.substring(prefix.length), indent, false)
            }
        }
        return Line(BlockType.PARAGRAPH, body, indent, false)
    }

    /** タブ1つか空白2つで1段。 */
    private fun indentOf(line: String): Int {
        var spaces = 0
        for (c in line) {
            if (c == ' ') spaces++
            else if (c == '\t') spaces += 2
            else break
        }
        return minOf(spaces / 2, Block.MAX_INDENT)
    }
}
