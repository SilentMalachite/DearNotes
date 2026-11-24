package app.dearnotes.save

import app.dearnotes.model.Block
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Consumer
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 変わったブロックを覚えておき、入力が止まってから1本の書き込みスレッドでまとめて書く。
 * changed / deleted は FX スレッドから、flushNow は db-writer スレッドで動く。
 */
class AutoSaver(
    private val store: BlockStore,
    private val ui: (Runnable) -> Unit,
    private val delayMillis: Long,
    private val retryMillis: Long,
) : AutoCloseable {

    private val writer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "db-writer").apply { isDaemon = true }
    }

    private val lock = Any()
    private val pending = LinkedHashMap<String, Block>()
    private val pendingDeletes = LinkedHashSet<String>()
    private val statusQueue = ArrayDeque<SaveStatus>()
    private var scheduled: ScheduledFuture<*>? = null
    private var closed = false
    private var drainingStatus = false

    @Volatile
    private var state: SaveStatus = SaveStatus.SAVED

    @Volatile
    private var listener: Consumer<SaveStatus> = Consumer { }

    constructor(store: BlockStore, ui: (Runnable) -> Unit) : this(store, ui, 500L, 5_000L)

    fun setListener(listener: Consumer<SaveStatus>?) {
        this.listener = listener ?: Consumer { }
    }

    fun status(): SaveStatus = state

    fun changed(block: Block) {
        var notify = false
        synchronized(lock) {
            if (closed) return
            pending[block.id] = block
            pendingDeletes.remove(block.id)
            rescheduleLocked(delayMillis)
            if (state != SaveStatus.FAILED) {
                recordStatusLocked(SaveStatus.DIRTY)
                notify = true
            }
        }
        if (notify) dispatchStatuses()
    }

    fun deleted(blockId: String) {
        var notify = false
        synchronized(lock) {
            if (closed) return
            pending.remove(blockId)
            pendingDeletes.add(blockId)
            rescheduleLocked(delayMillis)
            if (state != SaveStatus.FAILED) {
                recordStatusLocked(SaveStatus.DIRTY)
                notify = true
            }
        }
        if (notify) dispatchStatuses()
    }

    /** まだ書いていない変更を今すぐ書き、終わるまで待つ。ページを開き直す前に使う。 */
    fun flushAndWait(timeout: Long, unit: TimeUnit): Boolean {
        val future = synchronized(lock) {
            if (closed || writer.isShutdown) return !hasPendingLocked()
            scheduled?.cancel(false)
            scheduled = null
            try {
                writer.submit(Runnable { flushNow() })
            } catch (_: RejectedExecutionException) {
                return !hasPendingLocked()
            }
        }
        return awaitFlush(future, timeout, unit)
    }

    fun hasPending(): Boolean {
        return synchronized(lock) {
            hasPendingLocked()
        }
    }

    /** ウィンドウを閉じるとき: 残りを書いてからスレッドを止める。 */
    override fun close() {
        val finalFlush = synchronized(lock) {
            if (closed) return
            closed = true
            scheduled?.cancel(false)
            scheduled = null
            try {
                writer.submit(Runnable { flushNow() })
            } catch (_: RejectedExecutionException) {
                null
            }
        }
        val ok = if (finalFlush != null) {
            awaitFlush(finalFlush, CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } else {
            !hasPending()
        }
        if (ok) {
            writer.shutdown()
        } else {
            LOG.warning("保存できなかった変更を残して終了する")
            writer.shutdownNow()
        }
        try {
            if (!writer.awaitTermination(TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                writer.shutdownNow()
                if (!writer.awaitTermination(TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    LOG.warning("保存スレッドの終了待機がタイムアウトした")
                }
            }
        } catch (_: InterruptedException) {
            writer.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    private fun awaitFlush(future: Future<*>, timeout: Long, unit: TimeUnit): Boolean {
        return try {
            future.get(timeout, unit)
            !hasPending()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (e: ExecutionException) {
            LOG.log(Level.WARNING, "flush に失敗", e)
            false
        } catch (e: TimeoutException) {
            LOG.log(Level.WARNING, "flush に失敗", e)
            false
        }
    }

    private fun hasPendingLocked(): Boolean = !pending.isEmpty() || !pendingDeletes.isEmpty()

    private fun rescheduleLocked(delay: Long) {
        if (closed || writer.isShutdown) return
        scheduled?.cancel(false)
        scheduled = try {
            writer.schedule(Runnable { flushNow() }, delay, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            null
        }
    }

    private fun flushNow() {
        val taken = synchronized(lock) {
            if (pending.isEmpty() && pendingDeletes.isEmpty()) {
                null
            } else {
                val batch = LinkedHashMap(pending)
                val deletes = LinkedHashSet(pendingDeletes)
                pending.clear()
                pendingDeletes.clear()
                // changed() / deleted() と同じロック内で SAVING を確定し、通知順の逆転を防ぐ
                recordStatusLocked(SaveStatus.SAVING)
                batch to deletes
            }
        } ?: return
        dispatchStatuses()
        val (batch, deletes) = taken
        try {
            store.save(batch.values, deletes)
            // changed() と同じロックの中で状態を決めるので、最後に届く状態が正しくなる
            synchronized(lock) {
                recordStatusLocked(
                    if (pending.isEmpty() && pendingDeletes.isEmpty()) SaveStatus.SAVED else SaveStatus.DIRTY,
                )
            }
            dispatchStatuses()
        } catch (e: Exception) {
            // Kotlin には検査例外がないので、SQLException 以外の検査例外も来る
            keepAndRetry(batch, deletes, e)
        }
    }

    private fun keepAndRetry(batch: Map<String, Block>, deletes: Set<String>, e: Exception) {
        LOG.log(Level.WARNING, "保存に失敗。${retryMillis}ms 後に再試行する", e)
        synchronized(lock) {
            // 失敗している間に入った新しい入力や削除は上書きしない
            for ((id, block) in batch) {
                if (!pendingDeletes.contains(id)) pending.putIfAbsent(id, block)
            }
            for (id in deletes) {
                if (!pending.containsKey(id)) pendingDeletes.add(id)
            }
            rescheduleLocked(retryMillis)
            recordStatusLocked(SaveStatus.FAILED)
        }
        dispatchStatuses()
    }

    private fun recordStatusLocked(s: SaveStatus) {
        state = s
        statusQueue.addLast(s)
    }

    /** ロック外でキューを順番に流し、状態決定順の維持とデッドロック回避を両立する。 */
    private fun dispatchStatuses() {
        synchronized(lock) {
            if (drainingStatus) return
            drainingStatus = true
        }
        while (true) {
            val next = synchronized(lock) {
                if (statusQueue.isEmpty()) {
                    drainingStatus = false
                    return
                }
                statusQueue.removeFirst()
            }
            try {
                val l = listener
                ui(Runnable { l.accept(next) })
            } catch (t: Throwable) {
                synchronized(lock) {
                    drainingStatus = false
                }
                throw t
            }
        }
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(AutoSaver::class.java.name)
        private const val CLOSE_TIMEOUT_SECONDS = 10L
        private const val TERMINATION_TIMEOUT_SECONDS = 5L
    }
}
