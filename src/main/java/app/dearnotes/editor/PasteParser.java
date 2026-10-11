package app.dearnotes.editor;

import app.dearnotes.model.Block;
import app.dearnotes.model.BlockType;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 貼り付けた複数行の文字列を、Markdown の書き方を見てブロックに分ける。 */
public final class PasteParser {

    public record Line(BlockType type, String text, int indent, boolean checked) {}

    private static final Pattern TASK = Pattern.compile("^(?:[-*] )?\\[( |x|X)?\\] (.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^\\d+[.)] (.*)$");

    private PasteParser() {}

    public static List<Line> parse(String text) {
        String[] raw = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        var out = new ArrayList<Line>();
        for (int i = 0; i < raw.length; i++) {
            String line = raw[i];
            String body = line.stripLeading();
            int indent = indentOf(line);
            if (body.startsWith("```")) {
                var code = new ArrayList<String>();
                int j = i + 1;
                while (j < raw.length && !raw[j].stripLeading().startsWith("```")) code.add(raw[j++]);
                out.add(new Line(BlockType.CODE, String.join("\n", code), indent, false));
                i = j;   // 閉じの ``` を飛ばす（なければ末尾まで）
                continue;
            }
            if (body.isBlank()) continue;
            out.add(classify(body, indent));
        }
        return out;
    }

    static Line classify(String body, int indent) {
        if (body.equals("---") || body.equals("***") || body.equals("___")) {
            return new Line(BlockType.DIVIDER, "", indent, false);
        }
        Matcher task = TASK.matcher(body);
        if (task.matches()) {
            boolean checked = task.group(1) != null && task.group(1).equalsIgnoreCase("x");
            return new Line(BlockType.TODO, task.group(2), indent, checked);
        }
        Matcher num = NUMBERED.matcher(body);
        if (num.matches()) return new Line(BlockType.NUMBERED, num.group(1), indent, false);
        for (String prefix : List.of("### ", "## ", "# ", "- ", "* ", "> ")) {
            if (body.startsWith(prefix)) {
                BlockType t = MarkdownShortcuts.match(prefix).orElseThrow();
                return new Line(t, body.substring(prefix.length()), indent, false);
            }
        }
        return new Line(BlockType.PARAGRAPH, body, indent, false);
    }

    /** タブ1つか空白2つで1段。 */
    private static int indentOf(String line) {
        int spaces = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') spaces++;
            else if (c == '\t') spaces += 2;
            else break;
        }
        return Math.min(spaces / 2, Block.MAX_INDENT);
    }
}
