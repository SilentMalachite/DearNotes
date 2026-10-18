package app.dearnotes.editor

import app.dearnotes.model.BlockType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlashCommandsTest {
    @Test
    fun japanesePrefixNarrowsToHeadings() {
        assertEquals(
            listOf(BlockType.H1, BlockType.H2, BlockType.H3),
            SlashCommands.filter("見").map { it.type },
        )
    }

    @Test
    fun englishKeywordMatches() {
        assertEquals(listOf(BlockType.TODO), SlashCommands.filter("todo").map { it.type })
    }

    @Test
    fun emptyQueryListsEverything() {
        assertEquals(BlockType.entries.size, SlashCommands.filter("").size)
    }

    @Test
    fun queryOfRecognisesSlashText() {
        assertEquals("", SlashCommands.queryOf("/"))
        assertEquals("見", SlashCommands.queryOf("/見"))
        assertNull(SlashCommands.queryOf("/見 出し"))
        assertNull(SlashCommands.queryOf("見出し"))
    }

    @Test
    fun nbspDoesNotEndTheQuery() {
        assertEquals("見\u00A0出し", SlashCommands.queryOf("/見\u00A0出し"))
        assertNull(SlashCommands.queryOf("/見\u3000出し"))
    }

    @Test
    fun japaneseInputSlashesAlsoOpenTheMenu() {
        // 日本語入力のままだと「/」は「／」か「・」になる（Google 日本語入力の既定は「・」）
        assertEquals("みだし", SlashCommands.queryOf("\uFF0Fみだし"))
        assertEquals("見出し", SlashCommands.queryOf("\u30FB見出し"))
        assertEquals("", SlashCommands.queryOf("\u30FB"))
        assertNull(SlashCommands.queryOf("a\u30FB見出し"))
        assertNull(SlashCommands.queryOf("\u30FBりんご ごりら"))
    }
}
