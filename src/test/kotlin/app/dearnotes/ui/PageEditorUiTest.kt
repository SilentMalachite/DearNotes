package app.dearnotes.ui

import app.dearnotes.db.BlockRepo
import app.dearnotes.db.Database
import app.dearnotes.db.PageRepo
import app.dearnotes.db.SearchRepo
import app.dearnotes.model.Block
import app.dearnotes.model.BlockType
import app.dearnotes.model.Mark
import app.dearnotes.model.Page
import app.dearnotes.save.AutoSaver
import java.nio.file.Path
import java.sql.SQLException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javafx.application.Platform
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.TextField
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.InputMethodEvent
import javafx.scene.input.InputMethodHighlight
import javafx.scene.input.InputMethodTextRun
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.stage.Stage
import org.fxmisc.richtext.StyleClassedTextArea
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * 本物の画面を作り、キーイベントを直接送って編集の動きを確かめる。
 * OS の入力メソッドを通さないので、日本語 IME の状態に左右されない。
 */
class PageEditorUiTest {

    @TempDir
    lateinit var dir: Path

    private lateinit var db: Database
    private lateinit var pages: PageRepo
    private lateinit var blocks: BlockRepo
    private lateinit var saver: AutoSaver
    private lateinit var services: Services
    private lateinit var stage: Stage
    private lateinit var window: MainWindow

    /** true のあいだ、ブロックの保存を失敗させる（別の接続がロックしているときの代わり）。 */
    @Volatile
    private var failSaves = false

    private val openedLinks = mutableListOf<String>()

    companion object {
        private var fxReady = false

        @JvmStatic
        @BeforeAll
        fun startFx() {
            if (fxReady) return
            try {
                Platform.startup { }
            } catch (_: IllegalStateException) {
                // 他のテストが先に起動した
            } catch (noDisplay: UnsupportedOperationException) {
                Assumptions.abort("画面がないので UI テストを飛ばす: $noDisplay")
            } catch (noDisplay: Error) {
                Assumptions.abort("画面がないので UI テストを飛ばす: $noDisplay")
            }
            Platform.setImplicitExit(false)
            fxReady = true
        }
    }

    @BeforeEach
    fun openWindow() {
        System.setProperty("dearnotes.dataDir", dir.toString())
        db = Database.open(dir.resolve("ui.db"))
        pages = PageRepo(db)
        blocks = BlockRepo(db)
        saver = AutoSaver({ upserts, deletes ->
            if (failSaves) throw SQLException("database is locked")
            blocks.save(upserts, deletes)
        }, Platform::runLater, 50L, 1000L)
        openedLinks.clear()
        services = Services(db, pages, blocks, SearchRepo(db), saver) { url -> openedLinks.add(url) }
        fx {
            stage = Stage()
            window = MainWindow(stage, services)
            stage.show()
            window.newPage(null)
            null
        }
        settle()
    }

    @AfterEach
    fun close() {
        fx {
            Errors.dismiss()
            stage.close()
            null
        }
        saver.close()
        db.close()
        System.clearProperty("dearnotes.dataDir")
    }

    @Test
    fun windowHasTheAppIconInEverySize() {
        val widths = fx {
            stage.getIcons().map { i ->
                assertFalse(i.isError(), "読めないアイコン")
                i.getWidth()
            }
        }
        assertEquals(listOf(16.0, 32.0, 48.0, 64.0, 128.0, 256.0), widths)
    }

    @Test
    fun markdownShortcutsAndEnterBuildAList() {
        focusFirstBlock()
        type("# Weekend")
        press(KeyCode.ENTER)
        type("- milk")
        press(KeyCode.ENTER)
        type("coffee beans")
        press(KeyCode.ENTER)
        press(KeyCode.ENTER)          // 空のリスト項目で Enter → テキストに戻る
        type("[] batteries")
        press(KeyCode.ENTER)
        press(KeyCode.BACK_SPACE)     // 空の ToDo で Backspace → テキストに戻り、
        type("1. one")                // そこで「1. 」を打つと番号付きになる
        press(KeyCode.ENTER)
        type("two")
        press(KeyCode.TAB)
        press(KeyCode.ENTER)
        type("three")

        assertEquals(listOf(
                "h1:0:Weekend",
                "bullet:0:milk",
                "bullet:0:coffee beans",
                "todo:0:batteries",
                "numbered:0:one",
                "numbered:1:two",
                "numbered:1:three"),
            summary())
        assertEquals(listOf("1.", "1.", "2."), fx {
            window.editor().views()
                .filter { v -> v.block().type == BlockType.NUMBERED }
                .map { v -> (v.lookup(".block-bullet") as Label).getText() }
        })
    }

    @Test
    fun editsAreSavedToTheDatabaseInOrder() {
        focusFirstBlock()
        type("first")
        press(KeyCode.ENTER)
        type("second")
        press(KeyCode.ENTER)
        type("third")
        press(KeyCode.UP, KeyMod.SHORTCUT, KeyMod.SHIFT)   // third を1つ上へ

        assertTrue(saver.flushAndWait(2L, TimeUnit.SECONDS))
        val pageId = fx { window.editor().page()!!.id }
        assertEquals(listOf("first", "third", "second"),
            blocks.listByPage(pageId).map { it.text })
    }

    @Test
    fun enterSplitsAndBackspaceMergesWithMarks() {
        focusFirstBlock()
        type("hello world")
        // "hello" を太字にする
        fx {
            current().area().selectRange(0, 5)
            null
        }
        press(KeyCode.B, KeyMod.SHORTCUT)
        fx {
            current().area().moveTo(5)
            null
        }
        press(KeyCode.ENTER)
        assertEquals(listOf("paragraph:0:hello", "paragraph:0: world"), summary())

        press(KeyCode.BACK_SPACE)
        assertEquals(listOf("paragraph:0:hello world"), summary())
        val merged = fx { window.editor().views().first().block() }
        assertEquals(listOf(Mark(0, 5, Mark.Style.BOLD)), merged.marks)
        assertEquals(5, fx { current().caret() })
    }

    @Test
    fun slashMenuChangesBlockType() {
        focusFirstBlock()
        type("/見")
        press(KeyCode.DOWN)
        press(KeyCode.ENTER)
        assertEquals(listOf("h2:0:"), summary())
    }

    @Test
    fun headingPickedFromSlashMenuShowsItsNameAtLineStart() {
        // 名前が出ないと、空の見出しは余白だけになり、カーソルが次の行に移ったように見える
        focusFirstBlock()
        type("/見")
        press(KeyCode.ENTER)
        val (text, dx) = fx {
            val v = current()
            val label = shownPlaceholder(v)
            val area = v.area()
            label?.text to label?.let { it.localToScene(it.layoutBounds).minX - area.localToScene(area.layoutBounds).minX }
        }
        assertEquals("見出し 1", text)
        assertTrue(dx!! < 10.0, "行頭に出す: $dx")
    }

    @Test
    fun placeholderShowsOnFocusedEmptyLineUntilTyped() {
        focusFirstBlock()
        assertEquals("「/」でコマンド", fx { shownPlaceholder(current())?.text })
        type("a")
        assertNull(fx { shownPlaceholder(current())?.text })
    }

    @Test
    fun dividerShortcutAddsLineAndContinuesBelow() {
        focusFirstBlock()
        type("---")
        type("after")
        assertEquals(listOf("divider:0:", "paragraph:0:after"), summary())
    }

    @Test
    fun reopeningPageShowsSavedBlocks() {
        focusFirstBlock()
        type("> 引用です")
        val pageId = fx { window.editor().page()!!.id }
        fx {
            window.openToday()
            null
        }
        settle()
        fx {
            window.open(pages.get(pageId) ?: error("missing"))
            null
        }
        settle()
        assertEquals(listOf("quote:0:引用です"), summary())
    }

    @Test
    fun pastingMarkdownCreatesBlocks() {
        focusFirstBlock()
        fx {
            val content = ClipboardContent()
            content.putString("# 週末\n- 牛乳\n  - 低脂肪\n```\nbuy(milk)\nbuy(bread)\n```\n後")
            Clipboard.getSystemClipboard().setContent(content)
            null
        }
        press(KeyCode.V, KeyMod.SHORTCUT)
        assertEquals(listOf(
                "h1:0:週末",
                "bullet:0:牛乳",
                "bullet:1:低脂肪",
                "code:0:buy(milk)\nbuy(bread)",
                "paragraph:0:後"),
            summary())
    }

    @Test
    fun pastingIntoMiddleOfTextKeepsTail() {
        focusFirstBlock()
        type("AB")
        fx {
            current().area().moveTo(1)
            val content = ClipboardContent()
            content.putString("x\ny")
            Clipboard.getSystemClipboard().setContent(content)
            null
        }
        press(KeyCode.V, KeyMod.SHORTCUT)
        assertEquals(listOf("paragraph:0:Ax", "paragraph:0:yB"), summary())
    }

    @Test
    fun reloadingPlainTextDoesNotInheritOldStyles() {
        val marks = fx {
            val area = StyleClassedTextArea()
            StyledText.load(area, "太字", listOf(Mark(0, 2, Mark.Style.BOLD)))
            StyledText.load(area, "ふつう\nの文", listOf())
            StyledText.read(area)
        }
        assertEquals(listOf<Mark>(), marks)
    }

