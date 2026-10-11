package app.dearnotes.model;

import java.util.Locale;

/** 行内の装飾。start は含み、end は含まない。 */
public record Mark(int start, int end, Style style) {

    public enum Style {
        BOLD, ITALIC, STRIKE, CODE, LINK;

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
    }
}
