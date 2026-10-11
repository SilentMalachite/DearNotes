package app.dearnotes.editor;

import static org.junit.jupiter.api.Assertions.*;

import app.dearnotes.model.BlockType;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class MarkdownShortcutsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "'# '   | H1",
        "'## '  | H2",
        "'### ' | H3",
        "'- '   | BULLET",
        "'* '   | BULLET",
        "'1. '  | NUMBERED",
        "'[] '  | TODO",
        "'> '   | QUOTE",
        "'```'  | CODE",
        "'---'  | DIVIDER",
    })
    void matchesKnownPrefixes(String prefix, BlockType expected) {
        assertEquals(Optional.of(expected), MarkdownShortcuts.match(prefix));
    }

    @Test
    void unknownPrefixDoesNotMatch() {
        assertTrue(MarkdownShortcuts.match("#").isEmpty());
        assertTrue(MarkdownShortcuts.match("hello").isEmpty());
    }

    @Test
    void detectFindsShortcutAtStartOfText() {
        var hit = MarkdownShortcuts.detect("## 見出し", 3).orElseThrow();
        assertEquals(BlockType.H2, hit.type());
        assertEquals(3, hit.prefixLength());
    }

    @Test
    void detectIgnoresShortcutWhenCaretIsNotRightAfterPrefix() {
        assertTrue(MarkdownShortcuts.detect("## 見出し", 5).isEmpty());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "'＃　'     | H1",
        "'＃＃　'   | H2",
        "'ー　'     | BULLET",
        "'１．　'   | NUMBERED",
        "'１。　'   | NUMBERED",
        "'「」　'   | TODO",
        "'＞　'     | QUOTE",
        "'ーーー'   | DIVIDER",
    })
    void fullWidthPrefixesFromJapaneseInputAlsoMatch(String prefix, BlockType expected) {
        var hit = MarkdownShortcuts.detect(prefix + "本文", prefix.length()).orElseThrow();
        assertEquals(expected, hit.type());
        assertEquals(prefix.length(), hit.prefixLength());
    }
}
