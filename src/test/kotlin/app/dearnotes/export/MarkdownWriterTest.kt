package app.dearnotes.export

import app.dearnotes.model.Block
import app.dearnotes.model.BlockType
import app.dearnotes.model.BlockType.BULLET
import app.dearnotes.model.BlockType.CODE
import app.dearnotes.model.BlockType.DIVIDER
import app.dearnotes.model.BlockType.H1
import app.dearnotes.model.BlockType.H2
import app.dearnotes.model.BlockType.H3
import app.dearnotes.model.BlockType.NUMBERED
import app.dearnotes.model.BlockType.PARAGRAPH
import app.dearnotes.model.BlockType.QUOTE
import app.dearnotes.model.BlockType.TODO
import app.dearnotes.model.Mark
import app.dearnotes.model.Page
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class MarkdownWriterTest {

    private val blocks = ArrayList<Block>()

    private fun add(type: BlockType, text: String, indent: Int): MarkdownWriterTest {
        return add(type, text, indent, false, emptyList())
    }

    private fun add(
        type: BlockType,
        text: String,
        indent: Int,
        checked: Boolean,
        marks: List<Mark>,
    ): MarkdownWriterTest {
        blocks.add(
            Block("b" + blocks.size, "p", type, text, marks, checked, indent, blocks.size.toDouble(), false),
        )
        return this
    }

    private fun write(): String = MarkdownWriter.write(PAGE, blocks)

    @Test
    fun emptyPageIsOnlyTheTitle() {
        assertEquals("# 京都\n", write())
    }

    @Test
    fun untitledPageUsesTheDisplayTitle() {
        val page = Page("p", null, "", null, null, 0.0, 0L, 0L, null)
        assertEquals("# 無題\n", MarkdownWriter.write(page, emptyList()))
    }

    @Test
    fun titleIsEscaped() {
        val page = Page("p", null, "*注意*", null, null, 0.0, 0L, 0L, null)
        assertEquals("# \\*注意\\*\n", MarkdownWriter.write(page, emptyList()))
    }

    @Test
    fun titleThatLooksLikeHeadingSyntaxIsKept() {
        assertEquals(
            "# \\###\n",
            MarkdownWriter.write(Page("p", null, "###", null, null, 0.0, 0L, 0L, null), emptyList()),
        )
        assertEquals(
            "# Done \\#\n",
            MarkdownWriter.write(Page("p", null, "Done #", null, null, 0.0, 0L, 0L, null), emptyList()),
        )
    }

    @Test
    fun blankLineBetweenNumberedListsKeepsThemApart() {
        // 画面では空の行の後でまた 1 から数える。CommonMark では空行だけだと1つのリストになる
        add(NUMBERED, "一", 0).add(PARAGRAPH, "", 0).add(NUMBERED, "二", 0)
        assertEquals(
            """
            # 京都

            1. 一

            <!-- -->

            1. 二
            """.trimIndent() + "\n",
            write(),
        )
    }

    @Test
    fun breakerInsideAnOuterListIsIndented() {
        add(BULLET, "外", 0).add(NUMBERED, "一", 1).add(PARAGRAPH, "", 0).add(NUMBERED, "二", 1)
        assertEquals(
            """
            # 京都

            - 外
              1. 一

              <!-- -->

              1. 二
            """.trimIndent() + "\n",
            write(),
        )
    }

    @Test
    fun numberedListAfterAParagraphNeedsNoBreaker() {
        add(NUMBERED, "一", 0).add(PARAGRAPH, "間", 0).add(PARAGRAPH, "", 0).add(NUMBERED, "二", 0)
        assertEquals("# 京都\n\n1. 一\n\n間\n\n1. 二\n", write())
    }

    @Test
    fun eachTypeHasItsMarker() {
        add(H1, "1日目", 0).add(H2, "午前", 0).add(H3, "朝", 0)
            .add(PARAGRAPH, "伏見稲荷へ行く", 0)
            .add(QUOTE, "早く行く", 0)
            .add(DIVIDER, "", 0)
            .add(CODE, "let a = 1;\nlet b = 2;", 0)
        assertEquals(
            """
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
            """.trimIndent() + "\n",
            write(),
        )
    }

    @Test
    fun listItemsStayTogether() {
        add(BULLET, "充電器", 0).add(BULLET, "着替え", 0)
            .add(TODO, "宿", 0, true, emptyList()).add(TODO, "切符", 0, false, emptyList())
            .add(PARAGRAPH, "以上", 0)
        assertEquals(
            """
            # 京都

            - 充電器
            - 着替え
            - [x] 宿
            - [ ] 切符

            以上
            """.trimIndent() + "\n",
            write(),
        )
    }

    @Test
    fun numbersFollowTheScreen() {
        add(NUMBERED, "準備", 0).add(BULLET, "充電器", 1).add(NUMBERED, "出発", 0)
            .add(PARAGRAPH, "間", 0).add(NUMBERED, "また1から", 0)
        assertEquals(
            """
            # 京都

            1. 準備
               - 充電器
            2. 出発

            間

            1. また1から
            """.trimIndent() + "\n",
            write(),
        )
    }

    @Test
    fun childrenAlignWithTheParentsText() {
        add(BULLET, "a", 0).add(BULLET, "b", 1).add(TODO, "c", 2, false, emptyList())
            .add(BULLET, "d", 3).add(PARAGRAPH, "続き", 1)
        assertEquals(
            """
            # 京都

            - a
              - b
                - [ ] c
                  - d

              続き
            """.trimIndent() + "\n",
            write(),
        )
    }

    @Test
    fun indentUnderANonListBlockIsDropped() {
        add(PARAGRAPH, "段落", 0).add(BULLET, "項目", 1).add(PARAGRAPH, "下の段落", 2)
        assertEquals(
            """
            # 京都

            段落

            - 項目

              下の段落
            """.trimIndent() + "\n",
            write(),
        )
    }

    @Test
    fun indentJumpUsesTheNearestParent() {
        add(BULLET, "a", 0).add(BULLET, "深い", 3)
        assertEquals(
            """
            # 京都

            - a
              - 深い
            """.trimIndent() + "\n",
            write(),
        )
    }

    @Test
    fun lineBreaksInsideABlockAreHardBreaks() {
        add(PARAGRAPH, "1行目\n2行目", 0).add(BULLET, "項目\n続き", 0).add(QUOTE, "引用\n続き", 0)
        assertEquals(
            """
            # 京都

            1行目\
            2行目

            - 項目\
              続き

            > 引用\
            > 続き
            """.trimIndent() + "\n",
            write(),
        )
    }

    @Test
    fun headingLineBreaksBecomeSpaces() {
        add(H2, "長い\n見出し", 0)
        assertEquals("# 京都\n\n## 長い 見出し\n", write())
    }

    @Test
    fun blockSyntaxInTextIsEscaped() {
        add(PARAGRAPH, "# 見出しではない", 0).add(BULLET, "- 入れ子ではない", 0)
            .add(PARAGRAPH, "a\n- b", 0)
        assertEquals(
            """
            # 京都

            \# 見出しではない

            - \- 入れ子ではない

            a\
            \- b
            """.trimIndent() + "\n",
            write(),
        )
    }

    @Test
    fun paragraphStartingWithSpacesIsNotCode() {
        add(PARAGRAPH, "    字下げした文", 0)
        assertEquals("# 京都\n\n字下げした文\n", write())
    }

    @Test
    fun codeWithFenceInsideUsesALongerFence() {
        add(CODE, "```\nx\n```", 0)
        assertEquals("# 京都\n\n````\n```\nx\n```\n````\n", write())
    }

    @Test
    fun codeInsideAListIsIndented() {
        add(BULLET, "例", 0).add(CODE, "a\n\nb", 1)
        assertEquals("# 京都\n\n- 例\n\n  ```\n  a\n\n  b\n  ```\n", write())
    }

    @Test
    fun codeIsNotEscaped() {
        add(CODE, "*a* # b", 0)
        assertEquals("# 京都\n\n```\n*a* # b\n```\n", write())
    }

    @Test
    fun emptyParagraphsAreSkipped() {
        add(PARAGRAPH, "a", 0).add(PARAGRAPH, "", 0).add(PARAGRAPH, "", 0).add(PARAGRAPH, "b", 0)
        assertEquals("# 京都\n\na\n\nb\n", write())
    }

    @Test
    fun marksAreWritten() {
        add(BULLET, "伏見稲荷へ朝早く行く", 0, false, listOf(Mark(5, 7, Mark.Style.BOLD)))
        assertEquals("# 京都\n\n- 伏見稲荷へ**朝早**く行く\n", write())
    }

    @Test
    fun collapsedBlocksAreWrittenToo() {
        blocks.add(Block("h", "p", H1, "節", emptyList(), false, 0, 0.0, true))
        add(PARAGRAPH, "隠れた行", 0)
        assertEquals("# 京都\n\n# 節\n\n隠れた行\n", write())
    }

    companion object {
        private val PAGE = Page("p", null, "京都", null, null, 0.0, 0L, 0L, null)
    }
}
