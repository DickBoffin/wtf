package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.TileSource;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A {@code .part} is a sheet being written and nothing else. One left over from a run that died –
 * of an {@code OutOfMemoryError}, say, which is not an {@code Exception} – leaves nothing of itself
 * in the next file written through it: a raster is written into an empty file, so a smaller TIFF
 * does not end with a larger one's bytes. And whatever stops a write, the {@code .part} goes, while
 * what stopped it still reaches the caller.
 */
class StalePartTest {

    private static final LonLatBounds BISCAY = new LonLatBounds(-10, 46, 4, 50);

    private static ComposeJob job(final Path out) {
        return TestJobs.job(BISCAY).width(100).dir(out).name("print").build();
    }

    /** Writes the job's first sheet, or all of them into one PDF, to {@code file}. */
    private static void write(final String format, final ComposeJob job, final TilePlan.Plan plan,
                              final TileSource tiles, final Path file, final ProgressSink progress)
            throws IOException {
        if ("pdf".equals(format)) {
            PdfExporter.write(job, plan, plan.sheets(), tiles, file, progress);
        } else {
            RasterExporter.write(job, plan, plan.sheets().getFirst(), tiles, file, format, null, progress);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"png", "tiff", "pdf"})
    @DisplayName("a stale, longer .part leaves nothing in the result")
    void aStaleLongerPartLeavesNothing(final String format, @TempDir final Path dir) throws IOException {
        final Path out = dir.resolve("out");
        final ComposeJob job = job(out);
        final TilePlan.Plan plan = TilePlan.of(job);
        final TileSource tiles = TestJobs.solidCache(dir.resolve("cache"), job, 0x336699);
        final Path file = out.resolve("print." + format);

        write(format, job, plan, tiles, file, ProgressSink.IGNORE);
        final byte[] clean = Files.readAllBytes(file);
        Files.delete(file);

        // What a run that died left: far longer than the file, and nothing like it
        final byte[] stale = new byte[clean.length + (1 << 20)];
        Arrays.fill(stale, (byte) 0xAB);
        final Path part = out.resolve("print." + format + ".part");
        Files.write(part, stale);

        write(format, job, plan, tiles, file, ProgressSink.IGNORE);

        assertArrayEquals(clean, Files.readAllBytes(file),
                "the file is the one written without the stale .part, byte for byte");
        assertEquals(List.of(file), list(out), "and the .part is gone");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"png", "tiff", "pdf"})
    @DisplayName("an OutOfMemoryError while the .part is written removes it and still propagates")
    void anErrorRemovesThePart(final String format, @TempDir final Path dir) throws IOException {
        final Path out = dir.resolve("out");
        final ComposeJob job = job(out);
        final TilePlan.Plan plan = TilePlan.of(job);
        final TileSource tiles = TestJobs.solidCache(dir.resolve("cache"), job, 0x336699);
        final Path file = out.resolve("print." + format);
        final Path part = out.resolve("print." + format + ".part");
        final OutOfMemoryAtPart progress = new OutOfMemoryAtPart(part);

        final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class,
                () -> write(format, job, plan, tiles, file, progress));

        assertSame(progress.error, thrown, "the error itself reaches the caller");
        assertEquals(List.of(), list(out), "no .part and no file");
    }

    private static List<Path> list(final Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.toList();
        }
    }

    /** Runs out of memory the first time it is asked once the {@code .part} is on disk. */
    private static final class OutOfMemoryAtPart implements ProgressSink {
        private final Path part;
        private final OutOfMemoryError error = new OutOfMemoryError("Java heap space (a test's)");

        OutOfMemoryAtPart(final Path part) {
            this.part = part;
        }

        @Override
        public void phase(final @NotNull String name, final long total, final @NotNull Unit unit) {
        }

        @Override
        public void advance(final long delta) {
        }

        @Override
        public void note(final @NotNull String detail) {
        }

        @Override
        public boolean isCanceled() {
            if (Files.exists(part)) {
                throw error;
            }
            return false;
        }

        @Override
        public void finished(final @NotNull String summary) {
        }
    }
}
