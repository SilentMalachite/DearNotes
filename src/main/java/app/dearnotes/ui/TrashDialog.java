package app.dearnotes.ui;

import app.dearnotes.db.PageRepo;
import app.dearnotes.model.Page;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/** ゴミ箱。戻すか、完全に消す。30日たつと起動時に消える。 */
final class TrashDialog {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Services s;
    private final Runnable onChanged;
    private final Stage stage = new Stage();
    private final ListView<Page> list = new ListView<>();

    TrashDialog(Window owner, Services services, Runnable onChanged) {
        this.s = services;
        this.onChanged = onChanged;
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("ゴミ箱");

        list.setPlaceholder(new Label("ゴミ箱は空です"));
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Page p, boolean empty) {
                super.updateItem(p, empty);
                if (empty || p == null) {
                    setText(null);
                    return;
                }
                String when = WHEN.format(Instant.ofEpochMilli(p.deletedAt()).atZone(ZoneId.systemDefault()));
                setText(p.displayIcon() + "  " + p.displayTitle() + "    （" + when + " に削除）");
            }
        });

        var note = new Label("ゴミ箱のページは " + PageRepo.TRASH_RETENTION.toDays() + " 日後に自動で消えます。");
        note.getStyleClass().add("muted");
        var restore = new Button("元に戻す");
        restore.setOnAction(e -> restoreSelected());
        var delete = new Button("完全に削除");
        delete.setOnAction(e -> deleteSelected());
        var close = new Button("閉じる");
        close.setOnAction(e -> stage.hide());
        restore.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        delete.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var buttons = new HBox(8, restore, delete, spacer, close);
        buttons.setAlignment(Pos.CENTER_LEFT);

        var box = new VBox(10, note, list, buttons);
        box.setPadding(new Insets(14));
        VBox.setVgrow(list, Priority.ALWAYS);
        var scene = new Scene(box, 520, 400);
        scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        stage.setScene(scene);
    }

    void show() {
        reload();
        stage.show();
    }

    private void reload() {
        List<Page> pages = Errors.get("ゴミ箱を読む", s.pages()::listTrash).orElse(List.of());
        list.getItems().setAll(pages);
    }

    private void restoreSelected() {
        Page p = list.getSelectionModel().getSelectedItem();
        if (p != null && Errors.run("元に戻す", () -> s.pages().restore(p.id()))) {
            reload();
            onChanged.run();
        }
    }

    private void deleteSelected() {
        Page p = list.getSelectionModel().getSelectedItem();
        if (p == null) return;
        var confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "「" + p.displayTitle() + "」と子ページを完全に削除します。元に戻せません。",
            ButtonType.CANCEL, ButtonType.OK);
        confirm.initOwner(stage);
        confirm.setHeaderText(null);
        confirm.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
            if (Errors.run("削除", () -> s.pages().deleteForever(p.id()))) {
                reload();
                onChanged.run();
            }
        });
    }
}
