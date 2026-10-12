package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mapy.com's credit is printed with Mapy.com's logo (providers.md §3): after the credit's words,
 * as tall as they are set, on every sheet that carries the credit – in a PDF as a form of paths,
 * in a PNG or a TIFF drawn into the pixels. Every other credit is words only, as it was;
 * {@link GoldenPdfTest} holds two prints of other providers to their stored bytes.
 */
class CreditLogoTest {

    private static final Provider MAPY = ProviderCatalog.builtIn().require("mapy-com");
    private static final Provider OSM = ProviderCatalog.builtIn().require("osm");

    /** Prague, which 200 mm across lays out on A6 two sheets by two. */
    private static final LonLatBounds PRAGUE = new LonLatBounds(14.30, 50.00, 14.60, 50.15);

    /** The credit and nothing else, so that whatever on a sheet is not map is the credit. */
    private static final Marks CREDIT_ONLY = new Marks(5, false, false, false, true, null, "", "");

    /** Every tile's one color, which no mark is drawn in. */
    private static final int MAP = 0x7FB069;

    /** Mapy.com's letters: black at 80 percent, which over white is this, and is drawn opaque. */
    private static final int LETTERS = 0x333333;

    /** Where the credit ends: the white edge and the credit's inset in from the paper's edge. */
    private static final double CORNER_MM = 5 + SeamMarks.CREDIT_INSET_MM;

    private static ComposeJob job(final Path dir, final Provider provider, final int zoom,
                                  final ComposeJob.OutputGrouping grouping,
                                  final Set<ComposeJob.OutputFormat> formats) {
        return TestJobs.job(PRAGUE).provider(provider).lang("cs").zoom(zoom).width(200)
                .medium(Medium.paper("A6").sheet(Medium.Orientation.LANDSCAPE)).overlap(20)
                .formats(formats).grouping(grouping).dir(dir).name("credit").marks(CREDIT_ONLY).build();
    }

    private static void export(final Path dir, final ComposeJob job) throws IOException {
        new Composer().export(job, TestJobs.solidCache(dir.resolve("cache"), job, MAP), 0, ProgressSink.IGNORE);
    }

    // ------------------------------------------------------------------------------------ the PDF

    @ParameterizedTest(name = "{0}")
    @EnumSource(ComposeJob.OutputGrouping.class)
    @DisplayName("a Mapy.com PDF has the logo after the credit's words, as tall as they are set, on every page"
            + " that carries the credit")
    void thePdfCreditHasTheLogo(final ComposeJob.OutputGrouping grouping, @TempDir final Path dir) throws IOException {
        final ComposeJob job = job(dir, MAPY, 6, grouping, EnumSet.of(ComposeJob.OutputFormat.PDF));
        assertEquals(4, job.layout().count(), "two sheets by two, so that most carry no credit");
        export(dir, job);

        int carrying = 0;
        for (final Page page : pagesOf(job)) {
            final Rectangle2D logo = page.logo();
            if (page.words().isEmpty()) {
                assertNull(logo, page + ": no credit, no logo");
                continue;
            }
            carrying++;
            assertNotNull(logo, page + " carries the credit, and Mapy.com's comes with its logo");
            final double size = page.words().getFirst().getFontSizeInPt();
            assertEquals(SeamMarks.CREDIT_PT, size, 1e-3, "the credit is set as it always was");
            assertEquals(size, logo.getHeight(), 0.01, page + ": the logo as tall as the credit's words are set");

            final double wordsEnd = page.wordsEnd();
            assertTrue(logo.getMinX() > wordsEnd && logo.getMinX() - wordsEnd < size,
                    page + ": the logo after the words, a little apart – the words end at " + wordsEnd
                            + " pt, the logo starts at " + logo.getMinX());
            final double baseline = page.height() - page.words().getFirst().getYDirAdj();
            assertTrue(logo.getMinY() < baseline && logo.getMaxY() > baseline,
                    page + ": on the words' line, across their baseline at " + baseline + ": " + logo);
            assertEquals(baseline + 0.36 * size, logo.getCenterY(), 0.01, "centered on their capitals");
            assertEquals(page.width() - PdfExporter.mmToPt(CORNER_MM), logo.getMaxX(), 0.01,
                    "in the corner, where the words ended before");
        }
        assertEquals(1, carrying, "the bottom-right sheet carries the credit");
    }

