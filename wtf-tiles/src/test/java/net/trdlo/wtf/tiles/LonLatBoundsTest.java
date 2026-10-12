package net.trdlo.wtf.tiles;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A range across the antimeridian is refused, with a message, as D22 decided: west of 180° to
 * east of −180° would be two ranges in every stage after it. Unrefused, 170° to −170° is a range
 * whose east is west of its west, and the command line counts some tiles for it anyway and says
 * they are missing.
 */
class LonLatBoundsTest {

    /**
     * A home of the class's own, with no {@code .wtf} in it: the command line merges
     * {@code ~/.wtf/providers.json}, and a developer's own file that hides or overrides {@code osm}
     * is not what CI, which has none, sees.
     */
    @TempDir
    static Path classHome;
    private static String originalHome;

    @BeforeAll
    static void homeOfItsOwn() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", classHome.toString());
    }

    @AfterAll
    static void homeGivenBack() {
        System.setProperty("user.home", originalHome);
    }

    @Test
    @DisplayName("a range whose east is west of its west is refused, and the message names the antimeridian")
    void acrossTheAntimeridianIsRefused() {
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new LonLatBounds(170, -20, -170, -10));
        assertTrue(refused.getMessage().contains("antimeridian"), refused.getMessage());
        assertTrue(refused.getMessage().contains("D22"), "and where the decision is: " + refused.getMessage());

        // Up to the antimeridian from either side, and the whole world, are ranges like any other
        assertEquals(10, new LonLatBounds(170, -20, 180, -10).widthDegrees(), 1e-12);
        assertEquals(10, new LonLatBounds(-180, -20, -170, -10).widthDegrees(), 1e-12);
        assertEquals(360, new LonLatBounds(-180, -85, 180, 85).widthDegrees(), 1e-12);
        assertEquals(0, LonLatBounds.of(180, 0).widthDegrees(), 1e-12, "and one place on it");
    }

    @Test
    @DisplayName("the command line refuses such a --bbox as a bad argument, exit 2, and says why")
    void theCommandLineSaysWhy(@TempDir final Path dir) {
        final PrintStream originalErr = System.err;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            assertEquals(2, TilesCli.run(new String[]{"count", "--provider", "osm", "--theme", "standard",
                    "--zoom", "5", "--bbox", "170,-20,-170,-10", "--cache", dir.toString()}));
        } finally {
            System.setErr(originalErr);
        }
        final String said = captured.toString(StandardCharsets.UTF_8);
        assertTrue(said.contains("antimeridian"), said);
    }
}
