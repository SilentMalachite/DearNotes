package app.dearnotes.ui;

import app.dearnotes.db.BlockRepo.Backlink;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** ページの下に出す「このページへのリンク」。行を押すと、リンクのある行へ移る。 */
final class Backlinks extends VBox {

    private static final int SNIPPET = 60;

    private final Label header = new Label();
    private final Consumer<Backlink> onOpen;
    private final List<Backlink> shown = new ArrayList<>();
    private final List<String> titles = new ArrayList<>();

    Backlinks(Consumer<Backlink> onOpen) {
        this.onOpen = onOpen;
        getStyleClass().add("backlinks");
        header.getStyleClass().add("backlinks-header");
        show(List.of());
    }

    /** 一覧を作り直す。なければ隠す。 */
    void show(List<Backlink> links) {
        shown.clear();
        shown.addAll(links);
        titles.clear();
        getChildren().setAll(header);
        header.setText("このページへのリンク · " + links.size());
        for (int i = 0; i < links.size(); i++) {
            Backlink b = links.get(i);
            String title = icon(b) + " " + (b.pageTitle() == null || b.pageTitle().isBlank() ? "無題" : b.pageTitle());
            titles.add(title);
            var name = new Label(title);
            name.getStyleClass().add("backlink-title");
            var snippet = new Label(snippet(b.text()));
            snippet.getStyleClass().add("backlink-snippet");
            var row = new HBox(8, name, snippet);
            row.getStyleClass().add("backlink");
            int at = i;
            row.setOnMouseClicked(e -> open(at));
            getChildren().add(row);
        }
        setVisible(!links.isEmpty());
        setManaged(!links.isEmpty());
    }

    List<String> titles() {
        return List.copyOf(titles);
    }

    void open(int index) {
        if (index >= 0 && index < shown.size()) onOpen.accept(shown.get(index));
    }

    private static String icon(Backlink b) {
        if (b.pageIcon() != null && !b.pageIcon().isBlank()) return b.pageIcon();
        return b.daily() ? "📅" : "📝";
    }

    private static String snippet(String text) {
        String flat = text.replace('\n', ' ').strip();
        if (flat.codePointCount(0, flat.length()) <= SNIPPET) return flat;
        return flat.substring(0, flat.offsetByCodePoints(0, SNIPPET)) + "…";
    }
}
