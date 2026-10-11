package app.dearnotes.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** OS のユーザーフォルダの下に置く。アプリを入れ直しても消えない。 */
public final class DataDir {

    private DataDir() {}

    public static Path dataDir() throws IOException {
        String override = System.getProperty("dearnotes.dataDir");
        if (override != null && !override.isBlank()) {
            return Files.createDirectories(Path.of(override));
        }
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        Path dir = switch (os) {
            case String s when s.startsWith("mac") ->
                Path.of(System.getProperty("user.home"), "Library", "Application Support", "DearNotes");
            case String s when s.startsWith("windows") -> {
                String appData = System.getenv("APPDATA");
                yield appData != null
                    ? Path.of(appData, "DearNotes")
                    : Path.of(System.getProperty("user.home"), "AppData", "Roaming", "DearNotes");
            }
            default -> {
                String xdg = System.getenv("XDG_DATA_HOME");
                yield xdg != null && !xdg.isBlank()
                    ? Path.of(xdg, "DearNotes")
                    : Path.of(System.getProperty("user.home"), ".local", "share", "DearNotes");
            }
        };
        return Files.createDirectories(dir);
    }

    public static Path databaseFile() throws IOException {
        return dataDir().resolve("dearnotes.db");
    }
}
