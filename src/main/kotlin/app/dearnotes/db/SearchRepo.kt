package app.dearnotes.db

import app.dearnotes.strip

import java.sql.Connection
import java.util.ArrayList
import java.util.LinkedHashMap
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
            // FTS5 のクエリパーサは NUL 文字で途中終了するため、NUL を含む場合は LIKE にフォールバックする
            val body = if (q.codePointCount(0, q.length) >= 3 && !q.contains('\u0000')) {
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

        /** 一致した場所の前後だけを切り出す。文字長が変わる小文字化やサロゲート分断を避ける。 */
        internal fun snippet(text: String, q: String): String {
            val flat = text.replace('\n', ' ')
            // lowercase() の文字長変化（例: İ → i + 結合ドット）による位置ずれを防ぐため元文字列上で探す
            val found = flat.indexOf(q, ignoreCase = true)
            var matchStart = if (found >= 0) found else 0
            var matchEnd = if (found >= 0) {
                found + q.length
            } else {
                offsetForward(flat, 0, q.codePointCount(0, q.length))
            }
            // サロゲートペアの途中を指していれば境界へ寄せる
            if (matchStart > 0 && matchStart < flat.length &&
                Character.isSurrogatePair(flat[matchStart - 1], flat[matchStart])
            ) {
                matchStart--
            }
            if (matchEnd > 0 && matchEnd < flat.length &&
                Character.isSurrogatePair(flat[matchEnd - 1], flat[matchEnd])
            ) {
                matchEnd++
            }
            val from = offsetBackward(flat, matchStart, SNIPPET_RADIUS)
            val to = offsetForward(flat, matchEnd, SNIPPET_RADIUS)
            return (if (from > 0) "…" else "") + flat.substring(from, to) + (if (to < flat.length) "…" else "")
        }

        /** start からコードポイント単位で前へ進んだ UTF-16 インデックスを返す。 */
        private fun offsetBackward(s: String, start: Int, codePoints: Int): Int {
            var i = start
            var remaining = codePoints
            while (i > 0 && remaining > 0) {
                i = if (i >= 2 && Character.isSurrogatePair(s[i - 2], s[i - 1])) i - 2 else i - 1
                remaining--
            }
            return i
        }

        /** start からコードポイント単位で後ろへ進んだ UTF-16 インデックスを返す。 */
        private fun offsetForward(s: String, start: Int, codePoints: Int): Int {
            var i = start
            var remaining = codePoints
            while (i < s.length && remaining > 0) {
                i = if (i + 1 < s.length && Character.isSurrogatePair(s[i], s[i + 1])) i + 2 else i + 1
                remaining--
            }
            return i
        }
    }
}
