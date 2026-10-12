package net.trdlo.wtf.studio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import net.trdlo.wtf.tiles.ProgressModel;

import java.time.Duration;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The time left, as the window says it: the same steps as the console's, at every boundary, in
 * both languages – the window's words come from the console's arithmetic, not a copy of it.
 */
class EtaWordingTest {

    private final Locale display = Locale.getDefault(Locale.Category.DISPLAY);

    @AfterEach
    void back() {
        I18n.setLocale(display);
    }

    /** Seconds either side of each boundary. */
    private static final long[] SECONDS = {44, 45, 89, 90, 3569, 3570, 4499, 4500, 4800, 6299, 6300, 6600,
            8099, 8100, 35_099, 35_100, 37_800};

    @Test
    @DisplayName("in English the window says what the console says, at every boundary")
    void englishIsTheConsole() {
        I18n.setLocale(Locale.ENGLISH);
        for (final long s : SECONDS) {
            assertEquals(ProgressModel.describe(Duration.ofSeconds(s)), ProgressText.eta(Duration.ofSeconds(s)),
                    s + " s");
        }
        assertEquals(ProgressModel.describe(null), ProgressText.eta(null));
    }

    @Test
    @DisplayName("in Czech the boundaries fall in the same places")
    void czechBoundaries() {
        I18n.setLocale(Locale.forLanguageTag("cs-CZ"));
        assertEquals("asi 59 min", ProgressText.eta(Duration.ofSeconds(3569)));
        assertEquals("asi hodina", ProgressText.eta(Duration.ofSeconds(3570)));
        assertEquals("asi hodina", ProgressText.eta(Duration.ofSeconds(4499)));
        assertEquals("asi hodina a půl", ProgressText.eta(Duration.ofSeconds(4500)));
        assertEquals("asi hodina a půl", ProgressText.eta(Duration.ofSeconds(6299)));
        assertEquals("asi 2 h", ProgressText.eta(Duration.ofSeconds(6300)), "two hours, not \"2,0\"");
        assertEquals("asi 2,5 h", ProgressText.eta(Duration.ofSeconds(8100)));
        assertEquals("asi 9,5 h", ProgressText.eta(Duration.ofSeconds(35_099)));
        assertEquals("asi 10 h", ProgressText.eta(Duration.ofSeconds(35_100)));
    }
}
