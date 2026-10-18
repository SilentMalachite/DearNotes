package app.dearnotes.editor

import app.dearnotes.model.Mark

/** ブロックを分けたり繋げたりするときの装飾の付け替え。 */
object Marks {
    /** [from, to) にかかる装飾を切り出し、from を 0 に揃える。 */
    fun slice(marks: List<Mark>, from: Int, to: Int): List<Mark> {
        val out = ArrayList<Mark>()
        for (m in marks) {
            val s = maxOf(m.start, from)
            val e = minOf(m.end, to)
            if (s < e) out.add(m.withRange(s - from, e - from))
        }
        return normalize(out)
    }

    /**
     * index の文字にかかるリンク。隣り合うリンクを取り違えないよう、end の文字は含めない。
     * ページへのリンクと URL のリンクが重なっていれば、書き出しと同じくページへのリンクを選ぶ。
     */
    fun linkAt(marks: List<Mark>, index: Int): Mark? =
        marks.asSequence()
            .filter { it.style == Mark.Style.PAGE || it.style == Mark.Style.LINK }
            .filter { it.start <= index && index < it.end }
            .minWithOrNull(compareBy { if (it.style == Mark.Style.PAGE) 0 else 1 })

    /** a の後ろに長さ aLength の文字列が続くとして、b をずらして繋げる。 */
    fun concat(a: List<Mark>, aLength: Int, b: List<Mark>): List<Mark> {
        val out = ArrayList(a)
        for (m in b) out.add(m.withRange(m.start + aLength, m.end + aLength))
        return normalize(out)
    }

    /** 空の装飾を捨て、同じ種類（リンクは同じリンク先）で重なる・接するものを1つにまとめる。 */
    fun normalize(marks: List<Mark>): List<Mark> {
        val sorted = marks.asSequence()
            .filter { it.start < it.end }
            .sortedWith(
                compareBy<Mark> { it.style }
                    .thenBy(nullsFirst(naturalOrder<String>())) { it.target }
                    .thenBy { it.start },
            )
            .toList()
        val out = ArrayList<Mark>()
        for (m in sorted) {
            if (out.isNotEmpty()) {
                val last = out.last()
                if (last.style == m.style && last.target == m.target && m.start <= last.end) {
                    out[out.size - 1] = last.withRange(last.start, maxOf(last.end, m.end))
                    continue
                }
            }
            out.add(m)
        }
        out.sortWith(
            compareBy<Mark> { it.start }
                .thenBy { it.style }
                .thenBy(nullsFirst(naturalOrder<String>())) { it.target },
        )
        return out.toList()
    }
}
