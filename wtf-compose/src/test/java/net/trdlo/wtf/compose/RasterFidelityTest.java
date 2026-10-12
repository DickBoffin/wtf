package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.CachedTileSource;
import net.trdlo.wtf.tiles.Mercator;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.TileCache;
import net.trdlo.wtf.tiles.TileKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Polygon;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A raster shows nothing the PDF does not, in two ways it could: lettering set in whatever the
 * exporting machine's logical {@code SansSerif} happens to map to, rather than the bundled face
 * the PDF uses (D29), and a shaped print's paper painted opaque white outside its outline where
 * the PDF leaves it unpainted (D28).
 */
class RasterFidelityTest {

    @Test
    @DisplayName("a raster's marks are set in the bundled Liberation Sans, not the machine's logical sans-serif")
    void theMarksAreTheBundledFont() throws IOException {
        // Java's logical SansSerif maps to whatever face the machine running the export happens
        // to have – Arial, Dialog, DejaVu Sans – never this one, so the family name is a fair,
        // machine-independent witness that the fix is in place rather than a coincidence of the
        // machine running the test.
        assertEquals("Liberation Sans", RasterExporter.lettering().getFamily(Locale.ROOT));
    }

    /** A circle of this many points in the unit square, as the shape in degrees that projects to it. */
    private static Polygon circle(final double cx, final double cy, final double r, final int points) {
        final Coordinate[] ring = new Coordinate[points + 1];
        for (int i = 0; i < points; i++) {
            final double a = 2 * Math.PI * i / points;
            ring[i] = new Coordinate(Mercator.unitXToLon(cx + r * Math.cos(a)),
                    Mercator.unitYToLat(cy + r * Math.sin(a)));
        }
        ring[points] = ring[0];
        return Shapes.GF.createPolygon(ring);
    }

    @Test
    @DisplayName("under TRANSPARENT, a shaped print's PNG is see-through outside the outline, not opaque white")
    void outsideTheOutlineIsTransparent(@TempDir final Path dir) throws IOException {
        final double cx = Mercator.lonToUnitX(14.5), cy = Mercator.latToUnitY(49.8);
        final Polygon area = circle(cx, cy, 0.01, 200);
        final ComposeJob job = TestJobs.job(area).zoom(8).width(120).background(0x000000)
                .missing(MissingTilePolicy.TRANSPARENT).formats(ComposeJob.OutputFormat.PNG).perSheet().dir(dir)
                .name("circle").build();
        assertEquals(1, job.layout().count(), "small enough for one sheet");

        final TileCache cache = new TileCache(dir.resolve("cache"));
        final byte[] tile = TestJobs.solidPng(0x336699);
        for (final TileKey key : TilePlan.of(job).distinctKeys()) {
            cache.store(key, tile, null);
        }
        final Path png = new Composer()
                .export(job, CachedTileSource.cacheOnly(cache, job.provider()), 0, ProgressSink.IGNORE)
                .files().stream().filter(p -> p.toString().endsWith(".png")).findFirst().orElseThrow();
        final BufferedImage img = ImageIO.read(png.toFile());

        // The corner of a box exactly the circle's own bounds is always outside the circle: past
        // the outline, where a PDF leaves the page unpainted, and so does this raster.
        final int corner = img.getRGB(0, 0);
        assertEquals(0, corner >>> 24, () -> String.format(Locale.ROOT, "the corner is %08X, not see-through", corner));

        // The middle of that same box is the circle's own center: opaque, and the tile's color.
        final int center = img.getRGB(img.getWidth() / 2, img.getHeight() / 2);
        assertEquals(0xFF, center >>> 24, "the middle of the circle is opaque");
    }
}
