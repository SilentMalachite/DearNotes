package app.dearnotes.save

import app.dearnotes.model.Block
import app.dearnotes.model.BlockType
import java.io.IOException
import java.sql.SQLException
import java.util.ArrayList
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

internal class AutoSaverTest {

    /** 書かれた内容を覚え、指定回数だけ失敗する。 */
    internal class FakeStore : BlockStore {
        val saves = CopyOnWriteArrayList<List<Block>>()
        val deletes = CopyOnWriteArrayList<List<String>>()
        val failuresLeft = AtomicInteger()

        @Volatile
        var failure: () -> Exception = { SQLException("disk full") }

        override fun save(upserts: Collection<Block>, deletes: Collection<String>) {
            if (failuresLeft.getAndDecrement() > 0) throw failure()
            saves.add(upserts.toList())
            this.deletes.add(deletes.toList())
        }
    }

    private val store = FakeStore()
    private val statuses = CopyOnWriteArrayList<SaveStatus>()
    private var saver: AutoSaver? = null

    @AfterEach
    fun tearDown() {
        saver?.close()
    }

    private fun newSaver(delay: Long, retry: Long): AutoSaver {
        val created = AutoSaver(store, Runnable::run, delay, retry)
        saver = created
        created.setListener { statuses.add(it) }
        return created
    }

    @Test
    fun batchesRapidEditsIntoOneWrite() {
        val saver = newSaver(100L, 1000L)
        val b = block("a")
        saver.changed(b)
        saver.changed(b.withContent("ab", emptyList()))
        saver.changed(b.withContent("abc", emptyList()))
        waitUntil { saver.status() == SaveStatus.SAVED && !store.saves.isEmpty() }
        assertEquals(1, store.saves.size)
        assertEquals("abc", store.saves.first().first().text)
        assertEquals(
            listOf(SaveStatus.DIRTY, SaveStatus.DIRTY, SaveStatus.DIRTY, SaveStatus.SAVING, SaveStatus.SAVED),
            statuses,
        )
    }

    @Test
    fun deleteCancelsPendingUpsert() {
        val saver = newSaver(10_000L, 1000L)
        val b = block("x")
        saver.changed(b)
        saver.deleted(b.id)
        assertTrue(saver.flushAndWait(1L, TimeUnit.SECONDS))
        assertEquals(emptyList<Block>(), store.saves.first())
        assertEquals(listOf(b.id), store.deletes.first())
    }

    @Test
    fun failureKeepsChangesAndRetries() {
        store.failuresLeft.set(1)
        val saver = newSaver(10L, 50L)
        val b = block("消えない")
        saver.changed(b)
        waitUntil { statuses.contains(SaveStatus.FAILED) }
        waitUntil { saver.status() == SaveStatus.SAVED }
        assertEquals("消えない", store.saves.first().first().text)
    }

    @Test
    fun checkedFailureOtherThanSqlAlsoRetries() {
        store.failuresLeft.set(1)
        store.failure = { IOException("ディスクが外れた") }
        val saver = newSaver(10L, 50L)
        saver.changed(block("消えない"))
        waitUntil { statuses.contains(SaveStatus.FAILED) }
        waitUntil { saver.status() == SaveStatus.SAVED }
        assertEquals("消えない", store.saves.first().first().text)
    }

    @Test
    fun retryDoesNotOverwriteNewerEdit() {
        store.failuresLeft.set(1)
        val saver = newSaver(10L, 200L)
        val b = block("古い")
        saver.changed(b)
        waitUntil { saver.status() == SaveStatus.FAILED }
        saver.changed(b.withContent("新しい", emptyList()))
        waitUntil { saver.status() == SaveStatus.SAVED }
        val written = ArrayList<String>()
        store.saves.forEach { batch -> batch.forEach { x -> written.add(x.text) } }
        assertEquals(listOf("新しい"), written)
    }

    @Test
    fun closeFlushesPendingChanges() {
        val saver = newSaver(60_000L, 1000L)
        saver.changed(block("終了直前"))
        saver.close()
        assertEquals("終了直前", store.saves.first().first().text)
        assertFalse(saver.hasPending())
    }

    private fun block(text: String): Block {
        return Block.empty("page", BlockType.PARAGRAPH, 1.0).withContent(text, emptyList())
    }

    private fun waitUntil(cond: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!cond()) {
            if (System.nanoTime() > deadline) fail<Nothing>("timed out")
            Thread.sleep(5)
        }
    }
}
