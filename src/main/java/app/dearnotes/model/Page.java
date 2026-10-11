package app.dearnotes.model;

import java.time.LocalDate;

/** サイドバーに出るページ。dailyDate があれば日記。 */
public record Page(
        String id,
        String parentId,
        String title,
        String icon,
        LocalDate dailyDate,
        double sortKey,
        long createdAt,
        long updatedAt,
        Long deletedAt) {

    public boolean isDaily() {
        return dailyDate != null;
    }

    public boolean isTrashed() {
        return deletedAt != null;
    }

    public String displayTitle() {
        return title == null || title.isBlank() ? "無題" : title;
    }

    public String displayIcon() {
        if (icon != null && !icon.isBlank()) return icon;
        return isDaily() ? "📅" : "📝";
    }
}
