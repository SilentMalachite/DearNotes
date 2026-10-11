package app.dearnotes.ui;

import app.dearnotes.editor.SlashCommands;
import app.dearnotes.editor.SlashCommands.Command;
import java.util.List;
import java.util.function.BiConsumer;
import javafx.geometry.Bounds;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Popup;

/** 「/」で出る種類の候補。フォーカスはエリアに残し、キーは BlockView から受け取る。 */
final class SlashMenu {

    private final Popup popup = new Popup();
    private final ListView<Command> list = new ListView<>();
    private final BiConsumer<BlockView, Command> onPick;
    private BlockView target;

    SlashMenu(BiConsumer<BlockView, Command> onPick) {
        this.onPick = onPick;
        list.getStyleClass().add("slash-menu");
        list.setFocusTraversable(false);
        list.setPrefWidth(240);
        list.setFixedCellSize(28);
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Command c, boolean empty) {
                super.updateItem(c, empty);
                if (empty || c == null) {
                    setGraphic(null);
                    return;
                }
                var label = new Label(c.label());
                var shortcut = new Label(c.shortcut());
                shortcut.getStyleClass().add("slash-shortcut");
                var spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                setGraphic(new HBox(8, label, spacer, shortcut));
            }
        });
        list.setOnMouseClicked(e -> pick());
        popup.getContent().add(list);
        popup.setAutoHide(true);
        popup.setOnHidden(e -> target = null);
    }

    boolean isShowing() {
        return popup.isShowing();
    }

    BlockView target() {
        return target;
    }

    void update(BlockView v, String query) {
        if (query == null) {
            if (v == target) hide();
            return;
        }
        List<Command> items = SlashCommands.filter(query);
        if (items.isEmpty()) {
            hide();
            return;
        }
        target = v;
        list.getItems().setAll(items);
        list.getSelectionModel().selectFirst();
        list.setPrefHeight(Math.min(items.size(), 10) * 28 + 4);
        Bounds b = v.caretBounds().orElse(null);
        if (b == null) return;
        if (popup.isShowing()) {
            popup.setX(b.getMinX());
            popup.setY(b.getMaxY() + 4);
        } else {
            popup.show(v.getScene().getWindow(), b.getMinX(), b.getMaxY() + 4);
        }
    }

    /** メニューが出ているときの ↑↓ Enter Esc。使ったら true。 */
    boolean handleKey(BlockView v, KeyEvent e) {
        if (!popup.isShowing() || v != target) return false;
        var sel = list.getSelectionModel();
        switch (e.getCode()) {
            case UP -> sel.select(Math.max(0, sel.getSelectedIndex() - 1));
            case DOWN -> sel.select(Math.min(list.getItems().size() - 1, sel.getSelectedIndex() + 1));
            case ENTER, TAB -> pick();
            case ESCAPE -> hide();
            default -> {
                return false;
            }
        }
        list.scrollTo(sel.getSelectedIndex());
        return true;
    }

    void hide() {
        popup.hide();
        target = null;
    }

    private void pick() {
        Command c = list.getSelectionModel().getSelectedItem();
        BlockView v = target;
        hide();
        if (c != null && v != null) onPick.accept(v, c);
    }
}
