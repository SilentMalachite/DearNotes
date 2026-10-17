package app.dearnotes.model

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdsTest {
    @Test
    fun producesVersion7Uuids() {
        val id = UUID.fromString(Ids.uuidV7())
        assertEquals(7, id.version())
        assertEquals(2, id.variant())
    }

    @Test
    fun idsSortByCreationTime() {
        val a = Ids.uuidV7()
        Thread.sleep(2)
        val b = Ids.uuidV7()
        assertTrue(a < b, "$a < $b")
    }

    @Test
    fun idsAreUnique() {
        assertNotEquals(Ids.uuidV7(), Ids.uuidV7())
    }
}
