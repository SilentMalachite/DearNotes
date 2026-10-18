package app.dearnotes.model

import app.dearnotes.isJavaBlank

import kotlinx.datetime.LocalDate

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

    fun displayTitle() = if (title == null || title.isJavaBlank()) "無題" else title

    fun displayIcon(): String {
        if (icon != null && !icon.isJavaBlank()) return icon
        return if (isDaily()) "📅" else "📝"
    }
}
