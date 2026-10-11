package app.dearnotes.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** 全ページを語句で探す。日本語の部分一致は FTS5 trigram で探す。 */
public final class SearchRepo {

    public static final int LIMIT = 20;
    private static final int SNIPPET_RADIUS = 18;

    /** blockId が null ならタイトルに一致した。 */
    public record Hit(String pageId, String pageTitle, String pageIcon, boolean daily,
                      String blockId, String snippet) {
        public boolean titleMatch() {
            return blockId == null;
        }
    }

    private final Database db;

    public SearchRepo(Database db) {
        this.db = db;
    }

    public List<Hit> search(String query) throws SQLException {
        String q = query == null ? "" : query.strip();
        if (q.isEmpty()) return List.of();
        return db.read(c -> {
            var hits = new LinkedHashMap<String, Hit>();
            for (Hit h : titleSearch(c, q)) hits.putIfAbsent(h.pageId() + ":", h);
            List<Hit> body = q.codePointCount(0, q.length()) >= 3
                ? ftsSearch(c, "\"" + q.replace("\"", "\"\"") + "\"", q)   // MATCH 用に引用
                : likeSearch(c, q);
            for (Hit h : body) {
                if (hits.size() >= LIMIT) break;
                hits.putIfAbsent(h.pageId() + ":" + h.blockId(), h);
            }
            return new ArrayList<>(hits.values()).subList(0, Math.min(LIMIT, hits.size()));
        });
    }

    private static List<Hit> titleSearch(Connection c, String q) throws SQLException {
        try (var ps = c.prepareStatement("""
                SELECT id, title, icon, daily_date FROM pages
                WHERE deleted_at IS NULL AND title LIKE ? ESCAPE '\\'
                ORDER BY updated_at DESC LIMIT ?""")) {
            ps.setString(1, "%" + escapeLike(q) + "%");
            ps.setInt(2, LIMIT);
            try (var rs = ps.executeQuery()) {
                var out = new ArrayList<Hit>();
                while (rs.next()) {
                    out.add(new Hit(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4) != null, null, "タイトルに一致"));
                }
                return out;
            }
        }
    }

    private static List<Hit> ftsSearch(Connection c, String match, String q) throws SQLException {
        return blockSearch(c, """
                SELECT b.id, b.text, p.id, p.title, p.icon, p.daily_date
                FROM blocks_fts f
                JOIN blocks b ON b.rowid = f.rowid
                JOIN pages p ON p.id = b.page_id
                WHERE blocks_fts MATCH ? AND p.deleted_at IS NULL
                ORDER BY f.rank LIMIT ?""", match, q);
    }

    /** 2文字以下は trigram で探せないので LIKE で探す。 */
    private static List<Hit> likeSearch(Connection c, String q) throws SQLException {
        return blockSearch(c, """
                SELECT b.id, b.text, p.id, p.title, p.icon, p.daily_date
                FROM blocks b JOIN pages p ON p.id = b.page_id
                WHERE b.text LIKE ? ESCAPE '\\' AND p.deleted_at IS NULL
                ORDER BY p.updated_at DESC, b.sort_key LIMIT ?""", "%" + escapeLike(q) + "%", q);
    }

    private static List<Hit> blockSearch(Connection c, String sql, String arg, String q) throws SQLException {
        try (var ps = c.prepareStatement(sql)) {
            ps.setString(1, arg);
            ps.setInt(2, LIMIT);
            try (var rs = ps.executeQuery()) {
                var out = new ArrayList<Hit>();
                while (rs.next()) {
                    out.add(new Hit(rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6) != null, rs.getString(1), snippet(rs.getString(2), q)));
                }
                return out;
            }
        }
    }

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 一致した場所の前後だけを切り出す。 */
    static String snippet(String text, String q) {
        String flat = text.replace('\n', ' ');
        int at = flat.toLowerCase(Locale.ROOT).indexOf(q.toLowerCase(Locale.ROOT));
        if (at < 0) at = 0;
        int from = Math.max(0, at - SNIPPET_RADIUS);
        int to = Math.min(flat.length(), at + q.length() + SNIPPET_RADIUS);
        return (from > 0 ? "…" : "") + flat.substring(from, to) + (to < flat.length() ? "…" : "");
    }
}
