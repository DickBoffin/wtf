package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.CachedTileSource;
import net.trdlo.wtf.tiles.Mercator;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.TileCache;
import net.trdlo.wtf.tiles.TileKey;
import net.trdlo.wtf.tiles.TileSource;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.operation.distance.IndexedFacetDistance;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A page carries the part of the outlines that reaches it. The subject and the area are projected
 * once for the job and cut once per sheet, so a surveyed border is not written whole on every page
 * – and the paper shows what the whole outlines showed.
 */
class OutlineCutTest {

    /** Where the shapes are, in the unit square: Bohemia, near enough. */
    private static final double CX = Mercator.lonToUnitX(14.5), CY = Mercator.latToUnitY(49.8);

    /** A circle of this many points in the unit square, as the shape in degrees that projects to it. */
    private static Polygon circle(final double cx, final double cy, final double r, final int points) {
        final Coordinate[] ring = new Coordinate[points + 1];
        for (int i = 0; i < points; i++) {
            final double a = 2 * Math.PI * i / points;
            ring[i] = new Coordinate(Mercator.unitXToLon(cx + r * Math.cos(a)),
                    Mercator.unitYToLat(cy + r * Math.sin(a)));
        }
        ring[points] = new Coordinate(ring[0]);
        return Shapes.GF.createPolygon(ring);
    }

    private static List<Path> export(final ComposeJob job, final TileSource tiles) throws IOException {
        return new Composer().export(job, tiles, 1, ProgressSink.IGNORE).files();
    }

    // ------------------------------------------------------------------ what a page carries

    private static final int BORDER_POINTS = 100_000;

    /** The area's clip, both regions' clips and the stroke: each writes the outline once. */
    private static final int USES = 4;

    /**
     * A surveyed border as both the area and the subject, over twelve sheets: every one of the four
     * uses writes it on every page it reaches. Every tile is one white image, so what grows with the border is the paths alone.
     */
    @Test
    @DisplayName("page content stays bounded for a 100 000-point subject")
    void pageContentStaysBounded(@TempDir final Path dir) throws IOException {
        final double radius = 0.01;
        final Polygon border = circle(CX, CY, radius, BORDER_POINTS);
        final ComposeJob job = TestJobs.job(border).subject(border)
                .regions(new Regions(8, Regions.RegionSpec.PLAIN, Regions.RegionSpec.FADED,
                        new Regions.BoundaryStroke(0.5, 0x000000)))
                .width(800).medium(Medium.paper("A4").sheet(Medium.Orientation.PORTRAIT)).dir(dir).name("border")
                .build();
        final SheetLayout layout = job.layout();
        assertEquals(12, layout.count(), "four sheets across and three down");

        final Coordinate[] ring = Shapes.toUnitSquare(border).getCoordinates();
        export(job, TestJobs.solidCache(dir.resolve("cache"), job, 0xFFFFFF));

        boolean somePageInside = false, somePageCrossed = false;
        try (final PDDocument doc = Loader.loadPDF(PdfExporter.fileFor(job, null, "pdf").toFile())) {
            assertEquals(layout.count(), doc.getNumberOfPages());
            for (int i = 0; i < layout.count(); i++) {
                final SheetLayout.Sheet sheet = layout.sheets().get(i);
                // The border's points within the page and a centimeter round it: more than the
                // page's cut takes in, so what a page may carry is never underestimated
                final PrintRect reach = sheet.slice().grownByMm(20, 20);
                int own = 0;
                for (final Coordinate c : ring) {
                    if (c.x >= reach.x0() && c.x <= reach.x1() && c.y >= reach.y0() && c.y <= reach.y1()) {
                        own++;
                    }
                }
                final int points = pathPoints(doc.getPage(i));
                final int finalOwn = own;
                assertTrue(points <= USES * own + 64, () -> String.format(Locale.ROOT,
                        "sheet %d carries %,d path points for the %,d of the border within its reach",
                        sheet.index(), points, finalOwn));

                final double dx = (sheet.slice().x0() + sheet.slice().x1()) / 2 - CX;
                final double dy = (sheet.slice().y0() + sheet.slice().y1()) / 2 - CY;
                somePageInside |= own == 0 && Math.hypot(dx, dy) < radius;
                somePageCrossed |= own > 0;
            }
        }
        assertTrue(somePageInside, "the test is void unless some page lies wholly inside the border");
        assertTrue(somePageCrossed, "and some page is crossed by it");
    }

