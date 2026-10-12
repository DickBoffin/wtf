package net.trdlo.wtf.studio.steps;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.studio.Units;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The roll-width combo writes what is typed into it and nothing it is shown. An editable combo
 * commits its editor's text when it loses the focus – the converter's {@code fromString} of what
 * its {@code toString} put there – and that text is rounded under either unit.
 */
class RollWidthTextTest {

    private final Units before = Units.current();

    @AfterEach
    void back() {
        Units.use(before.key());
    }

    /** What the combo does when the focus passes through it: commits the text it was showing. */
    private static double passedThrough(final RollWidthText converter, final DoubleProperty mm) {
        return converter.fromString(converter.toString(mm.get()));
    }

    @ParameterizedTest(name = "{0}: {1} mm is shown as \"{2}\" and stays {1} mm")
    @CsvSource({
            "inch, 914, 36",
            "inch, 914.4, 36",
            "mm, 914, 914",
            "mm, 914.4, 914",
    })
    @DisplayName("a width committed unchanged keeps its width")
    void committedUnchanged(final String unit, final double widthMm, final String shown) {
        Units.use(unit);
        final DoubleProperty mm = new SimpleDoubleProperty(widthMm);
        final RollWidthText converter = new RollWidthText(mm, Units.current());
        assertEquals(shown, converter.toString(widthMm));
        assertEquals(widthMm, passedThrough(converter, mm), 0.0, "not the width read back from the text");
    }

    @Test
    @DisplayName("a width typed is written as typed, in the unit shown")
    void typedIsWritten() {
        Units.use("inch");
        final DoubleProperty mm = new SimpleDoubleProperty(914);
        final RollWidthText converter = new RollWidthText(mm, Units.current());
        assertEquals(914.4, converter.fromString(I18n.number(36.0, 1)), 1e-9, "36.0 is not the 36 shown");
        assertEquals(1066.8, converter.fromString("42"), 1e-9);

        Units.use("mm");
        final RollWidthText inMm = new RollWidthText(mm, Units.current());
        mm.set(914.4);
        assertEquals(915.0, inMm.fromString("915"), 0.0);
    }

    @Test
    @DisplayName("a text that is not a number leaves the model's width")
    void notANumber() {
        Units.use("mm");
        final DoubleProperty mm = new SimpleDoubleProperty(914.4);
        final RollWidthText converter = new RollWidthText(mm, Units.current());
        assertEquals(914.4, converter.fromString("wide"), 0.0);
        assertEquals(914.4, converter.fromString(""), 0.0);
    }
}
