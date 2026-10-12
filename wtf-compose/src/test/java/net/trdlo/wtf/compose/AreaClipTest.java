package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.Mercator;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.TileKey;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An area that is not a rectangle ends the print at its outline, not at the edges of the tiles it
 * touched. Every tile is one color, so the map is that color wherever it is printed and the paper
 * is white wherever it is not.
 */
class AreaClipTest {

    private static final int MAP = 0x3366CC;

    /** A triangle: its bounding box's top corners are outside it, the middle of its base inside. */
    private static final Geometry TRIANGLE = new GeometryFactory().createPolygon(new Coordinate[]{
            new Coordinate(12.0, 49.4), new Coordinate(16.0, 49.4), new Coordinate(14.0, 50.8),
            new Coordinate(12.0, 49.4)});

    private static ComposeJob job(final Path dir, final ComposeJob.OutputFormat format) {
        return job(dir, format, 6);
    }

    /** The triangle 200 mm across, on one A4 landscape sheet: 97 mm across and 101 mm down are spare. */
    private static ComposeJob job(final Path dir, final ComposeJob.OutputFormat format, final int zoom) {
        return TestJobs.job(TRIANGLE).zoom(zoom).background(MAP).missing(MissingTilePolicy.REFUSE)
                .formats(format)
                .dir(dir).name("clip").build();
    }

    /** The same triangle at zoom 9 on one sheet cut to the print's own size, so no paper is spare. */
    private static ComposeJob fitted(final Path dir, final ComposeJob.OutputFormat format) {
        return TestJobs.from(job(dir, format, 9)).onItsOwnSheet().build();
    }

    private static Path export(final Path dir, final ComposeJob job, final String extension) throws IOException {
        return new Composer().export(job, TestJobs.solidCache(dir.resolve("cache"), job, MAP), 0,
                        ProgressSink.IGNORE).files().stream()
                .filter(p -> p.toString().endsWith(extension)).findFirst().orElseThrow();
    }

    /**
     * Samples the corners of the box the map fills and the middle of its base: the triangle's
     * bounding box, wherever on the sheet the spare paper put it.
     */
    private static void assertClipped(final BufferedImage img) {
        final Printed map = printed(img);
        final int x0 = map.left(), y0 = map.top(), w = map.width(), h = map.height();
        assertEquals(0xFFFFFF, img.getRGB(x0 + w / 20, y0 + h / 20) & 0xFFFFFF,
                "top left, outside the triangle: paper");
        assertEquals(0xFFFFFF, img.getRGB(x0 + w - w / 20, y0 + h / 20) & 0xFFFFFF,
                "top right, outside it: paper");
        final int inside = img.getRGB(x0 + w / 2, y0 + h - h / 10) & 0xFFFFFF;
        assertNotEquals(0xFFFFFF, inside, "the middle of the base is map");
    }

    @Test
    @DisplayName("a PDF of a triangle is a triangle")
    void pdf(@TempDir final Path dir) throws IOException {
        final Path pdf = export(dir, job(dir, ComposeJob.OutputFormat.PDF), ".pdf");
        try (final PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            assertClipped(new PDFRenderer(doc).renderImageWithDPI(0, 36));
        }
    }

    @Test
    @DisplayName("and so is a PNG")
    void png(@TempDir final Path dir) throws IOException {
        assertClipped(ImageIO.read(export(dir, job(dir, ComposeJob.OutputFormat.PNG), ".png").toFile()));
    }

    @Test
    @DisplayName("a rectangle is not clipped at all, so its tiles are placed exactly as before")
    void aRectangleIsLeftAlone(@TempDir final Path dir) throws IOException {
        final ComposeJob job = TestJobs.job(Shapes.boundsOf(TRIANGLE)).background(MAP)
                .formats(ComposeJob.OutputFormat.PNG).dir(dir).name("box").build();
        final BufferedImage img = ImageIO.read(export(dir, job, ".png").toFile());
        assertEquals(MAP, img.getRGB(2, 2) & 0xFFFFFF, "the corner is map");
    }

    // ------------------------------------------------------------------ the spare paper, D34

