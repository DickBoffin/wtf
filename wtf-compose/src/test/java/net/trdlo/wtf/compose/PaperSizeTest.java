package net.trdlo.wtf.compose;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.trdlo.wtf.tiles.CachedTileSource;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.TileCache;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The paper in the file has to be the paper in the drawer.
 * <p>
 * A printer driver matches a PDF page to a tray by its size in points, and a page a fraction of a
 * millimeter off a standard size is a custom size: the driver asks for manual feed, or scales it
 * to fit and shrinks the map. So the table must hold the standard's own numbers, and the page the
 * exporter writes must be that size to within a hundredth of a point.
 */
class PaperSizeTest {

    private static final double INCH = 25.4;

    @Test
    @DisplayName("every paper in the table is the size its standard says")
    void theTableMatchesTheStandards() {
        final Map<String, double[]> standard = Map.ofEntries(
                // ISO 216 – defined in whole millimeters
                Map.entry("4A0", new double[]{1682, 2378}),
                Map.entry("2A0", new double[]{1189, 1682}),
                Map.entry("A0", new double[]{841, 1189}),
                Map.entry("A1", new double[]{594, 841}),
                Map.entry("A2", new double[]{420, 594}),
                Map.entry("A3", new double[]{297, 420}),
                Map.entry("A4", new double[]{210, 297}),
                Map.entry("A5", new double[]{148, 210}),
                Map.entry("A6", new double[]{105, 148}),
                // ANSI and ARCH – defined in inches, and millimeters only by conversion
                Map.entry("Letter", new double[]{8.5 * INCH, 11 * INCH}),
                Map.entry("Legal", new double[]{8.5 * INCH, 14 * INCH}),
                Map.entry("Tabloid", new double[]{11 * INCH, 17 * INCH}),
                Map.entry("ANSI C", new double[]{17 * INCH, 22 * INCH}),
                Map.entry("ANSI D", new double[]{22 * INCH, 34 * INCH}),
                Map.entry("ANSI E", new double[]{34 * INCH, 44 * INCH}),
                Map.entry("Arch D", new double[]{24 * INCH, 36 * INCH}),
                Map.entry("Arch E", new double[]{36 * INCH, 48 * INCH}));

        assertEquals(standard.size(), Medium.PAPER_SIZES.size(), "a paper without a check, or a check without a paper");
        for (final Medium.Paper p : Medium.PAPER_SIZES) {
            final double[] mm = standard.get(p.name());
            assertNotNull(mm, () -> "no standard size known for " + p.name());
            assertEquals(mm[0], p.widthMm(), 1e-9, () -> p.name() + " width");
            assertEquals(mm[1], p.heightMm(), 1e-9, () -> p.name() + " height");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "overlap {0} mm, print {1} mm wide")
    @org.junit.jupiter.params.provider.CsvSource({"0, 297", "15, 297", "15, 250", "15, 400"})
    @DisplayName("an exported A3 page is A3 to a hundredth of a point, however much paper is spare")
    void anA3PageIsA3(final double overlapMm, final double printMm, @TempDir final Path dir) throws IOException {
        assertPageIs(dir, "A3", PDRectangle.A3, overlapMm, printMm);
    }

    @Test
    @DisplayName("an exported Letter page is Letter, 612 by 792 points exactly")
    void aLetterPageIsLetter(@TempDir final Path dir) throws IOException {
        assertPageIs(dir, "Letter", PDRectangle.LETTER, 0, 8.5 * INCH);
    }

    /**
     * A PNG or a TIFF says its physical size through its resolution, one value each way. A single
     * DPI taken from the width would make an A3 come back 420.02 mm tall: the two edges are
     * rounded to whole pixels separately, and the height would be worked out from the width's
     * resolution.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.EnumSource(value = ComposeJob.OutputFormat.class, names = {"PNG", "TIFF"})
    @DisplayName("an exported A3 raster says it is 297 by 420 mm")
    void anA3RasterIsA3(final ComposeJob.OutputFormat format, @TempDir final Path dir) throws IOException {
        // At zoom 10 this is about 240 dpi – a real print's resolution, which is what matters for
        // a format that rounds its resolution to a step: at zoom 6 a sheet is a few hundred pixels
        // and one step is a large share of them.
        final Path file = export(dir, "A3", 15, 297, 10, format, ".png", ".tif", ".tiff");
        final javax.imageio.stream.ImageInputStream in = javax.imageio.ImageIO.createImageInputStream(file.toFile());
        final javax.imageio.ImageReader reader = javax.imageio.ImageIO.getImageReaders(in).next();
        reader.setInput(in);
        final javax.imageio.metadata.IIOMetadata meta = reader.getImageMetadata(0);
        final org.w3c.dom.Node root = meta.getAsTree(javax.imageio.metadata.IIOMetadataFormatImpl.standardMetadataFormatName);
        final double[] mmPerPx = {Double.NaN, Double.NaN};
        walk(root, mmPerPx);
        final double w = reader.getWidth(0) * mmPerPx[0];
        final double h = reader.getHeight(0) * mmPerPx[1];
        in.close();
        // Each format stores what it can. PNG's pHYs is whole pixels per meter, so at 240 dpi it
        // can be off by up to a few hundredths of a millimeter – far inside any driver's tolerance
        // for calling a page A3, and not something a PNG can say more exactly.
        final double tolerance = 0.03;
        assertEquals(297, w, tolerance, "width in mm");
        assertEquals(420, h, tolerance, "height in mm");
    }

    private static void walk(final org.w3c.dom.Node n, final double[] out) {
        if ("HorizontalPixelSize".equals(n.getNodeName())) {
            out[0] = Double.parseDouble(((org.w3c.dom.Element) n).getAttribute("value"));
        } else if ("VerticalPixelSize".equals(n.getNodeName())) {
            out[1] = Double.parseDouble(((org.w3c.dom.Element) n).getAttribute("value"));
        }
        for (org.w3c.dom.Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) {
            walk(c, out);
        }
    }

    private static void assertPageIs(final Path dir, final String paper, final PDRectangle expected,
                                     final double overlapMm, final double printMm) throws IOException {
        final Path pdf = export(dir, paper, overlapMm, printMm,
                6, ComposeJob.OutputFormat.PDF, ".pdf");
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            // every page is the paper
            for (final var page : doc.getPages()) {
                final PDRectangle box = page.getMediaBox();
                assertEquals(expected.getWidth(), box.getWidth(), 0.01, paper + " width in points");
                assertEquals(expected.getHeight(), box.getHeight(), 0.01, paper + " height in points");
            }
        }
    }

    private static Path export(final Path dir, final String paper,
                               final double overlapMm, final double printMm, final int zoom,
                               final ComposeJob.OutputFormat format, final String... extensions)
            throws IOException {
        // A small area this wide, on a sheet or two of this paper in portrait
        final LonLatBounds bohemia = new LonLatBounds(12.09, 49.44, 15.98, 50.73);
        final ComposeJob job = TestJobs.job(bohemia).zoom(zoom).width(printMm)
                .medium(Medium.paper(paper).sheet(Medium.Orientation.PORTRAIT)).overlap(overlapMm)
                .formats(format).perSheet().dir(dir).name("paper").build();

        final TileCache empty = new TileCache(dir.resolve("cache"));
        final Composer.ExportReport report = new Composer().export(job,
                CachedTileSource.cacheOnly(empty, job.provider()), 0, ProgressSink.IGNORE);
        return report.files().stream()
                .filter(f -> java.util.Arrays.stream(extensions).anyMatch(e -> f.toString().toLowerCase().endsWith(e)))
                .findFirst().orElseThrow(() -> new AssertionError("no " + format + " in " + report.files()));
    }
}
