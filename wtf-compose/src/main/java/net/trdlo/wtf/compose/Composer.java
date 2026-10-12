package net.trdlo.wtf.compose;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.trdlo.wtf.tiles.NothingThereException;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.Refusal;
import net.trdlo.wtf.tiles.TileSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The engine's front door: plan a job, count what it needs, write the files.
 * <p>
 * {@link #layout} and {@link #inventory} are counting, not reading, and must stay fast enough to
 * run on every control change in the GUI. {@link #export} is the only method that touches a tile's
 * bytes.
 */
public final class Composer {

    private static final Logger log = LoggerFactory.getLogger(Composer.class);

    public @NotNull SheetLayout layout(final @NotNull ComposeJob job) {
        return job.layout();
    }

    public @NotNull TilePlan.Plan plan(final @NotNull ComposeJob job) {
        return TilePlan.of(job);
    }

    public @NotNull TilePlan.Inventory inventory(final @NotNull ComposeJob job, final @NotNull TileSource tiles) {
        return TilePlan.inventory(plan(job), tiles);
    }

    /**
     * What an export wrote.
     *
     * @param files    every file written, in the order they were written
     * @param images   the images of every PDF written, a file per sheet and both kinds of PDF
     *                 added up, and why each decoded one was: the central result's one exception
     *                 (compose-module.md §6). {@link PdfTileImages.Tally#NONE} for an export
     *                 that wrote no PDF – a raster copies nothing, so it has nothing to fall back
     *                 from
     * @param planned  the print files a whole export writes: one per sheet and format, or one per
     *                 format for a single document; the layout and the attribution are not counted
     * @param canceled true when the export was canceled before its last print file was in place.
     *                 {@code files} are then the print files that were finished, and nothing else:
     *                 no layout and no attribution, which describe a whole export (D26)
     * @param proof    true when a print file of this export must not be sent to a printer:
     *                 {@link ComposeJob#isProofOnly()} <b>and</b> the plan actually had a hole to
     *                 mark. The policy alone is not enough – a proof is a file with a hole in it,
     *                 and one drawn under a policy that permits a hole but never met one is a
     *                 plain print
     */
    public record ExportReport(@NotNull List<Path> files, int sheets, int tilesPlaced,
                               long bytes, @NotNull Duration elapsed, PdfTileImages.@NotNull Tally images,
                               int planned, boolean canceled, boolean proof) {

        /**
         * The export in one line, for the log's INFO line and the command line's last: what was
         * written, and – only when a tile went into a PDF decoded rather than copied – how many
         * images were and why.
         */
        public @NotNull String summary() {
            final String work = String.format(Locale.ROOT, "%,d tile placements, %,.1f MB in %s",
                    tilesPlaced, bytes / 1_048_576.0, human(elapsed));
            final String written = canceled
                    ? String.format(Locale.ROOT, "Canceled after %d of %d file(s): %s", files.size(), planned, work)
                    : String.format(Locale.ROOT, "%d file(s), %d sheet(s), %s", files.size(), sheets, work);
            return images.decodedCount() == 0 ? written : written + "; " + images.describe();
        }

        private static @NotNull String human(final @NotNull Duration d) {
            final long s = d.getSeconds();
            return s < 60 ? s + " s" : (s / 60) + " min " + (s % 60) + " s";
        }
    }

    /**
     * Writes everything the job asks for.
     * <p>
     * What happens to a tile that is not cached is {@link ComposeJob#missingTiles()}, and the
     * default is to refuse: silently printing a five-meter map with holes in it is the worst
     * available outcome. A tile the provider has said does not exist – sea, or past the edge of a
     * national provider's coverage – is not a hole and never stops an export, but a plan whose
     * every tile is that – nothing cached, nothing to fetch, the whole area is sea or coverage
     * that never reached it – is refused under every policy ({@link NothingThereException},
     * {@code refuseNothingThere}): the policies say what goes where a tile is missing, and here
     * none is; nothing in the plan is real, and nobody means a print that is sea from edge to edge.
     * <p>
     * <b>A canceled export is neither a failure nor a finished one.</b> The print files already
     * finished stay, the one in progress is deleted by its exporter, and no layout or attribution
     * is written, since both describe a whole export; the report says how far it got (D26). No
     * file is ever left under its final name that is not whole – and the last export's files are
     * not left under theirs either: every file the run would replace, and every other print file
     * of its base name and formats in the folder, is renamed {@code <name>.previous} as the run
     * starts, and deleted only when a run is whole, so the finished sheets of a canceled
     * re-export do not sit beside the last export's later ones as if they were one print, and a
     * whole run of fewer sheets does not leave the last one's extra sheets beside its own (D26).
     * A {@code .previous} file is never replaced: where one is there, the files of its kind in
     * place are a canceled run's and are deleted, so what is aside stays the last whole export
     * however many runs are canceled after it ({@link OutputFiles#moveAside}).
     *
     * @param bandTiles how many rows of tiles a PNG or TIFF holds in memory at once – what the
     *                  command line's {@code --band-tiles} says, one at the least – or null for
     *                  the engine's own, which the studio always asks for: sixteen, fewer on a
     *                  sheet too wide for them ({@link RasterExporter#defaultBandTiles}). A
     *                  project names none; the files come out the same whatever it is
     */
    public @NotNull ExportReport export(final @NotNull ComposeJob job, final @NotNull TileSource tiles,
                                        final @Nullable Integer bandTiles,
                                        final @NotNull ProgressSink progress) throws IOException {
        final Instant start = Instant.now();
        final TilePlan.Plan plan = plan(job);
        // Every sheet of every raster format, before anything is written or set aside: a TIFF past
        // its limit refused after the PDFs would leave them in place of the last export (§7)
        refuseTooLarge(job, plan);
        final TilePlan.Inventory inventory = TilePlan.inventory(plan, tiles);

        // Before the policy is asked, since it has nothing to say here: it decides what goes in a
        // gap, and a print with nothing real in it has no gap, only nothing
        refuseNothingThere(job, inventory);
        if (!inventory.isComplete() && !job.missingTiles().allowsExport()) {
            throw new MissingTilesException(inventory);
        }
        if (!inventory.isComplete()) {
            log.warn("Exporting with {} missing tile(s); policy is to {}",
                    inventory.missing().size(), job.missingTiles().describe());
        }
        job.print().checkUniform();

        // A proof is a file with a hole in it: the policy has to be one that can mark one
        // (isProofOnly) and the plan has to actually have one, or a PLACEHOLDER export of a
        // complete cache would still carry -proof and the warning on a print with nothing missing
        final boolean proof = job.isProofOnly() && !inventory.isComplete();

        Files.createDirectories(job.outputDir());
        final List<Path> outputs = outputs(job, plan, proof);
        // With every print file of this name and these formats that is there already, whatever
        // layout wrote it: a run of four sheets after one of six does not leave the fifth and sixth
        final List<Path> aside = aside(job, outputs);
        // Before the work, not after it: a viewer holding last time's PDF is found now
        OutputFiles.checkReplaceable(OutputFiles.touchedBy(outputs, aside));
        // And last time's files go aside now – or go, when they are a canceled run's – so that a
        // cancel leaves no folder that looks whole
        OutputFiles.moveAside(aside);
        final int planned = printFiles(job, plan, proof).size();
        final List<Path> written = new ArrayList<>();
        int placed = 0;
        long bytes = 0;
        // Every PDF's, not the last one's: a file per sheet is a document per sheet
        PdfTileImages.Tally images = PdfTileImages.Tally.NONE;

        try {
            for (final ComposeJob.OutputFormat format : job.formats()) {
                switch (format) {
                    case PDF -> {
                        if (job.grouping() == ComposeJob.OutputGrouping.SINGLE_DOCUMENT) {
                            CanceledException.check(progress);
                            final Path out = PdfExporter.fileFor(job, null, "pdf", proof);
                            final PdfExporter.Result r =
                                    PdfExporter.write(job, plan, plan.sheets(), tiles, out, progress);
                            written.add(out);
                            placed += r.tilesPlaced();
                            bytes += r.bytes();
                            images = images.plus(r.images());
                        } else {
                            for (final TilePlan.SheetPlan sp : plan.sheets()) {
                                CanceledException.check(progress);
                                final Path out = PdfExporter.fileFor(job, sp.sheet(), "pdf", proof);
                                final PdfExporter.Result r =
                                        PdfExporter.write(job, plan, List.of(sp), tiles, out, progress);
                                written.add(out);
                                placed += r.tilesPlaced();
                                bytes += r.bytes();
                                images = images.plus(r.images());
                            }
                        }
                    }
                    case PNG, TIFF -> {
                        final String extension = format == ComposeJob.OutputFormat.PNG ? "png" : "tif";
                        for (final TilePlan.SheetPlan sp : plan.sheets()) {
                            CanceledException.check(progress);
                            final Path out = PdfExporter.fileFor(job, sp.sheet(), extension, proof);
                            final RasterExporter.Result r = RasterExporter.write(job, plan, sp, tiles, out,
                                    format == ComposeJob.OutputFormat.PNG ? "png" : "tiff",
                                    bandTiles, progress);
                            written.add(out);
                            bytes += r.bytes();
                            placed += sp.tileCount();
                        }
                    }
                }
            }
        } catch (final CanceledException ex) {
            // What was finished stays and is listed; the file in progress is gone already, deleted
            // by its exporter; and nothing is written that would describe a whole export
            final ExportReport report = new ExportReport(written, plan.layout().count(), placed, bytes,
                    Duration.between(start, Instant.now()), images, planned, true, proof);
            log.info("Export canceled: {}", report.summary());
            progress.finished(report.summary());
            return report;
        }

        written.add(writeLayoutFile(job, plan, inventory));
        written.add(writeAttribution(job));
        // Every file is replaced, so what was set aside has served its purpose
        OutputFiles.discardAside(aside);

        final ExportReport report = new ExportReport(written, plan.layout().count(), placed, bytes,
                Duration.between(start, Instant.now()), images, planned, false, proof);
        log.info("Export finished: {}", report.summary());
        progress.finished(report.summary());
        return report;
    }

    /**
     * Every file an export writes, in the order it writes them, on the policy alone: whether any
     * of them would actually be a proof is not known until a cache is checked against a plan, so
     * this is the answer a test gets that has not run one – every test that calls this rather than
     * the three-argument overload below picks a policy for which the two agree. {@link #export}
     * calls that overload with the {@code proof} it resolved.
     */
    static @NotNull List<Path> outputs(final @NotNull ComposeJob job, final TilePlan.@NotNull Plan plan) {
        return outputs(job, plan, job.isProofOnly());
    }

    /** Every file an export writes, in the order it writes them, {@code proof} said by the caller. */
    static @NotNull List<Path> outputs(final @NotNull ComposeJob job, final TilePlan.@NotNull Plan plan,
                                       final boolean proof) {
        final List<Path> out = printFiles(job, plan, proof);
        out.add(job.outputDir().resolve(job.baseName() + OutputFiles.LAYOUT));
        out.add(job.outputDir().resolve(job.baseName() + OutputFiles.ATTRIBUTION));
        return out;
    }

    /**
     * What a run sets aside as it starts: the files it will write, and every print file of its
     * base name and formats already in the folder, in place or set aside – the last export's
     * sheets past this one's count, its whole document or its proofs – so that what the folder
     * holds after a whole run is this run's print and nothing of the last one's (D26).
     */
    private static @NotNull List<Path> aside(final @NotNull ComposeJob job, final @NotNull List<Path> outputs)
            throws IOException {
        final Set<String> extensions = new LinkedHashSet<>();
        for (final ComposeJob.OutputFormat format : job.formats()) {
            extensions.add(extensionOf(format));
        }
        return OutputFiles.filesOf(job.outputDir(), job.baseName(), extensions, outputs);
    }

    private static @NotNull String extensionOf(final ComposeJob.@NotNull OutputFormat format) {
        return switch (format) {
            case PDF -> "pdf";
            case PNG -> "png";
            case TIFF -> "tif";
        };
    }

    /** The print files alone, which is what a canceled export counts its progress in. */
    private static @NotNull List<Path> printFiles(final @NotNull ComposeJob job, final TilePlan.@NotNull Plan plan,
                                                  final boolean proof) {
        final List<Path> out = new ArrayList<>();
        for (final ComposeJob.OutputFormat format : job.formats()) {
            final String extension = extensionOf(format);
            if (format == ComposeJob.OutputFormat.PDF
                    && job.grouping() == ComposeJob.OutputGrouping.SINGLE_DOCUMENT) {
                out.add(PdfExporter.fileFor(job, null, extension, proof));
            } else {
                for (final TilePlan.SheetPlan sp : plan.sheets()) {
                    out.add(PdfExporter.fileFor(job, sp.sheet(), extension, proof));
                }
            }
        }
        return out;
    }

    /**
     * The export was asked to stop.
     * <p>
     * Thrown by the exporters between tiles, bands and sheets, with their {@code .part} already
     * deleted, and turned into a report by {@link #export} – a cancel is somebody's decision, not
     * something that went wrong, so it never reaches a caller of that.
     */
    public static final class CanceledException extends IOException {

        CanceledException() {
            super("Canceled");
        }

        /** Throws when the sink says to stop. */
        static void check(final @NotNull ProgressSink progress) throws CanceledException {
            if (progress.isCanceled()) {
                throw new CanceledException();
            }
        }
    }

    /**
     * A file the export has to replace is held open by another program – most often a PDF viewer
     * still showing the last export – or cannot be written at all.
     */
    public static final class FileInUseException extends IOException {
        private final transient Path file;

        FileInUseException(final @NotNull Path file, final @NotNull Throwable cause) {
            super(file.getFileName() + " cannot be replaced: another program has it open, most likely"
                    + " a PDF or image viewer. Close it there and export again.", cause);
            this.file = file;
        }

        public @NotNull Path file() {
            return file;
        }
    }

    /** Raised rather than printing a map with holes in it. */
    public static final class MissingTilesException extends IOException {
        private final transient TilePlan.Inventory inventory;

        MissingTilesException(final @NotNull TilePlan.Inventory inventory) {
            super("The export needs " + inventory.missing().size() + " tile(s) that are not cached:"
                    + System.lineSeparator() + inventory.describe());
            this.inventory = inventory;
        }

        public @NotNull TilePlan.Inventory inventory() {
            return inventory;
        }
    }

    /**
     * Refuses a plan with nothing real in it, whatever the missing-tile policy. A plan of no tiles
     * at all – neither side drawn, or only the inside of a subject that misses the area – is a
     * {@link Refusal} of its own ({@link ComposeReason#NO_MAP_PRINTED}): it would print blank
     * sheets with a credit, and no provider is to blame. A plan whose every tile is known not to
     * exist, none of it cached, is the downloader's own refusal of a job that came to nothing, so
     * that a window says the press that downloads it and the press that exports it the same way.
     * {@link #export} asks this before it writes anything, and the command line's
     * {@code inventory} and {@code --dry-run} after their count, so a script hears it before an
     * export does.
     */
    static void refuseNothingThere(final @NotNull ComposeJob job, final TilePlan.@NotNull Inventory inventory)
            throws NothingThereException {
        if (inventory.needed() == 0) {
            throw new Refusal(ComposeReason.NO_MAP_PRINTED);
        }
        if (inventory.nothingThere()) {
            throw new NothingThereException(job.provider(), inventory.needed());
        }
    }

    /**
     * Refuses a job with a sheet no raster format it asks for can hold
     * ({@link RasterExporter#refuseTooLarge}): every sheet of every PNG and TIFF, asked by
     * {@link #export} before it writes a file or sets one aside, so that a refusal leaves the
     * folder as it found it.
     */
    private static void refuseTooLarge(final @NotNull ComposeJob job, final TilePlan.@NotNull Plan plan) {
        for (final ComposeJob.OutputFormat format : job.formats()) {
            if (format == ComposeJob.OutputFormat.PDF) {
                continue;
            }
            final String name = format == ComposeJob.OutputFormat.PNG ? "png" : "tiff";
            for (final TilePlan.SheetPlan sp : plan.sheets()) {
                RasterExporter.refuseTooLarge(job, plan, sp.sheet(), name);
            }
        }
    }

    /**
     * Six unlabeled strips on a floor are six identical strips on a floor. Laid out here, in the
     * job's words ({@link ComposeJob#words}): the studio's in the interface language, the command
     * line's in English.
     */
    private static @NotNull Path writeLayoutFile(final @NotNull ComposeJob job,
                                                 final @NotNull TilePlan.Plan plan,
                                                 final @NotNull TilePlan.Inventory inventory) throws IOException {
        final Path out = job.outputDir().resolve(job.baseName() + OutputFiles.LAYOUT);
        final FileWords words = job.words();
        final String nl = System.lineSeparator();
        final StringBuilder sb = new StringBuilder();
        sb.append(words.title(job.baseName())).append(nl);
        sb.append(words.medium(job.medium())).append(nl);
        sb.append(words.regions(job.regions())).append(nl);
        sb.append(words.scale(job.print().mapScale(),
                job.print().dpi(job.regions().zoom(), job.regions().scale()))).append(nl);
        if (!inventory.isComplete()) {
            sb.append(nl).append("!! ").append(words.missing(inventory.missing().size(), job.missingTiles()))
                    .append(nl);
            if (!job.missingTiles().isPrintable()) {
                sb.append("!! ").append(words.proof()).append(nl);
            }
        }
        sb.append(nl).append(plan.layout().describe(words));
        if (plan.layout().count() > 1) {
            // The seam rule SeamMarks places everything by, said once in words
            sb.append(nl).append(wrap(words.hanging()));
        }
        Files.writeString(out, sb, StandardCharsets.UTF_8);
        return out;
    }

    /** How wide a paragraph of the layout file runs before it is wrapped: any editor shows it whole. */
    static final int LINE_WIDTH = 78;

    /**
     * Paragraphs as lines of at most {@link #LINE_WIDTH} characters, broken at spaces – a no-break
     * space is not one – the first line from the margin and every line after it two spaces in. A
     * word longer than a line has a line to itself.
     */
    static @NotNull String wrap(final @NotNull List<String> paragraphs) {
        final StringBuilder sb = new StringBuilder();
        String margin = "";
        for (final String paragraph : paragraphs) {
            if (paragraph.isBlank()) {
                continue;
            }
            final StringBuilder line = new StringBuilder(margin);
            boolean bare = true;
            for (final String word : paragraph.strip().split("\\s+")) {
                if (!bare && line.length() + 1 + word.length() > LINE_WIDTH) {
                    sb.append(line).append(System.lineSeparator());
                    line.setLength(0);
                    line.append("  ");
                    bare = true;
                }
                if (!bare) {
                    line.append(' ');
                }
                line.append(word);
                bare = false;
            }
            sb.append(line).append(System.lineSeparator());
            margin = "  ";
        }
        return sb.toString();
    }

    /**
     * The provider's attribution, beside the files, because several licenses require it: its own
     * words as the provider gives them, then the map and the terms in the job's words.
     */
    private static @NotNull Path writeAttribution(final @NotNull ComposeJob job) throws IOException {
        final Path out = job.outputDir().resolve(job.baseName() + OutputFiles.ATTRIBUTION);
        final FileWords words = job.words();
        final String nl = System.lineSeparator();
        final StringBuilder text = new StringBuilder()
                .append(job.provider().attribution()).append(nl)
                .append(words.theme(job.themeId())).append(nl);
        final String terms = job.provider().termsUrl();
        if (terms != null) {
            text.append(words.terms(terms)).append(nl);
        }
        text.append(words.producedBy()).append(nl);
        Files.writeString(out, text, StandardCharsets.UTF_8);
        return out;
    }
}
