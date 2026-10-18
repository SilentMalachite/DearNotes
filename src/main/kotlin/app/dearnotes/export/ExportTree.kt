package app.dearnotes.export

import app.dearnotes.strip

import app.dearnotes.model.Block
import app.dearnotes.model.Page
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.sql.SQLException
import java.text.Normalizer
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.Locale
import java.util.regex.Pattern

/**
 * ページと子ページを、フォルダの中に1ページ1ファイルで書く。
 * いつも新しいフォルダを作り、既にあるファイルは上書きしない。
 * 書き出すページどうしのリンクは、相対パスのリンクにする。
 */
object ExportTree {

    /** ページのブロックを読む。BlockRepo::listByPage を渡す。 */
    fun interface Blocks {
        fun of(pageId: String): List<Block>
    }

    /** 途中で失敗した。書けたファイルは残す。 */
    class ExportException internal constructor(
        written: List<Path>,
        cause: Exception,
    ) : Exception(cause.message, cause) {
        private val writtenFiles: List<Path> = java.util.List.copyOf(written)

        fun written(): List<Path> = writtenFiles
    }

    // macOS と Windows のどちらかで使えない文字
    private val BAD: Pattern = Pattern.compile("""[\\/:*?"<>|\p{Cntrl}]""")
    private val RESERVED: Set<String> = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )
    private const val MAX_NAME = 100

    /** ページ名をファイル名にする。拡張子は付けない。 */
    fun fileName(p: Page): String {
        var name = BAD.matcher(p.displayTitle()).replaceAll("_").strip()
        // パスの長さの上限を越えないよう切る。文字の途中では切らない
        if (name.codePointCount(0, name.length) > MAX_NAME) {
            name = name.substring(0, name.offsetByCodePoints(0, MAX_NAME))
        }
        name = name.replace(Regex("[. ]+$"), "") // Windows は末尾の . と空白を消す（切った後にも）
        if (name.startsWith(".")) name = "_" + name.substring(1) // 隠しファイルにしない
        // Windows は「CON.txt」のように拡張子が付いても予約名と見る
        val stem = name.split(Regex("""\."""), limit = 2)[0].strip()
        if (name.isEmpty() || RESERVED.contains(stem.uppercase(Locale.ROOT))) name = "_" + name
        return name
    }

    /**
     * dir の中に root の名前のフォルダを作り、root と子孫を書く。
     * alive はゴミ箱にないページ全部。書いたファイルを、書いた順に返す。
     * 書き出すページどうしのリンクは、相対パスのリンクにする。
     */
    fun write(dir: Path, root: Page, alive: List<Page>, blocks: Blocks): List<Path> {
        val children = HashMap<String, MutableList<Page>>()
        val byId = HashMap<String, Page>()
        for (p in alive) {
            byId[p.id] = p
            val parentId = p.parentId
            if (parentId != null) children.getOrPut(parentId) { ArrayList() }.add(p)
        }
        byId[root.id] = root
        children.values.forEach { list -> list.sortBy { page -> page.sortKey } }

        val written = ArrayList<Path>()
        try {
            val top = dir.resolve(unique(existing(dir), fileName(root)))
            // 先に全ページのファイルの場所を決める。リンクを相対パスで書くため
            val files = LinkedHashMap<String, Path>()
            val used = HashSet<String>()
            files[root.id] = top.resolve(unique(used, fileName(root)) + ".md")
            plan(top, root, children, used, files, HashSet(setOf(root.id)))

            Files.createDirectory(top)
            for ((id, file) in files) {
                val parent = file.parent!!
                if (!Files.isDirectory(parent)) Files.createDirectory(parent)
                writePage(file, byId[id]!!, blocks, links(file, files), written)
            }
        } catch (e: IOException) {
            throw ExportException(written, e)
        } catch (e: SQLException) {
            throw ExportException(written, e)
        }
        return written
    }

    /** 子ページのファイルの場所を、書く順に files に入れる。孫があれば、子と同じ名前のフォルダに入れる。 */
    private fun plan(
        folder: Path,
        parent: Page,
        children: Map<String, List<Page>>,
        used: MutableSet<String>,
        files: MutableMap<String, Path>,
        seen: MutableSet<String>,
    ) {
        for (p in children[parent.id] ?: emptyList()) {
            if (!seen.add(p.id)) continue // 親子が輪になっていても止まる
            val name = unique(used, fileName(p))
            files[p.id] = folder.resolve(name + ".md")
            if (children.containsKey(p.id)) {
                plan(folder.resolve(name), p, children, HashSet(), files, seen)
            }
        }
    }

    /** from のファイルから、書き出すほかのページのファイルへの相対パス。 */
    private fun links(from: Path, files: Map<String, Path>): Links {
        val folder = from.parent
        return Links { id ->
            val to = files[id]
            if (to == null) {
                null
            } else {
                val parts = ArrayList<String>()
                for (part in folder!!.relativize(to)) parts.add(part.toString())
                parts.joinToString("/")
            }
        }
    }

    private fun writePage(file: Path, p: Page, blocks: Blocks, links: Links, written: MutableList<Path>) {
        val md = MarkdownWriter.write(p, blocks.of(p.id), links)
        Files.writeString(file, md, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)
        written.add(file)
    }

    /**
     * used にない名前。「京都」→「京都 (2)」。
     * ページは「名前.md」と、子があれば「名前」のフォルダを使うので、両方を取っておく。
     */
    internal fun unique(used: MutableSet<String>, name: String): String {
        var candidate = name
        var n = 2
        while (used.contains(key(candidate)) || used.contains(key(candidate + ".md"))) {
            candidate = name + " (" + n + ")"
            n++
        }
        used.add(key(candidate))
        used.add(key(candidate + ".md"))
        return candidate
    }

    /**
     * file に text を書く。同じフォルダの一時ファイルに書いてから置き換えるので、
     * 途中で失敗しても前のファイルは残る。
     */
    fun writeReplacing(file: Path, text: String) {
        val tmp = Files.createTempFile(file.toAbsolutePath().parent!!, ".dearnotes-", ".tmp")
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8)
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** 大文字と小文字、濁点の表し方の違いは同じ名前と見る（macOS と Windows に合わせる）。 */
    private fun key(name: String): String {
        return Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    }

    /** dir に今ある名前。拡張子 .md も外して並べる。 */
    private fun existing(dir: Path): MutableSet<String> {
        val out = HashSet<String>()
        Files.list(dir).use { s ->
            s.forEach { p ->
                val n = p.fileName!!.toString()
                out.add(key(n))
                if (n.endsWith(".md")) out.add(key(n.substring(0, n.length - 3)))
            }
        }
        return out
    }
}
