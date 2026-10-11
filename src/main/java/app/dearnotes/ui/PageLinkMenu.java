package app.dearnotes.ui;

import app.dearnotes.model.Page;
import java.util.List;
import javafx.geometry.Bounds;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyEvent;
import javafx.stage.Popup;

/** 「[[」で出るページの候補。フォーカスはエリアに残し、キーは BlockView から受け取る。 */
final class PageLinkMenu {

    /** 候補の1行。page が null なら、name で新しいページを作る行。 */
    record Choice(Page page, String name) {}

    @FunctionalInterface
    interface OnPick {
        void pick(BlockView v, int from, Choice c);
    }

    private static final double ROW = 28;

    private final Popup popup = new Popup();
    private final ListView<Choice> list = new ListView<>();
    private final OnPick onPick;
    private BlockView target;
    private int from;
    /** Esc で閉じた「[[」。同じ「[[」の後ろで打ち続けても、候補を出し直さない。 */
    private BlockView dismissedView;
    private int dismissedFrom = -1;

    PageLinkMenu(OnPick onPick) {
        this.onPick = onPick;
        list.getStyleClass().addAll("slash-menu", "page-link-menu");
        list.setFocusTraversable(false);
        list.setPrefWidth(280);
        list.setFixedCellSize(ROW);
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Choice c, boolean empty) {
                super.updateItem(c, empty);
                if (empty || c == null) {
                    setGraphic(null);
                    return;
                }
                setGraphic(new Label(c.page() == null
                    ? "＋  新しいページ「" + c.name() + "」を作る"
                    : c.page().displayIcon() + "  " + c.page().displayTitle()));
            }
        });
        list.setOnMouseClicked(e -> pick());
        popup.getContent().add(list);
        popup.setAutoHide(true);
        // Esc は Popup が先に取ってしまうので、handleKey で受けて閉じたことを覚える
        popup.setHideOnEscape(false);
        popup.setOnHidden(e -> target = null);
    }

    boolean isShowing() {
        return popup.isShowing();
    }

    /** v のカーソルの下に候補を出す。from は「[[」の位置。候補がなければ閉じる。 */
    void show(BlockView v, int from, List<Choice> items) {
        if (items.isEmpty()) {
            close(v);
            return;
        }
        Bounds b = v.caretBounds().orElse(null);
        if (b == null || v.getScene() == null) return;
        target = v;
        this.from = from;
        list.getItems().setAll(items);
        list.getSelectionModel().selectFirst();
        list.setPrefHeight(Math.min(items.size(), 10) * ROW + 4);
        if (popup.isShowing()) {
            popup.setX(b.getMinX());
            popup.setY(b.getMaxY() + 4);
        } else {
            popup.show(v.getScene().getWindow(), b.getMinX(), b.getMaxY() + 4);
        }
    }

    /** v に出ていれば閉じる。 */
    void close(BlockView v) {
        if (v == target) hide();
    }

    /** v の from の「[[」を Esc で閉じたか。 */
    boolean dismissed(BlockView v, int from) {
        return v == dismissedView && from == dismissedFrom;
    }

    /** v の「[[」が無くなった。閉じて、Esc で閉じたことも忘れる。 */
    void forget(BlockView v) {
        close(v);
        if (v == dismissedView) {
            dismissedView = null;
            dismissedFrom = -1;
        }
    }

    /** 候補が出ているときの ↑↓ Enter Tab Esc。使ったら true。 */
    boolean handleKey(BlockView v, KeyEvent e) {
        if (!popup.isShowing() || v != target) return false;
        var sel = list.getSelectionModel();
        switch (e.getCode()) {
            case UP -> sel.select(Math.max(0, sel.getSelectedIndex() - 1));
            case DOWN -> sel.select(Math.min(list.getItems().size() - 1, sel.getSelectedIndex() + 1));
            case ENTER, TAB -> pick();
            case ESCAPE -> {
                dismissedView = v;
                dismissedFrom = from;
                hide();
            }
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
        Choice c = list.getSelectionModel().getSelectedItem();
        BlockView v = target;
        int at = from;
        hide();
        if (c != null && v != null) onPick.pick(v, at, c);
    }
}
