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
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A subject with a hole – an enclave, a lake – whose rings run the same way round. Under the
 * nonzero winding rule such a hole is filled; the inside region's clip has to be even-odd, as the
 * outside region's and the raster's are, or the enclave prints as the country around it.
 * <p>
 * The outside is not drawn, a stencil: drawn after the inside, its tiles would cover the mistake.
 */
class HoledSubjectTest {

    private static final int PAPER = 0xFFFFFF, INSIDE = 0xCC2222;
    private static final int ZOOM = 9;

    private static final LonLatBounds AREA = new LonLatBounds(12, 48.5, 17, 51);

    /**
     * Both rings counterclockwise in degrees, as a file may well give them. The hole's west edge
     * is just inside tile 275 at zoom 9 (13.36° to 14.06°), which the inside region therefore has
     * and clips: a tile wholly in the hole is not planned for it, so only such a tile shows the rule.
     */
    private static final Polygon SUBJECT = Shapes.GF.createPolygon(
            ring(13, 49, 16, 50.6), new LinearRing[]{ring(13.40, 49.5, 15, 50.1)});

    private static LinearRing ring(final double w, final double s, final double e, final double n) {
        return Shapes.GF.createLinearRing(new Coordinate[]{
                new Coordinate(w, s), new Coordinate(e, s), new Coordinate(e, n), new Coordinate(w, n),
                new Coordinate(w, s)});
    }

    private static ComposeJob job(final Path dir, final ComposeJob.OutputFormat format) {
        return TestJobs.job(AREA).subject(SUBJECT)
                .regions(new Regions(ZOOM, Regions.RegionSpec.PLAIN, Regions.RegionSpec.BLANK, null))
                .width(250).missing(MissingTilePolicy.REFUSE).formats(format).dir(dir).name("holed").build();
    }

    /** Every tile the job needs, which is the inside's alone. */
    private static TileSource solidTiles(final Path dir, final ComposeJob job) throws IOException {
        final TileCache cache = new TileCache(dir.resolve("cache"));
        final byte[] inside = TestJobs.solidPng(INSIDE);
        for (final TileKey key : TilePlan.of(job).distinctKeys()) {
            cache.store(key, inside, null);
        }
        return CachedTileSource.cacheOnly(cache, job.provider());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ComposeJob.OutputFormat.class, names = {"PDF", "PNG"})
    @DisplayName("a hole running the same way as its shell is outside the subject")
    void theHoleIsOutside(final ComposeJob.OutputFormat format, @TempDir final Path dir) throws IOException {
        final ComposeJob job = job(dir, format);
        assertEquals(1, job.layout().count(), "one sheet");
        final Path file = new Composer().export(job, solidTiles(dir, job), 1, ProgressSink.IGNORE).files()
                .stream().filter(p -> p.toString().endsWith(format.isPdf() ? ".pdf" : ".png"))
                .findFirst().orElseThrow();

        final SheetLayout.Sheet sheet = job.layout().sheets().getFirst();
        final BufferedImage img;
        final double unitPerPx;
        if (format == ComposeJob.OutputFormat.PNG) {
            img = ImageIO.read(file.toFile());
            unitPerPx = sheet.slice().unitWidth() / img.getWidth();
        } else {
            final int dpi = 40;
            try (final PDDocument doc = Loader.loadPDF(file.toFile())) {
                img = new PDFRenderer(doc).renderImageWithDPI(0, dpi);
            }
            unitPerPx = 25.4 / dpi / sheet.slice().mmPerUnit();
        }
        assertColor(PAPER, img, sheet, unitPerPx, 13.75, 49.8, "in the hole, under a tile the inside has");
        assertColor(PAPER, img, sheet, unitPerPx, 14.5, 49.8, "the middle of the hole");
        assertColor(INSIDE, img, sheet, unitPerPx, 13.2, 49.8, "between the hole and the shell");
        assertColor(PAPER, img, sheet, unitPerPx, 12.5, 49.8, "past the shell");
    }

    private static void assertColor(final int expected, final BufferedImage img, final SheetLayout.Sheet sheet,
                                    final double unitPerPx, final double lon, final double lat,
                                    final String where) {
        final int px = (int) ((Mercator.lonToUnitX(lon) - sheet.slice().x0()) / unitPerPx);
        final int py = (int) ((Mercator.latToUnitY(lat) - sheet.slice().y0()) / unitPerPx);
        TestJobs.assertColor(expected, img, px, py, 24, where + " at");
    }
}
