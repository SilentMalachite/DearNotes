package app.dearnotes.export;

import app.dearnotes.editor.BlockOps;
import app.dearnotes.model.Block;
import app.dearnotes.model.BlockType;
import app.dearnotes.model.Page;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * ページを Markdown（CommonMark と GFM のタスクリスト）の文字列にする。
 * 先頭にページ名を「# 」で書く。畳んだ行も書く。改行は LF、末尾に改行を1つ。
 */
public final class MarkdownWriter {

    /** 見出しの末尾の # は、閉じの記号と読まれる。 */
    private static final Pattern CLOSING_HASHES = Pattern.compile("(.*\\s)(#+\\s*)");

    private MarkdownWriter() {}

    public static String write(Page page, List<Block> blocks) {
        var out = new StringBuilder();
        headingBody(out, "# ", Inline.escape(page.displayTitle().replace('\n', ' ')).strip());
        // 段ごとに、その段の最後の行の子を置く桁。-1 はまだない
        int[] childColumn = new int[Block.MAX_INDENT + 1];
        Arrays.fill(childColumn, -1);
        Block prev = null;
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            if (b.type() == BlockType.PARAGRAPH && b.text().isBlank()) {
                prev = b;
                continue;
            }
            int depth = b.indent();
            int base = 0;
            for (int k = depth - 1; k >= 0; k--) {
                if (childColumn[k] >= 0) {
                    base = childColumn[k];
                    break;
                }
            }
            // リストの項目は続けて書き、ほかは空行で分ける（段落が前の行とつながらないように）
            boolean tight = prev != null && prev.type().isList() && b.type().isList();
            if (!tight) out.append('\n');
            String pad = " ".repeat(base);
            if (b.type() == BlockType.NUMBERED && continuesNumberedList(blocks, i)) {
                // 画面では空の行の後で 1 から数え直す。CommonMark は空行だけでは同じリストと読むので、間に区切りを入れる
                line(out, pad + "<!-- -->");
                out.append('\n');
            }
            // 子の行を置く桁は、リストの項目なら記号の後ろ
            int width = 0;
            switch (b.type()) {
                case PARAGRAPH -> text(out, pad, "", "", b);
                case H1 -> heading(out, pad, "# ", b);
                case H2 -> heading(out, pad, "## ", b);
                case H3 -> heading(out, pad, "### ", b);
                case BULLET -> {
                    text(out, pad, "- ", "  ", b);
                    width = 2;
                }
                case NUMBERED -> {
                    String marker = BlockOps.listNumber(blocks, i) + ". ";
                    width = marker.length();
                    text(out, pad, marker, " ".repeat(width), b);
                }
                case TODO -> {
                    // 「[ ] 」は項目の文字の側なので、子は「- 」の後ろにそろえる
                    text(out, pad, b.checked() ? "- [x] " : "- [ ] ", "  ", b);
                    width = 2;
                }
                case QUOTE -> text(out, pad, "> ", "> ", b);
                case CODE -> fence(out, pad, b.text());
                case DIVIDER -> line(out, pad + "---");
            }
            childColumn[depth] = base + width;
            for (int k = depth + 1; k < childColumn.length; k++) childColumn[k] = -1;
            prev = b;
        }
        return out.toString();
    }

    /** 文字のあるブロック。2行目からは continuation を前に付け、行末の \ で改行する。 */
    private static void text(StringBuilder out, String pad, String marker, String continuation, Block b) {
        String[] lines = trimNewlines(Inline.write(b.text(), b.marks())).split("\n", -1);
        for (int j = 0; j < lines.length; j++) {
            String lead = pad + (j == 0 ? marker : continuation);
            if (j == lines.length - 1) {
                content(out, lead, lines[j]);
            } else {
                line(out, lead + Inline.escapeLineStart(lines[j]) + "\\");
            }
        }
    }

    /**
     * 番号を 1 から数え直す項目なのに、Markdown では前の番号付きリストの続きと読まれるか。
     * 間に空の段落しかなく、同じ段の番号付きの項目が前にあるとき。
     */
    private static boolean continuesNumberedList(List<Block> blocks, int i) {
        if (BlockOps.listNumber(blocks, i) != 1) return false;
        int depth = blocks.get(i).indent();
        for (int j = i - 1; j >= 0; j--) {
            Block b = blocks.get(j);
            if (b.type() == BlockType.PARAGRAPH && b.text().isBlank()) continue;
            if (b.indent() > depth) continue;               // 前の項目の子の行
            return b.indent() == depth && b.type() == BlockType.NUMBERED;
        }
        return false;
    }

    private static void heading(StringBuilder out, String pad, String marker, Block b) {
        headingBody(out, pad + marker, trimNewlines(Inline.write(b.text(), b.marks())).replace('\n', ' '));
    }

    /** 見出しの1行。行頭と末尾の # が、見出しの記号と読まれないようにする。 */
    private static void headingBody(StringBuilder out, String lead, String body) {
        var closing = CLOSING_HASHES.matcher(body);
        if (closing.matches()) body = closing.group(1) + "\\" + closing.group(2);
        content(out, lead, body);
    }

    /** 記号と本文の1行。本文が空なら、記号の後ろの空白を消す。 */
    private static void content(StringBuilder out, String lead, String body) {
        line(out, body.isEmpty() ? lead.stripTrailing() : lead + Inline.escapeLineStart(body));
    }

    /** 中の ``` より長い囲みで書く。中身は守らない。 */
    private static void fence(StringBuilder out, String pad, String code) {
        int longest = 0;
        int run = 0;
        for (char c : code.toCharArray()) {
            run = c == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        String f = "`".repeat(Math.max(3, longest + 1));
        line(out, pad + f);
        if (!code.isEmpty()) {
            for (String l : code.split("\n", -1)) line(out, l.isEmpty() ? "" : pad + l);
        }
        line(out, pad + f);
    }

    private static void line(StringBuilder out, String s) {
        out.append(s).append('\n');
    }

    private static String trimNewlines(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '\n') start++;
        while (end > start && s.charAt(end - 1) == '\n') end--;
        return s.substring(start, end);
    }
}
