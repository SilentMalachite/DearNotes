package app.dearnotes.ui;

import app.dearnotes.db.BlockRepo;
import app.dearnotes.db.Database;
import app.dearnotes.db.PageRepo;
import app.dearnotes.db.SearchRepo;
import app.dearnotes.save.AutoSaver;
import java.util.function.Consumer;

/** 画面の部品が使う、アプリに1つずつあるもの。 */
public record Services(
        Database db,
        PageRepo pages,
        BlockRepo blocks,
        SearchRepo search,
        AutoSaver saver,
        Consumer<String> openLink) {
}
