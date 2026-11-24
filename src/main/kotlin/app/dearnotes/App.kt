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
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
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
            ) { url -> openExternalLink(url) { hostServices.showDocument(it) } }
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
        shutdown(
            flushTitle = { window?.flushTitle() },
            closeSaver = { saver?.close() },
            closeDatabase = { db?.close() },
        )
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(App::class.java.name)

        /** 終了時の後処理を順に実行する。途中で失敗しても後続のクローズ処理を止めず、ログ記録に留める。 */
        internal fun shutdown(
            flushTitle: () -> Unit,
            closeSaver: () -> Unit,
            closeDatabase: () -> Unit,
        ) {
            try {
                try {
                    flushTitle()
                } catch (e: Exception) {
                    LOG.log(Level.WARNING, "終了時のタイトル保存に失敗した", e)
                }
            } finally {
                try {
                    try {
                        closeSaver()
                    } catch (e: Exception) {
                        LOG.log(Level.WARNING, "AutoSaver を閉じられなかった", e)
                    }
                } finally {
                    try {
                        closeDatabase()
                    } catch (e: Exception) {
                        LOG.log(Level.WARNING, "DB を閉じられなかった", e)
                    }
                }
            }
        }

        /** 許可された外部リンク（http / https / mailto）だけを OS へ渡す。 */
        internal fun openExternalLink(raw: String, open: (String) -> Unit) {
            val safe = validateExternalUrl(raw)
            if (safe == null) {
                LOG.warning("許可されていない形式のリンクを開かなかった")
                return
            }
            open(safe)
        }

        /** 外部リンクとして安全に開ける URL なら正規化済み文字列を返し、それ以外は null を返す。 */
        internal fun validateExternalUrl(raw: String): String? {
            val trimmed = raw.strip()
            if (trimmed.isEmpty()) return null
            if (trimmed.any { it.isISOControl() }) return null
            val uri = try {
                URI(trimmed)
            } catch (_: URISyntaxException) {
                return null
            }
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            return when (scheme) {
                "http", "https" -> {
                    // 相対・opaque 形式やホスト欠落（http:///etc/passwd 等）は拒否する
                    if (uri.isOpaque) return null
                    val authority = uri.rawAuthority
                    if (authority.isNullOrEmpty()) return null
                    val hostPort = authority.substringAfterLast('@')
                    val host = if (hostPort.startsWith("[")) {
                        val close = hostPort.indexOf(']')
                        if (close <= 1) return null
                        hostPort.substring(1, close)
                    } else {
                        hostPort.substringBefore(':')
                    }
                    if (host.isEmpty()) return null
                    trimmed
                }
                "mailto" -> {
                    // mailto: は opaque 形式かつ宛先等の本文がある場合のみ許可する
                    if (!uri.isOpaque) return null
                    val body = uri.schemeSpecificPart
                    if (body.isNullOrBlank()) return null
                    trimmed
                }
                else -> null
            }
        }
    }
}
