package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.CachedTileSource;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.TileCache;
import net.trdlo.wtf.tiles.TileKey;
import net.trdlo.wtf.tiles.TileSource;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cancel after the first sheet of three, and the folder holds what D26 says: the first sheet,
 * whole, under its name; nothing of the second, not even a {@code .part}; nothing of the third; no
 * layout and no attribution, which describe a whole export; and a report that says it was
 * canceled after one of three.
 * <p>
 * A canceled export leaves no folder that looks finished: no PDF saved with its second page half
 * placed and a layout beside it, no TIFF renamed into place with its unwritten strips black, and no
 * error for a button press where a PNG was being written.
 * <p>
 * Three strips of a roll, with nothing in the cache and the background allowed in its place:
 * what is tested here is which files exist, not what is on them.
 */
class CanceledExportTest {

    private static final LonLatBounds BISCAY = new LonLatBounds(-10, 46, 4, 50);

    /** 280 mm across a 100 mm roll: three strips, the last one 80 mm wide. */
    private static ComposeJob job(final Path out, final ComposeJob.OutputFormat format,
                                  final ComposeJob.OutputGrouping grouping) {
        return TestJobs.job(BISCAY).width(280).medium(new Medium.Roll(100, Medium.Axis.VERTICAL))
                .formats(format).grouping(grouping).dir(out).name("print").build();
    }

    private static TileSource emptyCache(final Path dir, final ComposeJob job) {
        return CachedTileSource.cacheOnly(new TileCache(dir.resolve("cache")), job.provider());
    }

    /**
     * Canceled from the moment a phase of this name begins, as if somebody pressed Cancel just
     * then; and it notes whether a {@code .part} was on disk when the exporter noticed.
     */
    private static final class CancelAt implements ProgressSink {
        private final String phase;
        private final Path out;
        private boolean canceled;
        private boolean sawPart;
        private @Nullable String finished;

        CancelAt(final String phase, final Path out) {
            this.phase = phase;
            this.out = out;
        }

        @Override
        public void phase(final @NotNull String name, final long total, final @NotNull Unit unit) {
            canceled |= name.equals(phase);
        }

        @Override
        public void advance(final long delta) {
        }

        @Override
        public void note(final @NotNull String detail) {
        }

        @Override
        public boolean isCanceled() {
            if (canceled && !sawPart) {
                try (Stream<Path> files = Files.list(out)) {
                    sawPart = files.anyMatch(p -> p.getFileName().toString().endsWith(".part"));
                } catch (final IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }
            return canceled;
        }

        @Override
        public void finished(final @NotNull String summary) {
            finished = summary;
        }
    }

    static Stream<Arguments> cancels() {
        final String placingTheSecond = String.format(Locale.ROOT, PdfExporter.PHASE_PLACING, 2);
        final String rasterizingTheSecond = String.format(Locale.ROOT, RasterExporter.PHASE_RASTERIZING, 2);
        return Stream.of(
                // The second page has begun, and nothing of it is on disk yet
                Arguments.of(ComposeJob.OutputFormat.PDF, ComposeJob.OutputGrouping.PER_SHEET,
                        placingTheSecond, List.of("print-s01.pdf"), 3, false),
                // The second is saved and not yet renamed: the last moment a cancel takes it back
                Arguments.of(ComposeJob.OutputFormat.PDF, ComposeJob.OutputGrouping.PER_SHEET,
                        String.format(Locale.ROOT, PdfExporter.PHASE_WRITING, "print-s02.pdf"),
                        List.of("print-s01.pdf"), 3, true),
                Arguments.of(ComposeJob.OutputFormat.PNG, ComposeJob.OutputGrouping.PER_SHEET,
                        rasterizingTheSecond, List.of("print-s01.png"), 3, true),
                Arguments.of(ComposeJob.OutputFormat.TIFF, ComposeJob.OutputGrouping.PER_SHEET,
                        rasterizingTheSecond, List.of("print-s01.tif"), 3, true),
                // One document of three pages is not whole with one of them, so nothing stays
                Arguments.of(ComposeJob.OutputFormat.PDF, ComposeJob.OutputGrouping.SINGLE_DOCUMENT,
                        placingTheSecond, List.of(), 1, false));
    }

    @ParameterizedTest(name = "{0}, {1}, canceled at \"{2}\"")
    @MethodSource("cancels")
    @DisplayName("cancel after the first sheet of three")
    void cancelAfterTheFirstSheetOfThree(final ComposeJob.OutputFormat format,
                                         final ComposeJob.OutputGrouping grouping, final String cancelAt,
                                         final List<String> kept, final int planned,
                                         final boolean partOnDisk, @TempDir final Path dir) throws IOException {
        final Path out = dir.resolve("out");
        final ComposeJob job = job(out, format, grouping);
        assertEquals(3, job.layout().count(), "the job is the three strips it is meant to be");
        final CancelAt progress = new CancelAt(cancelAt, out);

        final Composer.ExportReport report = new Composer().export(job, emptyCache(dir, job), 0, progress);

        final List<Path> finished = kept.stream().map(out::resolve).toList();
        assertTrue(report.canceled(), "the report says it was canceled");
        assertEquals(finished, report.files(), "and lists what stayed, and nothing else");
        assertEquals(planned, report.planned(), "out of how many");
        assertTrue(report.summary().startsWith("Canceled after " + kept.size() + " of " + planned + " "),
                report::summary);
        assertEquals(report.summary(), progress.finished, "the progress sink is told the same");

        try (Stream<Path> files = Files.list(out)) {
            assertEquals(Set.copyOf(finished), files.collect(Collectors.toSet()),
                    "on disk: the finished sheet and nothing else – no later sheet, no .part, no layout, "
                            + "no attribution");
        }
        assertEquals(partOnDisk, progress.sawPart, partOnDisk
                ? "the second sheet's .part was on disk when the cancel was noticed, and is gone now"
                : "the cancel came before anything of the second sheet was written");
        long bytes = 0;
        for (final Path file : finished) {
            assertWhole(file);
            bytes += Files.size(file);
        }
        assertEquals(bytes, report.bytes(), "the bytes counted are those of the files that stayed");
    }

