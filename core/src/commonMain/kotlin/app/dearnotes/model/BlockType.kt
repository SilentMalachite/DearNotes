package app.dearnotes.model

/** ブロックの種類。DB には小文字の名前で入る。 */
enum class BlockType(val label: String) {
    PARAGRAPH("テキスト"),
    H1("見出し 1"),
    H2("見出し 2"),
    H3("見出し 3"),
    BULLET("箇条書き"),
    NUMBERED("番号付きリスト"),
    TODO("ToDo"),
    QUOTE("引用"),
    CODE("コード"),
    DIVIDER("区切り線");

    fun dbName() = name.lowercase()

    /** Enter で次に作るブロックの種類。リスト系は同じ種類を続ける。 */
    fun next() = when (this) {
        BULLET, NUMBERED, TODO -> this
        else -> PARAGRAPH
    }

    fun isList() = this == BULLET || this == NUMBERED || this == TODO

    fun hasText() = this != DIVIDER

    companion object {
        fun fromDb(name: String) = valueOf(name.uppercase())
    }
}
