package app.dearnotes.db

import app.dearnotes.model.Block
import app.dearnotes.model.BlockType
import app.dearnotes.model.Page
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class SearchRepoTest : DbTestBase() {

    lateinit var shopping: Page
    lateinit var diary: Page
    lateinit var beans: Block

    @BeforeEach
    fun seed() {
        shopping = pages.create(null, "買い物リスト")
        diary = pages.create(null, "2026-10-10")
        val howTo = pages.create(null, "コーヒーの淹れ方")
        beans = text(shopping, "コーヒー豆 200g", 1.0)
        blocks.save(
            listOf(
                beans,
                text(shopping, "電池は単3を4本", 2.0),
                text(diary, "駅前の店でコーヒーを飲んだ", 1.0),
                text(howTo, "お湯は 90 度", 1.0),
            ),
            emptyList(),
        )
    }

    @Test
    fun findsJapaneseSubstringWithTrigram() {
        val hits = search.search("コーヒー")
        assertEquals("コーヒーの淹れ方", hits.first().pageTitle)
        assertTrue(hits.first().titleMatch())
        assertTrue(hits.any { h -> beans.id == h.blockId })
        assertTrue(hits.any { h -> h.pageId == diary.id && h.snippet.contains("コーヒー") })
        assertEquals(3, hits.size)
    }

    @Test
    fun findsMidWordSubstring() {
        val hits = search.search("ーヒー豆")
        assertEquals(1, hits.size)
        assertEquals(shopping.id, hits.first().pageId)
    }

    @Test
    fun shortQueriesFallBackToLike() {
        val hits = search.search("電池")
        assertEquals(1, hits.size)
        assertEquals("電池は単3を4本", hits.first().snippet)

        assertEquals(1, search.search("駅").size)
    }

    @Test
    fun editedTextIsReindexed() {
        blocks.save(listOf(beans.withContent("紅茶の葉", emptyList())), emptyList())
        assertTrue(search.search("コーヒー豆").isEmpty())
        assertEquals(1, search.search("紅茶の葉").size)
    }

    @Test
    fun deletedBlocksAndTrashedPagesAreExcluded() {
        blocks.save(emptyList(), listOf(beans.id))
        assertTrue(search.search("コーヒー豆").isEmpty())

        pages.trash(diary.id, 1L)
        assertTrue(search.search("駅前の店").isEmpty())
    }

    @Test
    fun specialCharactersAreQuoted() {
        assertDoesNotThrow { search.search("\"AND OR*") }
        assertDoesNotThrow { search.search("%_") }
        assertTrue(search.search("%_").isEmpty())
        assertTrue(search.search("   ").isEmpty())
    }

    @Test
    fun snippetIsCutAroundMatch() {
        val text = "あ".repeat(40) + "目印" + "い".repeat(40)
        val s = SearchRepo.snippet(text, "目印")
        assertTrue(s.startsWith("…") && s.endsWith("…"))
        assertTrue(s.contains("目印"))
    }

    private fun text(p: Page, text: String, key: Double): Block {
        return Block.empty(p.id, BlockType.PARAGRAPH, key).withContent(text, emptyList())
    }
}
