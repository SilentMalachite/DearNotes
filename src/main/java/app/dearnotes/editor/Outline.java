package app.dearnotes.editor;

import app.dearnotes.model.Block;
import app.dearnotes.model.BlockType;
import java.util.ArrayList;
import java.util.List;

/**
 * 畳む範囲と目次の計算。UI から切り離してテストする。
 * 見出しの範囲は、同じか上の段の見出しの手前まで。
 * それ以外の行の範囲は、字下げが自分より深い行が続くところまで。
 */
public final class Outline {

    /** 目次の1項目。index はページの中の行の位置。 */
    public record Heading(int index, String blockId, int level, String text) {}

    private Outline() {}

    /** H1〜H3 なら 1〜3、それ以外は 0。 */
    public static int level(BlockType t) {
        return switch (t) {
            case H1 -> 1;
            case H2 -> 2;
            case H3 -> 3;
            default -> 0;
        };
    }

    /** index の行の範囲の終わり（その行は含まない）。子の行がなければ index + 1。 */
    public static int end(List<Block> blocks, int index) {
        Block self = blocks.get(index);
        int lv = level(self.type());
        int i = index + 1;
        if (lv > 0) {
            while (i < blocks.size()) {
                int other = level(blocks.get(i).type());
                if (other > 0 && other <= lv) break;
                i++;
            }
        } else {
            while (i < blocks.size() && blocks.get(i).indent() > self.indent()) i++;
        }
        return i;
    }

    public static boolean hasChildren(List<Block> blocks, int index) {
        return end(blocks, index) > index + 1;
    }

    /** 畳んだ行の範囲に入っていて、画面に出さない行。 */
    public static boolean[] hidden(List<Block> blocks) {
        boolean[] out = new boolean[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) {
            if (!blocks.get(i).collapsed()) continue;
            int e = end(blocks, i);
            for (int j = i + 1; j < e; j++) out[j] = true;
        }
        return out;
    }

    /** index の行を隠している畳んだ行の位置。開くとその行が見える。 */
    public static List<Integer> hiders(List<Block> blocks, int index) {
        var out = new ArrayList<Integer>();
        for (int i = 0; i < index; i++) {
            if (blocks.get(i).collapsed() && end(blocks, i) > index) out.add(i);
        }
        return out;
    }

    public static List<Heading> headings(List<Block> blocks) {
        var out = new ArrayList<Heading>();
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            int lv = level(b.type());
            if (lv > 0) out.add(new Heading(i, b.id(), lv, b.text()));
        }
        return out;
    }

    /** index の行が入っている節の見出しの位置。前に見出しがなければ -1。 */
    public static int currentHeading(List<Block> blocks, int index) {
        for (int i = Math.min(index, blocks.size() - 1); i >= 0; i--) {
            if (level(blocks.get(i).type()) > 0) return i;
        }
        return -1;
    }
}
