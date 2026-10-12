package net.trdlo.wtf.studio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import net.trdlo.wtf.compose.Composer;
import net.trdlo.wtf.compose.PdfExporter;
import net.trdlo.wtf.compose.PdfTileImages;
import net.trdlo.wtf.compose.RasterExporter;
import net.trdlo.wtf.tiles.ProgressModel;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.TileDownloader;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The interface in Czech: the words, the numbers, and the engine's progress said in both.
 * <p>
 * The test JVM runs in English whatever the machine speaks, so each test here chooses Czech
 * itself and puts everything back afterwards.
 */
class CzechTest {

    private static final Locale CZECH = Locale.forLanguageTag("cs-CZ");

    private Locale display;
    private Locale format;

    @BeforeEach
    void czech() {
        display = Locale.getDefault(Locale.Category.DISPLAY);
        format = Locale.getDefault(Locale.Category.FORMAT);
        I18n.setLocale(CZECH);
    }

    @AfterEach
    void back() {
        Locale.setDefault(Locale.Category.DISPLAY, display);
        Locale.setDefault(Locale.Category.FORMAT, format);
        I18n.setLocale(display);
    }

    @Test
    @DisplayName("the steps are named in Czech")
    void theWordsAreCzech() {
        assertEquals("Předmět", I18n.get("step.subject"));
        assertEquals("Stáhnout dlaždice (12) a exportovat", I18n.format("export.run.withDownload", 12));
    }

    @Test
    @DisplayName("English chosen on a Czech machine is English, not the machine's language")
    void englishOnACzechMachineIsEnglish() {
        Locale.setDefault(Locale.Category.DISPLAY, CZECH);
        Locale.setDefault(CZECH);
        try {
            I18n.setLocale(Locale.ENGLISH);
            assertEquals("Subject", I18n.get("step.subject"));
        } finally {
            Locale.setDefault(format);
        }
    }

    @Test
    @DisplayName("a decimal comma is typed and read")
    void decimalComma() {
        assertEquals(5.5, I18n.parseNumber("5,5"));
        assertEquals("1,5", I18n.number(1.5, 1));
    }

    @Test
    @DisplayName("thousands are grouped with whichever space the keyboard types")
    void groupingWithAnySpace() {
        assertEquals(50_000.0, I18n.parseNumber("50 000"), "an ordinary space");
        assertEquals(50_000.0, I18n.parseNumber("50\u00a0000"), "a no-break space");
        assertEquals(50_000.0, I18n.parseNumber("50\u202f000"), "a narrow no-break space");
        assertEquals(12_345.5, I18n.parseNumber("12 345,5"));
        assertEquals(50_000.0, I18n.parseNumber(I18n.integer(50_000)), "what the field itself writes");
        assertNull(I18n.parseNumber("50 000 m"), "a number with a word after it is still refused");
    }

    @Test
    @DisplayName("every phrase the engine reports progress and a failed tile with is said in Czech")
    void theEnginePhrasesAreTranslated() {
        assertEquals("Stahuji dlaždice", ProgressText.phrase(TileDownloader.PHASE_DOWNLOADING));
        assertEquals("Skládám dlaždice, list 3",
                ProgressText.phrase(String.format(Locale.ROOT, PdfExporter.PHASE_PLACING, 3)));
        assertEquals("Rastruji list 12",
                ProgressText.phrase(String.format(Locale.ROOT, RasterExporter.PHASE_RASTERIZING, 12)));
        assertEquals("Zapisuji tisk-1.pdf",
                ProgressText.phrase(String.format(Locale.ROOT, PdfExporter.PHASE_WRITING, "tisk-1.pdf")));
        assertEquals("Všechno už bylo v mezipaměti (dlaždice: " + I18n.integer(1234) + ")",
                ProgressText.phrase(String.format(Locale.ROOT, TileDownloader.FINISHED_ALL_CACHED, 1234)));
        final TileDownloader.Report report = new TileDownloader.Report(1234, 56_789, 7, 0,
                12_900_000, Duration.ofSeconds(185), java.util.List.of(), null, null);
        assertEquals("Staženo: " + I18n.integer(1234) + ". Už v mezipaměti: " + I18n.integer(56_789)
                        + ". Prázdné: 7. Selhalo: 0. 12,3 MB za 3 min 5 s.", ProgressText.phrase(report.summary()),
                "a download's closing line, which the export step's label keeps");
        final TileDownloader.Report quick = new TileDownloader.Report(3, 0, 0, 1, 40_000,
                Duration.ofSeconds(42), java.util.List.of(), null, null);
        assertEquals(ProgressText.report(quick), ProgressText.phrase(quick.summary()));
        assertEquals("něco, co nikdo nepřeložil", ProgressText.phrase("něco, co nikdo nepřeložil"),
                "a phrase it does not know is passed through");
        assertEquals("adresa není platná (Illegal character in query)", ProgressText.phrase(
                        String.format(Locale.ROOT, TileDownloader.UNMADE_ADDRESS, "Illegal character in query")),
                "why no request could be made for a tile, the parser's reason as it said it");
        assertEquals("klíč obsahuje znak, který hlavička požadavku nedokáže přenést",
                ProgressText.phrase(TileDownloader.UNMADE_KEY));
    }

