package app.dearnotes.export

import app.dearnotes.isJavaWhitespace

import app.dearnotes.model.Mark
import app.dearnotes.model.Mark.Style
import kotlin.math.max

/** 1ブロックの文字と装飾を、Markdown の行内の書き方にする。 */
internal object Inline {

    /** 装飾の外で \ を付ける記号。 */
    private const val SPECIAL = "\\`*_~[]<>"

    private val SCHEME = Regex("""[A-Za-z][A-Za-z0-9+.-]{1,31}:[^\s<>]*""")
    private val HEADING = Regex("""#{1,6}(\s.*)?""")
    private val BULLET = Regex("""[-+](\s.*)?""")
    private val NUMBER = Regex("""(\d{1,9})([.)])(\s.*)?""")
    private val RULE = Regex("""(-[ \t]*)+|=+[ \t]*""")
    private val LEADING_BLANKS = Regex("""^[ \t]+""")
    private val ENTITY =
        Regex("&(?:#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});")

    /** 1つの装飾の続く範囲。 */
    private data class Segment(val start: Int, val end: Int, val styles: Set<Style>)

    /** 書き出す文字か、太字・斜体・取り消し線を開くか閉じる印。 */
    private sealed interface Token

    private data class Text(val s: String) : Token

    private data class Delim(val style: Style, val open: Boolean) : Token

    fun write(text: String, marks: List<Mark>): String = write(text, marks, Links.NONE)

    /** links はページへのリンクの行き先。行き先のないページへのリンクは、ふだんの文字にする。 */
    fun write(text: String, marks: List<Mark>, links: Links): String {
        val cleaned = clean(text, marks)
        val tokens = tokens(text, segments(text, cleaned), cleaned, links)
        // 記号で書いて CommonMark が同じ範囲に読めないときは、HTML のタグで書く
        val tags = !markersParse(tokens)
        val out = StringBuilder()
        for (t in tokens) {
            when (t) {
                is Text -> out.append(t.s)
                is Delim -> out.append(if (tags) tag(t.style, t.open) else marker(t.style))
            }
        }
        return out.toString()
    }

    private fun tokens(text: String, segments: List<Segment>, marks: List<Mark>, links: Links): List<Token> {
        val out = ArrayList<Token>()
        val open = ArrayDeque<Style>()
        for (i in segments.indices) {
            val seg = segments[i]
            // 要らない装飾があれば、そこまで閉じる（後に開いたものから）
            while (!seg.styles.containsAll(open)) out.add(Delim(open.removeLast(), false))
            val at = i
            seg.styles
                .filter { s -> !open.contains(s) && !atom(s) }
                .sortedWith(
                    Comparator { a, b ->
                        val byEnd = runEnd(segments, at, b).compareTo(runEnd(segments, at, a))
                        if (byEnd != 0) byEnd else a.compareTo(b)
                    },
                )
                .forEach { s ->
                    out.add(Delim(s, true))
                    open.addLast(s)
                }
            val part = text.substring(seg.start, seg.end)
            if (seg.styles.contains(Style.CODE)) {
                out.add(Text(code(part)))
            } else if (seg.styles.contains(Style.LINK) || seg.styles.contains(Style.PAGE)) {
                val link = if (seg.styles.contains(Style.PAGE)) {
                    pageLink(part, targetOver(marks, seg)?.let { links.href(it) })
                } else {
                    link(part)
                }
                // 「!」のすぐ後の [文字](URL) は画像になる
                val last = if (out.isEmpty()) null else out.last()
                if (link.startsWith("[") && last is Text && last.s.endsWith("!")) {
                    out[out.size - 1] = Text(last.s.substring(0, last.s.length - 1) + "\\!")
                }
                out.add(Text(link))
            } else {
                out.add(Text(escape(part)))
            }
        }
        while (!open.isEmpty()) out.add(Delim(open.removeLast(), false))
        return out
    }

