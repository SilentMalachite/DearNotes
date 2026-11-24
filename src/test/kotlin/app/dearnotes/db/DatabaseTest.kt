package app.dearnotes.db

import java.io.IOException
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
        assertEquals("5000", db.read { c -> pragma(c, "busy_timeout") })
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
            }
        }
        assertTrue(pages.get("x") == null)
    }

    @Test
    fun checkedExceptionAlsoRollsBack() {
        assertThrows(IOException::class.java) {
            db.write { c ->
                c.createStatement().use { s ->
                    s.executeUpdate("INSERT INTO pages (id, sort_key, created_at, updated_at) VALUES ('y', 1, 0, 0)")
                }
                throw IOException("途中で失敗")
            }
        }
        assertTrue(pages.get("y") == null)
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

    @Test
    fun migrationsRejectInvalidCurrentAndTargetVersions() {
        val file = dir.resolve("invalid-version.db")
        DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath()).use { c ->
            c.createStatement().use { s -> s.execute("PRAGMA user_version = -1") }
            assertThrows(SQLException::class.java) { Migrations.apply(c) }

            c.createStatement().use { s -> s.execute("PRAGMA user_version = 0") }
            assertThrows(IllegalArgumentException::class.java) { Migrations.apply(c, -1) }
            assertThrows(IllegalArgumentException::class.java) {
                Migrations.apply(c, Migrations.latestVersion() + 1)
            }
        }
    }

    @Test
    fun openFailsCleanlyOnNewerDatabaseVersionWithoutLeakingConnection() {
        val file = dir.resolve("future.db")
        DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath()).use { c ->
            c.createStatement().use { s -> s.execute("PRAGMA user_version = 999") }
        }
        assertThrows(SQLException::class.java) { Database.open(file) }
        // 接続リークがなければ user_version を戻してすぐ開き直せる
        DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath()).use { c ->
            c.createStatement().use { s -> s.execute("PRAGMA user_version = 0") }
        }
        Database.open(file).use { reopened ->
            assertEquals(Migrations.latestVersion().toString(), reopened.read { c -> pragma(c, "user_version") })
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
