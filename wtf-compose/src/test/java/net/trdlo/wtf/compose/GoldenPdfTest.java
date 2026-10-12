package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.CachedTileSource;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.ProviderCatalog;
import net.trdlo.wtf.tiles.TileCache;
import net.trdlo.wtf.tiles.TileSource;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.filter.Filter;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The same project over the same tiles is the same PDF, byte for byte, and it is the one stored
 * beside this test: the golden file of docs/design/headless-and-debugging.md §2, and acceptance
 * criterion 4 – reopening the saved project reproduces the identical export.
 * <p>
 * The fixture is {@code src/test/resources/golden}: a project, and the nine tiles it plans in a
 * cache kept for the fixtures – synthetic indexed PNGs, one drawn for each tile, and one tile
 * recorded as sea. A subject in color inside and faded outside at zoom 7, a stroked outline, two
 * strips of a roll in one document, and every mark the studio can add, so the file holds the copied
 * tiles, both clips, the stroke, the seam text and the face it is set in.
 * <p>
 * That file copies every tile, and copying is the easy half. The second fixture, {@code decoded},
 * is where determinism is most at risk: 15 synthetic tiles of an aerial JPEG theme in the same
 * cache, printed on two sheets – exactly, its rectangle the edge and the spare paper round it, so
 * that no tile past the fifteen is planned. Inside, the JPEG tiles are copied into {@code DCTDecode} streams
 * with a {@code /Decode} array for the lightness and contrast; outside is desaturated, so every
 * outer tile with color is decoded and written again through {@code LosslessFactory}. Five of the
 * inner tiles are of other kinds – a gray JPEG, a progressive one, a truecolor PNG copied with
 * {@code /Decode}, and an RGBA PNG and a gray one with alpha, both decoded, each with its
 * {@code /SMask} – so the dispatch table of compose §6 is in the file, nearly row for row.
 * <p>
 * <b>When a change is meant to change what an export writes,</b> the first test fails and leaves
 * what the export makes now in {@code target/golden/}, under the fixture's name. Look at it; if it
 * is what was meant, copy it over the one in {@code src/test/resources/golden/} in the same commit.
 */
class GoldenPdfTest {

    private static final Path HERE = Path.of("src", "test", "resources", "golden");
    private static final Path PROJECT = HERE.resolve("golden.wtf");
    /** The second fixture: JPEG copied with {@code /Decode}, and tiles decoded. */
    private static final Path DECODED = HERE.resolve("decoded.wtf");
    private static final Path MADE = Path.of("target", "golden");

    /** The day the stored file's labels say. */
    private static final String DAY = "2026-01-01";

    /**
     * What PDFBox's Flate filter makes of the bundled face on the machine that made
     * {@code golden.pdf}, as a SHA-256.
     * <p>
     * Every compressed stream in the file – the object streams, the cross-reference stream, the
     * face – comes out of {@code java.util.zip}, which is the JDK's own zlib on Windows and often
     * the system's elsewhere, and a zlib that compresses differently writes a different file of the
     * same PDF. Where the two disagree the stored bytes cannot be compared, and that test is
     * skipped rather than failed; the others still run.
     */
    private static final String ZLIB = "4fb4c2fc38e249fd76376a89d68eb80dd79bae9f627a9418eec1dbfca6551264";

    private @Nullable String dayBefore;
    private @Nullable String levelBefore;

    @BeforeEach
    void theDayIsGiven() {
        dayBefore = System.getProperty(Marks.DATE_PROPERTY);
        levelBefore = System.getProperty(Filter.SYSPROP_DEFLATELEVEL);
        System.setProperty(Marks.DATE_PROPERTY, DAY);
        // PDFBox's own compression, not one somebody set for another run
        System.clearProperty(Filter.SYSPROP_DEFLATELEVEL);
    }

    @AfterEach
    void andTakenBack() {
        restore(Marks.DATE_PROPERTY, dayBefore);
        restore(Filter.SYSPROP_DEFLATELEVEL, levelBefore);
    }

    private static void restore(final String property, final @Nullable String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }

    /** The project as the file states it, with the command line's marks: the credit, and no label. */
    private static ComposeJob asFiled(final Path project, final Path out) throws IOException {
        return WtfFile.read(project).toJob(ProviderCatalog.builtIn(), out);
    }

    /** The same with every mark the studio adds as well: the label and its day, the arrows, the lines, the scale. */
    private static ComposeJob withEveryMark(final ComposeJob j) {
        return TestJobs.from(j).marks(new Marks(j.marks().edgeMm(), true, true, true, true, ScaleMark.BOTH,
                j.marks().name(), j.marks().date(), j.marks().sheetLine())).build();
    }

    private static Composer.ExportReport export(final ComposeJob job) throws IOException {
        final TileSource tiles = CachedTileSource.cacheOnly(new TileCache(HERE.resolve("cache")), job.provider());
        return new Composer().export(job, tiles, 0, ProgressSink.IGNORE);
    }

