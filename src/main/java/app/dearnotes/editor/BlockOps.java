package app.dearnotes.editor;

import app.dearnotes.model.Block;
import app.dearnotes.model.BlockType;
import app.dearnotes.model.SortKeys;
import java.util.List;

/** ページの中のブロックの並びについての計算。UI から切り離してテストする。 */
public final class BlockOps {

    private BlockOps() {}

    /** index の番号付きリストの番号。同じ字下げで続く番号付きを数える。 */
    public static int listNumber(List<Block> blocks, int index) {
        Block self = blocks.get(index);
        int n = 1;
        for (int i = index - 1; i >= 0; i--) {
            Block b = blocks.get(i);
            if (b.indent() > self.indent()) continue;          // 子の行は飛ばす
            if (b.indent() < self.indent()) break;             // 親に出たら終わり
            if (b.type() != BlockType.NUMBERED) break;
            n++;
        }
        return n;
    }

    /** index の直後に入れるキー。間がなければ null（全体を振り直す）。 */
    public static Double keyAfter(List<Block> blocks, int index) {
        Double prev = index >= 0 ? blocks.get(index).sortKey() : null;
        Double next = index + 1 < blocks.size() ? blocks.get(index + 1).sortKey() : null;
        return SortKeys.between(prev, next);
    }

    /** from を取り除いてから、残りの並びで insertAt の位置に入れるためのキー。 */
    public static Double keyForMove(List<Block> blocks, int from, int insertAt) {
        var rest = new java.util.ArrayList<>(blocks);
        rest.remove(from);
        int target = Math.clamp(insertAt, 0, rest.size());
        return keyAfter(rest, target - 1);
    }
}