    /**
     * The spare paper around an outline: the outline stays the edge and the spare is paper, so a
     * tile in the spare band would be downloaded and then clipped away. Zoom 9 puts the triangle
     * across six tiles and the spare band across two more on every side.
     */
    @Test
    @DisplayName("a triangle with paper to spare needs no tile outside itself")
    void theSparePaperPlansNoTileOutsideTheOutline(@TempDir final Path dir) {
        final ComposeJob spare = job(dir, ComposeJob.OutputFormat.PDF, 9);
        assertTrue(spare.layout().print().widthMm() > spare.print().widthMm()
                && spare.layout().print().heightMm() > spare.print().heightMm(), "there is spare paper both ways");

        final Geometry triangle = Shapes.toUnitSquare(TRIANGLE);
        final Set<TileKey> planned = TilePlan.of(spare).distinctKeys();
        for (final TileKey key : planned) {
            final double s = 1.0 / Mercator.tileCount(key.zoom());
            final Geometry tile = Shapes.GF.toGeometry(
                    new Envelope(key.x() * s, (key.x() + 1) * s, key.y() * s, (key.y() + 1) * s));
            assertTrue(triangle.intersects(tile), () -> key + " lies wholly outside the triangle");
        }
        assertEquals(TilePlan.of(fitted(dir, ComposeJob.OutputFormat.PDF)).distinctKeys(), planned,
                "the spare paper costs no tiles: a sheet the print's own size needs the same");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ComposeJob.OutputFormat.class, names = {"PDF", "PNG"})
    @DisplayName("and prints the same triangle as a sheet with none, with the spare as paper half on each side")
    void theSparePaperPrintsTheSameTriangle(final ComposeJob.OutputFormat format, @TempDir final Path dir)
            throws IOException {
        final Printed fitted = printed(fitted(dir.resolve("fitted"), format), format);
        final Printed spare = printed(job(dir.resolve("spare"), format, 9), format);

        assertEquals(fitted.mapPixels(), spare.mapPixels(), fitted.mapPixels() / 100.0,
                "as much map, and no more: the spare band is not map");
        assertEquals(fitted.width(), spare.width(), 2, "the triangle as wide");
        assertEquals(fitted.height(), spare.height(), 2, "and as tall");
        assertTrue(spare.left() > 10 && spare.top() > 10, "paper around the map");
        // Within two pixels: the apex is too sharp to fill its first row
        assertEquals(spare.left(), spare.right(), 2, "the spare across, half on each side");
        assertEquals(spare.top(), spare.bottom(), 2, "the spare down, half on each side");
    }

    // ------------------------------------------------------------------ a rectangle printed exactly

    /** The triangle's bounding box, 200 mm across on one A4 landscape sheet. */
    private static ComposeJob box(final Path dir, final ComposeJob.OutputFormat format, final boolean exact) {
        return TestJobs.job(Shapes.boundsOf(TRIANGLE)).zoom(9).exact(exact)
                .background(MAP).missing(MissingTilePolicy.REFUSE).formats(format).dir(dir).name("box").build();
    }

    @Test
    @DisplayName("a rectangle printed exactly needs no tile of the band the spare paper would show")
    void anExactRectanglePlansOnlyItself(@TempDir final Path dir) {
        final ComposeJob exact = box(dir, ComposeJob.OutputFormat.PDF, true);
        final Geometry rectangle = Shapes.toUnitSquare(exact.fillShape());
        final Set<TileKey> planned = TilePlan.of(exact).distinctKeys();
        for (final TileKey key : planned) {
            final double s = 1.0 / Mercator.tileCount(key.zoom());
            final Geometry tile = Shapes.GF.toGeometry(
                    new Envelope(key.x() * s, (key.x() + 1) * s, key.y() * s, (key.y() + 1) * s));
            assertTrue(rectangle.intersects(tile), () -> key + " lies wholly outside the rectangle");
        }
        final Set<TileKey> more = TilePlan.of(box(dir, ComposeJob.OutputFormat.PDF, false)).distinctKeys();
        assertTrue(more.containsAll(planned) && more.size() > planned.size(),
                "without it, the spare paper is more map and more tiles");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ComposeJob.OutputFormat.class, names = {"PDF", "PNG"})
    @DisplayName("and prints the rectangle alone, with the spare as paper half on each side")
    void anExactRectangleIsClipped(final ComposeJob.OutputFormat format, @TempDir final Path dir)
            throws IOException {
        final Printed exact = printed(box(dir.resolve("exact"), format, true), format);
        assertTrue(exact.left() > 10 && exact.top() > 10, "paper around the map");
        assertEquals(exact.left(), exact.right(), 2, "the spare across, half on each side");
        assertEquals(exact.top(), exact.bottom(), 2, "the spare down, half on each side");

        final Printed more = printed(box(dir.resolve("more"), format, false), format);
        assertEquals(0, more.left() + more.right() + more.top() + more.bottom(), "without it, map to every edge");
    }

    /** Where the map is on a printed page, and how much of it; margins in pixels from each edge. */
    private record Printed(int mapPixels, int width, int height, int left, int right, int top, int bottom) { }

    private static Printed printed(final ComposeJob job, final ComposeJob.OutputFormat format) throws IOException {
        final Path dir = job.outputDir();
        if (format == ComposeJob.OutputFormat.PNG) {
            return printed(ImageIO.read(export(dir, job, ".png").toFile()));
        }
        try (final PDDocument doc = Loader.loadPDF(export(dir, job, ".pdf").toFile())) {
            return printed(new PDFRenderer(doc).renderImageWithDPI(0, 72));
        }
    }

    private static Printed printed(final BufferedImage img) {
        int count = 0, x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (isMap(img.getRGB(x, y))) {
                    count++;
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
        }
        assertTrue(count > 0, "some of it is map");
        return new Printed(count, x1 - x0 + 1, y1 - y0 + 1,
                x0, img.getWidth() - 1 - x1, y0, img.getHeight() - 1 - y1);
    }

    /** Nearer the map's color than the paper's, so an antialiased edge counts where it mostly is. */
    private static boolean isMap(final int rgb) {
        return distance(rgb, MAP) < distance(rgb, 0xFFFFFF);
    }

    private static int distance(final int a, final int b) {
        int sum = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            final int d = (a >> shift & 0xFF) - (b >> shift & 0xFF);
            sum += d * d;
        }
        return sum;
    }
}
