package app.dearnotes.save

import app.dearnotes.model.Block

/** AutoSaver の書き込み先。テストで差し替えられるように分ける。 */
fun interface BlockStore {
    fun save(upserts: Collection<Block>, deletes: Collection<String>)
}
