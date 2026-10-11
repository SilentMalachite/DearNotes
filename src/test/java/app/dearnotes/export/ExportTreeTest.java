package app.dearnotes.export;

import static org.junit.jupiter.api.Assertions.*;

import app.dearnotes.model.Block;
import app.dearnotes.model.BlockType;
import app.dearnotes.model.Page;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportTreeTest {

    @TempDir
    Path dir;

    private static Page page(String id, String parentId, String title, double sortKey) {
        return new Page(id, parentId, title, null, null, sortKey, 0, 0, null);
    }

    private static Page titled(String title) {
        return page("x", null, title, 0);
    }

    private static final ExportTree.Blocks NO_BLOCKS = id -> List.of();

    @Test
    void fileNameReplacesCharactersThatAreNotAllowed() {
        assertEquals("a_b_c_d_e_f_g_h_i", ExportTree.fileName(titled("a/b\\c:d*e?f\"g<h>i")));
        assertEquals("a_b", ExportTree.fileName(titled("a|b")));
        assertEquals("京都 旅行", ExportTree.fileName(titled(" 京都 旅行 ")));
    }

    @Test
    void fileNameAvoidsNamesWindowsRefuses() {
        assertEquals("_CON", ExportTree.fileName(titled("con".toUpperCase())));
        assertEquals("_con", ExportTree.fileName(titled("con")));
        assertEquals("メモ", ExportTree.fileName(titled("メモ. . ")));
        assertEquals("_", ExportTree.fileName(titled("..")));
        assertEquals("_bashrc", ExportTree.fileName(titled(".bashrc")));
        assertEquals("_CON.txt", ExportTree.fileName(titled("CON.txt")));
        assertEquals("_nul.tar.gz", ExportTree.fileName(titled("nul.tar.gz")));
        assertEquals("CONSOLE", ExportTree.fileName(titled("CONSOLE")));
    }

    @Test
    void untitledPageIsCalledUntitled() {
        assertEquals("無題", ExportTree.fileName(titled("  ")));
    }

    @Test
    void longNamesAreCutWithoutBreakingCharacters() {
        String name = ExportTree.fileName(titled("😀".repeat(150)));
        assertEquals(100, name.codePointCount(0, name.length()));
        assertTrue(name.codePoints().allMatch(c -> c == "😀".codePointAt(0)));
    }

    @Test
    void cutNameDoesNotEndWithADotOrSpace() {
        String name = ExportTree.fileName(titled("a".repeat(98) + ". b"));
        assertEquals("a".repeat(98), name);
    }

    @Test
    void pageNamedLikeASiblingsFileDoesNotCollide() throws Exception {
        // 「大阪」の .md と、「大阪.md」という名前のページの子フォルダが、同じパスにならない
        Page root = page("r", null, "旅行", 0);
        Page osaka = page("o", "r", "大阪", 1);
        Page osakaMd = page("m", "r", "大阪.md", 2);
        Page child = page("c", "m", "子", 1);
        Page readme = page("x", "r", "readme", 3);
        Page readmeMd = page("y", "r", "README.md", 4);
        Page child2 = page("z", "y", "子", 1);

        ExportTree.write(dir, root, List.of(root, osaka, osakaMd, child, readme, readmeMd, child2), NO_BLOCKS);

        Path top = dir.resolve("旅行");
        assertTrue(Files.isRegularFile(top.resolve("大阪.md")));
        assertTrue(Files.isRegularFile(top.resolve("大阪.md (2).md")));
        assertTrue(Files.isRegularFile(top.resolve("大阪.md (2)").resolve("子.md")));
        assertTrue(Files.isRegularFile(top.resolve("readme.md")));
        assertTrue(Files.isRegularFile(top.resolve("README.md (2)").resolve("子.md")));
    }

    @Test
    void writeReplacingKeepsTheOldFileUntilTheNewOneIsDone() throws Exception {
        Path file = dir.resolve("京都.md");
        Files.writeString(file, "前の");
        ExportTree.writeReplacing(file, "新しい");
        assertEquals("新しい", Files.readString(file));
        assertEquals(List.of("京都.md"), list(dir));
    }

    @Test
    void writeReplacingCreatesANewFile() throws Exception {
        Path file = dir.resolve("新.md");
        ExportTree.writeReplacing(file, "中身");
        assertEquals("中身", Files.readString(file));
        assertEquals(List.of("新.md"), list(dir));
    }

    @Test
    void writesOneFilePerPageInANewFolder() throws Exception {
        Page root = page("r", null, "旅行", 0);
        Page kyoto = page("k", "r", "京都", 1);
        Page osaka = page("o", "r", "大阪", 2);
        Page day = page("d", "o", "1日目", 1);
        Page other = page("z", null, "ほか", 1);
        var text = Map.of("r", "まえがき", "k", "伏見稲荷", "o", "道頓堀", "d", "朝");
        ExportTree.Blocks blocks = id -> List.of(
            new Block("b" + id, id, BlockType.PARAGRAPH, text.getOrDefault(id, ""), List.of(), false, 0, 0));

        List<Path> written = ExportTree.write(dir, root, List.of(root, osaka, kyoto, day, other), blocks);

        Path top = dir.resolve("旅行");
        assertEquals(List.of(top.resolve("旅行.md"), top.resolve("京都.md"), top.resolve("大阪.md"),
            top.resolve("大阪").resolve("1日目.md")), written);
        assertEquals("# 京都\n\n伏見稲荷\n", Files.readString(top.resolve("京都.md"), StandardCharsets.UTF_8));
        assertEquals(List.of("1日目.md"), list(top.resolve("大阪")));
        assertFalse(Files.exists(dir.resolve("ほか.md")));
    }

    @Test
    void existingFolderIsNotTouched() throws Exception {
        Files.createDirectory(dir.resolve("旅行"));
        Files.writeString(dir.resolve("旅行").resolve("旅行.md"), "前の");
        Page root = page("r", null, "旅行", 0);

        ExportTree.write(dir, root, List.of(root), NO_BLOCKS);

        assertEquals("前の", Files.readString(dir.resolve("旅行").resolve("旅行.md")));
        assertEquals("# 旅行\n", Files.readString(dir.resolve("旅行 (2)").resolve("旅行.md")));
    }

    @Test
    void siblingsWithTheSameNameGetNumbers() throws Exception {
        Page root = page("r", null, "旅行", 0);
        Page a = page("a", "r", "メモ", 1);
        Page b = page("b", "r", "メモ", 2);
        Page c = page("c", "r", "旅行", 3);
        Page d = page("d", "r", "MEMO", 4);
        Page e = page("e", "r", "memo", 5);

        ExportTree.write(dir, root, List.of(root, a, b, c, d, e), NO_BLOCKS);

        assertEquals(List.of("MEMO.md", "memo (2).md", "メモ (2).md", "メモ.md", "旅行 (2).md", "旅行.md"),
            list(dir.resolve("旅行")));
    }

    @Test
    void failureKeepsWhatWasWritten() {
        Page root = page("r", null, "旅行", 0);
        Page a = page("a", "r", "京都", 1);
        ExportTree.Blocks blocks = id -> {
            if (id.equals("a")) throw new java.sql.SQLException("読めない");
            return List.of();
        };

        var e = assertThrows(ExportTree.ExportException.class,
            () -> ExportTree.write(dir, root, List.of(root, a), blocks));

        assertEquals(List.of(dir.resolve("旅行").resolve("旅行.md")), e.written());
        assertEquals("読めない", e.getCause().getMessage());
    }

    private static List<String> list(Path folder) throws IOException {
        try (Stream<Path> s = Files.list(folder)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}
