package app.dearnotes.db

import app.dearnotes.strip
import app.dearnotes.stripLeading

import java.sql.Connection
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** 全ページを語句で探す。日本語の部分一致は FTS5 trigram で探す。 */
class SearchRepo(private val db: Database) {

    /** blockId が null ならタイトルに一致した。 */
    data class Hit(
        val pageId: String,
        val pageTitle: String,
        val pageIcon: String?,
        val daily: Boolean,
        val blockId: String?,
        val snippet: String,
    ) {
        fun titleMatch(): Boolean = blockId == null
    }

    fun search(query: String?): List<Hit> {
        val q = if (query == null) "" else query.strip()
        if (q.isEmpty()) return emptyList()
        return db.read { c ->
            val hits = LinkedHashMap<String, Hit>()
            for (h in titleSearch(c, q)) hits.putIfAbsent(h.pageId + ":", h)
            val body = if (q.codePointCount(0, q.length) >= 3) {
                ftsSearch(c, "\"" + q.replace("\"", "\"\"") + "\"", q) // MATCH 用に引用
            } else {
                likeSearch(c, q)
            }
            for (h in body) {
                if (hits.size >= LIMIT) break
                hits.putIfAbsent(h.pageId + ":" + h.blockId, h)
            }
            val all = ArrayList(hits.values)
            all.subList(0, min(LIMIT, all.size))
        }
    }

    companion object {
        const val LIMIT = 20
        private const val SNIPPET_RADIUS = 18

        private fun titleSearch(c: Connection, q: String): List<Hit> {
            return c.prepareStatement(
                """
                SELECT id, title, icon, daily_date FROM pages
                WHERE deleted_at IS NULL AND title LIKE ? ESCAPE '\'
                ORDER BY updated_at DESC LIMIT ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, "%" + escapeLike(q) + "%")
                ps.setInt(2, LIMIT)
                ps.executeQuery().use { rs ->
                    val out = ArrayList<Hit>()
                    while (rs.next()) {
                        out.add(
                            Hit(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getString(4) != null,
                                null,
                                "タイトルに一致",
                            ),
                        )
                    }
                    out
                }
            }
        }

        private fun ftsSearch(c: Connection, match: String, q: String): List<Hit> {
            return blockSearch(
                c,
                """
                SELECT b.id, b.text, p.id, p.title, p.icon, p.daily_date
                FROM blocks_fts f
                JOIN blocks b ON b.rowid = f.rowid
                JOIN pages p ON p.id = b.page_id
                WHERE blocks_fts MATCH ? AND p.deleted_at IS NULL
                ORDER BY f.rank LIMIT ?
                """.trimIndent(),
                match,
                q,
            )
        }

        /** 2文字以下は trigram で探せないので LIKE で探す。 */
        private fun likeSearch(c: Connection, q: String): List<Hit> {
            return blockSearch(
                c,
                """
                SELECT b.id, b.text, p.id, p.title, p.icon, p.daily_date
                FROM blocks b JOIN pages p ON p.id = b.page_id
                WHERE b.text LIKE ? ESCAPE '\' AND p.deleted_at IS NULL
                ORDER BY p.updated_at DESC, b.sort_key LIMIT ?
                """.trimIndent(),
                "%" + escapeLike(q) + "%",
                q,
            )
        }

        private fun blockSearch(c: Connection, sql: String, arg: String, q: String): List<Hit> {
            return c.prepareStatement(sql).use { ps ->
                ps.setString(1, arg)
                ps.setInt(2, LIMIT)
                ps.executeQuery().use { rs ->
                    val out = ArrayList<Hit>()
                    while (rs.next()) {
                        out.add(
                            Hit(
                                rs.getString(3),
                                rs.getString(4),
                                rs.getString(5),
                                rs.getString(6) != null,
                                rs.getString(1),
                                snippet(rs.getString(2), q),
                            ),
                        )
                    }
                    out
                }
            }
        }

        internal fun escapeLike(s: String): String {
            return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        }

        /** 一致した場所の前後だけを切り出す。 */
        internal fun snippet(text: String, q: String): String {
            val flat = text.replace('\n', ' ')
            var at = flat.lowercase(Locale.ROOT).indexOf(q.lowercase(Locale.ROOT))
            if (at < 0) at = 0
            val from = max(0, at - SNIPPET_RADIUS)
            val to = min(flat.length, at + q.length + SNIPPET_RADIUS)
            return (if (from > 0) "…" else "") + flat.substring(from, to) + (if (to < flat.length) "…" else "")
        }
    }
}
