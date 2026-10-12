package net.trdlo.wtf.tiles;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The slippy-map math. This is the one class where a mistake is silent – a map of somewhere else
 * still looks like a map – so the fixtures are external ones from the OSM wiki rather than values
 * this implementation happened to produce.
 */
class MercatorTest {

    @Test
    @DisplayName("tileCount is 2^zoom")
    void tileCount() {
        assertEquals(1, Mercator.tileCount(0));
        assertEquals(2, Mercator.tileCount(1));
        assertEquals(32768, Mercator.tileCount(15));
        assertEquals(1 << 19, Mercator.tileCount(19));
        assertThrows(IllegalArgumentException.class, () -> Mercator.tileCount(-1));
        assertThrows(IllegalArgumentException.class, () -> Mercator.tileCount(31));
    }

    @ParameterizedTest(name = "z{0}: the world spans tile 0..2^z-1")
    @ValueSource(ints = {0, 1, 5, 12, 15, 19})
    @DisplayName("the west and east edges of the world land on the first and last tile")
    void worldEdges(final int zoom) {
        assertEquals(0, Mercator.tileX(-180.0, zoom), "west edge");
        assertEquals(Mercator.tileCount(zoom) - 1, Mercator.tileX(180.0 - 1e-9, zoom), "east edge");
        assertEquals(0, Mercator.tileY(Mercator.MAX_LATITUDE, zoom), "north edge");
        assertEquals(Mercator.tileCount(zoom) - 1, Mercator.tileY(-Mercator.MAX_LATITUDE, zoom), "south edge");
    }

    @ParameterizedTest(name = "z{0}: latitude beyond the Mercator limit clamps, never wraps")
    @ValueSource(ints = {0, 3, 15, 19})
    @DisplayName("out-of-range latitude clamps into the world rather than producing a negative index")
    void latitudeClamps(final int zoom) {
        final int last = Mercator.tileCount(zoom) - 1;
        assertEquals(0, Mercator.tileY(89.9, zoom));
        assertEquals(0, Mercator.tileY(90.0, zoom));
        assertEquals(last, Mercator.tileY(-89.9, zoom));
        assertEquals(last, Mercator.tileY(-90.0, zoom));
    }

    /**
     * Known tiles. The Brandenburg Gate at zoom 8 is the worked example on the OpenStreetMap
     * wiki's slippy-map page – a genuinely external fixture. The rest were cross-checked against an
     * independent implementation of the published formula rather than against this one's output,
     * which is weaker but still catches a change in behavior.
     */
    @ParameterizedTest(name = "z{2}: {0},{1} -> tile {3},{4}")
    @CsvSource({
            // lon,        lat,       zoom, x,      y
            "  0.0,        0.0,        1,   1,      1",      // the origin corner of four tiles
            "-180.0,      85.0511,     1,   0,      0",
            "  13.3777,   52.5163,     8,   137,    83",     // Brandenburg Gate – the OSM wiki example
            "  13.3777,   52.5163,    16,   35203,  21493",
            "  14.4213,   50.0875,    15,   17696,  11100",  // Prague, Old Town Square
            "  -0.0005,   51.4779,    17,   65535,  43602",  // Greenwich, just west of the meridian
            " 139.7671,   35.6812,    12,   3638,   1612",   // Tokyo station
    })
    void knownTiles(final double lon, final double lat, final int zoom, final int x, final int y) {
        assertEquals(x, Mercator.tileX(lon, zoom), "tile x");
        assertEquals(y, Mercator.tileY(lat, zoom), "tile y");
    }

    @ParameterizedTest(name = "round trip at z{0}")
    @ValueSource(ints = {0, 1, 8, 15, 19})
    @DisplayName("lon/lat survives a trip through the unit square")
    void roundTrip(final int zoom) {
        for (double lon = -179.5; lon < 180; lon += 7.3) {
            assertEquals(lon, Mercator.unitXToLon(Mercator.lonToUnitX(lon)), 1e-9, "lon " + lon);
            assertEquals(lon, Mercator.tileXToLon(Mercator.lonToTileX(lon, zoom), zoom), 1e-9, "lon " + lon);
        }
        for (double lat = -84; lat < 85; lat += 6.7) {
            assertEquals(lat, Mercator.unitYToLat(Mercator.latToUnitY(lat)), 1e-9, "lat " + lat);
            assertEquals(lat, Mercator.tileYToLat(Mercator.latToTileY(lat, zoom), zoom), 1e-9, "lat " + lat);
        }
    }

