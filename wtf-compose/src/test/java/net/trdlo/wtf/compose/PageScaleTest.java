package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.CachedTileSource;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Mercator;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.TileCache;
import net.trdlo.wtf.tiles.TileKey;
import net.trdlo.wtf.tiles.TileSource;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A sheet past 14 400 pt on a side gets a {@code /UserUnit} that shrinks the MediaBox, while its
 * content is worked out in unscaled points: without a matching scale on the stream itself,
 * everything drawn on it lands past the edge of that smaller box.
 */
class PageScaleTest {

    /**
     * Three columns and two rows of zoom 6 tiles, and nothing else: the area is the tile grid's
     * own boundary, not an arbitrary coastline, so every needed tile sits fully inside it and none
     * of them overhangs the sheet – the only overhang this test wants to catch is the exporter's.
     */
    private static final int ZOOM = 6;
    private static final int TX0 = 28, TX1 = 30, TY0 = 25, TY1 = 26;
    private static final LonLatBounds AREA = new LonLatBounds(
            Mercator.tileXToLon(TX0, ZOOM), Mercator.tileYToLat(TY1 + 1, ZOOM),
            Mercator.tileXToLon(TX1 + 1, ZOOM), Mercator.tileYToLat(TY0, ZOOM));

    /**
     * A print taller than the 14 400 pt limit, on a roll exactly as wide: a single sheet, with no
     * paper to spare and so no map past the area's six tiles.
     */
    private static ComposeJob job(final Path dir) {
        final PrintRect print = PrintSize.height(6000).apply(AREA);
        return TestJobs.job(AREA).zoom(ZOOM).print(print)
                .medium(new Medium.Roll(print.widthMm(), Medium.Axis.VERTICAL))
                .missing(MissingTilePolicy.PLACEHOLDER).dir(dir).name("huge").build();
    }

    /** A cache holding real tile bytes for every tile the job needs. */
    private static TileSource tiles(final Path dir, final ComposeJob job) throws IOException {
        return tiles(dir, job, new java.util.ArrayList<>());
    }

    /** The same, with each tile's {@code IDAT} data added to a list. */
    private static TileSource tiles(final Path dir, final ComposeJob job, final List<byte[]> idats)
            throws IOException {
        final TileCache cache = new TileCache(dir.resolve("cache"));
        final List<TileKey> keys = List.copyOf(TilePlan.of(job).distinctKeys());
        assertEquals(6, keys.size(), "the job is the six tiles it is meant to be");
        int i = 0;
        for (final TileKey key : keys) {
            final byte[] tile = TestJobs.solidPng(0x102030 * (++i) & 0xFFFFFF);
            cache.store(key, tile, null);
            idats.add(CentralClaimTest.idat(tile));
        }
        return CachedTileSource.cacheOnly(cache, job.provider());
    }

    /**
     * Walks a page's content stream and fails on the first rectangle or image whose device-space
     * coordinates – after every {@code cm} the stream applied, including one from before the first
     * operator – fall outside the MediaBox.
     */
    private static final class BoundsCheckingEngine extends PDFGraphicsStreamEngine {

        private final float w;
        private final float h;
        private String violation;
        /** Each image's width and height on the page, in its own units. */
        private final List<Point2D> images = new java.util.ArrayList<>();

        BoundsCheckingEngine(final PDPage page) {
            super(page);
            w = page.getMediaBox().getWidth();
            h = page.getMediaBox().getHeight();
        }

        private void check(final String what, final Point2D... points) {
            for (final Point2D p : points) {
                if (p.getX() < -0.5 || p.getX() > w + 0.5 || p.getY() < -0.5 || p.getY() > h + 0.5) {
                    violation = String.format("%s at (%.1f, %.1f) is outside the %.1f x %.1f MediaBox",
                            what, p.getX(), p.getY(), w, h);
                    return;
                }
            }
        }

        @Override
        public void appendRectangle(final Point2D p0, final Point2D p1, final Point2D p2, final Point2D p3) {
            check("a rectangle", p0, p1, p2, p3);
        }

        @Override
        public void drawImage(final PDImage image) {
            final AffineTransform at = getGraphicsState().getCurrentTransformationMatrix().createAffineTransform();
            final Point2D[] corners = {
                    at.transform(new Point2D.Float(0, 0), null), at.transform(new Point2D.Float(1, 0), null),
                    at.transform(new Point2D.Float(1, 1), null), at.transform(new Point2D.Float(0, 1), null)
            };
            check("an image's matrix", corners);
            images.add(new Point2D.Double(corners[0].distance(corners[1]), corners[0].distance(corners[3])));
        }

        @Override
        public void clip(final int windingRule) {
        }

        @Override
        public void moveTo(final float x, final float y) {
        }

