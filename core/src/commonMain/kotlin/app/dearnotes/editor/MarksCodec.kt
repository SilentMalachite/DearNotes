package app.dearnotes.editor

import app.dearnotes.isJavaBlank
import app.dearnotes.strip

import app.dearnotes.model.Mark

/**
 * marks 列の JSON: [[start,end,"style"],…]。ページへのリンクだけ4つ目にページの id を持つ。
 * 形が決まっているので手で読む。古い版は要素が3つでない装飾を捨てる。
 */
object MarksCodec {
    fun encode(marks: List<Mark>?): String? {
        if (marks.isNullOrEmpty()) return null
        val sb = StringBuilder("[")
        for (i in marks.indices) {
            val m = marks[i]
            if (i > 0) sb.append(',')
            sb.append('[').append(m.start).append(',').append(m.end)
                .append(",\"").append(m.style.key()).append('"')
            if (m.target != null) sb.append(",\"").append(m.target).append('"')
            sb.append(']')
        }
        return sb.append(']').toString()
    }

    fun decode(json: String?): List<Mark> {
        if (json == null || json.isJavaBlank()) return emptyList()
        val out = ArrayList<Mark>()
        val s = json.strip()
        var i = s.indexOf('[') + 1
        while (true) {
            val open = s.indexOf('[', i)
            if (open < 0) break
            val close = s.indexOf(']', open)
            if (close < 0) break
            // Java の split と同じく、末尾の空の要素は捨てる
            val parts = s.substring(open + 1, close).split(",").dropLastWhile { it.isEmpty() }
            i = close + 1
            if (parts.size != 3 && parts.size != 4) continue
            try {
                val start = parts[0].strip().toInt()
                val end = parts[1].strip().toInt()
                val style = parts[2].strip().replace("\"", "")
                val target = if (parts.size == 4) parts[3].strip().replace("\"", "") else null
                out.add(Mark(start, end, Mark.Style.fromKey(style), target))
            } catch (_: IllegalArgumentException) {
                // 知らない装飾や壊れた値は捨てる
            }
        }
        return out.toList()
    }
}
