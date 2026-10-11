package app.dearnotes.editor;

import app.dearnotes.model.Mark;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** ブロックを分けたり繋げたりするときの装飾の付け替え。 */
public final class Marks {

    private Marks() {}

    /** [from, to) にかかる装飾を切り出し、from を 0 に揃える。 */
    public static List<Mark> slice(List<Mark> marks, int from, int to) {
        var out = new ArrayList<Mark>();
        for (Mark m : marks) {
            int s = Math.max(m.start(), from);
            int e = Math.min(m.end(), to);
            if (s < e) out.add(new Mark(s - from, e - from, m.style()));
        }
        return normalize(out);
    }

    /** a の後ろに長さ aLength の文字列が続くとして、b をずらして繋げる。 */
    public static List<Mark> concat(List<Mark> a, int aLength, List<Mark> b) {
        var out = new ArrayList<Mark>(a);
        for (Mark m : b) out.add(new Mark(m.start() + aLength, m.end() + aLength, m.style()));
        return normalize(out);
    }

    /** 空の装飾を捨て、同じ種類で重なる・接するものを1つにまとめる。 */
    public static List<Mark> normalize(List<Mark> marks) {
        var sorted = marks.stream()
            .filter(m -> m.start() < m.end())
            .sorted(Comparator.comparing(Mark::style).thenComparingInt(Mark::start))
            .toList();
        var out = new ArrayList<Mark>();
        for (Mark m : sorted) {
            if (!out.isEmpty()) {
                Mark last = out.getLast();
                if (last.style() == m.style() && m.start() <= last.end()) {
                    out.set(out.size() - 1, new Mark(last.start(), Math.max(last.end(), m.end()), m.style()));
                    continue;
                }
            }
            out.add(m);
        }
        out.sort(Comparator.comparingInt(Mark::start).thenComparing(Mark::style));
        return List.copyOf(out);
    }
}
