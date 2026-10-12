package net.trdlo.wtf.compose;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import net.trdlo.wtf.tiles.CachedTileSource;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.TileCache;
import net.trdlo.wtf.tiles.TileKey;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A file the export has to replace and cannot: said before the work, by name, and with nothing left
 * half-written behind it.
 * <p>
 * The real case is a PDF viewer holding last time's file, which Java cannot imitate – it always
 * opens files letting others write too. A read-only file is refused for writing the same way, and
 * is what these tests use: the permission bits where the file system has them, the DOS attribute on
 * Windows. The CI runners are Linux.
 */
class OutputFilesTest {

    private static ComposeJob job(final Path dir) {
        return job(dir, EnumSet.of(ComposeJob.OutputFormat.PDF), ComposeJob.OutputGrouping.SINGLE_DOCUMENT);
    }

    private static ComposeJob job(final Path dir, final java.util.Set<ComposeJob.OutputFormat> formats,
                                  final ComposeJob.OutputGrouping grouping) {
        return job(dir, formats, grouping, MissingTilePolicy.TRANSPARENT);
    }

    private static ComposeJob job(final Path dir, final java.util.Set<ComposeJob.OutputFormat> formats,
                                  final ComposeJob.OutputGrouping grouping, final MissingTilePolicy missing) {
        final LonLatBounds bohemia = new LonLatBounds(12.09, 49.44, 15.98, 50.73);
        return TestJobs.job(bohemia).zoom(5).missing(missing).formats(formats)
                .grouping(grouping).dir(dir).name("print").build();
    }