    @Test
    @DisplayName("another provider's credit is its words in the corner, with no logo")
    void anotherProvidersCreditIsWordsOnly(@TempDir final Path dir) throws IOException {
        final ComposeJob job = job(dir, OSM, 6, ComposeJob.OutputGrouping.SINGLE_DOCUMENT,
                EnumSet.of(ComposeJob.OutputFormat.PDF));
        export(dir, job);
        int carrying = 0;
        for (final Page page : pagesOf(job)) {
            assertNull(page.logo(), page + ": OpenStreetMap names no logo");
            if (!page.words().isEmpty()) {
                carrying++;
                assertEquals(page.width() - PdfExporter.mmToPt(CORNER_MM), page.wordsEnd(), 0.01,
                        "the words end in the corner, as they did");
            }
        }
        assertEquals(1, carrying);
        assertTrue(SeamMarks.forSheet(job, job.layout(), job.layout().sheets().getLast()).stream()
                .noneMatch(m -> m instanceof SeamMarks.Logo), "no logo among the marks");
    }

    @Test
    @DisplayName("a Mapy.com PDF is the same bytes every time, its logo one form of paths")
    void thePdfIsTheSameEveryTime(@TempDir final Path dir) throws IOException {
        final byte[][] made = new byte[2][];
        for (int i = 0; i < made.length; i++) {
            // The same name in two folders, since the name is in the file's /ID
            final ComposeJob job = job(dir.resolve("run" + i), MAPY, 6, ComposeJob.OutputGrouping.SINGLE_DOCUMENT,
                    EnumSet.of(ComposeJob.OutputFormat.PDF));
            export(dir.resolve("run" + i), job);
            made[i] = Files.readAllBytes(PdfExporter.fileFor(job, null, "pdf"));
        }
        assertArrayEquals(made[0], made[1], "nothing in it from the clock, the machine or the run");

        final String raw = new String(made[0], StandardCharsets.ISO_8859_1);
        assertEquals(1, raw.split("/Subtype /Form", -1).length - 1, "the logo is one form in the file");
    }

    // --------------------------------------------------------------------------------- the rasters

    @Test
    @DisplayName("a Mapy.com PNG and TIFF have the logo drawn after the credit's words, as tall as they are set,"
            + " on every sheet that carries the credit")
    void theRasterCreditHasTheLogo(@TempDir final Path dir) throws IOException {
        // Zoom 13 is about nine pixels to the millimeter, the logo some twenty pixels tall
        final ComposeJob job = job(dir, MAPY, 13, ComposeJob.OutputGrouping.PER_SHEET,
                EnumSet.of(ComposeJob.OutputFormat.PNG, ComposeJob.OutputFormat.TIFF));
        export(dir, job);

        for (final String extension : List.of("png", "tif")) {
            int carrying = 0;
            for (final SheetLayout.Sheet sheet : job.layout().sheets()) {
                final BufferedImage img = ImageIO.read(PdfExporter.fileFor(job, sheet, extension).toFile());
                final String which = extension + " sheet " + sheet.index();
                final Rectangle logo = around(img, LETTERS);
                if (drawn(img, img.getWidth()) == null) {
                    assertNull(logo, which + ": no credit, no logo");
                    continue;
                }
                carrying++;
                assertNotNull(logo, which + " carries the credit, and Mapy.com's comes with its logo");
                final double pxPerMm = img.getWidth() / sheet.widthMm();
                final double size = SeamMarks.CREDIT_PT * 25.4 / 72 * pxPerMm;
                assertEquals(size, logo.height, 2, which + ": the logo as tall as the credit's words are set, give"
                        + " or take the pixels its edges share with the map");
                assertEquals((sheet.widthMm() - CORNER_MM) * pxPerMm, logo.getMaxX(), 2, which + ": in the corner");
                final Rectangle words = drawn(img, logo.x);
                assertNotNull(words, which + ": the words are there too");
                assertTrue(logo.getMinX() - words.getMaxX() < size, which + ": the words end a little before the"
                        + " logo: " + words + ", " + logo);
                assertTrue(words.getMinY() < logo.getMaxY() && words.getMaxY() > logo.getMinY(),
                        which + ": on one line with it");
            }
            assertEquals(1, carrying, extension + ": the bottom-right sheet carries the credit");
        }
    }

