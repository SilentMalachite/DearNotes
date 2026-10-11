package app.dearnotes.export;

/** 書き出すファイルから、ほかのページのファイルへのリンク先。 */
@FunctionalInterface
public interface Links {

    /** 書き出さないページへのリンクは、ふだんの文字になる。 */
    Links NONE = pageId -> null;

    /** pageId のページのファイルへの相対パス（/ でつなぐ）。書き出していないページなら null。 */
    String href(String pageId);
}
