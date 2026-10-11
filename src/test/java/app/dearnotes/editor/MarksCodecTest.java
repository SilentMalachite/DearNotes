package app.dearnotes.editor;

import static org.junit.jupiter.api.Assertions.*;

import app.dearnotes.model.Mark;
import app.dearnotes.model.Mark.Style;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarksCodecTest {

    @Test
    void encodesMarksAsCompactJson() {
        var marks = List.of(new Mark(0, 5, Style.BOLD), new Mark(6, 9, Style.LINK));
        assertEquals("[[0,5,\"bold\"],[6,9,\"link\"]]", MarksCodec.encode(marks));
    }

    @Test
    void emptyListEncodesToNull() {
        assertNull(MarksCodec.encode(List.of()));
    }

    @Test
    void roundTrips() {
        var marks = List.of(new Mark(1, 3, Style.ITALIC), new Mark(2, 8, Style.STRIKE), new Mark(0, 1, Style.CODE));
        assertEquals(marks, MarksCodec.decode(MarksCodec.encode(marks)));
    }

    @Test
    void decodesNullAndBlankAsEmpty() {
        assertEquals(List.of(), MarksCodec.decode(null));
        assertEquals(List.of(), MarksCodec.decode(""));
        assertEquals(List.of(), MarksCodec.decode("[]"));
    }

    @Test
    void decodeToleratesWhitespaceAndSkipsUnknownStyles() {
        assertEquals(List.of(new Mark(0, 2, Style.BOLD)),
            MarksCodec.decode(" [ [0, 2, \"bold\"], [1,2,\"sparkle\"] ] "));
    }
}
