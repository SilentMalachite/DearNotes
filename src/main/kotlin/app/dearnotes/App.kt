package app.dearnotes

import app.dearnotes.db.BlockRepo
import app.dearnotes.db.DataDir
import app.dearnotes.db.Database
import app.dearnotes.db.PageRepo
import app.dearnotes.db.SearchRepo
import app.dearnotes.save.AutoSaver
import app.dearnotes.ui.MainWindow
import app.dearnotes.ui.Services
import app.dearnotes.ui.Welcome
import java.util.logging.Level
import java.util.logging.Logger
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.control.Alert
import javafx.stage.Stage

class App : Application() {

    private var db: Database? = null
    private var saver: AutoSaver? = null
    private var window: MainWindow? = null

    override fun start(stage: Stage) {
        try {
            val file = DataDir.databaseFile()
            LOG.info("database: $file")
            val database = Database.open(file)
            db = database
            val pages = PageRepo(database)
            val blocks = BlockRepo(database)
            val cutoff = System.currentTimeMillis() - PageRepo.TRASH_RETENTION.toMillis()
            val purged = pages.purgeTrashedBefore(cutoff)
            if (purged > 0) LOG.info("ゴミ箱から $purged ページを消した")
            Welcome.createIfEmpty(pages, blocks)

            val autoSaver = AutoSaver(blocks) { Platform.runLater(it) }
            saver = autoSaver
            val services = Services(
                database,
                pages,
                blocks,
                SearchRepo(database),
                autoSaver,
            ) { url -> hostServices.showDocument(url) }
            val main = MainWindow(stage, services)
            window = main
            main.openInitialPage()
            stage.show()
        } catch (e: Exception) {
            LOG.log(Level.SEVERE, "起動に失敗", e)
            val alert = Alert(Alert.AlertType.ERROR)
            alert.title = "DearNotes"
            alert.headerText = "DearNotes を起動できませんでした"
            alert.contentText = java.lang.String.valueOf(e.message)
            alert.showAndWait()
            Platform.exit()
        }
    }

    /** ウィンドウを閉じたら、未保存の変更を書いてから DB を閉じる。 */
    override fun stop() {
        // Cmd+Q などウィンドウの close request を通らない終わり方でも、名前を書いておく
        window?.flushTitle()
        saver?.close()
        val database = db
        if (database != null) {
            try {
                database.close()
            } catch (e: Exception) {
                LOG.log(Level.WARNING, "DB を閉じられなかった", e)
            }
        }
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(App::class.java.name)
    }
}
