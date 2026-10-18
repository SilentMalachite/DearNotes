package app.dearnotes.editor

import app.dearnotes.model.Block
import app.dearnotes.model.BlockType
import kotlin.math.min

/**
 * 畳む範囲と目次の計算。UI から切り離してテストする。
 * 見出しの範囲は、字下げが自分以下の同じか上の段の見出しか、字下げが自分より浅い行の手前まで。
 * こうすると範囲どうしは入れ子か別々になり、途中で交わらない。
 * それ以外の行の範囲は、字下げが自分より深い行が続くところまで。
 */
object Outline {
    /** 目次の1項目。index はページの中の行の位置。 */
    data class Heading(val index: Int, val blockId: String, val level: Int, val text: String)

    /** H1〜H3 なら 1〜3、それ以外は 0。 */
    fun level(t: BlockType) = when (t) {
        BlockType.H1 -> 1
        BlockType.H2 -> 2
        BlockType.H3 -> 3
        else -> 0
    }

    /** index の行の範囲の終わり（その行は含まない）。子の行がなければ index + 1。 */
    fun end(blocks: List<Block>, index: Int): Int {
        val self = blocks[index]
        val lv = level(self.type)
        var i = index + 1
        if (lv > 0) {
            while (i < blocks.size) {
                val b = blocks[i]
                val other = level(b.type)
                // 自分より深く字下げした見出しは、上の行の子なので節を終わらせない
                if (other > 0 && other <= lv && b.indent <= self.indent) break
                // 字下げした見出しは、字下げが浅い行で終わる（親の行の外まで畳まない）
                if (b.indent < self.indent) break
                i++
            }
        } else {
            while (i < blocks.size && blocks[i].indent > self.indent) i++
        }
        return i
    }

    fun hasChildren(blocks: List<Block>, index: Int) = end(blocks, index) > index + 1

    /** 畳んだ行の範囲に入っていて、画面に出さない行。 */
    fun hidden(blocks: List<Block>): BooleanArray {
        val out = BooleanArray(blocks.size)
        for (i in blocks.indices) {
            if (!blocks[i].collapsed) continue
            val e = end(blocks, i)
            for (j in i + 1 until e) out[j] = true
        }
        return out
    }

    /** index の行を隠している畳んだ行の位置。開くとその行が見える。 */
    fun hiders(blocks: List<Block>, index: Int): List<Int> {
        val out = ArrayList<Int>()
        for (i in 0 until index) {
            if (blocks[i].collapsed && end(blocks, i) > index) out.add(i)
        }
        return out
    }

    fun headings(blocks: List<Block>): List<Heading> {
        val out = ArrayList<Heading>()
        for (i in blocks.indices) {
            val b = blocks[i]
            val lv = level(b.type)
            if (lv > 0) out.add(Heading(i, b.id, lv, b.text))
        }
        return out
    }

    /** index の行が入っている節の見出しの位置。前に見出しがなければ -1。 */
    fun currentHeading(blocks: List<Block>, index: Int): Int {
        for (i in min(index, blocks.size - 1) downTo 0) {
            if (level(blocks[i].type) > 0) return i
        }
        return -1
    }
}
