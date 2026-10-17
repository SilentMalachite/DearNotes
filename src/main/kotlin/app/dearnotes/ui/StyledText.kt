package app.dearnotes.ui

import app.dearnotes.editor.Marks
import app.dearnotes.model.Mark
import java.util.ArrayList
import java.util.TreeSet
import org.fxmisc.richtext.StyleClassedTextArea
import org.fxmisc.richtext.model.StyleSpan
import org.fxmisc.richtext.model.StyleSpans
import org.fxmisc.richtext.model.StyleSpansBuilder

/**
 * Mark の並びと RichTextFX の StyleSpans を行き来する。CSS クラスは「m-太字の名前」。
 * ページへのリンクは「m-page」と「to-ページの id」の2つで持つ。
 */
internal object StyledText {

    private const val PREFIX = "m-"
    private const val TARGET = "to-"

    fun css(style: Mark.Style): String = PREFIX + style.key()

    /** 文字列と装飾をエリアに入れる。段落（改行）ごとに装飾を当てる。 */
    fun load(area: StyleClassedTextArea, text: String, marks: List<Mark>) {
        area.replaceText(text)
        // replaceText は前の先頭文字の装飾を引き継ぐので、いったん全部外す
        if (area.length > 0) area.setStyle(0, area.length, emptyList())
        var offset = 0
        for (p in 0 until area.paragraphs.size) {
            val len = area.getParagraph(p).length()
            val local = Marks.slice(marks, offset, offset + len)
            if (local.isNotEmpty()) area.setStyleSpans(p, 0, spans(len, local))
            offset += len + 1
        }
    }

    /** エリアの装飾を Mark に戻す。 */
    fun read(area: StyleClassedTextArea): List<Mark> {
        val out = ArrayList<Mark>()
        var offset = 0
        for (p in 0 until area.paragraphs.size) {
            var pos = offset
            for (span in area.getStyleSpans(p)) {
                val target = targetOf(span.style)
                for (item in span.style) {
                    val cls = item ?: continue
                    if (!cls.startsWith(PREFIX) || span.length == 0) continue
                    try {
                        val style = Mark.Style.fromKey(cls.substring(PREFIX.length))
                        out.add(Mark(pos, pos + span.length, style, if (style == Mark.Style.PAGE) target else null))
                    } catch (ignored: IllegalArgumentException) {
                        // 知らないクラス
                    }
                }
                pos += span.length
            }
            offset += area.getParagraph(p).length() + 1
        }
        return Marks.normalize(out)
    }

    fun spans(length: Int, marks: List<Mark>): StyleSpans<MutableCollection<String>> {
        val cuts = TreeSet<Int>()
        cuts.add(0)
        cuts.add(length)
        for (m in marks) {
            cuts.add(m.start.coerceIn(0, length))
            cuts.add(m.end.coerceIn(0, length))
        }
        val builder = StyleSpansBuilder<MutableCollection<String>>()
        var prev: Int? = null
        for (cut in cuts) {
            val start = prev
            if (start != null && cut > start) {
                val classes = ArrayList<String>()
                for (m in marks) {
                    if (m.start <= start && m.end >= cut) {
                        classes.add(css(m.style))
                        val target = m.target
                        if (target != null) classes.add(TARGET + target)
                    }
                }
                builder.add(classes, cut - start)
            }
            prev = cut
        }
        if (length == 0) builder.add(ArrayList(), 0)
        return builder.create()
    }

    /** 選んだ範囲の装飾を切り替える。全部に付いていれば外し、そうでなければ付ける。 */
    fun toggle(area: StyleClassedTextArea, style: Mark.Style) {
        val sel = area.selection
        if (sel.length == 0) return
        val cls = css(style)
        val current = area.getStyleSpans(sel.start, sel.end)
        val all = current.all { span -> span.length == 0 || span.style.contains(cls) }
        val updated = current.mapStyles { classes ->
            val next = ArrayList(classes)
            next.remove(cls)
            if (!all) next.add(cls)
            next
        }
        area.setStyleSpans(sel.start, updated)
    }

    /** target のページへのリンクにするクラス。 */
    fun pageClasses(target: String): List<String> = listOf(css(Mark.Style.PAGE), TARGET + target)

    /** ページへのリンクなら、そのページの id。そうでなければ null。 */
    fun targetOf(classes: Iterable<*>): String? {
        var page = false
        var target: String? = null
        for (item in classes) {
            val cls = item as? String ?: continue
            if (cls == css(Mark.Style.PAGE)) page = true
            if (target == null && cls.startsWith(TARGET)) target = cls.substring(TARGET.length)
        }
        return if (page) target else null
    }

    /** ページへのリンクのクラスを外す。ほかの装飾は残す。 */
    fun withoutPage(classes: Iterable<*>): MutableCollection<String> {
        val out = ArrayList<String>()
        for (item in classes) {
            val c = item as? String ?: continue
            if (c != css(Mark.Style.PAGE) && !c.startsWith(TARGET)) out.add(c)
        }
        return out
    }
}
