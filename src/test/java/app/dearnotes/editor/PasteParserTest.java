package app.dearnotes.editor;

import static app.dearnotes.model.BlockType.*;
import static org.junit.jupiter.api.Assertions.*;

import app.dearnotes.editor.PasteParser.Line;
import java.util.List;
import org.junit.jupiter.api.Test;

class PasteParserTest {

    @Test
    void parsesMarkdownBlocks() {
        String md = """
            # 週末
            - 牛乳
              - 低脂肪
            [] 電池
            - [x] パン
            2. 駅前
            > 注意

            ---
            ふつうの文
            """;
        assertEquals(List.of(
                new Line(H1, "週末", 0, false),
                new Line(BULLET, "牛乳", 0, false),
                new Line(BULLET, "低脂肪", 1, false),
                new Line(TODO, "電池", 0, false),
                new Line(TODO, "パン", 0, true),
                new Line(NUMBERED, "駅前", 0, false),
                new Line(QUOTE, "注意", 0, false),
                new Line(DIVIDER, "", 0, false),
                new Line(PARAGRAPH, "ふつうの文", 0, false)),
            PasteParser.parse(md));
    }

    @Test
    void fencedCodeBecomesOneBlock() {
        String md = "前\n```java\nint a = 1;\n\n  int b = 2;\n```\n後";
        assertEquals(List.of(
                new Line(PARAGRAPH, "前", 0, false),
                new Line(CODE, "int a = 1;\n\n  int b = 2;", 0, false),
                new Line(PARAGRAPH, "後", 0, false)),
            PasteParser.parse(md));
    }

    @Test
    void unclosedFenceRunsToEnd() {
        assertEquals(List.of(new Line(CODE, "x\ny", 0, false)), PasteParser.parse("```\nx\ny"));
    }
}
