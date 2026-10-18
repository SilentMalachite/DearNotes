package app.dearnotes.editor

import app.dearnotes.model.BlockType.BULLET
import app.dearnotes.model.BlockType.CODE
import app.dearnotes.model.BlockType.DIVIDER
import app.dearnotes.model.BlockType.H1
import app.dearnotes.model.BlockType.NUMBERED
import app.dearnotes.model.BlockType.PARAGRAPH
import app.dearnotes.model.BlockType.QUOTE
import app.dearnotes.model.BlockType.TODO
import kotlin.test.Test
import kotlin.test.assertEquals

class PasteParserTest {
    @Test
    fun parsesMarkdownBlocks() {
        val md = """
            # 週末
            - 牛乳
              - 低脂肪
            [] 電池
            - [x] パン
            2. 駅前
            > 注意

            ---
            ふつうの文
            """.trimIndent()
        assertEquals(
            listOf(
                PasteParser.Line(H1, "週末", 0, false),
                PasteParser.Line(BULLET, "牛乳", 0, false),
                PasteParser.Line(BULLET, "低脂肪", 1, false),
                PasteParser.Line(TODO, "電池", 0, false),
                PasteParser.Line(TODO, "パン", 0, true),
                PasteParser.Line(NUMBERED, "駅前", 0, false),
                PasteParser.Line(QUOTE, "注意", 0, false),
                PasteParser.Line(DIVIDER, "", 0, false),
                PasteParser.Line(PARAGRAPH, "ふつうの文", 0, false),
            ),
            PasteParser.parse(md),
        )
    }

    @Test
    fun fencedCodeBecomesOneBlock() {
        val md = "前\n```java\nint a = 1;\n\n  int b = 2;\n```\n後"
        assertEquals(
            listOf(
                PasteParser.Line(PARAGRAPH, "前", 0, false),
                PasteParser.Line(CODE, "int a = 1;\n\n  int b = 2;", 0, false),
                PasteParser.Line(PARAGRAPH, "後", 0, false),
            ),
            PasteParser.parse(md),
        )
    }

    @Test
    fun unclosedFenceRunsToEnd() {
        assertEquals(listOf(PasteParser.Line(CODE, "x\ny", 0, false)), PasteParser.parse("```\nx\ny"))
    }
}
