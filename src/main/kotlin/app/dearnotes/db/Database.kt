package app.dearnotes.db

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
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
            } catch (e: SQLException) {
                writer.rollback()
                throw e
            } catch (e: RuntimeException) {
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
        readLock.lock()
        try {
            reader.close()
            try {
                writer.createStatement().use { s ->
                    s.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                }
            } finally {
                writer.close()
            }
        } finally {
            readLock.unlock()
            writeLock.unlock()
        }
    }

    companion object {
        fun open(file: Path): Database {
            val w = connect(file)
            try {
                Migrations.apply(w)
                return Database(w, connect(file))
            } catch (e: SQLException) {
                w.close()
                throw e
            }
        }

        private fun connect(file: Path): Connection {
            val conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath())
            conn.createStatement().use { s ->
                s.execute("PRAGMA journal_mode = WAL")
                s.execute("PRAGMA synchronous = NORMAL")
                s.execute("PRAGMA foreign_keys = ON")
                s.execute("PRAGMA busy_timeout = 5000")
            }
            return conn
        }
    }
}
