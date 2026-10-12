package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.Mercator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How many rows of tiles a raster's band holds when nobody names the height – the studio never
 * does, and the command line only with {@code --band-tiles}. Sixteen; fewer on a raster so wide
 * that sixteen would pass the pixels a whole TIFF is composited in; and never none.
 */
class BandHeightTest {

    @Test
    @DisplayName("a narrow raster is banded sixteen rows of tiles at a time")
    void aNarrowRasterGetsSixteen() {
        assertEquals(16, RasterExporter.DEFAULT_BAND_TILES);
        // 15 625 pixels across is as wide as sixteen rows of tiles stay within 64 Mpx, to the pixel
        for (final int width : new int[]{1, 437, 4_000, 15_625}) {
            assertEquals(RasterExporter.DEFAULT_BAND_TILES, RasterExporter.defaultBandTiles(width),
                    () -> width + " px across");
        }
    }

    @Test
    @DisplayName("a very wide one in fewer, as many as a whole TIFF's pixels hold")
    void aWideRasterGetsFewer() {
        assertEquals(15, RasterExporter.defaultBandTiles(15_626), "a pixel past sixteen's width");
        assertEquals(7, RasterExporter.defaultBandTiles(35_000));
        assertEquals(2, RasterExporter.defaultBandTiles(100_000));
        for (int width = 15_626; width <= 250_000; width += 1_234) {
            final int w = width;
            final long tiles = RasterExporter.defaultBandTiles(w);
            assertTrue(tiles < RasterExporter.DEFAULT_BAND_TILES, () -> w + " px across");
            assertTrue(tiles * Mercator.TILE_SIZE * w <= RasterExporter.PIXELS_AT_ONCE,
                    () -> w + " px across: within the budget");
            assertTrue((tiles + 1) * Mercator.TILE_SIZE * w > RasterExporter.PIXELS_AT_ONCE,
                    () -> w + " px across: and as many rows as it holds");
        }
    }

    @Test
    @DisplayName("and never none, however wide")
    void neverNone() {
        assertEquals(1, RasterExporter.defaultBandTiles(250_000), "one row of tiles is all 64 Mpx hold");
        assertEquals(1, RasterExporter.defaultBandTiles(250_001), "one row is more, and still one");
        assertEquals(1, RasterExporter.defaultBandTiles(Integer.MAX_VALUE));
    }
}
