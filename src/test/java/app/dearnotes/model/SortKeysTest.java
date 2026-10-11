package app.dearnotes.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SortKeysTest {

    @Test
    void betweenTwoKeysReturnsMidpoint() {
        assertEquals(1536.0, SortKeys.between(1024.0, 2048.0));
    }

    @Test
    void firstKeyInEmptyListIsGap() {
        assertEquals(512.0, SortKeys.between(null, null));
    }

    @Test
    void afterLastKeyAddsHalfGap() {
        assertEquals(2560.0, SortKeys.between(2048.0, null));
    }

    @Test
    void beforeFirstKeyUsesZeroAsLowerBound() {
        assertEquals(512.0, SortKeys.between(null, 1024.0));
    }

    @Test
    void returnsNullWhenGapIsExhausted() {
        double lo = 1.0;
        double hi = Math.nextUp(lo);
        assertNull(SortKeys.between(lo, hi));
    }

    @Test
    void spacedProducesEvenGaps() {
        assertEquals(List.of(1024.0, 2048.0, 3072.0), SortKeys.spaced(3));
    }
}
