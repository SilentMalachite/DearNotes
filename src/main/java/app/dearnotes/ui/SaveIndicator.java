package app.dearnotes.ui;

import app.dearnotes.save.SaveStatus;
import javafx.scene.control.Label;

/** 右上の「保存済み」「保存中…」。 */
final class SaveIndicator extends Label {

    SaveIndicator() {
        getStyleClass().add("save-indicator");
        show(SaveStatus.SAVED);
    }

    void show(SaveStatus status) {
        setText(status.label());
        for (SaveStatus s : SaveStatus.values()) getStyleClass().remove("save-" + s.name().toLowerCase(java.util.Locale.ROOT));
        getStyleClass().add("save-" + status.name().toLowerCase(java.util.Locale.ROOT));
    }
}