    /**
     * 記号で書いたとき、CommonMark が開きと閉じを意図どおりに組むか。
     * 隣の文字で開けない・閉じられない記号、閉じと開きが1つの並びに混ざる記号、
     * 「3の倍数」の決まりに当たる組があれば false。
     */
    private fun markersParse(tokens: List<Token>): Boolean {
        // 同じ文字の記号が続く並び（* か ~）
        class Run(val c: Char, val length: Int, val opens: Boolean, val closes: Boolean, val both: Boolean)
        // Delim は data class なので、同じ値の記号を別々に持つため、トークンの添字で引く
        val runOf = HashMap<Int, Run>()
        var i = 0
        while (i < tokens.size) {
            val first = tokens[i]
            if (first !is Delim) {
                i++
                continue
            }
            val c = markerChar(first.style)
            var j = i
            var length = 0
            var opens = false
            var closes = false
            while (j < tokens.size) {
                val d = tokens[j]
                if (d !is Delim || markerChar(d.style) != c) break
                length += marker(d.style).length
                opens = opens || d.open
                closes = closes || !d.open
                j++
            }
            if (opens && closes) return false
            val before = charBefore(tokens, i)
            val after = charAfter(tokens, j)
            val left = !space(after) && (!punct(after) || space(before) || punct(before))
            val right = !space(before) && (!punct(before) || space(after) || punct(after))
            if (opens && !left || closes && !right) return false
            val run = Run(c, length, opens, closes, left && right)
            for (k in i until j) runOf[k] = run
            i = j
        }
        // 開きと閉じの組。同じ装飾は入れ子にならないので、次に来る閉じが組になる
        val opened = HashMap<Style, Run>()
        for ((k, t) in tokens.withIndex()) {
            if (t !is Delim) continue
            val run = runOf[k]!!
            if (t.open) {
                opened[t.style] = run
                continue
            }
            val o = opened.remove(t.style)!!
            if (run.c == '*' && (o.both || run.both)
                && (o.length + run.length) % 3 == 0 && !(o.length % 3 == 0 && run.length % 3 == 0)
            ) {
                return false
            }
        }
        return true
    }

    /** 並びの前の文字。なければ -1（行の端は空白と同じ）。 */
    private fun charBefore(tokens: List<Token>, i: Int): Int {
        for (k in i - 1 downTo 0) {
            val t = tokens[k]
            if (t is Text && t.s.isNotEmpty()) return t.s.lastCodePoint()
            if (t is Delim) return markerChar(t.style).code
        }
        return -1
    }

    /** 並びの後の文字。なければ -1。 */
    private fun charAfter(tokens: List<Token>, j: Int): Int {
        for (k in j until tokens.size) {
            val t = tokens[k]
            if (t is Text && t.s.isNotEmpty()) return t.s.firstCodePoint()
            if (t is Delim) return markerChar(t.style).code
        }
        return -1
    }

    /** CommonMark の空白。行の端も空白と見る。 */
    private fun space(c: Int): Boolean {
        return c < 0 || isSpaceSeparator(c)
            || c == '\t'.code || c == '\n'.code || c == '\r'.code || c == '\u000C'.code
    }

    /** CommonMark の句読点（Unicode の P と S）。 */
    private fun punct(c: Int): Boolean = c >= 0 && isPunctuationOrSymbol(c)

    /** 文字の外に出た範囲を切り、太字などの両端の空白を外に出す。 */
    private fun clean(text: String, marks: List<Mark>): List<Mark> {
        val out = ArrayList<Mark>()
        for (m in marks) {
            var start = minOf(m.start, text.length)
            var end = minOf(m.end, text.length)
            if (m.style != Style.CODE) {
                while (start < end && text[start].isJavaWhitespace()) start++
                while (end > start && text[end - 1].isJavaWhitespace()) end--
            }
            if (start < end) out.add(m.withRange(start, end))
        }
        return out
    }

    /** 装飾の境目で文字を切る。コードとリンクは途中で切らず、全体に掛かる装飾だけを残す。 */
    private fun segments(text: String, marks: List<Mark>): List<Segment> {
        val cuts = mutableSetOf(0, text.length)
        for (m in marks) {
            cuts.add(m.start)
            cuts.add(m.end)
        }
        val out = ArrayList<Segment>()
        var prev: Int? = null
        for (cut in cuts.sorted()) {
            val from = prev
            if (from != null && from < cut) out.add(Segment(from, cut, stylesOver(marks, from, cut)))
            prev = cut
        }
        val merged = ArrayList<Segment>()
        for (s in out) {
            val last = if (merged.isEmpty()) null else merged.last()
            val atomic = s.styles.any { style -> atom(style) }
            if (last != null && atomic && sameAtom(last, s, marks)) {
                val both = mutableSetOf<Style>()
                both.addAll(last.styles)
                both.retainAll(s.styles)
                merged[merged.size - 1] = Segment(last.start, s.end, both)
            } else {
                merged.add(s)
            }
        }
        return merged
    }

    /** コードとリンクは途中で切らない。 */
    private fun atom(s: Style): Boolean {
        return s == Style.CODE || s == Style.LINK || s == Style.PAGE
    }

