package app.dearnotes.model

/** 並べ替え用の実数キー。間に入れるだけなら他の行を書き換えずに済む。 */
object SortKeys {
    const val GAP = 1024.0

    /** prev と next の間に入れる。間が狭すぎたら null。 */
    fun between(prev: Double?, next: Double?): Double? {
        val lo = prev ?: 0.0
        val hi = next ?: (lo + GAP)
        val mid = (lo + hi) / 2
        return if (mid <= lo || mid >= hi) null else mid
    }

    /** null のとき: 全体を GAP 間隔で振り直すためのキー。 */
    fun spaced(count: Int): List<Double> {
        val keys = ArrayList<Double>(count)
        for (i in 1..count) keys.add(i * GAP)
        return keys
    }
}
