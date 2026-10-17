package app.dearnotes.editor

import app.dearnotes.model.BlockType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

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
}
