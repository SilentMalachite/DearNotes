package app.dearnotes

/**
 * Java の Character.isWhitespace と同じ判定。
 * Kotlin の isWhitespace は NBSP（U+00A0, U+2007, U+202F）も空白にするが、こちらは空白にしない。
 */
internal fun Char.isJavaWhitespace(): Boolean = when (this) {
    ' ', ' ', ' ' -> false
    '\t', '\n', '\u000B', '\u000C', '\r', '\u001C', '\u001D', '\u001E', '\u001F' -> true
    else -> category == CharCategory.SPACE_SEPARATOR ||
        category == CharCategory.LINE_SEPARATOR ||
        category == CharCategory.PARAGRAPH_SEPARATOR
}

/** Java の String.isBlank。NBSP だけの文字列は空にしない。 */
internal fun CharSequence.isJavaBlank(): Boolean = all { it.isJavaWhitespace() }

/** Java の String.strip。 */
internal fun String.strip(): String = trim { it.isJavaWhitespace() }

/** Java の String.stripLeading。先頭の空白だけ外す。 */
internal fun String.stripLeading(): String = trimStart { it.isJavaWhitespace() }

/** Java の String.stripTrailing。末尾の空白だけ外す。 */
internal fun String.stripTrailing(): String = trimEnd { it.isJavaWhitespace() }
