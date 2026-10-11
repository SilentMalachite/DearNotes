package app.dearnotes.db;

import static org.junit.jupiter.api.Assertions.*;

import app.dearnotes.model.Block;
import app.dearnotes.model.BlockType;
import app.dearnotes.model.Mark;
import app.dearnotes.model.Page;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlockRepoTest extends DbTestBase {

    @Test
    void savesAndLoadsBlocksInSortOrder() throws Exception {
        Page p = pages.create(null, "買い物リスト");
        Block second = new Block("b2", p.id(), BlockType.TODO, "電池", List.of(), true, 1, 2048);
        Block first = new Block("b1", p.id(), BlockType.H1, "週末",
            List.of(new Mark(0, 2, Mark.Style.BOLD)), false, 0, 1024);
        blocks.save(List.of(second, first), List.of());
        assertEquals(List.of(first, second), blocks.listByPage(p.id()));
    }

    @Test
    void upsertUpdatesOnlyChangedRow() throws Exception {
        Page p = pages.create(null, "x");
        Block b = Block.empty(p.id(), BlockType.PARAGRAPH, 1024).withContent("牛乳", List.of());
        blocks.save(List.of(b), List.of());
        Block edited = b.withContent("牛乳 2本", List.of()).withType(BlockType.BULLET);
        blocks.save(List.of(edited), List.of());
        assertEquals(List.of(edited), blocks.listByPage(p.id()));
    }

    @Test
    void deletesInSameTransaction() throws Exception {
        Page p = pages.create(null, "x");
        Block a = Block.empty(p.id(), BlockType.PARAGRAPH, 1024);
        Block b = Block.empty(p.id(), BlockType.PARAGRAPH, 2048);
        blocks.save(List.of(a, b), List.of());
        blocks.save(List.of(), List.of(a.id()));
        assertEquals(List.of(b), blocks.listByPage(p.id()));
    }

    @Test
    void blocksOfDeletedPageAreSkippedInsteadOfFailing() throws Exception {
        Page p = pages.create(null, "x");
        pages.deleteForever(p.id());
        assertDoesNotThrow(() -> blocks.save(List.of(Block.empty(p.id(), BlockType.PARAGRAPH, 1)), List.of()));
        assertEquals(List.of(), blocks.listByPage(p.id()));
    }

    @Test
    void savingBlocksTouchesPage() throws Exception {
        Page p = pages.create(null, "x");
        Thread.sleep(5);
        blocks.save(List.of(Block.empty(p.id(), BlockType.PARAGRAPH, 1)), List.of());
        assertTrue(pages.get(p.id()).orElseThrow().updatedAt() > p.updatedAt());
    }

    @Test
    void savesCollapsedState() throws Exception {
        Page p = pages.create(null, "x");
        Block b = Block.empty(p.id(), BlockType.H2, 1024).withContent("1日目", List.of()).withCollapsed(true);
        blocks.save(List.of(b), List.of());
        assertTrue(blocks.listByPage(p.id()).getFirst().collapsed());
        blocks.save(List.of(b.withCollapsed(false)), List.of());
        assertFalse(blocks.listByPage(p.id()).getFirst().collapsed());
    }
}
