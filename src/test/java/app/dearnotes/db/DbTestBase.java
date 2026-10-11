package app.dearnotes.db;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

abstract class DbTestBase {

    @TempDir
    Path dir;

    Database db;
    PageRepo pages;
    BlockRepo blocks;
    SearchRepo search;

    @BeforeEach
    void openDb() throws Exception {
        db = Database.open(dir.resolve("test.db"));
        pages = new PageRepo(db);
        blocks = new BlockRepo(db);
        search = new SearchRepo(db);
    }

    @AfterEach
    void closeDb() throws Exception {
        db.close();
    }
}
