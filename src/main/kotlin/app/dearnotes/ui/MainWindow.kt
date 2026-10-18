package app.dearnotes.ui

import app.dearnotes.db.SearchRepo.Hit
import app.dearnotes.export.ExportTree
import app.dearnotes.export.MarkdownWriter
import app.dearnotes.model.Page
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.SplitPane
import javafx.scene.image.Image
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.stage.DirectoryChooser
import javafx.stage.FileChooser
import javafx.stage.Stage
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/** 左にサイドバー、右に開いているページ。 */
class MainWindow(private val stage: Stage, private val s: Services) : Sidebar.Actions, PageEditor.Navigator {

    private val prefs = Preferences.userNodeForPackage(MainWindow::class.java)
    private val sidebar = Sidebar(s, this)
    private val editor = PageEditor(s, this)
    private val outline = OutlinePanel(editor::jumpTo)
    private val breadcrumb = Label()
    private val outlineButton = Button("☰ 目次")
    private val saveIndicator = SaveIndicator()
    private var searchDialog: SearchDialog? = null
    private var trashDialog: TrashDialog? = null

    init {
        // 目次は初めは閉じておく
        outline.isVisible = false
        outline.isManaged = false
        editor.setOutlineListener { refreshOutline() }

        breadcrumb.styleClass.add("breadcrumb")
        outlineButton.styleClass.add("outline-button")
        outlineButton.isFocusTraversable = false
        outlineButton.setOnAction { toggleOutline() }
        val spacer = Region()
        HBox.setHgrow(spacer, Priority.ALWAYS)
        val header = HBox(8.0, breadcrumb, spacer, saveIndicator, outlineButton)
        header.styleClass.add("page-header")
        header.alignment = Pos.CENTER_LEFT

        // 本文は読みやすい幅で中央に置く
        val column = StackPane(editor)
        column.styleClass.add("page-column")
        StackPane.setAlignment(editor, Pos.TOP_CENTER)
        editor.maxWidth = 760.0
        val scroll = ScrollPane(column)
        scroll.isFitToWidth = true
        scroll.styleClass.add("page-scroll")
        editor.setScrollPane(scroll)

        val main = BorderPane(scroll)
        main.top = header
        main.right = outline
        val split = SplitPane(sidebar, main)
        split.setDividerPositions(0.24)
        SplitPane.setResizableWithParent(sidebar, false)
        sidebar.minWidth = 180.0

        val scene = Scene(split, 1100.0, 720.0)
        scene.stylesheets.add(MainWindow::class.java.getResource("dearnotes.css")!!.toExternalForm())
        scene.accelerators[KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN)] = Runnable { showSearch() }
        scene.accelerators[KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN)] = Runnable { newPage(null) }
        scene.accelerators[
            KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
        ] = Runnable { openToday() }
        scene.accelerators[
            KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
        ] = Runnable { toggleOutline() }
        scene.accelerators[
            KeyCodeCombination(KeyCode.E, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
        ] = Runnable {
            val current = editor.page()
            if (current != null) exportPage(current)
        }
        stage.scene = scene
        stage.title = "DearNotes"
        stage.icons.setAll(icons())
        stage.minWidth = 640.0
        stage.minHeight = 400.0

        s.saver.setListener { saveIndicator.show(it) }
        stage.setOnCloseRequest { e ->
            if (!flushAll()) e.consume()
        }
    }

    /**
     * 閉じる前に、名前と書きかけのブロックを書く。
     * 書けなかったら尋ね、閉じるのをやめるなら false。
     */
    fun flushTitle() {
        editor.saveTitle()
    }

    fun flushAll(): Boolean {
        editor.saveTitle()
        if (s.saver.flushAndWait(5, TimeUnit.SECONDS)) return true
        val alert = Alert(
            Alert.AlertType.CONFIRMATION,
            "保存できていない変更があります。いま終了すると、その変更は失われます。",
            ButtonType.CANCEL,
            ButtonType.OK,
        )
        alert.initOwner(stage)
        alert.headerText = "保存できませんでした"
        (alert.dialogPane.lookupButton(ButtonType.OK) as Button).text = "終了する"
        return alert.showAndWait().orElse(null) == ButtonType.OK
    }

    internal fun editor(): PageEditor = editor

    internal fun sidebar(): Sidebar = sidebar

    internal fun outline(): OutlinePanel = outline

    /** 本文の右の目次を出し入れする。 */
    fun toggleOutline() {
        val show = !outline.isVisible
        outline.isVisible = show
        outline.isManaged = show
        outlineButton.styleClass.remove("active")
        if (show) outlineButton.styleClass.add("active")
        refreshOutline()
    }

    private fun refreshOutline() {
        if (outline.isVisible) outline.show(editor.headings(), editor.currentHeadingId())
    }

    /** 起動時: 前回のページ、なければ今日の日記を開く。 */
    fun openInitialPage() {
        sidebar.reload(null)
        val last = prefs.get(LAST_PAGE, null)
        var page = if (last == null) {
            null
        } else {
            Errors.get("ページを読む") { s.pages.get(last) }?.takeIf { !it.isTrashed() }
        }
        if (page == null) {
            val alive = Errors.get("ページの一覧を読む") { s.pages.listAlive() } ?: emptyList()
            page = alive.firstOrNull { !it.isDaily() }
        }
        if (page == null) openToday() else open(page)
    }

    override fun open(p: Page) {
        open(p, null)
    }

    /** 開けなかったら（書きかけを保存できないとき）、サイドバーの選択を今のページに戻して false。 */
    private fun open(p: Page, blockId: String?): Boolean {
        if (!editor.open(p, blockId)) {
            val current = editor.page()
            sidebar.select(current?.id)
            return false
        }
        prefs.put(LAST_PAGE, p.id)
        sidebar.select(p.id)
        updateHeader(p)
        return true
    }

    override fun openToday() {
        // 移れないときに日記を作ったりゴミ箱から戻したりしないよう、先に保存する
        if (!editor.flushBeforeLeaving()) return
        val p = Errors.get("今日の日記を開く") { s.pages.openDaily(Clock.System.todayIn(TimeZone.currentSystemDefault())) } ?: return
        sidebar.reload(p.id)
        open(p)
    }

    override fun newPage(parentId: String?) {
        if (!editor.flushBeforeLeaving()) return
        val p = Errors.get("ページを作る") { s.pages.create(parentId, "") } ?: return
        sidebar.reload(p.id)
        if (open(p, null)) editor.focusTitle()
    }

    override fun trash(p: Page) {
        s.saver.flushAndWait(2, TimeUnit.SECONDS)
        if (!Errors.run("ゴミ箱へ移動") { s.pages.trash(p.id, System.currentTimeMillis()) }) return
        val current = editor.page()
        sidebar.reload(current?.id)
        val currentGone = current != null && (
            Errors.get("ページを読む") { s.pages.get(current.id)?.isTrashed() ?: true } ?: true
            )
        if (currentGone) openInitialPage()
    }

    override fun exportPage(p: Page) {
        val page = beforeExport(p) ?: return
        val chooser = FileChooser()
        chooser.title = "Markdown で書き出す"
        chooser.initialFileName = ExportTree.fileName(page) + ".md"
        chooser.extensionFilters.add(FileChooser.ExtensionFilter("Markdown", "*.md"))
        lastExportDir()?.let { chooser.initialDirectory = it }
        // 同じ名前のファイルがあれば、上書きするかは OS の窓が尋ねる
        val file = chooser.showSaveDialog(stage) ?: return
        prefs.put(EXPORT_DIR, file.parent)
        val blocks = Errors.get("ページを読む") { s.blocks.listByPage(page.id) } ?: return
        try {
            ExportTree.writeReplacing(file.toPath(), MarkdownWriter.write(page, blocks))
        } catch (e: IOException) {
            Errors.show("書き出し", e)
        }
    }

    override fun exportTree(p: Page) {
        val page = beforeExport(p) ?: return
        val chooser = DirectoryChooser()
        chooser.title = "子ページごと書き出す先のフォルダ"
        lastExportDir()?.let { chooser.initialDirectory = it }
        val dir = chooser.showDialog(stage) ?: return
        prefs.put(EXPORT_DIR, dir.path)
        val alive = Errors.get("ページの一覧を読む") { s.pages.listAlive() } ?: return
        try {
            val written = ExportTree.write(dir.toPath(), page, alive) { s.blocks.listByPage(it) }
            val done = Alert(Alert.AlertType.INFORMATION)
            done.initOwner(stage)
            done.title = "DearNotes"
            done.headerText = written.size.toString() + " ページを書き出しました"
            done.contentText = written.first().parent.toString()
            done.show()
        } catch (e: ExportTree.ExportException) {
            // 書けたファイルは残すので、どこに何ページ書けたかも知らせる
            val message = if (e.written().isEmpty()) {
                e.message
            } else {
                e.written().size.toString() + " ページは " + e.written().first().parent + " に書けています。\n" + e.message
            }
            Errors.show("書き出し", IOException(message, e))
        }
    }

    /** 書きかけを保存し、名前を DB から読み直す。保存できなければ書き出さずに null。 */
    private fun beforeExport(p: Page): Page? {
        // 名前を変えられなかったら、古い名前で書き出さない（エラーの窓は editor が出す）
        if (!editor.saveTitle()) return null
        if (!s.saver.flushAndWait(3, TimeUnit.SECONDS)) {
            Errors.warn(
                "書き出せませんでした",
                "まだ保存できていない変更があります。少し待ってからもう一度試してください。",
            )
            return null
        }
        return Errors.get("ページを読む") { s.pages.get(p.id) }
    }

    /** 前に書き出したフォルダ。消えていれば空。 */
    private fun lastExportDir(): File? {
        val path = prefs.get(EXPORT_DIR, null) ?: return null
        val file = File(path)
        return if (file.isDirectory) file else null
    }

    override fun showTrash() {
        val dialog = trashDialog ?: TrashDialog(stage, s) {
            val current = editor.page()
            sidebar.reload(current?.id)
            val gone = current == null || (
                Errors.get("ページを読む") { s.pages.get(current.id)?.isTrashed() ?: true } ?: true
                )
            if (gone) openInitialPage()
        }.also { trashDialog = it }
        dialog.show()
    }

    override fun showSearch() {
        val dialog = searchDialog ?: SearchDialog(stage, s, ::openHit).also { searchDialog = it }
        dialog.show()
    }

    private fun openHit(h: Hit) {
        val p = Errors.get("ページを開く") { s.pages.get(h.pageId) } ?: return
        open(p, h.blockId)
    }

    override fun pageChanged(p: Page) {
        sidebar.reload(p.id)
        editor.pageUpdated(p)
        updateHeader(p)
    }

    override fun openPage(pageId: String, blockId: String?) {
        val found = try {
            s.pages.get(pageId)
        } catch (e: java.sql.SQLException) {
            Errors.show("ページを開く", e)
            return
        }
        if (found == null) {
            Errors.warn("リンク先のページがありません", "ページは完全に削除されました。")
        } else if (found.isTrashed()) {
            Errors.warn(
                "「" + found.displayTitle() + "」はゴミ箱にあります",
                "ゴミ箱から元に戻すと、このリンクから開けます。",
            )
        } else {
            open(found, blockId)
        }
    }

    override fun newLinkedPage(parentId: String?, title: String): Page? {
        val created = Errors.get("ページを作る") { s.pages.create(parentId, title) }
        val current = editor.page()
        if (created != null) sidebar.reload(current?.id)
        return created
    }

    private fun updateHeader(p: Page) {
        breadcrumb.text = sidebar.pathTo(p).joinToString("  /  ") { it.displayIcon() + " " + it.displayTitle() }
        stage.title = p.displayTitle() + " — DearNotes"
    }

    companion object {
        // 別のデータフォルダで試すときは、前回のページも別に覚える
        private val LAST_PAGE: String = if (System.getProperty("dearnotes.dataDir") == null) {
            "lastPageId"
        } else {
            "lastPageId@" + Integer.toHexString(System.getProperty("dearnotes.dataDir").hashCode())
        }
        private const val EXPORT_DIR = "exportDir"
        private val ICON_SIZES = listOf(16, 32, 48, 64, 128, 256)

        /** ウィンドウのアイコン（Windows のタイトルバーとタスクバー）。packaging/icon/build-icons.py が作る。 */
        internal fun icons(): List<Image> = ICON_SIZES.mapNotNull { px ->
            MainWindow::class.java.getResource("/app/dearnotes/icon-$px.png")
        }.map { url -> Image(url.toExternalForm()) }
    }
}
