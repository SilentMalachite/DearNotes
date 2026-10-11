package app.dearnotes.ui;

import app.dearnotes.model.Page;
import app.dearnotes.model.SortKeys;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** ページの木。上に「検索」「今日」「新しいページ」、下に「ゴミ箱」。 */
final class Sidebar extends VBox {

    /** 木の1行。見出し（ページ / デイリー）かページ。 */
    sealed interface Entry permits Section, PageEntry {}

    record Section(String label, boolean daily) implements Entry {}

    record PageEntry(Page page) implements Entry {}

    interface Actions {
        void open(Page p);
        void openToday();
        void newPage(String parentId);
        void trash(Page p);
        void showTrash();
        void showSearch();
    }

    private enum Drop { BEFORE, INTO, AFTER }

    private final Services s;
    private final Actions actions;
    private final TreeView<Entry> tree = new TreeView<>();
    private final TreeItem<Entry> pagesSection = new TreeItem<>(new Section("ページ", false));
    private final TreeItem<Entry> dailySection = new TreeItem<>(new Section("デイリー", true));
    private final Set<String> expanded = new HashSet<>();
    private List<Page> alive = List.of();
    private Page dragging;
    private boolean selecting;
    private String currentId;

    Sidebar(Services services, Actions actions) {
        this.s = services;
        this.actions = actions;
        getStyleClass().add("sidebar");

        var search = navButton("🔍  検索", actions::showSearch);
        var today = navButton("📅  今日 · " + todayLabel(), actions::openToday);
        var newPage = navButton("＋  新しいページ", () -> actions.newPage(null));

        var root = new TreeItem<Entry>();
        root.getChildren().addAll(pagesSection, dailySection);
        pagesSection.setExpanded(true);
        dailySection.setExpanded(true);
        tree.setRoot(root);
        tree.setShowRoot(false);
        tree.getStyleClass().add("page-tree");
        tree.setCellFactory(t -> new PageCell());
        tree.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> {
            if (selecting || item == null) return;
            // 木の選択イベントの最中に木を作り直さないよう、開くのは後にする
            if (item.getValue() instanceof PageEntry(Page p)) javafx.application.Platform.runLater(() -> actions.open(p));
        });
        VBox.setVgrow(tree, Priority.ALWAYS);

