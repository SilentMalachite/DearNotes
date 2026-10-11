package app.dearnotes.db;

import app.dearnotes.model.Ids;
import app.dearnotes.model.Page;
import app.dearnotes.model.SortKeys;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class PageRepo {

    public static final Duration TRASH_RETENTION = Duration.ofDays(30);

    private static final String COLUMNS =
        "id, parent_id, title, icon, daily_date, sort_key, created_at, updated_at, deleted_at";

    private final Database db;

    public PageRepo(Database db) {
        this.db = db;
    }

    /** 親の子の末尾に作る。parentId が null なら一番上の階層。 */
    public Page create(String parentId, String title) throws SQLException {
        return db.write(c -> {
            long now = System.currentTimeMillis();
            double key = nextChildKey(c, parentId);
            String id = Ids.uuidV7();
            try (var ps = c.prepareStatement("""
                    INSERT INTO pages (id, parent_id, title, sort_key, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)""")) {
                ps.setString(1, id);
                ps.setString(2, parentId);
                ps.setString(3, title == null ? "" : title);
                ps.setDouble(4, key);
                ps.setLong(5, now);
                ps.setLong(6, now);
                ps.executeUpdate();
            }
            return get(c, id).orElseThrow();
        });
    }

    /** その日の日記を開く。なければ作る。2回呼んでも1ページだけ。 */
    public Page openDaily(LocalDate date) throws SQLException {
        return db.write(c -> {
            long now = System.currentTimeMillis();
            try (var ps = c.prepareStatement("""
                    INSERT INTO pages (id, title, daily_date, sort_key, created_at, updated_at)
                    VALUES (?, ?, ?, 0, ?, ?)
                    ON CONFLICT (daily_date) DO NOTHING""")) {
                ps.setString(1, Ids.uuidV7());
                ps.setString(2, date.toString());
                ps.setString(3, date.toString());
                ps.setLong(4, now);
                ps.setLong(5, now);
                ps.executeUpdate();
            }
            Page daily = query(c, "SELECT " + COLUMNS + " FROM pages WHERE daily_date = ?", date.toString()).getFirst();
            if (!daily.isTrashed()) return daily;
            // ゴミ箱にあった日記は、一緒に捨てた子ページごと戻す
            restoreTree(c, daily.id());
            try (var ps = c.prepareStatement("UPDATE pages SET parent_id = NULL WHERE id = ?")) {
                ps.setString(1, daily.id());
                ps.executeUpdate();
            }
            return get(c, daily.id()).orElseThrow();
        });
    }

    public Optional<Page> get(String id) throws SQLException {
        return db.read(c -> get(c, id));
    }

    /** ゴミ箱にないページ。sort_key 順。 */
    public List<Page> listAlive() throws SQLException {
        return db.read(c -> query(c,
            "SELECT " + COLUMNS + " FROM pages WHERE deleted_at IS NULL ORDER BY sort_key, created_at"));
    }

    /**
     * 「[[」の候補。名前に query を含む、ゴミ箱にない名前付きのページ。
     * 名前の頭で一致するもの、最近変えたものから。query が空なら最近変えたものから。
     */
    public List<Page> findByTitle(String query, int limit) throws SQLException {
        String q = query == null ? "" : query.strip();
        return db.read(c -> query(c, "SELECT " + COLUMNS + " " + """
             FROM pages
             WHERE deleted_at IS NULL AND title <> '' AND title LIKE ? ESCAPE '\\'
             ORDER BY instr(title, ?) = 1 DESC, updated_at DESC
             LIMIT """ + " " + Math.max(0, limit), "%" + SearchRepo.escapeLike(q) + "%", q));
    }

    /** ゴミ箱の中で、親がゴミ箱にないもの（まとめて消された木の根）。新しい順。 */
    public List<Page> listTrash() throws SQLException {
        return db.read(c -> query(c, "SELECT " + COLUMNS + " " + """
             FROM pages p WHERE p.deleted_at IS NOT NULL
               AND (p.parent_id IS NULL OR NOT EXISTS (
                    SELECT 1 FROM pages q WHERE q.id = p.parent_id AND q.deleted_at IS NOT NULL))
             ORDER BY p.deleted_at DESC"""));
    }

    public void rename(String id, String title) throws SQLException {
        update("UPDATE pages SET title = ?, updated_at = ? WHERE id = ?", title, id);
    }

    public void setIcon(String id, String icon) throws SQLException {
        update("UPDATE pages SET icon = ?, updated_at = ? WHERE id = ?",
            icon == null || icon.isBlank() ? null : icon, id);
    }

    public void touch(String id) throws SQLException {
        db.write(c -> {
            try (var ps = c.prepareStatement("UPDATE pages SET updated_at = ? WHERE id = ?")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, id);
                return ps.executeUpdate();
            }
        });
    }

    /** 親と順番を変える。自分の子孫の下には入れられない。 */
    public void move(String id, String parentId, double sortKey) throws SQLException {
        db.write(c -> {
            if (parentId != null && subtree(c, id).contains(parentId)) {
                throw new SQLException("ページを自分の下に移すことはできない");
            }
            try (var ps = c.prepareStatement(
                    "UPDATE pages SET parent_id = ?, sort_key = ?, updated_at = ? WHERE id = ?")) {
                ps.setString(1, parentId);
                ps.setDouble(2, sortKey);
                ps.setLong(3, System.currentTimeMillis());
                ps.setString(4, id);
                return ps.executeUpdate();
            }
        });
    }

    /** 兄弟を GAP 間隔で振り直す。間に入れる余地がなくなったとき用。 */
    public void respaceChildren(String parentId) throws SQLException {
        db.write(c -> {
            List<Page> siblings = query(c, "SELECT " + COLUMNS + " FROM pages WHERE parent_id IS ? "
                + "AND deleted_at IS NULL ORDER BY sort_key, created_at", parentId);
            List<Double> keys = SortKeys.spaced(siblings.size());
            try (var ps = c.prepareStatement("UPDATE pages SET sort_key = ? WHERE id = ?")) {
                for (int i = 0; i < siblings.size(); i++) {
                    ps.setDouble(1, keys.get(i));
                    ps.setString(2, siblings.get(i).id());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    /** 子ページも一緒にゴミ箱へ。 */
    public void trash(String id, long now) throws SQLException {
        db.write(c -> {
            try (var ps = c.prepareStatement("""
                    WITH RECURSIVE sub(id) AS (
                      SELECT ?
                      UNION ALL
                      SELECT p.id FROM pages p JOIN sub ON p.parent_id = sub.id
                    )
                    UPDATE pages SET deleted_at = ? WHERE id IN sub AND deleted_at IS NULL""")) {
                ps.setString(1, id);
                ps.setLong(2, now);
                return ps.executeUpdate();
            }
        });
    }

    public void restore(String id) throws SQLException {
        db.write(c -> {
            restoreTree(c, id);
            try (var ps = c.prepareStatement("""
                    UPDATE pages SET parent_id = NULL
                    WHERE id = ? AND parent_id IN (SELECT id FROM pages WHERE deleted_at IS NOT NULL)""")) {
                ps.setString(1, id);
                return ps.executeUpdate();
            }
        });
    }

    public void deleteForever(String id) throws SQLException {
        db.write(c -> {
            try (var ps = c.prepareStatement("DELETE FROM pages WHERE id = ?")) {
                ps.setString(1, id);
                return ps.executeUpdate();
            }
        });
    }

    /** 起動時に1回: cutoff より前にゴミ箱に入ったものを消す。blocks は CASCADE。 */
    public int purgeTrashedBefore(long cutoff) throws SQLException {
        return db.write(c -> {
            try (var ps = c.prepareStatement("DELETE FROM pages WHERE deleted_at < ?")) {
                ps.setLong(1, cutoff);
                return ps.executeUpdate();
            }
        });
    }

    // ---- helpers ----

    private void update(String sql, String value, String id) throws SQLException {
        db.write(c -> {
            try (var ps = c.prepareStatement(sql)) {
                ps.setString(1, value);
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, id);
                return ps.executeUpdate();
            }
        });
    }

    /** id と、id と一緒にゴミ箱へ入れた子孫を戻す。先に別で捨てた子は残す。 */
    private static void restoreTree(Connection c, String id) throws SQLException {
        try (var ps = c.prepareStatement("""
                WITH RECURSIVE sub(id) AS (
                  SELECT ?
                  UNION ALL
                  SELECT p.id FROM pages p JOIN sub ON p.parent_id = sub.id
                )
                UPDATE pages SET deleted_at = NULL
                WHERE id IN sub
                  AND deleted_at = (SELECT deleted_at FROM pages WHERE id = ?)""")) {
            ps.setString(1, id);
            ps.setString(2, id);
            ps.executeUpdate();
        }
    }

    private static double nextChildKey(Connection c, String parentId) throws SQLException {
        try (var ps = c.prepareStatement(
                "SELECT MAX(sort_key) FROM pages WHERE parent_id IS ? AND daily_date IS NULL")) {
            ps.setString(1, parentId);
            try (var rs = ps.executeQuery()) {
                double max = rs.next() ? rs.getDouble(1) : 0;
                return max + SortKeys.GAP;
            }
        }
    }

    private static List<String> subtree(Connection c, String id) throws SQLException {
        var ids = new ArrayList<String>();
        try (var ps = c.prepareStatement("""
                WITH RECURSIVE sub(id) AS (
                  SELECT ? UNION ALL SELECT p.id FROM pages p JOIN sub ON p.parent_id = sub.id
                ) SELECT id FROM sub""")) {
            ps.setString(1, id);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getString(1));
            }
        }
        return ids;
    }

    private static Optional<Page> get(Connection c, String id) throws SQLException {
        List<Page> list = query(c, "SELECT " + COLUMNS + " FROM pages WHERE id = ?", id);
        return list.stream().findFirst();
    }

    private static List<Page> query(Connection c, String sql, String... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null) ps.setNull(i + 1, Types.VARCHAR);
                else ps.setString(i + 1, args[i]);
            }
            try (var rs = ps.executeQuery()) {
                var out = new ArrayList<Page>();
                while (rs.next()) out.add(map(rs));
                return out;
            }
        }
    }

    private static Page map(ResultSet rs) throws SQLException {
        String daily = rs.getString("daily_date");
        long deleted = rs.getLong("deleted_at");
        Long deletedAt = rs.wasNull() ? null : deleted;
        return new Page(
            rs.getString("id"),
            rs.getString("parent_id"),
            rs.getString("title"),
            rs.getString("icon"),
            daily == null ? null : LocalDate.parse(daily),
            rs.getDouble("sort_key"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"),
            deletedAt);
    }
}
