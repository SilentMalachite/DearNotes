package app.dearnotes.editor;

import static app.dearnotes.model.Mark.Style.*;
import static org.junit.jupiter.api.Assertions.*;

import app.dearnotes.model.Mark;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarksTest {

    @Test
    void sliceClipsAndShifts() {
        var marks = List.of(new Mark(0, 4, BOLD), new Mark(6, 10, ITALIC));
        assertEquals(List.of(new Mark(0, 1, BOLD)), Marks.slice(marks, 3, 5));
        assertEquals(List.of(new Mark(1, 5, ITALIC)), Marks.slice(marks, 5, 20));
    }

    @Test
    void concatShiftsSecondPart() {
        var a = List.of(new Mark(0, 2, BOLD));
        var b = List.of(new Mark(0, 3, ITALIC));
        assertEquals(List.of(new Mark(0, 2, BOLD), new Mark(4, 7, ITALIC)), Marks.concat(a, 4, b));
    }

    @Test
    void concatMergesTouchingSameStyle() {
        var a = List.of(new Mark(1, 3, BOLD));
        var b = List.of(new Mark(0, 2, BOLD));
        assertEquals(List.of(new Mark(1, 5, BOLD)), Marks.concat(a, 3, b));
    }

    @Test
    void splitThenConcatRestoresOriginal() {
        var marks = List.of(new Mark(0, 3, BOLD), new Mark(2, 8, STRIKE));
        var left = Marks.slice(marks, 0, 5);
        var right = Marks.slice(marks, 5, 10);
        assertEquals(Marks.normalize(marks), Marks.concat(left, 5, right));
    }

    @Test
    void adjacentLinksToDifferentPagesStaySeparate() {
        var a = new Mark(0, 2, PAGE, "a");
        var b = new Mark(2, 4, PAGE, "b");
        assertEquals(List.of(a, b), Marks.normalize(List.of(b, a)));
        assertEquals(List.of(new Mark(0, 4, PAGE, "a")), Marks.normalize(List.of(a, new Mark(2, 4, PAGE, "a"))));
    }

    @Test
    void sliceAndConcatKeepTheTarget() {
        var link = new Mark(1, 5, PAGE, "p");
        assertEquals(List.of(new Mark(0, 2, PAGE, "p")), Marks.slice(List.of(link), 3, 8));
        assertEquals(List.of(new Mark(4, 8, PAGE, "p")), Marks.concat(List.of(), 3, List.of(link)));
    }

    @Test
    void pageNeedsTargetAndOtherStylesMustNotHaveOne() {
        assertThrows(IllegalArgumentException.class, () -> new Mark(0, 1, PAGE));
        assertThrows(IllegalArgumentException.class, () -> new Mark(0, 1, BOLD, "x"));
    }
}
