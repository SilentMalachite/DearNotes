package app.dearnotes.ui;

import app.dearnotes.editor.Outline;
import java.util.List;
import java.util.function.Consumer;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** 本文の右に出す目次。H1〜H3 を段ごとに字下げして並べ、押すとその見出しへ移る。 */
final class OutlinePanel extends VBox {

    private final VBox items = new VBox();
    private final Consumer<String> onJump;

    OutlinePanel(Consumer<String> onJump) {
        this.onJump = onJump;
        getStyleClass().add("outline-panel");
        setMinWidth(160);
        setPrefWidth(220);

        var title = new Label("目次");
        title.getStyleClass().add("outline-title");
        items.getStyleClass().add("outline-items");
        var scroll = new ScrollPane(items);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("outline-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(title, scroll);
    }

    /** 見出しの一覧を作り直す。currentId の見出しを太字にする。 */
    void show(List<Outline.Heading> headings, String currentId) {
        items.getChildren().clear();
        if (headings.isEmpty()) {
            var none = new Label("見出しがありません");
            none.getStyleClass().add("outline-empty");
            items.getChildren().add(none);
            return;
        }
        for (Outline.Heading h : headings) {
            boolean blank = h.text().isBlank();
            var item = new Label(blank ? "無題の見出し" : h.text().replace('\n', ' '));
            item.getStyleClass().addAll("outline-item", "outline-h" + h.level());
            if (blank) item.getStyleClass().add("blank");
            if (h.blockId().equals(currentId)) item.getStyleClass().add("current");
            item.setMaxWidth(Double.MAX_VALUE);
            item.setTextOverrun(OverrunStyle.ELLIPSIS);
            item.setOnMouseClicked(e -> onJump.accept(h.blockId()));
            items.getChildren().add(item);
        }
    }

    List<Label> items() {
        return items.getChildren().stream().filter(Label.class::isInstance).map(Label.class::cast).toList();
    }
}
