package app.dearnotes.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SortKeysTest {
    @Test
    fun betweenTwoKeysReturnsMidpoint() {
        assertEquals(1536.0, SortKeys.between(1024.0, 2048.0))
    }

    @Test
    fun firstKeyInEmptyListIsGap() {
        assertEquals(512.0, SortKeys.between(null, null))
    }

    @Test
    fun afterLastKeyAddsHalfGap() {
        assertEquals(2560.0, SortKeys.between(2048.0, null))
    }

    @Test
    fun beforeFirstKeyUsesZeroAsLowerBound() {
        assertEquals(512.0, SortKeys.between(null, 1024.0))
    }

    @Test
    fun returnsNullWhenGapIsExhausted() {
        val lo = 1.0
        val hi = Math.nextUp(lo)
        assertNull(SortKeys.between(lo, hi))
    }

    @Test
    fun spacedProducesEvenGaps() {
        assertEquals(listOf(1024.0, 2048.0, 3072.0), SortKeys.spaced(3))
    }
}
