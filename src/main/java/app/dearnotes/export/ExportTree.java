package app.dearnotes.export;

import app.dearnotes.model.Block;
import app.dearnotes.model.Page;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ページと子ページを、フォルダの中に1ページ1ファイルで書く。
 * いつも新しいフォルダを作り、既にあるファイルは上書きしない。
 */
public final class ExportTree {

    /** ページのブロックを読む。BlockRepo::listByPage を渡す。 */
    @FunctionalInterface
    public interface Blocks {
        List<Block> of(String pageId) throws SQLException;
    }

    /** 途中で失敗した。書けたファイルは残す。 */
    public static final class ExportException extends Exception {
        private final transient List<Path> written;

        ExportException(List<Path> written, Exception cause) {
            super(cause.getMessage(), cause);
            this.written = List.copyOf(written);
        }

        public List<Path> written() {
            return written;
        }
    }

    // macOS と Windows のどちらかで使えない文字
    private static final Pattern BAD = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");
    private static final Set<String> RESERVED = Set.of("CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
    private static final int MAX_NAME = 100;

    private ExportTree() {}

    /** ページ名をファイル名にする。拡張子は付けない。 */
    public static String fileName(Page p) {
        String name = BAD.matcher(p.displayTitle()).replaceAll("_").strip();
        // パスの長さの上限を越えないよう切る。文字の途中では切らない
        if (name.codePointCount(0, name.length()) > MAX_NAME) {
            name = name.substring(0, name.offsetByCodePoints(0, MAX_NAME));
        }
        name = name.replaceAll("[. ]+$", "");           // Windows は末尾の . と空白を消す（切った後にも）
        if (name.startsWith(".")) name = "_" + name.substring(1);   // 隠しファイルにしない
        // Windows は「CON.txt」のように拡張子が付いても予約名と見る
        String stem = name.split("\\.", 2)[0].strip();
        if (name.isEmpty() || RESERVED.contains(stem.toUpperCase(Locale.ROOT))) name = "_" + name;
        return name;
    }

    /**
     * dir の中に root の名前のフォルダを作り、root と子孫を書く。
     * alive はゴミ箱にないページ全部。書いたファイルを、書いた順に返す。
     */
    public static List<Path> write(Path dir, Page root, List<Page> alive, Blocks blocks) throws ExportException {
        Map<String, List<Page>> children = new HashMap<>();
        for (Page p : alive) {
            if (p.parentId() != null) children.computeIfAbsent(p.parentId(), k -> new ArrayList<>()).add(p);
        }
        children.values().forEach(l -> l.sort(Comparator.comparingDouble(Page::sortKey)));

        var written = new ArrayList<Path>();
        try {
            Path top = dir.resolve(unique(existing(dir), fileName(root)));
            Files.createDirectory(top);
            var used = new HashSet<String>();
            String rootName = unique(used, fileName(root));
            writePage(top.resolve(rootName + ".md"), root, blocks, written);
            writeChildren(top, root, children, used, blocks, written, new HashSet<>(Set.of(root.id())));
        } catch (IOException | SQLException e) {
            throw new ExportException(written, e);
        }
        return written;
    }

    /** 子ページを folder に書く。孫があれば、子と同じ名前のフォルダを作って入れる。 */
    private static void writeChildren(Path folder, Page parent, Map<String, List<Page>> children,
                                      Set<String> used, Blocks blocks, List<Path> written, Set<String> seen)
            throws IOException, SQLException {
        for (Page p : children.getOrDefault(parent.id(), List.of())) {
            if (!seen.add(p.id())) continue;   // 親子が輪になっていても止まる
            String name = unique(used, fileName(p));
            writePage(folder.resolve(name + ".md"), p, blocks, written);
            if (children.containsKey(p.id())) {
                Path sub = Files.createDirectory(folder.resolve(name));
                writeChildren(sub, p, children, new HashSet<>(), blocks, written, seen);
            }
        }
    }

    private static void writePage(Path file, Page p, Blocks blocks, List<Path> written)
            throws IOException, SQLException {
        String md = MarkdownWriter.write(p, blocks.of(p.id()));
        Files.writeString(file, md, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        written.add(file);
    }

    /**
     * used にない名前。「京都」→「京都 (2)」。
     * ページは「名前.md」と、子があれば「名前」のフォルダを使うので、両方を取っておく。
     */
    static String unique(Set<String> used, String name) {
        String candidate = name;
        for (int n = 2; used.contains(key(candidate)) || used.contains(key(candidate + ".md")); n++) {
            candidate = name + " (" + n + ")";
        }
        used.add(key(candidate));
        used.add(key(candidate + ".md"));
        return candidate;
    }

    /**
     * file に text を書く。同じフォルダの一時ファイルに書いてから置き換えるので、
     * 途中で失敗しても前のファイルは残る。
     */
    public static void writeReplacing(Path file, String text) throws IOException {
        Path tmp = Files.createTempFile(file.toAbsolutePath().getParent(), ".dearnotes-", ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** 大文字と小文字、濁点の表し方の違いは同じ名前と見る（macOS と Windows に合わせる）。 */
    private static String key(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    /** dir に今ある名前。拡張子 .md も外して並べる。 */
    private static Set<String> existing(Path dir) throws IOException {
        var out = new HashSet<String>();
        try (var s = Files.list(dir)) {
            s.forEach(p -> {
                String n = p.getFileName().toString();
                out.add(key(n));
                if (n.endsWith(".md")) out.add(key(n.substring(0, n.length() - 3)));
            });
        }
        return out;
    }
}
