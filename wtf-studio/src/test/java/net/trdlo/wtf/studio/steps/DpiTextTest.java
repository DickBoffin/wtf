package net.trdlo.wtf.studio.steps;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import net.trdlo.wtf.studio.I18n;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/** The DPI combo takes any whole DPI typed in the display language, and nothing else. */
class DpiTextTest {

    private final Locale before = I18n.locale();

    @AfterEach
    void back() {
        I18n.setLocale(before);
    }

    @ParameterizedTest(name = "{0}: \"{1}\" is {2} DPI")
    @CsvSource(delimiter = '|', value = {
            "en | 152    | 152",
            "en | 1,200  | 1200",
            "cs | 1 200  | 1200",
            "en | 152.4  | 152",
            "en | junk   | 150",
            "en | 0      | 150",
            "en | 5      | 150",
            "en | 100000 | 150",
    })
    void typed(final String language, final String text, final int dpi) {
        I18n.setLocale(Locale.forLanguageTag(language));
        final IntegerProperty model = new SimpleIntegerProperty(150);
        assertEquals(dpi, new DpiText(model).fromString(text));
    }
}
