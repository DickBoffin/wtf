package net.trdlo.wtf.studio.steps;

import net.trdlo.wtf.studio.Units;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Room around it in kilometers, or in miles where lengths are in inches, while the model keeps meters. */
class GrowUnitsTest {

    private final Units before = Units.current();

    @AfterEach
    void back() {
        Units.use(before.key());
    }

    @Test
    @DisplayName("with inches the field is in miles: 50 km is 31.07")
    void inMiles() {
        Units.use("inch");
        assertEquals(AreaPanel.Grow.MILES, AreaPanel.Grow.current());
        assertEquals("31.07", AreaPanel.Grow.MILES.shown(50_000));
    }

    @Test
    @DisplayName("metric is kilometers: 50 km, not 50,000 m, and a quarter of one to the meter")
    void metric() {
        Units.use("mm");
        assertEquals(AreaPanel.Grow.KILOMETERS, AreaPanel.Grow.current());
        assertEquals("50", AreaPanel.Grow.KILOMETERS.shown(50_000));
        assertEquals("0.25", AreaPanel.Grow.KILOMETERS.shown(250));
        assertEquals("1.234", AreaPanel.Grow.KILOMETERS.shown(1_234.4));
    }
}
