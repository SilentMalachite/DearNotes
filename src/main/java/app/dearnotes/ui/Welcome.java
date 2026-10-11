package app.dearnotes.ui;

import app.dearnotes.db.BlockRepo;
import app.dearnotes.db.PageRepo;
import app.dearnotes.model.Block;
import app.dearnotes.model.BlockType;
import app.dearnotes.model.Mark;
import app.dearnotes.model.Page;
import app.dearnotes.model.SortKeys;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** 初めて起動したときに作る、使い方のページ。 */
public final class Welcome {

    private Welcome() {}

    public static void createIfEmpty(PageRepo pages, BlockRepo blocks) throws SQLException {
        if (!pages.listAlive().isEmpty() || !pages.listTrash().isEmpty()) return;
        Page p = pages.create(null, "DearNotes へようこそ");
        pages.setIcon(p.id(), "👋");

        var lines = new ArrayList<Block>();
        String intro = "Notion のように、1行ずつブロックで書くメモアプリです。変更は自動で保存されます。";
        add(lines, p, BlockType.PARAGRAPH, intro, List.of(mark(intro, "自動で保存", Mark.Style.BOLD)));
        add(lines, p, BlockType.H2, "書き方", List.of());
        add(lines, p, BlockType.BULLET, "行頭に「# 」「- 」「[] 」「> 」などを打つと、ブロックの種類が変わります", List.of());
        add(lines, p, BlockType.BULLET, "「/」を打つとブロックの種類を選べます", List.of());
        add(lines, p, BlockType.BULLET, "Tab / Shift+Tab で字下げ、左端の ⋮⋮ をドラッグして並べ替え", List.of());
        String styles = "文字を選んで Cmd/Ctrl+B で太字、+I で斜体、+E でコード、+Shift+S で取り消し線";
        add(lines, p, BlockType.BULLET, styles, List.of(
            mark(styles, "太字", Mark.Style.BOLD), mark(styles, "斜体", Mark.Style.ITALIC),
            mark(styles, "コード", Mark.Style.CODE), mark(styles, "取り消し線", Mark.Style.STRIKE)));
        add(lines, p, BlockType.H2, "便利なキー", List.of());
        add(lines, p, BlockType.TODO, "Cmd/Ctrl+K で全ページを検索する", List.of());
        add(lines, p, BlockType.TODO, "Cmd/Ctrl+N で新しいページを作る", List.of());
        add(lines, p, BlockType.TODO, "Cmd/Ctrl+Shift+D で今日の日記を開く", List.of());
        add(lines, p, BlockType.DIVIDER, "", List.of());
        add(lines, p, BlockType.QUOTE, "サイドバーのページを右クリックすると、子ページの追加やゴミ箱への移動ができます。", List.of());
        blocks.save(lines, List.of());
    }

    private static void add(List<Block> out, Page p, BlockType type, String text, List<Mark> marks) {
        double key = SortKeys.GAP * (out.size() + 1);
        out.add(Block.empty(p.id(), type, key).withContent(text, marks));
    }

    private static Mark mark(String text, String part, Mark.Style style) {
        int at = text.indexOf(part);
        return new Mark(at, at + part.length(), style);
    }
}
