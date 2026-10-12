package net.trdlo.wtf.studio;

import javafx.application.Application;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks without a window: which command-line flags are captures and what their modifiers are,
 * and what a zoom spinner makes of the text typed into it.
 */
class SmallFixesTest {

    private final Locale before = I18n.locale();

    @AfterEach
    void restore() {
        I18n.setLocale(before);
    }

    private static Application.Parameters args(final String... raw) {
        return new Application.Parameters() {
            @Override
            public List<String> getRaw() {
                return List.of(raw);
            }

            @Override
            public List<String> getUnnamed() {
                return List.of(raw);
            }

            @Override
            public Map<String, String> getNamed() {
                return Map.of();
            }
        };
    }

    @Test
    @DisplayName("a capture modifier is a whole word after the @, not a substring of the flag")
    void modifiersAreWordsAfterTheAt() {
        assertTrue(BatchMode.hasModifier(args("--screenshot-steps=temp/o@outline"), "outline"));
        assertTrue(BatchMode.hasModifier(args("--screenshot-preview=temp/p.png@faded,calibrate"), "calibrate"));

        assertFalse(BatchMode.hasModifier(args("--screenshot-outlines=temp/o.png#america"), "outline"),
                "the flag's own name");
        assertFalse(BatchMode.hasModifier(args("--screenshot-steps=outlines/x@faded"), "outline"),
                "a folder in the path");
        assertFalse(BatchMode.hasModifier(args("--screenshot-steps=temp/x@edited"), "edit"),
                "part of a longer word");
        assertFalse(BatchMode.hasModifier(args("--seed-view=1,2,3@edit"), "edit"), "not a capture flag");
    }

    @Test
    @DisplayName("every capture flag makes the run unattended, and nothing else does")
    void anyCaptureIsBatch() {
        assertTrue(BatchMode.isAnyRequested(args("--screenshot")));
        assertTrue(BatchMode.isAnyRequested(args("--lang=cs", "--screenshot-splash=temp/s.png@2600")));
        assertFalse(BatchMode.isAnyRequested(args("--lang=cs", "--seed-view=14,50,8")));
        assertFalse(BatchMode.isAnyRequested(null));
    }

    @Test
    @DisplayName("a zoom spinner reads the display locale and keeps its value on anything else")
    void zoomSpinnerText() {
        I18n.setLocale(Locale.forLanguageTag("cs-CZ"));
        final var zoom = ProviderEditor.zoomConverter(() -> 7);
        assertEquals(12, zoom.fromString("12"));
        assertEquals(12, zoom.fromString(" 12 "));
        assertEquals(7, zoom.fromString("12,5"), "not a zoom level");
        assertEquals(7, zoom.fromString("abc"), "not a number, and no exception");
        assertEquals(7, zoom.fromString(""));
        assertEquals("12", zoom.toString(12));
    }
}
