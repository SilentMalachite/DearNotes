package app.dearnotes.ui

import app.dearnotes.save.SaveStatus
import java.util.Locale
import javafx.scene.control.Label

/** 右上の「保存済み」「保存中…」。 */
internal class SaveIndicator : Label() {

    init {
        styleClass.add("save-indicator")
        show(SaveStatus.SAVED)
    }

    fun show(status: SaveStatus) {
        text = status.label()
        for (s in SaveStatus.values()) styleClass.remove("save-" + s.name.lowercase(Locale.ROOT))
        styleClass.add("save-" + status.name.lowercase(Locale.ROOT))
    }
}
