package app.dearnotes.db

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class DatabaseTest : DbTestBase() {

    @Test
    fun opensInWalModeWithForeignKeys() {
        assertEquals("wal", db.read { c -> pragma(c, "journal_mode") })
        assertEquals("1", db.read { c -> pragma(c, "foreign_keys") })
        assertEquals(Migrations.latestVersion().toString(), db.read { c -> pragma(c, "user_version") })
    }

    @Test
    fun reopeningKeepsDataAndDoesNotRemigrate() {
        pages.create(null, "残る")
        db.close()
        db = Database.open(dir.resolve("test.db"))
        assertEquals("残る", PageRepo(db).listAlive().first().title)
    }

    @Test
    fun failedWriteRollsBack() {
        assertThrows(SQLException::class.java) {
            db.write { c ->
                c.createStatement().use { s ->
                    s.executeUpdate("INSERT INTO pages (id, sort_key, created_at, updated_at) VALUES ('x', 1, 0, 0)")
                    s.executeUpdate("INSERT INTO pages (id, sort_key, created_at, updated_at) VALUES ('x', 1, 0, 0)")
                }
                null
            }
        }
        assertTrue(pages.get("x") == null)
    }

    @Test
    fun upgradingFromV1KeepsBlocksOpenAndSearchable() {
        val file = dir.resolve("v1.db")
        DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath()).use { c ->
            Migrations.apply(c, 1)
            c.createStatement().use { s ->
                s.executeUpdate(
                    "INSERT INTO pages (id, title, sort_key, created_at, updated_at) VALUES ('p', '旅行', 1, 0, 0)",
                )
                s.executeUpdate(
                    """
                    INSERT INTO blocks (id, page_id, type, text, sort_key, updated_at)
                    VALUES ('b', 'p', 'h2', '伏見稲荷へ行く', 1, 0)
                    """.trimIndent(),
                )
            }
        }
        Database.open(file).use { upgraded ->
            val b = BlockRepo(upgraded).listByPage("p").first()
            assertEquals("伏見稲荷へ行く", b.text)
            assertFalse(b.collapsed)
            BlockRepo(upgraded).save(listOf(b.withCollapsed(true)), emptyList())
            assertTrue(BlockRepo(upgraded).listByPage("p").first().collapsed)
            assertEquals(1, SearchRepo(upgraded).search("伏見稲荷").size)
        }
    }

    private fun pragma(c: Connection, name: String): String {
        return c.createStatement().use { s ->
            s.executeQuery("PRAGMA $name").use { rs ->
                rs.next()
                rs.getString(1)
            }
        }
    }
}
