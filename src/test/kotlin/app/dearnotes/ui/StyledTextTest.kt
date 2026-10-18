package app.dearnotes.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StyledTextTest {
    @Test
    fun targetOfKeepsTheFirstPageLink() {
        assertEquals("first", StyledText.targetOf(listOf("m-page", "to-first", "to-second")))
        assertEquals("first", StyledText.targetOf(listOf("to-first", "m-page", "to-second")))
    }

    @Test
    fun targetOfIgnoresATargetWithoutAPageClass() {
        assertNull(StyledText.targetOf(listOf("to-first")))
    }
}
