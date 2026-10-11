package app.dearnotes.db;

import static org.junit.jupiter.api.Assertions.*;

import app.dearnotes.model.Block;
import app.dearnotes.model.BlockType;
import app.dearnotes.model.Page;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PageRepoTest extends DbTestBase {

    @Test
    void createAppendsToEndOfSiblings() throws Exception {
        Page a = pages.create(null, "A");
        Page b = pages.create(null, "B");
        Page child = pages.create(a.id(), "A-1");
        assertTrue(a.sortKey() < b.sortKey());
        assertEquals(a.id(), child.parentId());
        assertEquals(1024.0, child.sortKey());
        assertEquals(3, titles().size());
    }

    @Test
    void openDailyTwiceCreatesOnePage() throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 11);
        Page first = pages.openDaily(day);
        Page second = pages.openDaily(day);
        assertEquals(first.id(), second.id());
        assertEquals(day, first.dailyDate());
        assertEquals("2026-10-11", first.title());
        assertEquals(1, pages.listAlive().size());
    }

    @Test
    void openDailyRestoresTrashedDiary() throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 11);
        Page p = pages.openDaily(day);
        pages.trash(p.id(), 1000);
        Page again = pages.openDaily(day);
        assertEquals(p.id(), again.id());
        assertFalse(again.isTrashed());
    }

    @Test
    void openDailyRestoresChildrenTrashedTogether() throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 11);
        Page diary = pages.openDaily(day);
        Page child = pages.create(diary.id(), "子");
        Page earlier = pages.create(diary.id(), "先に捨てた子");
        pages.trash(earlier.id(), 500);
        pages.trash(diary.id(), 1000);

        pages.openDaily(day);
        assertFalse(pages.get(child.id()).orElseThrow().isTrashed());
        assertTrue(pages.get(earlier.id()).orElseThrow().isTrashed());
    }

    @Test
    void trashMovesWholeSubtreeAndRestoreBringsItBack() throws Exception {
        Page root = pages.create(null, "親");
        Page child = pages.create(root.id(), "子");
        Page grandchild = pages.create(child.id(), "孫");
        pages.create(null, "他");

        pages.trash(root.id(), 1000);
        assertEquals(List.of("他"), titles());
        assertEquals(List.of("親"), pages.listTrash().stream().map(Page::title).toList());
        assertTrue(pages.get(grandchild.id()).orElseThrow().isTrashed());

        pages.restore(root.id());
        assertEquals(4, pages.listAlive().size());
    }

    @Test
    void restoreKeepsChildrenThatWereTrashedEarlierOnTheirOwn() throws Exception {
        Page parent = pages.create(null, "親");
        Page earlier = pages.create(parent.id(), "先に捨てた子");
        Page together = pages.create(parent.id(), "一緒に捨てた子");
        pages.trash(earlier.id(), 1000);
        pages.trash(parent.id(), 2000);

        pages.restore(parent.id());
        assertFalse(pages.get(together.id()).orElseThrow().isTrashed());
        assertTrue(pages.get(earlier.id()).orElseThrow().isTrashed());
        assertEquals(List.of("先に捨てた子"), pages.listTrash().stream().map(Page::title).toList());
    }

    @Test
    void restoringChildOfTrashedParentMovesItToTop() throws Exception {
        Page root = pages.create(null, "親");
        Page child = pages.create(root.id(), "子");
        pages.trash(root.id(), 1000);
        pages.restore(child.id());
        Page restored = pages.get(child.id()).orElseThrow();
        assertNull(restored.parentId());
        assertFalse(restored.isTrashed());
    }

    @Test
    void purgeDeletesOldTrashAndCascadesBlocks() throws Exception {
        Page old = pages.create(null, "古い");
        Page recent = pages.create(null, "新しい");
        blocks.save(List.of(Block.empty(old.id(), BlockType.PARAGRAPH, 1024)), List.of());
        pages.trash(old.id(), 1_000);
        pages.trash(recent.id(), 5_000);

        assertEquals(1, pages.purgeTrashedBefore(2_000));
        assertTrue(pages.get(old.id()).isEmpty());
        assertTrue(pages.get(recent.id()).isPresent());
        assertEquals(List.of(), blocks.listByPage(old.id()));
    }

    @Test
    void moveChangesParentAndRejectsCycles() throws Exception {
        Page a = pages.create(null, "A");
        Page b = pages.create(a.id(), "B");
        pages.move(a.id(), null, 5000);
        pages.move(b.id(), null, 6000);
        assertNull(pages.get(b.id()).orElseThrow().parentId());

        pages.move(b.id(), a.id(), 1);
        assertThrows(java.sql.SQLException.class, () -> pages.move(a.id(), b.id(), 1));
        assertThrows(java.sql.SQLException.class, () -> pages.move(a.id(), a.id(), 1));
    }

    @Test
    void renameAndIcon() throws Exception {
        Page p = pages.create(null, "");
        assertEquals("無題", p.displayTitle());
        pages.rename(p.id(), "読書メモ");
        pages.setIcon(p.id(), "📚");
        Page after = pages.get(p.id()).orElseThrow();
        assertEquals("読書メモ", after.title());
        assertEquals("📚", after.displayIcon());
    }

    @Test
    void respaceChildrenKeepsOrder() throws Exception {
        Page a = pages.create(null, "A");
        Page b = pages.create(null, "B");
        pages.move(b.id(), null, a.sortKey() + 1e-9);
        pages.respaceChildren(null);
        List<Page> alive = pages.listAlive();
        assertEquals(List.of("A", "B"), alive.stream().map(Page::title).toList());
        assertEquals(1024.0, alive.get(1).sortKey() - alive.get(0).sortKey());
    }

@Test
    void findByTitlePutsPrefixMatchesFirstAndSkipsTrashAndUntitled() throws Exception {
        var inn = pages.create(null, "東京の宿");
        var kyoto = pages.create(null, "京都");
        var gone = pages.create(null, "京都メモ");
        pages.create(null, "");
        pages.trash(gone.id(), 1);
        assertEquals(List.of(kyoto.id(), inn.id()), pages.findByTitle("京", 8).stream().map(Page::id).toList());
        assertEquals(2, pages.findByTitle("", 8).size());
        assertEquals(1, pages.findByTitle("", 1).size());
        assertEquals(List.of(), pages.findByTitle("100%", 8));
    }

    private List<String> titles() throws Exception {
        return pages.listAlive().stream().map(Page::title).toList();
    }
}
