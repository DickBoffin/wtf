package net.trdlo.wtf.compose;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.trdlo.wtf.tiles.CachedTileSource;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.ProviderCatalog;
import net.trdlo.wtf.tiles.TileCache;
import net.trdlo.wtf.tiles.TileSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An export says how many of its PDFs' images went in decoded rather than copied, and why –
 * the central result's one exception – in its summary, which is the line the log keeps at INFO and
 * the command line prints last; an export that copies every tile says nothing more. On the two
 * fixtures of {@link GoldenPdfTest}: {@code decoded} desaturates its outside and has two PNGs with
 * alpha on the subject's edge, {@code golden} copies every tile it has.
 */
class FallbackReportTest {

    private static final Path HERE = Path.of("src", "test", "resources", "golden");

    /** The project as the file states it, which needs no day: the credit, and no label. */
    private static ComposeJob asFiled(final String fixture, final Path out) throws IOException {
        return WtfFile.read(HERE.resolve(fixture + ".wtf")).toJob(ProviderCatalog.builtIn(), out);
    }

    private static TileSource tiles(final ComposeJob job) {
        return CachedTileSource.cacheOnly(new TileCache(HERE.resolve("cache")), job.provider());
    }

    private static Composer.ExportReport export(final ComposeJob job) throws IOException {
        return new Composer().export(job, tiles(job), null, ProgressSink.IGNORE);
    }

    @Test
    @DisplayName("an export that decodes tiles says how many and why, in its summary and in the log at INFO")
    void anExportThatDecodesSaysHowManyAndWhy(@TempDir final Path dir) throws IOException {
        final Composer.ExportReport report;
        final List<String> info;
        try (Logged logged = new Logged()) {
            report = export(asFiled("decoded", dir));
            info = logged.info();
        }
        assertEquals(26, report.images().total(), () -> report.images().toString());
        assertEquals(Map.of(PdfTileImages.Fallback.SATURATION, 11, PdfTileImages.Fallback.ALPHA_CHANNEL, 4),
                report.images().decoded(), "the desaturated outside, and the two PNGs with alpha in both regions");
        assertTrue(report.summary().endsWith(
                        "; images decoded rather than copied: 15 of 26 (saturation: 11, alpha channel: 4)"),
                report::summary);
        assertEquals(List.of("Export finished: " + report.summary()), info, "one line, at INFO, that says it");
    }

    @Test
    @DisplayName("an export that copies every tile says nothing more, and a raster has nothing to say")
    void anExportThatCopiesEveryTileSaysNothingMore(@TempDir final Path dir) throws IOException {
        final Composer.ExportReport copied;
        final List<String> info;
        try (Logged logged = new Logged()) {
            copied = export(asFiled("golden", dir.resolve("golden")));
            info = logged.info();
        }
        assertEquals(0, copied.images().decodedCount(), () -> copied.images().toString());
        assertTrue(copied.images().total() > 0, "the images are counted all the same");
        assertTrue(copied.summary().matches("\\d+ file\\(s\\), \\d+ sheet\\(s\\), [\\d,]+ tile placements,"
                + " [\\d,.]+ MB in (\\d+ min )?\\d+ s"), copied::summary);
        assertEquals(List.of("Export finished: " + copied.summary()), info);

        // Every pixel of a raster is composited, so there is no copy for one to fall back from
        final ComposeJob decoded = asFiled("decoded", dir.resolve("raster"));
        final Composer.ExportReport raster = export(TestJobs.from(decoded)
                .formats(ComposeJob.OutputFormat.PNG).build());
        assertEquals(PdfTileImages.Tally.NONE, raster.images());
        assertFalse(raster.summary().contains("decoded"), raster::summary);
    }

    @Test
    @DisplayName("a file per sheet adds up every sheet's images, not the last one's alone")
    void aFilePerSheetAddsUpEverySheet(@TempDir final Path dir) throws IOException {
        final ComposeJob job = TestJobs.from(asFiled("decoded", dir.resolve("out")))
                .formats(ComposeJob.OutputFormat.PDF).perSheet().build();
        final TilePlan.Plan plan = TilePlan.of(job);
        assertEquals(2, plan.sheets().size(), "the fixture has two sheets");

        // What each sheet's document holds, written alone
        PdfTileImages.Tally each = PdfTileImages.Tally.NONE;
        PdfTileImages.Tally last = PdfTileImages.Tally.NONE;
        for (final TilePlan.SheetPlan sp : plan.sheets()) {
            last = PdfExporter.write(job, plan, List.of(sp), tiles(job),
                    dir.resolve("alone-" + sp.sheet().index() + ".pdf"), ProgressSink.IGNORE).images();
            each = each.plus(last);
        }
        final Composer.ExportReport report = export(job);
        assertEquals(each, report.images(), "both sheets' images, added up");
        assertNotEquals(last, report.images(), "which is not the last sheet's");
        assertTrue(report.summary().endsWith("; " + each.describe()), report::summary);
    }

    /**
     * The INFO lines {@link Composer} logs while this is open, which the tests' WARN would drop: kept
     * in a list, not printed into the build's log, and put back as they were on close.
     */
    private static final class Logged implements AutoCloseable {
        private final ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Composer.class);
        private final Level level = logger.getLevel();
        private final boolean additive = logger.isAdditive();
        private final ListAppender<ILoggingEvent> lines = new ListAppender<>();

        Logged() {
            lines.start();
            logger.addAppender(lines);
            logger.setLevel(Level.INFO);
            logger.setAdditive(false);
        }

        List<String> info() {
            return lines.list.stream().filter(e -> e.getLevel() == Level.INFO)
                    .map(ILoggingEvent::getFormattedMessage).toList();
        }

        @Override
        public void close() {
            logger.detachAppender(lines);
            logger.setLevel(level);
            logger.setAdditive(additive);
        }
    }
}
