package app.dearnotes.ui;

import app.dearnotes.db.SearchRepo.Hit;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;

/** Cmd/Ctrl+K の検索。↑↓ で選び Enter で開く。 */
final class SearchDialog {

    private final Services s;
    private final Consumer<Hit> onOpen;
    private final Stage stage = new Stage(StageStyle.UNDECORATED);
    private final TextField field = new TextField();
    private final ListView<Hit> results = new ListView<>();
    private final Label empty = new Label();
    private final PauseTransition debounce = new PauseTransition(Duration.millis(120));
    /** いまの results を出したときの検索語。 */
    private String searchedQuery;

    SearchDialog(Window owner, Services services, Consumer<Hit> onOpen) {
        this.s = services;
        this.onOpen = onOpen;
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);

        field.setPromptText("ページを検索…");
        field.getStyleClass().add("search-field");
        field.textProperty().addListener((obs, o, n) -> debounce.playFromStart());
        debounce.setOnFinished(e -> runSearch());

        results.getStyleClass().add("search-results");
        results.setPlaceholder(empty);
        results.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Hit h, boolean isEmpty) {
                super.updateItem(h, isEmpty);
                if (isEmpty || h == null) {
                    setGraphic(null);
                    return;
                }
                String icon = h.pageIcon() != null ? h.pageIcon() : (h.daily() ? "📅" : "📝");
                String title = h.pageTitle() == null || h.pageTitle().isBlank() ? "無題" : h.pageTitle();
                var t = new Label(icon + "  " + title);
                t.getStyleClass().add("hit-title");
                var snip = new Label(h.snippet());
                snip.getStyleClass().add("hit-snippet");
                setGraphic(new VBox(2, t, snip));
            }
        });
        results.setOnMouseClicked(e -> {
            // 語句を変えた直後の古い結果は開かず、探し直すだけにする
            if (!field.getText().equals(searchedQuery)) {
                debounce.stop();
                runSearch();
            } else if (e.getClickCount() >= 1) {
                openSelected();
            }
        });

        field.setOnKeyPressed(e -> {
            var sel = results.getSelectionModel();
            switch (e.getCode()) {
                case DOWN -> {
                    sel.select(Math.min(results.getItems().size() - 1, sel.getSelectedIndex() + 1));
                    results.scrollTo(sel.getSelectedIndex());
                    e.consume();
                }
                case UP -> {
                    sel.select(Math.max(0, sel.getSelectedIndex() - 1));
                    results.scrollTo(sel.getSelectedIndex());
                    e.consume();
                }
                case ENTER -> {
                    debounce.stop();
                    // 語句を変えてすぐ Enter したときは、古い結果を開かないよう探し直す
                    if (results.getItems().isEmpty() || !field.getText().equals(searchedQuery)) runSearch();
                    openSelected();
                    e.consume();
                }
                case ESCAPE -> {
                    stage.hide();
                    e.consume();
                }
                default -> { }
            }
        });

        var box = new VBox(8, field, results);
        box.getStyleClass().add("search-dialog");
        box.setPadding(new Insets(12));
        var scene = new Scene(box, 560, 420);
        scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) stage.hide();
        });
        stage.setScene(scene);
        stage.focusedProperty().addListener((obs, was, now) -> {
            if (!now) stage.hide();
        });
    }

    TextField field() {
        return field;
    }

    ListView<Hit> results() {
        return results;
    }

    void show() {
        // 書きかけの変更も探せるように、先に書いておく
        s.saver().flushAndWait(2, TimeUnit.SECONDS);
        Window owner = stage.getOwner();
        stage.setX(owner.getX() + (owner.getWidth() - 560) / 2);
        stage.setY(owner.getY() + 90);
        field.selectAll();
        runSearch();
        stage.show();
        field.requestFocus();
    }

    private void runSearch() {
        String q = field.getText();
        searchedQuery = q;
        if (q == null || q.isBlank()) {
            results.getItems().clear();
            empty.setText("語句を入れると、すべてのページから探します");
            return;
        }
        List<Hit> hits = Errors.get("検索", () -> s.search().search(q)).orElse(List.of());
        results.getItems().setAll(hits);
        empty.setText("見つかりませんでした");
        if (!hits.isEmpty()) results.getSelectionModel().selectFirst();
    }

    private void openSelected() {
        Hit h = results.getSelectionModel().getSelectedItem();
        if (h == null) return;
        stage.hide();
        onOpen.accept(h);
    }
}
