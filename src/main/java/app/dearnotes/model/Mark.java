package app.dearnotes.model;

import java.util.Locale;

/** 行内の装飾。start は含み、end は含まない。target は PAGE のときだけ、リンク先のページの id。 */
public record Mark(int start, int end, Style style, String target) {

    public enum Style {
        BOLD, ITALIC, STRIKE, CODE, LINK, PAGE;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Style fromKey(String s) {
            return valueOf(s.toUpperCase(Locale.ROOT));
        }
    }

    public Mark {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("bad range: " + start + ".." + end);
        }
        if ((style == Style.PAGE) != (target != null && !target.isBlank())) {
            throw new IllegalArgumentException("bad target: " + style + " " + target);
        }
    }

    public Mark(int start, int end, Style style) {
        this(start, end, style, null);
    }

    /** 種類とリンク先はそのままで、範囲を替える。 */
    public Mark withRange(int start, int end) {
        return new Mark(start, end, style, target);
    }
}
