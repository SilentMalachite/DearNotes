package app.dearnotes.editor;

import app.dearnotes.model.BlockType;
import java.util.Optional;

/** 行頭に打つと種類が変わる記号。 */
public final class MarkdownShortcuts {

    public record Hit(BlockType type, int prefixLength) {}

    private MarkdownShortcuts() {}

    static Optional<BlockType> match(String prefix) {
        return Optional.ofNullable(switch (prefix) {
            case "# " -> BlockType.H1;
            case "## " -> BlockType.H2;
            case "### " -> BlockType.H3;
            case "- ", "* " -> BlockType.BULLET;
            case "1. " -> BlockType.NUMBERED;
            case "[] " -> BlockType.TODO;
            case "> " -> BlockType.QUOTE;
            case "```" -> BlockType.CODE;
            case "---" -> BlockType.DIVIDER;
            default -> null;
        });
    }

    /** 入力直後の caret の位置までが記号だけなら、その種類を返す。 */
    public static Optional<Hit> detect(String text, int caret) {
        if (caret < 2 || caret > 4 || caret > text.length()) return Optional.empty();
        return match(halfWidth(text.substring(0, caret))).map(t -> new Hit(t, caret));
    }

    /** 日本語入力のまま打った「＃　」「ー　」なども同じに扱う。1文字は1文字のまま。 */
    static String halfWidth(String s) {
        var sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= '\uFF01' && c <= '\uFF5E') sb.append((char) (c - 0xFEE0));
            else if (c == '\u3000') sb.append(' ');
            else if (c == 'ー' || c == '－' || c == '―') sb.append('-');
            else if (c == '「' || c == '【') sb.append('[');
            else if (c == '」' || c == '】') sb.append(']');
            else if (c == '。') sb.append('.');
            else sb.append(c);
        }
        return sb.toString();
    }
}
