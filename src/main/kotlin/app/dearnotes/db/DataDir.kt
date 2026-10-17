package app.dearnotes.db

import app.dearnotes.isJavaBlank

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** OS のユーザーフォルダの下に置く。アプリを入れ直しても消えない。 */
object DataDir {

    fun dataDir(): Path {
        val override = System.getProperty("dearnotes.dataDir")
        if (override != null && !override.isJavaBlank()) {
            return Files.createDirectories(Path.of(override))
        }
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        val dir = when {
            os.startsWith("mac") ->
                Path.of(System.getProperty("user.home"), "Library", "Application Support", "DearNotes")
            os.startsWith("windows") -> {
                val appData = System.getenv("APPDATA")
                if (appData != null) {
                    Path.of(appData, "DearNotes")
                } else {
                    Path.of(System.getProperty("user.home"), "AppData", "Roaming", "DearNotes")
                }
            }
            else -> {
                val xdg = System.getenv("XDG_DATA_HOME")
                if (xdg != null && !xdg.isJavaBlank()) {
                    Path.of(xdg, "DearNotes")
                } else {
                    Path.of(System.getProperty("user.home"), ".local", "share", "DearNotes")
                }
            }
        }
        return Files.createDirectories(dir)
    }

    fun databaseFile(): Path = dataDir().resolve("dearnotes.db")
}
