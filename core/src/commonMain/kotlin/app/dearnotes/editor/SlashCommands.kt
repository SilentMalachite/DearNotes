package app.dearnotes.editor

import app.dearnotes.isJavaWhitespace
import app.dearnotes.strip

import app.dearnotes.model.BlockType

/** 「/」のあとに打った文字でブロックの種類を絞り込む。 */
object SlashCommands {
    data class Command(
        val type: BlockType,
        val label: String,
        val shortcut: String,
        val keywords: List<String>,
    ) {
        internal fun matches(q: String): Boolean {
            if (q.isEmpty()) return true
            val lower = q.lowercase()
            return label.lowercase().contains(lower) || keywords.any { it.startsWith(lower) }
        }
    }

    val ALL: List<Command> = listOf(
        Command(BlockType.PARAGRAPH, BlockType.PARAGRAPH.label, "", listOf("text", "paragraph", "てきすと")),
        Command(BlockType.H1, BlockType.H1.label, "#", listOf("h1", "heading", "みだし")),
        Command(BlockType.H2, BlockType.H2.label, "##", listOf("h2", "heading", "みだし")),
        Command(BlockType.H3, BlockType.H3.label, "###", listOf("h3", "heading", "みだし")),
        Command(BlockType.BULLET, BlockType.BULLET.label, "-", listOf("bullet", "list", "かじょうがき")),
        Command(BlockType.NUMBERED, BlockType.NUMBERED.label, "1.", listOf("numbered", "list", "ばんごう")),
        Command(BlockType.TODO, BlockType.TODO.label, "[]", listOf("todo", "check", "task", "たすく")),
        Command(BlockType.QUOTE, BlockType.QUOTE.label, ">", listOf("quote", "いんよう")),
        Command(BlockType.CODE, BlockType.CODE.label, "```", listOf("code", "こーど")),
        Command(BlockType.DIVIDER, BlockType.DIVIDER.label, "---", listOf("divider", "hr", "line", "くぎり")),
    )

    fun filter(query: String?): List<Command> {
        val q = query?.strip() ?: ""
        return ALL.filter { it.matches(q) }
    }

    /** ブロックの文字列が「/語句」だけなら語句を返す。そうでなければ null。 */
    fun queryOf(text: String): String? {
        if (!text.startsWith("/") || text.contains("\n")) return null
        val q = text.substring(1)
        return if (q.any { it.isJavaWhitespace() }) null else q
    }
}
