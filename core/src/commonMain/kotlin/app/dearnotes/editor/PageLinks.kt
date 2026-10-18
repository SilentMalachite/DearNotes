package app.dearnotes.editor

/** 「[[」の後ろに打ったページ名を読む。 */
object PageLinks {
    /** 「[[」の後ろで打っているページ名。from は「[[」の位置。 */
    data class Query(val from: Int, val text: String)

    // 日本語入力では [[ が「「 や ［［ になる。閉じ括弧か改行があれば候補を閉じる
    private val open = Regex("(?:\\[\\[|「「|［［)([^\\[\\]「」［］\\n]{0,40})$")

    /** caret の前が「[[語句」なら、その位置と語句。そうでなければ空。 */
    fun queryAt(text: String, caret: Int): Query? {
        if (caret < 0 || caret > text.length) return null
        val m = open.find(text.substring(0, caret)) ?: return null
        return Query(m.range.first, m.groupValues[1])
    }
}
