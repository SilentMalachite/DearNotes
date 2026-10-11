package app.dearnotes.editor;

import static org.junit.jupiter.api.Assertions.*;

import app.dearnotes.editor.SlashCommands.Command;
import app.dearnotes.model.BlockType;
import java.util.List;
import org.junit.jupiter.api.Test;

class SlashCommandsTest {

    @Test
    void japanesePrefixNarrowsToHeadings() {
        assertEquals(List.of(BlockType.H1, BlockType.H2, BlockType.H3),
            SlashCommands.filter("見").stream().map(Command::type).toList());
    }

    @Test
    void englishKeywordMatches() {
        assertEquals(List.of(BlockType.TODO),
            SlashCommands.filter("todo").stream().map(Command::type).toList());
    }

    @Test
    void emptyQueryListsEverything() {
        assertEquals(BlockType.values().length, SlashCommands.filter("").size());
    }

    @Test
    void queryOfRecognisesSlashText() {
        assertEquals("", SlashCommands.queryOf("/"));
        assertEquals("見", SlashCommands.queryOf("/見"));
        assertNull(SlashCommands.queryOf("/見 出し"));
        assertNull(SlashCommands.queryOf("見出し"));
    }
}
