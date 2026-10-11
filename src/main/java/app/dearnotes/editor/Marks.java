package app.dearnotes.editor;

import app.dearnotes.model.Mark;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** ブロックを分けたり繋げたりするときの装飾の付け替え。 */
public final class Marks {

    private Marks() {}

    /** [from, to) にかかる装飾を切り出し、from を 0 に揃える。 */
    public static List<Mark> slice(List<Mark> marks, int from, int to) {
        var out = new ArrayList<Mark>();
        for (Mark m : marks) {
            int s = Math.max(m.start(), from);
            int e = Math.min(m.end(), to);
            if (s < e) out.add(m.withRange(s - from, e - from));
        }
        return normalize(out);
    }

    /**
     * index の文字にかかるリンク。隣り合うリンクを取り違えないよう、end の文字は含めない。
     * ページへのリンクと URL のリンクが重なっていれば、書き出しと同じくページへのリンクを選ぶ。
     */
    public static Optional<Mark> linkAt(List<Mark> marks, int index) {
        return marks.stream()
            .filter(m -> m.style() == Mark.Style.PAGE || m.style() == Mark.Style.LINK)
            .filter(m -> m.start() <= index && index < m.end())
            .min(Comparator.comparing(m -> m.style() == Mark.Style.PAGE ? 0 : 1));
    }

    /** a の後ろに長さ aLength の文字列が続くとして、b をずらして繋げる。 */
    public static List<Mark> concat(List<Mark> a, int aLength, List<Mark> b) {
        var out = new ArrayList<Mark>(a);
        for (Mark m : b) out.add(m.withRange(m.start() + aLength, m.end() + aLength));
        return normalize(out);
    }

    /** 空の装飾を捨て、同じ種類（リンクは同じリンク先）で重なる・接するものを1つにまとめる。 */
    public static List<Mark> normalize(List<Mark> marks) {
        var sorted = marks.stream()
            .filter(m -> m.start() < m.end())
            .sorted(Comparator.comparing(Mark::style).thenComparing(TARGET).thenComparingInt(Mark::start))
            .toList();
        var out = new ArrayList<Mark>();
        for (Mark m : sorted) {
            if (!out.isEmpty()) {
                Mark last = out.getLast();
                if (last.style() == m.style() && Objects.equals(last.target(), m.target()) && m.start() <= last.end()) {
                    out.set(out.size() - 1, last.withRange(last.start(), Math.max(last.end(), m.end())));
                    continue;
                }
            }
            out.add(m);
        }
        out.sort(Comparator.comparingInt(Mark::start).thenComparing(Mark::style).thenComparing(TARGET));
        return List.copyOf(out);
    }

    private static final Comparator<Mark> TARGET =
        Comparator.comparing(Mark::target, Comparator.nullsFirst(Comparator.naturalOrder()));
}
