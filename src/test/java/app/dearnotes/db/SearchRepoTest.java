package app.dearnotes.db;

import static org.junit.jupiter.api.Assertions.*;

import app.dearnotes.db.SearchRepo.Hit;
import app.dearnotes.model.Block;
import app.dearnotes.model.BlockType;
import app.dearnotes.model.Page;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SearchRepoTest extends DbTestBase {

    Page shopping;
    Page diary;
    Block beans;

    @BeforeEach
    void seed() throws Exception {
        shopping = pages.create(null, "買い物リスト");
        diary = pages.create(null, "2026-10-10");
        Page howTo = pages.create(null, "コーヒーの淹れ方");
        beans = text(shopping, "コーヒー豆 200g", 1);
        blocks.save(List.of(
            beans,
            text(shopping, "電池は単3を4本", 2),
            text(diary, "駅前の店でコーヒーを飲んだ", 1),
            text(howTo, "お湯は 90 度", 1)), List.of());
    }

    @Test
    void findsJapaneseSubstringWithTrigram() throws Exception {
        List<Hit> hits = search.search("コーヒー");
        assertEquals("コーヒーの淹れ方", hits.getFirst().pageTitle());
        assertTrue(hits.getFirst().titleMatch());
        assertTrue(hits.stream().anyMatch(h -> beans.id().equals(h.blockId())));
        assertTrue(hits.stream().anyMatch(h -> h.pageId().equals(diary.id()) && h.snippet().contains("コーヒー")));
        assertEquals(3, hits.size());
    }

    @Test
    void findsMidWordSubstring() throws Exception {
        List<Hit> hits = search.search("ーヒー豆");
        assertEquals(1, hits.size());
        assertEquals(shopping.id(), hits.getFirst().pageId());
    }

    @Test
    void shortQueriesFallBackToLike() throws Exception {
        List<Hit> hits = search.search("電池");
        assertEquals(1, hits.size());
        assertEquals("電池は単3を4本", hits.getFirst().snippet());

        assertEquals(1, search.search("駅").size());
    }

    @Test
    void editedTextIsReindexed() throws Exception {
        blocks.save(List.of(beans.withContent("紅茶の葉", List.of())), List.of());
        assertTrue(search.search("コーヒー豆").isEmpty());
        assertEquals(1, search.search("紅茶の葉").size());
    }

    @Test
    void deletedBlocksAndTrashedPagesAreExcluded() throws Exception {
        blocks.save(List.of(), List.of(beans.id()));
        assertTrue(search.search("コーヒー豆").isEmpty());

        pages.trash(diary.id(), 1);
        assertTrue(search.search("駅前の店").isEmpty());
    }

    @Test
    void specialCharactersAreQuoted() throws Exception {
        assertDoesNotThrow(() -> search.search("\"AND OR*"));
        assertDoesNotThrow(() -> search.search("%_"));
        assertTrue(search.search("%_").isEmpty());
        assertTrue(search.search("   ").isEmpty());
    }

    @Test
    void snippetIsCutAroundMatch() {
        String text = "あ".repeat(40) + "目印" + "い".repeat(40);
        String s = SearchRepo.snippet(text, "目印");
        assertTrue(s.startsWith("…") && s.endsWith("…"));
        assertTrue(s.contains("目印"));
    }

    private static Block text(Page p, String text, double key) {
        return Block.empty(p.id(), BlockType.PARAGRAPH, key).withContent(text, List.of());
    }
}
