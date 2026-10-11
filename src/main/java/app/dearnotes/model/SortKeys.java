package app.dearnotes.model;

import java.util.ArrayList;
import java.util.List;

/** 並べ替え用の実数キー。間に入れるだけなら他の行を書き換えずに済む。 */
public final class SortKeys {

    public static final double GAP = 1024;

    private SortKeys() {}

    /** prev と next の間に入れる。間が狭すぎたら null。 */
    public static Double between(Double prev, Double next) {
        double lo = prev == null ? 0 : prev;
        double hi = next == null ? lo + GAP : next;
        double mid = (lo + hi) / 2;
        return (mid <= lo || mid >= hi) ? null : mid;
    }

    /** null のとき: 全体を GAP 間隔で振り直すためのキー。 */
    public static List<Double> spaced(int count) {
        var keys = new ArrayList<Double>(count);
        for (int i = 1; i <= count; i++) keys.add(i * GAP);
        return keys;
    }
}
