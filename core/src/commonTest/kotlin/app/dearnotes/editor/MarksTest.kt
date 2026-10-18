package app.dearnotes.editor

import app.dearnotes.model.Mark
import app.dearnotes.model.Mark.Style.BOLD
import app.dearnotes.model.Mark.Style.ITALIC
import app.dearnotes.model.Mark.Style.LINK
import app.dearnotes.model.Mark.Style.PAGE
import app.dearnotes.model.Mark.Style.STRIKE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MarksTest {
    @Test
    fun linkAtUsesTheClickedCharacter() {
        val kyoto = Mark(0, 2, PAGE, "k")
        val osaka = Mark(2, 4, PAGE, "o")
        val marks = listOf(Mark(0, 4, BOLD), kyoto, osaka)
        assertEquals(kyoto, Marks.linkAt(marks, 1))
        assertEquals(osaka, Marks.linkAt(marks, 2))
        assertNull(Marks.linkAt(marks, 4))
        assertEquals(Mark(1, 3, LINK), Marks.linkAt(listOf(Mark(1, 3, LINK)), 1))
    }

    @Test
    fun linkAtPrefersPageLinkOverUrlLink() {
        val page = Mark(0, 2, PAGE, "k")
        assertEquals(page, Marks.linkAt(Marks.normalize(listOf(Mark(0, 2, LINK), page)), 0))
    }

    @Test
    fun sliceClipsAndShifts() {
        val marks = listOf(Mark(0, 4, BOLD), Mark(6, 10, ITALIC))
        assertEquals(listOf(Mark(0, 1, BOLD)), Marks.slice(marks, 3, 5))
        assertEquals(listOf(Mark(1, 5, ITALIC)), Marks.slice(marks, 5, 20))
    }

    @Test
    fun concatShiftsSecondPart() {
        val a = listOf(Mark(0, 2, BOLD))
        val b = listOf(Mark(0, 3, ITALIC))
        assertEquals(listOf(Mark(0, 2, BOLD), Mark(4, 7, ITALIC)), Marks.concat(a, 4, b))
    }

    @Test
    fun concatMergesTouchingSameStyle() {
        val a = listOf(Mark(1, 3, BOLD))
        val b = listOf(Mark(0, 2, BOLD))
        assertEquals(listOf(Mark(1, 5, BOLD)), Marks.concat(a, 3, b))
    }

    @Test
    fun splitThenConcatRestoresOriginal() {
        val marks = listOf(Mark(0, 3, BOLD), Mark(2, 8, STRIKE))
        val left = Marks.slice(marks, 0, 5)
        val right = Marks.slice(marks, 5, 10)
        assertEquals(Marks.normalize(marks), Marks.concat(left, 5, right))
    }

    @Test
    fun adjacentLinksToDifferentPagesStaySeparate() {
        val a = Mark(0, 2, PAGE, "a")
        val b = Mark(2, 4, PAGE, "b")
        assertEquals(listOf(a, b), Marks.normalize(listOf(b, a)))
        assertEquals(listOf(Mark(0, 4, PAGE, "a")), Marks.normalize(listOf(a, Mark(2, 4, PAGE, "a"))))
    }

    @Test
    fun sliceAndConcatKeepTheTarget() {
        val link = Mark(1, 5, PAGE, "p")
        assertEquals(listOf(Mark(0, 2, PAGE, "p")), Marks.slice(listOf(link), 3, 8))
        assertEquals(listOf(Mark(4, 8, PAGE, "p")), Marks.concat(emptyList(), 3, listOf(link)))
    }

    @Test
    fun pageNeedsTargetAndOtherStylesMustNotHaveOne() {
        assertFailsWith<IllegalArgumentException> { Mark(0, 1, PAGE) }
        assertFailsWith<IllegalArgumentException> { Mark(0, 1, BOLD, "x") }
    }
}
