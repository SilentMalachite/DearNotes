package app.dearnotes.ui

import app.dearnotes.model.Page
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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

    @Test
    fun todayButtonTextFormatsGivenDate() {
        assertEquals("📅  今日 · 12月31日", Sidebar.todayButtonText(LocalDate.of(2026, 12, 31)))
        assertEquals("📅  今日 · 1月1日", Sidebar.todayButtonText(LocalDate.of(2027, 1, 1)))
    }

    @Test
    fun lastPageKeyResolvesDynamicallyWithoutStaticCache() {
        assertEquals("lastPageId", MainWindow.lastPageKey(null))
        val first = MainWindow.lastPageKey("/tmp/dearnotes-a")
        val second = MainWindow.lastPageKey("/tmp/dearnotes-b")
        assertTrue(first.startsWith("lastPageId@"))
        assertTrue(second.startsWith("lastPageId@"))
        assertNotEquals(first, second)
    }

    @Test
    fun normalizeIconExtractsSingleGraphemeClusterIncludingZwjAndKeycap() {
        assertNull(PageEditor.normalizeIcon(null))
        assertNull(PageEditor.normalizeIcon(""))
        assertNull(PageEditor.normalizeIcon("   \u3000  "))
        assertEquals("📝", PageEditor.normalizeIcon("📝"))
        assertEquals("📝", PageEditor.normalizeIcon("📝🎉"))
        // ZWJ 合成絵文字（家族・技術者）が codePoints().limit(2) のように途中で切れないこと
        assertEquals("👨‍👩‍👧‍👦", PageEditor.normalizeIcon("👨‍👩‍👧‍👦abc"))
        assertEquals("👩‍💻", PageEditor.normalizeIcon("👩‍💻🔥"))
        // キーキャップ合成絵文字（1 + VS16 + Combining Enclosing Keycap）が壊れないこと
        assertEquals("1️⃣", PageEditor.normalizeIcon("1️⃣2️⃣"))
        // 国旗（Regional Indicator 2 文字）や肌色修飾子・VS16 付き絵文字を保持すること
        assertEquals("🇯🇵", PageEditor.normalizeIcon("🇯🇵🇺🇸"))
        assertEquals("👍🏽", PageEditor.normalizeIcon("👍🏽✨"))
        assertEquals("❤️", PageEditor.normalizeIcon("  ❤️  "))
        // NFD 結合文字も 1 書記素クラスタとして保持し、ASCII は先頭 1 文字のみ
        assertEquals("か\u3099", PageEditor.normalizeIcon("か\u3099き"))
        assertEquals("A", PageEditor.normalizeIcon("AB"))
    }
}
