package app.dearnotes.ui

import app.dearnotes.model.Page
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SidebarTest {

    private fun page(id: String, sortKey: Double): Page {
        return Page(id, null, id, null, null, sortKey, 0L, 0L, null)
    }

    private val a = page("A", 1024.0)
    private val b = page("B", 2048.0)
    private val alive = listOf(a, b)

    @Test
    fun dropBeforeFirstSiblingGoesAboveIt() {
        val key = Sidebar.keyFor(alive, null, b, a, Sidebar.Drop.BEFORE)
        assertNotNull(key)
        assertTrue(key!! < a.sortKey)
    }

    @Test
    fun dropAfterLastSiblingGoesBelowIt() {
        val key = Sidebar.keyFor(alive, null, a, b, Sidebar.Drop.AFTER)
        assertNotNull(key)
        assertTrue(key!! > b.sortKey)
    }

    @Test
    fun dropBetweenSiblings() {
        val c = page("C", 3072.0)
        val key = Sidebar.keyFor(listOf(a, b, c), null, c, b, Sidebar.Drop.BEFORE)
        assertNotNull(key)
        assertTrue(a.sortKey < key!! && key < b.sortKey)
    }
}
