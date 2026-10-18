package app.dearnotes.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdsAdvanceTest {
    private val now = 1_800_000_000_000L

    @Test
    fun newMillisecondStartsFromTheSeed() {
        assertEquals((now shl 12) or 0x123, Ids.advance(prev = (now - 1) shl 12, now = now, seed = 0x123))
    }

    @Test
    fun sameMillisecondCountsUp() {
        val prev = (now shl 12) or 0x123
        assertEquals(prev + 1, Ids.advance(prev = prev, now = now, seed = 0x7FF))
    }

    @Test
    fun counterOverflowCarriesIntoTheNextMillisecond() {
        var state = Ids.advance(prev = 0L, now = now, seed = 0x7FF)
        val seen = mutableListOf(state)
        repeat(7_000) {
            state = Ids.advance(prev = state, now = now, seed = 0)
            seen.add(state)
        }
        // 0x7FF から数えて 0xFFF を超えるたびに、ミリ秒が 1 つ進み、カウンタは 0 から続く（2047 + 7000 で 2 回繰り上がる）
        val carried = seen.first { it ushr 12 == now + 1 }
        assertEquals(0L, carried and 0xFFF)
        assertEquals(now + 2, seen.last() ushr 12)
        assertTrue(seen.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun clockGoingBackwardsStillCountsUp() {
        val prev = (now shl 12) or 0x10
        assertEquals(prev + 1, Ids.advance(prev = prev, now = now - 5, seed = 0x7FF))
    }
}
