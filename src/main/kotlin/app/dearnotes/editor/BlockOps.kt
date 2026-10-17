package app.dearnotes.editor

import app.dearnotes.model.Block
import app.dearnotes.model.BlockType
import app.dearnotes.model.SortKeys

/** ページの中のブロックの並びについての計算。UI から切り離してテストする。 */
object BlockOps {
    /** index の番号付きリストの番号。同じ字下げで続く番号付きを数える。 */
    fun listNumber(blocks: List<Block>, index: Int): Int {
        val self = blocks[index]
        var n = 1
        for (i in index - 1 downTo 0) {
            val b = blocks[i]
            if (b.indent > self.indent) continue
            if (b.indent < self.indent) break
            if (b.type != BlockType.NUMBERED) break
            n++
        }
        return n
    }

    /** index の直後に入れるキー。間がなければ null（全体を振り直す）。 */
    fun keyAfter(blocks: List<Block>, index: Int): Double? {
        val prev = if (index >= 0) blocks[index].sortKey else null
        val next = if (index + 1 < blocks.size) blocks[index + 1].sortKey else null
        return SortKeys.between(prev, next)
    }

    /**
     * from から count 行を取り除いてから、残りの並びで insertAt の位置にまとめて入れるためのキー。
     * 間に収まらなければ null（全体を振り直す）。
     */
    fun keysForMove(blocks: List<Block>, from: Int, count: Int, insertAt: Int): List<Double>? {
        val rest = ArrayList(blocks)
        rest.subList(from, from + count).clear()
        val target = insertAt.coerceIn(0, rest.size)
        val lo = if (target > 0) rest[target - 1].sortKey else 0.0
        val hi = if (target < rest.size) rest[target].sortKey else lo + SortKeys.GAP * (count + 1)
        val keys = ArrayList<Double>(count)
        var prev = lo
        for (i in 0 until count) {
            val k = lo + (hi - lo) * (i + 1) / (count + 1)
            if (k <= prev || k >= hi) return null
            keys.add(k)
            prev = k
        }
        return keys
    }
}
