package app.dearnotes.editor;

import static app.dearnotes.model.BlockType.*;
import static org.junit.jupiter.api.Assertions.*;

import app.dearnotes.model.Block;
import app.dearnotes.model.BlockType;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlockOpsTest {

    private static Block b(BlockType t, int indent, double key) {
        return Block.empty("p", t, key).withIndent(indent);
    }

    @Test
    void numbersConsecutiveItemsAndRestartsAfterOtherBlocks() {
        var list = List.of(
            b(NUMBERED, 0, 1), b(NUMBERED, 0, 2), b(PARAGRAPH, 0, 3), b(NUMBERED, 0, 4));
        assertEquals(1, BlockOps.listNumber(list, 0));
        assertEquals(2, BlockOps.listNumber(list, 1));
        assertEquals(1, BlockOps.listNumber(list, 3));
    }

    @Test
    void nestedItemsHaveTheirOwnNumbering() {
        var list = List.of(
            b(NUMBERED, 0, 1), b(NUMBERED, 1, 2), b(NUMBERED, 1, 3), b(NUMBERED, 0, 4));
        assertEquals(1, BlockOps.listNumber(list, 1));
        assertEquals(2, BlockOps.listNumber(list, 2));
        assertEquals(2, BlockOps.listNumber(list, 3));
    }

    @Test
    void keyAfterPicksMidpointOrAppends() {
        var list = List.of(b(PARAGRAPH, 0, 1024), b(PARAGRAPH, 0, 2048));
        assertEquals(1536.0, BlockOps.keyAfter(list, 0));
        assertEquals(2560.0, BlockOps.keyAfter(list, 1));
        assertEquals(512.0, BlockOps.keyAfter(list, -1));
    }

    @Test
    void keysForMoveSpreadsGroupBetweenNeighbours() {
        var list = List.of(b(PARAGRAPH, 0, 1024), b(PARAGRAPH, 1, 2048), b(PARAGRAPH, 1, 3072),
            b(PARAGRAPH, 0, 4096));
        // 先頭の3行をまとめて末尾へ
        assertEquals(List.of(5120.0, 6144.0, 7168.0), BlockOps.keysForMove(list, 0, 3, 1));
        // 末尾を先頭へ（1行だけ）
        assertEquals(List.of(512.0), BlockOps.keysForMove(list, 3, 1, 0));
        // 2〜3行目を先頭へ
        assertEquals(List.of(1024.0 / 3, 2048.0 / 3), BlockOps.keysForMove(list, 1, 2, 0));
    }

    @Test
    void keysForMoveReturnsNullWhenNoRoom() {
        var list = List.of(b(PARAGRAPH, 0, 1), b(PARAGRAPH, 0, Math.nextUp(1.0)), b(PARAGRAPH, 0, 5));
        assertNull(BlockOps.keysForMove(list, 2, 1, 1));
    }
}
