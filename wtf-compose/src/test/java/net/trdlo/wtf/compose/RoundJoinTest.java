package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.CachedTileSource;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Mercator;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.TileCache;
import net.trdlo.wtf.tiles.TileKey;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The outline's stroke turns its corners round, in the PDF and the raster alike.
 * <p>
 * The subject is a triangle with a sharp top, 26 degrees, and the stroke is 2 mm wide. A mitered
 * join, at PDF's default limit of 10, would put a spike 4.5 mm above that corner; a round one
 * reaches 1 mm. Half-way between, 2.5 mm above, is the map outside the subject.
 */
class RoundJoinTest {

    /** The outside's treatment, which is what tells the two regions apart on the paper. */
    private static final TileEffect GRAYED = new TileEffect(0, 0, 0);
    private static final int INSIDE = 0xCC2222, OUTSIDE = GRAYED.apply(INSIDE), STROKE = 0x000000;
    private static final LonLatBounds AREA = new LonLatBounds(12, 48.5, 17, 51);
    private static final double WIDTH_MM = 250;
    private static final Coordinate APEX = new Coordinate(14.5, 50.6);

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ComposeJob.OutputFormat.class, names = {"PDF", "PNG"})
    @DisplayName("a sharp corner of the outline is stroked round, with no miter spike past it")
    void aSharpCornerIsRound(final ComposeJob.OutputFormat format, @TempDir final Path dir) throws IOException {
        final Geometry triangle = Shapes.GF.createPolygon(new Coordinate[]{
                new Coordinate(14.0, 49.2), new Coordinate(15.0, 49.2), APEX, new Coordinate(14.0, 49.2)});
        final ComposeJob job = TestJobs.job(AREA).subject(triangle)
                .regions(new Regions(9, Regions.RegionSpec.PLAIN, new Regions.RegionSpec(GRAYED, true),
                        new Regions.BoundaryStroke(2, STROKE)))
                .width(WIDTH_MM).missing(MissingTilePolicy.REFUSE).formats(format).perSheet().dir(dir).name("corner")
                .build();
        assertEquals(1, job.layout().count());
        final TileCache cache = new TileCache(dir.resolve("cache"));
        final byte[] tile = TestJobs.solidPng(INSIDE);
        for (final TileKey key : TilePlan.of(job).distinctKeys()) {
            cache.store(key, tile, null);
        }
        final Path file = new Composer()
                .export(job, CachedTileSource.cacheOnly(cache, job.provider()), 0, ProgressSink.IGNORE)
                .files().getFirst();

        final PrintRect slice = job.layout().sheets().getFirst().slice();
        final BufferedImage img;
        final double pxPerMm;
        if (format == ComposeJob.OutputFormat.PNG) {
            img = ImageIO.read(file.toFile());
            pxPerMm = img.getWidth() / slice.widthMm();
        } else {
            try (final PDDocument doc = Loader.loadPDF(file.toFile())) {
                img = new PDFRenderer(doc).renderImageWithDPI(0, 300);
            }
            pxPerMm = 300 / 25.4;
        }
        final double apexX = (Mercator.lonToUnitX(APEX.x) - slice.x0()) * slice.mmPerUnit();
        final double apexY = (Mercator.latToUnitY(APEX.y) - slice.y0()) * slice.mmPerUnit();

        assertColor(STROKE, img, pxPerMm, apexX, apexY, "on the corner");
        assertColor(STROKE, img, pxPerMm, apexX, apexY - 0.6, "within the round join");
        assertColor(OUTSIDE, img, pxPerMm, apexX, apexY - 2.5, "where a miter would have reached");
    }

    private static void assertColor(final int expected, final BufferedImage img, final double pxPerMm,
                                    final double xMm, final double yMm, final String where) {
        final int px = (int) Math.floor(xMm * pxPerMm);
        final int py = (int) Math.floor(yMm * pxPerMm);
        TestJobs.assertColor(expected, img, px, py, 48,
                String.format(Locale.ROOT, "%s, %.1f mm by %.1f mm", where, xMm, yMm));
    }
}
