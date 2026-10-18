package app.dearnotes.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PageLinksTest {
    @Test
    fun findsQueryAfterBrackets() {
        assertEquals(PageLinks.Query(3, "京"), PageLinks.queryAt("次は [[京", 6))
    }

    @Test
    fun japaneseBracketsWork() {
        assertEquals(PageLinks.Query(0, "京都"), PageLinks.queryAt("「「京都", 4))
        assertEquals(PageLinks.Query(0, ""), PageLinks.queryAt("［［", 2))
    }

    @Test
    fun emptyQueryRightAfterBrackets() {
        assertEquals(PageLinks.Query(1, ""), PageLinks.queryAt("a[[", 3))
    }

    @Test
    fun looksOnlyBeforeCaret() {
        assertEquals(PageLinks.Query(0, "京都"), PageLinks.queryAt("[[京都 ab", 4))
    }

    @Test
    fun closedOrBrokenQueryIsNotAQuery() {
        assertNull(PageLinks.queryAt("[[京都]] へ", 7))
        assertNull(PageLinks.queryAt("[[京\n都", 5))
        assertNull(PageLinks.queryAt("[京", 2))
        assertNull(PageLinks.queryAt("", 0))
    }
}
