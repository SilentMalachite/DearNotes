package app.dearnotes.db

import java.sql.Connection
import java.sql.SQLException

/** PRAGMA user_version で版を管理する。版を足すときは STEPS の末尾に追加する。 */
internal object Migrations {

    private val STEPS: List<List<String>> = listOf(
        // v1
        listOf(
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
            )
            """.trimIndent(),
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
            )
            """.trimIndent(),
            "CREATE INDEX blocks_by_page ON blocks (page_id, sort_key)",
            // blocks は rowid を使う外部コンテンツ。rowid が変わる VACUUM はしない
            """
            CREATE VIRTUAL TABLE blocks_fts USING fts5(
              text, content = 'blocks', content_rowid = 'rowid',
              tokenize = 'trigram'
            )
            """.trimIndent(),
            """
            CREATE TRIGGER blocks_ai AFTER INSERT ON blocks BEGIN
              INSERT INTO blocks_fts(rowid, text) VALUES (new.rowid, new.text);
            END
            """.trimIndent(),
            """
            CREATE TRIGGER blocks_ad AFTER DELETE ON blocks BEGIN
              INSERT INTO blocks_fts(blocks_fts, rowid, text) VALUES ('delete', old.rowid, old.text);
            END
            """.trimIndent(),
            """
            CREATE TRIGGER blocks_au AFTER UPDATE OF text ON blocks BEGIN
              INSERT INTO blocks_fts(blocks_fts, rowid, text) VALUES ('delete', old.rowid, old.text);
              INSERT INTO blocks_fts(rowid, text) VALUES (new.rowid, new.text);
            END
            """.trimIndent(),
        ),
        // v2: 子の行を畳んでいるか。種類は変えないので表は作り直さない
        listOf(
            "ALTER TABLE blocks ADD COLUMN collapsed INTEGER NOT NULL DEFAULT 0",
        ),
    )

    fun latestVersion(): Int = STEPS.size

    fun apply(conn: Connection) {
        apply(conn, STEPS.size)
    }

    /** target の版まで進める。古い版からの移行を確かめるテスト用。 */
    fun apply(conn: Connection, target: Int) {
        val current = conn.createStatement().use { s ->
            s.executeQuery("PRAGMA user_version").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
        if (current > STEPS.size) {
            throw SQLException("データベースの版 $current はこのアプリより新しい")
        }
        val auto = conn.autoCommit
        conn.autoCommit = false
        try {
            conn.createStatement().use { s ->
                for (v in current until target) {
                    for (sql in STEPS[v]) s.execute(sql)
                    s.execute("PRAGMA user_version = ${v + 1}")
                }
                conn.commit()
            }
        } catch (e: SQLException) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = auto
        }
    }
}
