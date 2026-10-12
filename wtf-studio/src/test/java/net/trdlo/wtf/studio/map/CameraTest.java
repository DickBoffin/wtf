package net.trdlo.wtf.studio.map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import net.trdlo.wtf.tiles.Mercator;

import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The viewport arithmetic. No toolkit is started: the camera is three JavaFX properties and the
 * rest is trigonometry, which is exactly why it is worth testing here rather than by eye.
 */
class CameraTest {

    private static Camera sized(final double width, final double height) {
        final Camera camera = new Camera();
        camera.setViewSize(width, height);
        return camera;
    }

    @Test
    @DisplayName("the view cannot be panned off the map on either axis")
    void panningStopsAtTheEdges() {
        final Camera c = sized(800, 600);
        c.centerOn(0, 0, 4);                    // zoom 4: the world is 4096 px, far bigger than the view

        c.panByPixels(100_000, 100_000);        // shove it as far up and left as it will go
        assertEquals(0, c.originXProperty().get(), 1e-9, "the west edge is the west edge");
        assertEquals(0, c.originYProperty().get(), 1e-9, "the north edge is the north edge");

        c.panByPixels(-100_000, -100_000);      // and as far the other way
        final double unitsWide = 800 / c.pixelsPerUnit();
        final double unitsTall = 600 / c.pixelsPerUnit();
        assertEquals(1 - unitsWide, c.originXProperty().get(), 1e-9);
        assertEquals(1 - unitsTall, c.originYProperty().get(), 1e-9);
    }

    @Test
    @DisplayName("zooming to the cursor cannot walk the view off the map either")
    void zoomingAtAPointStaysInside() {
        final Camera c = sized(800, 600);
        c.centerOn(0, 0, 3);
        for (int i = 0; i < 40; i++) {
            c.zoomAt(795, 595, -0.25);          // zoom out at the far corner, over and over
        }
        assertTrue(c.originXProperty().get() <= 0.5, "never past the map's middle when fully out");
        assertTrue(c.toUnitX(0) <= 0 || c.pixelsPerUnit() > 800, "no background west of the world");
        assertTrue(c.toUnitY(0) <= 0 || c.pixelsPerUnit() > 600, "no background north of it");
    }

    @Test
    @DisplayName("a world smaller than the canvas is centered on each axis separately")
    void theSmallWorldIsCentered() {
        // 256 px of world in a 900 x 400 canvas: narrower than the canvas both ways
        final Camera c = sized(900, 400);
        c.setFractionalZoom(0);
        c.panByPixels(300, 200);                // try to shove it into a corner

        assertEquals((1 - 900 / 256.0) / 2, c.originXProperty().get(), 1e-9, "centered horizontally");
        assertEquals((1 - 400 / 256.0) / 2, c.originYProperty().get(), 1e-9, "centered vertically");
    }

    @Test
    @DisplayName("each axis is decided on its own: one can fit while the other does not")
    void theAxesAreIndependent() {
        // At zoom 1 the world is 512 px: wider than a 300 px canvas, shorter than an 800 px one
        final Camera c = sized(300, 800);
        c.centerOn(0, 0, 1);
        c.panByPixels(-100_000, -100_000);

        assertEquals(1 - 300 / 512.0, c.originXProperty().get(), 1e-9, "held against the east edge");
        assertEquals((1 - 800 / 512.0) / 2, c.originYProperty().get(), 1e-9, "centered vertically");
    }

    @Test
    @DisplayName("resizing keeps what was in the middle in the middle")
    void resizingHoldsTheCenter() {
        final Camera c = sized(800, 600);
        c.centerOn(14.42, 50.08, 8);
        final double lon = c.lonAt(400), lat = c.latAt(300);

        c.setViewSize(1600, 1000);              // maximized, growing right and down

        assertEquals(lon, c.lonAt(800), 1e-6, "same longitude in the middle");
        assertEquals(lat, c.latAt(500), 1e-6, "same latitude in the middle");
    }

    @Test
    @DisplayName("a point off a small world is on its edge, not wrapped around to the other side")
    void offTheWorldIsClampedLikeTheLatitude() {
        // 256 px of world centered in a 900 px canvas: over 300 px of background on each side
        final Camera c = sized(900, 400);
        c.setFractionalZoom(0);

        assertEquals(180, c.lonAt(880), 1e-9, "east of the world is the antimeridian");
        assertEquals(-180, c.lonAt(20), 1e-9, "and west of it too");
        assertTrue(c.lonAt(20) < c.lonAt(880), "a drag across it runs west to east");
    }