        @Override
        public void lineTo(final float x, final float y) {
        }

        @Override
        public void curveTo(final float x1, final float y1, final float x2, final float y2,
                            final float x3, final float y3) {
        }

        @Override
        public Point2D getCurrentPoint() {
            return new Point2D.Float(0, 0);
        }

        @Override
        public void closePath() {
        }

        @Override
        public void endPath() {
        }

        @Override
        public void strokePath() {
        }

        @Override
        public void fillPath(final int windingRule) {
        }

        @Override
        public void fillAndStrokePath(final int windingRule) {
        }

        @Override
        public void shadingFill(final COSName shadingName) {
        }
    }

    @Test
    @DisplayName("every rectangle and matrix in the content stream lies inside the MediaBox")
    void contentStaysInsideTheSmallerMediaBox(@TempDir final Path dir) throws IOException {
        final ComposeJob job = job(dir);
        final PrintRect print = job.print();
        assertTrue(PdfExporter.mmToPt(print.heightMm()) > PdfExporter.MAX_PAGE_PT,
                "the test is void unless the sheet is actually past the limit");

        final Composer.ExportReport report = new Composer().export(job, tiles(dir, job), 0, ProgressSink.IGNORE);
        final Path pdf = report.files().stream().filter(p -> p.toString().endsWith(".pdf"))
                .findFirst().orElseThrow(() -> new AssertionError("no PDF written"));

        try (final PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            assertEquals(1, doc.getNumberOfPages());
            final PDPage page = doc.getPage(0);

            final PDRectangle box = page.getMediaBox();
            assertTrue(Math.max(box.getWidth(), box.getHeight()) <= PdfExporter.MAX_PAGE_PT + 0.5f,
                    "the MediaBox itself must respect the limit: " + box);
            final float userUnit = page.getCOSObject().getFloat(COSName.getPDFName("UserUnit"), 1f);
            assertTrue(userUnit > 1f, "a sheet this tall needs /UserUnit set");

            final BoundsCheckingEngine engine = new BoundsCheckingEngine(page);
            engine.processPage(page);
            assertNull(engine.violation, engine.violation);
        }
    }

    /**
     * Inside the box is not yet the right size: the page and every tile on it, counted in user
     * units and multiplied by {@code /UserUnit}, are the sheet and the tile in real points. And the
     * shrinking is the page's business alone, so every image is still its tile's {@code IDAT}.
     */
    @Test
    @DisplayName("under /UserUnit the page and every tile keep their real size, and their copied bytes")
    void userUnitKeepsTheSize(@TempDir final Path dir) throws IOException {
        final ComposeJob job = job(dir);
        final List<byte[]> idats = new java.util.ArrayList<>();
        final Composer.ExportReport report = new Composer().export(job, tiles(dir, job, idats), 0,
                ProgressSink.IGNORE);
        final Path pdf = report.files().stream().filter(p -> p.toString().endsWith(".pdf")).findFirst().orElseThrow();
        final SheetLayout.Sheet sheet = job.layout().sheets().getFirst();
        final double tilePt = PdfExporter.mmToPt(sheet.slice().mmPerUnit() / Mercator.tileCount(ZOOM));

        try (final PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            final PDPage page = doc.getPage(0);
            final float u = page.getCOSObject().getFloat(COSName.getPDFName("UserUnit"), 1f);
            assertTrue(u > 1f);
            assertEquals(PdfExporter.mmToPt(sheet.widthMm()), page.getMediaBox().getWidth() * u,
                    0.5 * u, "the page's width in real points");
            assertEquals(PdfExporter.mmToPt(sheet.heightMm()), page.getMediaBox().getHeight() * u,
                    0.5 * u, "and its height");

            final BoundsCheckingEngine engine = new BoundsCheckingEngine(page);
            engine.processPage(page);
            assertEquals(6, engine.images.size(), "six tiles placed");
            for (final Point2D size : engine.images) {
                assertEquals(tilePt, size.getX() * u, 0.01 * tilePt, "a tile's width in real points");
                assertEquals(tilePt, size.getY() * u, 0.01 * tilePt, "and its height");
            }

            final org.apache.pdfbox.pdmodel.PDResources res = page.getResources();
            int copied = 0;
            for (final COSName n : res.getXObjectNames()) {
                if (res.getXObject(n) instanceof final org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject img) {
                    final byte[] raw;
                    try (var in = img.getCOSObject().createRawInputStream()) {
                        raw = in.readAllBytes();
                    }
                    assertTrue(idats.stream().anyMatch(i -> java.util.Arrays.equals(i, raw)),
                            "an image stream that is no tile's IDAT");
                    copied++;
                }
            }
            assertEquals(6, copied);
        }
    }
}
