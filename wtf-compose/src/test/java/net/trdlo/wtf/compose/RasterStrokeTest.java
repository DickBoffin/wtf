package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.CachedTileSource;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Mercator;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.TileCache;
import net.trdlo.wtf.tiles.TileKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A raster of a stroked print has the stroke, as the PDF does: along the whole outline, across
 * every band it runs through, and nowhere a few millimeters off it.
 */
class RasterStrokeTest {

    /** The outside's treatment, which is what tells the two regions apart on the paper. */
    private static final TileEffect GRAYED = new TileEffect(0, 0, 0);
    private static final int INSIDE = 0xCC2222, OUTSIDE = GRAYED.apply(INSIDE), STROKE = 0x000000;
    private static final int ZOOM = 9;
    private static final LonLatBounds AREA = new LonLatBounds(12, 48.5, 17, 51);
    private static final LonLatBounds SUBJECT = new LonLatBounds(13, 49, 16, 50.6);

    @Test
    @DisplayName("a PNG strokes the subject's outline across every band, and only there")
    void theOutlineIsStroked(@TempDir final Path dir) throws IOException {
        final ComposeJob job = TestJobs.job(AREA).subject(Shapes.rectangle(SUBJECT))
                .regions(new Regions(ZOOM, Regions.RegionSpec.PLAIN, new Regions.RegionSpec(GRAYED, true),
                        new Regions.BoundaryStroke(1, STROKE)))
                .width(250).missing(MissingTilePolicy.REFUSE).formats(ComposeJob.OutputFormat.PNG).perSheet()
                .dir(dir).name("stroked").build();
        assertEquals(1, job.layout().count());
        final TileCache cache = new TileCache(dir.resolve("cache"));
        final byte[] tile = TestJobs.solidPng(INSIDE);
        for (final TileKey key : TilePlan.of(job).distinctKeys()) {
            cache.store(key, tile, null);
        }
        // A band of one row of tiles, so that the outline crosses many of them
        final Path png = new Composer()
                .export(job, CachedTileSource.cacheOnly(cache, job.provider()), 1, ProgressSink.IGNORE)
                .files().stream().filter(p -> p.toString().endsWith(".png")).findFirst().orElseThrow();
        final BufferedImage img = ImageIO.read(png.toFile());
        final PrintRect slice = job.layout().sheets().getFirst().slice();
        final double pxPerUnit = img.getWidth() / slice.unitWidth();
        final double mmPerDegree = 250.0 / AREA.widthDegrees();

        // Short of the corners at both ends, where the other edge's stroke is
        for (int i = 2; i <= 30; i++) {
            final double lat = SUBJECT.south() + i * 0.05;
            assertColor(STROKE, img, slice, pxPerUnit, SUBJECT.west(), lat, "on the west edge");
            assertColor(STROKE, img, slice, pxPerUnit, SUBJECT.east(), lat, "on the east edge");
            assertColor(OUTSIDE, img, slice, pxPerUnit, SUBJECT.west() - 3 / mmPerDegree, lat, "3 mm outside");
            assertColor(INSIDE, img, slice, pxPerUnit, SUBJECT.west() + 3 / mmPerDegree, lat, "3 mm inside");
        }
        for (int i = 2; i <= 58; i++) {
            final double lon = SUBJECT.west() + i * 0.05;
            assertColor(STROKE, img, slice, pxPerUnit, lon, SUBJECT.north(), "on the north edge");
            assertColor(STROKE, img, slice, pxPerUnit, lon, SUBJECT.south(), "on the south edge");
        }
    }

    private static void assertColor(final int expected, final BufferedImage img, final PrintRect slice,
                                    final double pxPerUnit, final double lon, final double lat,
                                    final String where) {
        final int px = (int) Math.floor((Mercator.lonToUnitX(lon) - slice.x0()) * pxPerUnit);
        final int py = (int) Math.floor((Mercator.latToUnitY(lat) - slice.y0()) * pxPerUnit);
        TestJobs.assertColor(expected, img, px, py, 48,
                String.format(Locale.ROOT, "%s at %.2f, %.2f", where, lon, lat));
    }
}
