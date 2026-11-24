package app.dearnotes.db

import app.dearnotes.model.Block
import app.dearnotes.model.BlockType
import app.dearnotes.model.Mark
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class BlockRepoTest : DbTestBase() {

    @Test
    fun savesAndLoadsBlocksInSortOrder() {
        val p = pages.create(null, "買い物リスト")
        val second = Block("b2", p.id, BlockType.TODO, "電池", emptyList(), true, 1, 2048.0, false)
        val first = Block(
            "b1",
            p.id,
            BlockType.H1,
            "週末",
            listOf(Mark(0, 2, Mark.Style.BOLD)),
            false,
            0,
            1024.0,
            false,
        )
        blocks.save(listOf(second, first), emptyList())
        assertEquals(listOf(first, second), blocks.listByPage(p.id))
    }

    @Test
    fun upsertUpdatesOnlyChangedRow() {
        val p = pages.create(null, "x")
        val b = Block.empty(p.id, BlockType.PARAGRAPH, 1024.0).withContent("牛乳", emptyList())
        blocks.save(listOf(b), emptyList())
        val edited = b.withContent("牛乳 2本", emptyList()).withType(BlockType.BULLET)
        blocks.save(listOf(edited), emptyList())
        assertEquals(listOf(edited), blocks.listByPage(p.id))
    }

    @Test
    fun deletesInSameTransaction() {
        val p = pages.create(null, "x")
        val a = Block.empty(p.id, BlockType.PARAGRAPH, 1024.0)
        val b = Block.empty(p.id, BlockType.PARAGRAPH, 2048.0)
        blocks.save(listOf(a, b), emptyList())
        blocks.save(emptyList(), listOf(a.id))
        assertEquals(listOf(b), blocks.listByPage(p.id))
    }

    @Test
    fun blocksOfDeletedPageAreSkippedInsteadOfFailing() {
        val p = pages.create(null, "x")
        pages.deleteForever(p.id)
        assertDoesNotThrow {
            blocks.save(listOf(Block.empty(p.id, BlockType.PARAGRAPH, 1.0)), emptyList())
        }
        assertEquals(emptyList<Block>(), blocks.listByPage(p.id))
    }

    @Test
    fun savingBlocksTouchesPage() {
        val p = pages.create(null, "x")
        // 実時間スリープを避けるため、保存前に古い updated_at を明示的に設定する
        db.write { c ->
            c.prepareStatement("UPDATE pages SET updated_at = ? WHERE id = ?").use { ps ->
                ps.setLong(1, 1_000L)
                ps.setString(2, p.id)
                ps.executeUpdate()
            }
        }
        blocks.save(listOf(Block.empty(p.id, BlockType.PARAGRAPH, 1.0)), emptyList())
        assertTrue((pages.get(p.id) ?: error("missing")).updatedAt > 1_000L)
    }

    @Test
    fun deletingBlocksAloneTouchesPage() {
        val p = pages.create(null, "x")
        val b = Block.empty(p.id, BlockType.PARAGRAPH, 1.0)
        blocks.save(listOf(b), emptyList())
        // 削除前後での updated_at 更新を確実に判定できるよう古い時刻へ戻す
        db.write { c ->
            c.prepareStatement("UPDATE pages SET updated_at = ? WHERE id = ?").use { ps ->
                ps.setLong(1, 1_000L)
                ps.setString(2, p.id)
                ps.executeUpdate()
            }
        }
        blocks.save(emptyList(), listOf(b.id, "non-existent-block-id"))
        assertTrue((pages.get(p.id) ?: error("missing")).updatedAt > 1_000L)
        assertEquals(emptyList<Block>(), blocks.listByPage(p.id))
    }

    @Test
    fun savesCollapsedState() {
        val p = pages.create(null, "x")
        val b = Block.empty(p.id, BlockType.H2, 1024.0).withContent("1日目", emptyList()).withCollapsed(true)
        blocks.save(listOf(b), emptyList())
        assertTrue(blocks.listByPage(p.id).first().collapsed)
        blocks.save(listOf(b.withCollapsed(false)), emptyList())
        assertFalse(blocks.listByPage(p.id).first().collapsed)
    }

    @Test
    fun linksToFindsBlocksOnAlivePagesOnly() {
        val kyoto = pages.create(null, "京都")
        val trip = pages.create(null, "旅行")
        val old = pages.create(null, "古い")
        val link = listOf(Mark(2, 4, Mark.Style.PAGE, kyoto.id))
        val b = Block.empty(trip.id, BlockType.PARAGRAPH, 1.0).withContent("次は京都へ", link)
        blocks.save(
            listOf(
                b,
                Block.empty(trip.id, BlockType.PARAGRAPH, 2.0).withContent("京都", emptyList()),
                Block.empty(old.id, BlockType.PARAGRAPH, 1.0).withContent("次は京都へ", link),
                Block.empty(kyoto.id, BlockType.PARAGRAPH, 1.0)
                    .withContent("自分", listOf(Mark(0, 2, Mark.Style.PAGE, kyoto.id))),
            ),
            emptyList(),
        )
        pages.trash(old.id, 1L)

        val hits = blocks.linksTo(kyoto.id)
        assertEquals(listOf(b.id), hits.map { it.blockId })
        assertEquals("旅行", hits.first().pageTitle)
        assertEquals("次は京都へ", hits.first().text)
        assertEquals(emptyList<BlockRepo.Backlink>(), blocks.linksTo(trip.id))
    }

    @Test
    fun linksToIsCaseSensitiveAndDoesNotTreatLikeWildcards() {
        val source = pages.create(null, "参照元")
        val exactBlock = Block.empty(source.id, BlockType.PARAGRAPH, 1.0)
            .withContent("exact", listOf(Mark(0, 5, Mark.Style.PAGE, "Page_1")))
        val similarBlock = Block.empty(source.id, BlockType.PARAGRAPH, 2.0)
            .withContent("similar", listOf(Mark(0, 7, Mark.Style.PAGE, "Pagex1")))
        blocks.save(listOf(exactBlock, similarBlock), emptyList())

        // "_" をワイルドカード扱いせず、大小文字も区別すること
        assertEquals(listOf(exactBlock.id), blocks.linksTo("Page_1").map { it.blockId })
        assertEquals(emptyList<BlockRepo.Backlink>(), blocks.linksTo("page_1"))
        assertEquals(emptyList<BlockRepo.Backlink>(), blocks.linksTo("Page%1"))
        assertEquals(listOf(similarBlock.id), blocks.linksTo("Pagex1").map { it.blockId })
    }
}