    /** How many points the page's paths are built of: every {@code m} and {@code l}. */
    private static int pathPoints(final PDPage page) throws IOException {
        final PDFStreamParser parser = new PDFStreamParser(page);
        int points = 0;
        for (Object token = parser.parseNextToken(); token != null; token = parser.parseNextToken()) {
            if (token instanceof final Operator op && ("m".equals(op.getName()) || "l".equals(op.getName()))) {
                points++;
            }
        }
        return points;
    }

    // ------------------------------------------------------------------ what the paper shows

    /** The outside's treatment, which is what tells the two regions apart on the paper. */
    private static final TileEffect GRAYED = new TileEffect(0, 0, 0);

    private static final int PAPER = 0xFFFFFF, INSIDE = 0xCC2222, OUTSIDE = GRAYED.apply(INSIDE), STROKE = 0x000000;
    /** Under the tiles, and every tile is cached, so it shows nowhere. */
    private static final int BACKGROUND = 0x22AA44;
    private static final int[] PALETTE = {PAPER, INSIDE, OUTSIDE, STROKE, BACKGROUND};

    private static final int ZOOM = 9;

    /** Closer than this to an outline, a tile's edge or the paper's, a pixel is not judged. */
    private static final double TOLERANCE_MM = 2;

    /** The area's hexagon, 450 mm across. */
    private static final Polygon HEXAGON = circle(CX, CY, 0.008, 6);

    /**
     * The subject, across both seams of the two by two sheets and across the hexagon's edge, and
     * 28 mm past the hexagon's right edge, onto the spare paper round it.
     */
    private static final Polygon SUBJECT = circle(CX + 0.005, CY + 0.0015, 0.004, 720);

    private static ComposeJob crossed(final Path dir, final ComposeJob.OutputFormat format) {
        return TestJobs.job(HEXAGON).subject(SUBJECT)
                .regions(new Regions(ZOOM, Regions.RegionSpec.PLAIN, new Regions.RegionSpec(GRAYED, true),
                        new Regions.BoundaryStroke(1, STROKE)))
                .width(450).background(BACKGROUND).missing(MissingTilePolicy.REFUSE).formats(format).dir(dir)
                .name("crossed").build();
    }

    /** Every tile the job needs, in the inside's color, which the outside grays. */
    private static TileSource solidTiles(final Path dir, final ComposeJob job) throws IOException {
        final TileCache cache = new TileCache(dir.resolve("cache"));
        final byte[] inside = TestJobs.solidPng(INSIDE);
        for (final TileKey key : TilePlan.of(job).distinctKeys()) {
            cache.store(key, inside, null);
        }
        return CachedTileSource.cacheOnly(cache, job.provider());
    }

    /**
     * Every page is cut differently from the whole outlines, and has to print what they would: a
     * cut edge that showed would be a line of stroke across the paper, and a region clipped to the
     * wrong part of the cut would print the wrong color.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ComposeJob.OutputFormat.class, names = {"PDF", "PNG"})
    @DisplayName("a page the outlines cross prints what the whole outlines printed")
    void aCutPrintsWhatTheWholeOutlinesPrinted(final ComposeJob.OutputFormat format, @TempDir final Path dir)
            throws IOException {
        final ComposeJob job = crossed(dir, format);
        final SheetLayout layout = job.layout();
        assertEquals(4, layout.count(), "two sheets across and two down");
        export(job, solidTiles(dir, job));

        final Judge judge = new Judge(TOLERANCE_MM / layout.print().mmPerUnit());
        if (format == ComposeJob.OutputFormat.PNG) {
            for (final SheetLayout.Sheet sheet : layout.sheets()) {
                final BufferedImage img = ImageIO.read(PdfExporter.fileFor(job, sheet, "png").toFile());
                judge.page(sheet, img, sheet.slice().unitWidth() / img.getWidth(),
                        sheet.slice().unitHeight() / img.getHeight(), 3);
            }
        } else {
            final int dpi = 40;
            try (final PDDocument doc = Loader.loadPDF(PdfExporter.fileFor(job, null, "pdf").toFile())) {
                final PDFRenderer renderer = new PDFRenderer(doc);
                for (int i = 0; i < layout.count(); i++) {
                    final SheetLayout.Sheet sheet = layout.sheets().get(i);
                    final double unitPerPx = 25.4 / dpi / sheet.slice().mmPerUnit();
                    judge.page(sheet, renderer.renderImageWithDPI(i, dpi), unitPerPx, unitPerPx, 1);
                }
            }
        }
        assertTrue(judge.judged > 20_000, () -> "only " + judge.judged + " pixels were judged");
        assertEquals(0, judge.wrong, () -> judge.wrong + " of " + judge.judged
                + " pixels print the wrong thing, among them " + judge.examples);
    }

    /** What each pixel of a sheet should be, from the whole outlines, against what it is. */
    private static final class Judge {

