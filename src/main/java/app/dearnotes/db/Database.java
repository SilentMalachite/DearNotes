package app.dearnotes.db;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SQLite の1ファイル。書き込み用と読み出し用に接続を分ける。
 * WAL なので、書き込み中も読み出しは止まらない。
 */
public final class Database implements AutoCloseable {

    private final Connection writer;
    private final Connection reader;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final ReentrantLock readLock = new ReentrantLock();

    private Database(Connection writer, Connection reader) {
        this.writer = writer;
        this.reader = reader;
    }

    public static Database open(Path file) throws SQLException {
        Connection w = connect(file);
        try {
            Migrations.apply(w);
            return new Database(w, connect(file));
        } catch (SQLException e) {
            w.close();
            throw e;
        }
    }

    private static Connection connect(Path file) throws SQLException {
        var conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (var s = conn.createStatement()) {
            s.execute("PRAGMA journal_mode = WAL");
            s.execute("PRAGMA synchronous = NORMAL");
            s.execute("PRAGMA foreign_keys = ON");
            s.execute("PRAGMA busy_timeout = 5000");
        }
        return conn;
    }

    /** 1トランザクションで書く。失敗したら巻き戻して投げ直す。 */
    public <T> T write(SqlFunction<T> work) throws SQLException {
        writeLock.lock();
        try {
            writer.setAutoCommit(false);
            try {
                T result = work.apply(writer);
                writer.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                writer.rollback();
                throw e;
            } finally {
                writer.setAutoCommit(true);
            }
        } finally {
            writeLock.unlock();
        }
    }

    public <T> T read(SqlFunction<T> work) throws SQLException {
        readLock.lock();
        try {
            return work.apply(reader);
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void close() throws SQLException {
        writeLock.lock();
        readLock.lock();
        try {
            reader.close();
            try (var s = writer.createStatement()) {
                s.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            } finally {
                writer.close();
            }
        } finally {
            readLock.unlock();
            writeLock.unlock();
        }
    }
}
