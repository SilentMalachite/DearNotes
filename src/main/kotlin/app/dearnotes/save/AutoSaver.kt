package app.dearnotes.save

import app.dearnotes.model.Block
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.function.Consumer
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
    private var scheduled: ScheduledFuture<*>? = null

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
        synchronized(lock) {
            pending[block.id] = block
            pendingDeletes.remove(block.id)
            reschedule(delayMillis)
            if (state != SaveStatus.FAILED) setStatus(SaveStatus.DIRTY)
        }
    }

    fun deleted(blockId: String) {
        synchronized(lock) {
            pending.remove(blockId)
            pendingDeletes.add(blockId)
            reschedule(delayMillis)
            if (state != SaveStatus.FAILED) setStatus(SaveStatus.DIRTY)
        }
    }

    /** まだ書いていない変更を今すぐ書き、終わるまで待つ。ページを開き直す前に使う。 */
    fun flushAndWait(timeout: Long, unit: TimeUnit): Boolean {
        synchronized(lock) {
            scheduled?.cancel(false)
            scheduled = null
        }
        try {
            writer.submit(Runnable { flushNow() }).get(timeout, unit)
            return !hasPending()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } catch (e: ExecutionException) {
            LOG.log(Level.WARNING, "flush に失敗", e)
            return false
        } catch (e: TimeoutException) {
            LOG.log(Level.WARNING, "flush に失敗", e)
            return false
        }
    }

    fun hasPending(): Boolean {
        return synchronized(lock) {
            !pending.isEmpty() || !pendingDeletes.isEmpty()
        }
    }

    /** ウィンドウを閉じるとき: 残りを書いてからスレッドを止める。 */
    override fun close() {
        if (writer.isShutdown) return
        val ok = flushAndWait(10L, TimeUnit.SECONDS)
        if (!ok) LOG.warning("保存できなかった変更を残して終了する")
        writer.shutdownNow()
    }

    private fun reschedule(delay: Long) {
        if (writer.isShutdown) return
        scheduled?.cancel(false)
        scheduled = writer.schedule(Runnable { flushNow() }, delay, TimeUnit.MILLISECONDS)
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
                batch to deletes
            }
        } ?: return
        val (batch, deletes) = taken
        setStatus(SaveStatus.SAVING)
        try {
            store.save(batch.values, deletes)
            // changed() と同じロックの中で知らせるので、最後に届く状態が正しくなる
            synchronized(lock) {
                setStatus(
                    if (pending.isEmpty() && pendingDeletes.isEmpty()) SaveStatus.SAVED else SaveStatus.DIRTY,
                )
            }
        } catch (e: Exception) {
            // Kotlin には検査例外がないので、SQLException 以外の検査例外も来る
            keepAndRetry(batch, deletes, e)
        }
    }

    private fun keepAndRetry(batch: Map<String, Block>, deletes: Set<String>, e: Exception) {
        LOG.log(Level.WARNING, "保存に失敗。${retryMillis}ms 後に再試行する", e)
        synchronized(lock) {
            // 失敗している間に入った新しい入力は上書きしない
            for ((id, block) in batch) {
                pending.putIfAbsent(id, block)
            }
            for (id in deletes) {
                if (!pending.containsKey(id)) pendingDeletes.add(id)
            }
            reschedule(retryMillis)
            setStatus(SaveStatus.FAILED)
        }
    }

    private fun setStatus(s: SaveStatus) {
        state = s
        val l = listener
        ui(Runnable { l.accept(s) })
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(AutoSaver::class.java.name)
    }
}
