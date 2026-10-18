package app.dearnotes.editor

import app.dearnotes.model.Block
import app.dearnotes.model.BlockType
import app.dearnotes.model.BlockType.NUMBERED
import app.dearnotes.model.BlockType.PARAGRAPH
import kotlin.math.nextUp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BlockOpsTest {
    private fun b(t: BlockType, indent: Int, key: Double) = Block.empty("p", t, key).withIndent(indent)

    @Test
    fun numbersConsecutiveItemsAndRestartsAfterOtherBlocks() {
        val list = listOf(
            b(NUMBERED, 0, 1.0), b(NUMBERED, 0, 2.0), b(PARAGRAPH, 0, 3.0), b(NUMBERED, 0, 4.0),
        )
        assertEquals(1, BlockOps.listNumber(list, 0))
        assertEquals(2, BlockOps.listNumber(list, 1))
        assertEquals(1, BlockOps.listNumber(list, 3))
    }

    @Test
    fun nestedItemsHaveTheirOwnNumbering() {
        val list = listOf(
            b(NUMBERED, 0, 1.0), b(NUMBERED, 1, 2.0), b(NUMBERED, 1, 3.0), b(NUMBERED, 0, 4.0),
        )
        assertEquals(1, BlockOps.listNumber(list, 1))
        assertEquals(2, BlockOps.listNumber(list, 2))
        assertEquals(2, BlockOps.listNumber(list, 3))
    }

    @Test
    fun keyAfterPicksMidpointOrAppends() {
        val list = listOf(b(PARAGRAPH, 0, 1024.0), b(PARAGRAPH, 0, 2048.0))
        assertEquals(1536.0, BlockOps.keyAfter(list, 0))
        assertEquals(2560.0, BlockOps.keyAfter(list, 1))
        assertEquals(512.0, BlockOps.keyAfter(list, -1))
    }

    @Test
    fun keysForMoveSpreadsGroupBetweenNeighbours() {
        val list = listOf(
            b(PARAGRAPH, 0, 1024.0), b(PARAGRAPH, 1, 2048.0), b(PARAGRAPH, 1, 3072.0),
            b(PARAGRAPH, 0, 4096.0),
        )
        // 先頭の3行をまとめて末尾へ
        assertEquals(listOf(5120.0, 6144.0, 7168.0), BlockOps.keysForMove(list, 0, 3, 1))
        // 末尾を先頭へ（1行だけ）
        assertEquals(listOf(512.0), BlockOps.keysForMove(list, 3, 1, 0))
        // 2〜3行目を先頭へ
        assertEquals(listOf(1024.0 / 3, 2048.0 / 3), BlockOps.keysForMove(list, 1, 2, 0))
    }

    @Test
    fun keysForMoveReturnsNullWhenNoRoom() {
        val list = listOf(b(PARAGRAPH, 0, 1.0), b(PARAGRAPH, 0, 1.0.nextUp()), b(PARAGRAPH, 0, 5.0))
        assertNull(BlockOps.keysForMove(list, 2, 1, 1))
    }
}
