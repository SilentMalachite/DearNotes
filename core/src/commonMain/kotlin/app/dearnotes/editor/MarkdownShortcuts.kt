package app.dearnotes.editor

import app.dearnotes.model.BlockType

/** 行頭に打つと種類が変わる記号。 */
object MarkdownShortcuts {
    data class Hit(val type: BlockType, val prefixLength: Int)

    internal fun match(prefix: String): BlockType? = when (prefix) {
        "# " -> BlockType.H1
        "## " -> BlockType.H2
        "### " -> BlockType.H3
        "- ", "* " -> BlockType.BULLET
        "1. " -> BlockType.NUMBERED
        "[] " -> BlockType.TODO
        "> " -> BlockType.QUOTE
        "```" -> BlockType.CODE
        "---" -> BlockType.DIVIDER
        else -> null
    }

    /** 入力直後の caret の位置までが記号だけなら、その種類を返す。 */
    fun detect(text: String, caret: Int): Hit? {
        if (caret < 2 || caret > 4 || caret > text.length) return null
        val type = match(halfWidth(text.substring(0, caret))) ?: return null
        return Hit(type, caret)
    }

    /** 日本語入力のまま打った「＃　」「ー　」なども同じに扱う。1文字は1文字のまま。 */
    internal fun halfWidth(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            if (c in '\uFF01'..'\uFF5E') sb.append((c.code - 0xFEE0).toChar())
            else if (c == '\u3000') sb.append(' ')
            else if (c == 'ー' || c == '－' || c == '―') sb.append('-')
            else if (c == '「' || c == '【') sb.append('[')
            else if (c == '」' || c == '】') sb.append(']')
            else if (c == '。') sb.append('.')
            else sb.append(c)
        }
        return sb.toString()
    }
}
