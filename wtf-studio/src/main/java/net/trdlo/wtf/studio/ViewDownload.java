package net.trdlo.wtf.studio;

import javafx.concurrent.Task;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.stage.Stage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.trdlo.wtf.studio.map.TileCanvas;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.tiles.*;

import java.util.List;
import java.util.function.Consumer;

/**
 * Downloading what the view is missing whenever it settles – "fetch as I pan" – and the progress
 * in the status bar. The export's own download lives in step 5, where the counts are.
 * <p>
 * What stands in its way – no key, a rejected key, too many tiles for a viewing-only provider,
 * tiles that would not arrive – is said in a dialog once ({@link FetchAsIPan#tell}), with the
 * Providers dialog one press away, and Try again where trying again may help. Panning on says
 * nothing more until another provider is chosen or its key changes. The attempts behind a failed
 * tile are the downloader's own: a server in trouble is asked three times before the tile fails.
 * <p>
 * Part of {@link MainWindow}, which places its progress; the cache it downloads into is
 * {@link MapSource}'s, asked at the moment it is needed.
 */
final class ViewDownload {

    private static final Logger log = LoggerFactory.getLogger(ViewDownload.class);

    private final Stage stage;
    private final Prefs prefs;
    private final ProjectModel model;
    private final TileCanvas map;
    private final MapSource source;
    /** The window's Providers dialog, opened on the provider a dialog here was about. */
    private final Consumer<@Nullable Provider> openProviders;

    private final ProgressBar progressBar = new ProgressBar();
    private final Label progressLabel = new Label();

