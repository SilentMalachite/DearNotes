package app.dearnotes.export

import app.dearnotes.model.Block
import app.dearnotes.model.BlockType
import app.dearnotes.model.Mark
import app.dearnotes.model.Page
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

internal class ExportTreeTest {

    @TempDir
    lateinit var dir: Path

    private fun page(id: String, parentId: String?, title: String, sortKey: Double): Page {
        return Page(id, parentId, title, null, null, sortKey, 0L, 0L, null)
    }

    private fun titled(title: String): Page = page("x", null, title, 0.0)

    @Test
    fun fileNameReplacesCharactersThatAreNotAllowed() {
        assertEquals("a_b_c_d_e_f_g_h_i", ExportTree.fileName(titled("a/b\\c:d*e?f\"g<h>i")))
        assertEquals("a_b", ExportTree.fileName(titled("a|b")))
        assertEquals("京都 旅行", ExportTree.fileName(titled(" 京都 旅行 ")))
    }

    @Test
    fun fileNameAvoidsNamesWindowsRefuses() {
        assertEquals("_CON", ExportTree.fileName(titled("con".uppercase())))
        assertEquals("_con", ExportTree.fileName(titled("con")))
        assertEquals("メモ", ExportTree.fileName(titled("メモ. . ")))
        assertEquals("_", ExportTree.fileName(titled("..")))
        assertEquals("_bashrc", ExportTree.fileName(titled(".bashrc")))
        assertEquals("_CON.txt", ExportTree.fileName(titled("CON.txt")))
        assertEquals("_nul.tar.gz", ExportTree.fileName(titled("nul.tar.gz")))
        assertEquals("CONSOLE", ExportTree.fileName(titled("CONSOLE")))
    }

    @Test
    fun untitledPageIsCalledUntitled() {
        assertEquals("無題", ExportTree.fileName(titled("  ")))
    }

    @Test
    fun longNamesAreCutWithoutBreakingCharacters() {
        val name = ExportTree.fileName(titled("😀".repeat(150)))
        assertEquals(100, name.codePointCount(0, name.length))
        assertTrue(name.codePoints().allMatch { c -> c == "😀".codePointAt(0) })
    }

    @Test
    fun cutNameDoesNotEndWithADotOrSpace() {
        val name = ExportTree.fileName(titled("a".repeat(98) + ". b"))
        assertEquals("a".repeat(98), name)
    }

    @Test
    fun pageNamedLikeASiblingsFileDoesNotCollide() {
        // 「大阪」の .md と、「大阪.md」という名前のページの子フォルダが、同じパスにならない
        val root = page("r", null, "旅行", 0.0)
        val osaka = page("o", "r", "大阪", 1.0)
        val osakaMd = page("m", "r", "大阪.md", 2.0)
        val child = page("c", "m", "子", 1.0)
        val readme = page("x", "r", "readme", 3.0)
        val readmeMd = page("y", "r", "README.md", 4.0)
        val child2 = page("z", "y", "子", 1.0)

        ExportTree.write(dir, root, listOf(root, osaka, osakaMd, child, readme, readmeMd, child2), NO_BLOCKS)

        val top = dir.resolve("旅行")
        assertTrue(Files.isRegularFile(top.resolve("大阪.md")))
        assertTrue(Files.isRegularFile(top.resolve("大阪.md (2).md")))
        assertTrue(Files.isRegularFile(top.resolve("大阪.md (2)").resolve("子.md")))
        assertTrue(Files.isRegularFile(top.resolve("readme.md")))
        assertTrue(Files.isRegularFile(top.resolve("README.md (2)").resolve("子.md")))
    }

    @Test
    fun writeReplacingKeepsTheOldFileUntilTheNewOneIsDone() {
        val file = dir.resolve("京都.md")
        Files.writeString(file, "前の")
        ExportTree.writeReplacing(file, "新しい")
        assertEquals("新しい", Files.readString(file))
        assertEquals(listOf("京都.md"), list(dir))
    }

    @Test
    fun writeReplacingCreatesANewFile() {
        val file = dir.resolve("新.md")
        ExportTree.writeReplacing(file, "中身")
        assertEquals("中身", Files.readString(file))
        assertEquals(listOf("新.md"), list(dir))
    }