    @Test
    @DisplayName("another provider's PNG has its credit's words in the corner and no logo")
    void anotherProvidersRasterIsWordsOnly(@TempDir final Path dir) throws IOException {
        final ComposeJob job = job(dir, OSM, 13, ComposeJob.OutputGrouping.PER_SHEET,
                EnumSet.of(ComposeJob.OutputFormat.PNG));
        export(dir, job);
        final SheetLayout.Sheet last = job.layout().sheets().getLast();
        final BufferedImage img = ImageIO.read(PdfExporter.fileFor(job, last, "png").toFile());
        assertNull(around(img, LETTERS), "nothing in the logo's ink");
        final Rectangle words = drawn(img, img.getWidth());
        assertNotNull(words, "the words");
        // Their halo runs a little past where they end; a logo would have moved them a centimeter
        assertEquals(last.widthMm() - CORNER_MM, words.getMaxX() / (img.getWidth() / last.widthMm()), 1,
                "the words end in the corner, as they did: " + words);
    }

    // ------------------------------------------------------------------------------- the reader

    @Test
    @DisplayName("every logo a shipped definition names is one WTF draws, opaque and inside its box")
    void everyShippedLogoIsDrawn() {
        int logos = 0;
        for (final Provider p : ProviderCatalog.builtIn().all()) {
            final CreditLogo logo = CreditLogo.of(p);
            if (logo == null) {
                continue;
            }
            logos++;
            final Rectangle2D box = logo.box(), painted = logo.painted();
            assertTrue(painted.getMinX() >= box.getMinX() - 1e-6 && painted.getMaxX() <= box.getMaxX() + 1e-6
                    && painted.getMinY() >= box.getMinY() - 1e-6 && painted.getMaxY() <= box.getMaxY() + 1e-6,
                    p.id() + ": inside its box, " + painted + " in " + box);
            assertTrue(logo.aspect() > 1, p.id() + ": a logo wider than tall, as a credit's line is");
        }
        assertTrue(logos >= 1, "Mapy.com's at least");
        assertEquals(Set.of(0xFFFFFF, LETTERS), Set.copyOf(CreditLogo.of(MAPY).fills().stream()
                .map(CreditLogo.Fill::rgb).toList()), "a white halo, and the letters as they are over white");
    }

    @Test
    @DisplayName("what the logo reader does not draw is refused by name, not drawn wrong")
    void whatIsNotDrawnIsRefused() {
        final String path = "<path d='M0 0 L10 0 L10 10 Z'";
        for (final String[] svg : new String[][]{
                {"<svg viewBox='0 0 10 10'>" + path + " stroke='red'/></svg>", "stroke"},
                {"<svg viewBox='0 0 10 10'>" + path + " transform='scale(2)'/></svg>", "transform"},
                {"<svg viewBox='0 0 10 10'><g>" + path + "/></g></svg>", "<g>"},
                {"<svg viewBox='0 0 10 10'><path d='M0 0 A5 5 0 0 1 10 10 Z'/></svg>", "'A'"},
                {"<svg viewBox='0 0 10 10'>" + path + " fill='url(#gradient)'/></svg>", "url(#gradient)"},
                {"<svg viewBox='0 0 10 10'>" + path + " fill='red'/></svg>", "red"},
                {"<svg viewBox='0 0 5 5'>" + path + "/></svg>", "outside"},
                {"<svg>" + path + "/></svg>", "viewBox"},
                {"<svg viewBox='0 0 10 10'><path d='L10 10'/></svg>", "move"}}) {
            final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> CreditLogo.read(svg[0].getBytes(StandardCharsets.UTF_8), "test.svg"), svg[0]);
            assertTrue(refused.getMessage().contains(svg[1]), refused.getMessage());
        }

