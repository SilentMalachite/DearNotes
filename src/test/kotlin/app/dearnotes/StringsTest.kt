package app.dearnotes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StringsTest {
    @Test
    fun whitespaceMatchesJavaForEveryChar() {
        for (code in Char.MIN_VALUE.code..Char.MAX_VALUE.code) {
            val c = code.toChar()
            assertEquals(Character.isWhitespace(c), c.isJavaWhitespace(), "U+%04X".format(code))
        }
    }

    @Test
    fun nbspIsNotBlank() {
        assertFalse("\u00A0".isJavaBlank())
        assertFalse(" \u2007 ".isJavaBlank())
        assertTrue(" \t\u3000\n".isJavaBlank())
        assertTrue("".isJavaBlank())
    }

    @Test
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    fun stripKeepsNbspLikeJava() {
        val s = "\u3000 \u00A0本文\u00A0 \t"
        assertEquals((s as java.lang.String).strip(), s.strip())
        assertEquals((s as java.lang.String).stripLeading(), s.stripLeading())
        assertEquals((s as java.lang.String).stripTrailing(), s.stripTrailing())
    }
}
