package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.CachedTileSource;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Mercator;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import net.trdlo.wtf.tiles.TileCache;
import net.trdlo.wtf.tiles.TileKey;
import net.trdlo.wtf.tiles.TileSource;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Geometry;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code TRANSPARENT} leaves a missing tile see-through: the PDF paints no background under it, and a
 * PNG or a TIFF has an alpha channel (D28). A tile that does not exist is the map, and keeps the
 * background.
 * <p>
 * Six tiles at zoom 6: one never fetched, one the provider says does not exist and four cached,
 * in a color of their own. The background is another color, so each shows as what it is.
 */
class TransparentTest {

    private static final Provider OSM = ProviderCatalog.builtIn().require("osm");
    private static final int TILE = 0x3366CC, BACKGROUND = 0xE0C080;

    /** Three columns and two rows of tiles at zoom 6, with no edge on a tile boundary. */
    private static final LonLatBounds BISCAY = new LonLatBounds(-10, 46, 4, 50);

    /** Well inside the top row, which the missing and the empty tile are in. */
    private static final double TOP_ROW_LAT = 49.48;

    private static ComposeJob job(final Path dir, final MissingTilePolicy policy, final @Nullable Geometry subject,
                                  final Regions regions, final ComposeJob.OutputFormat format) {
        return TestJobs.job(BISCAY).subject(subject).regions(regions).background(BACKGROUND)
                .missing(policy).formats(format).dir(dir.resolve(policy.name())).name("holes").build();
    }

    private static ComposeJob plain(final Path dir, final MissingTilePolicy policy,
                                    final ComposeJob.OutputFormat format) {
        return job(dir, policy, null, Regions.single(6), format);
    }

    /** Every tile of the job cached, except the one at {@code missingX} and the one at {@code emptyX}. */
    private static TileSource tiles(final Path dir, final ComposeJob job, final int missingX, final int emptyX)
            throws IOException {
        final TileCache cache = new TileCache(dir.resolve("cache"));
        final byte[] tile = TestJobs.solidPng(TILE);
        for (final TileKey key : TilePlan.of(job).distinctKeys()) {
            if (key.y() == 21 && key.x() == emptyX) {
                cache.recordEmpty(key);
            } else if (key.y() != 21 || key.x() != missingX) {
                cache.store(key, tile, null);
            }
        }
        return CachedTileSource.cacheOnly(cache, OSM);
    }

    /** The sheet as it is printed, transparency and all. */
    private record Printed(BufferedImage img, PrintRect slice, double pxPerUnit) {

        static Printed of(final ComposeJob job, final TileSource tiles) throws IOException {
            new Composer().export(job, tiles, 0, ProgressSink.IGNORE);
            final SheetLayout.Sheet sheet = job.layout().sheets().getFirst();
            final ComposeJob.OutputFormat format = job.formats().iterator().next();
            if (format != ComposeJob.OutputFormat.PDF) {
                final BufferedImage img = ImageIO.read(PdfExporter.fileFor(job, sheet,
                        format == ComposeJob.OutputFormat.PNG ? "png" : "tif").toFile());
                return new Printed(img, sheet.slice(), img.getWidth() / sheet.slice().unitWidth());
            }
            final int dpi = 36;
            try (final PDDocument doc = Loader.loadPDF(PdfExporter.fileFor(job, null, "pdf").toFile())) {
                return new Printed(new PDFRenderer(doc).renderImageWithDPI(0, dpi, ImageType.ARGB),
                        sheet.slice(), sheet.slice().mmPerUnit() / 25.4 * dpi);
            }
        }

        /** Packed ARGB at a place on the map. */
        int at(final double lon, final double lat) {
            final int x = (int) ((Mercator.lonToUnitX(lon) - slice.x0()) * pxPerUnit);
            final int y = (int) ((Mercator.latToUnitY(lat) - slice.y0()) * pxPerUnit);
            return img.getRGB(x, y);
        }
    }

    private static void assertColor(final int expectedRgb, final int argb, final String what) {
        assertEquals(0xFF, argb >>> 24, () -> what + " is opaque");
        for (int shift = 0; shift <= 16; shift += 8) {
            final int e = expectedRgb >> shift & 0xFF, a = argb >> shift & 0xFF;
            assertTrue(Math.abs(e - a) <= 3, () -> String.format(Locale.ROOT, "%s is %06X, not %06X",
                    what, argb & 0xFFFFFF, expectedRgb));
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ComposeJob.OutputFormat.class, names = {"PDF", "PNG", "TIFF"})
    @DisplayName("a missing tile is see-through under TRANSPARENT, and one that does not exist is the background")
    void aHoleIsSeeThrough(final ComposeJob.OutputFormat format, @TempDir final Path dir) throws IOException {
        final ComposeJob transparent = plain(dir, MissingTilePolicy.TRANSPARENT, format);
        final TileSource tiles = tiles(dir, transparent, 30, 31);

        final Printed seeThrough = Printed.of(transparent, tiles);
        assertTrue(seeThrough.img().getColorModel().hasAlpha(), "the print has an alpha channel");
        assertEquals(0, seeThrough.at(-7.8, TOP_ROW_LAT) >>> 24, "the missing tile is see-through");
        assertColor(BACKGROUND, seeThrough.at(-2.8, TOP_ROW_LAT), "a tile that does not exist, which is the map,");
        assertColor(TILE, seeThrough.at(2, 47), "a cached tile");
    }

    /**
     * The missing tile straddles the subject, and it is the surround's: the subject is not drawn,
     * so inside it the page is the background, and only the surround's part of the tile is a hole.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ComposeJob.OutputFormat.class, names = {"PDF", "PNG", "TIFF"})
    @DisplayName("a hole is a hole on its own region's side of the subject only")
    void aHoleIsItsRegions(final ComposeJob.OutputFormat format, @TempDir final Path dir) throws IOException {
        // The subject's edge runs down the middle of the missing tile, which spans 5.625° W to 0°
        final Geometry subject = Shapes.rectangle(new LonLatBounds(-12, 45, -2.8, 51));
        final ComposeJob job = job(dir, MissingTilePolicy.TRANSPARENT, subject,
                new Regions(6, Regions.RegionSpec.BLANK, Regions.RegionSpec.PLAIN, null), format);
        final Printed printed = Printed.of(job, tiles(dir, job, 31, -1));

        assertColor(BACKGROUND, printed.at(-4.2, TOP_ROW_LAT), "the missing tile's part inside the subject");
        assertEquals(0, printed.at(-1.4, TOP_ROW_LAT) >>> 24, "its part outside the subject is see-through");
        assertColor(BACKGROUND, printed.at(-7.8, TOP_ROW_LAT), "the subject, which is not drawn,");
        assertColor(TILE, printed.at(2, 47), "the surround");
    }
}
