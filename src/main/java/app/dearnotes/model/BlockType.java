package app.dearnotes.model;

import java.util.Locale;

/** ブロックの種類。DB には小文字の名前で入る。 */
public enum BlockType {
    PARAGRAPH("テキスト"),
    H1("見出し 1"),
    H2("見出し 2"),
    H3("見出し 3"),
    BULLET("箇条書き"),
    NUMBERED("番号付きリスト"),
    TODO("ToDo"),
    QUOTE("引用"),
    CODE("コード"),
    DIVIDER("区切り線");

    private final String label;

    BlockType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public String dbName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static BlockType fromDb(String name) {
        return valueOf(name.toUpperCase(Locale.ROOT));
    }

    /** Enter で次に作るブロックの種類。リスト系は同じ種類を続ける。 */
    public BlockType next() {
        return switch (this) {
            case BULLET, NUMBERED, TODO -> this;
            default -> PARAGRAPH;
        };
    }

    public boolean isList() {
        return this == BULLET || this == NUMBERED || this == TODO;
    }

    public boolean hasText() {
        return this != DIVIDER;
    }
}
