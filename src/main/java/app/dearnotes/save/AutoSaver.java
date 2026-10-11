package app.dearnotes.save;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import app.dearnotes.model.Block;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 変わったブロックを覚えておき、入力が止まってから1本の書き込みスレッドでまとめて書く。
 * changed / deleted は FX スレッドから、flushNow は db-writer スレッドで動く。
 */
public final class AutoSaver implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(AutoSaver.class.getName());

    private final BlockStore store;
    private final Consumer<Runnable> ui;
    private final long delayMillis;
    private final long retryMillis;
    private final ScheduledExecutorService writer;

    private final Object lock = new Object();
    private final Map<String, Block> pending = new LinkedHashMap<>();
    private final Set<String> pendingDeletes = new LinkedHashSet<>();
    private ScheduledFuture<?> scheduled;
    private volatile SaveStatus status = SaveStatus.SAVED;
    private volatile Consumer<SaveStatus> listener = s -> {};

    public AutoSaver(BlockStore store, Consumer<Runnable> ui) {
        this(store, ui, 500, 5_000);
    }

    public AutoSaver(BlockStore store, Consumer<Runnable> ui, long delayMillis, long retryMillis) {
        this.store = store;
        this.ui = ui;
        this.delayMillis = delayMillis;
        this.retryMillis = retryMillis;
        this.writer = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "db-writer");
            t.setDaemon(true);
            return t;
        });
    }

    public void setListener(Consumer<SaveStatus> listener) {
        this.listener = listener == null ? s -> {} : listener;
    }

    public SaveStatus status() {
        return status;
    }

    public void changed(Block block) {
        synchronized (lock) {
            pending.put(block.id(), block);
            pendingDeletes.remove(block.id());
            reschedule(delayMillis);
            if (status != SaveStatus.FAILED) setStatus(SaveStatus.DIRTY);
        }
    }

    public void deleted(String blockId) {
        synchronized (lock) {
            pending.remove(blockId);
            pendingDeletes.add(blockId);
            reschedule(delayMillis);
            if (status != SaveStatus.FAILED) setStatus(SaveStatus.DIRTY);
        }
    }

    /** まだ書いていない変更を今すぐ書き、終わるまで待つ。ページを開き直す前に使う。 */
    public boolean flushAndWait(long timeout, TimeUnit unit) {
        synchronized (lock) {
            if (scheduled != null) scheduled.cancel(false);
            scheduled = null;
        }
        try {
            writer.submit(this::flushNow).get(timeout, unit);
            return !hasPending();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            LOG.log(Level.WARNING, "flush に失敗", e);
            return false;
        }
    }

    public boolean hasPending() {
        synchronized (lock) {
            return !pending.isEmpty() || !pendingDeletes.isEmpty();
        }
    }

    /** ウィンドウを閉じるとき: 残りを書いてからスレッドを止める。 */
    @Override
    public void close() {
        if (writer.isShutdown()) return;
        boolean ok = flushAndWait(10, TimeUnit.SECONDS);
        if (!ok) LOG.warning("保存できなかった変更を残して終了する");
        writer.shutdownNow();
    }

    private void reschedule(long delay) {
        if (writer.isShutdown()) return;
        if (scheduled != null) scheduled.cancel(false);
        scheduled = writer.schedule(this::flushNow, delay, MILLISECONDS);
    }

    private void flushNow() {
        Map<String, Block> batch;
        Set<String> deletes;
        synchronized (lock) {
            if (pending.isEmpty() && pendingDeletes.isEmpty()) return;
            batch = new LinkedHashMap<>(pending);
            deletes = new LinkedHashSet<>(pendingDeletes);
            pending.clear();
            pendingDeletes.clear();
        }
        setStatus(SaveStatus.SAVING);
        try {
            store.save(batch.values(), deletes);
            // changed() と同じロックの中で知らせるので、最後に届く状態が正しくなる
            synchronized (lock) {
                setStatus(pending.isEmpty() && pendingDeletes.isEmpty() ? SaveStatus.SAVED : SaveStatus.DIRTY);
            }
        } catch (SQLException | RuntimeException e) {
            LOG.log(Level.WARNING, "保存に失敗。" + retryMillis + "ms 後に再試行する", e);
            synchronized (lock) {
                // 失敗している間に入った新しい入力は上書きしない
                batch.forEach(pending::putIfAbsent);
                for (String id : deletes) {
                    if (!pending.containsKey(id)) pendingDeletes.add(id);
                }
                reschedule(retryMillis);
                setStatus(SaveStatus.FAILED);
            }
        }
    }

    private void setStatus(SaveStatus s) {
        status = s;
        Consumer<SaveStatus> l = listener;
        ui.accept(() -> l.accept(s));
    }
}
