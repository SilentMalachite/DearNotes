package app.dearnotes.ui

import app.dearnotes.db.BlockRepo
import app.dearnotes.db.Database
import app.dearnotes.db.PageRepo
import app.dearnotes.db.SearchRepo
import app.dearnotes.save.AutoSaver

/** 画面の部品が使う、アプリに1つずつあるもの。 */
data class Services(
    val db: Database,
    val pages: PageRepo,
    val blocks: BlockRepo,
    val search: SearchRepo,
    val saver: AutoSaver,
    val openLink: (String) -> Unit,
)
