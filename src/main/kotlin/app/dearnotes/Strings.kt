package app.dearnotes

/** Java の String.strip。Kotlin の String 型からは直接見えない。 */
internal fun String.strip(): String = (this as java.lang.String).strip()

/** Java の String.stripLeading。先頭の空白だけ外す。 */
internal fun String.stripLeading(): String = (this as java.lang.String).stripLeading()

/** Java の String.stripTrailing。末尾の空白だけ外す。 */
internal fun String.stripTrailing(): String = (this as java.lang.String).stripTrailing()
