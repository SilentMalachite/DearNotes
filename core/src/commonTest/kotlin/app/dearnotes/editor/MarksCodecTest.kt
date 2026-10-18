package app.dearnotes.editor

import app.dearnotes.model.Mark
import app.dearnotes.model.Mark.Style
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MarksCodecTest {
    @Test
    fun encodesMarksAsCompactJson() {
        val marks = listOf(Mark(0, 5, Style.BOLD), Mark(6, 9, Style.LINK))
        assertEquals("[[0,5,\"bold\"],[6,9,\"link\"]]", MarksCodec.encode(marks))
    }

    @Test
    fun emptyListEncodesToNull() {
        assertNull(MarksCodec.encode(emptyList()))
    }

    @Test
    fun roundTrips() {
        val marks = listOf(Mark(1, 3, Style.ITALIC), Mark(2, 8, Style.STRIKE), Mark(0, 1, Style.CODE))
        assertEquals(marks, MarksCodec.decode(MarksCodec.encode(marks)))
    }

    @Test
    fun decodesNullAndBlankAsEmpty() {
        assertEquals(emptyList<Mark>(), MarksCodec.decode(null))
        assertEquals(emptyList<Mark>(), MarksCodec.decode(""))
        assertEquals(emptyList<Mark>(), MarksCodec.decode("[]"))
    }

    @Test
    fun decodeToleratesWhitespaceAndSkipsUnknownStyles() {
        assertEquals(
            listOf(Mark(0, 2, Style.BOLD)),
            MarksCodec.decode(" [ [0, 2, \"bold\"], [1,2,\"sparkle\"] ] "),
        )
    }

    @Test
    fun pageLinkKeepsItsTarget() {
        val marks = listOf(Mark(2, 4, Style.PAGE, "0192f3c1-aaaa"))
        assertEquals("[[2,4,\"page\",\"0192f3c1-aaaa\"]]", MarksCodec.encode(marks))
        assertEquals(marks, MarksCodec.decode(MarksCodec.encode(marks)))
    }

    @Test
    fun pageLinkWithoutTargetIsDropped() {
        assertEquals(listOf(Mark(0, 1, Style.BOLD)), MarksCodec.decode("[[0,1,\"bold\"],[2,4,\"page\"]]"))
        assertEquals(emptyList<Mark>(), MarksCodec.decode("[[0,1,\"bold\",\"x\"]]"))
    }

    @Test
    fun trailingCommaIsIgnored() {
        assertEquals(listOf(Mark(0, 3, Style.BOLD)), MarksCodec.decode("[[0,3,\"bold\",]]"))
        assertEquals(listOf(Mark(0, 3, Style.PAGE, "id")), MarksCodec.decode("[[0,3,\"page\",\"id\",]]"))
    }
}
