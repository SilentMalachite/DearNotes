package app.dearnotes.editor

import app.dearnotes.model.BlockType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class MarkdownShortcutsTest {
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
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
        ],
    )
    fun matchesKnownPrefixes(prefix: String, expected: BlockType) {
        assertEquals(expected, MarkdownShortcuts.match(prefix))
    }

    @Test
    fun unknownPrefixDoesNotMatch() {
        assertNull(MarkdownShortcuts.match("#"))
        assertNull(MarkdownShortcuts.match("hello"))
    }

    @Test
    fun detectFindsShortcutAtStartOfText() {
        val hit = MarkdownShortcuts.detect("## 見出し", 3) ?: error("missing")
        assertEquals(BlockType.H2, hit.type)
        assertEquals(3, hit.prefixLength)
    }

    @Test
    fun detectIgnoresShortcutWhenCaretIsNotRightAfterPrefix() {
        assertNull(MarkdownShortcuts.detect("## 見出し", 5))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "'＃　'     | H1",
            "'＃＃　'   | H2",
            "'ー　'     | BULLET",
            "'１．　'   | NUMBERED",
            "'１。　'   | NUMBERED",
            "'「」　'   | TODO",
            "'＞　'     | QUOTE",
            "'ーーー'   | DIVIDER",
        ],
    )
    fun fullWidthPrefixesFromJapaneseInputAlsoMatch(prefix: String, expected: BlockType) {
        val hit = MarkdownShortcuts.detect(prefix + "本文", prefix.length) ?: error("missing")
        assertEquals(expected, hit.type)
        assertEquals(prefix.length, hit.prefixLength)
    }
}
