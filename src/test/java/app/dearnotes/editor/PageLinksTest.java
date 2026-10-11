package app.dearnotes.editor;

import static org.junit.jupiter.api.Assertions.*;

import app.dearnotes.editor.PageLinks.Query;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PageLinksTest {

    @Test
    void findsQueryAfterBrackets() {
        assertEquals(Optional.of(new Query(3, "京")), PageLinks.queryAt("次は [[京", 6));
    }

    @Test
    void japaneseBracketsWork() {
        assertEquals(Optional.of(new Query(0, "京都")), PageLinks.queryAt("「「京都", 4));
        assertEquals(Optional.of(new Query(0, "")), PageLinks.queryAt("［［", 2));
    }

    @Test
    void emptyQueryRightAfterBrackets() {
        assertEquals(Optional.of(new Query(1, "")), PageLinks.queryAt("a[[", 3));
    }

    @Test
    void looksOnlyBeforeCaret() {
        assertEquals(Optional.of(new Query(0, "京都")), PageLinks.queryAt("[[京都 ab", 4));
    }

    @Test
    void closedOrBrokenQueryIsNotAQuery() {
        assertEquals(Optional.empty(), PageLinks.queryAt("[[京都]] へ", 7));
        assertEquals(Optional.empty(), PageLinks.queryAt("[[京\n都", 5));
        assertEquals(Optional.empty(), PageLinks.queryAt("[京", 2));
        assertEquals(Optional.empty(), PageLinks.queryAt("", 0));
    }
}