    @Test
    fun pageLinkSurvivesLoadAndRead() {
        val links = listOf(Mark(2, 4, Mark.Style.PAGE, "p1"), Mark(4, 6, Mark.Style.PAGE, "p2"))
        val marks = fx {
            val area = StyleClassedTextArea()
            StyledText.load(area, "次は京都大阪へ", links)
            StyledText.read(area)
        }
        assertEquals(links, marks)
    }

    @Test
    fun typingAtTheEndOfPageLinkIsNotLinked() {
        val p = pages.create(null, "リンク")
        blocks.save(listOf(Block.empty(p.id, BlockType.PARAGRAPH, 1024.0)
            .withContent("京都", listOf(Mark(0, 2, Mark.Style.PAGE, "x")))), listOf())
        reopen(p.id)
        focusEndOf(0)
        type("へ")
        fx {
            current().area().moveTo(1)
            null
        }
        type("の")
        assertEquals("京の都へ", fx { current().block().text })
        assertEquals(listOf(Mark(0, 3, Mark.Style.PAGE, "x")), fx { current().block().marks })
    }

    @Test
    fun shortcutTypedBeforePageLinkKeepsTheWholeLink() {
        val p = pages.create(null, "リンク")
        blocks.save(listOf(Block.empty(p.id, BlockType.PARAGRAPH, 1024.0)
            .withContent("京都", listOf(Mark(0, 2, Mark.Style.PAGE, "x")))), listOf())
        reopen(p.id)
        fx {
            window.editor().views().first().focusAt(0)
            null
        }
        settle()
        type("# ")
        assertEquals(BlockType.H1, fx { current().block().type })
        assertEquals("京都", fx { current().block().text })
        assertEquals(listOf(Mark(0, 2, Mark.Style.PAGE, "x")), fx { current().block().marks })
    }

    @Test
    fun pastedPageLinkStaysLinkedButPastedPlainTextDoesNot() {
        val p = pages.create(null, "リンク")
        blocks.save(listOf(Block.empty(p.id, BlockType.PARAGRAPH, 1024.0)
            .withContent("京都", listOf(Mark(0, 2, Mark.Style.PAGE, "x")))), listOf())
        reopen(p.id)
        focusEndOf(0)
        fx {
            current().area().selectRange(0, 2)
            current().area().copy()
            current().area().moveTo(2)
            null
        }
        settle()
        type(" ")
        press(KeyCode.V, KeyMod.SHORTCUT)
        assertEquals("京都 京都", fx { current().block().text })
        val linked = listOf(Mark(0, 2, Mark.Style.PAGE, "x"), Mark(3, 5, Mark.Style.PAGE, "x"))
        val marks = fx { current().block().marks }
        val clip = fx { "クリップボードの形式: " + Clipboard.getSystemClipboard().getContentTypes() }
        // CI の macOS では、OS のクリップボードが装飾つきの形式をときどき保たず、ただの文字として貼られる。
        // そのときは装飾ごとの貼り付けを確かめられないので飛ばす
        if (marks != linked) {
            Assumptions.assumeTrue(fx { current().styledClipboard() }, "装飾つきの形式がない。" + clip)
        }
        assertEquals(linked, marks, clip)
        fx {
            val content = ClipboardContent()
            content.putString("へ")
            Clipboard.getSystemClipboard().setContent(content)
            null
        }
        press(KeyCode.V, KeyMod.SHORTCUT)
        assertEquals("京都 京都へ", fx { current().block().text })
        assertEquals(linked, fx { current().block().marks })
    }

    @Test
    fun typingAtTheStartOfPageLinkIsNotLinked() {
        val p = pages.create(null, "リンク")
        blocks.save(listOf(Block.empty(p.id, BlockType.PARAGRAPH, 1024.0)
            .withContent("京都", listOf(Mark(0, 2, Mark.Style.PAGE, "x")))), listOf())
        reopen(p.id)
        fx {
            window.editor().views().first().focusAt(0)
            null
        }
        settle()
        type("明日は")
        assertEquals("明日は京都", fx { current().block().text })
        assertEquals(listOf(Mark(3, 5, Mark.Style.PAGE, "x")), fx { current().block().marks })
    }

    @Test
    fun undoingDeleteAtTheEndOfPageLinkKeepsTheLink() {
        val p = pages.create(null, "リンク")
        blocks.save(listOf(Block.empty(p.id, BlockType.PARAGRAPH, 1024.0)
            .withContent("京都", listOf(Mark(0, 2, Mark.Style.PAGE, "x")))), listOf())
        reopen(p.id)
        focusEndOf(0)
        press(KeyCode.BACK_SPACE)
        assertEquals("京", fx { current().block().text })
        press(KeyCode.Z, KeyMod.SHORTCUT)
        assertEquals("京都", fx { current().block().text })
        assertEquals(listOf(Mark(0, 2, Mark.Style.PAGE, "x")), fx { current().block().marks })
    }

    @Test
    fun movingCaretAwayClosesPageSuggestions() {
        pages.create(null, "京都")
        focusFirstBlock()
        type("東京と[[京")
        fx {
            current().area().moveTo(0)
            null
        }
        settle()
        press(KeyCode.ENTER)
        // 候補は閉じているので、Enter はふつうに行を分ける
        assertEquals(listOf("paragraph:0:", "paragraph:0:東京と[[京"), summary())
        assertEquals(1, pages.findByTitle("京", 8).size)
    }

    @Test
    fun escapeKeepsSuggestionsClosedForThatBracket() {
        focusFirstBlock()
        type("[[奈良")
        press(KeyCode.ESCAPE)
        type("x")
        press(KeyCode.ENTER)
        assertEquals(listOf("paragraph:0:[[奈良x", "paragraph:0:"), summary())
        assertEquals(listOf<Page>(), pages.findByTitle("奈良", 8))
    }

    @Test
    fun pageLinkInsideBoldStaysBold() {
        val kyoto = pages.create(null, "京都")
        focusFirstBlock()
        type("[[京")
        fx {
            current().area().selectRange(0, 3)
            null
        }
        press(KeyCode.B, KeyMod.SHORTCUT)
        fx {
            current().area().moveTo(3)
            null
        }
        settle()
        type("都")
        press(KeyCode.ENTER)
        assertEquals(listOf(Mark(0, 2, Mark.Style.BOLD), Mark(0, 2, Mark.Style.PAGE, kyoto.id)),
            fx { current().block().marks })
    }

    @Test
    fun typingBracketsAndEnterInsertsPageLink() {
        val kyoto = pages.create(null, "京都")
        focusFirstBlock()
        type("次は「「京")
        press(KeyCode.ENTER)
        type("へ")
        assertEquals("次は京都へ", fx { current().block().text })
        assertEquals(listOf(Mark(2, 4, Mark.Style.PAGE, kyoto.id)), fx { current().block().marks })
        assertEquals(1, summary().size)
    }

    @Test
    fun pageLinkAtLineStartDoesNotBecomeMarkdownShortcut() {
        val rule = pages.create(null, "---")
        val hash = pages.create(null, "#")
        focusFirstBlock()
        type("[[--")
        press(KeyCode.ENTER)
        assertEquals(listOf("paragraph:0:---"), summary())
        assertEquals(listOf(Mark(0, 3, Mark.Style.PAGE, rule.id)), fx { current().block().marks })
        fx {
            current().setContent("", listOf())
            null
        }
        type("[[#")
        press(KeyCode.ENTER)
        type(" ")
        assertEquals(listOf("paragraph:0:# "), summary())
        assertEquals(listOf(Mark(0, 1, Mark.Style.PAGE, hash.id)), fx { current().block().marks })
    }

    @Test
    fun unknownNameCreatesChildPageAndStaysHere() {
        focusFirstBlock()
        val here = fx { window.editor().page()!!.id }
        type("[[奈良")
        press(KeyCode.ENTER)
        val nara = pages.findByTitle("奈良", 8).first()
        assertEquals(here, nara.parentId)
        assertEquals(here, fx { window.editor().page()!!.id })
        assertEquals(listOf(Mark(0, 2, Mark.Style.PAGE, nara.id)), fx { current().block().marks })
    }

    @Test
    fun openingPageLinkMovesToAliveTargetsOnly() {
        val kyoto = pages.create(null, "京都")
        fx {
            window.openPage(kyoto.id, null)
            null
        }
        settle()
        assertEquals(kyoto.id, fx { window.editor().page()!!.id })

        val other = pages.create(null, "別")
        fx {
            window.open(other)
            null
        }
        settle()
        pages.trash(kyoto.id, 1L)
        fx {
            window.openPage(kyoto.id, null)
            window.openPage("no-such-page", null)
            null
        }
        settle()
        assertEquals(other.id, fx { window.editor().page()!!.id })
    }

    @Test
    fun backlinksListPagesThatLinkHereAndOpenTheRow() {
        val kyoto = pages.create(null, "京都")
        val trip = pages.create(null, "旅行")
        val b = Block.empty(trip.id, BlockType.PARAGRAPH, 1024.0)
            .withContent("次は京都へ", listOf(Mark(2, 4, Mark.Style.PAGE, kyoto.id)))
        blocks.save(listOf(b), listOf())
        reopen(kyoto.id)
        assertEquals(listOf("📝 旅行"), fx { window.editor().backlinks().titles() })
        assertTrue(fx { window.editor().backlinks().isVisible() })

        fx {
            window.editor().backlinks().open(0)
            null
        }
        settle()
        assertEquals(trip.id, fx { window.editor().page()!!.id })
        assertEquals(b.id, fx { current().block().id })
        assertFalse(fx { window.editor().backlinks().isVisible() })
    }

