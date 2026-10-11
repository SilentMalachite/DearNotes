package app.dearnotes.save;

import static org.junit.jupiter.api.Assertions.*;

import app.dearnotes.model.Block;
import app.dearnotes.model.BlockType;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AutoSaverTest {

    /** 書かれた内容を覚え、指定回数だけ失敗する。 */
    static final class FakeStore implements BlockStore {
        final List<List<Block>> saves = new CopyOnWriteArrayList<>();
        final List<List<String>> deletes = new CopyOnWriteArrayList<>();
        final AtomicInteger failuresLeft = new AtomicInteger();

        @Override
        public void save(Collection<Block> upserts, Collection<String> deleted) throws SQLException {
            if (failuresLeft.getAndDecrement() > 0) throw new SQLException("disk full");
            saves.add(List.copyOf(upserts));
            deletes.add(List.copyOf(deleted));
        }
    }

    final FakeStore store = new FakeStore();
    final List<SaveStatus> statuses = new CopyOnWriteArrayList<>();
    AutoSaver saver;

    @AfterEach
    void tearDown() {
        if (saver != null) saver.close();
    }

    private AutoSaver newSaver(long delay, long retry) {
        saver = new AutoSaver(store, Runnable::run, delay, retry);
        saver.setListener(statuses::add);
        return saver;
    }

    private static Block block(String text) {
        return Block.empty("page", BlockType.PARAGRAPH, 1).withContent(text, List.of());
    }

    @Test
    void batchesRapidEditsIntoOneWrite() throws Exception {
        newSaver(100, 1000);
        Block b = block("a");
        saver.changed(b);
        saver.changed(b.withContent("ab", List.of()));
        saver.changed(b.withContent("abc", List.of()));
        waitUntil(() -> saver.status() == SaveStatus.SAVED && !store.saves.isEmpty());
        assertEquals(1, store.saves.size());
        assertEquals("abc", store.saves.getFirst().getFirst().text());
        assertEquals(List.of(SaveStatus.DIRTY, SaveStatus.DIRTY, SaveStatus.DIRTY, SaveStatus.SAVING, SaveStatus.SAVED),
            statuses);
    }

    @Test
    void deleteCancelsPendingUpsert() {
        newSaver(10_000, 1000);
        Block b = block("x");
        saver.changed(b);
        saver.deleted(b.id());
        assertTrue(saver.flushAndWait(1, TimeUnit.SECONDS));
        assertEquals(List.of(), store.saves.getFirst());
        assertEquals(List.of(b.id()), store.deletes.getFirst());
    }

    @Test
    void failureKeepsChangesAndRetries() throws Exception {
        store.failuresLeft.set(1);
        newSaver(10, 50);
        Block b = block("消えない");
        saver.changed(b);
        waitUntil(() -> statuses.contains(SaveStatus.FAILED));
        waitUntil(() -> saver.status() == SaveStatus.SAVED);
        assertEquals("消えない", store.saves.getFirst().getFirst().text());
    }

    @Test
    void retryDoesNotOverwriteNewerEdit() throws Exception {
        store.failuresLeft.set(1);
        newSaver(10, 200);
        Block b = block("古い");
        saver.changed(b);
        waitUntil(() -> saver.status() == SaveStatus.FAILED);
        saver.changed(b.withContent("新しい", List.of()));
        waitUntil(() -> saver.status() == SaveStatus.SAVED);
        List<String> written = new ArrayList<>();
        store.saves.forEach(batch -> batch.forEach(x -> written.add(x.text())));
        assertEquals(List.of("新しい"), written);
    }

    @Test
    void closeFlushesPendingChanges() {
        newSaver(60_000, 1000);
        saver.changed(block("終了直前"));
        saver.close();
        assertEquals("終了直前", store.saves.getFirst().getFirst().text());
        assertFalse(saver.hasPending());
    }

    private static void waitUntil(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out");
            Thread.sleep(5);
        }
    }
}
