package app.dearnotes.ui;

import static org.junit.jupiter.api.Assertions.*;

import app.dearnotes.model.Page;
import java.util.List;
import org.junit.jupiter.api.Test;

class SidebarTest {

    private static Page page(String id, double sortKey) {
        return new Page(id, null, id, null, null, sortKey, 0, 0, null);
    }

    private final Page a = page("A", 1024);
    private final Page b = page("B", 2048);
    private final List<Page> alive = List.of(a, b);

    @Test
    void dropBeforeFirstSiblingGoesAboveIt() {
        Double key = Sidebar.keyFor(alive, null, b, a, Sidebar.Drop.BEFORE);
        assertNotNull(key);
        assertTrue(key < a.sortKey());
    }

    @Test
    void dropAfterLastSiblingGoesBelowIt() {
        Double key = Sidebar.keyFor(alive, null, a, b, Sidebar.Drop.AFTER);
        assertNotNull(key);
        assertTrue(key > b.sortKey());
    }

    @Test
    void dropBetweenSiblings() {
        Page c = page("C", 3072);
        Double key = Sidebar.keyFor(List.of(a, b, c), null, c, b, Sidebar.Drop.BEFORE);
        assertNotNull(key);
        assertTrue(a.sortKey() < key && key < b.sortKey());
    }
}
