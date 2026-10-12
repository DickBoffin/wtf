package net.trdlo.wtf.studio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/** Lengths on paper in millimeters or inches; the test JVM runs in en-US. */
class UnitsTest {

    @Test
    @DisplayName("the machine's country decides the default")
    void fromTheMachine() {
        assertEquals(Units.INCHES, Units.fromLocale(Locale.US));
        assertEquals(Units.MILLIMETERS, Units.fromLocale(Locale.forLanguageTag("cs-CZ")));
        assertEquals(Units.MILLIMETERS, Units.fromLocale(Locale.UK));
    }

    @Test
    @DisplayName("inches say what they need and no more")
    void inches() {
        assertEquals("8.5 × 11 in", Units.INCHES.size(215.9, 279.4, 1));
        assertEquals("0.0039 in", Units.INCHES.length(0.1, 2), "a thin boundary is not rounded away");
        assertEquals(25.4, Units.INCHES.toMm(1), 1e-12);
        assertEquals("40.4 ft", Units.INCHES.longLength(12_300));
    }

    @Test
    @DisplayName("millimeters keep the decimals asked for")
    void millimeters() {
        assertEquals("297 × 210 mm", Units.MILLIMETERS.size(297, 210, 0));
        assertEquals("1.20 mm", Units.MILLIMETERS.length(1.2, 2));
        assertEquals("12.3 m", Units.MILLIMETERS.longLength(12_300));
    }

    /** The status bar's ground per pixel: feet and miles under inches, meters and kilometers else. */
    @Test
    @DisplayName("the status bar measures the ground in the chosen system")
    void resolution() {
        final Units before = Units.current();
        final Locale language = I18n.locale();
        try {
            I18n.setLocale(Locale.ENGLISH);
            Units.use("inch");
            assertEquals("3.28 ft/px", MainWindow.resolution(1));
            assertEquals("1.00 mi/px", MainWindow.resolution(1609.344));
            Units.use("mm");
            assertEquals("1.00 m/px", MainWindow.resolution(1));
            assertEquals("1.61 km/px", MainWindow.resolution(1609.344));
        } finally {
            Units.use(before.key());
            I18n.setLocale(language);
        }
    }

    @Test
    @DisplayName("one distance on the ground, in the chosen system")
    void ground() {
        assertEquals("50.0 km", Units.MILLIMETERS.ground(50_000));
        assertEquals("31.1 mi", Units.INCHES.ground(50_000));
    }

    @Test
    @DisplayName("Settings can choose either, and nothing leaves the machine's default")
    void chosen() {
        final Units before = Units.current();
        try {
            Units.use("inch");
            assertEquals(Units.INCHES, Units.current());
            Units.use("mm");
            assertEquals(Units.MILLIMETERS, Units.current());
            Units.use(null);
            assertEquals(Units.MILLIMETERS, Units.current(), "no choice keeps what there was");
        } finally {
            Units.use(before.key());
        }
    }
}
