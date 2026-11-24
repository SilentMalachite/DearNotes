package app.dearnotes.db

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.locks.ReentrantLock

/**
 * SQLite の1ファイル。書き込み用と読み出し用に接続を分ける。
 * WAL なので、書き込み中も読み出しは止まらない。
 */
class Database private constructor(
    private val writer: Connection,
    private val reader: Connection,
) : AutoCloseable {

    private val writeLock = ReentrantLock()
    private val readLock = ReentrantLock()

    /** 1トランザクションで書く。失敗したら巻き戻して投げ直す。 */
    fun <T> write(work: (Connection) -> T): T {
        writeLock.lock()
        try {
            writer.autoCommit = false
            try {
                val result = work(writer)
                writer.commit()
                return result
            } catch (e: Throwable) {
                // Kotlin には検査例外がないので、何が飛んできても巻き戻す。
                // 巻き戻さないと、finally の autoCommit = true で途中までの書き込みが確定する。
                writer.rollback()
                throw e
            } finally {
                writer.autoCommit = true
            }
        } finally {
            writeLock.unlock()
        }
    }

    fun <T> read(work: (Connection) -> T): T {
        readLock.lock()
        try {
            return work(reader)
        } finally {
            readLock.unlock()
        }
    }

    override fun close() {
        writeLock.lock()
        try {
            readLock.lock()
            try {
                var firstError: Throwable? = null
                try {
                    reader.close()
                } catch (t: Throwable) {
                    firstError = t
                }
                try {
                    writer.createStatement().use { s ->
                        s.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                    }
                } catch (t: Throwable) {
                    if (firstError == null) firstError = t else firstError.addSuppressed(t)
                } finally {
                    try {
                        writer.close()
                    } catch (t: Throwable) {
                        if (firstError == null) firstError = t else firstError.addSuppressed(t)
                    }
                }
                if (firstError != null) throw firstError
            } finally {
                readLock.unlock()
            }
        } finally {
            writeLock.unlock()
        }
    }

    companion object {
        fun open(file: Path): Database {
            val w = connect(file)
            try {
                Migrations.apply(w)
                return Database(w, connect(file))
            } catch (t: Throwable) {
                try {
                    w.close()
                } catch (closeErr: Throwable) {
                    t.addSuppressed(closeErr)
                }
                throw t
            }
        }

        private fun connect(file: Path): Connection {
            val conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath())
            try {
                conn.createStatement().use { s ->
                    // WAL 切替時の SQLITE_BUSY を避けるため busy_timeout を先頭で設定する
                    s.execute("PRAGMA busy_timeout = 5000")
                    s.execute("PRAGMA journal_mode = WAL")
                    s.execute("PRAGMA synchronous = NORMAL")
                    s.execute("PRAGMA foreign_keys = ON")
                }
                return conn
            } catch (t: Throwable) {
                try {
                    conn.close()
                } catch (closeErr: Throwable) {
                    t.addSuppressed(closeErr)
                }
                throw t
            }
        }
    }
}
