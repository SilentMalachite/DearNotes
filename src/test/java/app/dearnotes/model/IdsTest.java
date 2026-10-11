package app.dearnotes.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void producesVersion7Uuids() {
        UUID id = UUID.fromString(Ids.uuidV7());
        assertEquals(7, id.version());
        assertEquals(2, id.variant());
    }

    @Test
    void idsSortByCreationTime() throws InterruptedException {
        String a = Ids.uuidV7();
        Thread.sleep(2);
        String b = Ids.uuidV7();
        assertTrue(a.compareTo(b) < 0, a + " < " + b);
    }

    @Test
    void idsAreUnique() {
        assertNotEquals(Ids.uuidV7(), Ids.uuidV7());
    }
}
