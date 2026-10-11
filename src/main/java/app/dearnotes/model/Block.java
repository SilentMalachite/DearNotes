package app.dearnotes.model;

import java.util.List;

/** ページの1行。DB の blocks テーブルの1行に対応する。 */
public record Block(
        String id,
        String pageId,
        BlockType type,
        String text,
        List<Mark> marks,
        boolean checked,
        int indent,
        double sortKey) {

    public static final int MAX_INDENT = 6;

    public Block {
        text = text == null ? "" : text;
        marks = marks == null ? List.of() : List.copyOf(marks);
        indent = Math.clamp(indent, 0, MAX_INDENT);
    }

    public static Block empty(String pageId, BlockType type, double sortKey) {
        return new Block(Ids.uuidV7(), pageId, type, "", List.of(), false, 0, sortKey);
    }

    public Block withType(BlockType t) {
        return new Block(id, pageId, t, text, marks, checked, indent, sortKey);
    }

    public Block withContent(String t, List<Mark> m) {
        return new Block(id, pageId, type, t, m, checked, indent, sortKey);
    }

    public Block withChecked(boolean c) {
        return new Block(id, pageId, type, text, marks, c, indent, sortKey);
    }

    public Block withIndent(int i) {
        return new Block(id, pageId, type, text, marks, checked, i, sortKey);
    }

    public Block withSortKey(double k) {
        return new Block(id, pageId, type, text, marks, checked, indent, k);
    }
}