    @Test
    @DisplayName("left alone, the same export writes the three strips, the layout and the attribution")
    void leftAlone(@TempDir final Path dir) throws IOException {
        final Path out = dir.resolve("out");
        final ComposeJob job = job(out, ComposeJob.OutputFormat.PDF, ComposeJob.OutputGrouping.PER_SHEET);

        final Composer.ExportReport report = new Composer().export(job, emptyCache(dir, job), 0, ProgressSink.IGNORE);

        assertFalse(report.canceled());
        assertEquals(3, report.planned());
        assertEquals(Composer.outputs(job, new Composer().plan(job)), report.files());
        assertEquals(5, report.files().size(), "three strips, the layout and the attribution");
        assertTrue(report.summary().startsWith("5 file(s), 3 sheet(s), "), report::summary);
    }

    /**
     * A cancel pressed while the document is being saved stops the save where it is – within the
     * buffer the output stream was handed – rather than waiting for all of it and then deleting it.
     * The tiles are noise, so the document is many buffers long.
     */
    @Test
    @DisplayName("a cancel during the save stops it within a buffer and leaves no file")
    void aCancelDuringTheSave(@TempDir final Path dir) throws IOException {
        final Path out = dir.resolve("out");
        final ComposeJob job = job(out, ComposeJob.OutputFormat.PDF,
                ComposeJob.OutputGrouping.SINGLE_DOCUMENT);
        final TilePlan.Plan plan = TilePlan.of(job);
        final TileCache cache = new TileCache(dir.resolve("cache"));
        final Random random = new Random(86);
        for (final TileKey key : plan.distinctKeys()) {
            cache.store(key, noise(random), null);
        }
        final TileSource tiles = CachedTileSource.cacheOnly(cache, job.provider());
        final Path pdf = out.resolve("print.pdf");

        // Left alone, the document is this long
        PdfExporter.write(job, plan, plan.sheets(), tiles, pdf, ProgressSink.IGNORE);
        final long whole = Files.size(pdf);
        Files.delete(pdf);
        assertTrue(whole > 50 * BUFFER, () -> "the document is many buffers long, not " + whole);

        final CancelDuringSave progress = new CancelDuringSave(pdf.resolveSibling("print.pdf.part"));
        assertThrows(Composer.CanceledException.class,
                () -> PdfExporter.write(job, plan, plan.sheets(), tiles, pdf, progress));

        assertTrue(progress.partAtCancel >= 0, "the cancel came while the .part was being written");
        assertTrue(progress.partAtCancel <= 2 * BUFFER, () -> "the save stopped within a buffer, "
                + progress.partAtCancel + " bytes in, of " + whole);
        try (Stream<Path> files = Files.list(out)) {
            assertEquals(List.of(), files.toList(), "no file and no .part");
        }
    }

    /** What a {@code BufferedOutputStream} holds before it writes, as {@code PDDocument.save} has it. */
    private static final int BUFFER = 8192;

    /** Canceled from the second time it is asked in the writing phase, noting the .part's size then. */
    private static final class CancelDuringSave implements ProgressSink {
        private final Path part;
        private boolean writing;
        private int asked;
        private long partAtCancel = -1;

        CancelDuringSave(final Path part) {
            this.part = part;
        }

        @Override
        public void phase(final @NotNull String name, final long total, final @NotNull Unit unit) {
            writing = name.startsWith("Writing ");
        }

        @Override
        public void advance(final long delta) {
        }

        @Override
        public void note(final @NotNull String detail) {
        }

        @Override
        public boolean isCanceled() {
            if (!writing || ++asked < 2) {
                return false;
            }
            if (partAtCancel < 0) {
                try {
                    partAtCancel = Files.size(part);
                } catch (final IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }
            return true;
        }

        @Override
        public void finished(final @NotNull String summary) {
        }
    }

    /** A tile of noise, which no compression makes small. */
    private static byte[] noise(final Random random) throws IOException {
        final BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                img.setRGB(x, y, random.nextInt(0x1000000));
            }
        }
        return TestJobs.png(img);
    }

    /** Opens as what it claims to be, the way it would in a viewer or at a print shop. */
    private static void assertWhole(final Path file) throws IOException {
        if (file.getFileName().toString().endsWith(".pdf")) {
            try (PDDocument doc = Loader.loadPDF(file.toFile())) {
                assertEquals(1, doc.getNumberOfPages(), () -> file + " is one sheet");
            }
        } else {
            assertNotNull(ImageIO.read(file.toFile()), () -> file + " reads as an image");
        }
    }
}