    /** The one document a single-document export writes. */
    private static byte[] pdfOf(final ComposeJob job) throws IOException {
        export(job);
        return Files.readAllBytes(PdfExporter.fileFor(job, null, "pdf"));
    }

    // -------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}.pdf")
    @ValueSource(strings = {"golden", "decoded"})
    @DisplayName("the export is the stored PDF, byte for byte")
    void theStoredPdf(final String fixture, @TempDir final Path dir) throws IOException {
        final String zlib = zlibCanary();
        assumeTrue(ZLIB.equals(zlib), "This JDK's zlib compresses differently from the one the stored PDFs were"
                + " made with (" + zlib + " here, " + ZLIB + " there), so their bytes cannot be compared here");

        final Path stored = HERE.resolve(fixture + ".pdf");
        final byte[] made = pdfOf(withEveryMark(asFiled(HERE.resolve(fixture + ".wtf"), dir)));
        final byte[] expected = Files.exists(stored) ? Files.readAllBytes(stored) : new byte[0];
        if (!Arrays.equals(expected, made)) {
            final Path madeHere = MADE.resolve(fixture + ".pdf");
            Files.createDirectories(MADE);
            Files.write(madeHere, made);
            fail(String.format(Locale.ROOT, "The export is not the stored PDF: they part at byte %,d, and they"
                            + " are %,d and %,d bytes long. What it makes now is in %s. If the change is meant,"
                            + " look at it, and copy it over %s in the same commit.",
                    Arrays.mismatch(expected, made), expected.length, made.length, madeHere.toAbsolutePath(),
                    stored));
        }
    }

    @ParameterizedTest(name = "{0}.wtf")
    @ValueSource(strings = {"golden", "decoded"})
    @DisplayName("reopening the saved project reproduces the export, byte for byte, with nothing set")
    void reopeningReproducesTheExport(final String fixture, @TempDir final Path dir) throws IOException {
        // As on a clean machine: nothing names the day, and the command line prints no label
        System.clearProperty(Marks.DATE_PROPERTY);
        final WtfFile file = WtfFile.read(HERE.resolve(fixture + ".wtf"));
        final byte[] first = pdfOf(file.toJob(ProviderCatalog.builtIn(), dir.resolve("first")));
        final Path saved = dir.resolve("saved.wtf");
        file.write(saved);
        final byte[] again = pdfOf(asFiled(saved, dir.resolve("again")));
        assertArrayEquals(first, again,
                "the same project over the same tiles is the same file, wherever it is written and whenever");

        // A labeled print says its day, which is why the day can be given
        System.setProperty(Marks.DATE_PROPERTY, "2026-01-01");
        final byte[] onTheFirst = pdfOf(withEveryMark(asFiled(saved, dir.resolve("on-the-first"))));
        System.setProperty(Marks.DATE_PROPERTY, "2026-01-02");
        final byte[] onTheSecond = pdfOf(withEveryMark(asFiled(saved, dir.resolve("on-the-second"))));
        assertFalse(Arrays.equals(onTheFirst, onTheSecond), "the label's day is in the file");
    }

    @Test
    @DisplayName("the file has no date in it, and an identifier worked out from what it holds")
    void theIdentifier(@TempDir final Path dir) throws IOException {
        final ComposeJob job = withEveryMark(asFiled(PROJECT, dir));
        final Composer.ExportReport report = export(job);
        assertEquals(0, report.images().decodedCount(),
                () -> "every fixture tile is copied, as a map's tiles are: " + report.images());

        final Path pdf = PdfExporter.fileFor(job, null, "pdf");
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            assertNull(doc.getDocument().getTrailer().getCOSDictionary(COSName.INFO),
                    "no information dictionary, so no creation date: the clock is not in the file");
        }
        final byte[] id = idOf(pdf);
        assertEquals(32, id.length, "a SHA-256");

