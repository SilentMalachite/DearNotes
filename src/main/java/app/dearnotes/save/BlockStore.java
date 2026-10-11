package app.dearnotes.save;

import app.dearnotes.model.Block;
import java.sql.SQLException;
import java.util.Collection;

/** AutoSaver の書き込み先。テストで差し替えられるように分ける。 */
@FunctionalInterface
public interface BlockStore {
    void save(Collection<Block> upserts, Collection<String> deletes) throws SQLException;
}
