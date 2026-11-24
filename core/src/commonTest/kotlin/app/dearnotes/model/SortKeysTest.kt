package app.dearnotes.model

import kotlin.math.nextUp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SortKeysTest {
    @Test
    fun betweenTwoKeysReturnsMidpoint() {
        assertEquals(1536.0, SortKeys.between(1024.0, 2048.0))
    }

    @Test
    fun firstKeyInEmptyListIsHalfGap() {
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
        val hi = lo.nextUp()
        assertNull(SortKeys.between(lo, hi))
    }

    @Test
    fun spacedProducesEvenGaps() {
        assertEquals(listOf(1024.0, 2048.0, 3072.0), SortKeys.spaced(3))
    }

    @Test
    fun returnsNullForNonFiniteOrReversedBounds() {
        assertNull(SortKeys.between(Double.NaN, 1024.0))
        assertNull(SortKeys.between(1024.0, Double.NaN))
        assertNull(SortKeys.between(Double.NaN, null))
        assertNull(SortKeys.between(null, Double.NaN))
        assertNull(SortKeys.between(Double.POSITIVE_INFINITY, null))
        assertNull(SortKeys.between(Double.NEGATIVE_INFINITY, 1024.0))
        assertNull(SortKeys.between(1024.0, Double.POSITIVE_INFINITY))
        assertNull(SortKeys.between(2048.0, 1024.0))
        assertNull(SortKeys.between(Double.MAX_VALUE, null))
    }
}
