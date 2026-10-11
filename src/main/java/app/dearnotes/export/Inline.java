package app.dearnotes.export;

import app.dearnotes.model.Mark;
import app.dearnotes.model.Mark.Style;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** 1ブロックの文字と装飾を、Markdown の行内の書き方にする。 */
final class Inline {

    /** 装飾の外で \ を付ける記号。 */
    private static final String SPECIAL = "\\`*_~[]<>";

    private static final Pattern SCHEME = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]{1,31}:[^\\s<>]*");
    private static final Pattern HEADING = Pattern.compile("#{1,6}(\\s.*)?");
    private static final Pattern BULLET = Pattern.compile("[-+](\\s.*)?");
    private static final Pattern NUMBER = Pattern.compile("(\\d{1,9})([.)])(\\s.*)?");
    private static final Pattern RULE = Pattern.compile("(-[ \\t]*)+|=+[ \\t]*");
    private static final Pattern LEADING_BLANKS = Pattern.compile("^[ \\t]+");
    private static final Pattern ENTITY =
        Pattern.compile("&(?:#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});");

    private Inline() {}

    /** 1つの装飾の続く範囲。 */
    private record Segment(int start, int end, Set<Style> styles) {}

    /** 書き出す文字か、太字・斜体・取り消し線を開くか閉じる印。 */
    private sealed interface Token permits Text, Delim {}

    private record Text(String s) implements Token {}

    private record Delim(Style style, boolean open) implements Token {}

    static String write(String text, List<Mark> marks) {
        List<Token> tokens = tokens(text, segments(text, clean(text, marks)));
        // 記号で書いて CommonMark が同じ範囲に読めないときは、HTML のタグで書く
        boolean tags = !markersParse(tokens);
        var out = new StringBuilder();
        for (Token t : tokens) {
            switch (t) {
                case Text(String s) -> out.append(s);
                case Delim(Style style, boolean open) -> out.append(tags ? tag(style, open) : marker(style));
            }
        }
        return out.toString();
    }

    private static List<Token> tokens(String text, List<Segment> segments) {
        var out = new ArrayList<Token>();
        Deque<Style> open = new ArrayDeque<>();
        for (int i = 0; i < segments.size(); i++) {
            Segment seg = segments.get(i);
            // 要らない装飾があれば、そこまで閉じる（後に開いたものから）
            while (!seg.styles().containsAll(open)) out.add(new Delim(open.pop(), false));
            int at = i;
            seg.styles().stream()
                .filter(s -> !open.contains(s) && s != Style.CODE && s != Style.LINK)
                .sorted(Comparator.comparingInt((Style s) -> runEnd(segments, at, s)).reversed()
                    .thenComparing(Comparator.naturalOrder()))
                .forEach(s -> {
                    out.add(new Delim(s, true));
                    open.push(s);
                });
            String part = text.substring(seg.start(), seg.end());
            if (seg.styles().contains(Style.CODE)) {
                out.add(new Text(code(part)));
            } else if (seg.styles().contains(Style.LINK)) {
                String link = link(part);
                // 「!」のすぐ後の [文字](URL) は画像になる
                if (link.startsWith("[") && !out.isEmpty() && out.getLast() instanceof Text(String prev)
                        && prev.endsWith("!")) {
                    out.set(out.size() - 1, new Text(prev.substring(0, prev.length() - 1) + "\\!"));
                }
                out.add(new Text(link));
            } else {
                out.add(new Text(escape(part)));
            }
        }
        while (!open.isEmpty()) out.add(new Delim(open.pop(), false));
        return out;
    }

    /**
     * 記号で書いたとき、CommonMark が開きと閉じを意図どおりに組むか。
     * 隣の文字で開けない・閉じられない記号、閉じと開きが1つの並びに混ざる記号、
     * 「3の倍数」の決まりに当たる組があれば false。
     */
    private static boolean markersParse(List<Token> tokens) {
        // 同じ文字の記号が続く並び（* か ~）
        record Run(char c, int length, boolean opens, boolean closes, boolean both) {}
        var runOf = new IdentityHashMap<Delim, Run>();
        int i = 0;
        while (i < tokens.size()) {
            if (!(tokens.get(i) instanceof Delim first)) {
                i++;
                continue;
            }
            char c = markerChar(first.style());
            int j = i;
            int length = 0;
            boolean opens = false;
            boolean closes = false;
            while (j < tokens.size() && tokens.get(j) instanceof Delim d && markerChar(d.style()) == c) {
                length += marker(d.style()).length();
                opens |= d.open();
                closes |= !d.open();
                j++;
            }
            if (opens && closes) return false;
            int before = charBefore(tokens, i);
            int after = charAfter(tokens, j);
            boolean left = !space(after) && (!punct(after) || space(before) || punct(before));
            boolean right = !space(before) && (!punct(before) || space(after) || punct(after));
            if (opens && !left || closes && !right) return false;
            var run = new Run(c, length, opens, closes, left && right);
            for (int k = i; k < j; k++) runOf.put((Delim) tokens.get(k), run);
            i = j;
        }
        // 開きと閉じの組。同じ装飾は入れ子にならないので、次に来る閉じが組になる
        Map<Style, Run> opened = new EnumMap<>(Style.class);
        for (Token t : tokens) {
            if (!(t instanceof Delim d)) continue;
            Run run = runOf.get(d);
            if (d.open()) {
                opened.put(d.style(), run);
                continue;
            }
            Run o = opened.remove(d.style());
            if (run.c() == '*' && (o.both() || run.both())
                    && (o.length() + run.length()) % 3 == 0 && !(o.length() % 3 == 0 && run.length() % 3 == 0)) {
                return false;
            }
        }
        return true;
    }

    /** 並びの前の文字。なければ -1（行の端は空白と同じ）。 */
    private static int charBefore(List<Token> tokens, int i) {
        for (int k = i - 1; k >= 0; k--) {
            Token t = tokens.get(k);
            if (t instanceof Text(String s) && !s.isEmpty()) return s.codePointBefore(s.length());
            if (t instanceof Delim d) return markerChar(d.style());
        }
        return -1;
    }

    /** 並びの後の文字。なければ -1。 */
    private static int charAfter(List<Token> tokens, int j) {
        for (int k = j; k < tokens.size(); k++) {
            Token t = tokens.get(k);
            if (t instanceof Text(String s) && !s.isEmpty()) return s.codePointAt(0);
            if (t instanceof Delim d) return markerChar(d.style());
        }
        return -1;
    }

    /** CommonMark の空白。行の端も空白と見る。 */
    private static boolean space(int c) {
        return c < 0 || Character.getType(c) == Character.SPACE_SEPARATOR
            || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }

    /** CommonMark の句読点（Unicode の P と S）。 */
    private static boolean punct(int c) {
        if (c < 0) return false;
        return switch (Character.getType(c)) {
            case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                 Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION,
                 Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
                 Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL,
                 Character.OTHER_SYMBOL -> true;
            default -> false;
        };
    }

    /** 文字の外に出た範囲を切り、太字などの両端の空白を外に出す。 */
    private static List<Mark> clean(String text, List<Mark> marks) {
        var out = new ArrayList<Mark>();
        for (Mark m : marks) {
            int start = Math.min(m.start(), text.length());
            int end = Math.min(m.end(), text.length());
            if (m.style() != Style.CODE) {
                while (start < end && Character.isWhitespace(text.charAt(start))) start++;
                while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
            }
            if (start < end) out.add(new Mark(start, end, m.style()));
        }
        return out;
    }

    /** 装飾の境目で文字を切る。コードとリンクは途中で切らず、全体に掛かる装飾だけを残す。 */
    private static List<Segment> segments(String text, List<Mark> marks) {
        var cuts = new TreeSet<Integer>(List.of(0, text.length()));
        for (Mark m : marks) {
            cuts.add(m.start());
            cuts.add(m.end());
        }
        var out = new ArrayList<Segment>();
        Integer prev = null;
        for (int cut : cuts) {
            if (prev != null && prev < cut) out.add(new Segment(prev, cut, stylesOver(marks, prev, cut)));
            prev = cut;
        }
        var merged = new ArrayList<Segment>();
        for (Segment s : out) {
            Segment last = merged.isEmpty() ? null : merged.getLast();
            boolean atom = s.styles().contains(Style.CODE) || s.styles().contains(Style.LINK);
            if (last != null && atom && sameAtom(last, s, marks)) {
                Set<Style> both = EnumSet.noneOf(Style.class);
                both.addAll(last.styles());
                both.retainAll(s.styles());
                merged.set(merged.size() - 1, new Segment(last.start(), s.end(), both));
            } else {
                merged.add(s);
            }
        }
        return merged;
    }

    /** 2つの切れ目が、同じコードかリンクの装飾の中にあるか。 */
    private static boolean sameAtom(Segment a, Segment b, List<Mark> marks) {
        for (Mark m : marks) {
            if (m.style() != Style.CODE && m.style() != Style.LINK) continue;
            if (m.start() <= a.start() && b.end() <= m.end()) return true;
        }
        return false;
    }

    private static Set<Style> stylesOver(List<Mark> marks, int start, int end) {
        Set<Style> out = EnumSet.noneOf(Style.class);
        for (Mark m : marks) {
            if (m.start() <= start && end <= m.end()) out.add(m.style());
        }
        return out;
    }

    /** from の切れ目から、style が続く最後の位置。 */
    private static int runEnd(List<Segment> segments, int from, Style style) {
        int end = segments.get(from).end();
        for (int i = from; i < segments.size() && segments.get(i).styles().contains(style); i++) {
            end = segments.get(i).end();
        }
        return end;
    }

    private static String marker(Style s) {
        return switch (s) {
            case BOLD -> "**";
            case ITALIC -> "*";
            case STRIKE -> "~~";
            case CODE, LINK -> "";
        };
    }

    /** 記号の代わりの HTML のタグ。 */
    private static String tag(Style s, boolean open) {
        String name = switch (s) {
            case BOLD -> "strong";
            case ITALIC -> "em";
            case STRIKE -> "del";
            case CODE, LINK -> throw new IllegalArgumentException(s.name());
        };
        return open ? "<" + name + ">" : "</" + name + ">";
    }

    private static char markerChar(Style s) {
        return s == Style.STRIKE ? '~' : '*';
    }

    /** 中の ` より長い ` で囲む。端が ` か空白なら、空白を1つずつ足す。 */
    private static String code(String s) {
        int longest = 0;
        int run = 0;
        for (char c : s.toCharArray()) {
            run = c == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        String fence = "`".repeat(longest + 1);
        boolean pad = s.startsWith("`") || s.endsWith("`") || s.startsWith(" ") || s.endsWith(" ");
        return pad ? fence + " " + s + " " + fence : fence + s + fence;
    }

    /** スキームがあれば <url>。なければ [文字](文字)。 */
    private static String link(String s) {
        if (SCHEME.matcher(s).matches()) return "<" + s + ">";
        String target = s.replace("(", "%28").replace(")", "%29").replace(" ", "%20");
        return "[" + escape(s) + "](" + target + ")";
    }

    /** 装飾の外の文字。記号の前に \ を付ける。& は文字参照（&amp; など）に読まれるときだけ。 */
    static String escape(String s) {
        var sb = new StringBuilder(s.length());
        var entity = ENTITY.matcher(s);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (SPECIAL.indexOf(c) >= 0 || c == '&' && entity.region(i, s.length()).lookingAt()) sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 行頭で種類が変わる書き方（# - + 1. --- ===）を守る。* と > は escape() が守る。
     * 行頭の半角の空白とタブは消す（4つあるとコードになる。表示では元から消える）。全角の空白は残す。
     */
    static String escapeLineStart(String line) {
        String body = LEADING_BLANKS.matcher(line).replaceFirst("");
        if (HEADING.matcher(body).matches() || BULLET.matcher(body).matches() || RULE.matcher(body).matches()) {
            return "\\" + body;
        }
        var num = NUMBER.matcher(body);
        if (num.matches()) return num.group(1) + "\\" + body.substring(num.end(1));
        return body;
    }
}