        var trash = navButton("🗑  ゴミ箱", actions::showTrash);
        getChildren().addAll(search, today, newPage, tree, trash);
    }

    /** DB から読み直す。開いている枝と選択は残す。 */
    void reload(String selectedId) {
        rememberExpanded(pagesSection);
        rememberExpanded(dailySection);
        alive = Errors.get("ページの一覧を読む", s.pages()::listAlive).orElse(List.of());

        Map<String, List<Page>> children = new HashMap<>();
        for (Page p : alive) children.computeIfAbsent(p.parentId(), k -> new ArrayList<>()).add(p);
        children.values().forEach(l -> l.sort(Comparator.comparingDouble(Page::sortKey)));

        List<Page> top = children.getOrDefault(null, List.of());
        pagesSection.getChildren().setAll(top.stream().filter(p -> !p.isDaily())
            .map(p -> build(p, children)).toList());
        dailySection.getChildren().setAll(top.stream().filter(Page::isDaily)
            .sorted(Comparator.comparing(Page::dailyDate).reversed())
            .map(p -> build(p, children)).toList());
        select(selectedId);
    }

    void select(String pageId) {
        currentId = pageId;
        selecting = true;
        try {
            TreeItem<Entry> item = find(tree.getRoot(), pageId);
            if (item == null) {
                tree.getSelectionModel().clearSelection();
                return;
            }
            for (var p = item.getParent(); p != null; p = p.getParent()) p.setExpanded(true);
            tree.getSelectionModel().select(item);
            int row = tree.getRow(item);
            if (row >= 0) tree.scrollTo(Math.max(0, row - 3));
        } finally {
            selecting = false;
        }
    }

    /** ページの親をたどった道筋。パンくず用。 */
    List<Page> pathTo(Page page) {
        Map<String, Page> byId = new HashMap<>();
        for (Page p : alive) byId.put(p.id(), p);
        var path = new ArrayList<Page>();
        for (Page p = page; p != null; p = p.parentId() == null ? null : byId.get(p.parentId())) {
            path.addFirst(p);
            if (path.size() > 32) break;
        }
        return path;
    }

    private TreeItem<Entry> build(Page p, Map<String, List<Page>> children) {
        var item = new TreeItem<Entry>(new PageEntry(p));
        for (Page c : children.getOrDefault(p.id(), List.of())) item.getChildren().add(build(c, children));
        item.setExpanded(expanded.contains(p.id()));
        item.expandedProperty().addListener((obs, was, now) -> {
            if (now) expanded.add(p.id());
            else expanded.remove(p.id());
        });
        return item;
    }

    private void rememberExpanded(TreeItem<Entry> item) {
        for (var child : item.getChildren()) {
            if (child.getValue() instanceof PageEntry(Page p) && child.isExpanded()) expanded.add(p.id());
            rememberExpanded(child);
        }
    }

    private static TreeItem<Entry> find(TreeItem<Entry> item, String id) {
        if (id == null) return null;
        if (item.getValue() instanceof PageEntry(Page p) && p.id().equals(id)) return item;
        for (var child : item.getChildren()) {
            var hit = find(child, id);
            if (hit != null) return hit;
        }
        return null;
    }

    private Button navButton(String text, Runnable action) {
        var b = new Button(text);
        b.getStyleClass().add("nav-button");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        return b;
    }

    private static String todayLabel() {
        LocalDate d = LocalDate.now();
        return d.getMonthValue() + "月" + d.getDayOfMonth() + "日";
    }

    // ---- ドラッグで移動 ----

    private void dropOnto(Page target, Drop where) {
        Page moving = dragging;
        if (moving == null || moving.id().equals(target.id())) return;
        String parentId = where == Drop.INTO ? target.id() : target.parentId();
        Double key = keyFor(parentId, moving, target, where);
        if (key == null) {
            Errors.run("並べ替え", () -> s.pages().respaceChildren(parentId));
            alive = Errors.get("ページの一覧を読む", s.pages()::listAlive).orElse(List.of());
            Page fresh = alive.stream().filter(p -> p.id().equals(target.id())).findFirst().orElse(target);
            key = keyFor(parentId, moving, fresh, where);
        }
        double k = key == null ? SortKeys.GAP : key;
        if (Errors.run("ページを移動", () -> s.pages().move(moving.id(), parentId, k))) {
            if (where == Drop.INTO) expanded.add(target.id());
            reload(currentId);
        }
    }

    private Double keyFor(String parentId, Page moving, Page target, Drop where) {
        List<Page> siblings = alive.stream()
            .filter(p -> Objects.equals(p.parentId(), parentId) && !p.id().equals(moving.id()) && !p.isDaily())
            .sorted(Comparator.comparingDouble(Page::sortKey))
            .toList();
        if (where == Drop.INTO) {
            return SortKeys.between(siblings.isEmpty() ? null : siblings.getLast().sortKey(), null);
        }
        int i = siblings.indexOf(target);
        if (i < 0) return SortKeys.between(siblings.isEmpty() ? null : siblings.getLast().sortKey(), null);
        Double prev = where == Drop.BEFORE ? (i > 0 ? siblings.get(i - 1).sortKey() : null) : siblings.get(i).sortKey();
        Double next = where == Drop.BEFORE ? siblings.get(i).sortKey() : (i + 1 < siblings.size() ? siblings.get(i + 1).sortKey() : null);
        return SortKeys.between(prev, next);
    }

    private final class PageCell extends TreeCell<Entry> {

        PageCell() {
            // 長い名前で木が横に広がらないよう、幅は木に合わせて「…」で切る
            setPrefWidth(0);
            setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
            setOnDragDetected(e -> {
                if (getItem() instanceof PageEntry(Page p) && !p.isDaily()) {
                    dragging = p;
                    var db = startDragAndDrop(TransferMode.MOVE);
                    var content = new ClipboardContent();
                    content.putString(p.displayTitle());
                    db.setContent(content);
                    e.consume();
                }
            });
            setOnDragOver(this::dragOver);
            setOnDragExited(e -> hint(null));
            setOnDragDropped(e -> {
                hint(null);
                Drop where = dropPosition(e);
                if (dragging != null && where != null) {
                    if (getItem() instanceof PageEntry(Page target)) {
                        dropOnto(target, where);
                    } else if (getItem() instanceof Section(var l, boolean daily) && !daily) {
                        Page moving = dragging;
                        Double key = SortKeys.between(lastTopKey(moving), null);
                        double k = key == null ? SortKeys.GAP : key;
                        if (Errors.run("ページを移動", () -> s.pages().move(moving.id(), null, k))) reload(currentId);
                    }
                    e.setDropCompleted(true);
                }
                e.consume();
            });
            setOnDragDone(e -> dragging = null);
        }

        private Double lastTopKey(Page moving) {
            return alive.stream()
                .filter(p -> p.parentId() == null && !p.isDaily() && !p.id().equals(moving.id()))
                .map(Page::sortKey).max(Double::compare).orElse(null);
        }

        private void dragOver(DragEvent e) {
            Drop where = dropPosition(e);
            if (dragging != null && where != null) {
                e.acceptTransferModes(TransferMode.MOVE);
                hint(where);
            }
            e.consume();
        }

        private Drop dropPosition(DragEvent e) {
            if (dragging == null || isEmpty() || getItem() == null) return null;
            if (getItem() instanceof Section(var l, boolean daily)) return daily ? null : Drop.INTO;
            Page target = ((PageEntry) getItem()).page();
            if (target.id().equals(dragging.id())) return null;
            // 自分の子孫には落とせない
            for (var p = getTreeItem(); p != null; p = p.getParent()) {
                if (p.getValue() instanceof PageEntry(Page anc) && anc.id().equals(dragging.id())) return null;
            }
            double y = e.getY() / getHeight();
            if (target.isDaily() && target.parentId() == null) return Drop.INTO;
            if (y < 0.25) return Drop.BEFORE;
            if (y > 0.75) return Drop.AFTER;
            return Drop.INTO;
        }

        private void hint(Drop where) {
            getStyleClass().removeAll("drop-before", "drop-into", "drop-after");
            if (where != null) getStyleClass().add("drop-" + where.name().toLowerCase(java.util.Locale.ROOT));
        }

        @Override
        protected void updateItem(Entry entry, boolean empty) {
            super.updateItem(entry, empty);
            getStyleClass().removeAll("section-cell", "page-cell");
            setContextMenu(null);
            if (empty || entry == null) {
                setText(null);
                return;
            }
            switch (entry) {
                case Section(String label, boolean daily) -> {
                    setText(label);
                    getStyleClass().add("section-cell");
                    if (!daily) {
                        var add = new MenuItem("新しいページ");
                        add.setOnAction(e -> actions.newPage(null));
                        setContextMenu(new ContextMenu(add));
                    }
                }
                case PageEntry(Page p) -> {
                    setText(p.displayIcon() + "  " + p.displayTitle());
                    getStyleClass().add("page-cell");
                    setContextMenu(menuFor(p));
                }
            }
        }

        private ContextMenu menuFor(Page p) {
            var child = new MenuItem("子ページを追加");
            child.setOnAction(e -> {
                expanded.add(p.id());
                actions.newPage(p.id());
            });
            var trash = new MenuItem("ゴミ箱へ移動");
            trash.setOnAction(e -> actions.trash(p));
            return new ContextMenu(child, new SeparatorMenuItem(), trash);
        }
    }
}
