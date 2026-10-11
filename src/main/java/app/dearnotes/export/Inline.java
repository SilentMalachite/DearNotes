package app.dearnotes.export;

import app.dearnotes.model.Mark;
import app.dearnotes.model.Mark.Style;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
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

    private Inline() {}

    /** 1つの装飾の続く範囲。 */
    private record Segment(int start, int end, Set<Style> styles) {}

    static String write(String text, List<Mark> marks) {
        List<Mark> clean = clean(text, marks);
        List<Segment> segments = segments(text, clean);
        var out = new StringBuilder();
        Deque<Style> open = new ArrayDeque<>();
        for (int i = 0; i < segments.size(); i++) {
            Segment seg = segments.get(i);
            // 要らない装飾があれば、そこまで閉じる（後に開いたものから）
            while (!seg.styles().containsAll(open)) out.append(close(open.pop()));
            int at = i;
            seg.styles().stream()
                .filter(s -> !open.contains(s) && s != Style.CODE && s != Style.LINK)
                .sorted(Comparator.comparingInt((Style s) -> runEnd(segments, at, s)).reversed()
                    .thenComparing(Comparator.naturalOrder()))
                .forEach(s -> {
                    out.append(marker(s));
                    open.push(s);
                });
            String part = text.substring(seg.start(), seg.end());
            if (seg.styles().contains(Style.CODE)) {
                out.append(code(part));
            } else if (seg.styles().contains(Style.LINK)) {
                out.append(link(part));
            } else {
                out.append(escape(part));
            }
        }
        while (!open.isEmpty()) out.append(close(open.pop()));
        return out.toString();
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

    private static String close(Style s) {
        return marker(s);
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

    /** 装飾の外の文字。記号の前に \ を付ける。 */
    static String escape(String s) {
        var sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (SPECIAL.indexOf(c) >= 0) sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    /** 行頭で種類が変わる書き方（# - + 1. --- ===）を守る。* と > は escape() が守る。 */
    static String escapeLineStart(String line) {
        String body = line.stripLeading();
        String lead = line.substring(0, line.length() - body.length());
        if (HEADING.matcher(body).matches() || BULLET.matcher(body).matches() || RULE.matcher(body).matches()) {
            return lead + "\\" + body;
        }
        var num = NUMBER.matcher(body);
        if (num.matches()) return lead + num.group(1) + "\\" + body.substring(num.end(1));
        return line;
    }
}
