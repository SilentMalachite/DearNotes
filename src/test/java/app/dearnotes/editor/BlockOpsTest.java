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
    void keyForMoveIgnoresTheMovedBlock() {
        var list = List.of(b(PARAGRAPH, 0, 1024), b(PARAGRAPH, 0, 2048), b(PARAGRAPH, 0, 3072));
        // 先頭を末尾へ
        assertEquals(3584.0, BlockOps.keyForMove(list, 0, 2));
        // 末尾を先頭へ
        assertEquals(512.0, BlockOps.keyForMove(list, 2, 0));
        // 先頭を2番目と3番目の間へ
        assertEquals(2560.0, BlockOps.keyForMove(list, 0, 1));
    }
}
