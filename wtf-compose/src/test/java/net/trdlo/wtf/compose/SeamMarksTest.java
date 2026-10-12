package net.trdlo.wtf.compose;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.ProgressSink;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The marks that go where the neighboring sheet covers them, and the credit that goes where it
 * does not. The right and the lower sheet lie on top; see {@link SeamMarks}.
 */
class SeamMarksTest {

    private static final LonLatBounds BOHEMIA = new LonLatBounds(12.09, 49.44, 15.98, 50.73);
    private static final LonLatBounds PRAGUE = new LonLatBounds(14.30, 50.00, 14.60, 50.15);
    private static final Marks ALL = new Marks(5, true, true, true, true, null, "Bohemia", "2026-09-24");

    /** Two A4 sheets side by side with a 22 mm overlap – a strip of 22 − 2 x 5 − 2 x 1 = 10 mm. */
    private static ComposeJob twoAcross(final Path dir, final Marks marks, final ComposeJob.OutputFormat format) {
        return twoAcross(dir, marks, format, 6);
    }

    private static ComposeJob twoAcross(final Path dir, final Marks marks, final ComposeJob.OutputFormat format,
                                        final int zoom) {
        // Two A4 sheets sharing 22 mm, less the 5 mm white edge kept free at either end
        return TestJobs.job(BOHEMIA).zoom(zoom).width(388)
                .medium(Medium.paper("A4").sheet(Medium.Orientation.PORTRAIT)).overlap(22).formats(format).dir(dir)
                .name("seams").marks(marks).build();
    }

    private static List<Path> export(final Path dir, final ComposeJob job, final int bandTiles) throws IOException {
        // White tiles, so that the marks are all there is to see
        return new Composer().export(job, TestJobs.solidCache(dir.resolve("cache"), job, 0xFFFFFF),
                bandTiles, ProgressSink.IGNORE).files();
    }

    // ------------------------------------------------------------------------------- the geometry

    @Test
    @DisplayName("a 3 x 2 layout has hidden strips on exactly its right and bottom seams")
    void stripsOnlyWhereANeighborLiesOnTop() {
        final PrintRect print = PrintSize.width(560).apply(BOHEMIA);
        final SheetLayout layout = SheetLayout.plan(print,
                Medium.paper("A4").sheet(Medium.Orientation.LANDSCAPE), 22, 0);
        assertTrue(layout.columns() >= 2, "a real grid");
        for (final SheetLayout.Sheet s : layout.sheets()) {
            final boolean lastColumn = s.column() == layout.columns() - 1;
            final boolean lastRow = s.row() == layout.rows() - 1;
            assertEquals(!lastColumn, SeamMarks.rightStrip(layout, s, 5).isPresent(), "right strip on " + s);
            assertEquals(!lastRow, SeamMarks.bottomStrip(layout, s, 5).isPresent(), "bottom strip on " + s);
        }
    }

    @Test
    @DisplayName("the strip runs from the neighbor's trimmed edge to the sheet's own white edge, a gap inside each")
    void theStripIsWhereTheFormulaSays() {
        final SheetLayout layout = twoAcross(Path.of("."), ALL, ComposeJob.OutputFormat.PDF).layout();
        final SheetLayout.Sheet first = layout.sheets().getFirst();
        final SeamMarks.Strip s = SeamMarks.rightStrip(layout, first, 5).orElseThrow();
        final double neighbor = layout.sheets().get(1).xMm() - first.xMm();
        assertEquals(neighbor + 5 + SeamMarks.GAP_MM, s.x0(), 1e-9);
        assertEquals(first.widthMm() - 5 - SeamMarks.GAP_MM, s.x1(), 1e-9);
        assertEquals(22 - 2 * 5 - 2 * SeamMarks.GAP_MM, s.width(), 1e-9);
    }