    /**
     * A download the server's rate limit ended says so where its counts are said – the
     * export's log, the Tests of the provider dialogs, the label that keeps its closing line – and
     * until when if the server named the time, written the way the reader writes one.
     */
    @Test
    @DisplayName("a download a rate limit ended says so in Czech, and until when if the server said")
    void aRateLimitThatEndedADownloadIsSaid() {
        final Instant until = Instant.parse("2026-09-28T13:05:00Z");
        final TileDownloader.Report named = new TileDownloader.Report(0, 3, 0, 7, 0, Duration.ZERO, List.of(),
                new TileDownloader.RateLimited(until), null);
        assertEquals("Staženo: 0. Už v mezipaměti: 3. Prázdné: 0. Selhalo: 7. 0,0 MB za 0 s. Server omezuje"
                        + " počet požadavků a do " + I18n.dateTime(until) + " žádný další nepřijme, takže se už"
                        + " na nic dalšího neptalo.", ProgressText.report(named));
        assertEquals(ProgressText.report(named), ProgressText.phrase(named.summary()),
                "its closing line, taken apart and said the same");

        final TileDownloader.Report lasting = new TileDownloader.Report(12, 0, 0, 28, 1_048_576,
                Duration.ofSeconds(31), List.of(), new TileDownloader.RateLimited(null), null);
        assertTrue(ProgressText.report(lasting).endsWith(". Server omezuje počet požadavků a odmítal je dál, takže"
                + " se už na nic dalšího neptalo."), ProgressText.report(lasting));
        assertEquals(ProgressText.report(lasting), ProgressText.phrase(lasting.summary()));
    }

    @Test
    @DisplayName("the progress line is Czech from end to end")
    void theProgressLine() {
        final long[] now = {0};
        final ProgressModel model = new ProgressModel(() -> now[0]);
        model.phase(TileDownloader.PHASE_DOWNLOADING, 1000, ProgressSink.Unit.TILES);
        assertEquals("Stahuji dlaždice: 0 z " + I18n.integer(1000) + " (0 %)",
                ProgressText.line(model));
        assertEquals("asi 3 min", ProgressText.eta(Duration.ofMinutes(3)));
        assertEquals("asi 2,5 h", ProgressText.eta(Duration.ofMinutes(150)));
        assertEquals("3 min 5 s", ProgressText.took(Duration.ofSeconds(185)));
    }

    @Test
    @DisplayName("a finished export says what it wrote, and a canceled one how far it got")
    void theExportReport() {
        final List<Path> all = List.of(Path.of("tisk-s01.pdf"), Path.of("tisk-s02.pdf"), Path.of("tisk-s03.pdf"),
                Path.of("tisk-layout.txt"), Path.of("tisk-attribution.txt"));
        assertEquals("Soubory: 5. Listy: 3. Umístěné dlaždice: 36. 3,0 MB za 12 s.",
                ProgressText.report(new Composer.ExportReport(all, 3, 36, 3 * 1_048_576L,
                        Duration.ofSeconds(12), PdfTileImages.Tally.NONE, 3, false, false)));
        assertEquals("Zrušeno. Zapsané soubory: 1 z 3. Umístěné dlaždice: 12. 1,0 MB za 4 s.",
                ProgressText.report(new Composer.ExportReport(all.subList(0, 1), 3, 12, 1_048_576L,
                        Duration.ofSeconds(4), PdfTileImages.Tally.NONE, 3, true, false)));
    }

    @Test
    @DisplayName("an export whose PDF has decoded tiles says how many and why, every reason in Czech")
    void theDecodedTiles() {
        final List<Path> all = List.of(Path.of("tisk-s01.pdf"), Path.of("tisk-s02.pdf"), Path.of("tisk-s03.pdf"),
                Path.of("tisk-layout.txt"), Path.of("tisk-attribution.txt"));
        final PdfTileImages.Tally decoded = new PdfTileImages.Tally(20,
                Map.of(PdfTileImages.Fallback.SATURATION, 8, PdfTileImages.Fallback.ALPHA_CHANNEL, 2));
        assertEquals("Soubory: 5. Listy: 3. Umístěné dlaždice: 36. 3,0 MB za 12 s.\n"
                        + "Obrázky dekódované místo zkopírování: 10 z 20 (barevnost: 8, alfa kanál: 2).",
                ProgressText.report(new Composer.ExportReport(all, 3, 36, 3 * 1_048_576L,
                        Duration.ofSeconds(12), decoded, 3, false, false)));
        // Canceled, the files it finished say theirs, in the engine's order whatever the map's
        final PdfTileImages.Tally treated = new PdfTileImages.Tally(9, Map.of(PdfTileImages.Fallback.FORMAT, 2,
                PdfTileImages.Fallback.CONTRAST, 1, PdfTileImages.Fallback.SATURATION, 1));
        assertEquals("Zrušeno. Zapsané soubory: 1 z 3. Umístěné dlaždice: 12. 1,0 MB za 4 s.\n"
                        + "Obrázky dekódované místo zkopírování: 4 z 9 (barevnost: 1, kontrast: 1, formát dlaždic: 2).",
                ProgressText.report(new Composer.ExportReport(all.subList(0, 1), 3, 12, 1_048_576L,
                        Duration.ofSeconds(4), treated, 3, true, false)));
    }
}
