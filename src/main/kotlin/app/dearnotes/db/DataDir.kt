package app.dearnotes.db

import app.dearnotes.isJavaBlank

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale

/** OS のユーザーフォルダの下に置く。アプリを入れ直しても消えない。 */
object DataDir {

    private val OWNER_ONLY_DIR = PosixFilePermissions.fromString("rwx------")

    fun dataDir(): Path {
        val override = System.getProperty("dearnotes.dataDir")
        if (override != null && !override.isJavaBlank()) {
            return ensureDirectory(Path.of(override))
        }
        val dir = resolveDefaultDir(
            osName = System.getProperty("os.name"),
            userHome = System.getProperty("user.home"),
            appData = System.getenv("APPDATA"),
            xdgDataHome = System.getenv("XDG_DATA_HOME"),
        )
        return ensureDirectory(dir)
    }

    fun databaseFile(): Path = dataDir().resolve("dearnotes.db")

    internal fun resolveDefaultDir(
        osName: String,
        userHome: String,
        appData: String?,
        xdgDataHome: String?,
    ): Path {
        val os = osName.lowercase(Locale.ROOT)
        return when {
            os.startsWith("mac") ->
                Path.of(userHome, "Library", "Application Support", "DearNotes")
            os.startsWith("windows") -> {
                if (appData != null && !appData.isJavaBlank()) {
                    Path.of(appData, "DearNotes")
                } else {
                    Path.of(userHome, "AppData", "Roaming", "DearNotes")
                }
            }
            else -> {
                if (xdgDataHome != null && !xdgDataHome.isJavaBlank()) {
                    Path.of(xdgDataHome, "DearNotes")
                } else {
                    Path.of(userHome, ".local", "share", "DearNotes")
                }
            }
        }
    }

    internal fun ensureDirectory(dir: Path): Path {
        // POSIX 環境では新規作成ディレクトリに所有者専用権限 (0700) を設定し、非 POSIX 環境ではスキップする
        if (dir.fileSystem.supportedFileAttributeViews().contains("posix")) {
            return try {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIR))
            } catch (_: UnsupportedOperationException) {
                Files.createDirectories(dir)
            }
        }
        return Files.createDirectories(dir)
    }
}