    @Test
    @DisplayName("the first sizing centers on a middle asked for at one pixel, showing only map")
    void theFirstSizingCentersOnTheMiddleAskedFor() {
        final Camera c = new Camera();          // 1 x 1 until the canvas is laid out
        c.setFractionalZoom(2);
        c.centerOn(Mercator.lonToUnitX(0), Mercator.latToUnitY(0));
        c.setViewSize(1000, 800);

        assertEquals(0, c.lonAt(500), 1e-9, "the middle asked for is the middle");
        assertEquals(0, c.latAt(400), 1e-9);
        // At zoom 2 the world is 1024 px, so a 1000 px view centered on it is still inside it
        assertTrue(c.toUnitX(0) >= -1e-9, "no background to the west");
        assertTrue(c.toUnitX(1000) <= 1 + 1e-9, "none to the east");
        assertTrue(c.toUnitY(0) >= -1e-9 && c.toUnitY(800) <= 1 + 1e-9, "and none above or below");
    }

    @Test
    @DisplayName("a middle asked for before the height arrives is the middle once it has")
    void aCenteringWaitsForTheHeight() {
        final Camera c = new Camera();
        c.setViewSize(1000, 1);                 // a Region is given its width, then its height
        c.centerOn(14.42, 50.08, 8);
        c.setViewSize(1000, 800);

        final Camera sizedFirst = sized(1000, 800);
        sizedFirst.centerOn(14.42, 50.08, 8);
        assertEquals(sizedFirst.originXProperty().get(), c.originXProperty().get(), 1e-12,
                "the view a centering at the whole size gives");
        assertEquals(sizedFirst.originYProperty().get(), c.originYProperty().get(), 1e-12);
        assertEquals(50.08, c.latAt(400), 1e-9,
                "the latitude asked for, in the middle and not on the top edge");
    }

    /** What else can move a view between a centering and the arrival of its height. */
    static Stream<Arguments> movesBeforeTheHeight() {
        return Stream.of(
                Arguments.of("a pan", (Consumer<Camera>) c -> c.panByPixels(120, -80)),
                Arguments.of("a zoom at the pointer", (Consumer<Camera>) c -> c.zoomAt(250, 0, 1)),
                Arguments.of("a zoom", (Consumer<Camera>) c -> c.setFractionalZoom(9)),
                Arguments.of("a restore", (Consumer<Camera>) c -> c.restore(0.5, 0.3, 131_072)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("movesBeforeTheHeight")
    @DisplayName("a pan, a zoom or a restore before the height arrives is the view it keeps")
    void aMoveInBetweenWins(final String what, final Consumer<Camera> move) {
        final Camera c = new Camera();
        c.setViewSize(1000, 1);
        c.centerOn(14.42, 50.08, 8);
        move.accept(c);
        final double x = c.originXProperty().get(), y = c.originYProperty().get();

        c.setViewSize(1000, 800);

        assertEquals(x, c.originXProperty().get(), 1e-12, "the corner the move left");
        assertEquals(y, c.originYProperty().get(), 1e-12, "not the middle asked for before it");
    }

    @Test
    @DisplayName("a view made one pixel high and given its height back keeps its middle")
    void aSizeLostAndFoundKeepsTheMiddle() {
        final Camera c = sized(1000, 800);
        c.centerOn(14.42, 50.08, 8);
        final double x = c.originXProperty().get(), y = c.originYProperty().get();

        c.setViewSize(1000, 1);                 // shorter than a window's toolbar and status bar
        c.setViewSize(1000, 800);

        assertEquals(x, c.originXProperty().get(), 1e-12, "the view it had");
        assertEquals(y, c.originYProperty().get(), 1e-12);
        assertEquals(50.08, c.latAt(400), 1e-9, "Prague in the middle, not on the top edge");
    }

    @Test
    @DisplayName("ground resolution is meters per pixel, not per tile pixel at zoom 0")
    void groundResolutionIsInMeters() {
        final Camera c = sized(800, 600);
        c.centerOn(0, 0, 8);                    // the equator, so the cosine is 1

        // 40 075 017 m of equator over the 65 536 px the world spans at zoom 8
        assertEquals(Mercator.EQUATORIAL_CIRCUMFERENCE / 65_536, c.groundResolution(), 1e-6);
        assertEquals(611.49, c.groundResolution(), 0.01, "the published zoom 8 figure");
    }

    @Test
    @DisplayName("and it agrees with the tile module at the same latitude and zoom")
    void groundResolutionAgreesWithMercator() {
        final Camera c = sized(800, 600);
        c.centerOn(14.42, 50.08, 12);
        assertEquals(Mercator.groundResolution(c.latAt(300), 12), c.groundResolution(), 1e-6);
    }
}