    @Test
    fun deleteOnDividerRemovesItAndKeepsNextBlock() {
        focusFirstBlock()
        type("---")
        type("after")
        fx {
            window.editor().views().first().focusAt(0)
            null
        }
        press(KeyCode.DELETE)
        assertEquals(listOf("paragraph:0:after"), summary())
    }

    @Test
    fun enterWithSelectionReplacesIt() {
        focusFirstBlock()
        type("abcdef")
        fx {
            current().area().selectRange(2, 4)
            null
        }
        press(KeyCode.ENTER)
        assertEquals(listOf("paragraph:0:ab", "paragraph:0:ef"), summary())
    }

    @Test
    fun failedFlushKeepsCurrentPageAndUnsavedEdits() {
        focusFirstBlock()
        type("AB")
        assertTrue(saver.flushAndWait(2L, TimeUnit.SECONDS))
        val pageId = fx { window.editor().page()!!.id }
        val other = pages.create(null, "別のページ")

        failSaves = true
        type("C")
        fx {
            window.open(other)
            null
        }
        settle()
        // 保存できないあいだはページを切り替えず、書いた文字も残す
        assertEquals(pageId, fx { window.editor().page()!!.id })
        assertEquals(listOf("paragraph:0:ABC"), summary())

        failSaves = false
        assertTrue(saver.flushAndWait(2L, TimeUnit.SECONDS))
        assertEquals(listOf("ABC"), blocks.listByPage(pageId).map { it.text })
    }

    @Test
    fun failedFlushDoesNotCreateOrRestorePages() {
        focusFirstBlock()
        type("AB")
        assertTrue(saver.flushAndWait(2L, TimeUnit.SECONDS))
        val before = pages.listAlive().size

        failSaves = true
        type("C")
        fx {
            window.newPage(null)
            window.openToday()
            null
        }
        settle()
        // 切り替えられないときは、新しいページも今日の日記も作らない
        assertEquals(before, pages.listAlive().size)
        assertEquals(listOf("paragraph:0:ABC"), summary())
    }

    @Test
    fun failedBlockReadKeepsCurrentPage() {
        focusFirstBlock()
        type("AB")
        assertTrue(saver.flushAndWait(2L, TimeUnit.SECONDS))
        val pageId = fx { window.editor().page()!!.id }
        val other = pages.create(null, "別のページ")
        db.write { c ->
            c.createStatement().use { st ->
                st.executeUpdate("ALTER TABLE blocks RENAME TO blocks_gone")
            }
        }

        fx {
            window.open(other)
            null
        }
        settle()
        assertEquals(pageId, fx { window.editor().page()!!.id })
        assertEquals(listOf("paragraph:0:AB"), summary())
    }

