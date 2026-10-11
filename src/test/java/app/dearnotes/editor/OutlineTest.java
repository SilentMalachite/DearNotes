package app.dearnotes.editor;

import static app.dearnotes.model.BlockType.*;
import static org.junit.jupiter.api.Assertions.*;

import app.dearnotes.model.Block;
import app.dearnotes.model.BlockType;
import java.util.List;
import org.junit.jupiter.api.Test;

class OutlineTest {

    private static Block b(BlockType t, int indent, String text) {
        return Block.empty("p", t, 0).withIndent(indent).withContent(text, List.of());
    }

    private static Block folded(BlockType t, int indent, String text) {
        return b(t, indent, text).withCollapsed(true);
    }

    // 京都旅行(H1) / 持ち物(H2) / 充電器 / 1日目(H2) / 午前(H3) / 伏見稲荷 / 午後(H3) / 清水寺 / 2日目(H2)
    private static final List<Block> TRIP = List.of(
        b(H1, 0, "京都旅行"),
        b(H2, 0, "持ち物"),
        b(BULLET, 0, "充電器"),
        b(H2, 0, "1日目"),
        b(H3, 0, "午前"),
        b(PARAGRAPH, 0, "伏見稲荷へ行く"),
        b(H3, 0, "午後"),
        b(PARAGRAPH, 0, "清水寺から祇園まで歩く"),
        b(H2, 0, "2日目"));

    @Test
    void headingSectionEndsBeforeSameOrHigherHeading() {
        assertEquals(9, Outline.end(TRIP, 0));      // H1 は最後まで
        assertEquals(3, Outline.end(TRIP, 1));      // 持ち物 → 1日目の手前
        assertEquals(8, Outline.end(TRIP, 3));      // 1日目 → 2日目の手前（H3 では終わらない）
        assertEquals(6, Outline.end(TRIP, 4));      // 午前 → 午後の手前
        assertEquals(9, Outline.end(TRIP, 8));      // 2日目 は子がない
        assertFalse(Outline.hasChildren(TRIP, 8));
        assertTrue(Outline.hasChildren(TRIP, 3));
    }

    @Test
    void itemRangeIsFollowingDeeperRows() {
        var list = List.of(
            b(BULLET, 0, "読む本"),
            b(BULLET, 1, "古都"),
            b(BULLET, 2, "上巻"),
            b(BULLET, 1, "檸檬"),
            b(TODO, 0, "電池を替える"),
            b(PARAGRAPH, 0, "おわり"));
        assertEquals(4, Outline.end(list, 0));
        assertEquals(3, Outline.end(list, 1));
        assertTrue(Outline.hasChildren(list, 1));
        assertFalse(Outline.hasChildren(list, 3));
        assertFalse(Outline.hasChildren(list, 4));
        assertFalse(Outline.hasChildren(list, 5));
    }

    @Test
    void hiddenRowsAreInsideCollapsedRanges() {
        var list = List.of(
            folded(H2, 0, "1日目"),
            b(PARAGRAPH, 0, "伏見稲荷"),
            folded(BULLET, 0, "お昼"),          // 隠れた行の中で畳んでいても同じ
            b(BULLET, 1, "錦市場"),
            b(H2, 0, "2日目"),
            folded(BULLET, 0, "嵐山"),
            b(BULLET, 1, "竹林"),
            b(PARAGRAPH, 0, "帰る"));
        boolean[] hidden = Outline.hidden(list);
        assertArrayEquals(new boolean[] {false, true, true, true, false, false, true, false}, hidden);
    }

    @Test
    void hidersAreCollapsedRowsCoveringTheRow() {
        var list = List.of(
            folded(H2, 0, "1日目"),
            folded(BULLET, 0, "お昼"),
            b(BULLET, 1, "錦市場"),
            b(H2, 0, "2日目"));
        assertEquals(List.of(0, 1), Outline.hiders(list, 2));
        assertEquals(List.of(0), Outline.hiders(list, 1));
        assertEquals(List.of(), Outline.hiders(list, 3));
    }

    @Test
    void headingsListH1ToH3WithLevels() {
        var heads = Outline.headings(TRIP);
        assertEquals(List.of("京都旅行", "持ち物", "1日目", "午前", "午後", "2日目"),
            heads.stream().map(Outline.Heading::text).toList());
        assertEquals(List.of(1, 2, 2, 3, 3, 2), heads.stream().map(Outline.Heading::level).toList());
        assertEquals(TRIP.get(4).id(), heads.get(3).blockId());
    }

    @Test
    void currentHeadingIsNearestHeadingAtOrAbove() {
        assertEquals(4, Outline.currentHeading(TRIP, 5));
        assertEquals(3, Outline.currentHeading(TRIP, 3));
        assertEquals(-1, Outline.currentHeading(List.of(b(PARAGRAPH, 0, "前書き")), 0));
    }
}
