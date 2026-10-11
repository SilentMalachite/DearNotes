package app.dearnotes.editor;

import app.dearnotes.model.Mark;
import java.util.ArrayList;
import java.util.List;

/**
 * marks 列の JSON: [[start,end,"style"],…]。ページへのリンクだけ4つ目にページの id を持つ。
 * 形が決まっているので手で読む。古い版は要素が3つでない装飾を捨てる。
 */
public final class MarksCodec {

    private MarksCodec() {}

    public static String encode(List<Mark> marks) {
        if (marks == null || marks.isEmpty()) return null;
        var sb = new StringBuilder("[");
        for (int i = 0; i < marks.size(); i++) {
            Mark m = marks.get(i);
            if (i > 0) sb.append(',');
            sb.append('[').append(m.start()).append(',').append(m.end())
              .append(",\"").append(m.style().key()).append('"');
            if (m.target() != null) sb.append(",\"").append(m.target()).append('"');
            sb.append(']');
        }
        return sb.append(']').toString();
    }

    public static List<Mark> decode(String json) {
        if (json == null || json.isBlank()) return List.of();
        var out = new ArrayList<Mark>();
        String s = json.strip();
        int i = s.indexOf('[') + 1;
        while (true) {
            int open = s.indexOf('[', i);
            if (open < 0) break;
            int close = s.indexOf(']', open);
            if (close < 0) break;
            String[] parts = s.substring(open + 1, close).split(",");
            i = close + 1;
            if (parts.length != 3 && parts.length != 4) continue;
            try {
                int start = Integer.parseInt(parts[0].strip());
                int end = Integer.parseInt(parts[1].strip());
                String style = parts[2].strip().replace("\"", "");
                String target = parts.length == 4 ? parts[3].strip().replace("\"", "") : null;
                out.add(new Mark(start, end, Mark.Style.fromKey(style), target));
            } catch (IllegalArgumentException ignored) {
                // 知らない装飾や壊れた値は捨てる
            }
        }
        return List.copyOf(out);
    }
}
