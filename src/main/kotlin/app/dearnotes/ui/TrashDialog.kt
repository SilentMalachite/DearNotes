package app.dearnotes.ui

import app.dearnotes.db.PageRepo
import app.dearnotes.model.Page
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.Window

/** ゴミ箱。戻すか、完全に消す。30日たつと起動時に消える。 */
internal class TrashDialog(
    owner: Window,
    private val s: Services,
    private val onChanged: () -> Unit,
) {

    private val stage = Stage()
    private val list = ListView<Page>()

    init {
        stage.initOwner(owner)
        stage.initModality(Modality.WINDOW_MODAL)
        stage.title = "ゴミ箱"

        list.placeholder = Label("ゴミ箱は空です")
        list.setCellFactory {
            object : ListCell<Page>() {
                override fun updateItem(p: Page?, empty: Boolean) {
                    super.updateItem(p, empty)
                    if (empty || p == null) {
                        text = null
                        return
                    }
                    val whenDeleted = WHEN.format(Instant.ofEpochMilli(p.deletedAt!!).atZone(ZoneId.systemDefault()))
                    text = p.displayIcon() + "  " + p.displayTitle() + "    （" + whenDeleted + " に削除）"
                }
            }
        }

        val note = Label("ゴミ箱のページは " + PageRepo.TRASH_RETENTION.toDays() + " 日後に自動で消えます。")
        note.styleClass.add("muted")
        val restore = Button("元に戻す")
        restore.setOnAction { restoreSelected() }
        val delete = Button("完全に削除")
        delete.setOnAction { deleteSelected() }
        val close = Button("閉じる")
        close.setOnAction { stage.hide() }
        restore.disableProperty().bind(list.selectionModel.selectedItemProperty().isNull())
        delete.disableProperty().bind(list.selectionModel.selectedItemProperty().isNull())
        val spacer = Region()
        HBox.setHgrow(spacer, Priority.ALWAYS)
        val buttons = HBox(8.0, restore, delete, spacer, close)
        buttons.alignment = Pos.CENTER_LEFT

        val box = VBox(10.0, note, list, buttons)
        box.padding = Insets(14.0)
        VBox.setVgrow(list, Priority.ALWAYS)
        val scene = Scene(box, 520.0, 400.0)
        scene.stylesheets.addAll(owner.scene.stylesheets)
        stage.scene = scene
    }

    fun show() {
        reload()
        stage.show()
    }

    private fun reload() {
        val pages = Errors.get("ゴミ箱を読む") { s.pages.listTrash() } ?: emptyList()
        list.items.setAll(pages)
    }

    private fun restoreSelected() {
        val p = list.selectionModel.selectedItem ?: return
        if (Errors.run("元に戻す") { s.pages.restore(p.id) }) {
            reload()
            onChanged()
        }
    }

    private fun deleteSelected() {
        val p = list.selectionModel.selectedItem ?: return
        val confirm = Alert(
            Alert.AlertType.CONFIRMATION,
            "「" + p.displayTitle() + "」と子ページを完全に削除します。元に戻せません。",
            ButtonType.CANCEL,
            ButtonType.OK,
        )
        confirm.initOwner(stage)
        confirm.headerText = null
        val choice = confirm.showAndWait().orElse(null)
        if (choice == ButtonType.OK && Errors.run("削除") { s.pages.deleteForever(p.id) }) {
            reload()
            onChanged()
        }
    }

    companion object {
        private val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
