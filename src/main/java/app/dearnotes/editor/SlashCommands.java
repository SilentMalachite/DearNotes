package app.dearnotes.editor;

import app.dearnotes.model.BlockType;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** 「/」のあとに打った文字でブロックの種類を絞り込む。 */
public final class SlashCommands {

    public record Command(BlockType type, String label, String shortcut, List<String> keywords) {
        boolean matches(String q) {
            if (q.isEmpty()) return true;
            String lower = q.toLowerCase(Locale.ROOT);
            return label.toLowerCase(Locale.ROOT).contains(lower)
                || keywords.stream().anyMatch(k -> k.startsWith(lower));
        }
    }

    public static final List<Command> ALL = List.of(
        new Command(BlockType.PARAGRAPH, BlockType.PARAGRAPH.label(), "", List.of("text", "paragraph", "てきすと")),
        new Command(BlockType.H1, BlockType.H1.label(), "#", List.of("h1", "heading", "みだし")),
        new Command(BlockType.H2, BlockType.H2.label(), "##", List.of("h2", "heading", "みだし")),
        new Command(BlockType.H3, BlockType.H3.label(), "###", List.of("h3", "heading", "みだし")),
        new Command(BlockType.BULLET, BlockType.BULLET.label(), "-", List.of("bullet", "list", "かじょうがき")),
        new Command(BlockType.NUMBERED, BlockType.NUMBERED.label(), "1.", List.of("numbered", "list", "ばんごう")),
        new Command(BlockType.TODO, BlockType.TODO.label(), "[]", List.of("todo", "check", "task", "たすく")),
        new Command(BlockType.QUOTE, BlockType.QUOTE.label(), ">", List.of("quote", "いんよう")),
        new Command(BlockType.CODE, BlockType.CODE.label(), "```", List.of("code", "こーど")),
        new Command(BlockType.DIVIDER, BlockType.DIVIDER.label(), "---", List.of("divider", "hr", "line", "くぎり")));

    private SlashCommands() {}

    public static List<Command> filter(String query) {
        String q = query == null ? "" : query.strip();
        return ALL.stream().filter(c -> c.matches(q)).toList();
    }

    /** ブロックの文字列が「/語句」だけなら語句を返す。そうでなければ null。 */
    public static String queryOf(String text) {
        if (!text.startsWith("/") || text.contains("\n")) return null;
        String q = text.substring(1);
        return Arrays.stream(q.split("")).anyMatch(String::isBlank) && !q.isEmpty() ? null : q;
    }
}
