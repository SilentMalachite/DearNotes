package app.dearnotes.model

import java.time.LocalDate

/** サイドバーに出るページ。dailyDate があれば日記。 */
data class Page(
    val id: String,
    val parentId: String?,
    val title: String?,
    val icon: String?,
    val dailyDate: LocalDate?,
    val sortKey: Double,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
) {
    fun isDaily() = dailyDate != null

    fun isTrashed() = deletedAt != null

    fun displayTitle() = if (title.isNullOrBlank()) "無題" else title

    fun displayIcon(): String {
        if (!icon.isNullOrBlank()) return icon
        return if (isDaily()) "📅" else "📝"
    }
}
