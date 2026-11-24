package app.dearnotes.ui

import java.lang.ref.WeakReference
import java.sql.SQLException
import java.util.logging.Level
import java.util.logging.Logger
import javafx.application.Platform
import javafx.beans.value.ChangeListener
import javafx.scene.control.Alert
import javafx.stage.Modality
import javafx.stage.Window

/** データベースの操作を UI から呼び、失敗したらダイアログで知らせる。 */
internal object Errors {

    private val LOG: Logger = Logger.getLogger(Errors::class.java.name)
    private var defaultOwner: WeakReference<Window>? = null
    private var activeAlert: Alert? = null

    /** ダイアログの既定オーナーウィンドウを登録する。 */
    internal fun setOwner(owner: Window?) {
        defaultOwner = owner?.let { WeakReference(it) }
    }

    internal fun activeAlert(): Alert? = activeAlert?.takeIf { it.isShowing }

    /** 表示中のエラーダイアログがあれば閉じる（テストの終了処理用）。 */
    internal fun dismiss() {
        val current = activeAlert
        activeAlert = null
        if (current != null && current.isShowing) current.close()
    }

    internal fun run(what: String, action: () -> Unit): Boolean {
        try {
            action()
            return true
        } catch (e: SQLException) {
            show(what, e)
            return false
        }
    }

    /** ダイアログを出さずログ記録のみ行う（終了時など）。 */
    internal fun runQuietly(what: String, action: () -> Unit): Boolean {
        try {
            action()
            return true
        } catch (e: SQLException) {
            LOG.log(Level.WARNING, what, e)
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

    /** ダイアログを出さずログ記録のみ行う（終了時など）。 */
    internal fun <T> getQuietly(what: String, supplier: () -> T?): T? {
        try {
            return supplier()
        } catch (e: SQLException) {
            LOG.log(Level.WARNING, what, e)
            return null
        }
    }

    internal fun warn(header: String, message: String) {
        LOG.warning(header)
        showAlert(Alert.AlertType.WARNING, header, message)
    }

    internal fun show(what: String, e: Exception) {
        LOG.log(Level.WARNING, what, e)
        showAlert(Alert.AlertType.ERROR, what + "できませんでした", e.message)
    }

    private fun showAlert(type: Alert.AlertType, header: String, message: String?) {
        // FX スレッド外や表示中のウィンドウがない状態（終了処理中など）ではモーダルを出さない
        if (!Platform.isFxApplicationThread()) return
        val current = activeAlert
        if (current != null && current.isShowing) return
        val owner = findOwner() ?: return
        val alert = Alert(type)
        alert.initOwner(owner)
        alert.initModality(Modality.WINDOW_MODAL)
        alert.title = "DearNotes"
        alert.headerText = header
        alert.contentText = message
        // オーナーウィンドウが閉じたらアラートも即座に閉じて残留を防ぐ
        val ownerListener = ChangeListener<Boolean> { _, _, showing ->
            if (showing != true && alert.isShowing) alert.close()
        }
        owner.showingProperty().addListener(ownerListener)
        alert.setOnHidden {
            owner.showingProperty().removeListener(ownerListener)
            if (activeAlert === alert) activeAlert = null
        }
        activeAlert = alert
        alert.show()
    }

    private fun findOwner(): Window? {
        val currentWindow = activeAlert?.dialogPane?.scene?.window
        val showing = Window.getWindows().filter { it.isShowing && it !== currentWindow }
        showing.firstOrNull { it.isFocused }?.let { return it }
        defaultOwner?.get()?.takeIf { it.isShowing }?.let { return it }
        return showing.firstOrNull()
    }
}
