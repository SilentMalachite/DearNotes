package app.dearnotes.save;

public enum SaveStatus {
    /** すべてディスクにある。 */
    SAVED("保存済み"),
    /** 入力中。少し待ってから書く。 */
    DIRTY("編集中"),
    /** 書き込みスレッドが書いている。 */
    SAVING("保存中…"),
    /** 書けなかった。変更はメモリに残り、あとで再試行する。 */
    FAILED("保存できません · 再試行中");

    private final String label;

    SaveStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