    @Test
    @DisplayName("a strip too narrow for text gets the hairline and nothing else")
    void aNarrowStripHasNoText() {
        final Marks wide = new Marks(9, true, true, true, false, null, "", "");   // 22 − 18 − 2 = 2 mm
        final ComposeJob job = twoAcross(Path.of("."), wide, ComposeJob.OutputFormat.PDF);
        final List<SeamMarks.Mark> marks = SeamMarks.forSheet(job, job.layout(), job.layout().sheets().getFirst());
        assertTrue(marks.stream().noneMatch(m -> m instanceof SeamMarks.Text), "no room for text");
        assertEquals(1, marks.stream().filter(m -> m instanceof SeamMarks.Line).count(), "the hairline only");
    }

    @Test
    @DisplayName("the credit sits in from the map's own bottom-right corner, on the last sheet only")
    void theCreditIsInTheCorner() {
        final ComposeJob job = twoAcross(Path.of("."), ALL, ComposeJob.OutputFormat.PDF);
        final SheetLayout layout = job.layout();
        final SheetLayout.Sheet last = layout.sheets().getLast();
        final SeamMarks.Text credit = SeamMarks.forSheet(job, layout, last).stream()
                .filter(m -> m instanceof SeamMarks.Text t && t.style() == SeamMarks.Style.CREDIT)
                .map(m -> (SeamMarks.Text) m).findFirst().orElseThrow();
        // A rectangle's map runs to the paper's edge, so the map's corner is the paper's
        assertEquals(last.widthMm() - 5 - SeamMarks.CREDIT_INSET_MM, credit.x(), 1e-9);
        assertEquals(last.heightMm() - 5 - SeamMarks.CREDIT_INSET_MM, credit.y(), 1e-9);
        assertTrue(SeamMarks.forSheet(job, layout, layout.sheets().getFirst()).stream()
                .noneMatch(m -> m instanceof SeamMarks.Text t && t.style() == SeamMarks.Style.CREDIT));
    }

    // ---------------------------------------------------------------------------------- the scale

    private static Marks scale(final ScaleMark how, final boolean credit) {
        return new Marks(5, false, false, false, credit, how, "", "");
    }

    private static List<SeamMarks.Mark> scaleMarks(final ComposeJob job, final SheetLayout.Sheet sheet) {
        return SeamMarks.forSheet(job, job.layout(), sheet).stream()
                .filter(m -> m instanceof SeamMarks.Line l ? l.style() == SeamMarks.Style.CREDIT
                        : !((SeamMarks.Text) m).text().contains("OpenStreetMap"))
                .toList();
    }