    @Test
    fun writesOneFilePerPageInANewFolder() {
        val root = page("r", null, "旅行", 0.0)
        val kyoto = page("k", "r", "京都", 1.0)
        val osaka = page("o", "r", "大阪", 2.0)
        val day = page("d", "o", "1日目", 1.0)
        val other = page("z", null, "ほか", 1.0)
        val text = mapOf("r" to "まえがき", "k" to "伏見稲荷", "o" to "道頓堀", "d" to "朝")
        val blocks = ExportTree.Blocks { id ->
            listOf(
                Block(
                    "b$id",
                    id,
                    BlockType.PARAGRAPH,
                    text[id] ?: "",
                    emptyList(),
                    false,
                    0,
                    0.0,
                    false,
                ),
            )
        }

        val written = ExportTree.write(dir, root, listOf(root, osaka, kyoto, day, other), blocks)

        val top = dir.resolve("旅行")
        assertEquals(
            listOf(
                top.resolve("旅行.md"),
                top.resolve("京都.md"),
                top.resolve("大阪.md"),
                top.resolve("大阪").resolve("1日目.md"),
            ),
            written,
        )
        assertEquals("# 京都\n\n伏見稲荷\n", Files.readString(top.resolve("京都.md"), StandardCharsets.UTF_8))
        assertEquals(listOf("1日目.md"), list(top.resolve("大阪")))
        assertFalse(Files.exists(dir.resolve("ほか.md")))
    }

    @Test
    fun existingFolderIsNotTouched() {
        Files.createDirectory(dir.resolve("旅行"))
        Files.writeString(dir.resolve("旅行").resolve("旅行.md"), "前の")
        val root = page("r", null, "旅行", 0.0)

        ExportTree.write(dir, root, listOf(root), NO_BLOCKS)

        assertEquals("前の", Files.readString(dir.resolve("旅行").resolve("旅行.md")))
        assertEquals("# 旅行\n", Files.readString(dir.resolve("旅行 (2)").resolve("旅行.md")))
    }

    @Test
    fun siblingsWithTheSameNameGetNumbers() {
        val root = page("r", null, "旅行", 0.0)
        val a = page("a", "r", "メモ", 1.0)
        val b = page("b", "r", "メモ", 2.0)
        val c = page("c", "r", "旅行", 3.0)
        val d = page("d", "r", "MEMO", 4.0)
        val e = page("e", "r", "memo", 5.0)

        ExportTree.write(dir, root, listOf(root, a, b, c, d, e), NO_BLOCKS)

        assertEquals(
            listOf("MEMO.md", "memo (2).md", "メモ (2).md", "メモ.md", "旅行 (2).md", "旅行.md"),
            list(dir.resolve("旅行")),
        )
    }

    @Test
    fun failureKeepsWhatWasWritten() {
        val root = page("r", null, "旅行", 0.0)
        val a = page("a", "r", "京都", 1.0)
        val blocks = ExportTree.Blocks { id ->
            if (id == "a") throw SQLException("読めない")
            emptyList()
        }

        val e = assertThrows(ExportTree.ExportException::class.java) {
            ExportTree.write(dir, root, listOf(root, a), blocks)
        }

        assertEquals(listOf(dir.resolve("旅行").resolve("旅行.md")), e.written())
        assertEquals("読めない", e.cause!!.message)
    }

    @Test
    fun linksBetweenExportedPagesBecomeRelativePaths() {
        val trip = page("t", null, "旅行", 0.0)
        val kyoto = page("k", "t", "京都", 1.0)
        val osaka = page("o", "t", "大阪", 2.0)
        val day1 = page("d", "o", "1日目", 0.0)
        val books = page("b", null, "読書メモ", 3.0)
        val bodies = mapOf(
            "t" to listOf(linkTo("京都", "k")),
            "d" to listOf(linkTo("京都", "k"), linkTo("読書メモ", "b")),
        )

        ExportTree.write(dir, trip, listOf(trip, kyoto, osaka, day1, books)) { id ->
            bodies[id] ?: emptyList()
        }

        val top = dir.resolve("旅行")
        assertEquals("# 旅行\n\n[京都](京都.md)\n", Files.readString(top.resolve("旅行.md")))
        assertEquals(
            "# 1日目\n\n[京都](../京都.md)\n\n読書メモ\n",
            Files.readString(top.resolve("大阪").resolve("1日目.md")),
        )
    }

    private fun linkTo(text: String, target: String): Block {
        return Block(
            "b$target$text",
            "x",
            BlockType.PARAGRAPH,
            text,
            listOf(Mark(0, text.length, Mark.Style.PAGE, target)),
            false,
            0,
            0.0,
            false,
        )
    }

    private fun list(folder: Path): List<String> {
        return Files.list(folder).use { s ->
            s.map { p -> p.fileName!!.toString() }.sorted().toList()
        }
    }

    companion object {
        private val NO_BLOCKS = ExportTree.Blocks { _ -> emptyList() }
    }
}