    /** Counts change on every frame of a pan; this waits for the view to settle first. */
    private final javafx.animation.PauseTransition autoDownloadDelay =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(600));

    private boolean downloading;
    /** The download in flight, from its start until it lands; the close stops it. */
    private @Nullable ViewJob job;
    /** Set by the close: nothing more starts. */
    private boolean closing;
    /** A dialog of this class is showing, or about to: one at a time. */
    private boolean saying;
    /**
     * What the last download of the view left behind – the tiles it did not get, and whether the
     * key it was using is one the provider has rejected – and what has been said about it. The
     * tiles are not asked for again until a download lands or somebody asks, the key until it
     * changes: a tile the provider keeps refusing, asked for on every settled view, is a request to
     * it on every pan. Off the toolkit so the rules are testable, as {@link Closing} and
     * {@code ExportCount} are.
     */
    private final FetchAsIPan fetch = new FetchAsIPan();

    /**
     * How many of the tiles on screen are ones the last download could not get, and which check of
     * the cache may change it; see {@link #recountFailed}.
     */
    private final FailedOnScreen failed = new FailedOnScreen();

    ViewDownload(final @NotNull Stage stage, final @NotNull Prefs prefs, final @NotNull ProjectModel model,
                 final @NotNull TileCanvas map, final @NotNull MapSource source,
                 final @NotNull Consumer<@Nullable Provider> openProviders) {
        this.stage = stage;
        this.prefs = prefs;
        this.model = model;
        this.map = map;
        this.source = source;
        this.openProviders = openProviders;
    }

    @NotNull ProgressBar progressBar() {
        return progressBar;
    }

    @NotNull Label progressLabel() {
        return progressLabel;
    }

    /** The progress bar, hidden until a download runs: called from the status bar. */
    void installProgress() {
        progressBar.setVisible(false);
        progressBar.setManaged(false);
        progressBar.setPrefWidth(180);
    }

    /** Looks at every view the map settles on, and forgets what was said with every provider chosen. */
    void watchView() {
        map.statsProperty().addListener((o, a, b) -> consider());
        model.providerProperty().addListener((o, a, b) -> fetch.providerChanged());
    }

    /** What the pause after a pan ends in. */
    void armAutoDownload() {
        autoDownloadDelay.setOnFinished(e -> runAutoDownload());
    }

    private @Nullable Provider provider() {
        return model.providerProperty().get();
    }

    /** How many of the tiles on screen are ones the last download could not get. */
    private int failedOnScreen() {
        return failed.count();
    }

    /**
     * Refreshes {@link #failed}, off the FX thread where the view has any tile the last download
     * failed on – the ordinary view, with nothing failed, costs nothing to check. Called on every
     * {@link #consider}, so a settled view, a download that just landed and a cache forgotten empty
     * all reach it. A check already running is left to land, and dropped if the view changed
     * meanwhile; the question asked last runs then, so the count is always the view's on screen.
     */
    private void recountFailed() {
        startCheck(failed.ask(map.visibleKeys().stream().filter(fetch::isFailed).toList()));
    }

    private void startCheck(final FailedOnScreen.@Nullable Check next) {
        if (next == null) {
            return;
        }
        final Thread check = new Thread(() -> BackgroundWork.land(
                () -> (int) next.suspects().stream().filter(k -> !source.cache().contains(k)).count(),
                (found, failure) -> javafx.application.Platform.runLater(() -> {
                    if (failure != null) {
                        log.debug("Could not check the view's failed tiles: {}", failure.toString());
                    }
                    startCheck(failed.landed(next, failure == null ? found : null));
                })
        ), "check-failed");
        check.setDaemon(true);
        check.start();
    }

    // -------------------------------------------------------------------------------------------
    // Downloading the view. The export's own download lives in step 5, where the counts are.
    // -------------------------------------------------------------------------------------------

    private void downloadVisible() {
        final Provider p = provider();
        final Provider.Theme t = model.themeProperty().get();
        if (p == null || t == null || downloading || closing) {
            return;
        }
        final List<TileKey> keys = map.visibleKeys();
        // Held from here until the cache has been read and, if anything comes of it, until the
        // download is done: what the next settled view sees meanwhile is "downloading", not a
        // second read of the cache or a second job on top of this one.
        downloading = true;
        final Thread check = new Thread(() -> BackgroundWork.land(() -> source.cache().inventory(keys),
                (inv, failure) -> javafx.application.Platform.runLater(
                        () -> afterCacheRead(keys, p, inv, failure))),
                "check-cache");
        check.setDaemon(true);
        check.start();
    }

    /** What {@link #downloadVisible} does once it knows what the view needs, or why it does not. */
    private void afterCacheRead(final @NotNull List<TileKey> keys, final @NotNull Provider p,
                                 final TileCache.@Nullable Inventory inv,
                                 final @Nullable Throwable failure) {
        if (closing) {
            return;
        }
        if (failure != null) {
            // A full disk, a network drop – the same reasons the download itself can fail, and
            // met the same way: recorded against every key asked about, so the view is not tried
            // again until something changes.
            downloading = false;
            fetch.threw(keys);
            log.debug("Could not check the cache for the view: {}", failure.toString());
            sayThrown(p, failure);
            return;
        }
        final String apiKey = prefs.apiKey(p.id());
        // FetchAsIPan.mayTry checked the key before the read started, but it can go meanwhile
        if (inv.missing().isEmpty() || p.needsKey() && apiKey == null) {
            downloading = false;
            if (!inv.missing().isEmpty()) {
                sayNoKey(p);
            }
            return;
        }

        progressBar.setVisible(true);
        progressBar.setManaged(true);

        final FxProgress progress = new FxProgress(progressBar, progressLabel);
        final Task<TileDownloader.Report> task = new Task<>() {
            @Override
            protected TileDownloader.Report call() throws Exception {
                try (final TileDownloader downloader =
                             new TileDownloader(source.cache(), prefs.get(Prefs.DOWNLOAD_THREADS),
                                     AppInfo.get().userAgent())) {
                    // The whole view, not only its missing part: a view with any land in it has
                    // not come to nothing, however much of what is missing is sea
                    return downloader.download(p, keys, apiKey, progress);
                }
            }
        };
        task.setOnSucceeded(e -> {
            final TileDownloader.Report report = task.getValue();
            fetch.finished(report.failedKeys());
            finishDownload();
            log.debug("Downloaded the view: {}", report.summary());
            consider();
            if (map.imageCache() != null) {
                map.imageCache().invalidate();
            }
            map.requestPaint();
            sayFailed(p, report);
        });
        task.setOnFailed(e -> {
            finishDownload();
            final Throwable ex = task.getException();
            if (ex instanceof final TileDownloader.MissingCredentialsException bad) {
                // Rejected until the key itself changes, wherever it is changed
                fetch.keyRejected(bad.providerId(), apiKey);
                log.debug("The view's key was rejected for {}", bad.providerId());
                if (bad.statusCode() == 0) {
                    sayNoKey(p);        // the key went before the job asked anything
                } else {
                    sayRejected(p, bad.statusCode());
                }
            } else if (ex instanceof final TileDownloader.BulkDownloadRefusedException refused) {
                // mayTry keeps a job this would refuse from starting, on a count that can be a
                // moment old
                fetch.threw(inv.missing());
                log.debug("The view's download was refused: {}", refused.getMessage());
                sayTooMany(p, refused);
            } else if (ex instanceof NothingThereException) {
                // The answers are recorded, so the view now shows sea where it said "not cached".
                // Nothing to say: open sea is what the map shows.
                if (map.imageCache() != null) {
                    map.imageCache().invalidate();
                }
                map.requestPaint();
                log.debug("Nothing in this view: {}", ex.getMessage());
            } else {
                // A full disk, a provider refusing WTF, a network drop mid-job – whatever it was,
                // it is on every tile this view is missing, so it would recur on every pan.
                fetch.threw(inv.missing());
                log.debug("The view's download failed: {}", ex.getMessage(), ex);
                sayThrown(p, ex);
            }
        });
        job = new ViewJob("download-view", task, progress::cancel);
        job.start();
    }

    /**
     * Stops the view's download and waits for the tile in hand, for at most
     * {@link ViewJob#STOP_WAIT}, for the window's close; see {@link Closing}. Nothing starts after it: a cache read that lands later, or a pause after a
     * pan that ends later, finds the window closing and does nothing.
     */
    void stopAndWait() {
        closing = true;
        autoDownloadDelay.stop();
        if (job != null) {
            job.stopAndWait();
        }
    }

    /**
     * Fetches what the view is missing once it settles.
     * <p>
     * Debounced, because the counts change on every frame of a pan and each one would otherwise
     * start a job. Not capped: a view is a screenful of tiles at the zoom the screen shows, a
     * few hundred at most, however large the print is – the one thing a cap could have stopped,
     * a whole print's worth, is the export's to fetch, and it asks. It asks nothing when the
     * provider has no key, when the provider has rejected the one it has, and when the provider
     * allows no bulk downloading and the view is past what browsing it allows. Called by the map
     * settling and by what changes what it would find: a download landing, the Providers dialog.
     */
    void consider() {
        recountFailed();
        if (downloading || closing) {
            return;
        }
        autoDownloadDelay.playFromStart();
    }

    private void runAutoDownload() {
        final Provider p = provider();
        if (downloading || closing || p == null) {
            return;
        }
        final String apiKey = prefs.apiKey(p.id());
        final int missing = map.statsProperty().get().missing() - failedOnScreen();
        if (fetch.mayTry(p, apiKey, missing)) {
            downloadVisible();
        } else if (missing > 0 && p.needsKey() && apiKey == null) {
            sayNoKey(p);
        } else if (missing > TileDownloader.BROWSE_LIMIT && !p.bulkDownload()) {
            sayTooMany(p, new TileDownloader.BulkDownloadRefusedException(p, missing));
        }
    }

    private void finishDownload() {
        downloading = false;
        job = null;
        progressBar.setVisible(false);
        progressBar.setManaged(false);
        progressLabel.setText("");
    }

    // -------------------------------------------------------------------------------------------
    // Saying what stood in the way, once
    // -------------------------------------------------------------------------------------------

    private void sayNoKey(final @NotNull Provider p) {
        say(FetchAsIPan.Notice.NO_KEY, p, () -> answer(p, Dialogs.viewTrouble(stage,
                I18n.format("view.noKey.title", p.displayName()), I18n.get("view.noKey.body"), false, true)));
    }

    private void sayRejected(final @NotNull Provider p, final int status) {
        say(FetchAsIPan.Notice.KEY_REJECTED, p,
                () -> Dialogs.credentialsRejected(stage, p, status, () -> openProviders.accept(p)));
    }

    private void sayTooMany(final @NotNull Provider p, final TileDownloader.@NotNull BulkDownloadRefusedException refused) {
        say(FetchAsIPan.Notice.TOO_MANY, p, () -> answer(p, Dialogs.viewTrouble(stage,
                I18n.get("download.refused.title"), EngineText.of(refused), false, true)));
    }

    /**
     * Tiles that would not arrive, said with the server's answer, or with why no request could be
     * made for them; nothing when none failed for a reason worth saying.
     */
    private void sayFailed(final @NotNull Provider p, final TileDownloader.@NotNull Report report) {
        final TileDownloader.Failure f = report.failure();
        if (report.failed() == 0 || f == null) {
            return;
        }
        final String tiles = I18n.integer(report.failed());
        final String body;
        if (f.local()) {
            // No server heard of them, so none is to blame: the address or the key is, as the
            // engine says it
            body = I18n.format("view.failed.local", tiles, p.displayName(),
                    ProgressText.phrase(String.valueOf(f.message())));
        } else if (f.status() == 0) {
            body = I18n.format("view.failed.noAnswer", tiles, p.displayName(), String.valueOf(f.message()));
        } else if (f.retryable()) {
            body = I18n.format("view.failed.http", tiles, p.displayName(), String.valueOf(f.status()));
        } else {
            body = I18n.format("view.failed.httpFinal", tiles, p.displayName(), String.valueOf(f.status()));
        }
        say(FetchAsIPan.Notice.FAILED, p, () -> answer(p, Dialogs.viewTrouble(stage,
                I18n.get("view.failed.title"), body, f.retryable(), true)));
    }

    /**
     * A job that threw: a cache that would not take a tile, no connection, a provider refusing WTF
     * whatever key it is given. The last is not worth trying again, and no key would help it.
     */
    private void sayThrown(final @NotNull Provider p, final @NotNull Throwable ex) {
        final ErrorText.Explained e = ErrorText.explain(ex);
        final boolean refused = ex instanceof Refusal;
        say(FetchAsIPan.Notice.FAILED, p, () -> answer(p, Dialogs.viewTrouble(stage,
                e.headline(), e.body(), !refused, !refused)));
    }

    /**
     * Shows a dialog when this is the first time for the provider and its key, and none of these is
     * showing already. Later, not now: a dialog waits for its answer in a loop of its own, which
     * JavaFX does not allow inside the pause after a pan.
     */
    private void say(final FetchAsIPan.@NotNull Notice notice, final @NotNull Provider p,
                     final @NotNull Runnable show) {
        if (closing || saying || !fetch.tell(notice, p.id(), prefs.apiKey(p.id()))) {
            return;
        }
        saying = true;
        javafx.application.Platform.runLater(() -> {
            try {
                if (!closing) {
                    show.run();
                }
            } finally {
                saying = false;
            }
        });
    }

    private void answer(final @NotNull Provider p, final Dialogs.@NotNull ViewTrouble pressed) {
        switch (pressed) {
            case RETRY -> {
                fetch.retry();
                downloadVisible();
            }
            case PROVIDERS -> openProviders.accept(p);
            case CLOSED -> { }
        }
    }
}