    private static SheetLayout.Sheet bottomLeft(final SheetLayout layout) {
        return layout.sheets().stream().filter(s -> s.column() == 0 && s.row() == layout.rows() - 1)
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("the scale sits in from the bottom-left corner of the bottom-left sheet, and on no other")
    void theScaleIsInTheBottomLeftCorner() {
        final ComposeJob job = TestJobs.job(BOHEMIA).width(560).overlap(22)
                .name("grid").marks(scale(ScaleMark.BOTH, true)).build();
        final SheetLayout layout = job.layout();
        assertTrue(layout.columns() >= 2 && layout.rows() >= 2, "a real grid");
        for (final SheetLayout.Sheet s : layout.sheets()) {
            final List<SeamMarks.Mark> marks = scaleMarks(job, s);
            if (s.column() != 0 || s.row() != layout.rows() - 1) {
                assertTrue(marks.isEmpty(), "no scale on " + s);
                continue;
            }
            final SeamMarks.Line bar = (SeamMarks.Line) marks.getFirst();
            assertEquals(5 + SeamMarks.CREDIT_INSET_MM, bar.x1(), 1e-9);
            assertEquals(s.heightMm() - 5 - SeamMarks.CREDIT_INSET_MM - SeamMarks.BAR_BELOW_MM, bar.y1(), 1e-9,
                    "the bar raised so the labels under it end where the credit's line does");
            final SeamMarks.Text ratio = (SeamMarks.Text) marks.getLast();
            assertEquals(bar.y1(), ratio.y(), 1e-9, "the ratio on the bar's baseline");
            assertTrue(ratio.x() > bar.x2(), "after the bar");
            assertTrue(ratio.text().startsWith("1:"), ratio.text());
        }
    }

    @Test
    @DisplayName("the bar is the round distance that fits in 40 mm, as long on the paper as the scale says")
    void theBarIsARoundDistance() {
        assertEquals(2000, SeamMarks.barMeters(50_000), 1e-9);
        assertEquals(1000, SeamMarks.barMeters(25_000), 1e-9);
        assertEquals(200, SeamMarks.barMeters(10_000), 1e-9);
        assertEquals(20_000, SeamMarks.barMeters(1_000_000), 1e-9);
        assertEquals(0.02, SeamMarks.barMeters(1), 1e-12);
        assertEquals("2\u00A0km", SeamMarks.distance(2000));
        assertEquals("200\u00A0m", SeamMarks.distance(200));
        assertEquals("2\u00A0cm", SeamMarks.distance(0.02));
        assertEquals("2\u202F000\u00A0km", SeamMarks.distance(2_000_000));

        final ComposeJob job = TestJobs.job(PRAGUE).print(PrintSize.atScale(PRAGUE, 50_000).apply(PRAGUE))
                .name("bar").marks(scale(ScaleMark.BAR, false)).build();
        final List<SeamMarks.Mark> marks = scaleMarks(job, bottomLeft(job.layout()));
        final SeamMarks.Line bar = (SeamMarks.Line) marks.getFirst();
        assertEquals(40, bar.x2() - bar.x1(), 1e-6, "2 km at 1:50 000, longer than its mile");
        assertEquals(List.of("0", "2\u00A0km", "1\u00A0mi"), marks.stream().filter(m -> m instanceof SeamMarks.Text)
                .map(m -> ((SeamMarks.Text) m).text()).toList(), "the bar's distances and no ratio");
        final SeamMarks.Text mile = (SeamMarks.Text) marks.getLast();
        assertEquals(bar.x1() + SeamMarks.MILE_M / 50, mile.x(), 1e-6, "a mile is 32 mm at 1:50 000");
        assertTrue(mile.y() > bar.y1(), "below the bar");
        assertTrue(marks.stream().anyMatch(m -> m instanceof SeamMarks.Line l && l.x1() == mile.x()
                && l.y2() > bar.y1()), "on a tick down");
    }

    @Test
    @DisplayName("below the bar, the round distance in miles, feet or inches nearest the metric one")
    void theBarHasMilesBelow() {
        assertEquals("1\u00A0mi", SeamMarks.imperial(2000).label());
        assertEquals("1\u00A0mi", SeamMarks.imperial(1000).label(), "not 2 000 ft");
        assertEquals("500\u00A0ft", SeamMarks.imperial(200).label());
        assertEquals("10\u00A0mi", SeamMarks.imperial(20_000).label());
        assertEquals("1\u00A0in", SeamMarks.imperial(0.02).label());
        assertEquals(10 * SeamMarks.MILE_M, SeamMarks.imperial(20_000).meters(), 1e-9);

        // At 1:25 000 a kilometer is 40 mm and the nearest round imperial distance a mile, 64 mm:
        // the bar runs on to the mile
        final ComposeJob job = TestJobs.job(PRAGUE).print(PrintSize.atScale(PRAGUE, 25_000).apply(PRAGUE))
                .name("mile").marks(scale(ScaleMark.BAR, false)).build();
        final List<SeamMarks.Mark> marks = scaleMarks(job, bottomLeft(job.layout()));
        final SeamMarks.Line bar = (SeamMarks.Line) marks.getFirst();
        assertEquals(SeamMarks.MILE_M / 25, bar.x2() - bar.x1(), 1e-6);
        assertTrue(marks.stream().anyMatch(m -> m instanceof SeamMarks.Text t && t.text().equals("1\u00A0km")
                && Math.abs(t.x() - bar.x1() - 40) < 1e-6), "the kilometer short of the bar's end");
    }

    @Test
    @DisplayName("the ratio is the one asked for, and names its latitude only once it varies by a percent")
    void theRatioSaysTheScale() {
        final PrintRect city = PrintSize.atScale(PRAGUE, 50_000).apply(PRAGUE);
        assertEquals(50_000, city.mapScale(), 1e-6, "atScale is mapScale the other way round");
        assertTrue(city.scaleVariation() < 0.01, "a city prints at one scale: " + city.scaleVariation());
        assertEquals("1:50\u202F000", SeamMarks.ratio(city));

        final PrintRect country = PrintSize.atScale(BOHEMIA, 1_000_000).apply(BOHEMIA);
        assertTrue(country.scaleVariation() > 0.01, "a country does not: " + country.scaleVariation());
        assertEquals("1:1\u202F000\u202F000 (" + SeamMarks.latitude(country.centerLat()) + ")",
                SeamMarks.ratio(country));

        assertEquals("49°48′ N", SeamMarks.latitude(49.8));
        assertEquals("33°52′ S", SeamMarks.latitude(-33.8688));
        assertEquals("0°00′", SeamMarks.latitude(0.001));
        assertEquals("5000", SeamMarks.grouped(5000).replace("\u202F", ""));
        assertEquals("5\u202F000", SeamMarks.grouped(5000));
        assertEquals("100", SeamMarks.grouped(100));
    }

    @Test
    @DisplayName("on one narrow sheet with the credit, the scale goes a line above it, and only the ratio is text")
    void aNarrowSheetStacksTheScaleOverTheCredit() {
        final ComposeJob job = TestJobs.job(PRAGUE).width(100).name("narrow").onItsOwnSheet()
                .marks(scale(ScaleMark.RATIO, true)).build();
        final SheetLayout.Sheet only = bottomLeft(job.layout());
        assertEquals(1, job.layout().count());
        final List<SeamMarks.Mark> marks = scaleMarks(job, only);
        assertEquals(1, marks.size(), "the ratio alone: " + marks);
        final SeamMarks.Text ratio = (SeamMarks.Text) marks.getFirst();
        assertEquals(only.heightMm() - 5 - SeamMarks.CREDIT_INSET_MM - SeamMarks.CREDIT_LINE_MM, ratio.y(), 1e-9);
    }

    @Test
    @DisplayName("text in the right strip stops short of the bottom seam's hairline, where they cross")
    void textKeepsClearOfTheCrossingSeam() {
        final ComposeJob job = TestJobs.job(BOHEMIA).width(560).overlap(22)
                .name("grid").marks(ALL).build();
        final SheetLayout layout = job.layout();
        final SheetLayout.Sheet corner = layout.sheets().stream()
                .filter(s -> s.column() == 0 && s.row() == 0).findFirst().orElseThrow();
        final SeamMarks.Strip bottom = SeamMarks.bottomStrip(layout, corner, 5).orElseThrow();
        final double hairline = bottom.y0() - SeamMarks.GAP_MM / 2;

        final SeamMarks.Text label = SeamMarks.forSheet(job, layout, corner).stream()
                .filter(m -> m instanceof SeamMarks.Text t && t.text().startsWith("Sheet"))
                .map(m -> (SeamMarks.Text) m).findFirst().orElseThrow();
        assertEquals(90, label.angle(), "in the right strip, read upwards");
        // Read upwards from its start, so the start is its lowest point – and that is well above the line
        assertTrue(label.y() <= hairline - SeamMarks.CROSSING_CLEARANCE_MM,
                "label starts at " + label.y() + " mm, the bottom seam's hairline is at " + hairline);
    }

    /** What the first sheet's label says: its faint text that is not an arrow's number. */
    private static String labelOf(final ComposeJob job) {
        final SheetLayout layout = job.layout();
        return SeamMarks.forSheet(job, layout, layout.sheets().getFirst()).stream()
                .filter(m -> m instanceof SeamMarks.Text t && t.style() == SeamMarks.Style.FAINT
                        && !t.text().matches("\\d+"))
                .map(m -> ((SeamMarks.Text) m).text())
                .findFirst().orElseThrow(() -> new AssertionError("no label on the first sheet"));
    }

    @Test
    @DisplayName("the label says which sheet in English, unless the job is given other words")
    void theLabelIsEnglishUnlessGivenOtherWords() {
        assertEquals(Marks.SHEET_LINE, ALL.sheetLine(), "the engine's own words, when it is given none");
        assertEquals("Sheet 1 of 2 · column 1, row 1 · Bohemia · 2026-09-24",
                labelOf(twoAcross(Path.of("."), ALL, ComposeJob.OutputFormat.PDF)));

        final Marks czech = new Marks(5, true, true, true, true, null, "Plzeň", "2026-09-25",
                "List {0} z {1} · sloupec {2}, řádek {3}");
        assertEquals("List 1 z 2 · sloupec 1, řádek 1 · Plzeň · 2026-09-25",
                labelOf(twoAcross(Path.of("."), czech, ComposeJob.OutputFormat.PDF)));

        // The order is the words', and a name or a day there is none of is left out with its dot
        final Marks reordered = new Marks(5, true, false, false, false, null, "", "", "{3}.{2} ({0}/{1})");
        assertEquals("1.1 (1/2)", labelOf(twoAcross(Path.of("."), reordered, ComposeJob.OutputFormat.PDF)));
    }

    @Test
    @DisplayName("the label's numbers are plain integers, a thousand sheets and more among them")
    void theLabelsNumbersArePlain() {
        final Marks czech = new Marks(5, true, false, false, false, null, "", "",
                "List {0} z {1} · sloupec {2}, řádek {3}");
        final ComposeJob job = TestJobs.job(BOHEMIA).width(10_000).overlap(22).name("huge").marks(czech).build();
        final int count = job.layout().count();
        assertTrue(count >= 1000, () -> count + " sheets");
        assertEquals("List 1 z " + count + " · sloupec 1, řádek 1", labelOf(job), "not grouped as Czech groups them");
    }

    @Test
    @DisplayName("Plzeň stays Plzeň, and a letter the face lacks is a question mark, not a failed export")
    void accentsStay() throws IOException {
        try (final PDDocument doc = new PDDocument()) {
            final PDFont font = new PdfExporter.Lettering(doc).font();
            assertEquals("Plzeň · Ústí · Αθήνα · Київ", PdfExporter.encodable(font, "Plzeň · Ústí · Αθήνα · Київ"));
            assertEquals("© München", PdfExporter.encodable(font, "© München"));
            assertEquals("Praha ?? ?", PdfExporter.encodable(font, "Praha 北京 🗺"),
                    "one mark for each character, a surrogate pair among them");
        }
    }

    // ------------------------------------------------------------------------------- in the files

    @Test
    @DisplayName("the PDF has the label and arrow on the covered sheet, and the credit on the last")
    void thePdfCarriesTheMarks(@TempDir final Path dir) throws IOException {
        final Path pdf = export(dir, twoAcross(dir, ALL, ComposeJob.OutputFormat.PDF), 0).stream()
                .filter(p -> p.toString().endsWith(".pdf")).findFirst().orElseThrow();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            assertEquals(2, doc.getNumberOfPages());
            final PDFTextStripper strip = new PDFTextStripper();
            strip.setStartPage(1);
            strip.setEndPage(1);
            final String first = strip.getText(doc);
            strip.setStartPage(2);
            strip.setEndPage(2);
            final String second = strip.getText(doc);

            // Text read upwards comes out of the extractor in fragments, so compare without spaces
            final String flat = first.replaceAll("\\s+", "");
            assertTrue(flat.contains("Sheet1of2"), first);
            assertFalse(flat.contains("OpenStreetMap"), "no credit hidden with the label: the one is on the last");
            assertTrue(first.contains("2"), "the arrow's number, pointing at sheet 2");
            assertFalse(second.contains("Sheet 2 of 2"), "nothing covers sheet 2, so it has no hidden strip");
            assertTrue(second.contains("OpenStreetMap contributors"), "the visible credit: " + second);
        }
    }

