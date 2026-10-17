package app.dearnotes.model

/** ページの1行。DB の blocks テーブルの1行に対応する。collapsed は子の行を畳んでいるか。 */
@ConsistentCopyVisibility // copy() も private にして、invoke の正規化を飛ばせないようにする
data class Block private constructor(
    val id: String,
    val pageId: String,
    val type: BlockType,
    val text: String,
    val marks: List<Mark>,
    val checked: Boolean,
    val indent: Int,
    val sortKey: Double,
    val collapsed: Boolean,
) {
    fun withType(t: BlockType) = invoke(id, pageId, t, text, marks, checked, indent, sortKey, collapsed)

    fun withContent(t: String?, m: List<Mark>?) =
        invoke(id, pageId, type, t, m, checked, indent, sortKey, collapsed)

    fun withChecked(c: Boolean) = invoke(id, pageId, type, text, marks, c, indent, sortKey, collapsed)

    fun withIndent(i: Int) = invoke(id, pageId, type, text, marks, checked, i, sortKey, collapsed)

    fun withSortKey(k: Double) = invoke(id, pageId, type, text, marks, checked, indent, k, collapsed)

    fun withCollapsed(c: Boolean) = invoke(id, pageId, type, text, marks, checked, indent, sortKey, c)

    companion object {
        const val MAX_INDENT = 6

        operator fun invoke(
            id: String,
            pageId: String,
            type: BlockType,
            text: String?,
            marks: List<Mark>?,
            checked: Boolean,
            indent: Int,
            sortKey: Double,
            collapsed: Boolean = false,
        ) = Block(
            id,
            pageId,
            type,
            text ?: "",
            marks?.toList() ?: emptyList(),
            checked,
            indent.coerceIn(0, MAX_INDENT),
            sortKey,
            collapsed,
        )

        fun empty(pageId: String, type: BlockType, sortKey: Double) =
            invoke(Ids.uuidV7(), pageId, type, "", emptyList(), false, 0, sortKey)
    }
}