    @Test
    @DisplayName("a tile's bounds contain its own center and abut its neighbors")
    void tileBounds() {
        final int z = 12, x = 2200, y = 1400;
        final LonLatBounds b = Mercator.tileBounds(z, x, y);
        assertTrue(b.west() < b.east() && b.south() < b.north(), "non-degenerate");
        assertEquals(x, Mercator.tileX(b.centerLon(), z), "center is in the tile");
        assertEquals(y, Mercator.tileY(b.centerLat(), z), "center is in the tile");

        assertEquals(b.east(), Mercator.tileBounds(z, x + 1, y).west(), 1e-12, "no gap to the east");
        assertEquals(b.south(), Mercator.tileBounds(z, x, y + 1).north(), 1e-12, "no gap to the south");
    }

    @Test
    @DisplayName("ground resolution matches the published figures")
    void groundResolution() {
        // 156543.03 m/px at the equator at zoom 0 is the number every slippy-map reference quotes
        assertEquals(156543.03, Mercator.groundResolution(0, 0), 0.01);
        assertEquals(156543.03 / 32768, Mercator.groundResolution(0, 15), 1e-6);
        // docs/design/compose-module.md §3 tabulates 3.07 m/px at 50 degrees north, zoom 15
        assertEquals(3.071, Mercator.groundResolution(50, 15), 0.001);
        assertEquals(786.1, Mercator.tileGroundSize(50, 15), 0.1);
    }

    @Test
    @DisplayName("a range covers exactly the tiles a rectangle touches")
    void range() {
        // one tile at zoom 10, asked for by a rectangle wholly inside it
        final LonLatBounds inside = Mercator.tileBounds(10, 550, 350).expandedBy(-0.01);
        assertEquals(new TileRange(550, 350, 550, 350), Mercator.range(inside, 10));

        // a rectangle spanning two tiles horizontally
        final LonLatBounds b = Mercator.tileBounds(10, 550, 350);
        final LonLatBounds across = new LonLatBounds(
                b.east() - 0.001, b.south() + 0.001, b.east() + 0.001, b.north() - 0.001);
        assertEquals(new TileRange(550, 350, 551, 350), Mercator.range(across, 10));
    }

    @Test
    @DisplayName("a range's margin is rounded outwards and clamped to the world")
    void rangeMargin() {
        final LonLatBounds b = Mercator.tileBounds(8, 0, 0);
        final TileRange r = Mercator.range(b, 8, 2.1);
        assertEquals(0, r.minX(), "clamped at the west edge, not negative");
        assertEquals(0, r.minY(), "clamped at the north edge");
        assertEquals(3, r.maxX(), "2.1 tiles of margin rounds outwards to 3");
    }

    @Test
    @DisplayName("a rectangle snapped to tile boundaries does not gain a spurious row and column")
    void rangeOnExactBoundaries() {
        // the ordinary case once a shape has been rounded outwards to whole tiles
        final int z = 12;
        final LonLatBounds topLeft = Mercator.tileBounds(z, 100, 200);
        final LonLatBounds bottomRight = Mercator.tileBounds(z, 104, 203);
        final LonLatBounds exact = new LonLatBounds(
                topLeft.west(), bottomRight.south(), bottomRight.east(), topLeft.north());

        assertEquals(new TileRange(100, 200, 104, 203), Mercator.range(exact, z),
                "an edge lying exactly on a tile boundary must not pull in the next tile");
        assertEquals(20, Mercator.range(exact, z).count(), "5 x 4 tiles, not 6 x 5");
    }

    @Test
    @DisplayName("a degenerate rectangle on a tile boundary still yields one tile")
    void rangeDegenerateOnBoundary() {
        final int z = 10;
        final double boundary = Mercator.tileXToLon(600, z);
        final double vBoundary = Mercator.tileYToLat(400, z);
        final TileRange r = Mercator.range(new LonLatBounds(boundary, vBoundary, boundary, vBoundary), z);
        assertFalse(r.isEmpty(), "must not collapse to nothing");
        assertEquals(1, r.count());
    }

    @Test
    @DisplayName("the whole world at zoom 3 is 64 tiles")
    void wholeWorld() {
        final TileRange r = Mercator.range(new LonLatBounds(-180, -85, 180, 85), 3);
        assertEquals(64, r.count());
        assertEquals(new TileRange(0, 0, 7, 7), r);
    }
}
