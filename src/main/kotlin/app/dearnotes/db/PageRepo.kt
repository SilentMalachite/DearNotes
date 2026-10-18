package app.dearnotes.db

import app.dearnotes.isJavaBlank
import app.dearnotes.strip

import app.dearnotes.model.Ids
import app.dearnotes.model.Page
import app.dearnotes.model.SortKeys
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.Duration
import java.util.ArrayList
import kotlinx.datetime.LocalDate

class PageRepo(private val db: Database) {

    /** 親の子の末尾に作る。parentId が null なら一番上の階層。 */
    fun create(parentId: String?, title: String?): Page {
        return db.write { c ->
            val now = System.currentTimeMillis()
            val key = nextChildKey(c, parentId)
            val id = Ids.uuidV7()
            c.prepareStatement(
                """
                INSERT INTO pages (id, parent_id, title, sort_key, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, parentId)
                ps.setString(3, if (title == null) "" else title)
                ps.setDouble(4, key)
                ps.setLong(5, now)
                ps.setLong(6, now)
                ps.executeUpdate()
            }
            get(c, id) ?: error("missing")
        }
    }

    /** その日の日記を開く。なければ作る。2回呼んでも1ページだけ。 */
    fun openDaily(date: LocalDate): Page {
        return db.write { c ->
            val now = System.currentTimeMillis()
            c.prepareStatement(
                """
                INSERT INTO pages (id, title, daily_date, sort_key, created_at, updated_at)
                VALUES (?, ?, ?, 0, ?, ?)
                ON CONFLICT (daily_date) DO NOTHING
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, Ids.uuidV7())
                ps.setString(2, date.toString())
                ps.setString(3, date.toString())
                ps.setLong(4, now)
                ps.setLong(5, now)
                ps.executeUpdate()
            }
            val daily = query(c, "SELECT $COLUMNS FROM pages WHERE daily_date = ?", date.toString()).first()
            if (!daily.isTrashed()) {
                daily
            } else {
                // ゴミ箱にあった日記は、一緒に捨てた子ページごと戻す
                restoreTree(c, daily.id)
                c.prepareStatement("UPDATE pages SET parent_id = NULL WHERE id = ?").use { ps ->
                    ps.setString(1, daily.id)
                    ps.executeUpdate()
                }
                get(c, daily.id) ?: error("missing")
            }
        }
    }

    fun get(id: String): Page? = db.read { c -> get(c, id) }

    /** ゴミ箱にないページ。sort_key 順。 */
    fun listAlive(): List<Page> {
        return db.read { c ->
            query(c, "SELECT $COLUMNS FROM pages WHERE deleted_at IS NULL ORDER BY sort_key, created_at")
        }
    }

    /**
     * 「[[」の候補。名前に query を含む、ゴミ箱にない名前付きのページ。
     * 名前がそのまま一致するもの、名前の頭で一致するもの、最近変えたものから。query が空なら最近変えたものから。
     * 一致するページが件数の外に落ちると、選べずに同じ名前のページを作ってしまうので、件数で切る前に先頭へ並べる。
     */
    fun findByTitle(query: String?, limit: Int): List<Page> {
        val q = if (query == null) "" else query.strip()
        return db.read { c ->
            query(
                c,
                """
                SELECT $COLUMNS
                FROM pages
                WHERE deleted_at IS NULL AND title <> '' AND title LIKE ? ESCAPE '\'
                ORDER BY title = ? DESC, instr(title, ?) = 1 DESC, updated_at DESC
                LIMIT ${maxOf(0, limit)}
                """.trimIndent(),
                "%" + SearchRepo.escapeLike(q) + "%",
                q,
                q,
            )
        }
    }

    /** ゴミ箱の中で、親がゴミ箱にないもの（まとめて消された木の根）。新しい順。 */
    fun listTrash(): List<Page> {
        return db.read { c ->
            query(
                c,
                """
                SELECT $COLUMNS
                FROM pages p WHERE p.deleted_at IS NOT NULL
                  AND (p.parent_id IS NULL OR NOT EXISTS (
                       SELECT 1 FROM pages q WHERE q.id = p.parent_id AND q.deleted_at IS NOT NULL))
                ORDER BY p.deleted_at DESC
                """.trimIndent(),
            )
        }
    }

    fun rename(id: String, title: String?) {
        update("UPDATE pages SET title = ?, updated_at = ? WHERE id = ?", title, id)
    }

    fun setIcon(id: String, icon: String?) {
        update(
            "UPDATE pages SET icon = ?, updated_at = ? WHERE id = ?",
            if (icon == null || icon.isJavaBlank()) null else icon,
            id,
        )
    }

    fun touch(id: String) {
        db.write { c ->
            c.prepareStatement("UPDATE pages SET updated_at = ? WHERE id = ?").use { ps ->
                ps.setLong(1, System.currentTimeMillis())
                ps.setString(2, id)
                ps.executeUpdate()
            }
        }
    }

    /** 親と順番を変える。自分の子孫の下には入れられない。 */
    fun move(id: String, parentId: String?, sortKey: Double) {
        db.write { c ->
            if (parentId != null && subtree(c, id).contains(parentId)) {
                throw SQLException("ページを自分の下に移すことはできない")
            }
            c.prepareStatement(
                "UPDATE pages SET parent_id = ?, sort_key = ?, updated_at = ? WHERE id = ?",
            ).use { ps ->
                ps.setString(1, parentId)
                ps.setDouble(2, sortKey)
                ps.setLong(3, System.currentTimeMillis())
                ps.setString(4, id)
                ps.executeUpdate()
            }
        }
    }

    /** 兄弟を GAP 間隔で振り直す。間に入れる余地がなくなったとき用。 */
    fun respaceChildren(parentId: String?) {
        db.write { c ->
            val siblings = query(
                c,
                "SELECT $COLUMNS FROM pages WHERE parent_id IS ? " +
                    "AND deleted_at IS NULL ORDER BY sort_key, created_at",
                parentId,
            )
            val keys = SortKeys.spaced(siblings.size)
            c.prepareStatement("UPDATE pages SET sort_key = ? WHERE id = ?").use { ps ->
                for (i in siblings.indices) {
                    ps.setDouble(1, keys[i])
                    ps.setString(2, siblings[i].id)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            null
        }
    }

    /** 子ページも一緒にゴミ箱へ。 */
    fun trash(id: String, now: Long) {
        db.write { c ->
            c.prepareStatement(
                """
                WITH RECURSIVE sub(id) AS (
                  SELECT ?
                  UNION ALL
                  SELECT p.id FROM pages p JOIN sub ON p.parent_id = sub.id
                )
                UPDATE pages SET deleted_at = ? WHERE id IN sub AND deleted_at IS NULL
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, id)
                ps.setLong(2, now)
                ps.executeUpdate()
            }
        }
    }

    fun restore(id: String) {
        db.write { c ->
            restoreTree(c, id)
            c.prepareStatement(
                """
                UPDATE pages SET parent_id = NULL
                WHERE id = ? AND parent_id IN (SELECT id FROM pages WHERE deleted_at IS NOT NULL)
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
        }
    }

    fun deleteForever(id: String) {
        db.write { c ->
            c.prepareStatement("DELETE FROM pages WHERE id = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
        }
    }

    /** 起動時に1回: cutoff より前にゴミ箱に入ったものを消す。blocks は CASCADE。 */
    fun purgeTrashedBefore(cutoff: Long): Int {
        return db.write { c ->
            c.prepareStatement("DELETE FROM pages WHERE deleted_at < ?").use { ps ->
                ps.setLong(1, cutoff)
                ps.executeUpdate()
            }
        }
    }

    private fun update(sql: String, value: String?, id: String) {
        db.write { c ->
            c.prepareStatement(sql).use { ps ->
                ps.setString(1, value)
                ps.setLong(2, System.currentTimeMillis())
                ps.setString(3, id)
                ps.executeUpdate()
            }
        }
    }

    /** id と、id と一緒にゴミ箱へ入れた子孫を戻す。先に別で捨てた子は残す。 */
    private fun restoreTree(c: Connection, id: String) {
        c.prepareStatement(
            """
            WITH RECURSIVE sub(id) AS (
              SELECT ?
              UNION ALL
              SELECT p.id FROM pages p JOIN sub ON p.parent_id = sub.id
            )
            UPDATE pages SET deleted_at = NULL
            WHERE id IN sub
              AND deleted_at = (SELECT deleted_at FROM pages WHERE id = ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, id)
            ps.executeUpdate()
        }
    }

    private fun nextChildKey(c: Connection, parentId: String?): Double {
        return c.prepareStatement(
            "SELECT MAX(sort_key) FROM pages WHERE parent_id IS ? AND daily_date IS NULL",
        ).use { ps ->
            ps.setString(1, parentId)
            ps.executeQuery().use { rs ->
                val max = if (rs.next()) rs.getDouble(1) else 0.0
                max + SortKeys.GAP
            }
        }
    }

    private fun subtree(c: Connection, id: String): List<String> {
        val ids = ArrayList<String>()
        c.prepareStatement(
            """
            WITH RECURSIVE sub(id) AS (
              SELECT ? UNION ALL SELECT p.id FROM pages p JOIN sub ON p.parent_id = sub.id
            ) SELECT id FROM sub
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                while (rs.next()) ids.add(rs.getString(1))
            }
        }
        return ids
    }

    private fun get(c: Connection, id: String): Page? {
        return query(c, "SELECT $COLUMNS FROM pages WHERE id = ?", id).firstOrNull()
    }

    private fun query(c: Connection, sql: String, vararg args: String?): List<Page> {
        return c.prepareStatement(sql).use { ps ->
            for (i in args.indices) {
                val arg = args[i]
                if (arg == null) ps.setNull(i + 1, Types.VARCHAR)
                else ps.setString(i + 1, arg)
            }
            ps.executeQuery().use { rs ->
                val out = ArrayList<Page>()
                while (rs.next()) out.add(map(rs))
                out
            }
        }
    }

    companion object {
        val TRASH_RETENTION: Duration = Duration.ofDays(30)

        private const val COLUMNS =
            "id, parent_id, title, icon, daily_date, sort_key, created_at, updated_at, deleted_at"

        private fun map(rs: ResultSet): Page {
            val daily = rs.getString("daily_date")
            val deleted = rs.getLong("deleted_at")
            val deletedAt: Long? = if (rs.wasNull()) null else deleted
            return Page(
                rs.getString("id"),
                rs.getString("parent_id"),
                rs.getString("title"),
                rs.getString("icon"),
                if (daily == null) null else LocalDate.parse(daily),
                rs.getDouble("sort_key"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                deletedAt,
            )
        }
    }
}
