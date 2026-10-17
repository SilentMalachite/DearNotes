package app.dearnotes.ui

import java.sql.SQLException
import java.util.logging.Level
import java.util.logging.Logger
import javafx.scene.control.Alert

/** データベースの操作を UI から呼び、失敗したらダイアログで知らせる。 */
internal object Errors {

    private val LOG: Logger = Logger.getLogger(Errors::class.java.name)

    internal fun run(what: String, action: () -> Unit): Boolean {
        try {
            action()
            return true
        } catch (e: SQLException) {
            show(what, e)
            return false
        }
    }

    internal fun <T> get(what: String, supplier: () -> T?): T? {
        try {
            return supplier()
        } catch (e: SQLException) {
            show(what, e)
            return null
        }
    }

    internal fun warn(header: String, message: String) {
        LOG.warning(header)
        val alert = Alert(Alert.AlertType.WARNING)
        alert.title = "DearNotes"
        alert.headerText = header
        alert.contentText = message
        alert.show()
    }

    internal fun show(what: String, e: Exception) {
        LOG.log(Level.WARNING, what, e)
        val alert = Alert(Alert.AlertType.ERROR)
        alert.title = "DearNotes"
        alert.headerText = what + "できませんでした"
        alert.contentText = e.message
        alert.show()
    }
}
