package app.dearnotes.db;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DatabaseTest extends DbTestBase {

    @Test
    void opensInWalModeWithForeignKeys() throws Exception {
        assertEquals("wal", db.read(c -> pragma(c, "journal_mode")));
        assertEquals("1", db.read(c -> pragma(c, "foreign_keys")));
        assertEquals(String.valueOf(Migrations.latestVersion()), db.read(c -> pragma(c, "user_version")));
    }

    @Test
    void reopeningKeepsDataAndDoesNotRemigrate() throws Exception {
        pages.create(null, "残る");
        db.close();
        db = Database.open(dir.resolve("test.db"));
        assertEquals("残る", new PageRepo(db).listAlive().getFirst().title());
    }

    @Test
    void failedWriteRollsBack() throws Exception {
        assertThrows(java.sql.SQLException.class, () -> db.write(c -> {
            try (var s = c.createStatement()) {
                s.executeUpdate("INSERT INTO pages (id, sort_key, created_at, updated_at) VALUES ('x', 1, 0, 0)");
                s.executeUpdate("INSERT INTO pages (id, sort_key, created_at, updated_at) VALUES ('x', 1, 0, 0)");
            }
            return null;
        }));
        assertTrue(pages.get("x").isEmpty());
    }

    private static String pragma(java.sql.Connection c, String name) throws java.sql.SQLException {
        try (var s = c.createStatement(); var rs = s.executeQuery("PRAGMA " + name)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
