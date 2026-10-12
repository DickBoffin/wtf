package net.trdlo.wtf.studio.steps;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.trdlo.wtf.compose.*;
import net.trdlo.wtf.studio.AppInfo;
import net.trdlo.wtf.studio.BackgroundWork;
import net.trdlo.wtf.studio.Dialogs;
import net.trdlo.wtf.studio.EngineText;
import net.trdlo.wtf.studio.ErrorText;
import net.trdlo.wtf.studio.Forms;
import net.trdlo.wtf.studio.FxProgress;
import net.trdlo.wtf.studio.ProgressText;
import net.trdlo.wtf.studio.ViewJob;
import net.trdlo.wtf.studio.WtfApp;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.studio.Modal;
import net.trdlo.wtf.studio.Prefs;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.tiles.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Step 5: check the cache, fetch what is missing, write the files.
 * <p>
 * Two long operations live here and neither is a spinner. Downloading forty thousand tiles takes
 * hours and assembling a gigabyte PDF takes minutes, so both report where they are, how fast they
 * are going and roughly how long is left – the arithmetic is {@link ProgressModel}'s, in an engine
 * module, so the window and the command line cannot disagree about it. Both can be canceled, and
 * canceling is checked between items rather than by killing a thread.
 */
public final class ExportPanel extends StepPanel {

    private static final Logger log = LoggerFactory.getLogger(ExportPanel.class);

    /**
     * The threads' names: what the log and a thread dump call them, and how a job knows that it is
     * the download, which the close asks and waits for differently.
     */
    static final String DOWNLOAD_THREAD = "download-tiles";
    static final String EXPORT_THREAD = "export";

    private final StepContext ctx;

    private final Label inventoryLabel = new Label();
    private final Label sizeLabel = new Label();
    private final ProgressBar bar = new ProgressBar();
    private final Label progressLabel = new Label();
    private final Button cancelButton = new Button();
    private final Button exportButton;
    private final TextArea logArea = new TextArea();

