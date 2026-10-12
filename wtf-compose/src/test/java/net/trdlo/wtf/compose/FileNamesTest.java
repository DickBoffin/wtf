package net.trdlo.wtf.compose;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** A title or a base name as a file name that stays in its folder. */
class FileNamesTest {

    @Test
    @DisplayName("letters of any alphabet are kept, and a plain name is left alone")
    void plainNamesPass() {
        assertEquals("Plzeň a okolí", FileNames.safe("Plzeň a okolí", "print"));
        assertEquals("print", FileNames.safe("print", "x"));
    }

    @Test
    @DisplayName("what Windows refuses becomes an underscore")
    void refusedCharacters() {
        assertEquals("1_25 000", FileNames.safe("1:25 000", "print"));
        assertEquals("Brno_Jih", FileNames.safe("Brno/Jih", "print"));
        assertEquals("a_b_c_d_e_f_g", FileNames.safe("a<b>c\"d|e?f*g", "print"));
        assertEquals("tab_here", FileNames.safe("tab\there", "print"));
    }

    @Test
    @DisplayName("a path cannot climb out of the folder it is resolved against")
    void noPathEscapes() {
        final Path dir = Path.of("out").toAbsolutePath();
        for (final String typed : new String[]{"../x", "..\\..\\x", "/etc/passwd", "C:\\Windows\\x", ".."}) {
            final String name = FileNames.safe(typed, "print");
            assertEquals(dir, dir.resolve(name + ".pdf").normalize().getParent(), typed);
        }
    }

    @Test
    @DisplayName("what Windows would trim or take for a device is changed")
    void windowsQuirks() {
        assertEquals("map", FileNames.safe("map. . ", "print"));
        assertEquals("_con", FileNames.safe("con", "print"));
        assertEquals("_LPT1.backup", FileNames.safe("LPT1.backup", "print"));
        assertEquals("console", FileNames.safe("console", "print"));
    }

    @Test
    @DisplayName("nothing usable is the fallback, and a long title is shortened")
    void fallbackAndLength() {
        assertEquals("print", FileNames.safe("", "print"));
        assertEquals("print", FileNames.safe("  . ", "print"));
        assertTrue(FileNames.safe("x".repeat(400), "print").length() <= 200);
    }
}