        private final Geometry area = Shapes.toUnitSquare(HEXAGON);
        private final Geometry subject = Shapes.toUnitSquare(SUBJECT);
        private final PreparedGeometry areaInside = PreparedGeometryFactory.prepare(area);
        private final PreparedGeometry subjectInside = PreparedGeometryFactory.prepare(subject);
        private final IndexedFacetDistance areaEdge = new IndexedFacetDistance(area.getBoundary());
        private final IndexedFacetDistance subjectEdge = new IndexedFacetDistance(subject.getBoundary());
        private final double tolerance;

        int judged;
        int wrong;
        final List<String> examples = new ArrayList<>();

        Judge(final double toleranceUnits) {
            tolerance = toleranceUnits;
        }

        /** Looks at every {@code step}th pixel of one sheet, whose top-left is the slice's. */
        void page(final SheetLayout.Sheet sheet, final BufferedImage img,
                  final double unitPerPxAcross, final double unitPerPxDown, final int step) {
            final PrintRect slice = sheet.slice();
            for (int py = 2; py < img.getHeight() - 2; py += step) {
                for (int px = 2; px < img.getWidth() - 2; px += step) {
                    final double ux = slice.x0() + (px + 0.5) * unitPerPxAcross;
                    final double uy = slice.y0() + (py + 0.5) * unitPerPxDown;
                    final Integer expected = expected(slice, ux, uy);
                    if (expected == null) {
                        continue;
                    }
                    judged++;
                    final int actual = img.getRGB(px, py) & 0xFFFFFF;
                    if (nearest(actual) != expected) {
                        wrong++;
                        if (examples.size() < 10) {
                            examples.add(String.format(Locale.ROOT, "sheet %d (%d, %d) %06X for %06X",
                                    sheet.index(), px, py, actual, expected));
                        }
                    }
                }
            }
        }

        /** The color the whole outlines give this point, or null where it is too close to call. */
        private Integer expected(final PrintRect slice, final double ux, final double uy) {
            if (near(ux, slice.x0()) || near(ux, slice.x1()) || near(uy, slice.y0()) || near(uy, slice.y1())
                    || nearTileEdge(ux, uy, ZOOM)) {
                return null;
            }
            final Point p = Shapes.GF.createPoint(new Coordinate(ux, uy));
            if (areaEdge.distance(p) < tolerance || subjectEdge.distance(p) < tolerance) {
                return null;
            }
            if (!areaInside.contains(p)) {
                return PAPER;
            }
            return subjectInside.contains(p) ? INSIDE : OUTSIDE;
        }

        private boolean near(final double a, final double b) {
            return Math.abs(a - b) < tolerance;
        }

        private boolean nearTileEdge(final double ux, final double uy, final int zoom) {
            final int n = Mercator.tileCount(zoom);
            return Math.abs(ux * n - Math.rint(ux * n)) < tolerance * n
                    || Math.abs(uy * n - Math.rint(uy * n)) < tolerance * n;
        }

        private static int nearest(final int rgb) {
            int best = PALETTE[0], bestDistance = Integer.MAX_VALUE;
            for (final int c : PALETTE) {
                int d = 0;
                for (int shift = 0; shift <= 16; shift += 8) {
                    final int e = (rgb >> shift & 0xFF) - (c >> shift & 0xFF);
                    d += e * e;
                }
                if (d < bestDistance) {
                    best = c;
                    bestDistance = d;
                }
            }
            return best;
        }
    }
}
