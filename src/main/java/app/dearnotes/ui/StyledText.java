package app.dearnotes.ui;

import app.dearnotes.editor.Marks;
import app.dearnotes.model.Mark;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import org.fxmisc.richtext.StyleClassedTextArea;
import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

/** Mark の並びと RichTextFX の StyleSpans を行き来する。CSS クラスは「m-太字の名前」。 */
final class StyledText {

    private static final String PREFIX = "m-";

    private StyledText() {}

    static String css(Mark.Style style) {
        return PREFIX + style.key();
    }

    /** 文字列と装飾をエリアに入れる。段落（改行）ごとに装飾を当てる。 */
    static void load(StyleClassedTextArea area, String text, List<Mark> marks) {
        area.replaceText(text);
        // replaceText は前の先頭文字の装飾を引き継ぐので、いったん全部外す
        if (area.getLength() > 0) area.setStyle(0, area.getLength(), List.of());
        int offset = 0;
        for (int p = 0; p < area.getParagraphs().size(); p++) {
            int len = area.getParagraph(p).length();
            List<Mark> local = Marks.slice(marks, offset, offset + len);
            if (!local.isEmpty()) area.setStyleSpans(p, 0, spans(len, local));
            offset += len + 1;
        }
    }

    /** エリアの装飾を Mark に戻す。 */
    static List<Mark> read(StyleClassedTextArea area) {
        var out = new ArrayList<Mark>();
        int offset = 0;
        for (int p = 0; p < area.getParagraphs().size(); p++) {
            int pos = offset;
            for (StyleSpan<Collection<String>> span : area.getStyleSpans(p)) {
                for (String cls : span.getStyle()) {
                    if (!cls.startsWith(PREFIX) || span.getLength() == 0) continue;
                    try {
                        out.add(new Mark(pos, pos + span.getLength(), Mark.Style.fromKey(cls.substring(PREFIX.length()))));
                    } catch (IllegalArgumentException ignored) {
                        // 知らないクラス
                    }
                }
                pos += span.getLength();
            }
            offset += area.getParagraph(p).length() + 1;
        }
        return Marks.normalize(out);
    }

    static StyleSpans<Collection<String>> spans(int length, List<Mark> marks) {
        var cuts = new TreeSet<Integer>();
        cuts.add(0);
        cuts.add(length);
        for (Mark m : marks) {
            cuts.add(Math.clamp(m.start(), 0, length));
            cuts.add(Math.clamp(m.end(), 0, length));
        }
        var builder = new StyleSpansBuilder<Collection<String>>();
        Integer prev = null;
        for (int cut : cuts) {
            if (prev != null && cut > prev) {
                var classes = new ArrayList<String>();
                for (Mark m : marks) {
                    if (m.start() <= prev && m.end() >= cut) classes.add(css(m.style()));
                }
                builder.add(classes, cut - prev);
            }
            prev = cut;
        }
        if (length == 0) builder.add(List.of(), 0);
        return builder.create();
    }

    /** 選んだ範囲の装飾を切り替える。全部に付いていれば外し、そうでなければ付ける。 */
    static void toggle(StyleClassedTextArea area, Mark.Style style) {
        var sel = area.getSelection();
        if (sel.getLength() == 0) return;
        String cls = css(style);
        StyleSpans<Collection<String>> current = area.getStyleSpans(sel.getStart(), sel.getEnd());
        boolean all = current.stream().allMatch(s -> s.getLength() == 0 || s.getStyle().contains(cls));
        StyleSpans<Collection<String>> updated = current.mapStyles(classes -> {
            var next = new ArrayList<>(classes);
            next.remove(cls);
            if (!all) next.add(cls);
            return next;
        });
        area.setStyleSpans(sel.getStart(), updated);
    }
}
