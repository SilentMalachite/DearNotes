package app.dearnotes.ui;

import static org.junit.jupiter.api.Assertions.*;

import app.dearnotes.db.BlockRepo;
import app.dearnotes.db.Database;
import app.dearnotes.db.PageRepo;
import app.dearnotes.db.SearchRepo;
import app.dearnotes.model.Block;
import app.dearnotes.model.BlockType;
import app.dearnotes.model.Mark;
import app.dearnotes.save.AutoSaver;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 本物の画面を作り、キーイベントを直接送って編集の動きを確かめる。
 * OS の入力メソッドを通さないので、日本語 IME の状態に左右されない。
 */
class PageEditorUiTest {

    private static boolean fxReady;

    @TempDir
    Path dir;

    Database db;
    PageRepo pages;
    BlockRepo blocks;
    AutoSaver saver;
    Services services;
    Stage stage;
    MainWindow window;
    /** true のあいだ、ブロックの保存を失敗させる（別の接続がロックしているときの代わり）。 */
    volatile boolean failSaves;

    @BeforeAll
    static void startFx() {
        if (fxReady) return;
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyStarted) {
            // 他のテストが先に起動した
        } catch (UnsupportedOperationException | Error noDisplay) {
            Assumptions.abort("画面がないので UI テストを飛ばす: " + noDisplay);
        }
        Platform.setImplicitExit(false);
        fxReady = true;
    }

    @BeforeEach
    void openWindow() throws Exception {
        System.setProperty("dearnotes.dataDir", dir.toString());
        db = Database.open(dir.resolve("ui.db"));
        pages = new PageRepo(db);
        blocks = new BlockRepo(db);
        saver = new AutoSaver((upserts, deletes) -> {
            if (failSaves) throw new java.sql.SQLException("database is locked");
            blocks.save(upserts, deletes);
        }, Platform::runLater, 50, 1000);
        services = new Services(db, pages, blocks, new SearchRepo(db), saver, url -> {});
        fx(() -> {
            stage = new Stage();
            window = new MainWindow(stage, services);
            stage.show();
            window.newPage(null);
            return null;
        });
        settle();
    }

    @AfterEach
    void close() throws Exception {
        fx(() -> {
            stage.close();
            return null;
        });
        saver.close();
        db.close();
        System.clearProperty("dearnotes.dataDir");
    }

    @Test
    void markdownShortcutsAndEnterBuildAList() throws Exception {
        focusFirstBlock();
        type("# Weekend");
        press(KeyCode.ENTER);
        type("- milk");
        press(KeyCode.ENTER);
        type("coffee beans");
        press(KeyCode.ENTER);
        press(KeyCode.ENTER);          // 空のリスト項目で Enter → テキストに戻る
        type("[] batteries");
        press(KeyCode.ENTER);
        press(KeyCode.BACK_SPACE);     // 空の ToDo で Backspace → テキストに戻り、
        type("1. one");                // そこで「1. 」を打つと番号付きになる
        press(KeyCode.ENTER);
        type("two");
        press(KeyCode.TAB);
        press(KeyCode.ENTER);
        type("three");

        assertEquals(List.of(
                "h1:0:Weekend",
                "bullet:0:milk",
                "bullet:0:coffee beans",
                "todo:0:batteries",
                "numbered:0:one",
                "numbered:1:two",
                "numbered:1:three"),
            summary());
        assertEquals(List.of("1.", "1.", "2."), fx(() -> window.editor().views().stream()
            .filter(v -> v.block().type() == BlockType.NUMBERED)
            .map(v -> ((javafx.scene.control.Label) v.lookup(".block-bullet")).getText()).toList()));
    }

    @Test
    void editsAreSavedToTheDatabaseInOrder() throws Exception {
        focusFirstBlock();
        type("first");
        press(KeyCode.ENTER);
        type("second");
        press(KeyCode.ENTER);
        type("third");
        press(KeyCode.UP, KeyMod.SHORTCUT, KeyMod.SHIFT);   // third を1つ上へ

        assertTrue(saver.flushAndWait(2, TimeUnit.SECONDS));
        String pageId = fx(() -> window.editor().page().id());
        assertEquals(List.of("first", "third", "second"),
            blocks.listByPage(pageId).stream().map(Block::text).toList());
    }

    @Test
    void enterSplitsAndBackspaceMergesWithMarks() throws Exception {
        focusFirstBlock();
        type("hello world");
        // "hello" を太字にする
        fx(() -> {
            current().area().selectRange(0, 5);
            return null;
        });
        press(KeyCode.B, KeyMod.SHORTCUT);
        fx(() -> {
            current().area().moveTo(5);
            return null;
        });
        press(KeyCode.ENTER);
        assertEquals(List.of("paragraph:0:hello", "paragraph:0: world"), summary());

        press(KeyCode.BACK_SPACE);
        assertEquals(List.of("paragraph:0:hello world"), summary());
        Block merged = fx(() -> window.editor().views().getFirst().block());
        assertEquals(List.of(new Mark(0, 5, Mark.Style.BOLD)), merged.marks());
        assertEquals(5, fx(() -> current().caret()));
    }

    @Test
    void slashMenuChangesBlockType() throws Exception {
        focusFirstBlock();
        type("/見");
        press(KeyCode.DOWN);
        press(KeyCode.ENTER);
        assertEquals(List.of("h2:0:"), summary());
    }

    @Test
    void dividerShortcutAddsLineAndContinuesBelow() throws Exception {
        focusFirstBlock();
        type("---");
        type("after");
        assertEquals(List.of("divider:0:", "paragraph:0:after"), summary());
    }

    @Test
    void reopeningPageShowsSavedBlocks() throws Exception {
        focusFirstBlock();
        type("> 引用です");
        String pageId = fx(() -> window.editor().page().id());
        fx(() -> {
            window.openToday();
            return null;
        });
        settle();
        fx(() -> {
            window.open(pages.get(pageId).orElseThrow());
            return null;
        });
        settle();
        assertEquals(List.of("quote:0:引用です"), summary());
    }

    @Test
    void pastingMarkdownCreatesBlocks() throws Exception {
        focusFirstBlock();
        fx(() -> {
            var content = new javafx.scene.input.ClipboardContent();
            content.putString("# 週末\n- 牛乳\n  - 低脂肪\n```\nbuy(milk)\nbuy(bread)\n```\n後");
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            return null;
        });
        press(KeyCode.V, KeyMod.SHORTCUT);
        assertEquals(List.of(
                "h1:0:週末",
                "bullet:0:牛乳",
                "bullet:1:低脂肪",
                "code:0:buy(milk)\nbuy(bread)",
                "paragraph:0:後"),
            summary());
    }

    @Test
    void pastingIntoMiddleOfTextKeepsTail() throws Exception {
        focusFirstBlock();
        type("AB");
        fx(() -> {
            current().area().moveTo(1);
            var content = new javafx.scene.input.ClipboardContent();
            content.putString("x\ny");
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            return null;
        });
        press(KeyCode.V, KeyMod.SHORTCUT);
        assertEquals(List.of("paragraph:0:Ax", "paragraph:0:yB"), summary());
    }

    @Test
    void reloadingPlainTextDoesNotInheritOldStyles() throws Exception {
        List<Mark> marks = fx(() -> {
            var area = new org.fxmisc.richtext.StyleClassedTextArea();
            StyledText.load(area, "太字", List.of(new Mark(0, 2, Mark.Style.BOLD)));
            StyledText.load(area, "ふつう\nの文", List.of());
            return StyledText.read(area);
        });
        assertEquals(List.of(), marks);
    }

    @Test
    void deleteOnDividerRemovesItAndKeepsNextBlock() throws Exception {
        focusFirstBlock();
        type("---");
        type("after");
        fx(() -> {
            window.editor().views().getFirst().focusAt(0);
            return null;
        });
        press(KeyCode.DELETE);
        assertEquals(List.of("paragraph:0:after"), summary());
    }

    @Test
    void enterWithSelectionReplacesIt() throws Exception {
        focusFirstBlock();
        type("abcdef");
        fx(() -> {
            current().area().selectRange(2, 4);
            return null;
        });
        press(KeyCode.ENTER);
        assertEquals(List.of("paragraph:0:ab", "paragraph:0:ef"), summary());
    }

    @Test
    void failedFlushKeepsCurrentPageAndUnsavedEdits() throws Exception {
        focusFirstBlock();
        type("AB");
        assertTrue(saver.flushAndWait(2, TimeUnit.SECONDS));
        String pageId = fx(() -> window.editor().page().id());
        var other = pages.create(null, "別のページ");

        failSaves = true;
        type("C");
        fx(() -> {
            window.open(other);
            return null;
        });
        settle();
        // 保存できないあいだはページを切り替えず、書いた文字も残す
        assertEquals(pageId, fx(() -> window.editor().page().id()));
        assertEquals(List.of("paragraph:0:ABC"), summary());

        failSaves = false;
        assertTrue(saver.flushAndWait(2, TimeUnit.SECONDS));
        assertEquals(List.of("ABC"), blocks.listByPage(pageId).stream().map(Block::text).toList());
    }

    @Test
    void failedFlushDoesNotCreateOrRestorePages() throws Exception {
        focusFirstBlock();
        type("AB");
        assertTrue(saver.flushAndWait(2, TimeUnit.SECONDS));
        int before = pages.listAlive().size();

        failSaves = true;
        type("C");
        fx(() -> {
            window.newPage(null);
            window.openToday();
            return null;
        });
        settle();
        // 切り替えられないときは、新しいページも今日の日記も作らない
        assertEquals(before, pages.listAlive().size());
        assertEquals(List.of("paragraph:0:ABC"), summary());
    }

    @Test
    void failedBlockReadKeepsCurrentPage() throws Exception {
        focusFirstBlock();
        type("AB");
        assertTrue(saver.flushAndWait(2, TimeUnit.SECONDS));
        String pageId = fx(() -> window.editor().page().id());
        var other = pages.create(null, "別のページ");
        db.write(c -> {
            try (var st = c.createStatement()) {
                return st.executeUpdate("ALTER TABLE blocks RENAME TO blocks_gone");
            }
        });

        fx(() -> {
            window.open(other);
            return null;
        });
        settle();
        assertEquals(pageId, fx(() -> window.editor().page().id()));
        assertEquals(List.of("paragraph:0:AB"), summary());
    }

    @Test
    void clickingStaleSearchResultDoesNotOpenIt() throws Exception {
        pages.create(null, "東京メモ");
        var opened = new java.util.ArrayList<String>();
        var dialog = fx(() -> new SearchDialog(stage, services, h -> opened.add(h.pageId())));
        fx(() -> {
            dialog.field().setText("東京");
            dialog.show();
            return null;
        });
        settle();
        fx(() -> {
            // 待ち時間（120ms）が過ぎる前に、古い結果の行を押す
            dialog.field().setText("大阪");
            dialog.results().fireEvent(new javafx.scene.input.MouseEvent(
                javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                javafx.scene.input.MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, false, null));
            return null;
        });
        settle();
        assertEquals(List.of(), opened);
    }

    @Test
    void pastingLinesEndingWithDividerKeepsTailVisible() throws Exception {
        focusFirstBlock();
        type("AB");
        fx(() -> {
            current().area().moveTo(1);
            var content = new javafx.scene.input.ClipboardContent();
            content.putString("x\n---");
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            return null;
        });
        press(KeyCode.V, KeyMod.SHORTCUT);
        assertEquals(List.of("paragraph:0:Ax", "divider:0:", "paragraph:0:B"), summary());
    }

    @Test
    void slashInCodeBlockIsPlainText() throws Exception {
        focusFirstBlock();
        type("```");
        assertEquals(List.of("code:0:"), summary());
        type("/");
        press(KeyCode.ENTER);
        assertEquals(List.of("code:0:/\n"), summary());
    }

    @Test
    void shiftDownSelectsTextInsteadOfMovingToNextBlock() throws Exception {
        focusFirstBlock();
        type("abcd");
        press(KeyCode.ENTER);
        type("efgh");
        fx(() -> {
            window.editor().views().getFirst().focusAt(2);
            return null;
        });
        settle();
        press(KeyCode.DOWN, KeyMod.SHIFT);
        BlockView first = fx(() -> window.editor().views().getFirst());
        assertSame(first, fx(this::current));
        assertEquals("cd", fx(() -> first.area().getSelectedText()));

        press(KeyCode.UP, KeyMod.SHIFT);
        assertSame(first, fx(this::current));
        assertEquals("ab", fx(() -> first.area().getSelectedText()));
    }

    @Test
    void enterRightAfterChangingQueryOpensNewResult() throws Exception {
        var tokyo = pages.create(null, "東京メモ");
        var osaka = pages.create(null, "大阪メモ");
        var opened = new java.util.ArrayList<String>();
        var dialog = fx(() -> new SearchDialog(stage, services, h -> opened.add(h.pageId())));
        fx(() -> {
            dialog.field().setText("東京");
            dialog.show();
            return null;
        });
        settle();
        fx(() -> {
            // 待ち時間（120ms）が過ぎる前に Enter を押す
            dialog.field().setText("大阪");
            dialog.field().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER,
                false, false, false, false));
            return null;
        });
        settle();
        assertEquals(List.of(osaka.id()), opened);
        assertNotEquals(tokyo.id(), opened.getFirst());
    }

    @Test
    void foldingHeadingHidesItsSectionAndIsKeptAfterReopening() throws Exception {
        focusFirstBlock();
        type("## 1日目");
        press(KeyCode.ENTER);
        type("伏見稲荷");
        press(KeyCode.ENTER);
        type("お昼");
        press(KeyCode.ENTER);
        type("## 2日目");
        press(KeyCode.ENTER);
        type("嵐山");
        focusEndOf(0);

        press(KeyCode.ENTER, KeyMod.SHORTCUT);
        assertEquals(List.of("h2:0:1日目", "h2:0:2日目", "paragraph:0:嵐山"), visibleSummary());

        // 畳んだ見出しで Enter: 隠れた行の後に同じ段の見出しが入り、節は閉じたまま
        press(KeyCode.ENTER);
        assertEquals(List.of("h2:0:1日目", "h2:0:", "h2:0:2日目", "paragraph:0:嵐山"), visibleSummary());
        assertEquals(3, fx(() -> window.editor().views().indexOf(current())));
        // すぐ Backspace で消しても、節は開かない
        press(KeyCode.BACK_SPACE);
        assertEquals(List.of("h2:0:1日目", "h2:0:2日目", "paragraph:0:嵐山"), visibleSummary());
        assertEquals("1日目", fx(() -> current().block().text()));

        assertTrue(saver.flushAndWait(2, TimeUnit.SECONDS));
        String pageId = fx(() -> window.editor().page().id());
        assertTrue(blocks.listByPage(pageId).getFirst().collapsed());
        reopen(pageId);
        assertEquals(List.of("h2:0:1日目", "h2:0:2日目", "paragraph:0:嵐山"), visibleSummary());

        focusEndOf(0);
        press(KeyCode.ENTER, KeyMod.SHORTCUT);
        assertEquals(5, visibleSummary().size());
    }

    @Test
    void arrowsSkipHiddenRowsAndEnterAddsAfterThem() throws Exception {
        typeBookList();
        focusEndOf(0);
        press(KeyCode.ENTER, KeyMod.SHORTCUT);
        assertEquals(List.of("bullet:0:読む本", "bullet:0:電池"), visibleSummary());

        press(KeyCode.DOWN);
        assertEquals("電池", fx(() -> current().block().text()));
        press(KeyCode.UP);
        assertEquals("読む本", fx(() -> current().block().text()));

        press(KeyCode.ENTER);
        assertEquals(List.of("bullet:0:読む本", "bullet:1:古都", "bullet:1:檸檬", "bullet:0:", "bullet:0:電池"),
            summary());
        assertEquals(3, fx(() -> window.editor().views().indexOf(current())));
        assertTrue(fx(() -> window.editor().views().getFirst().block().collapsed()));
    }

    @Test
    void movingCollapsedRowMovesHiddenRowsWithIt() throws Exception {
        typeBookList();
        focusEndOf(0);
        press(KeyCode.ENTER, KeyMod.SHORTCUT);
        press(KeyCode.DOWN, KeyMod.SHORTCUT, KeyMod.SHIFT);
        assertEquals(List.of("bullet:0:電池", "bullet:0:読む本", "bullet:1:古都", "bullet:1:檸檬"), summary());
        assertEquals("読む本", fx(() -> current().block().text()));

        assertTrue(saver.flushAndWait(2, TimeUnit.SECONDS));
        String pageId = fx(() -> window.editor().page().id());
        assertEquals(List.of("電池", "読む本", "古都", "檸檬"),
            blocks.listByPage(pageId).stream().map(Block::text).toList());

        // 上の行は、畳んだまとまりごと飛び越える
        focusEndOf(0);
        press(KeyCode.DOWN, KeyMod.SHORTCUT, KeyMod.SHIFT);
        assertEquals(List.of("bullet:0:読む本", "bullet:1:古都", "bullet:1:檸檬", "bullet:0:電池"), summary());
    }

    @Test
    void outlineListsHeadingsAndJumpOpensCollapsedSection() throws Exception {
        focusFirstBlock();
        type("# 京都旅行");
        press(KeyCode.ENTER);
        type("## 1日目");
        press(KeyCode.ENTER);
        type("### 午前");
        press(KeyCode.ENTER);
        type("伏見稲荷");
        focusEndOf(1);
        press(KeyCode.ENTER, KeyMod.SHORTCUT);
        assertEquals(List.of("h1:0:京都旅行", "h2:0:1日目"), visibleSummary());

        fx(() -> {
            window.toggleOutline();
            return null;
        });
        assertEquals(List.of("京都旅行", "1日目", "午前"),
            fx(() -> window.outline().items().stream().map(javafx.scene.control.Label::getText).toList()));

        fx(() -> {
            var item = window.outline().items().get(2);
            item.fireEvent(new javafx.scene.input.MouseEvent(
                javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                javafx.scene.input.MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, false, null));
            return null;
        });
        settle();
        assertEquals(4, visibleSummary().size());
        assertEquals("午前", fx(() -> current().block().text()));
        assertEquals(List.of("午前"), fx(() -> window.outline().items().stream()
            .filter(l -> l.getStyleClass().contains("current")).map(javafx.scene.control.Label::getText).toList()));
    }

    @Test
    void collapsedRowWithoutChildrenLosesItsMark() throws Exception {
        var p = pages.create(null, "子のない行");
        blocks.save(List.of(Block.empty(p.id(), BlockType.BULLET, 1024).withContent("ひとり", List.of())
            .withCollapsed(true)), List.of());
        reopen(p.id());
        assertFalse(fx(() -> window.editor().views().getFirst().block().collapsed()));
        assertFalse(fx(() -> window.editor().views().getFirst().foldable()));
        assertTrue(saver.flushAndWait(2, TimeUnit.SECONDS));
        assertFalse(blocks.listByPage(p.id()).getFirst().collapsed());
    }

    @Test
    void movingCollapsedRowDoesNotHideRowsThatWereVisible() throws Exception {
        openRows(
            row(BlockType.BULLET, 0, "S", false), row(BlockType.BULLET, 1, "S1", false),
            row(BlockType.BULLET, 0, "R", true), row(BlockType.BULLET, 1, "R1", false));
        assertEquals(List.of("bullet:0:S", "bullet:1:S1", "bullet:0:R"), visibleSummary());
        focusEndOf(2);
        press(KeyCode.UP, KeyMod.SHORTCUT, KeyMod.SHIFT);
        // S1 は R の子の位置に入るが、隠さずに R を開く
        assertEquals(List.of("bullet:0:S", "bullet:0:R", "bullet:1:R1", "bullet:1:S1"), visibleSummary());
    }

    @Test
    void removingEmptyHeadingAfterCollapsedSectionKeepsFollowingRowsVisible() throws Exception {
        openRows(
            row(BlockType.H2, 0, "A", true), row(BlockType.PARAGRAPH, 0, "a1", false),
            row(BlockType.H2, 0, "", false), row(BlockType.PARAGRAPH, 0, "b1", false));
        fx(() -> {
            window.editor().views().get(2).focusAt(0);
            return null;
        });
        settle();
        press(KeyCode.BACK_SPACE);
        assertTrue(visibleSummary().contains("paragraph:0:b1"));
    }

    @Test
    void outdentingCollapsedRowDoesNotHideFollowingSibling() throws Exception {
        openRows(
            row(BlockType.BULLET, 0, "Q", false), row(BlockType.BULLET, 1, "R", true),
            row(BlockType.BULLET, 2, "R1", false), row(BlockType.BULLET, 1, "T", false));
        focusEndOf(1);
        press(KeyCode.TAB, KeyMod.SHIFT);
        assertTrue(visibleSummary().contains("bullet:1:T"));
    }

    @Test
    void clickingBelowCollapsedLastSectionKeepsItClosed() throws Exception {
        openRows(
            row(BlockType.H2, 0, "A", false), row(BlockType.PARAGRAPH, 0, "a1", false),
            row(BlockType.H2, 0, "B", true), row(BlockType.PARAGRAPH, 0, "b1", false));
        fx(() -> {
            window.editor().clickBelowLastBlock();
            return null;
        });
        settle();
        // 段落だと B の節に入って開いてしまうので、同じ段の見出しを足す
        assertEquals(List.of("h2:0:A", "paragraph:0:a1", "h2:0:B", "h2:0:"), visibleSummary());
        assertTrue(fx(() -> window.editor().views().get(2).block().collapsed()));
        assertEquals(4, fx(() -> window.editor().views().indexOf(current())));
    }

    @Test
    void clickingBelowCollapsedLastItemAddsParagraphAfterHiddenRows() throws Exception {
        openRows(row(BlockType.BULLET, 0, "R", true), row(BlockType.BULLET, 1, "R1", false));
        fx(() -> {
            window.editor().clickBelowLastBlock();
            return null;
        });
        settle();
        assertEquals(List.of("bullet:0:R", "paragraph:0:"), visibleSummary());
        assertTrue(fx(() -> window.editor().views().getFirst().block().collapsed()));
    }

    @Test
    void movingCollapsedHeadingTakesIndentedHeadingUnderItsItem() throws Exception {
        String pageId = openRows(
            row(BlockType.H2, 0, "A", true), row(BlockType.BULLET, 0, "B", true),
            row(BlockType.H2, 1, "C", false), row(BlockType.PARAGRAPH, 1, "D", false),
            row(BlockType.H1, 0, "E", false));
        assertEquals(List.of("h2:0:A", "h1:0:E"), visibleSummary());
        focusEndOf(0);
        press(KeyCode.DOWN, KeyMod.SHORTCUT, KeyMod.SHIFT);
        assertEquals(List.of("h1:0:E", "h2:0:A", "bullet:0:B", "h2:1:C", "paragraph:1:D"), summary());
        assertTrue(saver.flushAndWait(2, TimeUnit.SECONDS));
        assertEquals(List.of("E", "A", "B", "C", "D"),
            blocks.listByPage(pageId).stream().map(Block::text).toList());
    }

    @Test
    void pastingIntoHeadingKeepsOutlineInSync() throws Exception {
        openRows(row(BlockType.H2, 0, "AB", false));
        fx(() -> {
            window.toggleOutline();
            BlockView v = window.editor().views().getFirst();
            v.focusAt(1);
            window.editor().pasteLines(v, "x\n");
            return null;
        });
        settle();
        assertEquals("AxB", fx(() -> window.editor().views().getFirst().block().text()));
        assertEquals(List.of("AxB"),
            fx(() -> window.outline().items().stream().map(javafx.scene.control.Label::getText).toList()));
    }

    // ---- helpers ----

    enum KeyMod { SHIFT, SHORTCUT }

    private void focusFirstBlock() throws Exception {
        fx(() -> {
            window.editor().views().getFirst().focusAt(0);
            return null;
        });
        settle();
    }

    private void focusEndOf(int index) throws Exception {
        fx(() -> {
            window.editor().views().get(index).focusEnd();
            return null;
        });
        settle();
    }

    /** 読む本 / 古都(字下げ) / 檸檬(字下げ) / 電池 の箇条書き。 */
    private void typeBookList() throws Exception {
        focusFirstBlock();
        type("- 読む本");
        press(KeyCode.ENTER);
        type("古都");
        press(KeyCode.TAB);
        press(KeyCode.ENTER);
        type("檸檬");
        press(KeyCode.ENTER);
        press(KeyCode.ENTER);          // 空の項目で Enter → 字下げを戻す
        type("電池");
        assertEquals(List.of("bullet:0:読む本", "bullet:1:古都", "bullet:1:檸檬", "bullet:0:電池"), summary());
    }

    private void reopen(String pageId) throws Exception {
        fx(() -> {
            window.openToday();
            return null;
        });
        settle();
        fx(() -> {
            window.open(pages.get(pageId).orElseThrow());
            return null;
        });
        settle();
    }

    private record Row(BlockType type, int indent, String text, boolean collapsed) {}

    private static Row row(BlockType type, int indent, String text, boolean collapsed) {
        return new Row(type, indent, text, collapsed);
    }

    /** 行を DB に入れたページを作って開く。 */
    private String openRows(Row... rows) throws Exception {
        var p = pages.create(null, "行");
        var list = new java.util.ArrayList<Block>();
        for (int i = 0; i < rows.length; i++) {
            Row r = rows[i];
            list.add(Block.empty(p.id(), r.type(), (i + 1) * 1024.0).withIndent(r.indent())
                .withContent(r.text(), List.of()).withCollapsed(r.collapsed()));
        }
        blocks.save(list, List.of());
        reopen(p.id());
        return p.id();
    }

    private List<String> visibleSummary() throws Exception {
        return fx(() -> window.editor().views().stream()
            .filter(v -> !v.hidden())
            .map(v -> v.block().type().dbName() + ":" + v.block().indent() + ":" + v.block().text())
            .toList());
    }

    private BlockView current() {
        Node n = stage.getScene().getFocusOwner();
        for (BlockView v : window.editor().views()) {
            if (v.area() == n) return v;
        }
        throw new AssertionError("ブロックにフォーカスがない: " + n);
    }

    private void type(String text) throws Exception {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            i += ch.length();
            fx(() -> {
                Node target = stage.getScene().getFocusOwner();
                target.fireEvent(new KeyEvent(KeyEvent.KEY_TYPED, ch, "", KeyCode.UNDEFINED,
                    false, false, false, false));
                return null;
            });
            settle();
        }
    }

    private void press(KeyCode code, KeyMod... mods) throws Exception {
        boolean shift = List.of(mods).contains(KeyMod.SHIFT);
        boolean shortcut = List.of(mods).contains(KeyMod.SHORTCUT);
        boolean mac = System.getProperty("os.name").toLowerCase().startsWith("mac");
        fx(() -> {
            Node target = stage.getScene().getFocusOwner();
            target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                shift, shortcut && !mac, false, shortcut && mac));
            return null;
        });
        settle();
    }

    private List<String> summary() throws Exception {
        return fx(() -> window.editor().views().stream()
            .map(v -> v.block().type().dbName() + ":" + v.block().indent() + ":" + v.block().text())
            .toList());
    }

    /** FX スレッドに積まれた後続の処理（runLater）が終わるまで待つ。 */
    private static void settle() throws Exception {
        for (int i = 0; i < 3; i++) fx(() -> null);
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        var f = new CompletableFuture<T>();
        Platform.runLater(() -> {
            try {
                f.complete(work.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f.get(10, TimeUnit.SECONDS);
    }
}
