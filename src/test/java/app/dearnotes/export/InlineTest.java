package app.dearnotes.export;

import static app.dearnotes.model.Mark.Style.*;
import static org.junit.jupiter.api.Assertions.*;

import app.dearnotes.model.Mark;
import java.util.List;
import org.junit.jupiter.api.Test;

class InlineTest {

    @Test
    void plainTextIsEscaped() {
        assertEquals("a\\*b\\_c\\`d\\~e\\[f\\]g\\<h\\>i\\\\j", Inline.write("a*b_c`d~e[f]g<h>i\\j", List.of()));
    }

    @Test
    void japaneseTextStaysAsIs() {
        assertEquals("今日は晴れ。", Inline.write("今日は晴れ。", List.of()));
    }

    @Test
    void eachStyleHasItsMarker() {
        assertEquals("**太字**の", Inline.write("太字の", List.of(new Mark(0, 2, BOLD))));
        assertEquals("*斜体*の", Inline.write("斜体の", List.of(new Mark(0, 2, ITALIC))));
        assertEquals("~~消す~~の", Inline.write("消すの", List.of(new Mark(0, 2, STRIKE))));
        assertEquals("`a*b`の", Inline.write("a*bの", List.of(new Mark(0, 3, CODE))));
    }

    @Test
    void nestedMarksCloseInReverseOrder() {
        var marks = List.of(new Mark(0, 5, BOLD), new Mark(2, 3, ITALIC));
        assertEquals("**あい*う*えお**", Inline.write("あいうえお", marks));
    }

    @Test
    void overlappingMarksCloseAndReopen() {
        // 0..3 が太字、2..5 が斜体。記号では「****」となって読めないので、HTML のタグにする
        var marks = List.of(new Mark(0, 3, BOLD), new Mark(2, 5, ITALIC));
        assertEquals("<strong>あい<em>う</em></strong><em>えお</em>", Inline.write("あいうえお", marks));
    }

    @Test
    void markStartingWithPunctuationInsideAWordUsesTags() {
        // a**(b)**c は CommonMark では太字にならない
        assertEquals("a<strong>(b)</strong>c", Inline.write("a(b)c", List.of(new Mark(1, 4, BOLD))));
        assertEquals("<strong>「太字」</strong>の", Inline.write("「太字」の", List.of(new Mark(0, 4, BOLD))));
    }

    @Test
    void markStartingWithPunctuationAfterASpaceKeepsMarkers() {
        assertEquals("a **(b)** c", Inline.write("a (b) c", List.of(new Mark(2, 5, BOLD))));
    }

    @Test
    void boldNextToStrikeInsideAWordUsesTags() {
        // a**~~x~~**b の ** は ~ と a に挟まれて開けない
        var marks = List.of(new Mark(1, 2, BOLD), new Mark(1, 2, STRIKE));
        assertEquals("a<strong><del>x</del></strong>b", Inline.write("axb", marks));
    }

    @Test
    void entityLikeTextIsKept() {
        assertEquals("Tom \\&amp; Jerry \\&#65; & a&b", Inline.write("Tom &amp; Jerry &#65; & a&b", List.of()));
    }

    @Test
    void exclamationBeforeALinkDoesNotMakeAnImage() {
        assertEquals("\\![example.com](example.com)", Inline.write("!example.com", List.of(new Mark(1, 12, LINK))));
        assertEquals("すごい!", Inline.write("すごい!", List.of()));
    }

    @Test
    void spacesMoveOutsideTheMarks() {
        assertEquals(" **太字** ", Inline.write(" 太字 ", List.of(new Mark(0, 4, BOLD))));
    }

    @Test
    void markOfOnlySpacesIsDropped() {
        assertEquals("a b", Inline.write("a b", List.of(new Mark(1, 2, BOLD))));
    }

    @Test
    void codeWithBackticksGetsALongerFence() {
        assertEquals("``a`b``", Inline.write("a`b", List.of(new Mark(0, 3, CODE))));
        assertEquals("`` `a ``", Inline.write("`a", List.of(new Mark(0, 2, CODE))));
    }

    @Test
    void codeIsNotSplitByOtherMarks() {
        var marks = List.of(new Mark(0, 4, CODE), new Mark(2, 6, BOLD));
        assertEquals("`abcd`**ef**", Inline.write("abcdef", marks));
    }

    @Test
    void linkWithSchemeBecomesAutolink() {
        assertEquals("<https://example.com/a_b>",
            Inline.write("https://example.com/a_b", List.of(new Mark(0, 23, LINK))));
    }

    @Test
    void linkWithoutSchemeKeepsItsText() {
        assertEquals("見る: [example.com](example.com)",
            Inline.write("見る: example.com", List.of(new Mark(4, 15, LINK))));
    }

    @Test
    void boldLink() {
        var marks = List.of(new Mark(0, 15, LINK), new Mark(0, 15, BOLD));
        assertEquals("**<https://a.jp/x>**", Inline.write("https://a.jp/x ", marks).strip());
    }

    @Test
    void marksOutsideTheTextAreClamped() {
        assertEquals("**ab**", Inline.write("ab", List.of(new Mark(0, 10, BOLD))));
    }

    @Test
    void escapeLineStartGuardsBlockSyntax() {
        assertEquals("\\# 見出しではない", Inline.escapeLineStart("# 見出しではない"));
        assertEquals("\\- 項目ではない", Inline.escapeLineStart("- 項目ではない"));
        assertEquals("\\+ 項目ではない", Inline.escapeLineStart("+ 項目ではない"));
        assertEquals("1\\. 番号ではない", Inline.escapeLineStart("1. 番号ではない"));
        assertEquals("12\\) 番号ではない", Inline.escapeLineStart("12) 番号ではない"));
        assertEquals("\\---", Inline.escapeLineStart("---"));
        assertEquals("\\===", Inline.escapeLineStart("==="));
        assertEquals("#タグ", Inline.escapeLineStart("#タグ"));
        assertEquals("-3度", Inline.escapeLineStart("-3度"));
        assertEquals("2026.10.11", Inline.escapeLineStart("2026.10.11"));
        assertEquals("\\# 字下げした見出しではない", Inline.escapeLineStart("  # 字下げした見出しではない"));
    }

    @Test
    void leadingSpacesDoNotMakeACodeBlock() {
        assertEquals("\\*foo\\*", Inline.escapeLineStart("    \\*foo\\*"));
        assertEquals("\u3000全角の字下げは残す", Inline.escapeLineStart("\u3000全角の字下げは残す"));
    }
}
