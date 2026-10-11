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

    @Test
    void linksToFindsBlocksOnAlivePagesOnly() throws Exception {
        Page kyoto = pages.create(null, "京都");
        Page trip = pages.create(null, "旅行");
        Page old = pages.create(null, "古い");
        var link = List.of(new Mark(2, 4, Mark.Style.PAGE, kyoto.id()));
        Block b = Block.empty(trip.id(), BlockType.PARAGRAPH, 1).withContent("次は京都へ", link);
        blocks.save(List.of(
            b,
            Block.empty(trip.id(), BlockType.PARAGRAPH, 2).withContent("京都", List.of()),
            Block.empty(old.id(), BlockType.PARAGRAPH, 1).withContent("次は京都へ", link),
            Block.empty(kyoto.id(), BlockType.PARAGRAPH, 1)
                .withContent("自分", List.of(new Mark(0, 2, Mark.Style.PAGE, kyoto.id())))),
            List.of());
        pages.trash(old.id(), 1);

        List<BlockRepo.Backlink> hits = blocks.linksTo(kyoto.id());
        assertEquals(List.of(b.id()), hits.stream().map(BlockRepo.Backlink::blockId).toList());
        assertEquals("旅行", hits.getFirst().pageTitle());
        assertEquals("次は京都へ", hits.getFirst().text());
        assertEquals(List.of(), blocks.linksTo(trip.id()));
    }
}
