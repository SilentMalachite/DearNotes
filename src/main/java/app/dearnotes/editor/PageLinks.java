package app.dearnotes.editor;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 「[[」の後ろに打ったページ名を読む。 */
public final class PageLinks {

    /** 「[[」の後ろで打っているページ名。from は「[[」の位置。 */
    public record Query(int from, String text) {}

    // 日本語入力では [[ が「「 や ［［ になる。閉じ括弧か改行があれば候補を閉じる
    private static final Pattern OPEN = Pattern.compile("(?:\\[\\[|「「|［［)([^\\[\\]「」［］\\n]{0,40})$");

    private PageLinks() {}

    /** caret の前が「[[語句」なら、その位置と語句。そうでなければ空。 */
    public static Optional<Query> queryAt(String text, int caret) {
        if (caret < 0 || caret > text.length()) return Optional.empty();
        Matcher m = OPEN.matcher(text.substring(0, caret));
        return m.find() ? Optional.of(new Query(m.start(), m.group(1))) : Optional.empty();
    }
}