    @Test
    @DisplayName("the PDF has the scale on the bottom-left sheet")
    void thePdfCarriesTheScale(@TempDir final Path dir) throws IOException {
        final Path pdf = export(dir, twoAcross(dir, scale(ScaleMark.BOTH, true), ComposeJob.OutputFormat.PDF), 0)
                .stream().filter(p -> p.toString().endsWith(".pdf")).findFirst().orElseThrow();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            final PDFTextStripper strip = new PDFTextStripper();
            strip.setStartPage(1);
            strip.setEndPage(1);
            final String first = strip.getText(doc);
            assertTrue(first.contains("1:"), "the ratio: " + first);
            assertTrue(first.contains("km"), "the bar's distance: " + first);
            strip.setStartPage(2);
            strip.setEndPage(2);
            assertFalse(strip.getText(doc).contains("1:"), "the scale is on the first sheet only");
        }
    }

    @Test
    @DisplayName("the label and the credit are set in a subset of Liberation Sans, diacritics and all")
    void theTextKeepsItsDiacritics(@TempDir final Path dir) throws IOException {
        final Marks czech = new Marks(5, true, true, true, true, null, "Plzeň – Ústí nad Labem", "2026-09-25");
        final Path pdf = export(dir, twoAcross(dir, czech, ComposeJob.OutputFormat.PDF), 0).stream()
                .filter(p -> p.toString().endsWith(".pdf")).findFirst().orElseThrow();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            final PDFTextStripper strip = new PDFTextStripper();
            strip.setStartPage(1);
            strip.setEndPage(1);
            final String flat = strip.getText(doc).replaceAll("\\s+", "");
            assertTrue(flat.contains("Plzeň–ÚstínadLabem"), flat);

            int fonts = 0;
            for (final PDPage page : doc.getPages()) {
                final PDResources res = page.getResources();
                for (final COSName name : res.getFontNames()) {
                    final PDFont font = res.getFont(name);
                    assertInstanceOf(PDType0Font.class, font);
                    assertTrue(font.getName().matches("[A-Z]{6}\\+LiberationSans"),
                            "a subset, by its tag: " + font.getName());
                    final PDStream file = font.getFontDescriptor().getFontFile2();
                    assertNotNull(file, "embedded");
                    assertTrue(file.getLength() < 40_000, "only the glyphs used: " + file.getLength() + " bytes");
                    fonts++;
                }
            }
            assertTrue(fonts > 0, "the text has a font");
        }
    }

    @Test
    @DisplayName("with everything switched off, a page is map and nothing else")
    void offMeansOff(@TempDir final Path dir) throws IOException {
        final Path pdf = export(dir, twoAcross(dir, Marks.NONE, ComposeJob.OutputFormat.PDF), 0)
                .stream().filter(p -> p.toString().endsWith(".pdf")).findFirst().orElseThrow();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            assertEquals("", new PDFTextStripper().getText(doc).strip());
        }
    }

    @Test
    @DisplayName("a banded PNG has the marks in the strip, across band boundaries, and none elsewhere")
    void thePngCarriesTheMarks(@TempDir final Path dir) throws IOException {
        // Zoom 10 is about thirteen pixels to the millimeter; at zoom 6 a pixel is more than one,
        // and a hairline's antialiasing lands a pixel outside the strip it belongs to
        final ComposeJob job = twoAcross(dir, ALL, ComposeJob.OutputFormat.PNG, 10);
        final Path png = export(dir, job, 1).stream()
                .filter(p -> p.toString().endsWith(".png")).sorted().findFirst().orElseThrow();
        final BufferedImage img = ImageIO.read(png.toFile());
        final SheetLayout layout = job.layout();
        final SheetLayout.Sheet first = layout.sheets().getFirst();
        final SeamMarks.Strip s = SeamMarks.rightStrip(layout, first, 5).orElseThrow();
        final double pxPerMm = img.getWidth() / first.widthMm();

        int inStripTop = 0, inStripBottom = 0, outside = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) == 0xFFFFFF) {
                    continue;
                }
                final double xm = x / pxPerMm, ym = y / pxPerMm;
                final double px = 1 / pxPerMm;       // one pixel of antialiasing either side
                if (xm >= s.x0() - SeamMarks.GAP_MM - px && xm <= s.x1() + px
                        && ym >= s.y0() - px && ym <= s.y1() + px) {
                    if (y < img.getHeight() / 2) {
                        inStripTop++;
                    } else {
                        inStripBottom++;
                    }
                } else {
                    outside++;
                }
            }
        }
        assertTrue(inStripTop > 0 && inStripBottom > 0, "marks in both halves of the strip, so in several bands");
        assertEquals(0, outside, "nothing drawn outside the hidden strip on a sheet with no credit");
    }
}