    /** 切れ目に掛かるページへのリンクの、リンク先のページの id。 */
    private fun targetOver(marks: List<Mark>, seg: Segment): String? {
        for (m in marks) {
            if (m.style == Style.PAGE && m.start <= seg.start && seg.end <= m.end) return m.target
        }
        return null
    }

    /**
     * 2つの切れ目が、同じコードかリンクの装飾の中にあるか。
     * リンクは、行き先のページが変わるところで切る（URL のリンクが別々のページへのリンクを覆っていても繋げない）。
     */
    private fun sameAtom(a: Segment, b: Segment, marks: List<Mark>): Boolean {
        val sameTarget = targetOver(marks, a) == targetOver(marks, b)
        for (m in marks) {
            if (!atom(m.style)) continue
            if (m.start <= a.start && b.end <= m.end && (m.style == Style.CODE || sameTarget)) return true
        }
        return false
    }

    private fun stylesOver(marks: List<Mark>, start: Int, end: Int): Set<Style> {
        val out = mutableSetOf<Style>()
        for (m in marks) {
            if (m.start <= start && end <= m.end) out.add(m.style)
        }
        return out
    }

    /** from の切れ目から、style が続く最後の位置。 */
    private fun runEnd(segments: List<Segment>, from: Int, style: Style): Int {
        var end = segments[from].end
        var i = from
        while (i < segments.size && segments[i].styles.contains(style)) {
            end = segments[i].end
            i++
        }
        return end
    }

    private fun marker(s: Style): String {
        return when (s) {
            Style.BOLD -> "**"
            Style.ITALIC -> "*"
            Style.STRIKE -> "~~"
            Style.CODE, Style.LINK, Style.PAGE -> ""
        }
    }

    /** 記号の代わりの HTML のタグ。 */
    private fun tag(s: Style, open: Boolean): String {
        val name = when (s) {
            Style.BOLD -> "strong"
            Style.ITALIC -> "em"
            Style.STRIKE -> "del"
            Style.CODE, Style.LINK, Style.PAGE -> throw IllegalArgumentException(s.name)
        }
        return if (open) "<$name>" else "</$name>"
    }

    private fun markerChar(s: Style): Char = if (s == Style.STRIKE) '~' else '*'

    /** 中の ` より長い ` で囲む。端が ` か空白なら、空白を1つずつ足す。 */
    private fun code(s: String): String {
        var longest = 0
        var run = 0
        for (c in s) {
            run = if (c == '`') run + 1 else 0
            longest = max(longest, run)
        }
        val fence = "`".repeat(longest + 1)
        val pad = s.startsWith("`") || s.endsWith("`") || s.startsWith(" ") || s.endsWith(" ")
        return if (pad) fence + " " + s + " " + fence else fence + s + fence
    }

    /** スキームがあれば <url>。なければ [文字](文字)。 */
    private fun link(s: String): String {
        if (SCHEME.matches(s)) return "<$s>"
        val target = s.replace("(", "%28").replace(")", "%29").replace(" ", "%20")
        return "[" + escape(s) + "](" + target + ")"
    }

    /** ページへのリンク。行き先があれば [文字](パス)、なければふだんの文字。 */
    private fun pageLink(s: String, href: String?): String {
        if (href == null) return escape(s)
        // ファイル名に使える文字のうち、リンク先の書き方を壊すもの（& は文字参照になる）を % で書く
        val target = href.replace("%", "%25").replace(" ", "%20")
            .replace("(", "%28").replace(")", "%29").replace("#", "%23").replace("&", "%26")
        return "[" + escape(s) + "](" + target + ")"
    }

    /** 装飾の外の文字。記号の前に \ を付ける。& は文字参照（&amp; など）に読まれるときだけ。 */
    fun escape(s: String): String {
        val sb = StringBuilder(s.length)
        for (i in s.indices) {
            val c = s[i]
            if (SPECIAL.indexOf(c) >= 0 || c == '&' && ENTITY.matchesAt(s, i)) sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }

    /**
     * 行頭で種類が変わる書き方（# - + 1. --- ===）を守る。* と > は escape() が守る。
     * 行頭の半角の空白とタブは消す（4つあるとコードになる。表示では元から消える）。全角の空白は残す。
     */
    fun escapeLineStart(line: String): String {
        val body = LEADING_BLANKS.replaceFirst(line, "")
        if (HEADING.matches(body) || BULLET.matches(body) || RULE.matches(body)) {
            return "\\" + body
        }
        val num = NUMBER.matchEntire(body)
        if (num != null) {
            val digits = num.groupValues[1]
            return digits + "\\" + body.substring(digits.length)
        }
        return body
    }
}
