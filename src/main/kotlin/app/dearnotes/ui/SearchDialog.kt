package app.dearnotes.ui

import app.dearnotes.isJavaBlank

import app.dearnotes.db.SearchRepo.Hit
import java.util.concurrent.TimeUnit
import javafx.animation.PauseTransition
import javafx.beans.value.ChangeListener
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.layout.VBox
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.StageStyle
import javafx.stage.Window
import javafx.util.Duration

/** Cmd/Ctrl+K の検索。↑↓ で選び Enter で開く。 */
internal class SearchDialog(
    owner: Window,
    private val s: Services,
    private val onOpen: (Hit) -> Unit,
) {

    private val stage = Stage(StageStyle.UNDECORATED)
    private val field = TextField()
    private val results = ListView<Hit>()
    private val empty = Label()
    private val debounce = PauseTransition(Duration.millis(120.0))
    /** いまの results を出したときの検索語。 */
    private var searchedQuery: String? = null

    init {
        stage.initOwner(owner)
        stage.initModality(Modality.WINDOW_MODAL)

        field.promptText = "ページを検索…"
        field.styleClass.add("search-field")
        field.textProperty().addListener(ChangeListener { _, _, _ -> debounce.playFromStart() })
        debounce.setOnFinished { runSearch() }

        results.styleClass.add("search-results")
        results.placeholder = empty
        results.setCellFactory {
            object : ListCell<Hit>() {
                override fun updateItem(h: Hit?, isEmpty: Boolean) {
                    super.updateItem(h, isEmpty)
                    if (isEmpty || h == null) {
                        graphic = null
                        return
                    }
                    val icon = h.pageIcon ?: if (h.daily) "📅" else "📝"
                    val pageTitle = h.pageTitle
                    val title = if (pageTitle.isJavaBlank()) "無題" else pageTitle
                    val t = Label(icon + "  " + title)
                    t.styleClass.add("hit-title")
                    val snip = Label(h.snippet)
                    snip.styleClass.add("hit-snippet")
                    graphic = VBox(2.0, t, snip)
                }
            }
        }
        results.setOnMouseClicked { e ->
            // 語句を変えた直後の古い結果は開かず、探し直すだけにする
            if (field.text != searchedQuery) {
                debounce.stop()
                runSearch()
            } else if (e.clickCount >= 1) {
                openSelected()
            }
        }

        field.setOnKeyPressed { e ->
            val sel = results.selectionModel
            when (e.code) {
                KeyCode.DOWN -> {
                    sel.select(Math.min(results.items.size - 1, sel.selectedIndex + 1))
                    results.scrollTo(sel.selectedIndex)
                    e.consume()
                }
                KeyCode.UP -> {
                    sel.select(Math.max(0, sel.selectedIndex - 1))
                    results.scrollTo(sel.selectedIndex)
                    e.consume()
                }
                KeyCode.ENTER -> {
                    debounce.stop()
                    // 語句を変えてすぐ Enter したときは、古い結果を開かないよう探し直す
                    if (results.items.isEmpty() || field.text != searchedQuery) runSearch()
                    openSelected()
                    e.consume()
                }
                KeyCode.ESCAPE -> {
                    stage.hide()
                    e.consume()
                }
                else -> {}
            }
        }

        val box = VBox(8.0, field, results)
        box.styleClass.add("search-dialog")
        box.padding = Insets(12.0)
        val scene = Scene(box, 560.0, 420.0)
        scene.stylesheets.addAll(owner.scene.stylesheets)
        scene.setOnKeyPressed { e ->
            if (e.code == KeyCode.ESCAPE) stage.hide()
        }
        stage.scene = scene
        stage.focusedProperty().addListener(ChangeListener { _, _, now ->
            if (now != true) stage.hide()
        })
    }

    fun field(): TextField = field

    fun results(): ListView<Hit> = results

    fun show() {
        // 書きかけの変更も探せるように、先に書いておく
        s.saver.flushAndWait(2, TimeUnit.SECONDS)
        val owner = stage.owner
        stage.x = owner.x + (owner.width - 560) / 2
        stage.y = owner.y + 90
        field.selectAll()
        runSearch()
        stage.show()
        field.requestFocus()
    }

    private fun runSearch() {
        val q = field.text
        searchedQuery = q
        if (q == null || q.isJavaBlank()) {
            results.items.clear()
            empty.text = "語句を入れると、すべてのページから探します"
            return
        }
        val hits = Errors.get("検索") { s.search.search(q) } ?: emptyList()
        results.items.setAll(hits)
        empty.text = "見つかりませんでした"
        if (hits.isNotEmpty()) results.selectionModel.selectFirst()
    }

    private fun openSelected() {
        val h = results.selectionModel.selectedItem ?: return
        stage.hide()
        onOpen(h)
    }
}