        final CreditLogo read = CreditLogo.read(("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 10' fill='none'>"
                + "<path d='m1 1h8v8h-8z' fill='#000' fill-opacity='.5'/><path d='M2,2L8-8' fill='none'/></svg>")
                .getBytes(StandardCharsets.UTF_8), "test.svg");
        assertEquals(1, read.fills().size(), "a shape filled with none is not drawn");
        assertEquals(0x808080, read.fills().getFirst().rgb(), "black at half, over white");
        assertEquals(new Rectangle2D.Double(1, 1, 8, 8), read.painted(), "relative commands from where the last ended");
    }

    // --------------------------------------------------------------------------------- the helpers

    /**
     * One page of a job's PDFs, sheet by sheet, whether each sheet is a file of its own or a page of
     * one document: what it fills inside a form – the logo, the one form a page of these draws – and
     * the glyphs of its text, which is the credit's, the only text these jobs print.
     */
    private record Page(int index, double width, double height, @Nullable Rectangle2D logo,
                        @NotNull List<TextPosition> words) {

        double wordsEnd() {
            return words.stream().mapToDouble(t -> t.getXDirAdj() + t.getWidthDirAdj()).max().orElseThrow();
        }

        @Override
        public @NotNull String toString() {
            return "page " + index;
        }
    }

    private static @NotNull List<Page> pagesOf(final @NotNull ComposeJob job) throws IOException {
        final List<Page> pages = new ArrayList<>();
        if (job.grouping() == ComposeJob.OutputGrouping.SINGLE_DOCUMENT) {
            try (PDDocument doc = Loader.loadPDF(PdfExporter.fileFor(job, null, "pdf").toFile())) {
                for (int i = 0; i < doc.getNumberOfPages(); i++) {
                    pages.add(page(doc, i, i + 1));
                }
            }
        } else {
            for (final SheetLayout.Sheet sheet : job.layout().sheets()) {
                try (PDDocument doc = Loader.loadPDF(PdfExporter.fileFor(job, sheet, "pdf").toFile())) {
                    assertEquals(1, doc.getNumberOfPages());
                    pages.add(page(doc, 0, sheet.index()));
                }
            }
        }
        assertEquals(job.layout().count(), pages.size());
        return pages;
    }

    private static @NotNull Page page(final @NotNull PDDocument doc, final int i, final int index) throws IOException {
        final PDPage page = doc.getPage(i);
        final FormFills fills = new FormFills(page);
        fills.processPage(page);

        final List<TextPosition> glyphs = new ArrayList<>();
        final PDFTextStripper strip = new PDFTextStripper() {
            @Override
            protected void writeString(final String text, final List<TextPosition> positions) {
                glyphs.addAll(positions);
            }
        };
        strip.setStartPage(i + 1);
        strip.setEndPage(i + 1);
        strip.getText(doc);
        return new Page(index, page.getMediaBox().getWidth(), page.getMediaBox().getHeight(), fills.bounds,
                List.copyOf(glyphs));
    }

    /** The bounds, on the page and in points, of everything filled inside a form. */
    private static final class FormFills extends PDFGraphicsStreamEngine {
        private final GeneralPath path = new GeneralPath();
        private int forms;
        private @Nullable Rectangle2D bounds;

        FormFills(final @NotNull PDPage page) {
            super(page);
        }

        @Override
        public void showForm(final @NotNull PDFormXObject form) throws IOException {
            forms++;
            super.showForm(form);
            forms--;
        }

        @Override
        public void fillPath(final int windingRule) {
            if (forms > 0) {
                bounds = bounds == null ? path.getBounds2D() : bounds.createUnion(path.getBounds2D());
            }
            path.reset();
        }

        @Override
        public void fillAndStrokePath(final int windingRule) {
            fillPath(windingRule);
        }

        @Override
        public void strokePath() {
            path.reset();
        }

        @Override
        public void endPath() {
            path.reset();
        }

        @Override
        public void appendRectangle(final Point2D p0, final Point2D p1, final Point2D p2, final Point2D p3) {
            path.moveTo(p0.getX(), p0.getY());
            path.lineTo(p1.getX(), p1.getY());
            path.lineTo(p2.getX(), p2.getY());
            path.lineTo(p3.getX(), p3.getY());
            path.closePath();
        }

        @Override
        public void moveTo(final float x, final float y) {
            path.moveTo(x, y);
        }

        @Override
        public void lineTo(final float x, final float y) {
            path.lineTo(x, y);
        }

        @Override
        public void curveTo(final float x1, final float y1, final float x2, final float y2,
                            final float x3, final float y3) {
            path.curveTo(x1, y1, x2, y2, x3, y3);
        }

        @Override
        public Point2D getCurrentPoint() {
            return path.getCurrentPoint();
        }

        @Override
        public void closePath() {
            path.closePath();
        }

        @Override
        public void clip(final int windingRule) {
            // Kept: the path is ended by the operator after it
        }

        @Override
        public void drawImage(final @NotNull PDImage pdImage) {
            // The tiles
        }

        @Override
        public void shadingFill(final @NotNull COSName shadingName) {
            // None in these files
        }
    }

    /** The box of everything drawn over the map left of this column, or null where nothing is. */
    private static @Nullable Rectangle drawn(final @NotNull BufferedImage img, final int before) {
        final int[] row = new int[img.getWidth()];
        Rectangle box = null;
        for (int y = 0; y < img.getHeight(); y++) {
            img.getRGB(0, y, img.getWidth(), 1, row, 0, img.getWidth());
            for (int x = 0; x < before; x++) {
                if ((row[x] & 0xFFFFFF) != MAP) {
                    if (box == null) {
                        box = new Rectangle(x, y, 1, 1);
                    } else {
                        box.add(new Rectangle(x, y, 1, 1));
                    }
                }
            }
        }
        return box;
    }

    /**
     * What is drawn around the pixels of this color, edges and halo included: everything that is
     * not map and touches them, through everything else that is not map. Null when nothing is in
     * that color.
     */
    private static @Nullable Rectangle around(final @NotNull BufferedImage img, final int ink) {
        final int w = img.getWidth(), h = img.getHeight();
        final int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        final boolean[] seen = new boolean[w * h];
        final ArrayDeque<Integer> todo = new ArrayDeque<>();
        for (int i = 0; i < px.length; i++) {
            if ((px[i] & 0xFFFFFF) == ink) {
                seen[i] = true;
                todo.add(i);
            }
        }
        if (todo.isEmpty()) {
            return null;
        }
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        while (!todo.isEmpty()) {
            final int i = todo.poll();
            final int x = i % w, y = i / w;
            x0 = Math.min(x0, x);
            y0 = Math.min(y0, y);
            x1 = Math.max(x1, x);
            y1 = Math.max(y1, y);
            for (final int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                final int nx = x + d[0], ny = y + d[1];
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
                    continue;
                }
                final int n = ny * w + nx;
                if (!seen[n] && (px[n] & 0xFFFFFF) != MAP) {
                    seen[n] = true;
                    todo.add(n);
                }
            }
        }
        return new Rectangle(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }
}
