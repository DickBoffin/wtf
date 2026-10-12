package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.CachedTileSource;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Mercator;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.TileCache;
import net.trdlo.wtf.tiles.TileKey;
import net.trdlo.wtf.tiles.TileSource;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The three answers to a tile that was never fetched, each through a whole export, in a PDF and in
 * a PNG, and read off the finished file.
 * <p>
 * The print is two tiles by two, and the top-right one is not in the cache. Each policy is judged
 * where that tile would be and where its neighbor is: refused, nothing at all, or a red cross on a
 * proof.
 */
class MissingTilePoliciesTest {

    private static final int ZOOM = 6, TX = 34, TY = 21;
    private static final LonLatBounds SQUARE = new LonLatBounds(
            Mercator.tileXToLon(TX, ZOOM), Mercator.tileYToLat(TY + 2, ZOOM),
            Mercator.tileXToLon(TX + 2, ZOOM), Mercator.tileYToLat(TY, ZOOM));
    private static final int TILE = 0x3366AA, BACKGROUND = 0x88CC88, MARKER = 0xE06060;
    private static final int DPI = 100;

    private static ComposeJob job(final Path dir, final MissingTilePolicy missing,
                                  final ComposeJob.OutputFormat format) {
        return TestJobs.job(SQUARE).zoom(ZOOM).width(120).onItsOwnSheet().background(BACKGROUND).missing(missing)
                .formats(format).perSheet().dir(dir.resolve(missing.name())).name("gap").build();
    }

    /** Every tile of the job but the top-right one. */
    private static TileSource allButOne(final Path dir, final ComposeJob job) throws IOException {
        final TileCache cache = new TileCache(dir.resolve("cache"));
        final byte[] tile = TestJobs.solidPng(TILE);
        int stored = 0;
        for (final TileKey key : TilePlan.of(job).distinctKeys()) {
            if (key.x() != TX + 1 || key.y() != TY) {
                cache.store(key, tile, null);
                stored++;
            }
        }
        assertEquals(3, stored, "the print is the four tiles, one of them missing");
        return CachedTileSource.cacheOnly(cache, job.provider());
    }

    /** The finished sheet as pixels, with the ratio of pixels to millimeters of map. */
    private record Sheet(BufferedImage img, double pxPerMm, PrintRect slice, Path file) {

        /** The pixel at this fraction of the tile at column {@code tx}, row {@code ty}. */
        int argb(final int tx, final int ty, final double fx, final double fy) {
            final double unit = 1.0 / Mercator.tileCount(ZOOM);
            final double xMm = ((tx + fx) * unit - slice.x0()) * slice.mmPerUnit();
            final double yMm = ((ty + fy) * unit - slice.y0()) * slice.mmPerUnit();
            return img.getRGB((int) Math.floor(xMm * pxPerMm), (int) Math.floor(yMm * pxPerMm));
        }

        /** How many pixels of that tile's square, short of its edges, are near this color. */
        int near(final int tx, final int ty, final int rgb) {
            int n = 0;
            for (double fy = 0.05; fy < 0.95; fy += 0.01) {
                for (double fx = 0.05; fx < 0.95; fx += 0.01) {
                    if (close(argb(tx, ty, fx, fy), rgb)) {
                        n++;
                    }
                }
            }
            return n;
        }
    }

    private static boolean close(final int argb, final int rgb) {
        return (argb >>> 24) > 200 && Math.abs(((argb >> 16) & 0xFF) - (rgb >> 16)) + Math.abs(((argb >> 8) & 0xFF)
                - ((rgb >> 8) & 0xFF)) + Math.abs((argb & 0xFF) - (rgb & 0xFF)) < 40;
    }

    private static Sheet export(final Path dir, final ComposeJob job) throws IOException {
        final Path file = new Composer().export(job, allButOne(dir, job), 0, ProgressSink.IGNORE).files().getFirst();
        final PrintRect slice = job.layout().sheets().getFirst().slice();
        if (file.toString().endsWith(".png")) {
            final BufferedImage img = ImageIO.read(file.toFile());
            return new Sheet(img, img.getWidth() / slice.widthMm(), slice, file);
        }
        try (final PDDocument doc = Loader.loadPDF(file.toFile())) {
            // ARGB, so that what the page leaves unpainted comes out as nothing, not as white
            return new Sheet(new PDFRenderer(doc).renderImageWithDPI(0, DPI, ImageType.ARGB), DPI / 25.4, slice, file);
        }
    }

    private static String at(final Sheet s, final int tx, final int ty) {
        return String.format(Locale.ROOT, "%08X", s.argb(tx, ty, 0.25, 0.5));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ComposeJob.OutputFormat.class, names = {"PDF", "PNG"})
    @DisplayName("REFUSE writes nothing and names how many tiles are missing")
    void refuse(final ComposeJob.OutputFormat format, @TempDir final Path dir) throws IOException {
        final ComposeJob job = job(dir, MissingTilePolicy.REFUSE, format);
        final TileSource tiles = allButOne(dir, job);
        final Composer.MissingTilesException ex = assertThrows(Composer.MissingTilesException.class,
                () -> new Composer().export(job, tiles, 0, ProgressSink.IGNORE));
        assertTrue(ex.getMessage().startsWith("The export needs 1 tile(s)"), ex.getMessage());
        assertFalse(Files.exists(job.outputDir()) && Files.list(job.outputDir()).findAny().isPresent(),
                "not a file written");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ComposeJob.OutputFormat.class, names = {"PDF", "PNG"})
    @DisplayName("TRANSPARENT leaves nothing at all there, not even the background")
    void transparent(final ComposeJob.OutputFormat format, @TempDir final Path dir) throws IOException {
        final Sheet s = export(dir, job(dir, MissingTilePolicy.TRANSPARENT, format));
        assertEquals(0, s.argb(TX + 1, TY, 0.25, 0.5) >>> 24, "see-through: " + at(s, TX + 1, TY));
        assertEquals(0, s.argb(TX + 1, TY, 0.5, 0.5) >>> 24, "all of it: " + at(s, TX + 1, TY));
        assertTrue(close(s.argb(TX, TY, 0.25, 0.5), TILE), at(s, TX, TY));
        assertTrue(close(s.argb(TX + 1, TY + 1, 0.25, 0.5), TILE), "the tile below it is there");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ComposeJob.OutputFormat.class, names = {"PDF", "PNG"})
    @DisplayName("PLACEHOLDER crosses the missing tile out on the background, in a file named a proof")
    void placeholder(final ComposeJob.OutputFormat format, @TempDir final Path dir) throws IOException {
        final Sheet s = export(dir, job(dir, MissingTilePolicy.PLACEHOLDER, format));
        assertTrue(s.near(TX + 1, TY, MARKER) > 50, "a red cross: " + s.near(TX + 1, TY, MARKER) + " red pixels");
        assertTrue(close(s.argb(TX + 1, TY, 0.25, 0.5), BACKGROUND), "off the cross, the background: "
                + at(s, TX + 1, TY));
        for (final int[] t : new int[][]{{TX, TY}, {TX, TY + 1}, {TX + 1, TY + 1}}) {
            assertEquals(0, s.near(t[0], t[1], MARKER), "no cross on a tile that is there");
        }
        assertTrue(s.file().getFileName().toString().matches("gap-s01-proof\\.(pdf|png)"), s.file().toString());
    }
}