    /**
     * The count runs itself, a moment after the plan stops changing.
     * <p>
     * Not a button called "Check the cache": that is a question the application can answer
     * without being asked – and one that would have to be asked again after every change, because
     * the answer goes stale. A pause rather than an immediate count, because dragging the print
     * size changes the plan twenty times a second and each count is tens of thousands of file
     * lookups.
     */
    private final javafx.animation.PauseTransition countSoon =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(600));

    /** True while this step is the one on screen; see {@link #shown()}. */
    private boolean visible;

    /**
     * The download or the export in flight, or null. One at a time: Export is off while one runs,
     * and a job's landing clears this only if it still holds that job – so the download's landing,
     * which starts the export, leaves the export here for Cancel and the close to find.
     */
    private @Nullable Job running;
    private final ExportCount count = new ExportCount();
    /** Set while the automatic count is in flight, so a plan change can discard its answer. */
    private int countGeneration;

    public ExportPanel(final @NotNull ProjectModel model, final @NotNull StepContext ctx) {
        super(model);
        this.ctx = ctx;
        this.exportButton = Forms.button("export.run", this::runExport);
        this.countSoon.setOnFinished(e -> countCache());
    }

    @Override
    public @NotNull Step step() {
        return Step.EXPORT;
    }

    /**
     * Counting is for this step only.
     * <p>
     * The count is tens of thousands of file lookups, and the plan changes while somebody is
     * drawing a shape four steps away. It runs when this panel is on screen, and once when it
     * arrives, so the number is there by the time anybody looks at it.
     */
    @Override
    public void shown() {
        visible = true;
        countSoon.playFromStart();
    }

    @Override
    public void hidden() {
        visible = false;
        countSoon.stop();
        countGeneration++;      // anything in flight is answering a question nobody is asking
    }

    @Override
    protected @NotNull Node build() {
        // --- what to write ---------------------------------------------------------------------
        // A file per sheet belongs to the PDF box: only a PDF gathers sheets into one file
        final CheckBox perSheet = new CheckBox(I18n.get("export.grouping.perSheet"));
        live(model.groupingProperty(),
                g -> perSheet.setSelected(g != ComposeJob.OutputGrouping.SINGLE_DOCUMENT));
        perSheet.selectedProperty().addListener((o, a, on) -> model.groupingProperty().set(
                on ? ComposeJob.OutputGrouping.PER_SHEET : ComposeJob.OutputGrouping.SINGLE_DOCUMENT));
        final VBox grouping = new VBox(4, Forms.helped(perSheet, "export.grouping.help"));
        grouping.setPadding(new Insets(0, 0, 2, 22));
        // Without a PDF, or with one sheet, which is one file either way, the choice means
        // nothing. A listener rather than a binding: a bound disable is a property somebody else
        // may not set, and that has already thrown once in this window.
        final Runnable groupingEnabled = () -> {
            final var layout = model.layout();
            grouping.setDisable(!model.formats().contains(ComposeJob.OutputFormat.PDF)
                    || layout == null || layout.count() <= 1);
        };
        live(model.revisionProperty(), r -> groupingEnabled.run());
        model.formats().addListener((javafx.collections.SetChangeListener<ComposeJob.OutputFormat>)
                c -> groupingEnabled.run());

        final VBox formats = new VBox(4);
        for (final ComposeJob.OutputFormat f : ComposeJob.OutputFormat.values()) {
            final CheckBox cb = new CheckBox(I18n.get("export.format." + f.name()));
            cb.setSelected(model.formats().contains(f));
            cb.setTooltip(new Tooltip(I18n.get("export.format.tip." + f.name())));
            cb.selectedProperty().addListener((o, a, on) -> {
                if (on) {
                    model.formats().add(f);
                } else {
                    model.formats().remove(f);
                }
            });
            model.formats().addListener((javafx.collections.SetChangeListener<ComposeJob.OutputFormat>)
                    c -> cb.setSelected(model.formats().contains(f)));
            formats.getChildren().add(cb);
            if (f == ComposeJob.OutputFormat.PDF) {
                formats.getChildren().add(grouping);
            }
        }

        final ComboBox<MissingTilePolicy> missing = Forms.enumCombo(model.missingTilesProperty(),
                MissingTilePolicy.values(), "export.missing");
        final Label missingHint = new Label();
        missingHint.getStyleClass().add("step-summary");
        missingHint.setWrapText(true);
        missingHint.setMaxWidth(290);
        live(model.missingTilesProperty(), p -> missingHint.setText(
                p == null ? "" : I18n.get("export.missing.hint." + p.name())));

        // --- where ------------------------------------------------------------------------------
        final TextField baseName = new TextField(model.baseNameProperty().get());
        baseName.textProperty().bindBidirectional(model.baseNameProperty());

        final TextField dir = new TextField();
        dir.setEditable(false);
        HBox.setHgrow(dir, Priority.ALWAYS);
        live(model.outputDirProperty(), d -> dir.setText(model.effectiveOutputDir().toString()));
        final Button browse = Forms.button("export.browse", () -> {
            final DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(I18n.get("export.browse.title"));
            final Path current = model.effectiveOutputDir();
            if (java.nio.file.Files.isDirectory(current)) {
                chooser.setInitialDirectory(current.toFile());
            }
            final java.io.File chosen = Modal.folder(chooser, ctx.stage());
            if (chosen != null) {
                rememberFolder(chosen.toPath());
                model.outputDirProperty().set(chosen.toPath());
            }
        });
        // An open folder beside the path rather than a word: the text field already says what
        // the button is about, and the icon is the one every file dialog uses for "pick a folder".
        browse.setText(null);
        browse.setGraphic(new org.kordamp.ikonli.javafx.FontIcon(
                org.kordamp.ikonli.fontawesome5.FontAwesomeSolid.FOLDER_OPEN));
        browse.setTooltip(new javafx.scene.control.Tooltip(I18n.get("export.browse.tip")));

        // --- the run ------------------------------------------------------------------------------
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setVisible(false);
        bar.setManaged(false);
        progressLabel.getStyleClass().add("step-summary");
        progressLabel.setWrapText(true);
        // Grows into whatever the button leaves, so the button stays at the right edge however
        // long the progress text is, rather than wherever the text happens to end
        progressLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(progressLabel, Priority.ALWAYS);
        cancelButton.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);

        cancelButton.setText(I18n.get("button.cancel"));
        cancelButton.setOnAction(e -> {
            final Job job = running;
            if (job != null) {
                job.progress.cancel();
                cancelButton.setDisable(true);
                progressLabel.setText(I18n.get("export.canceling"));
            }
        });
        cancelButton.setVisible(false);
        cancelButton.setManaged(false);
        cancelButton.setFocusTraversable(false);

        inventoryLabel.getStyleClass().add("step-summary");
        inventoryLabel.setWrapText(true);
        inventoryLabel.setMaxWidth(290);
        sizeLabel.getStyleClass().add("step-summary");
        sizeLabel.setWrapText(true);
        sizeLabel.setMaxWidth(290);
        live(model.revisionProperty(), r -> invalidateCount());


        logArea.setEditable(false);
        logArea.setPrefRowCount(5);
        logArea.getStyleClass().add("export-log");
        logArea.setVisible(false);
        logArea.setManaged(false);

        // One button. "Check the cache" answered a question the application can answer by itself,
        // and "Download missing" was a step nobody wants for its own sake – what they want is the
        // files, and the download is how you get them. The one that is left says which of the two
        // things it is about to do.
        exportButton.setMaxWidth(Double.MAX_VALUE);
        exportButton.getStyleClass().add("accent");
        final VBox actions = new VBox(6, exportButton, openAfterExport());

        final VBox box = column();
        box.getChildren().addAll(
                Forms.heading("export.title"),
                hint("export.intro"),
                new Label(I18n.get("export.formats")) {{
                    getStyleClass().add("step-label");
                }},
                formats,
                gap(),
                Forms.row("export.baseName", baseName),
                Forms.row("export.dir", new HBox(6, dir, browse)),
                gap(),
                Forms.row("export.missing", missing),
                missingHint,
                gap(),
                creditOnPrint(),
                hint("export.credit.hint"),
                scaleOnPrint(),
                hint("export.scale.hint"),
                gap(),
                Forms.helped(seamMarks(), "export.marks.help"),
                hint("export.marks.hint"),
                gap(),
                sizeLabel,
                inventoryLabel,
                actions,
                bar,
                new HBox(8, progressLabel, cancelButton) {{
                    setAlignment(Pos.CENTER_LEFT);
                }},
                logArea);
        VBox.setMargin(actions, new Insets(6, 0, 0, 0));
        return box;
    }

    // -------------------------------------------------------------------------------------------
    // Counting: cheap enough to run on demand, never on every keystroke
    // -------------------------------------------------------------------------------------------

    /** What the export will weigh, from the tile count and the measured bytes per tile. */
    private void updateEstimate() {
        final ProjectModel.Built built = model.build();
        if (!built.ok()) {
            sizeLabel.setText(built.problem() == null ? "" : built.problem());
            exportButton.setDisable(true);
            exportButton.setText(I18n.get("export.run"));
            return;
        }
        // Off while a job runs, whatever changes meanwhile – an edit in another step, a union
        // landing: a second press would start a second job over the same files
        exportButton.setDisable(running != null);
        final SheetLayout layout = model.layout();
        sizeLabel.setText(layout == null ? "" : I18n.format("export.estimate", I18n.integer(layout.count())));
        updateExportButton();
    }

    /**
     * The button says which of its two jobs it is about to do.
     * <p>
     * Pressing it is the whole consent: the line above it says how many tiles are missing, so
     * "Download and export" is an informed click and a confirmation dialog on top of it would be
     * asking the same question twice. A download big enough to matter still asks – see
     * {@link #CONFIRM_ABOVE}.
     */
    private void updateExportButton() {
        final TilePlan.Inventory inv = count.get();
        final int missing = inv == null ? -1 : inv.missing().size();
        exportButton.setText(missing > 0
                ? I18n.format("export.run.withDownload", I18n.integer(missing))
                : I18n.get("export.run"));
    }

    /**
     * Throws away what was counted and counts again: the plan changed, or the cache did where this
     * panel does not see it – "Forget empty tiles" in the Providers dialog, which is why this is
     * public. A stale count still calls those tiles empty, and Export skips fetching them.
     */
    public void invalidateCount() {
        count.invalidate();
        countGeneration++;
        inventoryLabel.setText("");
        updateEstimate();
        countSoon.playFromStart();
    }

    /**
     * Counts what the cache has, on a background thread, without being asked.
     * <p>
     * Quietly: no progress bar and no busy state, because this runs itself and nothing should
     * flicker every time somebody nudges the print size. If the plan changes while it is counting,
     * the answer is thrown away rather than shown against a plan it was not counted for.
     */
    private void countCache() {
        final ProjectModel.Built built = model.build();
        if (!visible || !built.ok() || running != null) {
            return;         // not under a job; a press with nothing counted counts first
        }
        final ComposeJob job = built.job();
        final int generation = ++countGeneration;
        final Thread counter = new Thread(() -> BackgroundWork.land(() -> TilePlan.inventory(TilePlan.of(job),
                CachedTileSource.cacheOnly(ctx.tileCache(), job.provider())), (inv, failure) -> {
            if (failure instanceof Error) {
                log.warn("Could not count the cache", failure);
                return;
            }
            if (failure != null) {
                // Quietly: this runs itself, unasked, and a plan that cannot be counted this
                // moment is counted again the next time something changes.
                log.debug("Could not count the cache: {}", failure.toString());
                return;
            }
            javafx.application.Platform.runLater(() -> {
                if (generation == countGeneration) {
                    count.set(inv);
                    showInventory(inv);
                }
            });
        }), "count-tiles");
        counter.setDaemon(true);
        counter.start();
    }

    private void showInventory(final TilePlan.@NotNull Inventory inv) {
        inventoryLabel.setText(I18n.format("export.inventory",
                I18n.integer(inv.needed()), I18n.integer(inv.cached()), I18n.integer(inv.empty()),
                I18n.integer(inv.missing().size())));
        updateExportButton();
    }

    // -------------------------------------------------------------------------------------------
    // Downloading: hours, so it says how many hours
    // -------------------------------------------------------------------------------------------

    /**
     * How big a download still asks first.
     * <p>
     * The button already says how many tiles it is about to fetch, so for a screenful or a small
     * print the press is the consent. Ten thousand requests against somebody's metered allowance
     * is a different kind of thing, and worth one dialog.
     */
    private static final int CONFIRM_ABOVE = 2_000;

    /**
     * Fetches what the export is missing, and then exports.
     * <p>
     * Returns false when it has taken over – the export will be started from the download's
     * completion – and true when there was nothing to fetch and the caller should carry on.
     */
    private boolean downloadFirst(final @NotNull ComposeJob job) {
        final Provider provider = job.provider();
        // runExport() never reaches here with a stale answer: countNowThenExport() counts first.
        final TilePlan.Inventory inv = Objects.requireNonNull(count.get());
        final List<TileKey> missing = new ArrayList<>(inv.missing());
        if (missing.isEmpty()) {
            return true;
        }
        // The whole print goes to the downloader, which skips what it has: a print whose last
        // missing tiles are all sea has not come to nothing, and only the whole print says so
        final List<TileKey> wanted = inv.wanted();
        final String apiKey = ctx.prefs().apiKey(provider.id());
        if (provider.needsKey() && apiKey == null) {
            Dialogs.credentialsRejected(ctx.stage(), provider, 401, () -> ctx.openProviders().accept(provider));
            return false;
        }
        if (missing.size() > CONFIRM_ABOVE && !Dialogs.confirm(ctx.stage(),
                I18n.get("export.download.confirm.title"),
                I18n.format("export.download.confirm.body", I18n.integer(missing.size()),
                        provider.displayName()))) {
            return false;
        }

        busy(true);
        final FxProgress progress = startProgress();
        run(DOWNLOAD_THREAD, progress, new Task<TileDownloader.Report>() {
            @Override
            protected TileDownloader.Report call() throws Exception {
                try (final TileDownloader downloader = new TileDownloader(ctx.tileCache(),
                        ctx.prefs().get(Prefs.DOWNLOAD_THREADS),
                        AppInfo.get().userAgent())) {
                    return downloader.download(provider, wanted, apiKey, progress);
                }
            }
        }, report -> {
            appendLog(ProgressText.report(report));
            count.invalidate();
            busy(false);
            if (progress.isCanceled()) {
                // Cancel stops the whole press, not just its first half: an export now would be
                // from a cache still short of the plan. What did arrive is kept, and counted.
                countCache();
                progressLabel.setText(I18n.get("export.download.canceled"));
                return;
            }
            if (report.failed() > 0) {
                // Stop here: an export from a cache that is still short is either refused by the
                // missing-tile policy or quietly holed, and neither is what the press asked for.
                countCache();
                Dialogs.warn(ctx.stage(), I18n.get("export.refused.title"),
                        I18n.format("export.download.incomplete", I18n.integer(report.failed())));
                return;
            }
            export(job);        // what the press was actually about
        });
        return false;
    }

    // -------------------------------------------------------------------------------------------
    // Exporting
    // -------------------------------------------------------------------------------------------

    /**
     * One press: fetch what is missing, then write the files.
     * <p>
     * Downloading is not a separate button to press first, with Export refusing because some
     * tiles are missing – which would be the application knowing exactly what to do and asking to
     * be told to do it.
     */
    private void runExport() {
        if (running != null) {
            return;                 // one job at a time; Export is off while one runs
        }
        model.settleArea();         // an area still being worked out is not the one to print
        model.settleUnions();       // nor is a refusal for a shape still being joined
        final ProjectModel.Built built = model.build();
        if (!built.ok()) {
            return;
        }
        final ComposeJob job = built.job();
        if (job.formats().isEmpty()) {
            return;
        }
        if (count.get() == null) {
            countNowThenExport(job);        // the debounce has not landed; never count on this thread
            return;
        }
        if (!offerToSave()) {
            return;
        }
        // Used, so remembered – including a folder that came with an opened project
        rememberFolder(job.outputDir());
        // A file that must not go to a printer should be hard to produce without noticing.
        if (job.isProofOnly() && !Dialogs.confirm(ctx.stage(),
                I18n.get("export.proof.title"), I18n.get("export.proof.body"))) {
            return;
        }
        if (!downloadFirst(job)) {
            return;             // the download is running; it will export when it is done
        }
        export(job);
    }

    /**
     * Counts what the cache has before exporting, when the debounced count has not landed yet.
     * <p>
     * A press right after this step appears can beat the 600 ms pause in {@link #countSoon}, and
     * {@link #downloadFirst} needs to know what is missing before it can decide anything – and
     * running {@link TilePlan#inventory} itself, tens of thousands of file lookups, on this thread
     * would freeze the window. It counts off the FX thread instead, exactly as the automatic count
     * does, and carries on into {@link #runExport} once the answer is in – unless the plan or the
     * step moved on while it was counting, in which case the count that already re-ran on arrival
     * wins instead.
     */
    private void countNowThenExport(final @NotNull ComposeJob job) {
        countSoon.stop();
        exportButton.setDisable(true);
        final int generation = ++countGeneration;
        final Thread counter = new Thread(() -> BackgroundWork.land(() -> TilePlan.inventory(TilePlan.of(job),
                CachedTileSource.cacheOnly(ctx.tileCache(), job.provider())), (inv, failure) -> Platform.runLater(() -> {
            final boolean current = generation == countGeneration;
            if (current && inv != null) {
                count.set(inv);
                showInventory(inv);
            }
            updateEstimate();       // restores the button either way, whether this counted or threw
            if (current) {
                if (inv != null) {
                    runExport();
                } else {
                    // Said, not swallowed: a press left waiting deserves to know why nothing
                    // happened, rather than a button that is merely enabled again.
                    Dialogs.error(ctx.stage(), failure);
                }
            }
        })), "count-tiles");
        counter.setDaemon(true);
        counter.start();
    }

    private void export(final @NotNull ComposeJob job) {
        busy(true);
        final FxProgress progress = startProgress();
        run(EXPORT_THREAD, progress, new Task<Composer.ExportReport>() {
            @Override
            protected Composer.ExportReport call() throws Exception {
                return write(job, ctx.tileCache(), progress);
            }
        }, report -> {
            appendLog(ProgressText.report(report));
            for (final Path p : report.files()) {
                appendLog("  " + p);
            }
            busy(false);
            progressLabel.setText(ProgressText.report(report));
            // Canceled, the files that stayed are finished sheets of an unfinished print: the line
            // above says how many, and nothing opens as if the export were done
            if (!report.canceled() && !report.files().isEmpty()) {
                inventoryLabel.setText(I18n.format("export.done", report.files().size()));
                if (ctx.prefs().get(Prefs.OPEN_AFTER_EXPORT)) {
                    // The folder, not a file: an export is never one file, since the layout and
                    // the attribution go beside the print
                    Dialogs.openInSystem(report.files().getFirst().getParent());
                }
            }
        });
    }

    /**
     * The export itself: the job's files, from the cache alone, with the rasters banded as the
     * command line bands them without {@code --band-tiles} –
     * {@link RasterExporter#DEFAULT_BAND_TILES} rows of tiles, fewer on a sheet too wide for them.
     * How much of a sheet is held at once is the machine's, not the project's, and the files come
     * out the same whatever it is, so a project does not carry it.
     */
    static @NotNull Composer.ExportReport write(final @NotNull ComposeJob job, final @NotNull TileCache cache,
                                                final @NotNull ProgressSink progress) throws IOException {
        // No band named: the engine's own, as on the command line without --band-tiles
        return new Composer().export(job, CachedTileSource.cacheOnly(cache, job.provider()), null, progress);
    }

    /**
     * The next project that has no folder of its own exports here – and Open and Save start here
     * too, because the folder somebody exports to is usually the one they keep the project in.
     */
    private void rememberFolder(final @NotNull Path folder) {
        ctx.prefs().putPath(Prefs.LAST_EXPORT_DIR, folder);
        ctx.prefs().putPath(Prefs.LAST_PROJECT_DIR, folder);
        model.setRememberedOutputDir(folder);
    }

    /**
     * Asks whether to save first, when there is anything unsaved – and only then.
     * <p>
     * <b>It does not save without asking</b>, though an export is when a project becomes worth
     * keeping: a save goes over the project's own file, so exporting a trial variation would
     * quietly replace the version somebody meant to keep, with nothing to say it had happened. A
     * question costs one keystroke, and Enter answers it: Save is the default.
     * <p>
     * Save does what the diskette button does – to the project's file, or through the file picker
     * if it has never been saved – and Save as what the button beside it does, which is the answer
     * for a trial variation: the file stays as it was and this version gets a name of its own.
     * Export without saving goes on as it is, and Cancel stops, as does a save picker closed
     * without choosing.
     *
     * @return whether the export goes on
     */
    private boolean offerToSave() {
        if (!model.dirtyProperty().get()) {
            return true;
        }
        return Dialogs.askBeforeExport(ctx.stage(), model.fileProperty().get() != null)
                .goesOn(ctx.saveProject(), ctx.saveProjectAs());
    }

    // -------------------------------------------------------------------------------------------
    // What is running, for the window's close request; see Closing
    // -------------------------------------------------------------------------------------------

    /** Whether a download or an export is in flight. */
    public boolean isRunning() {
        return running != null;
    }

    /** Whether what is in flight is the download before the export, rather than the export. */
    public boolean isDownloading() {
        final Job job = running;
        return job != null && job.downloading();
    }

    /**
     * Cancels what is running, whichever of the two it is, and waits on the FX thread until it has
     * tidied up. The worker is a daemon thread, so without the wait the process would end with a
     * {@code .part} on disk.
     * <p>
     * An export is waited for until the sheet in progress is finished or its {@code .part} deleted
     * – as long as one item, since a PDF save cannot stop halfway, and the window is on its way out
     * anyway. A download is waited for at most {@link ViewJob#STOP_WAIT}, as the view's is: a
     * request in flight ignores a cancel, and a stalled one would hold the window frozen for the
     * client's timeouts. What a download still running then leaves is a tile's {@code .part} in
     * the cache, which the next start clears, and no export: its landing sees the cancel.
     */
    public void stopAndWait() {
        final Job job = running;
        if (job == null) {
            return;
        }
        job.progress.cancel();
        try {
            if (!job.downloading()) {
                job.thread.join();
            } else if (!job.thread.join(ViewJob.STOP_WAIT)) {
                log.info("The export step's download did not stop within {} ms; the close goes on without it",
                        ViewJob.STOP_WAIT.toMillis());
            }
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    // -------------------------------------------------------------------------------------------

    /**
     * A download or an export on its worker: the thread, which the close waits for, and the
     * progress, which Cancel and the close cancel. Compared by identity: a landing clears the
     * panel's job only while it is still this one.
     */
    private static final class Job {
        private final @NotNull Thread thread;
        private final @NotNull FxProgress progress;

        Job(final @NotNull Thread thread, final @NotNull FxProgress progress) {
            this.thread = thread;
            this.progress = progress;
        }

        /** Whether this is the download before the export, rather than the export. */
        boolean downloading() {
            return DOWNLOAD_THREAD.equals(thread.getName());
        }
    }

    private @NotNull FxProgress startProgress() {
        bar.setVisible(true);
        bar.setManaged(true);
        bar.setProgress(0);
        cancelButton.setVisible(true);
        cancelButton.setManaged(true);
        cancelButton.setDisable(false);
        logArea.setVisible(true);
        logArea.setManaged(true);
        return new FxProgress(bar, progressLabel, this::appendLog);
    }

    /**
     * Runs a task on a background thread with one place for success, failure and tidy-up.
     * <p>
     * The failure path matters more than the success one: a rejected key opens the provider
     * dialog on that provider, and missing tiles say which and how many rather than throwing a
     * stack trace at somebody who wanted a poster.
     * <p>
     * The task is the panel's running job from its start until it lands, and its landing lets it
     * go before doing anything else, so that what the landing starts – the download's starts the
     * export – is the running job after it, not cleared under it.
     *
     * @param progress what the task reports to, and what Cancel and the close cancel
     */
    private <T> void run(final @NotNull String name, final @NotNull FxProgress progress,
                         final @NotNull Task<T> task, final java.util.function.@NotNull Consumer<T> onSuccess) {
        final Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        final Job job = new Job(thread, progress);
        task.setOnSucceeded(e -> {
            landed(job);
            onSuccess.accept(task.getValue());
        });
        task.setOnFailed(e -> {
            landed(job);
            busy(false);
            final Throwable ex = task.getException();
            log.warn("{} failed", name, ex);
            // A failed job may have changed the cache before it threw, so it is counted again; a
            // refusal over missing tiles is kept meanwhile, or every press would repeat it
            invalidateCount();
            count.failed(ex);
            if (ex instanceof final TileDownloader.MissingCredentialsException bad) {
                final Provider rejected = ctx.provider(bad.providerId());
                Dialogs.credentialsRejected(ctx.stage(), rejected, bad.statusCode(),
                        () -> ctx.openProviders().accept(rejected));
            } else if (ex instanceof final TileDownloader.BulkDownloadRefusedException refused) {
                appendLog(EngineText.of(refused));
                Dialogs.warn(ctx.stage(), I18n.get("download.refused.title"), EngineText.of(refused));
            } else if (ex instanceof final Composer.MissingTilesException missing) {
                appendLog(I18n.format("export.refused.body", I18n.integer(missing.inventory().missing().size())));
                showInventory(missing.inventory());
                Dialogs.warn(ctx.stage(), I18n.get("export.refused.title"),
                        I18n.format("export.refused.body", I18n.integer(missing.inventory().missing().size())));
            } else if (ex instanceof NothingThereException) {
                // A print with nothing in it, from either press: the download that found only sea,
                // or the export of a print already known to be all of it. One refusal, said as the
                // view's download says it; the count, run again, shows what it found.
                final ErrorText.Explained nothing = ErrorText.explain(ex);
                appendLog(nothing.body());
                Dialogs.warn(ctx.stage(), nothing.headline(), nothing.body());
            } else if (ex instanceof final Composer.FileInUseException inUse) {
                // A viewer holding last time's file – common, harmless, and no reason for a stack trace
                final String body = I18n.format("export.inUse.body", inUse.file().getFileName());
                appendLog(body);
                Dialogs.warn(ctx.stage(), I18n.get("export.inUse.title"), body);
            } else {
                Dialogs.error(ctx.stage(), ex);
            }
        });
        running = job;
        thread.start();
    }

    /**
     * A job has landed, and the panel has none running – unless another runs already, which is not
     * this one's to clear.
     */
    private void landed(final @NotNull Job job) {
        if (running == job) {
            running = null;
        }
    }

    private void busy(final boolean on) {
        exportButton.setDisable(on);
        if (!on) {
            bar.setVisible(false);
            bar.setManaged(false);
            cancelButton.setVisible(false);
            cancelButton.setManaged(false);
            updateEstimate();
        }
    }

    private void appendLog(final @NotNull String line) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> appendLog(line));
            return;
        }
        logArea.setVisible(true);
        logArea.setManaged(true);
        // A tile-by-tile log over a six-hour download would be a million lines of heap.
        if (logArea.getLength() > 200_000) {
            logArea.deleteText(0, 100_000);
        }
        logArea.appendText(line + System.lineSeparator());
    }

    @Override
    public @NotNull String summary() {
        final ProjectModel.Built built = model.build();
        if (!built.ok()) {
            return "";
        }
        return summary(built.job().formats(), built.job().grouping());
    }

    /**
     * The formats by name, in the order the step lists them, and for a PDF whether it is a file
     * per sheet or one file – the one choice the other formats do not have.
     */
    static @NotNull String summary(final java.util.@NotNull Set<ComposeJob.OutputFormat> formats,
                                   final ComposeJob.@NotNull OutputGrouping grouping) {
        final String names = java.util.Arrays.stream(ComposeJob.OutputFormat.values())
                .filter(formats::contains)
                .map(f -> I18n.get("export.format." + f.name()))
                .collect(java.util.stream.Collectors.joining(", "));
        if (!formats.contains(ComposeJob.OutputFormat.PDF)) {
            return names;
        }
        return I18n.format("export.summary", names, grouping == ComposeJob.OutputGrouping.PER_SHEET
                ? I18n.get("export.grouping.short.PER_SHEET")
                : I18n.get("export.grouping.short.SINGLE_DOCUMENT"));
    }

    /**
     * Whether the files open when they are written.
     * <p>
     * Beside the button it changes rather than in Settings: a switch that matters in one place
     * belongs in that place, where it is seen when it is wanted. Remembered for the machine, not
     * the project – it is about the person at the keyboard, not the print.
     */
    private @NotNull javafx.scene.control.CheckBox openAfterExport() {
        final javafx.scene.control.CheckBox box = new javafx.scene.control.CheckBox(I18n.get("export.openAfter"));
        box.setTooltip(new javafx.scene.control.Tooltip(I18n.get("export.openAfter.tip")));
        box.setSelected(ctx.prefs().get(Prefs.OPEN_AFTER_EXPORT));
        box.selectedProperty().addListener((o, a, on) -> ctx.prefs().put(Prefs.OPEN_AFTER_EXPORT, on));
        return box;
    }

    /**
     * The sheet's number and place, and the arrows, in the part of each overlap the neighboring
     * sheet covers.
     * <p>
     * Here, beside the export they go into, rather than in Settings: a switch that matters in one
     * place belongs in that place. Kept for the machine, not the project – they are about how the
     * person printing puts sheets together – and off-limits for a single sheet, which has no
     * overlap to put them in.
     */
    private @NotNull javafx.scene.control.CheckBox seamMarks() {
        final javafx.scene.control.CheckBox box = new javafx.scene.control.CheckBox(I18n.get("export.marks"));
        box.setSelected(model.printing().marks());
        box.setWrapText(true);
        box.selectedProperty().addListener((o, a, on) -> {
            final ProjectModel.Printing p = new ProjectModel.Printing(on);
            net.trdlo.wtf.studio.PrintingPrefs.save(ctx.prefs(), p);
            model.setPrinting(p);
        });
        live(model.revisionProperty(), r -> {
            final var layout = model.layout();
            box.setDisable(layout == null || layout.count() <= 1);
        });
        return box;
    }

    /** The provider's credit on the finished print – the project's choice, on unless turned off. */
    private @NotNull javafx.scene.control.CheckBox creditOnPrint() {
        final javafx.scene.control.CheckBox box = new javafx.scene.control.CheckBox(I18n.get("export.credit"));
        box.selectedProperty().bindBidirectional(model.creditOnPrintProperty());
        return box;
    }

    /**
     * The map scale on the finished print, and how – the project's choice, off unless turned on,
     * and offered however the print is sized.
     */
    private @NotNull VBox scaleOnPrint() {
        final javafx.scene.control.CheckBox on = new javafx.scene.control.CheckBox(I18n.get("export.scale"));
        on.setWrapText(true);
        on.selectedProperty().bindBidirectional(model.scaleOnPrintProperty());
        final javafx.scene.control.ComboBox<ScaleMark> how = Forms.enumCombo(
                model.scaleMarkProperty(), ScaleMark.values(), "export.scale.mark");
        final Node row = Forms.row("export.scale.mark", how);
        row.disableProperty().bind(on.selectedProperty().not());
        return new VBox(6, Forms.helped(on, "export.scale.help"), row);
    }
}
