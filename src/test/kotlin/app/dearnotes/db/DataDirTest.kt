package app.dearnotes.db

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

internal class DataDirTest {

    @Test
    fun windowsBlankAppDataFallsBackToUserHome() {
        val home = "/home/tester"
        val expectedFallback = Path.of(home, "AppData", "Roaming", "DearNotes")
        assertEquals(expectedFallback, DataDir.resolveDefaultDir("Windows 11", home, null, null))
        assertEquals(expectedFallback, DataDir.resolveDefaultDir("Windows 11", home, "", null))
        assertEquals(expectedFallback, DataDir.resolveDefaultDir("Windows 11", home, "   \t", null))
        assertEquals(
            Path.of("/custom/roaming", "DearNotes"),
            DataDir.resolveDefaultDir("Windows 11", home, "/custom/roaming", null),
        )
    }

    @Test
    fun ensureDirectorySetsOwnerOnlyPermissionsOnPosix(@TempDir tempDir: Path) {
        val created = DataDir.ensureDirectory(tempDir.resolve("nested").resolve("DearNotes"))
        assertTrue(Files.isDirectory(created))
        if (created.fileSystem.supportedFileAttributeViews().contains("posix")) {
            assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(created),
            )
        }
    }
}
