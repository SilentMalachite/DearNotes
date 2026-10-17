package app.dearnotes.db

import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir

internal abstract class DbTestBase {

    @TempDir
    lateinit var dir: Path

    lateinit var db: Database
    lateinit var pages: PageRepo
    lateinit var blocks: BlockRepo
    lateinit var search: SearchRepo

    @BeforeEach
    fun openDb() {
        db = Database.open(dir.resolve("test.db"))
        pages = PageRepo(db)
        blocks = BlockRepo(db)
        search = SearchRepo(db)
    }

    @AfterEach
    fun closeDb() {
        db.close()
    }
}
