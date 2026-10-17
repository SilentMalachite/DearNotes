package app.dearnotes.editor

import app.dearnotes.model.Block
import app.dearnotes.model.BlockType
import app.dearnotes.model.BlockType.BULLET
import app.dearnotes.model.BlockType.H1
import app.dearnotes.model.BlockType.H2
import app.dearnotes.model.BlockType.H3
import app.dearnotes.model.BlockType.PARAGRAPH
import app.dearnotes.model.BlockType.TODO
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OutlineTest {
    private fun b(t: BlockType, indent: Int, text: String) =
        Block.empty("p", t, 0.0).withIndent(indent).withContent(text, emptyList())

    private fun folded(t: BlockType, indent: Int, text: String) = b(t, indent, text).withCollapsed(true)

    // 京都旅行(H1) / 持ち物(H2) / 充電器 / 1日目(H2) / 午前(H3) / 伏見稲荷 / 午後(H3) / 清水寺 / 2日目(H2)
    private val trip = listOf(
        b(H1, 0, "京都旅行"),
        b(H2, 0, "持ち物"),
        b(BULLET, 0, "充電器"),
        b(H2, 0, "1日目"),
        b(H3, 0, "午前"),
        b(PARAGRAPH, 0, "伏見稲荷へ行く"),
        b(H3, 0, "午後"),
        b(PARAGRAPH, 0, "清水寺から祇園まで歩く"),
        b(H2, 0, "2日目"),
    )

    @Test
    fun headingSectionEndsBeforeSameOrHigherHeading() {
        assertEquals(9, Outline.end(trip, 0))
        assertEquals(3, Outline.end(trip, 1))
        assertEquals(8, Outline.end(trip, 3))
        assertEquals(6, Outline.end(trip, 4))
        assertEquals(9, Outline.end(trip, 8))
        assertFalse(Outline.hasChildren(trip, 8))
        assertTrue(Outline.hasChildren(trip, 3))
    }

    @Test
    fun itemRangeIsFollowingDeeperRows() {
        val list = listOf(
            b(BULLET, 0, "読む本"),
            b(BULLET, 1, "古都"),
            b(BULLET, 2, "上巻"),
            b(BULLET, 1, "檸檬"),
            b(TODO, 0, "電池を替える"),
            b(PARAGRAPH, 0, "おわり"),
        )
        assertEquals(4, Outline.end(list, 0))
        assertEquals(3, Outline.end(list, 1))
        assertTrue(Outline.hasChildren(list, 1))
        assertFalse(Outline.hasChildren(list, 3))
        assertFalse(Outline.hasChildren(list, 4))
        assertFalse(Outline.hasChildren(list, 5))
    }

    @Test
    fun indentedHeadingSectionEndsAtShallowerRow() {
        val list = listOf(
            b(BULLET, 0, "旅の記録"),
            b(H3, 1, "午前"),
            b(PARAGRAPH, 1, "伏見稲荷"),
            b(PARAGRAPH, 2, "千本鳥居"),
            b(PARAGRAPH, 0, "おわり"),
            b(PARAGRAPH, 1, "あとがき"),
        )
        assertEquals(4, Outline.end(list, 1))
        assertEquals(4, Outline.end(list, 0))
    }

    @Test
    fun headingSectionContainsHeadingsIndentedUnderItsRows() {
        val list = listOf(
            b(H2, 0, "1日目"),
            b(BULLET, 0, "お昼"),
            b(H2, 1, "錦市場"),
            b(PARAGRAPH, 1, "だし巻き"),
            b(H1, 0, "2日目"),
        )
        assertEquals(4, Outline.end(list, 0))
        assertEquals(4, Outline.end(list, 1))
        assertEquals(4, Outline.end(list, 2))
    }

    @Test
    fun hiddenRowsAreInsideCollapsedRanges() {
        val list = listOf(
            folded(H2, 0, "1日目"),
            b(PARAGRAPH, 0, "伏見稲荷"),
            folded(BULLET, 0, "お昼"),
            b(BULLET, 1, "錦市場"),
            b(H2, 0, "2日目"),
            folded(BULLET, 0, "嵐山"),
            b(BULLET, 1, "竹林"),
            b(PARAGRAPH, 0, "帰る"),
        )
        val hidden = Outline.hidden(list)
        assertArrayEquals(booleanArrayOf(false, true, true, true, false, false, true, false), hidden)
    }

    @Test
    fun hidersAreCollapsedRowsCoveringTheRow() {
        val list = listOf(
            folded(H2, 0, "1日目"),
            folded(BULLET, 0, "お昼"),
            b(BULLET, 1, "錦市場"),
            b(H2, 0, "2日目"),
        )
        assertEquals(listOf(0, 1), Outline.hiders(list, 2))
        assertEquals(listOf(0), Outline.hiders(list, 1))
        assertEquals(emptyList<Int>(), Outline.hiders(list, 3))
    }

    @Test
    fun headingsListH1ToH3WithLevels() {
        val heads = Outline.headings(trip)
        assertEquals(listOf("京都旅行", "持ち物", "1日目", "午前", "午後", "2日目"), heads.map { it.text })
        assertEquals(listOf(1, 2, 2, 3, 3, 2), heads.map { it.level })
        assertEquals(trip[4].id, heads[3].blockId)
    }

    @Test
    fun currentHeadingIsNearestHeadingAtOrAbove() {
        assertEquals(4, Outline.currentHeading(trip, 5))
        assertEquals(3, Outline.currentHeading(trip, 3))
        assertEquals(-1, Outline.currentHeading(listOf(b(PARAGRAPH, 0, "前書き")), 0))
    }
}
