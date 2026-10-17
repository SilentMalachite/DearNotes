package app.dearnotes.export

import app.dearnotes.strip
import app.dearnotes.stripLeading

import app.dearnotes.model.Mark
import app.dearnotes.model.Mark.Style.BOLD
import app.dearnotes.model.Mark.Style.CODE
import app.dearnotes.model.Mark.Style.ITALIC
import app.dearnotes.model.Mark.Style.LINK
import app.dearnotes.model.Mark.Style.PAGE
import app.dearnotes.model.Mark.Style.STRIKE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class InlineTest {

    @Test
    fun plainTextIsEscaped() {
        assertEquals("a\\*b\\_c\\`d\\~e\\[f\\]g\\<h\\>i\\\\j", Inline.write("a*b_c`d~e[f]g<h>i\\j", emptyList()))
    }

    @Test
    fun japaneseTextStaysAsIs() {
        assertEquals("今日は晴れ。", Inline.write("今日は晴れ。", emptyList()))
    }

    @Test
    fun eachStyleHasItsMarker() {
        assertEquals("**太字**の", Inline.write("太字の", listOf(Mark(0, 2, BOLD))))
        assertEquals("*斜体*の", Inline.write("斜体の", listOf(Mark(0, 2, ITALIC))))
        assertEquals("~~消す~~の", Inline.write("消すの", listOf(Mark(0, 2, STRIKE))))
        assertEquals("`a*b`の", Inline.write("a*bの", listOf(Mark(0, 3, CODE))))
    }

    @Test
    fun nestedMarksCloseInReverseOrder() {
        val marks = listOf(Mark(0, 5, BOLD), Mark(2, 3, ITALIC))
        assertEquals("**あい*う*えお**", Inline.write("あいうえお", marks))
    }

    @Test
    fun overlappingMarksCloseAndReopen() {
        // 0..3 が太字、2..5 が斜体。記号では「****」となって読めないので、HTML のタグにする
        val marks = listOf(Mark(0, 3, BOLD), Mark(2, 5, ITALIC))
        assertEquals("<strong>あい<em>う</em></strong><em>えお</em>", Inline.write("あいうえお", marks))
    }

    @Test
    fun markStartingWithPunctuationInsideAWordUsesTags() {
        // a**(b)**c は CommonMark では太字にならない
        assertEquals("a<strong>(b)</strong>c", Inline.write("a(b)c", listOf(Mark(1, 4, BOLD))))
        assertEquals("<strong>「太字」</strong>の", Inline.write("「太字」の", listOf(Mark(0, 4, BOLD))))
    }

    @Test
    fun markStartingWithPunctuationAfterASpaceKeepsMarkers() {
        assertEquals("a **(b)** c", Inline.write("a (b) c", listOf(Mark(2, 5, BOLD))))
    }

    @Test
    fun boldNextToStrikeInsideAWordUsesTags() {
        // a**~~x~~**b の ** は ~ と a に挟まれて開けない
        val marks = listOf(Mark(1, 2, BOLD), Mark(1, 2, STRIKE))
        assertEquals("a<strong><del>x</del></strong>b", Inline.write("axb", marks))
    }

    @Test
    fun entityLikeTextIsKept() {
        assertEquals("Tom \\&amp; Jerry \\&#65; & a&b", Inline.write("Tom &amp; Jerry &#65; & a&b", emptyList()))
    }

    @Test
    fun exclamationBeforeALinkDoesNotMakeAnImage() {
        assertEquals("\\![example.com](example.com)", Inline.write("!example.com", listOf(Mark(1, 12, LINK))))
        assertEquals("すごい!", Inline.write("すごい!", emptyList()))
    }

    @Test
    fun spacesMoveOutsideTheMarks() {
        assertEquals(" **太字** ", Inline.write(" 太字 ", listOf(Mark(0, 4, BOLD))))
    }

    @Test
    fun markOfOnlySpacesIsDropped() {
        assertEquals("a b", Inline.write("a b", listOf(Mark(1, 2, BOLD))))
    }

    @Test
    fun codeWithBackticksGetsALongerFence() {
        assertEquals("``a`b``", Inline.write("a`b", listOf(Mark(0, 3, CODE))))
        assertEquals("`` `a ``", Inline.write("`a", listOf(Mark(0, 2, CODE))))
    }

    @Test
    fun codeIsNotSplitByOtherMarks() {
        val marks = listOf(Mark(0, 4, CODE), Mark(2, 6, BOLD))
        assertEquals("`abcd`**ef**", Inline.write("abcdef", marks))
    }

    @Test
    fun linkWithSchemeBecomesAutolink() {
        assertEquals(
            "<https://example.com/a_b>",
            Inline.write("https://example.com/a_b", listOf(Mark(0, 23, LINK))),
        )
    }

    @Test
    fun linkWithoutSchemeKeepsItsText() {
        assertEquals(
            "見る: [example.com](example.com)",
            Inline.write("見る: example.com", listOf(Mark(4, 15, LINK))),
        )
    }

    @Test
    fun boldLink() {
        val marks = listOf(Mark(0, 15, LINK), Mark(0, 15, BOLD))
        assertEquals("**<https://a.jp/x>**", Inline.write("https://a.jp/x ", marks).strip())
    }

    @Test
    fun pageLinkBecomesRelativeLinkOrPlainText() {
        val marks = listOf(Mark(2, 4, PAGE, "k"))
        assertEquals("次は[京都](../京都.md)へ", Inline.write("次は京都へ", marks) { _ -> "../京都.md" })
        assertEquals("次は京都へ", Inline.write("次は京都へ", marks, Links.NONE))
        assertEquals("次は京都へ", Inline.write("次は京都へ", marks))
    }

    @Test
    fun urlLinkOverTwoPageLinksKeepsBothPageLinks() {
        val marks = listOf(Mark(0, 4, LINK), Mark(0, 2, PAGE, "k"), Mark(2, 4, PAGE, "o"))
        assertEquals(
            "[京都](京都.md)[大阪](大阪.md)",
            Inline.write("京都大阪", marks) { id -> if (id == "k") "京都.md" else "大阪.md" },
        )
    }

    @Test
    fun pageLinkPathIsEncoded() {
        assertEquals(
            "[a\\*b](a%20%28b%29%20%2325%25.md)",
            Inline.write("a*b", listOf(Mark(0, 3, PAGE, "k"))) { _ -> "a (b) #25%.md" },
        )
        // &copy; を文字参照に読ませない
        assertEquals(
            "[a](A%26copy;B.md)",
            Inline.write("a", listOf(Mark(0, 1, PAGE, "k"))) { _ -> "A&copy;B.md" },
        )
    }

    @Test
    fun exclamationBeforePageLinkDoesNotMakeAnImage() {
        assertEquals(
            "\\![京都](京都.md)",
            Inline.write("!京都", listOf(Mark(1, 3, PAGE, "k"))) { _ -> "京都.md" },
        )
    }

    @Test
    fun boldPageLinkAndAdjacentLinksToDifferentPages() {
        val links = Links { id -> "$id.md" }
        assertEquals(
            "**[京都](k.md)**",
            Inline.write("京都", listOf(Mark(0, 2, PAGE, "k"), Mark(0, 2, BOLD)), links),
        )
        assertEquals(
            "[京都](k.md)[大阪](o.md)",
            Inline.write("京都大阪", listOf(Mark(0, 2, PAGE, "k"), Mark(2, 4, PAGE, "o")), links),
        )
    }

    @Test
    fun marksOutsideTheTextAreClamped() {
        assertEquals("**ab**", Inline.write("ab", listOf(Mark(0, 10, BOLD))))
    }

    @Test
    fun escapeLineStartGuardsBlockSyntax() {
        assertEquals("\\# 見出しではない", Inline.escapeLineStart("# 見出しではない"))
        assertEquals("\\- 項目ではない", Inline.escapeLineStart("- 項目ではない"))
        assertEquals("\\+ 項目ではない", Inline.escapeLineStart("+ 項目ではない"))
        assertEquals("1\\. 番号ではない", Inline.escapeLineStart("1. 番号ではない"))
        assertEquals("12\\) 番号ではない", Inline.escapeLineStart("12) 番号ではない"))
        assertEquals("\\---", Inline.escapeLineStart("---"))
        assertEquals("\\===", Inline.escapeLineStart("==="))
        assertEquals("#タグ", Inline.escapeLineStart("#タグ"))
        assertEquals("-3度", Inline.escapeLineStart("-3度"))
        assertEquals("2026.10.11", Inline.escapeLineStart("2026.10.11"))
        assertEquals("\\# 字下げした見出しではない", Inline.escapeLineStart("  # 字下げした見出しではない"))
    }

    @Test
    fun leadingSpacesDoNotMakeACodeBlock() {
        assertEquals("\\*foo\\*", Inline.escapeLineStart("    \\*foo\\*"))
        assertEquals("\u3000全角の字下げは残す", Inline.escapeLineStart("\u3000全角の字下げは残す"))
    }
}
