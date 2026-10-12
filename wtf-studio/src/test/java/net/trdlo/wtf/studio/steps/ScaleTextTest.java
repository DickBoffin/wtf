package net.trdlo.wtf.studio.steps;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import net.trdlo.wtf.studio.I18n;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/** The scale combo takes two numbers and a colon, or one number, in the display language. */
class ScaleTextTest {

    private final Locale before = I18n.locale();

    @AfterEach
    void back() {
        I18n.setLocale(before);
    }

    @ParameterizedTest(name = "{0}: \"{1}\" is 1:{2}")
    @CsvSource(delimiter = '|', value = {
            "en | 1:25000          | 25000",
            "en | 1:25,000         | 25000",
            "cs | 1:25 000         | 25000",
            "en | 1 : 50000        | 50000",
            "en | 50000            | 50000",
            "cs | 3,1415:100000    | 31832",
            "en | 3.1415:100,000   | 31832",
            "en | 2:100000         | 50000",
            "en | 1:1              | 1",
            "en | 1e5              | 7777",
            "en | 1:1e5            | 7777",
            "en | 0:5              | 7777",
            "en | 5:0              | 7777",
            "en | -1:5             | 7777",
            "en | 2:1              | 7777",
            "en | 1:2:3            | 7777",
            "en | :50000           | 7777",
            "en | junk             | 7777",
            "en | 1:2000000000     | 7777",
    })
    void typed(final String language, final String text, final int denominator) {
        I18n.setLocale(Locale.forLanguageTag(language));
        final IntegerProperty model = new SimpleIntegerProperty(7777);
        assertEquals(denominator, new ScaleText(model).fromString(text));
    }

    @Test
    void shownAsARatio() {
        I18n.setLocale(Locale.ENGLISH);
        final ScaleText text = new ScaleText(new SimpleIntegerProperty(1));
        assertEquals("1:31,832", text.toString(31_832));
        assertEquals(31_832, text.fromString(text.toString(31_832)), "what it shows reads back");
    }
}
