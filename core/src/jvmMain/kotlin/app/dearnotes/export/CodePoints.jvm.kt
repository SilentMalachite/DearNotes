package app.dearnotes.export

internal actual fun isSpaceSeparator(codePoint: Int): Boolean =
    Character.getType(codePoint) == Character.SPACE_SEPARATOR.toInt()

internal actual fun isPunctuationOrSymbol(codePoint: Int): Boolean =
    when (Character.getType(codePoint)) {
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt(),
        Character.MATH_SYMBOL.toInt(),
        Character.CURRENCY_SYMBOL.toInt(),
        Character.MODIFIER_SYMBOL.toInt(),
        Character.OTHER_SYMBOL.toInt(),
        -> true
        else -> false
    }
