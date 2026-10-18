package app.dearnotes.export

/**
 * Markdown の書き出しで、記号の前後の文字を符号位置で見るための補助。
 * Char.category は BMP の外の文字（絵文字は Unicode の「記号」）を判定できないので、判定は各プラットフォームに任せる。
 */

/** 先頭の文字の符号位置。サロゲートペアは1つにまとめる。 */
internal fun String.firstCodePoint(): Int {
    val high = this[0]
    if (high.isHighSurrogate() && length > 1 && this[1].isLowSurrogate()) return toCodePoint(high, this[1])
    return high.code
}

/** 末尾の文字の符号位置。サロゲートペアは1つにまとめる。 */
internal fun String.lastCodePoint(): Int {
    val low = this[length - 1]
    if (low.isLowSurrogate() && length > 1 && this[length - 2].isHighSurrogate()) return toCodePoint(this[length - 2], low)
    return low.code
}

private fun toCodePoint(high: Char, low: Char): Int = ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + 0x10000

/** Unicode の Zs（空白の区切り）か。 */
internal expect fun isSpaceSeparator(codePoint: Int): Boolean

/** Unicode の P（句読点）か S（記号）か。 */
internal expect fun isPunctuationOrSymbol(codePoint: Int): Boolean
