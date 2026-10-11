package app.dearnotes.db;

import app.dearnotes.editor.MarksCodec;
import app.dearnotes.model.Block;
import app.dearnotes.model.BlockType;
import app.dearnotes.save.BlockStore;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

public final class BlockRepo implements BlockStore {

    private final Database db;

    public BlockRepo(Database db) {
        this.db = db;
    }

    public List<Block> listByPage(String pageId) throws SQLException {
        return db.read(c -> {
            try (var ps = c.prepareStatement("""
                    SELECT id, page_id, type, text, marks, checked, indent, sort_key
                    FROM blocks WHERE page_id = ? ORDER BY sort_key, id""")) {
                ps.setString(1, pageId);
                try (var rs = ps.executeQuery()) {
                    var out = new ArrayList<Block>();
                    while (rs.next()) {
                        out.add(new Block(
                            rs.getString(1), rs.getString(2), BlockType.fromDb(rs.getString(3)),
                            rs.getString(4), MarksCodec.decode(rs.getString(5)),
                            rs.getInt(6) != 0, rs.getInt(7), rs.getDouble(8)));
                    }
                    return out;
                }
            }
        });
    }

    /**
     * 変わったブロックを書き、消えたブロックを消す。1トランザクション。
     * ページが先に消えていたブロックは書かずに捨てる（外部キーで失敗し続けないように）。
     */
    @Override
    public void save(Collection<Block> upserts, Collection<String> deletes) throws SQLException {
        if (upserts.isEmpty() && deletes.isEmpty()) return;
        db.write(c -> {
            long now = System.currentTimeMillis();
            var pages = new HashSet<String>();
            try (var ps = c.prepareStatement("""
                    INSERT INTO blocks (id, page_id, type, text, marks, checked, indent, sort_key, updated_at)
                    SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?
                    WHERE EXISTS (SELECT 1 FROM pages WHERE id = ?)
                    ON CONFLICT (id) DO UPDATE SET
                      page_id = excluded.page_id, type = excluded.type, text = excluded.text,
                      marks = excluded.marks, checked = excluded.checked, indent = excluded.indent,
                      sort_key = excluded.sort_key, updated_at = excluded.updated_at""")) {
                for (Block b : upserts) {
                    ps.setString(1, b.id());
                    ps.setString(2, b.pageId());
                    ps.setString(3, b.type().dbName());
                    ps.setString(4, b.text());
                    ps.setString(5, MarksCodec.encode(b.marks()));
                    ps.setInt(6, b.checked() ? 1 : 0);
                    ps.setInt(7, b.indent());
                    ps.setDouble(8, b.sortKey());
                    ps.setLong(9, now);
                    ps.setString(10, b.pageId());
                    ps.addBatch();
                    pages.add(b.pageId());
                }
                ps.executeBatch();
            }
            try (var ps = c.prepareStatement("DELETE FROM blocks WHERE id = ?")) {
                for (String id : deletes) {
                    ps.setString(1, id);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (var ps = c.prepareStatement("UPDATE pages SET updated_at = ? WHERE id = ?")) {
                for (String pageId : pages) {
                    ps.setLong(1, now);
                    ps.setString(2, pageId);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }
}
