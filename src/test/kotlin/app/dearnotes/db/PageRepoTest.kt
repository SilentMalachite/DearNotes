package app.dearnotes.db

import app.dearnotes.model.Block
import app.dearnotes.model.BlockType
import app.dearnotes.model.Page
import java.sql.SQLException
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class PageRepoTest : DbTestBase() {

    @Test
    fun createAppendsToEndOfSiblings() {
        val a = pages.create(null, "A")
        val b = pages.create(null, "B")
        val child = pages.create(a.id, "A-1")
        assertTrue(a.sortKey < b.sortKey)
        assertEquals(a.id, child.parentId)
        assertEquals(1024.0, child.sortKey)
        assertEquals(3, titles().size)
    }

    @Test
    fun openDailyTwiceCreatesOnePage() {
        val day = LocalDate.of(2026, 10, 11)
        val first = pages.openDaily(day)
        val second = pages.openDaily(day)
        assertEquals(first.id, second.id)
        assertEquals(day, first.dailyDate)
        assertEquals("2026-10-11", first.title)
        assertEquals(1, pages.listAlive().size)
    }

    @Test
    fun openDailyRestoresTrashedDiary() {
        val day = LocalDate.of(2026, 10, 11)
        val p = pages.openDaily(day)
        pages.trash(p.id, 1000L)
        val again = pages.openDaily(day)
        assertEquals(p.id, again.id)
        assertFalse(again.isTrashed())
    }

    @Test
    fun openDailyRestoresChildrenTrashedTogether() {
        val day = LocalDate.of(2026, 10, 11)
        val diary = pages.openDaily(day)
        val child = pages.create(diary.id, "子")
        val earlier = pages.create(diary.id, "先に捨てた子")
        pages.trash(earlier.id, 500L)
        pages.trash(diary.id, 1000L)

        pages.openDaily(day)
        assertFalse((pages.get(child.id) ?: error("missing")).isTrashed())
        assertTrue((pages.get(earlier.id) ?: error("missing")).isTrashed())
    }

    @Test
    fun trashMovesWholeSubtreeAndRestoreBringsItBack() {
        val root = pages.create(null, "親")
        val child = pages.create(root.id, "子")
        val grandchild = pages.create(child.id, "孫")
        pages.create(null, "他")

        pages.trash(root.id, 1000L)
        assertEquals(listOf("他"), titles())
        assertEquals(listOf("親"), pages.listTrash().map { it.title })
        assertTrue((pages.get(grandchild.id) ?: error("missing")).isTrashed())

        pages.restore(root.id)
        assertEquals(4, pages.listAlive().size)
    }

    @Test
    fun restoreKeepsChildrenThatWereTrashedEarlierOnTheirOwn() {
        val parent = pages.create(null, "親")
        val earlier = pages.create(parent.id, "先に捨てた子")
        val together = pages.create(parent.id, "一緒に捨てた子")
        pages.trash(earlier.id, 1000L)
        pages.trash(parent.id, 2000L)

        pages.restore(parent.id)
        assertFalse((pages.get(together.id) ?: error("missing")).isTrashed())
        assertTrue((pages.get(earlier.id) ?: error("missing")).isTrashed())
        assertEquals(listOf("先に捨てた子"), pages.listTrash().map { it.title })
    }

    @Test
    fun restoringChildOfTrashedParentMovesItToTop() {
        val root = pages.create(null, "親")
        val child = pages.create(root.id, "子")
        pages.trash(root.id, 1000L)
        pages.restore(child.id)
        val restored = pages.get(child.id) ?: error("missing")
        assertNull(restored.parentId)
        assertFalse(restored.isTrashed())
    }

    @Test
    fun purgeDeletesOldTrashAndCascadesBlocks() {
        val old = pages.create(null, "古い")
        val recent = pages.create(null, "新しい")
        blocks.save(listOf(Block.empty(old.id, BlockType.PARAGRAPH, 1024.0)), emptyList())
        pages.trash(old.id, 1_000L)
        pages.trash(recent.id, 5_000L)

        assertEquals(1, pages.purgeTrashedBefore(2_000L))
        assertTrue(pages.get(old.id) == null)
        assertTrue(pages.get(recent.id) != null)
        assertEquals(emptyList<Block>(), blocks.listByPage(old.id))
    }

    @Test
    fun moveChangesParentAndRejectsCycles() {
        val a = pages.create(null, "A")
        val b = pages.create(a.id, "B")
        pages.move(a.id, null, 5000.0)
        pages.move(b.id, null, 6000.0)
        assertNull((pages.get(b.id) ?: error("missing")).parentId)

        pages.move(b.id, a.id, 1.0)
        assertThrows(SQLException::class.java) { pages.move(a.id, b.id, 1.0) }
        assertThrows(SQLException::class.java) { pages.move(a.id, a.id, 1.0) }
    }

    @Test
    fun renameAndIcon() {
        val p = pages.create(null, "")
        assertEquals("無題", p.displayTitle())
        pages.rename(p.id, "読書メモ")
        pages.setIcon(p.id, "📚")
        val after = pages.get(p.id) ?: error("missing")
        assertEquals("読書メモ", after.title)
        assertEquals("📚", after.displayIcon())
    }

    @Test
    fun respaceChildrenKeepsOrder() {
        val a = pages.create(null, "A")
        val b = pages.create(null, "B")
        pages.move(b.id, null, a.sortKey + 1e-9)
        pages.respaceChildren(null)
        val alive = pages.listAlive()
        assertEquals(listOf("A", "B"), alive.map { it.title })
        assertEquals(1024.0, alive[1].sortKey - alive[0].sortKey)
    }

    @Test
    fun findByTitlePutsPrefixMatchesFirstAndSkipsTrashAndUntitled() {
        val inn = pages.create(null, "東京の宿")
        val kyoto = pages.create(null, "京都")
        val gone = pages.create(null, "京都メモ")
        pages.create(null, "")
        pages.trash(gone.id, 1L)
        assertEquals(listOf(kyoto.id, inn.id), pages.findByTitle("京", 8).map { it.id })
        assertEquals(2, pages.findByTitle("", 8).size)
        assertEquals(1, pages.findByTitle("", 1).size)
        assertEquals(emptyList<Page>(), pages.findByTitle("100%", 8))
    }

    @Test
    fun findByTitlePutsExactMatchBeforeTheLimit() {
        val kyoto = pages.create(null, "京都")
        Thread.sleep(5)
        pages.create(null, "京都メモ")
        assertEquals(listOf(kyoto.id), pages.findByTitle("京都", 1).map { it.id })
    }

    private fun titles(): List<String?> = pages.listAlive().map { it.title }
}
