package net.trdlo.wtf.studio.map;

import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.studio.Headless;
import net.trdlo.wtf.tiles.CachedTileSource;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import net.trdlo.wtf.tiles.TileCache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

import java.nio.file.Path;

import static net.trdlo.wtf.studio.Headless.onFx;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A region that is not drawn is white paper on the map, whichever side it is and whichever way the
 * subject's rings run. Over an empty cache every tile is a placeholder, so where the layer painted
 * is where a placeholder is, and where it did not, the color the canvas held before.
 */
class StencilTest {

    private static final int SIZE = 200;
    /** What the base canvas holds before the frame: what shows where the layer paints nothing. */
    private static final Color BEFORE = Color.MAGENTA;

    @BeforeAll
    static void toolkit() {
        Headless.start();
    }

    /** A square in the middle of the view, its ring counterclockwise or clockwise in degrees. */
    private static Geometry square(final boolean clockwise) {
        final Coordinate[] ring = {new Coordinate(14.9, 49.95), new Coordinate(15.1, 49.95),
                new Coordinate(15.1, 50.05), new Coordinate(14.9, 50.05), new Coordinate(14.9, 49.95)};
        if (clockwise) {
            java.util.Collections.reverse(java.util.Arrays.asList(ring));
        }
        return Shapes.GF.createPolygon(ring);
    }

    /** A wider square with a lake in its middle, the two rings wound alike – the wrong way for a hole. */
    private static Geometry withLake() {
        final Coordinate[] shell = {new Coordinate(14.8, 49.9), new Coordinate(15.2, 49.9),
                new Coordinate(15.2, 50.1), new Coordinate(14.8, 50.1), new Coordinate(14.8, 49.9)};
        final Coordinate[] lake = {new Coordinate(14.95, 49.97), new Coordinate(15.05, 49.97),
                new Coordinate(15.05, 50.03), new Coordinate(14.95, 50.03), new Coordinate(14.95, 49.97)};
        return Shapes.GF.createPolygon(Shapes.GF.createLinearRing(shell),
                new org.locationtech.jts.geom.LinearRing[]{Shapes.GF.createLinearRing(lake)});
    }

    /** The base canvas and the region canvas after one frame, middle and corner of each. */
    private record Frame(Color baseMiddle, Color baseCorner, Color regionMiddle, Color regionCorner) {
    }

    private static Frame frame(final Path dir, final Geometry subject, final boolean drawInside,
                               final boolean drawOutside) throws Exception {
        return onFx(() -> {
            final Camera camera = new Camera();
            camera.setViewSize(SIZE, SIZE);
            camera.centerOn(15, 50, 8);
            final TileLayer layer = new TileLayer(camera, () -> { });
            final Provider osm = ProviderCatalog.builtIn().require("osm");
            layer.setSource(osm, osm.themes().getFirst().id(), null, 1,
                    CachedTileSource.cacheOnly(new TileCache(dir), osm), 16L << 20);
            layer.setRegion(subject, drawInside, drawOutside);
            try {
                final Canvas base = new Canvas(SIZE, SIZE), region = new Canvas(SIZE, SIZE);
                base.getGraphicsContext2D().setFill(BEFORE);
                base.getGraphicsContext2D().fillRect(0, 0, SIZE, SIZE);
                layer.paint(base.getGraphicsContext2D(), region.getGraphicsContext2D(), SIZE, SIZE);
                final SnapshotParameters clear = new SnapshotParameters();
                clear.setFill(Color.TRANSPARENT);
                final WritableImage b = base.snapshot(clear, null), r = region.snapshot(clear, null);
                return new Frame(b.getPixelReader().getColor(SIZE / 2, SIZE / 2), b.getPixelReader().getColor(5, 5),
                        r.getPixelReader().getColor(SIZE / 2, SIZE / 2), r.getPixelReader().getColor(5, 5));
            } finally {
                layer.dispose();
            }
        });
    }

    @ParameterizedTest(name = "clockwise: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("an inside that is not drawn is white, and the map does not show through it")
    void anInsideNotDrawnIsPaper(final boolean clockwise, @TempDir final Path dir) throws Exception {
        final Frame f = frame(dir, square(clockwise), false, true);
        assertEquals(BEFORE, f.baseMiddle(), "the base layer leaves the inside alone");
        assertNotEquals(BEFORE, f.baseCorner(), "and draws the outside");
        assertEquals(Color.WHITE, f.regionMiddle(), "the inside is paper");
        assertEquals(Color.TRANSPARENT, f.regionCorner(), "and nothing else is on the region canvas");
    }

    @ParameterizedTest(name = "clockwise: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("an outside that is not drawn is white, and the inside is drawn over it")
    void anOutsideNotDrawnIsPaper(final boolean clockwise, @TempDir final Path dir) throws Exception {
        final Frame f = frame(dir, square(clockwise), true, false);
        assertEquals(Color.WHITE, f.baseCorner(), "the outside is paper");
        assertEquals(BEFORE, f.baseMiddle(), "and not over the inside");
        assertNotEquals(Color.TRANSPARENT, f.regionMiddle(), "the inside is drawn");
        assertNotEquals(Color.WHITE, f.regionMiddle());
        assertEquals(Color.TRANSPARENT, f.regionCorner(), "and only the inside");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("a lake in the subject is outside it: drawn with the outside, and no paper over it")
    void aLakeIsOutside(@TempDir final Path dir) throws Exception {
        final Frame f = frame(dir, withLake(), false, true);
        assertNotEquals(BEFORE, f.baseMiddle(), "the outside's map is drawn in the lake");
        assertEquals(Color.TRANSPARENT, f.regionMiddle(), "and the inside's paper leaves it alone");
    }
}
