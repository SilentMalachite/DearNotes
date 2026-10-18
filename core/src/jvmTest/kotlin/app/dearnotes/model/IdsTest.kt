package app.dearnotes.model

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdsTest {
    @Test
    fun producesVersion7Uuids() {
        val id = UUID.fromString(Ids.uuidV7())
        assertEquals(7, id.version())
        assertEquals(2, id.variant())
    }

    @Test
    fun idsSortByCreationTime() {
        val a = Ids.uuidV7()
        Thread.sleep(2)
        val b = Ids.uuidV7()
        assertTrue(a < b, "$a < $b")
    }

    @Test
    fun idsAreUnique() {
        assertNotEquals(Ids.uuidV7(), Ids.uuidV7())
    }

    @Test
    fun burstOverOneMillisecondCounterStaysOrderedAndUnique() {
        val ids = List(10_000) { Ids.uuidV7() }
        assertEquals(ids.sorted(), ids)
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertEquals(7, UUID.fromString(it).version()) }
    }

    @Test
    fun concurrentCallersNeverCollide() {
        val all = ConcurrentHashMap.newKeySet<String>()
        val threads = List(8) { Thread { repeat(5_000) { all.add(Ids.uuidV7()) } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(40_000, all.size)
    }
}
