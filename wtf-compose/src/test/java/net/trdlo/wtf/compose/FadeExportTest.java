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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The fade across a growth, as it is printed: the map whole inside the fitted box, half there
 * halfway across the band, and gone at the print's edge – in the PDF by a soft mask over tiles
 * that are still copied, and in a raster by its alpha.
 * <p>
 * A box from 7° W to 1° E grown to the Bay of Biscay's, 10° W to 4° E, on a sheet of its own size
 * so that the print's edge is the bay's: along 48° N the band is three degrees wide on the west,
 * and the north and south edges are farther than that.
 */
class FadeExportTest {

    private static final Provider OSM = ProviderCatalog.builtIn().require("osm");
    private static final int TILE = 0x3366CC, BACKGROUND = 0xE0C080;
    private static final LonLatBounds BISCAY = new LonLatBounds(-10, 46, 4, 50);
    private static final LonLatBounds FIT = new LonLatBounds(-7, 47, 1, 49);
    private static final double LAT = 48;

    private static ComposeJob job(final Path dir, final ComposeJob.OutputFormat format) {
        return TestJobs.job(BISCAY).onItsOwnSheet().fadeFrom(Shapes.rectangle(FIT)).background(BACKGROUND)
                .formats(format).dir(dir).name("fade").build();
    }

    private static TileSource tiles(final Path dir, final ComposeJob job) throws IOException {
        final TileCache cache = new TileCache(dir.resolve("cache"));
        final byte[] tile = TestJobs.solidPng(TILE);
        for (final TileKey key : TilePlan.of(job).distinctKeys()) {
            cache.store(key, tile, null);
        }
        return CachedTileSource.cacheOnly(cache, OSM);
    }

    private record Printed(BufferedImage img, PrintRect slice, double pxPerUnit, Composer.ExportReport report) {

        static Printed of(final ComposeJob job, final TileSource tiles, final ImageType pdfAs) throws IOException {
            final Composer.ExportReport report = new Composer().export(job, tiles, 0, ProgressSink.IGNORE);
            final SheetLayout.Sheet sheet = job.layout().sheets().getFirst();
            final ComposeJob.OutputFormat format = job.formats().iterator().next();
            if (format != ComposeJob.OutputFormat.PDF) {
                final BufferedImage img = ImageIO.read(PdfExporter.fileFor(job, sheet,
                        format == ComposeJob.OutputFormat.PNG ? "png" : "tif").toFile());
                return new Printed(img, sheet.slice(), img.getWidth() / sheet.slice().unitWidth(), report);
            }
            final int dpi = 72;
            try (final PDDocument doc = Loader.loadPDF(PdfExporter.fileFor(job, null, "pdf").toFile())) {
                return new Printed(new PDFRenderer(doc).renderImageWithDPI(0, dpi, pdfAs),
                        sheet.slice(), sheet.slice().mmPerUnit() / 25.4 * dpi, report);
            }
        }

        int at(final double lon, final double lat) {
            final int x = (int) ((Mercator.lonToUnitX(lon) - slice.x0()) * pxPerUnit);
            final int y = (int) ((Mercator.latToUnitY(lat) - slice.y0()) * pxPerUnit);
            return img.getRGB(x, y);
        }

        double alphaAt(final double lon, final double lat) {
            return (at(lon, lat) >>> 24) / 255.0;
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ComposeJob.OutputFormat.class, names = {"PDF", "PNG", "TIFF"})
    @DisplayName("the map is whole inside the fit, half there halfway across the band, and gone at the edge")
    void theMapFadesAcrossTheGrowth(final ComposeJob.OutputFormat format, @TempDir final Path dir)
            throws IOException {
        final ComposeJob job = job(dir, format);
        final Printed printed = Printed.of(job, tiles(dir, job), ImageType.ARGB);
        assertTrue(printed.img().getColorModel().hasAlpha(), "the print has an alpha channel");

        assertEquals(1, printed.alphaAt(-3, LAT), 0.01, "inside the fit");
        assertEquals(0xFF, printed.at(-3, LAT) >>> 24);
        assertEquals(TILE, printed.at(-3, LAT) & 0xFFFFFF, "and in the tile's own color");
        assertEquals(0.5, printed.alphaAt(-8.5, LAT), 0.05, "halfway across the band");
        assertEquals(0.75, printed.alphaAt(-7.75, LAT), 0.05, "a quarter of the way");
        assertTrue(printed.alphaAt(-9.93, LAT) < 0.1, "at the edge of the print");
        assertEquals(0.5, printed.alphaAt(2.5, LAT), 0.05, "halfway across on the east as well");
    }

    @Test
    @DisplayName("on paper the map fades to white, and not one tile was decoded for it")
    void onPaperItFadesToWhite(@TempDir final Path dir) throws IOException {
        final ComposeJob job = job(dir, ComposeJob.OutputFormat.PDF);
        final Printed printed = Printed.of(job, tiles(dir, job), ImageType.RGB);

        assertTrue(printed.report().images().decoded().isEmpty(),
                () -> "decoded for the fade: " + printed.report().images().decoded());
        final int half = printed.at(-8.5, LAT);
        for (int shift = 0; shift <= 16; shift += 8) {
            final int expected = ((TILE >> shift & 0xFF) + 0xFF) / 2;
            assertEquals(expected, half >> shift & 0xFF, 14, "halfway between the tile and the paper");
        }
        assertEquals(TILE, printed.at(-3, LAT) & 0xFFFFFF, "inside the fit");
    }

    @Test
    @DisplayName("a faded export is the same file every time")
    void theSameFileEveryTime(@TempDir final Path dir) throws IOException {
        final ComposeJob first = job(dir.resolve("first"), ComposeJob.OutputFormat.PDF);
        final TileSource tiles = tiles(dir, first);
        new Composer().export(first, tiles, 0, ProgressSink.IGNORE);
        final ComposeJob second = job(dir.resolve("second"), ComposeJob.OutputFormat.PDF);
        new Composer().export(second, tiles, 0, ProgressSink.IGNORE);

        final byte[] a = Files.readAllBytes(PdfExporter.fileFor(first, null, "pdf"));
        assertArrayEquals(a, Files.readAllBytes(PdfExporter.fileFor(second, null, "pdf")));

        // And not the same file as the print with a hard edge
        final ComposeJob hard = TestJobs.from(first).fadeFrom(null).dir(dir.resolve("hard")).build();
        new Composer().export(hard, tiles, 0, ProgressSink.IGNORE);
        assertFalse(java.util.Arrays.equals(a, Files.readAllBytes(PdfExporter.fileFor(hard, null, "pdf"))));
    }
}