    /**
     * The permission bits where there are any, the DOS attribute where there are not. Not the other
     * way round: Linux supports the "dos" view too, as an extended attribute nothing enforces, so a
     * file marked read-only that way can still be opened for writing.
     */
    private static void readOnly(final Path file, final boolean on) throws IOException {
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString(
                    on ? "r--r--r--" : "rw-r--r--"));
        } else {
            Files.setAttribute(file, "dos:readonly", on);
        }
    }

    @Test
    @DisplayName("a file that cannot be replaced stops the export before anything is written, and is named")
    void aHeldFileIsFoundFirst(@TempDir final Path dir) throws IOException {
        final Path held = dir.resolve("print.pdf");
        Files.writeString(held, "the last export");
        readOnly(held, true);
        try {
            final ComposeJob job = job(dir);
            final Composer.FileInUseException ex = assertThrows(Composer.FileInUseException.class,
                    () -> new Composer().export(job,
                            CachedTileSource.cacheOnly(new TileCache(dir.resolve("cache")), job.provider()),
                            0, ProgressSink.IGNORE));
            assertEquals(held, ex.file());
            assertEquals("the last export", Files.readString(held), "left as it was");
            try (var files = Files.list(dir)) {
                assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".part")),
                        "no half-written file left behind");
            }
        } finally {
            readOnly(held, false);
        }
    }

    @Test
    @DisplayName("the list of outputs names every file the export writes")
    void theOutputsAreTheFilesWritten(@TempDir final Path dir) throws IOException {
        final ComposeJob job = job(dir);
        final var written = new Composer().export(job,
                CachedTileSource.cacheOnly(new TileCache(dir.resolve("cache")), job.provider()),
                0, ProgressSink.IGNORE).files();
        assertEquals(java.util.Set.copyOf(written),
                java.util.Set.copyOf(Composer.outputs(job, new Composer().plan(job))));
    }

    /** Wide enough for several sheets of A4, so a cancel can fall between two of them. */
    private static ComposeJob wideJob(final Path dir) {
        return wideJob(dir, 800);
    }

    /** The same print at another width, so under the same base name with another number of sheets. */
    private static ComposeJob wideJob(final Path dir, final double widthMm) {
        final LonLatBounds bohemia = new LonLatBounds(12.09, 49.44, 15.98, 50.73);
        return TestJobs.job(bohemia).zoom(5).width(widthMm).perSheet().dir(dir)
                .name("print").build();
    }

    private static final byte[] LAST_EXPORT = "the last export".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    /** Writes a last export into the folder: every file this job would write, with known content. */
    private static java.util.List<Path> lastExport(final ComposeJob job) throws IOException {
        Files.createDirectories(job.outputDir());
        final java.util.List<Path> outputs = Composer.outputs(job, new Composer().plan(job));
        for (final Path p : outputs) {
            Files.write(p, LAST_EXPORT);
        }
        return outputs;
    }

    /** Canceled from the moment this file's save begins: the sheets before it are finished, it is not. */
    private static final class CancelWhileWriting implements ProgressSink {
        private final String phase;
        private boolean canceled;

        CancelWhileWriting(final String file) {
            this.phase = String.format(Locale.ROOT, PdfExporter.PHASE_WRITING, file);
        }

        @Override
        public void phase(final @org.jetbrains.annotations.NotNull String name, final long total,
                          final @org.jetbrains.annotations.NotNull Unit unit) {
            canceled |= name.equals(phase);
        }

        @Override
        public void advance(final long delta) {
        }

        @Override
        public void note(final @org.jetbrains.annotations.NotNull String detail) {
        }

        @Override
        public boolean isCanceled() {
            return canceled;
        }

        @Override
        public void finished(final @org.jetbrains.annotations.NotNull String summary) {
        }
    }

    @Test
    @DisplayName("a canceled re-export leaves the last export's files aside, not under their names")
    void aCanceledReExportMovesTheLastOneAside(@TempDir final Path dir) throws IOException {
        final ComposeJob job = wideJob(dir);
        assertTrue(job.layout().count() >= 2, "the job is meant to have sheets to cancel between");
        final java.util.List<Path> outputs = lastExport(job);

        final Composer.ExportReport report = new Composer().export(job,
                CachedTileSource.cacheOnly(new TileCache(dir.resolve("cache")), job.provider()),
                0, new CancelWhileWriting("print-s02.pdf"));

        assertTrue(report.canceled());
        final Path first = dir.resolve("print-s01.pdf");
        assertEquals(java.util.List.of(first), report.files(), "one sheet was finished");
        assertFalse(java.util.Arrays.equals(LAST_EXPORT, Files.readAllBytes(first)), "and it is this run's");

        final java.util.Set<Path> expected = new java.util.HashSet<>();
        expected.add(first);
        for (final Path p : outputs) {
            expected.add(OutputFiles.previousOf(p));
        }
        try (var files = Files.list(dir)) {
            assertEquals(expected, files.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toSet()),
                    "on disk: this run's finished sheet, and the last export as .previous files – no later "
                            + "sheet, no layout and no attribution under a name that would make one print of them");
        }
        assertArrayEquals(LAST_EXPORT, Files.readAllBytes(OutputFiles.previousOf(dir.resolve("print-s02.pdf"))),
                "nothing was lost, only renamed");
    }

    @Test
    @DisplayName("a whole re-export replaces every file and leaves nothing aside")
    void aWholeReExportLeavesNothingAside(@TempDir final Path dir) throws IOException {
        final ComposeJob job = wideJob(dir);
        final java.util.List<Path> outputs = lastExport(job);

        final Composer.ExportReport report = new Composer().export(job,
                CachedTileSource.cacheOnly(new TileCache(dir.resolve("cache")), job.provider()),
                0, ProgressSink.IGNORE);

        assertFalse(report.canceled());
        try (var files = Files.list(dir)) {
            assertEquals(java.util.Set.copyOf(outputs),
                    files.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toSet()),
                    "every file replaced, and no .previous left behind");
        }
        for (final Path p : outputs) {
            assertFalse(java.util.Arrays.equals(LAST_EXPORT, Files.readAllBytes(p)), p.toString());
        }
    }

    private static final byte[] NEIGHBOR = "not this project's".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    /**
     * Files beside the print that are not this job's: another project whose base name starts with
     * this one's, a note that only looks like it, and a format this job does not write.
     */
    private static java.util.List<Path> neighbors(final Path dir) throws IOException {
        final java.util.List<Path> neighbors = java.util.List.of(dir.resolve("print-east-s01.pdf"),
                dir.resolve("print-east.pdf"), dir.resolve("print-s01-notes.pdf"), dir.resolve("print-s01.png"));
        for (final Path p : neighbors) {
            Files.write(p, NEIGHBOR);
        }
        return neighbors;
    }

    private static java.util.Set<Path> onDisk(final Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toSet());
        }
    }

    private static void assertUntouched(final java.util.List<Path> neighbors) throws IOException {
        for (final Path p : neighbors) {
            assertArrayEquals(NEIGHBOR, Files.readAllBytes(p), "left as it was: " + p.getFileName());
        }
    }

    /**
     * Six sheets exported, then four under the same base name: the run writes over four, and
     * the fifth and sixth do not stay under their names beside a layout that does not mention them
     * – nor does a whole document or a proof the last runs left.
     */
    @Test
    @DisplayName("a re-export with fewer sheets leaves its own print and nothing of the last one's")
    void aReExportWithFewerSheetsLeavesNothingOfTheLast(@TempDir final Path dir) throws IOException {
        final ComposeJob six = wideJob(dir, 800);
        final ComposeJob four = wideJob(dir, 560);
        assertEquals(6, six.layout().count(), "the first run lays out six sheets");
        assertEquals(4, four.layout().count(), "the second four");
        final TileCache cache = new TileCache(dir.resolve("cache"));
        new Composer().export(six, CachedTileSource.cacheOnly(cache, six.provider()), 0, ProgressSink.IGNORE);
        for (final String older : java.util.List.of("print.pdf", "print-s03-proof.pdf", "print-s05.pdf")) {
            Files.write(dir.resolve(older), LAST_EXPORT);
        }
        final java.util.List<Path> neighbors = neighbors(dir);

        final Composer.ExportReport report = new Composer().export(four,
                CachedTileSource.cacheOnly(cache, four.provider()), 0, ProgressSink.IGNORE);

        assertFalse(report.canceled());
        final java.util.Set<Path> expected = new java.util.HashSet<>(Composer.outputs(four, new Composer().plan(four)));
        expected.addAll(neighbors);
        assertEquals(expected, onDisk(dir), "the four sheets, their layout and attribution, and the neighbors");
        assertUntouched(neighbors);
    }

    @Test
    @DisplayName("a canceled re-export with fewer sheets leaves the last one's extra sheets aside too")
    void aCanceledReExportWithFewerSheetsMovesTheExtraAside(@TempDir final Path dir) throws IOException {
        final ComposeJob six = wideJob(dir, 800);
        final ComposeJob four = wideJob(dir, 560);
        final TileCache cache = new TileCache(dir.resolve("cache"));
        final java.util.List<Path> last = new Composer().export(six,
                CachedTileSource.cacheOnly(cache, six.provider()), 0, ProgressSink.IGNORE).files();
        final java.util.List<Path> neighbors = neighbors(dir);

        final Composer.ExportReport report = new Composer().export(four,
                CachedTileSource.cacheOnly(cache, four.provider()), 0, new CancelWhileWriting("print-s02.pdf"));

        assertTrue(report.canceled());
        final Path first = dir.resolve("print-s01.pdf");
        assertEquals(java.util.List.of(first), report.files(), "one sheet was finished");
        final java.util.Set<Path> expected = new java.util.HashSet<>(neighbors);
        expected.add(first);
        for (final Path p : last) {
            expected.add(OutputFiles.previousOf(p));
        }
        assertEquals(expected, onDisk(dir),
                "this run's finished sheet, all six of the last run's sheets and its layout and attribution as "
                        + ".previous files, and the neighbors – no s05 or s06 under a name that prints");
        assertUntouched(neighbors);
    }

    private static Composer.ExportReport export(final ComposeJob job, final TileCache cache,
                                                final ProgressSink progress) throws IOException {
        return new Composer().export(job, CachedTileSource.cacheOnly(cache, job.provider()), 0, progress);
    }

    /** A cache holding a tile of this color for every tile the job needs. */
    private static TileCache solid(final Path dir, final ComposeJob job, final int rgb) throws IOException {
        final TileCache cache = new TileCache(dir);
        final byte[] tile = TestJobs.solidPng(rgb, 64);
        for (final TileKey key : TilePlan.of(job).distinctKeys()) {
            cache.store(key, tile, null);
        }
        return cache;
    }

    private static String sheet(final int number) {
        return String.format(Locale.ROOT, "print-s%02d.pdf", number);
    }

    /** Every file in the folder and what is in it. */
    private static Map<Path, byte[]> contents(final Path dir) throws IOException {
        final Map<Path, byte[]> contents = new HashMap<>();
        for (final Path p : onDisk(dir)) {
            contents.put(p, Files.readAllBytes(p));
        }
        return contents;
    }

    private static void assertContents(final Map<Path, byte[]> expected, final Map<Path, byte[]> actual,
                                       final String what) {
        assertEquals(expected.keySet(), actual.keySet(), what);
        for (final Map.Entry<Path, byte[]> e : expected.entrySet()) {
            assertArrayEquals(e.getValue(), actual.get(e.getKey()), what + ": " + e.getKey().getFileName());
        }
    }

    /**
     * Export, cancel after one sheet, export and cancel again: the second run finds the first
     * canceled one's sheets in place and the last whole export aside, and the sheets go – they
     * neither take the whole export's place aside nor join it. The whole export and the runs after
     * it are drawn from tiles of two colors, so that no file of one is byte for byte a file of the
     * other.
     * <p>
     * Once with the same print; once with one of fewer sheets, whose whole run at the end clears
     * the whole export's fifth and sixth sheets from aside as well; once with one of more, whose
     * first canceled run gets past the whole export's last sheet before it stops; and once after a
     * whole export as one document, a name none of the later runs' sheets is written under.
     */
    @ParameterizedTest(name = "whole at {0} mm, {1}, then canceled at {2} mm after {3}, and again after 1")
    @CsvSource({"800, PER_SHEET, 800, 1", "800, PER_SHEET, 560, 1", "560, PER_SHEET, 800, 5",
            "800, SINGLE_DOCUMENT, 800, 2"})
    @DisplayName("two canceled exports in a row leave the last whole one aside, whole")
    void twoCanceledExportsKeepTheLastWholeOneAside(final double wholeMm, final ComposeJob.OutputGrouping grouping,
                                                    final double laterMm, final int firstFinished,
                                                    @TempDir final Path dir) throws IOException {
        final ComposeJob whole = TestJobs.from(wideJob(dir, wholeMm)).grouping(grouping).build();
        final ComposeJob later = wideJob(dir, laterMm);
        final Map<Path, byte[]> lastWhole = new HashMap<>();
        for (final Path p : export(whole, solid(dir.resolve("blue"), whole, 0x336699), ProgressSink.IGNORE).files()) {
            lastWhole.put(OutputFiles.previousOf(p), Files.readAllBytes(p));
        }
        final TileCache since = solid(dir.resolve("brown"), later, 0x996633);

        final Composer.ExportReport first = export(later, since, new CancelWhileWriting(sheet(firstFinished + 1)));
        assertTrue(first.canceled());
        assertEquals(firstFinished, first.files().size(), "the first canceled run finished as many sheets");
        final Composer.ExportReport second = export(later, since, new CancelWhileWriting(sheet(2)));
        assertTrue(second.canceled());

        final Path finished = dir.resolve(sheet(1));
        assertEquals(List.of(finished), second.files(), "the second finished one sheet");
        final Map<Path, byte[]> expected = new HashMap<>(lastWhole);
        expected.put(finished, Files.readAllBytes(finished));
        assertContents(expected, contents(dir), "on disk: the second canceled run's sheet, and the last whole "
                + "export aside as it wrote it – none of the first canceled run's sheets, in place or aside");
        for (final byte[] aside : lastWhole.values()) {
            assertFalse(Arrays.equals(aside, expected.get(finished)), "no file of the whole export is the new "
                    + "sheet byte for byte, so the test tells them apart");
        }

        final Composer.ExportReport done = export(later, since, ProgressSink.IGNORE);
        assertFalse(done.canceled());
        assertEquals(Set.copyOf(done.files()), onDisk(dir), "a whole run then leaves its own print, and nothing "
                + "aside – not even the sheets of the last whole export past its own");
    }

    /**
     * A rename that fails halfway stops the run before it writes anything, and the renames made
     * before it are put back: the folder is as it was. What fails here is a name the file system
     * takes but not with {@code .previous} after it – 250 characters, where 255 is the limit –
     * which fails on every system; on Windows a viewer that lets a file be written but not renamed
     * does the same, which Java cannot imitate. It is an earlier print's sheet, which goes aside
     * after the files this run writes – so they are renamed already when it fails.
     */
    @Test
    @DisplayName("a rename that fails halfway leaves the folder as it was")
    void aRenameThatFailsHalfwayLeavesTheFolderAsItWas(@TempDir final Path dir) throws IOException {
        final ComposeJob job = wideJob(dir);
        final TileCache cache = new TileCache(dir.resolve("cache"));
        export(job, cache, ProgressSink.IGNORE);
        final Path stuck = dir.resolve("print-s" + "0".repeat(237) + "17.pdf");
        Files.write(stuck, LAST_EXPORT);
        final Map<Path, byte[]> before = contents(dir);
        assertEquals(9, before.size(), "six sheets, the layout and the attribution, and the earlier sheet");

        final Composer.FileInUseException ex = assertThrows(Composer.FileInUseException.class,
                () -> export(job, cache, ProgressSink.IGNORE));

        assertEquals(stuck, ex.file(), "the file named is the one that would not go aside");
        assertContents(before, contents(dir), "every file under its own name, as it was – none left as .previous");
    }

    /**
     * Two projects whose names are this one's with what a print file's name adds: the whole
     * document of {@code print-s07} is {@code print-s07.pdf}, which could be the seventh sheet of
     * {@code print}, and that of {@code print-proof} is {@code print-proof.pdf}, which could be a
     * proof of it. Their layout and attribution say whose the files are – in place, or set aside by
     * a canceled run of their own – and no run of {@code print}, canceled or whole, sets them aside
     * or deletes them or what they set aside. A ninth sheet nothing claims is {@code print}'s, an
     * earlier print's, and goes aside and then away.
     */
    @Test
    @DisplayName("another project's document, under a name this print could write too, is left alone")
    void anotherProjectsDocumentIsLeftAlone(@TempDir final Path dir) throws IOException {
        final List<Path> theirs = List.of(dir.resolve("print-s07.pdf"), dir.resolve("print-s07-layout.txt"),
                dir.resolve("print-s07-attribution.txt"), dir.resolve("print-proof.pdf.previous"),
                dir.resolve("print-proof-layout.txt.previous"), dir.resolve("print-proof-attribution.txt.previous"));
        for (final Path p : theirs) {
            Files.write(p, NEIGHBOR);
        }
        final Path ninth = dir.resolve(sheet(9));
        Files.write(ninth, LAST_EXPORT);
        final ComposeJob job = wideJob(dir);
        assertEquals(6, job.layout().count(), "six sheets, none of them the seventh or the ninth");
        final TileCache cache = new TileCache(dir.resolve("cache"));

        final Composer.ExportReport canceled = export(job, cache, new CancelWhileWriting(sheet(2)));
        assertTrue(canceled.canceled());
        final Set<Path> expected = new HashSet<>(theirs);
        expected.add(dir.resolve(sheet(1)));
        expected.add(OutputFiles.previousOf(ninth));
        assertEquals(expected, onDisk(dir), "a canceled run: its sheet, its own ninth aside, and the others' as they were");

        final Composer.ExportReport whole = export(job, cache, ProgressSink.IGNORE);
        assertFalse(whole.canceled());
        expected.clear();
        expected.addAll(theirs);
        expected.addAll(whole.files());
        assertEquals(expected, onDisk(dir), "a whole run: its print, and the others' as they were");
        assertUntouched(theirs);
    }

    /**
     * A TIFF or PNG is always one sheet's, so its name always has the sheet's number: {@code wall.tif}
     * is no file of {@code wall}'s, and {@code wall-s01.tif} – the first sheet of {@code wall} – is
     * none of a project named {@code wall-s01}.
     */
    @Test
    @DisplayName("the names a job could write, whatever its layout, and nothing that only starts like one")
    void thePrintFileNamesOfABaseName() {
        final java.util.regex.Pattern name = OutputFiles.printFileName("wall", java.util.List.of("pdf", "tif"));
        for (final String ours : java.util.List.of("wall.pdf", "wall-s01.pdf", "wall-s123.pdf", "wall-proof.pdf",
                "wall-s02-proof.pdf", "wall-s07.tif", "wall-s07-proof.tif")) {
            assertTrue(name.matcher(ours).matches(), ours);
        }
        for (final String theirs : java.util.List.of("wall-east-s01.pdf", "wall-east.pdf", "wall-s1.pdf",
                "wall-s01.png", "wall-s02-linked.pdf", "wall-layout.txt", "wall-s01.pdf.previous",
                "wall-s01.pdf.part", "wall-proof-s01.pdf", "wallpaper.pdf", "my-wall.pdf", "wall.tif",
                "wall-proof.tif")) {
            assertFalse(name.matcher(theirs).matches(), theirs);
        }
        assertFalse(OutputFiles.printFileName("wall-s01", java.util.List.of("tif")).matcher("wall-s01.tif").matches(),
                "the first sheet of wall is not a file of wall-s01's");
    }

    /** The cache is empty, so every tile is missing and the marker is on every one of them. */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.EnumSource(ComposeJob.OutputGrouping.class)
    @DisplayName("a proof says so in the name of every print file it writes, and a print in none")
    void aProofIsNamedOne(final ComposeJob.OutputGrouping grouping, @TempDir final Path dir) throws IOException {
        final java.util.Set<ComposeJob.OutputFormat> formats = EnumSet.of(ComposeJob.OutputFormat.PDF,
                ComposeJob.OutputFormat.PNG);
        for (final MissingTilePolicy missing : java.util.List.of(MissingTilePolicy.PLACEHOLDER,
                MissingTilePolicy.TRANSPARENT)) {
            final Path out = dir.resolve(missing.name());
            final ComposeJob job = job(out, formats, grouping, missing);
            final java.util.List<Path> written = new Composer().export(job,
                    CachedTileSource.cacheOnly(new TileCache(dir.resolve("cache")), job.provider()),
                    0, ProgressSink.IGNORE).files();
            assertEquals(java.util.Set.copyOf(Composer.outputs(job, new Composer().plan(job))),
                    java.util.Set.copyOf(written), "the list before the work names the same files");

            final boolean proof = missing == MissingTilePolicy.PLACEHOLDER;
            for (final Path file : written) {
                final String name = file.getFileName().toString();
                if (name.endsWith(".txt")) {
                    assertFalse(name.contains("proof"), "the layout and the credits describe it: " + name);
                    continue;
                }
                assertEquals(proof, name.matches("print(-s\\d\\d)?-proof\\.(pdf|png)"),
                        missing + ": " + name);
                assertEquals(proof, name.contains("proof"), missing + ": " + name);
            }
        }
    }

    /**
     * {@code PLACEHOLDER} lets a hole through marked; with every tile cached there is no hole to
     * mark, so this is a plain print. The plan the policy resolves to names a file a proof, not
     * the policy alone.
     */
    @Test
    @DisplayName("a proof policy with nothing missing writes a plain print, not a proof")
    void placeholderWithNothingMissingIsNotAProof(@TempDir final Path dir) throws IOException {
        final ComposeJob job = job(dir, EnumSet.of(ComposeJob.OutputFormat.PDF,
                ComposeJob.OutputFormat.PNG), ComposeJob.OutputGrouping.PER_SHEET, MissingTilePolicy.PLACEHOLDER);
        final TileCache cache = new TileCache(dir.resolve("cache"));
        final byte[] tile = TestJobs.solidPng(0x336699, 64);
        for (final TileKey key : TilePlan.of(job).distinctKeys()) {
            cache.store(key, tile, null);
        }
        final Composer.ExportReport report = new Composer().export(job,
                CachedTileSource.cacheOnly(cache, job.provider()), 0, ProgressSink.IGNORE);

        assertFalse(report.proof(), "nothing is missing, so this is a plain print");
        for (final Path file : report.files()) {
            assertFalse(file.getFileName().toString().contains("proof"), file.toString());
        }
    }
}
