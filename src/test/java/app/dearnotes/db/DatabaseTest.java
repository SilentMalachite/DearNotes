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

    @Test
    void upgradingFromV1KeepsBlocksOpenAndSearchable() throws Exception {
        var file = dir.resolve("v1.db");
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath())) {
            Migrations.apply(c, 1);
            try (var s = c.createStatement()) {
                s.executeUpdate("INSERT INTO pages (id, title, sort_key, created_at, updated_at) VALUES ('p', '旅行', 1, 0, 0)");
                s.executeUpdate("""
                    INSERT INTO blocks (id, page_id, type, text, sort_key, updated_at)
                    VALUES ('b', 'p', 'h2', '伏見稲荷へ行く', 1, 0)""");
            }
        }
        try (var upgraded = Database.open(file)) {
            var b = new BlockRepo(upgraded).listByPage("p").getFirst();
            assertEquals("伏見稲荷へ行く", b.text());
            assertFalse(b.collapsed());
            new BlockRepo(upgraded).save(java.util.List.of(b.withCollapsed(true)), java.util.List.of());
            assertTrue(new BlockRepo(upgraded).listByPage("p").getFirst().collapsed());
            assertEquals(1, new SearchRepo(upgraded).search("伏見稲荷").size());
        }
    }

    private static String pragma(java.sql.Connection c, String name) throws java.sql.SQLException {
        try (var s = c.createStatement(); var rs = s.executeQuery("PRAGMA " + name)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
