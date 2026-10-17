package app.dearnotes.model

/** 行内の装飾。start は含み、end は含まない。target は PAGE のときだけ、リンク先のページの id。 */
data class Mark private constructor(
    val start: Int,
    val end: Int,
    val style: Style,
    val target: String?,
) {
    enum class Style {
        BOLD, ITALIC, STRIKE, CODE, LINK, PAGE;

        fun key() = name.lowercase()

        companion object {
            fun fromKey(s: String) = valueOf(s.uppercase())
        }
    }

    /** 種類とリンク先はそのままで、範囲を替える。 */
    fun withRange(start: Int, end: Int) = invoke(start, end, style, target)

    companion object {
        operator fun invoke(start: Int, end: Int, style: Style, target: String? = null): Mark {
            require(start >= 0 && end >= start) { "bad range: $start..$end" }
            val hasTarget = target != null && target.isNotBlank()
            require((style == Style.PAGE) == hasTarget) { "bad target: $style $target" }
            return Mark(start, end, style, target)
        }
    }
}
