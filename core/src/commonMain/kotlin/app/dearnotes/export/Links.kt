package app.dearnotes.export

/** 書き出すファイルから、ほかのページのファイルへのリンク先。 */
fun interface Links {

    /** pageId のページのファイルへの相対パス（/ でつなぐ）。書き出していないページなら null。 */
    fun href(pageId: String): String?

    companion object {
        /** 書き出さないページへのリンクは、ふだんの文字になる。 */
        val NONE: Links = Links { _ -> null }
    }
}