    @Test
    fun failedTitleSaveBlocksLeavingAndKeepsUnsavedTitle() {
        val pageId = fx { window.editor().page()!!.id }
        val other = pages.create(null, "別のページ")
        val before = pages.listAlive().size

        // タイトル変更の UPDATE だけ失敗させる
        db.write { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TRIGGER fail_page_rename BEFORE UPDATE OF title ON pages " +
                        "BEGIN SELECT RAISE(ABORT, 'pages locked'); END",
                )
            }
        }
        fx {
            val field = stage.getScene().lookup(".page-title") as TextField
            field.text = "未保存タイトル"
            assertFalse(window.editor().flushBeforeLeaving())
            window.open(other)
            window.newPage(null)
            window.openToday()
            null
        }
        settle()

        // タイトル保存が失敗したときはページを切り替えず、新規作成もしない
        assertEquals(pageId, fx { window.editor().page()!!.id })
        assertEquals(before, pages.listAlive().size)
        assertEquals("未保存タイトル", fx { (stage.getScene().lookup(".page-title") as TextField).text })

        db.write { c ->
            c.createStatement().use { st ->
                st.executeUpdate("DROP TRIGGER fail_page_rename")
            }
        }
        fx {
            window.open(other)
            null
        }
        settle()
        assertEquals(other.id, fx { window.editor().page()!!.id })
        assertEquals("未保存タイトル", pages.get(pageId)?.title)
    }

    @Test
    fun flushAllReturnsFalseOnTitleSaveFailureAndStillFlushesBlocks() {
        focusFirstBlock()
        val pageId = fx { window.editor().page()!!.id }
        type("ブロック本文")

        db.write { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TRIGGER fail_page_rename BEFORE UPDATE OF title ON pages " +
                        "BEGIN SELECT RAISE(ABORT, 'pages locked'); END",
                )
            }
        }
        var confirmCalled = false
        val closed = fx {
            val field = stage.getScene().lookup(".page-title") as TextField
            field.text = "失敗タイトル"
            window.flushAll {
                confirmCalled = true
                false
            }
        }
        assertFalse(closed)
        assertTrue(confirmCalled)
        // タイトル保存に失敗しても、ブロック側の flushAndWait はショートサーキットされずに実行される
        assertEquals(listOf("ブロック本文"), blocks.listByPage(pageId).map { it.text })

        db.write { c ->
            c.createStatement().use { st ->
                st.executeUpdate("DROP TRIGGER fail_page_rename")
            }
        }
        confirmCalled = false
        val savedBoth = fx {
            window.flushAll {
                confirmCalled = true
                false
            }
        }
        assertTrue(savedBoth)
        assertFalse(confirmCalled)
        assertEquals("失敗タイトル", pages.get(pageId)?.title)
    }

    @Test
    fun linkClickWithOutOfBoundsMarkClampsSafelyWithoutThrowing() {
        focusFirstBlock()
        fx {
            val v = window.editor().views().first()
            // 本文長を超える範囲のリンクマーク（破損データ等）
            v.setBlock(v.block().withContent("https://example.com", listOf(Mark(0, 100, Mark.Style.LINK))))
            assertTrue(v.openLinkAt(0))
            // 本文の完全に外にある範囲
            v.setBlock(v.block().withContent("abc", listOf(Mark(50, 60, Mark.Style.LINK))))
            assertFalse(v.openLinkAt(55))
            // 空白のみのリンク範囲
            v.setBlock(v.block().withContent("   ", listOf(Mark(0, 10, Mark.Style.LINK))))
            assertFalse(v.openLinkAt(0))
            null
        }
        assertEquals(listOf("https://example.com"), openedLinks)
    }

    @Test
    fun clickingStaleSearchResultDoesNotOpenIt() {
        pages.create(null, "東京メモ")
        val opened = mutableListOf<String>()
        val dialog = fx { SearchDialog(stage, services) { h -> opened.add(h.pageId) } }
        fx {
            dialog.field().setText("東京")
            dialog.show()
            null
        }
        settle()
        fx {
            // 待ち時間（120ms）が過ぎる前に、古い結果の行を押す
            dialog.field().setText("大阪")
            dialog.results().fireEvent(MouseEvent(
                MouseEvent.MOUSE_CLICKED, 0.0, 0.0, 0.0, 0.0,
                MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, false, null))
            null
        }
        settle()
        assertEquals(listOf<String>(), opened)
    }

    @Test
    fun pastingLinesEndingWithDividerKeepsTailVisible() {
        focusFirstBlock()
        type("AB")
        fx {
            current().area().moveTo(1)
            val content = ClipboardContent()
            content.putString("x\n---")
            Clipboard.getSystemClipboard().setContent(content)
            null
        }
        press(KeyCode.V, KeyMod.SHORTCUT)
        assertEquals(listOf("paragraph:0:Ax", "divider:0:", "paragraph:0:B"), summary())
    }

    @Test
    fun slashInCodeBlockIsPlainText() {
        focusFirstBlock()
        type("```")
        assertEquals(listOf("code:0:"), summary())
        type("/")
        press(KeyCode.ENTER)
        assertEquals(listOf("code:0:/\n"), summary())
    }

    @Test
    fun shiftDownSelectsTextInsteadOfMovingToNextBlock() {
        focusFirstBlock()
        type("abcd")
        press(KeyCode.ENTER)
        type("efgh")
        fx {
            window.editor().views().first().focusAt(2)
            null
        }
        settle()
        press(KeyCode.DOWN, KeyMod.SHIFT)
        val first = fx { window.editor().views().first() }
        assertSame(first, fx { current() })
        assertEquals("cd", fx { first.area().getSelectedText() })

        press(KeyCode.UP, KeyMod.SHIFT)
        assertSame(first, fx { current() })
        assertEquals("ab", fx { first.area().getSelectedText() })
    }

    @Test
    fun enterRightAfterChangingQueryOpensNewResult() {
        val tokyo = pages.create(null, "東京メモ")
        val osaka = pages.create(null, "大阪メモ")
        val opened = mutableListOf<String>()
        val dialog = fx { SearchDialog(stage, services) { h -> opened.add(h.pageId) } }
        fx {
            dialog.field().setText("東京")
            dialog.show()
            null
        }
        settle()
        fx {
            // 待ち時間（120ms）が過ぎる前に Enter を押す
            dialog.field().setText("大阪")
            dialog.field().fireEvent(KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER,
                false, false, false, false))
            null
        }
        settle()
        assertEquals(listOf(osaka.id), opened)
        assertNotEquals(tokyo.id, opened.first())
    }

    @Test
    fun foldingHeadingHidesItsSectionAndIsKeptAfterReopening() {
        focusFirstBlock()
        type("## 1日目")
        press(KeyCode.ENTER)
        type("伏見稲荷")
        press(KeyCode.ENTER)
        type("お昼")
        press(KeyCode.ENTER)
        type("## 2日目")
        press(KeyCode.ENTER)
        type("嵐山")
        focusEndOf(0)

        press(KeyCode.ENTER, KeyMod.SHORTCUT)
        assertEquals(listOf("h2:0:1日目", "h2:0:2日目", "paragraph:0:嵐山"), visibleSummary())

        // 畳んだ見出しで Enter: 隠れた行の後に同じ段の見出しが入り、節は閉じたまま
        press(KeyCode.ENTER)
        assertEquals(listOf("h2:0:1日目", "h2:0:", "h2:0:2日目", "paragraph:0:嵐山"), visibleSummary())
        assertEquals(3, fx { window.editor().views().indexOf(current()) })
        // すぐ Backspace で消しても、節は開かない
        press(KeyCode.BACK_SPACE)
        assertEquals(listOf("h2:0:1日目", "h2:0:2日目", "paragraph:0:嵐山"), visibleSummary())
        assertEquals("1日目", fx { current().block().text })

        assertTrue(saver.flushAndWait(2L, TimeUnit.SECONDS))
        val pageId = fx { window.editor().page()!!.id }
        assertTrue(blocks.listByPage(pageId).first().collapsed)
        reopen(pageId)
        assertEquals(listOf("h2:0:1日目", "h2:0:2日目", "paragraph:0:嵐山"), visibleSummary())

        focusEndOf(0)
        press(KeyCode.ENTER, KeyMod.SHORTCUT)
        assertEquals(5, visibleSummary().size)
    }

    @Test
    fun arrowsSkipHiddenRowsAndEnterAddsAfterThem() {
        typeBookList()
        focusEndOf(0)
        press(KeyCode.ENTER, KeyMod.SHORTCUT)
        assertEquals(listOf("bullet:0:読む本", "bullet:0:電池"), visibleSummary())

        press(KeyCode.DOWN)
        assertEquals("電池", fx { current().block().text })
        press(KeyCode.UP)
        assertEquals("読む本", fx { current().block().text })

        press(KeyCode.ENTER)
        assertEquals(listOf("bullet:0:読む本", "bullet:1:古都", "bullet:1:檸檬", "bullet:0:", "bullet:0:電池"),
            summary())
        assertEquals(3, fx { window.editor().views().indexOf(current()) })
        assertTrue(fx { window.editor().views().first().block().collapsed })
    }

    @Test
    fun movingCollapsedRowMovesHiddenRowsWithIt() {
        typeBookList()
        focusEndOf(0)
        press(KeyCode.ENTER, KeyMod.SHORTCUT)
        press(KeyCode.DOWN, KeyMod.SHORTCUT, KeyMod.SHIFT)
        assertEquals(listOf("bullet:0:電池", "bullet:0:読む本", "bullet:1:古都", "bullet:1:檸檬"), summary())
        assertEquals("読む本", fx { current().block().text })

        assertTrue(saver.flushAndWait(2L, TimeUnit.SECONDS))
        val pageId = fx { window.editor().page()!!.id }
        assertEquals(listOf("電池", "読む本", "古都", "檸檬"),
            blocks.listByPage(pageId).map { it.text })

        // 上の行は、畳んだまとまりごと飛び越える
        focusEndOf(0)
        press(KeyCode.DOWN, KeyMod.SHORTCUT, KeyMod.SHIFT)
        assertEquals(listOf("bullet:0:読む本", "bullet:1:古都", "bullet:1:檸檬", "bullet:0:電池"), summary())
    }

    @Test
    fun outlineListsHeadingsAndJumpOpensCollapsedSection() {
        focusFirstBlock()
        type("# 京都旅行")
        press(KeyCode.ENTER)
        type("## 1日目")
        press(KeyCode.ENTER)
        type("### 午前")
        press(KeyCode.ENTER)
        type("伏見稲荷")
        focusEndOf(1)
        press(KeyCode.ENTER, KeyMod.SHORTCUT)
        assertEquals(listOf("h1:0:京都旅行", "h2:0:1日目"), visibleSummary())

        fx {
            window.toggleOutline()
            null
        }
        assertEquals(listOf("京都旅行", "1日目", "午前"),
            fx { window.outline().items().map { it.getText() } })

        fx {
            val item = window.outline().items().get(2)
            item.fireEvent(MouseEvent(
                MouseEvent.MOUSE_CLICKED, 0.0, 0.0, 0.0, 0.0,
                MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, false, null))
            null
        }
        settle()
        assertEquals(4, visibleSummary().size)
        assertEquals("午前", fx { current().block().text })
        assertEquals(listOf("午前"), fx {
            window.outline().items()
                .filter { l -> l.getStyleClass().contains("current") }
                .map { it.getText() }
        })
    }

    @Test
    fun collapsedRowWithoutChildrenLosesItsMark() {
        val p = pages.create(null, "子のない行")
        blocks.save(listOf(Block.empty(p.id, BlockType.BULLET, 1024.0).withContent("ひとり", listOf())
            .withCollapsed(true)), listOf())
        reopen(p.id)
        assertFalse(fx { window.editor().views().first().block().collapsed })
        assertFalse(fx { window.editor().views().first().foldable() })
        assertTrue(saver.flushAndWait(2L, TimeUnit.SECONDS))
        assertFalse(blocks.listByPage(p.id).first().collapsed)
    }

    @Test
    fun movingCollapsedRowDoesNotHideRowsThatWereVisible() {
        openRows(
            row(BlockType.BULLET, 0, "S", false), row(BlockType.BULLET, 1, "S1", false),
            row(BlockType.BULLET, 0, "R", true), row(BlockType.BULLET, 1, "R1", false))
        assertEquals(listOf("bullet:0:S", "bullet:1:S1", "bullet:0:R"), visibleSummary())
        focusEndOf(2)
        press(KeyCode.UP, KeyMod.SHORTCUT, KeyMod.SHIFT)
        // S1 は R の子の位置に入るが、隠さずに R を開く
        assertEquals(listOf("bullet:0:S", "bullet:0:R", "bullet:1:R1", "bullet:1:S1"), visibleSummary())
    }

    @Test
    fun removingEmptyHeadingAfterCollapsedSectionKeepsFollowingRowsVisible() {
        openRows(
            row(BlockType.H2, 0, "A", true), row(BlockType.PARAGRAPH, 0, "a1", false),
            row(BlockType.H2, 0, "", false), row(BlockType.PARAGRAPH, 0, "b1", false))
        fx {
            window.editor().views().get(2).focusAt(0)
            null
        }
        settle()
        press(KeyCode.BACK_SPACE)
        assertTrue(visibleSummary().contains("paragraph:0:b1"))
    }

    @Test
    fun outdentingCollapsedRowDoesNotHideFollowingSibling() {
        openRows(
            row(BlockType.BULLET, 0, "Q", false), row(BlockType.BULLET, 1, "R", true),
            row(BlockType.BULLET, 2, "R1", false), row(BlockType.BULLET, 1, "T", false))
        focusEndOf(1)
        press(KeyCode.TAB, KeyMod.SHIFT)
        assertTrue(visibleSummary().contains("bullet:1:T"))
    }

    @Test
    fun clickingBelowCollapsedLastSectionKeepsItClosed() {
        openRows(
            row(BlockType.H2, 0, "A", false), row(BlockType.PARAGRAPH, 0, "a1", false),
            row(BlockType.H2, 0, "B", true), row(BlockType.PARAGRAPH, 0, "b1", false))
        fx {
            window.editor().clickBelowLastBlock()
            null
        }
        settle()
        // 段落だと B の節に入って開いてしまうので、同じ段の見出しを足す
        assertEquals(listOf("h2:0:A", "paragraph:0:a1", "h2:0:B", "h2:0:"), visibleSummary())
        assertTrue(fx { window.editor().views().get(2).block().collapsed })
        assertEquals(4, fx { window.editor().views().indexOf(current()) })
    }

    @Test
    fun clickingBelowCollapsedLastItemAddsParagraphAfterHiddenRows() {
        openRows(row(BlockType.BULLET, 0, "R", true), row(BlockType.BULLET, 1, "R1", false))
        fx {
            window.editor().clickBelowLastBlock()
            null
        }
        settle()
        assertEquals(listOf("bullet:0:R", "paragraph:0:"), visibleSummary())
        assertTrue(fx { window.editor().views().first().block().collapsed })
    }

    @Test
    fun movingCollapsedHeadingTakesIndentedHeadingUnderItsItem() {
        val pageId = openRows(
            row(BlockType.H2, 0, "A", true), row(BlockType.BULLET, 0, "B", true),
            row(BlockType.H2, 1, "C", false), row(BlockType.PARAGRAPH, 1, "D", false),
            row(BlockType.H1, 0, "E", false))
        assertEquals(listOf("h2:0:A", "h1:0:E"), visibleSummary())
        focusEndOf(0)
        press(KeyCode.DOWN, KeyMod.SHORTCUT, KeyMod.SHIFT)
        assertEquals(listOf("h1:0:E", "h2:0:A", "bullet:0:B", "h2:1:C", "paragraph:1:D"), summary())
        assertTrue(saver.flushAndWait(2L, TimeUnit.SECONDS))
        assertEquals(listOf("E", "A", "B", "C", "D"),
            blocks.listByPage(pageId).map { it.text })
    }

    @Test
    fun pastingIntoHeadingKeepsOutlineInSync() {
        openRows(row(BlockType.H2, 0, "AB", false))
        fx {
            window.toggleOutline()
            val v = window.editor().views().first()
            v.focusAt(1)
            window.editor().pasteLines(v, "x\n")
            null
        }
        settle()
        assertEquals("AxB", fx { window.editor().views().first().block().text })
        assertEquals(listOf("AxB"),
            fx { window.outline().items().map { it.getText() } })
    }

    @Test
    fun imeCompositionKeepsTheFirstCharacterAndUnderlinesIt() {
        focusFirstBlock()
        fx {
            val area = current().area()
            fireIme(area, "あ", "")
            assertEquals("あ", area.getText())
            assertTrue(area.getStyleOfChar(0).contains(ImeArea.COMPOSED))
            fireIme(area, "あい", "")
            assertEquals("あい", area.getText())
            fireIme(area, "", "愛")
            assertEquals("愛", area.getText())
            assertFalse(area.getStyleOfChar(0).contains(ImeArea.COMPOSED))
            null
        }
    }

    @Test
    fun imeEnterDoesNotSplitOrInsertANewline() {
        focusFirstBlock()
        fx {
            val area = current().area()
            fireIme(area, "かんじ", "")
            fireEnter(area)
            assertEquals("かんじ", area.getText())
            assertEquals(1, window.editor().views().size)
            null
        }
        // 確定に使った Enter も、その場では行を分けない
        fx {
            val area = current().area()
            fireIme(area, "", "漢字")
            fireEnter(area)
            assertEquals("漢字", area.getText())
            assertEquals(1, window.editor().views().size)
            null
        }
        settle()
        press(KeyCode.ENTER)
        assertEquals(listOf("paragraph:0:漢字", "paragraph:0:"), summary())
    }

    @Test
    fun imeCandidateStaysByTheLineAfterEnterAndABreak() {
        focusFirstBlock()
        assertNearLine(fx { current().area() })
        type("ab")
        press(KeyCode.ENTER, KeyMod.SHIFT)
        fx {
            val area = current().area()
            assertEquals("ab\n", area.getText())
            area.moveTo(2) // 改行の位置
            assertNearLine(area)
            null
        }
        press(KeyCode.ENTER)
        assertNearLine(fx { current().area() })
    }

    @Test
    fun imeCommitStillDetectsAFullwidthHeadingMark() {
        focusFirstBlock()
        fx {
            fireIme(current().area(), "＃　", "")
            assertEquals(BlockType.PARAGRAPH, current().block().type)
            fireIme(current().area(), "", "＃　")
            null
        }
        settle()
        assertEquals(BlockType.H1, fx { current().block().type })
        assertEquals("", fx { current().block().text })
    }

    @Test
    fun titleConfirmEnterStaysInTheTitle() {
        val title = fx {
            val field = stage.getScene().lookup(".page-title") as TextField
            field.requestFocus()
            fireIme(field, "あい", "")
            fireEnter(field)
            assertSame(field, stage.getScene().getFocusOwner())
            fireIme(field, "", "愛")
            fireEnter(field)
            assertSame(field, stage.getScene().getFocusOwner())
            assertEquals("愛", field.getText())
            field
        }
        settle()
        press(KeyCode.ENTER)
        assertEquals("愛", fx { title.getText() })
        fx {
            current()
            null
        }
    }

    @Test
    fun imePartialCommitClearsUnderlineOnCommittedTextAndKeepsItOnComposedText() {
        focusFirstBlock()
        fx {
            val area = current().area()
            fireIme(area, "きょうとへいく", "")
            for (i in 0 until area.length) {
                assertTrue(area.getStyleOfChar(i).contains(ImeArea.COMPOSED))
            }
            // 部分確定: 「京都」を確定しつつ「へいく」は未確定のまま残す
            fireIme(area, "へいく", "京都")
            assertEquals("京都へいく", area.text)
            assertFalse(area.getStyleOfChar(0).contains(ImeArea.COMPOSED))
            assertFalse(area.getStyleOfChar(1).contains(ImeArea.COMPOSED))
            assertTrue(area.getStyleOfChar(2).contains(ImeArea.COMPOSED))
            assertTrue(area.getStyleOfChar(3).contains(ImeArea.COMPOSED))
            assertTrue(area.getStyleOfChar(4).contains(ImeArea.COMPOSED))
            // 残りを確定
            fireIme(area, "", "へ行く")
            assertEquals("京都へ行く", area.text)
            for (i in 0 until area.length) {
                assertFalse(area.getStyleOfChar(i).contains(ImeArea.COMPOSED))
            }
            null
        }
    }

    @Test
    fun imePartialCommitAtEndOfPageLinkUnlinksCommittedText() {
        val p = pages.create(null, "部分確定リンク")
        blocks.save(
            listOf(
                Block.empty(p.id, BlockType.PARAGRAPH, 1024.0)
                    .withContent("京都", listOf(Mark(0, 2, Mark.Style.PAGE, "x"))),
            ),
            listOf(),
        )
        reopen(p.id)
        focusEndOf(0)
        fx {
            val area = current().area()
            fireIme(area, "へいく", "")
            // 「へ」だけ先に部分確定し、「いく」は未確定のまま
            fireIme(area, "いく", "へ")
            null
        }
        settle()
        // 部分確定した「へ」は未確定文字が残っていてもリンクから外れ、下線も消えている
        assertFalse(fx { current().area().getStyleOfChar(2).contains(ImeArea.COMPOSED) })
        assertNull(fx { StyledText.targetOf(current().area().getStyleOfChar(2)) })
        assertTrue(fx { current().area().getStyleOfChar(3).contains(ImeArea.COMPOSED) })

        fx {
            fireIme(current().area(), "", "行く")
            null
        }
        settle()
        assertEquals("京都へ行く", fx { current().block().text })
        assertEquals(listOf(Mark(0, 2, Mark.Style.PAGE, "x")), fx { current().block().marks })
    }

    @Test
    fun imePartialCommitInsidePageLinkKeepsLink() {
        val p = pages.create(null, "部分確定リンク内")
        blocks.save(
            listOf(
                Block.empty(p.id, BlockType.PARAGRAPH, 1024.0)
                    .withContent("京都", listOf(Mark(0, 2, Mark.Style.PAGE, "x"))),
            ),
            listOf(),
        )
        reopen(p.id)
        fx {
            window.editor().views().first().focusAt(1)
            null
        }
        settle()
        fx {
            val area = current().area()
            fireIme(area, "のまち", "")
            fireIme(area, "まち", "の")
            null
        }
        settle()
        fx {
            fireIme(current().area(), "", "町")
            null
        }
        settle()
        assertEquals("京の町都", fx { current().block().text })
        assertEquals(listOf(Mark(0, 4, Mark.Style.PAGE, "x")), fx { current().block().marks })
    }

    @Test
    fun searchDialogSuppressesKeysDuringImeCompositionAndRightAfterConfirmOrCancel() {
        pages.create(null, "東京メモ")
        pages.create(null, "京都メモ")
        val opened = mutableListOf<String>()
        val dialog = fx { SearchDialog(stage, services) { h -> opened.add(h.pageId) } }
        fx {
            dialog.field().text = "メモ"
            dialog.show()
            null
        }
        settle()
        assertEquals(2, fx { dialog.results().items.size })
        assertEquals(0, fx { dialog.results().selectionModel.selectedIndex })
        assertTrue(fx { dialog.isShowing() })

        // 変換中は ↓・↑・Enter・Esc のいずれも検索操作や閉じる動作を起こさない
        fx {
            val field = dialog.field()
            fireIme(field, "とうきょう", "")
            fireKey(field, KeyCode.DOWN)
            assertEquals(0, dialog.results().selectionModel.selectedIndex)
            fireKey(field, KeyCode.UP)
            assertEquals(0, dialog.results().selectionModel.selectedIndex)
            fireKey(field, KeyCode.ENTER)
            assertTrue(opened.isEmpty())
            assertTrue(dialog.isShowing())
            fireKey(field, KeyCode.ESCAPE)
            assertTrue(dialog.isShowing())

            // 確定直後の Enter でも結果を開かない
            fireIme(field, "", "メモ")
            fireKey(field, KeyCode.ENTER)
            assertTrue(opened.isEmpty())
            assertTrue(dialog.isShowing())
            null
        }
        settle()

        // 変換取消（空確定）直後の Esc でも閉じない
        fx {
            val field = dialog.field()
            fireIme(field, "あ", "")
            fireIme(field, "", "")
            fireKey(field, KeyCode.ESCAPE)
            assertTrue(dialog.isShowing())
            null
        }
        settle()

        // 次のパルスでは ↓ と Enter が通常どおり動く
        fx {
            val field = dialog.field()
            fireKey(field, KeyCode.DOWN)
            assertEquals(1, dialog.results().selectionModel.selectedIndex)
            fireKey(field, KeyCode.ENTER)
            null
        }
        settle()
        assertEquals(1, opened.size)
        assertFalse(fx { dialog.isShowing() })
    }

    @Test
    fun switchingPageAndRemovingBlockDisposeViewsAndClearPageLinkMenu() {
        focusFirstBlock()
        type("[[奈良")
        press(KeyCode.ESCAPE)
        val oldView = fx { window.editor().views().first() }
        assertFalse(fx { oldView.isDisposed() })
        assertTrue(fx { window.editor().isLinkDismissed(oldView, 0) })

        val other = pages.create(null, "切替先")
        fx {
            window.open(other)
            null
        }
        settle()
        assertTrue(fx { oldView.isDisposed() })
        assertFalse(fx { window.editor().isLinkDismissed(oldView, 0) })

        // ブロック削除でも BlockView が破棄される
        focusFirstBlock()
        type("first")
        press(KeyCode.ENTER)
        type("second")
        val secondView = fx { window.editor().views()[1] }
        assertFalse(fx { secondView.isDisposed() })
        fx {
            secondView.focusAt(0)
            null
        }
        settle()
        press(KeyCode.BACK_SPACE)
        assertTrue(fx { secondView.isDisposed() })
        assertEquals(listOf("paragraph:0:firstsecond"), summary())
    }

    @Test
    fun delayedShortcutAndStaleCallbacksOnDisposedViewDoNotCorruptPageOrResurrectBlock() {
        val other = pages.create(null, "無傷のページ")
        blocks.save(
            listOf(Block.empty(other.id, BlockType.PARAGRAPH, 1024.0).withContent("元の本文", listOf())),
            listOf(),
        )
        focusFirstBlock()
        // 「---」で runLater の detectShortcut() を積み、実行前に別ページへ切り替える
        val oldView = fx {
            val v = window.editor().views().first()
            v.area().replaceText("---")
            window.open(other)
            v
        }
        settle()
        assertTrue(fx { oldView.isDisposed() })
        assertEquals(other.id, fx { window.editor().page()!!.id })
        assertEquals(listOf("paragraph:0:元の本文"), summary())

        // 削除済みブロックのビューから Host コールバックが遅延到達しても、削除済みブロックを復活させない
        focusFirstBlock()
        fx {
            window.editor().views().first().focusEnd()
            null
        }
        settle()
        press(KeyCode.ENTER)
        type("消す行")
        val deletedView = fx { window.editor().views()[1] }
        fx {
            deletedView.setContent("", listOf())
            deletedView.focusAt(0)
            null
        }
        settle()
        press(KeyCode.BACK_SPACE)
        assertTrue(fx { deletedView.isDisposed() })
        assertTrue(saver.flushAndWait(2L, TimeUnit.SECONDS))

        fx {
            window.editor().edited(deletedView)
            window.editor().convert(deletedView, BlockType.DIVIDER, 0)
            window.editor().convert(deletedView, BlockType.H1, 0)
            window.editor().enter(deletedView)
            null
        }
        settle()
        assertTrue(saver.flushAndWait(2L, TimeUnit.SECONDS))
        assertEquals(listOf("paragraph:0:元の本文"), summary())
        assertEquals(listOf("元の本文"), blocks.listByPage(other.id).map { it.text })
    }

    @Test
    fun imeStateResetsOnDocumentReplaceAndFocusLoss() {
        focusFirstBlock()
        fx {
            val v = current()
            val area = v.area() as ImeArea
            fireIme(area, "あ", "")
            assertTrue(area.isComposing())
            assertTrue(area.claimsKey(KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false)))
            // 本文入替で ImeGuard の変換状態が解除される
            v.setContent("入替", emptyList())
            assertFalse(area.isComposing())
            assertFalse(area.claimsKey(KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false)))
            null
        }
        settle()
        focusEndOf(0)
        type("X")
        assertEquals("入替X", fx { current().block().text })

        // 変換中にフォーカス喪失しても入力不能にならず下線も外れる
        fx {
            val area = current().area() as ImeArea
            fireIme(area, "い", "")
            assertTrue(area.isComposing())
            val title = stage.scene.lookup(".page-title") as TextField
            title.requestFocus()
            assertFalse(area.isComposing())
            assertFalse(area.claimsKey(KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false)))
            for (i in 0 until area.length) {
                assertFalse(area.getStyleOfChar(i).contains(ImeArea.COMPOSED))
            }
            null
        }
        settle()
        focusEndOf(0)
        type("Y")
        assertTrue(fx { current().block().text }.endsWith("Y"))
    }

    @Test
    fun imeCompositionSuppressesAutoSaveAndMenusUntilCommit() {
        pages.create(null, "京都")
        focusFirstBlock()
        assertTrue(saver.flushAndWait(2L, TimeUnit.SECONDS))
        assertFalse(saver.hasPending())

        // 変換中の未確定文字では自動保存もスラッシュメニューも発火しない
        fx {
            val area = current().area()
            fireIme(area, "/見", "")
            assertFalse(saver.hasPending())
            assertFalse(window.editor().slashMenu().isShowing())
            assertEquals("", current().block().text)
            null
        }
        settle()
        assertFalse(saver.hasPending())
        assertFalse(fx { window.editor().slashMenu().isShowing() })

        // 確定後にブロック本文・自動保存・スラッシュメニューへ反映される
        fx {
            fireIme(current().area(), "", "/見")
            null
        }
        settle()
        assertEquals("/見", fx { current().block().text })
        assertTrue(fx { window.editor().slashMenu().isShowing() })
        assertTrue(saver.flushAndWait(2L, TimeUnit.SECONDS))
        val pageId = fx { window.editor().page()!!.id }
        assertEquals(listOf("/見"), blocks.listByPage(pageId).map { it.text })

        // 変換キャンセル時は自動保存が走らない
        fx {
            current().setContent("", emptyList())
            window.editor().slashMenu().hide()
            null
        }
        assertTrue(saver.flushAndWait(2L, TimeUnit.SECONDS))
        fx {
            val area = current().area()
            fireIme(area, "[[京", "")
            assertFalse(saver.hasPending())
            assertFalse(window.editor().pageLinkMenu().isShowing())
            null
        }
        settle()
        assertFalse(saver.hasPending())
        assertFalse(fx { window.editor().pageLinkMenu().isShowing() })

        // 変換確定後にページリンク候補が出る
        fx {
            fireIme(current().area(), "", "[[京")
            null
        }
        settle()
        assertEquals("[[京", fx { current().block().text })
        assertTrue(fx { window.editor().pageLinkMenu().isShowing() })
        assertTrue(saver.flushAndWait(2L, TimeUnit.SECONDS))
        assertEquals(listOf("[[京"), blocks.listByPage(pageId).map { it.text })

        // 変換取消（空確定）では不要な保存を起こさない
        fx {
            val area = current().area()
            fireIme(area, "あ", "")
            fireIme(area, "", "")
            null
        }
        settle()
        assertFalse(saver.hasPending())
    }

    @Test
    fun sidebarDragDropUpdatesBreadcrumbAndEditorPage() {
        val parent = pages.create(null, "親")
        val child = pages.create(null, "子")
        fx {
            window.sidebar().reload(child.id)
            window.open(child)
            null
        }
        settle()
        assertNull(fx { window.editor().page()!!.parentId })
        assertEquals("📝 子", fx { (stage.scene.lookup(".breadcrumb") as Label).text })

        val movedInto = fx { window.sidebar().moveOnto(child, parent, Sidebar.Drop.INTO) }
        assertTrue(movedInto)
        assertEquals(parent.id, fx { window.editor().page()!!.parentId })
        assertEquals("📝 親  /  📝 子", fx { (stage.scene.lookup(".breadcrumb") as Label).text })

        val freshChild = pages.get(child.id)!!
        val movedRoot = fx { window.sidebar().moveToRoot(freshChild) }
        assertTrue(movedRoot)
        assertNull(fx { window.editor().page()!!.parentId })
        assertEquals("📝 子", fx { (stage.scene.lookup(".breadcrumb") as Label).text })
    }

    @Test
    fun sidebarRespaceFailureAndSectionDropExhaustionDoNotFallbackToGap() {
        val a = pages.create(null, "A")
        val b = pages.create(null, "B")
        val c = pages.create(null, "C")
        // A と B の sort_key を同一にして SortKeys.between が null になる状況を作る
        db.write { conn ->
            conn.prepareStatement("UPDATE pages SET sort_key = 500.0 WHERE id IN (?, ?)").use { ps ->
                ps.setString(1, a.id)
                ps.setString(2, b.id)
                ps.executeUpdate()
            }
            conn.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TRIGGER fail_respace BEFORE UPDATE OF sort_key ON pages " +
                        "BEGIN SELECT RAISE(ABORT, 'respace failed'); END",
                )
            }
        }
        fx {
            window.sidebar().reload(c.id)
            null
        }
        val originalCKey = pages.get(c.id)!!.sortKey
        val moved = fx { window.sidebar().moveOnto(pages.get(c.id)!!, pages.get(b.id)!!, Sidebar.Drop.BEFORE) }
        assertFalse(moved)
        assertEquals(originalCKey, pages.get(c.id)!!.sortKey)

        // セクションドロップで末尾キーの精度が尽きているときは respaceChildren(null) で振り直して末尾へ置く
        db.write { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("DROP TRIGGER fail_respace")
            }
            conn.prepareStatement("UPDATE pages SET sort_key = 1e20 WHERE id = ?").use { ps ->
                ps.setString(1, a.id)
                ps.executeUpdate()
            }
        }
        val nested = pages.create(a.id, "子")
        fx {
            window.sidebar().reload(nested.id)
            null
        }
        val movedToRoot = fx { window.sidebar().moveToRoot(pages.get(nested.id)!!) }
        assertTrue(movedToRoot)
        val afterA = pages.get(a.id)!!
        val afterNested = pages.get(nested.id)!!
        assertNull(afterNested.parentId)
        assertTrue(afterNested.sortKey > afterA.sortKey, "末尾へ配置されること: ${afterNested.sortKey} > ${afterA.sortKey}")
    }

    @Test
    fun trashRestoreAndDeleteRefreshBacklinksAndAbortOnSaveFailure() {
        val target = pages.create(null, "標的")
        val linker = pages.create(null, "参照元")
        val other = pages.create(null, "別ページ")
        blocks.save(
            listOf(
                Block.empty(linker.id, BlockType.PARAGRAPH, 1024.0)
                    .withContent("標的への参照", listOf(Mark(0, 2, Mark.Style.PAGE, target.id))),
            ),
            emptyList(),
        )
        reopen(target.id)
        assertEquals(listOf("📝 参照元"), fx { window.editor().backlinks().titles() })
        assertTrue(fx { window.editor().backlinks().isVisible })

        // ブロック保存失敗時はゴミ箱移動を中断する
        focusFirstBlock()
        failSaves = true
        type("未保存本文")
        fx {
            window.trash(other)
            null
        }
        settle()
        assertFalse(pages.get(other.id)!!.isTrashed())
        failSaves = false
        assertTrue(saver.flushAndWait(2L, TimeUnit.SECONDS))

        // タイトル保存失敗時もゴミ箱移動を中断する
        db.write { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TRIGGER fail_trash_title BEFORE UPDATE OF title ON pages " +
                        "BEGIN SELECT RAISE(ABORT, 'title locked'); END",
                )
            }
        }
        fx {
            Errors.dismiss()
            (stage.scene.lookup(".page-title") as TextField).text = "未保存名"
            window.trash(other)
            null
        }
        settle()
        assertFalse(pages.get(other.id)!!.isTrashed())
        db.write { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("DROP TRIGGER fail_trash_title")
            }
        }
        fx {
            Errors.dismiss()
            (stage.scene.lookup(".page-title") as TextField).text = "標的"
            window.editor().saveTitle()
            null
        }

        // 参照元ページをゴミ箱へ移すと開いているページの被リンクが消える
        fx {
            window.trash(linker)
            null
        }
        settle()
        assertEquals(emptyList<String>(), fx { window.editor().backlinks().titles() })
        assertFalse(fx { window.editor().backlinks().isVisible })

        // ゴミ箱から復元すると被リンクが復活する
        pages.restore(linker.id)
        fx {
            window.onTrashChanged()
            null
        }
        settle()
        assertEquals(listOf("📝 参照元"), fx { window.editor().backlinks().titles() })
        assertTrue(fx { window.editor().backlinks().isVisible })

        // 完全削除でも被リンクが更新される
        pages.deleteForever(linker.id)
        fx {
            window.onTrashChanged()
            null
        }
        settle()
        assertEquals(emptyList<String>(), fx { window.editor().backlinks().titles() })
        assertFalse(fx { window.editor().backlinks().isVisible })
    }

    @Test
    fun codeBlockEnterWithSelectionIncludingTrailingNewlineReplacesSelection() {
        focusFirstBlock()
        type("```")
        fx {
            val area = current().area()
            area.replaceText("abc\n")
            area.selectRange(0, 4)
            null
        }
        settle()
        press(KeyCode.ENTER)
        // 末尾改行を含む選択あり Enter は脱出せず選択範囲を改行に置き換える
        assertEquals(listOf("code:0:\n"), summary())

        // 選択なしで末尾改行の後ろで Enter すると通常どおりコードブロックから抜ける
        fx {
            val area = current().area()
            area.replaceText("abc\n")
            area.moveTo(4)
            null
        }
        settle()
        press(KeyCode.ENTER)
        assertEquals(listOf("code:0:abc", "paragraph:0:"), summary())
    }

    @Test
    fun shiftEnterOnDividerDoesNotInsertNewlineAndBackspaceStillDeletes() {
        focusFirstBlock()
        type("---")
        type("after")
        fx {
            window.editor().views().first().focusAt(0)
            null
        }
        settle()
        press(KeyCode.ENTER, KeyMod.SHIFT)
        assertEquals("", fx { window.editor().views().first().text() })
        press(KeyCode.BACK_SPACE)
        assertEquals(listOf("paragraph:0:after"), summary())
    }

    @Test
    fun menuAndSearchListClicksConfirmOnlyOnPrimaryClickOfNonEmptyCell() {
        val tokyo = pages.create(null, "東京メモ")
        val opened = mutableListOf<String>()
        val dialog = fx { SearchDialog(stage, services) { h -> opened.add(h.pageId) } }
        fx {
            dialog.field().text = "東京"
            dialog.show()
            dialog.results().applyCss()
            dialog.results().layout()
            null
        }
        settle()
        // ListView 全体（空白やスクロールバー相当）や右クリックでは開かない
        fx {
            dialog.results().fireEvent(clickEvent(MouseButton.PRIMARY))
            val nonEmptyCell = firstNonEmptyCell(dialog.results())
            assertNotNull(nonEmptyCell)
            nonEmptyCell!!.fireEvent(clickEvent(MouseButton.SECONDARY))
            val emptyCell = firstEmptyCell(dialog.results())
            emptyCell?.fireEvent(clickEvent(MouseButton.PRIMARY))
            null
        }
        settle()
        assertTrue(opened.isEmpty())
        assertTrue(fx { dialog.isShowing() })

        // 語句変更直後のセル左クリックは古い結果を開かず再検索する
        fx {
            dialog.field().text = "存在しない語句"
            val nonEmptyCell = firstNonEmptyCell(dialog.results())!!
            nonEmptyCell.fireEvent(clickEvent(MouseButton.PRIMARY))
            null
        }
        settle()
        assertTrue(opened.isEmpty())
        assertTrue(fx { dialog.results().items.isEmpty() })

        // 閉鎖時にデバウンスタイマーが停止される
        fx {
            dialog.field().text = "東京"
            assertTrue(dialog.isDebounceRunning())
            dialog.hide()
            assertFalse(dialog.isDebounceRunning())
            null
        }

        // 正しい語句で表示した非空セルの左クリックで確定する
        fx {
            dialog.show()
            dialog.results().applyCss()
            dialog.results().layout()
            val nonEmptyCell = firstNonEmptyCell(dialog.results())!!
            nonEmptyCell.fireEvent(clickEvent(MouseButton.PRIMARY))
            null
        }
        settle()
        assertEquals(listOf(tokyo.id), opened)

        // showSearch() 前にタイトル保存が実行され、直前のタイトル変更が検索に出る
        fx {
            val title = stage.scene.lookup(".page-title") as TextField
            title.text = "検索対象タイトル"
            window.showSearch()
            val d = window.searchDialog()!!
            d.field().text = "検索対象タイトル"
            d.show()
            assertTrue(d.results().items.any { it.pageTitle == "検索対象タイトル" })
            d.hide()
            null
        }

        // SlashMenu: ListView 全体や右クリックでは確定せず、非空セル左クリックで確定
        focusFirstBlock()
        type("/見")
        assertTrue(fx { window.editor().slashMenu().isShowing() })
        fx {
            val list = window.editor().slashMenu().list()
            list.applyCss()
            list.layout()
            list.fireEvent(clickEvent(MouseButton.PRIMARY))
            firstNonEmptyCell(list)!!.fireEvent(clickEvent(MouseButton.SECONDARY))
            null
        }
        settle()
        assertTrue(fx { window.editor().slashMenu().isShowing() })
        fx {
            val list = window.editor().slashMenu().list()
            firstNonEmptyCell(list)!!.fireEvent(clickEvent(MouseButton.PRIMARY))
            null
        }
        settle()
        assertFalse(fx { window.editor().slashMenu().isShowing() })
        assertEquals(BlockType.H1, fx { current().block().type })

        // PageLinkMenu: ListView 全体や右クリックでは確定せず、非空セル左クリックで確定
        fx {
            current().setBlock(current().block().withType(BlockType.PARAGRAPH).withContent("", emptyList()))
            current().focusAt(0)
            null
        }
        settle()
        type("[[東京")
        assertTrue(fx { window.editor().pageLinkMenu().isShowing() })
        fx {
            val list = window.editor().pageLinkMenu().list()
            list.applyCss()
            list.layout()
            list.fireEvent(clickEvent(MouseButton.PRIMARY))
            firstNonEmptyCell(list)!!.fireEvent(clickEvent(MouseButton.SECONDARY))
            null
        }
        settle()
        assertTrue(fx { window.editor().pageLinkMenu().isShowing() })
        fx {
            val list = window.editor().pageLinkMenu().list()
            firstNonEmptyCell(list)!!.fireEvent(clickEvent(MouseButton.PRIMARY))
            null
        }
        settle()
        assertFalse(fx { window.editor().pageLinkMenu().isShowing() })
        assertEquals("東京メモ", fx { current().block().text })
    }

    @Test
    fun pastingMultipleLinesIntoCollapsedBlockPlacesThemAfterHiddenChildren() {
        openRows(
            row(BlockType.BULLET, 0, "親", true),
            row(BlockType.BULLET, 1, "子1", false),
            row(BlockType.BULLET, 1, "子2", false),
            row(BlockType.BULLET, 0, "次", false),
        )
        focusEndOf(0)
        fx {
            window.editor().pasteLines(current(), "A\nB\nC")
            null
        }
        settle()
        assertEquals(
            listOf(
                "bullet:0:親A",
                "bullet:1:子1",
                "bullet:1:子2",
                "paragraph:0:B",
                "paragraph:0:C",
                "bullet:0:次",
            ),
            summary(),
        )
        assertTrue(fx { window.editor().views().first().block().collapsed })
        assertEquals(listOf("bullet:0:親A", "paragraph:0:B", "paragraph:0:C", "bullet:0:次"), visibleSummary())

        // 畳んだ見出しへの複数行ペーストも隠し子行の後に入る
        openRows(
            row(BlockType.H2, 0, "1日目", true),
            row(BlockType.PARAGRAPH, 0, "伏見稲荷", false),
            row(BlockType.H2, 0, "2日目", false),
        )
        focusEndOf(0)
        fx {
            window.editor().pasteLines(current(), "A\n## 途中")
            null
        }
        settle()
        assertEquals(
            listOf("h2:0:1日目A", "paragraph:0:伏見稲荷", "h2:0:途中", "h2:0:2日目"),
            summary(),
        )
        assertTrue(fx { window.editor().views().first().block().collapsed })
        assertEquals(listOf("h2:0:1日目A", "h2:0:途中", "h2:0:2日目"), visibleSummary())
    }

    @Test
    fun errorsAlertSetsOwnerSuppressesDuplicatesAndSkipsWhenNoWindowShowing() {
        fx {
            Errors.dismiss()
            Errors.warn("警告1", "内容1")
            val first = Errors.activeAlert()
            assertNotNull(first)
            assertSame(stage, first!!.owner)
            assertEquals("警告1", first.headerText)

            // 同時重複表示を抑止する
            Errors.show("失敗2", SQLException("boom"))
            assertSame(first, Errors.activeAlert())
            assertEquals("警告1", Errors.activeAlert()!!.headerText)

            // 閉じたあとは次のアラートを表示できる
            first.close()
            assertNull(Errors.activeAlert())
            Errors.show("失敗2", SQLException("boom"))
            val second = Errors.activeAlert()
            assertNotNull(second)
            assertSame(stage, second!!.owner)
            assertEquals("失敗2できませんでした", second.headerText)

            // オーナーウィンドウを閉じるとアラートも閉じ、表示中ウィンドウなしでは新規表示しない
            stage.close()
            assertNull(Errors.activeAlert())
            Errors.warn("終了後", "表示されない")
            assertNull(Errors.activeAlert())
            null
        }
    }

    // ---- helpers ----

    private fun clickEvent(button: MouseButton): MouseEvent = MouseEvent(
        MouseEvent.MOUSE_CLICKED, 0.0, 0.0, 0.0, 0.0,
        button, 1,
        false, false, false, false,
        button == MouseButton.PRIMARY, false, button == MouseButton.SECONDARY,
        false, false, false, null,
    )

    private fun firstNonEmptyCell(list: javafx.scene.control.ListView<*>): ListCell<*>? =
        list.lookupAll(".list-cell").filterIsInstance<ListCell<*>>().firstOrNull { !it.isEmpty && it.item != null }

    private fun firstEmptyCell(list: javafx.scene.control.ListView<*>): ListCell<*>? =
        list.lookupAll(".list-cell").filterIsInstance<ListCell<*>>().firstOrNull { it.isEmpty }

    private enum class KeyMod { SHIFT, SHORTCUT }

    private fun fireIme(target: Node, composed: String, committed: String) {
        val runs: List<InputMethodTextRun> = if (composed.isEmpty()) {
            listOf()
        } else {
            listOf(InputMethodTextRun(composed, InputMethodHighlight.UNSELECTED_RAW))
        }
        target.fireEvent(InputMethodEvent(
            InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, runs, committed, composed.length))
    }

    private fun fireEnter(target: Node) {
        fireKey(target, KeyCode.ENTER)
    }

    private fun fireKey(target: Node, code: KeyCode) {
        target.fireEvent(KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
            false, false, false, false))
    }

    /** 候補窓の位置が、行の画面上の範囲から離れていない。 */
    private fun assertNearLine(area: StyleClassedTextArea) {
        val p = area.getInputMethodRequests().getTextLocation(0)
        val b = area.localToScreen(area.getBoundsInLocal())
        assertNotNull(b, "行が画面にない")
        val box = b!!
        assertTrue(p.getX() >= box.getMinX() - 20 && p.getX() <= box.getMaxX() + 20) { "x $p vs $box" }
        assertTrue(p.getY() >= box.getMinY() - 20 && p.getY() <= box.getMaxY() + 30) { "y $p vs $box" }
    }

    private fun focusFirstBlock() {
        fx {
            window.editor().views().first().focusAt(0)
            null
        }
        settle()
    }

    private fun focusEndOf(index: Int) {
        fx {
            window.editor().views().get(index).focusEnd()
            null
        }
        settle()
    }

    /** 読む本 / 古都(字下げ) / 檸檬(字下げ) / 電池 の箇条書き。 */
    private fun typeBookList() {
        focusFirstBlock()
        type("- 読む本")
        press(KeyCode.ENTER)
        type("古都")
        press(KeyCode.TAB)
        press(KeyCode.ENTER)
        type("檸檬")
        press(KeyCode.ENTER)
        press(KeyCode.ENTER)          // 空の項目で Enter → 字下げを戻す
        type("電池")
        assertEquals(listOf("bullet:0:読む本", "bullet:1:古都", "bullet:1:檸檬", "bullet:0:電池"), summary())
    }

    private fun reopen(pageId: String) {
        fx {
            window.openToday()
            null
        }
        settle()
        fx {
            window.open(pages.get(pageId) ?: error("missing"))
            null
        }
        settle()
    }

    private data class Row(val type: BlockType, val indent: Int, val text: String, val collapsed: Boolean)

    private fun row(type: BlockType, indent: Int, text: String, collapsed: Boolean): Row {
        return Row(type, indent, text, collapsed)
    }

    /** 行を DB に入れたページを作って開く。 */
    private fun openRows(vararg rows: Row): String {
        val p = pages.create(null, "行")
        val list = mutableListOf<Block>()
        for (i in rows.indices) {
            val r = rows[i]
            list.add(Block.empty(p.id, r.type, (i + 1) * 1024.0).withIndent(r.indent)
                .withContent(r.text, listOf()).withCollapsed(r.collapsed))
        }
        blocks.save(list, listOf())
        reopen(p.id)
        return p.id
    }

    private fun visibleSummary(): List<String> {
        return fx {
            window.editor().views()
                .filter { v -> !v.hidden() }
                .map { v -> v.block().type.dbName() + ":" + v.block().indent + ":" + v.block().text }
        }
    }

    /** 画面に出ている、空の行の案内。出ていなければ null。 */
    private fun shownPlaceholder(v: BlockView): Label? =
        v.lookupAll(".block-placeholder").filterIsInstance<Label>().firstOrNull { label ->
            var n: Node? = label
            var shown = true
            while (n != null && n !== v) {
                if (!n.isVisible) shown = false
                n = n.parent
            }
            shown && n === v
        }

    private fun current(): BlockView {
        val n = stage.getScene().getFocusOwner()
        for (v in window.editor().views()) {
            if (v.area() === n) return v
        }
        throw AssertionError("ブロックにフォーカスがない: $n")
    }

    private fun type(text: String) {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val ch = String(Character.toChars(cp))
            i += ch.length
            fx {
                val target = stage.getScene().getFocusOwner()
                target.fireEvent(KeyEvent(KeyEvent.KEY_TYPED, ch, "", KeyCode.UNDEFINED,
                    false, false, false, false))
                null
            }
            settle()
        }
    }

    private fun press(code: KeyCode, vararg mods: KeyMod) {
        val shift = mods.contains(KeyMod.SHIFT)
        val shortcut = mods.contains(KeyMod.SHORTCUT)
        val mac = System.getProperty("os.name").lowercase().startsWith("mac")
        fx {
            val target = stage.getScene().getFocusOwner()
            target.fireEvent(KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                shift, shortcut && !mac, false, shortcut && mac))
            null
        }
        settle()
    }

    private fun summary(): List<String> {
        return fx {
            window.editor().views()
                .map { v -> v.block().type.dbName() + ":" + v.block().indent + ":" + v.block().text }
        }
    }

    /** FX スレッドに積まれた後続の処理（runLater）が終わるまで待つ。 */
    private fun settle() {
        repeat(3) { fx { } }
    }

    private fun <T> fx(work: () -> T): T {
        val f = CompletableFuture<T>()
        Platform.runLater {
            try {
                f.complete(work())
            } catch (t: Throwable) {
                f.completeExceptionally(t)
            }
        }
        return f.get(10L, TimeUnit.SECONDS)
    }
}
