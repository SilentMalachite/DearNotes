package app.dearnotes.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** PRAGMA user_version で版を管理する。版を足すときは STEPS の末尾に追加する。 */
final class Migrations {

    private static final List<List<String>> STEPS = List.of(
        // v1
        List.of(
            """
            CREATE TABLE pages (
              id          TEXT PRIMARY KEY,
              parent_id   TEXT REFERENCES pages(id) ON DELETE CASCADE,
              title       TEXT NOT NULL DEFAULT '',
              icon        TEXT,
              daily_date  TEXT UNIQUE,
              sort_key    REAL NOT NULL,
              created_at  INTEGER NOT NULL,
              updated_at  INTEGER NOT NULL,
              deleted_at  INTEGER
            )""",
            "CREATE INDEX pages_by_parent ON pages (parent_id, sort_key)",
            """
            CREATE TABLE blocks (
              id          TEXT PRIMARY KEY,
              page_id     TEXT NOT NULL REFERENCES pages(id) ON DELETE CASCADE,
              type        TEXT NOT NULL CHECK (type IN ('paragraph','h1','h2','h3',
                            'bullet','numbered','todo','quote','code','divider')),
              text        TEXT NOT NULL DEFAULT '',
              marks       TEXT,
              checked     INTEGER NOT NULL DEFAULT 0,
              indent      INTEGER NOT NULL DEFAULT 0,
              sort_key    REAL NOT NULL,
              updated_at  INTEGER NOT NULL
            )""",
            "CREATE INDEX blocks_by_page ON blocks (page_id, sort_key)",
            // blocks は rowid を使う外部コンテンツ。rowid が変わる VACUUM はしない
            """
            CREATE VIRTUAL TABLE blocks_fts USING fts5(
              text, content = 'blocks', content_rowid = 'rowid',
              tokenize = 'trigram'
            )""",
            """
            CREATE TRIGGER blocks_ai AFTER INSERT ON blocks BEGIN
              INSERT INTO blocks_fts(rowid, text) VALUES (new.rowid, new.text);
            END""",
            """
            CREATE TRIGGER blocks_ad AFTER DELETE ON blocks BEGIN
              INSERT INTO blocks_fts(blocks_fts, rowid, text) VALUES ('delete', old.rowid, old.text);
            END""",
            """
            CREATE TRIGGER blocks_au AFTER UPDATE OF text ON blocks BEGIN
              INSERT INTO blocks_fts(blocks_fts, rowid, text) VALUES ('delete', old.rowid, old.text);
              INSERT INTO blocks_fts(rowid, text) VALUES (new.rowid, new.text);
            END"""
        ),
        // v2: 子の行を畳んでいるか。種類は変えないので表は作り直さない
        List.of(
            "ALTER TABLE blocks ADD COLUMN collapsed INTEGER NOT NULL DEFAULT 0"
        )
    );

    private Migrations() {}

    static int latestVersion() {
        return STEPS.size();
    }

    static void apply(Connection conn) throws SQLException {
        apply(conn, STEPS.size());
    }

    /** target の版まで進める。古い版からの移行を確かめるテスト用。 */
    static void apply(Connection conn, int target) throws SQLException {
        int current;
        try (var s = conn.createStatement(); var rs = s.executeQuery("PRAGMA user_version")) {
            current = rs.next() ? rs.getInt(1) : 0;
        }
        if (current > STEPS.size()) {
            throw new SQLException("データベースの版 " + current + " はこのアプリより新しい");
        }
        boolean auto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (var s = conn.createStatement()) {
            for (int v = current; v < target; v++) {
                for (String sql : STEPS.get(v)) s.execute(sql);
                s.execute("PRAGMA user_version = " + (v + 1));
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(auto);
        }
    }
}
