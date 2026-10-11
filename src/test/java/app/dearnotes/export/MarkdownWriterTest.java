package app.dearnotes.export;

import static app.dearnotes.model.BlockType.*;
import static org.junit.jupiter.api.Assertions.*;

import app.dearnotes.model.Block;
import app.dearnotes.model.BlockType;
import app.dearnotes.model.Mark;
import app.dearnotes.model.Page;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownWriterTest {

    private static final Page PAGE = new Page("p", null, "京都", null, null, 0, 0, 0, null);

    private final List<Block> blocks = new ArrayList<>();

    private MarkdownWriterTest add(BlockType type, String text, int indent) {
        return add(type, text, indent, false, List.of());
    }

    private MarkdownWriterTest add(BlockType type, String text, int indent, boolean checked, List<Mark> marks) {
        blocks.add(new Block("b" + blocks.size(), "p", type, text, marks, checked, indent, blocks.size()));
        return this;
    }

    private String write() {
        return MarkdownWriter.write(PAGE, blocks);
    }

    @Test
    void emptyPageIsOnlyTheTitle() {
        assertEquals("# 京都\n", write());
    }

    @Test
    void untitledPageUsesTheDisplayTitle() {
        var page = new Page("p", null, "", null, null, 0, 0, 0, null);
        assertEquals("# 無題\n", MarkdownWriter.write(page, List.of()));
    }

    @Test
    void titleIsEscaped() {
        var page = new Page("p", null, "*注意*", null, null, 0, 0, 0, null);
        assertEquals("# \\*注意\\*\n", MarkdownWriter.write(page, List.of()));
    }

    @Test
    void titleThatLooksLikeHeadingSyntaxIsKept() {
        assertEquals("# \\###\n", MarkdownWriter.write(new Page("p", null, "###", null, null, 0, 0, 0, null), List.of()));
        assertEquals("# Done \\#\n", MarkdownWriter.write(new Page("p", null, "Done #", null, null, 0, 0, 0, null), List.of()));
    }

    @Test
    void blankLineBetweenNumberedListsKeepsThemApart() {
        // 画面では空の行の後でまた 1 から数える。CommonMark では空行だけだと1つのリストになる
        add(NUMBERED, "一", 0).add(PARAGRAPH, "", 0).add(NUMBERED, "二", 0);
        assertEquals("""
            # 京都

            1. 一

            <!-- -->

            1. 二
            """, write());
    }

    @Test
    void breakerInsideAnOuterListIsIndented() {
        add(BULLET, "外", 0).add(NUMBERED, "一", 1).add(PARAGRAPH, "", 0).add(NUMBERED, "二", 1);
        assertEquals("""
            # 京都

            - 外
              1. 一

              <!-- -->

              1. 二
            """, write());
    }

    @Test
    void numberedListAfterAParagraphNeedsNoBreaker() {
        add(NUMBERED, "一", 0).add(PARAGRAPH, "間", 0).add(PARAGRAPH, "", 0).add(NUMBERED, "二", 0);
        assertEquals("# 京都\n\n1. 一\n\n間\n\n1. 二\n", write());
    }

    @Test
    void eachTypeHasItsMarker() {
        add(H1, "1日目", 0).add(H2, "午前", 0).add(H3, "朝", 0)
            .add(PARAGRAPH, "伏見稲荷へ行く", 0)
            .add(QUOTE, "早く行く", 0)
            .add(DIVIDER, "", 0)
            .add(CODE, "let a = 1;\nlet b = 2;", 0);
        assertEquals("""
            # 京都

            # 1日目

            ## 午前

            ### 朝

            伏見稲荷へ行く

            > 早く行く

            ---

            ```
            let a = 1;
            let b = 2;
            ```
            """, write());
    }

    @Test
    void listItemsStayTogether() {
        add(BULLET, "充電器", 0).add(BULLET, "着替え", 0)
            .add(TODO, "宿", 0, true, List.of()).add(TODO, "切符", 0, false, List.of())
            .add(PARAGRAPH, "以上", 0);
        assertEquals("""
            # 京都

            - 充電器
            - 着替え
            - [x] 宿
            - [ ] 切符

            以上
            """, write());
    }

    @Test
    void numbersFollowTheScreen() {
        add(NUMBERED, "準備", 0).add(BULLET, "充電器", 1).add(NUMBERED, "出発", 0)
            .add(PARAGRAPH, "間", 0).add(NUMBERED, "また1から", 0);
        assertEquals("""
            # 京都

            1. 準備
               - 充電器
            2. 出発

            間

            1. また1から
            """, write());
    }

    @Test
    void childrenAlignWithTheParentsText() {
        add(BULLET, "a", 0).add(BULLET, "b", 1).add(TODO, "c", 2, false, List.of())
            .add(BULLET, "d", 3).add(PARAGRAPH, "続き", 1);
        assertEquals("""
            # 京都

            - a
              - b
                - [ ] c
                  - d

              続き
            """, write());
    }

    @Test
    void indentUnderANonListBlockIsDropped() {
        add(PARAGRAPH, "段落", 0).add(BULLET, "項目", 1).add(PARAGRAPH, "下の段落", 2);
        assertEquals("""
            # 京都

            段落

            - 項目

              下の段落
            """, write());
    }

    @Test
    void indentJumpUsesTheNearestParent() {
        add(BULLET, "a", 0).add(BULLET, "深い", 3);
        assertEquals("""
            # 京都

            - a
              - 深い
            """, write());
    }

    @Test
    void lineBreaksInsideABlockAreHardBreaks() {
        add(PARAGRAPH, "1行目\n2行目", 0).add(BULLET, "項目\n続き", 0).add(QUOTE, "引用\n続き", 0);
        assertEquals("""
            # 京都

            1行目\\
            2行目

            - 項目\\
              続き

            > 引用\\
            > 続き
            """, write());
    }

    @Test
    void headingLineBreaksBecomeSpaces() {
        add(H2, "長い\n見出し", 0);
        assertEquals("# 京都\n\n## 長い 見出し\n", write());
    }

    @Test
    void blockSyntaxInTextIsEscaped() {
        add(PARAGRAPH, "# 見出しではない", 0).add(BULLET, "- 入れ子ではない", 0)
            .add(PARAGRAPH, "a\n- b", 0);
        assertEquals("""
            # 京都

            \\# 見出しではない

            - \\- 入れ子ではない

            a\\
            \\- b
            """, write());
    }

    @Test
    void paragraphStartingWithSpacesIsNotCode() {
        add(PARAGRAPH, "    字下げした文", 0);
        assertEquals("# 京都\n\n字下げした文\n", write());
    }

    @Test
    void codeWithFenceInsideUsesALongerFence() {
        add(CODE, "```\nx\n```", 0);
        assertEquals("# 京都\n\n````\n```\nx\n```\n````\n", write());
    }

    @Test
    void codeInsideAListIsIndented() {
        add(BULLET, "例", 0).add(CODE, "a\n\nb", 1);
        assertEquals("# 京都\n\n- 例\n\n  ```\n  a\n\n  b\n  ```\n", write());
    }

    @Test
    void codeIsNotEscaped() {
        add(CODE, "*a* # b", 0);
        assertEquals("# 京都\n\n```\n*a* # b\n```\n", write());
    }

    @Test
    void emptyParagraphsAreSkipped() {
        add(PARAGRAPH, "a", 0).add(PARAGRAPH, "", 0).add(PARAGRAPH, "", 0).add(PARAGRAPH, "b", 0);
        assertEquals("# 京都\n\na\n\nb\n", write());
    }

    @Test
    void marksAreWritten() {
        add(BULLET, "伏見稲荷へ朝早く行く", 0, false, List.of(new Mark(5, 7, Mark.Style.BOLD)));
        assertEquals("# 京都\n\n- 伏見稲荷へ**朝早**く行く\n", write());
    }

    @Test
    void collapsedBlocksAreWrittenToo() {
        blocks.add(new Block("h", "p", H1, "節", List.of(), false, 0, 0, true));
        add(PARAGRAPH, "隠れた行", 0);
        assertEquals("# 京都\n\n# 節\n\n隠れた行\n", write());
    }
}