        // One sheet to a file: each file its own identifier, and the first sheet's another than
        // the whole document's, which holds the same page
        final ComposeJob perSheet = TestJobs.from(job).perSheet().build();
        export(perSheet);
        final var sheets = perSheet.layout().sheets();
        final byte[] first = idOf(PdfExporter.fileFor(perSheet, sheets.get(0), "pdf"));
        final byte[] second = idOf(PdfExporter.fileFor(perSheet, sheets.get(1), "pdf"));
        assertFalse(Arrays.equals(first, second), "two sheets, two identifiers");
        assertFalse(Arrays.equals(id, first), "the whole document and its first sheet, two identifiers");
    }

    /**
     * The second fixture holds what it is there to hold. A later change could send all its tiles
     * down one path – copy them all, or decode them all – and the byte comparison would pass again
     * once the stored file was replaced; this says which paths that file stands for.
     */
    @Test
    @DisplayName("the second fixture copies JPEG with /Decode, and decodes tiles with alpha and for saturation")
    void theSecondFixtureTakesEveryPath(@TempDir final Path dir) throws IOException {
        final ComposeJob job = withEveryMark(asFiled(DECODED, dir));
        final PdfTileImages.Tally images = export(job).images();
        assertEquals(26, images.total(), "twelve inside, fourteen outside: " + images);
        assertEquals(Map.of(PdfTileImages.Fallback.SATURATION, 11, PdfTileImages.Fallback.ALPHA_CHANNEL, 4),
                images.decoded(), "fifteen decoded: the outside's tiles with color, and the two PNGs with alpha"
                        + " in both regions");

        // Each image once, though both pages draw some of them
        final Set<COSDictionary> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int dctWithDecode = 0, grayDct = 0, flateWithDecode = 0, masked = 0;
        try (PDDocument doc = Loader.loadPDF(PdfExporter.fileFor(job, null, "pdf").toFile())) {
            for (final PDPage page : doc.getPages()) {
                final PDResources resources = page.getResources();
                for (final COSName name : resources.getXObjectNames()) {
                    if (!(resources.getXObject(name) instanceof PDImageXObject image)
                            || !seen.add(image.getCOSObject())) {
                        continue;
                    }
                    final COSDictionary d = image.getCOSObject();
                    final COSArray decode = d.getCOSArray(COSName.DECODE);
                    final COSBase filter = d.getDictionaryObject(COSName.FILTER);
                    if (COSName.DCT_DECODE.equals(filter) && decode != null) {
                        dctWithDecode++;
                        if (image.getColorSpace().getNumberOfComponents() == 1) {
                            grayDct++;
                            assertEquals(2, decode.size(), "one channel, one pair of numbers");
                        }
                    }
                    if (COSName.FLATE_DECODE.equals(filter) && decode != null) {
                        flateWithDecode++;
                    }
                    if (d.getCOSStream(COSName.SMASK) != null) {
                        masked++;
                    }
                }
            }
        }
        assertEquals(26, seen.size(), "every image the report counts is in the file");
        assertEquals(10, dctWithDecode, "every inner JPEG copied, its lightness and contrast in /Decode, and"
                + " the gray one outside, which has no color to take out");
        assertEquals(2, grayDct, "the gray JPEG among them, in both regions, with one channel's pair");
        assertEquals(1, flateWithDecode, "the truecolor PNG copied inside, with /Decode");
        assertEquals(4, masked, "the two PNGs with alpha decoded, in both regions, each with its /SMask");
    }

    /** The file's {@code /ID}, both halves of which are the same for a file never updated. */
    private static byte[] idOf(final Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            final COSArray ids = doc.getDocument().getTrailer().getCOSArray(COSName.ID);
            assertNotNull(ids, pdf + " has an /ID");
            assertEquals(2, ids.size());
            final byte[] a = ((COSString) ids.getObject(0)).getBytes();
            assertArrayEquals(a, ((COSString) ids.getObject(1)).getBytes(), "both halves alike");
            return a;
        }
    }

    /**
     * The command line has no language and prints no label. A file's job still has the label's
     * words, the engine's own English, and names the file's title – which is what the stored PDFs
     * say, the label being switched on there.
     */
    @Test
    @DisplayName("a project file's label is in English and names the file's title, as the stored PDFs have it")
    void theFilesLabel(@TempDir final Path dir) throws IOException {
        final Marks marks = asFiled(PROJECT, dir).marks();
        assertFalse(marks.label(), "the command line prints none");
        assertEquals(Marks.SHEET_LINE, marks.sheetLine());
        assertEquals("Golden", marks.name());
    }

    @Test
    @DisplayName("a label says the day -Dwtf.export.date names, or today; a day that is none is refused")
    void theLabelsDay(@TempDir final Path dir) throws IOException {
        assertEquals(DAY, asFiled(PROJECT, dir).marks().date());

        System.setProperty(Marks.DATE_PROPERTY, "yesterday");
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, Marks::today);
        assertTrue(refused.getMessage().contains(Marks.DATE_PROPERTY), refused.getMessage());

        System.clearProperty(Marks.DATE_PROPERTY);
        assertDoesNotThrow(() -> LocalDate.parse(Marks.today()), "today, as a day");
    }

    /** What PDFBox's Flate filter makes of the bundled face here: see {@link #ZLIB}. */
    private static String zlibCanary() throws IOException {
        final byte[] face;
        try (InputStream in = PdfExporter.class.getResourceAsStream(PdfExporter.FONT)) {
            face = Objects.requireNonNull(in, "the bundled face").readAllBytes();
        }
        final ByteArrayOutputStream deflated = new ByteArrayOutputStream();
        FilterFactory.INSTANCE.getFilter(COSName.FLATE_DECODE)
                .encode(new ByteArrayInputStream(face), deflated, new COSDictionary(), 0);
        return HexFormat.of().formatHex(PdfTileImages.sha256().digest(deflated.toByteArray()));
    }
}
