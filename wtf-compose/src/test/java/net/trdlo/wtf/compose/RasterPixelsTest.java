package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.CachedTileSource;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Mercator;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.TileCache;
import net.trdlo.wtf.tiles.TileKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A raster at its tiles' own zoom copies their pixels. The tiles are a checkerboard of single
 * pixels in two colors, so any resampling at all – a tile drawn at a fractional offset through
 * bilinear filtering – blends each pixel with its
 * neighbors, and a color appears that the tiles do not have.
 * <p>
 * The tile is a palette PNG, as most map tiles are, so the faded case takes the palette path.
 */
class RasterPixelsTest {

    private static final int ZOOM = 6;
    /** Edges that fall between the zoom's pixels, so any resampling has fractions to work at. */
    private static final LonLatBounds AREA = new LonLatBounds(12.37, 48.61, 16.83, 50.94);
    private static final int ONE = 0x336699, OTHER = 0xEEDD22;

    private static byte[] checkerboard() throws IOException {
        final IndexColorModel two = new IndexColorModel(4, 2,
                new byte[]{(byte) (ONE >> 16), (byte) (OTHER >> 16)},
                new byte[]{(byte) (ONE >> 8), (byte) (OTHER >> 8)},
                new byte[]{(byte) ONE, (byte) OTHER});
        final BufferedImage img = new BufferedImage(Mercator.TILE_SIZE, Mercator.TILE_SIZE,
                BufferedImage.TYPE_BYTE_BINARY, two);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                img.getRaster().setSample(x, y, 0, (x + y) % 2);
            }
        }
        return TestJobs.png(img);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("a small TIFF is compressed, and reads back as it was drawn")
    void aSmallTiffIsCompressed(@TempDir final Path dir) throws IOException {
        final ComposeJob job = TestJobs.job(AREA).zoom(ZOOM).background(ONE).formats(ComposeJob.OutputFormat.TIFF)
                .perSheet().dir(dir).name("flat").build();
        final Path tif = new Composer().export(job, TestJobs.solidCache(dir.resolve("cache"), job, ONE), 0,
                        ProgressSink.IGNORE)
                .files().stream().filter(p -> p.toString().endsWith(".tif")).findFirst().orElseThrow();

        final BufferedImage img = ImageIO.read(tif.toFile());
        final long raw = (long) img.getWidth() * img.getHeight() * 3;
        assertTrue(java.nio.file.Files.size(tif) < raw / 10, () -> "a flat sheet of " + raw
                + " bytes of pixels is not compressed to " + tif.toFile().length());
        assertEquals(ONE, img.getRGB(img.getWidth() / 2, img.getHeight() / 2) & 0xFFFFFF, "and it is the sheet");
    }

    @ParameterizedTest(name = "faded: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("a raster at the tiles' own zoom has their colors and no others")
    void pixelForPixel(final boolean faded, @TempDir final Path dir) throws IOException {
        final TileEffect effect = faded ? TileEffect.FADED : TileEffect.NONE;
        final ComposeJob job = TestJobs.job(AREA).zoom(ZOOM).region(new Regions.RegionSpec(effect, true)).missing(MissingTilePolicy.REFUSE)
                .formats(ComposeJob.OutputFormat.PNG).perSheet().dir(dir).name("pixels").build();
        final TileCache cache = new TileCache(dir.resolve("cache"));
        final byte[] tile = checkerboard();
        for (final TileKey key : TilePlan.of(job).distinctKeys()) {
            cache.store(key, tile, null);
        }
        final Path png = new Composer()
                .export(job, CachedTileSource.cacheOnly(cache, job.provider()), 1, ProgressSink.IGNORE)
                .files().stream().filter(p -> p.toString().endsWith(".png")).findFirst().orElseThrow();

        final Set<Integer> expected = Set.of(effect.apply(ONE), effect.apply(OTHER));
        final BufferedImage img = ImageIO.read(png.toFile());
        int wrong = 0;
        String first = null;
        for (int y = 2; y < img.getHeight() - 2; y++) {
            for (int x = 2; x < img.getWidth() - 2; x++) {
                final int rgb = img.getRGB(x, y) & 0xFFFFFF;
                if (!expected.contains(rgb)) {
                    wrong++;
                    if (first == null) {
                        first = String.format(Locale.ROOT, "(%d, %d) %06X", x, y, rgb);
                    }
                }
            }
        }
        assertTrue(img.getWidth() > 100 && img.getHeight() > 100, "a sheet worth looking at");
        assertEquals(0, wrong, "pixels in a color the tiles do not have, the first " + first);
        assertNotEquals(img.getRGB(10, 10), img.getRGB(11, 10), "and the checkerboard is still one");
    }
}
