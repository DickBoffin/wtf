package net.trdlo.wtf.studio;

import atlantafx.base.theme.NordDark;
import atlantafx.base.theme.NordLight;
import javafx.application.Application;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.stage.Stage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.trdlo.wtf.studio.project.ProjectIo;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import net.trdlo.wtf.tiles.TileCache;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * The application.
 * <p>
 * Startup does as little on the FX thread as it can. Loading the provider catalog reads a file
 * and clearing stale {@code .part} files walks the cache, which is a quarter of a million files on
 * the machine this was written for; both run on a background thread behind {@link Splash}, so the
 * pause has something on screen to explain it.
 */
public final class WtfApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(WtfApp.class);

    /** Long enough that the window is up and the user is looking at the map, not at a dialog. */
    private static final Duration UPDATE_CHECK_DELAY = Duration.ofSeconds(6);

    private static @Nullable HostServices hostServices;

    private MainWindow window;

    /** Where projects opened from the file manager arrive; null in a WTF that is not the first. */
    private @Nullable SingleInstance instance;

    /**
     * Set in {@code main} when a running WTF was found but never claimed the file handed to it –
     * told to the person once this, the second WTF, has a window up, in {@link #open}. A Cancel or
     * a failure at the running window is not kept here: it already answered, so this process just
     * exits, as {@code main} does for {@link SingleInstance.Outcome#TAKEN} too.
     */
    private static boolean pendingHandOverTimedOut;

    /** For the dialogs that open a browser. */
    public static @Nullable HostServices hostServices() {
        return hostServices;
    }

    /** Set once, by the one instance JavaFX makes; static because dialogs need it without one. */
    private static void rememberHostServices(final @NotNull HostServices services) {
        hostServices = services;
    }

    public static void applyTheme(final boolean dark) {
        Application.setUserAgentStylesheet(
                (dark ? new NordDark() : new NordLight()).getUserAgentStylesheet());
    }

    /**
     * What the background thread works out before there is a window.
     *
     * @param recovery where this window keeps a copy of its unsaved work; null in a capture run
     * @param orphans  the copies whose windows have gone, locked until the window has offered them
     */
    private record Startup(@NotNull ProviderCatalog catalog, @NotNull TileCache cache, int partsCleared,
                           @Nullable Recovery recovery, @NotNull List<Recovery.Orphan> orphans) { }

    @Override
    public void start(final @NotNull Stage stage) {
        rememberHostServices(getHostServices());
        final AppInfo info = AppInfo.get();
        log.info("{} {} starting", info.name(), info.version());
        catchWhatNobodyCaught();

        // An unattended run is armed before anything can open a dialog or write a setting, so a
        // startup failure does not wait for a click nobody would give, and a run leaves no seeds
        // behind. The exit code says failure from here until a picture is written, and no window
        // of the run hears the mouse or the keys, which would otherwise reach the picture
        BatchMode.arm(getParameters());
        if (BatchMode.isAnyRequested(getParameters())) {
            Dialogs.setBatchMode(true);
            Prefs.setReadOnly(true);
            BatchMode.refuseInput();
            final String unknown = BatchMode.unknownFlag(getParameters());
            if (unknown != null) {
                System.err.println("[batch] no such capture: " + unknown);
                Platform.exit();
                return;
            }
        }
        final Prefs prefs = Prefs.app();
        final String language = BatchMode.languageOf(getParameters());
        I18n.useLanguage(language != null ? language : prefs.get(Prefs.LANGUAGE));
        final String units = BatchMode.unitsOf(getParameters());
        Units.use(units != null ? units : prefs.get(Prefs.UNITS));
        applyTheme(prefs.get(Prefs.DARK_THEME));

        // The splash capture is the one unattended mode that wants the splash
        if (BatchMode.isSplashRequested(getParameters())) {
            final Splash only = new Splash();
            only.show();
            only.loadBackdrop(prefs);
            BatchMode.captureSplash(only.scene(),
                    BatchMode.splashOutputFor(getParameters()), splashDelayMillis(getParameters()));
            return;
        }
        final boolean batch = BatchMode.isRequested(getParameters())
                || BatchMode.isStepsRequested(getParameters())
                || BatchMode.isPreviewRequested(getParameters())
                || BatchMode.isOutlinesRequested(getParameters());
        // No splash in an unattended run: a second window is one more thing that can be captured
        // by mistake, and there is nobody to reassure.
        final Splash splash = batch || !prefs.get(Prefs.SHOW_SPLASH) ? null : new Splash();
        if (splash != null) {
            // Where the main window is about to open, which is where the eye already is
            splash.show(prefs.screenOf("main"));
            splash.loadBackdrop(prefs);
        }

        final Task<Startup> startup = new Task<>() {
            @Override
            protected Startup call() {
                say(splash, I18n.get("splash.providers"));
                // One place decides where the user's provider file lives, so the startup load
                // and the editor that writes it cannot disagree about the path.
                final ProviderCatalog catalog =
                        ProviderCatalog.load(net.trdlo.wtf.tiles.ProviderStore.defaultFile());
                // The keys the providers need, asked of where they are kept now, behind the splash.
                // Asked once and kept (ApiKeys), and the first question links WTF to the system's
                // functions, which the toolbar's first look at the map would otherwise wait for.
                for (final Provider p : catalog.all()) {
                    if (p.needsKey()) {
                        prefs.apiKey(p.id());
                    }
                }

                say(splash, I18n.get("splash.cache"));
                final TileCache cache = new TileCache(TileCache.resolveRoot(prefs.getPath(Prefs.CACHE_DIR)));
                log.info("Tile cache: {}", cache.root());
                // A .part file left by a killed download is dead weight; clear the stale ones once.
                final int cleared = cache.cleanPartFiles(Duration.ofHours(1));
                // The copies of unsaved work whose windows have gone, found here and offered once
                // the window is up. None in a capture run: it seeds unsaved projects of its own,
                // which the next real start would offer back, and it has nobody to ask.
                final Recovery recovery = batch ? null : new Recovery(Recovery.defaultFolder());
                return new Startup(catalog, cache, cleared, recovery,
                        recovery == null ? List.of() : recovery.orphans());
            }
        };
        startup.setOnSucceeded(e -> {
            if (splash == null) {
                open(stage, prefs, startup.getValue(), batch);
                return;
            }
            // Build the window now, behind the splash, but let the splash decide when to go:
            // it holds itself open for its minimum, so it is actually seen.
            splash.say(I18n.get("splash.window"));
            final Startup ready = startup.getValue();
            splash.finished(() -> open(stage, prefs, ready, batch));
        });
        startup.setOnFailed(e -> {
            if (splash != null) {
                splash.hide();
            }
            log.error("Startup failed", startup.getException());
            Dialogs.error(stage, startup.getException());
            Platform.exit();
        });
        final Thread thread = new Thread(startup, "startup");
        thread.setDaemon(true);
        thread.start();
    }

    /** {@code --screenshot-splash=file@2800} catches a later frame; the sweep is worth seeing twice. */
    private static long splashDelayMillis(final Application.@Nullable Parameters parameters) {
        if (parameters != null) {
            for (final String raw : parameters.getRaw()) {
                final int at = raw.indexOf('@');
                if (raw.startsWith(BatchMode.ARG_SPLASH) && at > 0) {
                    try {
                        return Long.parseLong(raw.substring(at + 1));
                    } catch (final NumberFormatException ignored) {
                        break;
                    }
                }
            }
        }
        return 1_400;
    }

    private static void say(final @Nullable Splash splash, final @NotNull String message) {
        if (splash != null) {
            splash.say(message);
        }
    }

    private void open(final @NotNull Stage stage, final @NotNull Prefs prefs,
                      final @NotNull Startup startup, final boolean batch) {
        if (startup.partsCleared() > 0) {
            log.info("Cleared {} stale .part file(s)", startup.partsCleared());
        }
        window = new MainWindow(stage, prefs, startup.catalog(), startup.cache());
        stage.setScene(window.build());
        stage.setTitle(AppInfo.get().name());
        Windows.decorate(stage, prefs, "main");
        stage.setOnCloseRequest(e -> {
            // A download or an export in step 5 is asked about before the unsaved changes, and
            // stopped and waited for only once both are answered; the view's download is stopped
            // and waited for without a question; see Closing
            final net.trdlo.wtf.studio.steps.ExportPanel exporting = window.exportPanel();
            final boolean closes = Closing.decide(exporting.isRunning(),
                    () -> Dialogs.confirm(stage, I18n.get("close.running.title"),
                            I18n.get(exporting.isDownloading() ? "close.running.download" : "close.running.export")),
                    window::confirmDiscard, exporting::stopAndWait, window::stopViewDownload);
            if (!closes) {
                e.consume();
                return;
            }
            // Saved, or thrown away: nothing is left for a recovery copy to keep
            window.discardRecoveryCopy();
            window.saveState();
        });
        stage.show();

        final CaptureSeeds seeds = window.seeds();
        // --seed-subject=<file> and --seed-view=<lon>,<lat>,<zoom>, read before the modifiers below:
        // @drawn seeds an area from whatever view the map opens on, and a view asked for on the
        // command line must be the one it uses, not the machine's own last view landing first
        for (final String raw : getParameters().getRaw()) {
            if (raw.startsWith("--seed-subject=")) {
                seeds.seedSubjectFrom(java.nio.file.Path.of(raw.substring("--seed-subject=".length())));
            } else if (raw.startsWith("--seed-view=")) {
                final String[] v = raw.substring("--seed-view=".length()).split(",");
                seeds.seedView(Double.parseDouble(v[0]), Double.parseDouble(v[1]), Double.parseDouble(v[2]));
            }
        }
        // @hike, @poster, @wallpaper or @city: the example, opened as its card opens it, before the
        // seeds below – which seed nothing from the view over it, since it fills every step
        final net.trdlo.wtf.studio.project.Example example = BatchMode.exampleOf(getParameters());
        if (example != null) {
            window.openExample(example);
        }
        if (BatchMode.hasModifier(getParameters(), "drawn")) {
            seeds.seedAreaFromView();
            seeds.seedDrawnArea();
        }
        if (BatchMode.hasModifier(getParameters(), "edit")) {
            // A many-part subject, so the step-1 picture shows handles on a country rather than
            // on a four-corner rectangle. Nothing here turns editing on – the step does that.
            seeds.seedEditableSubject();
        }
        if (BatchMode.hasModifier(getParameters(), "outline")) {
            seeds.seedOutlineArea();
        }
        if (BatchMode.isFaded(getParameters()) && !BatchMode.isPreviewRequested(getParameters())) {
            seeds.seedAreaFromView();
            seeds.seedFadedTreatment();            // faded outside the subject, full color in
        }
        if (BatchMode.hasModifier(getParameters(), "onesheet")) {
            seeds.seedPrintWidth(150);
        }
        if (BatchMode.hasModifier(getParameters(), "sheets")) {
            seeds.seedPrintWidth(1000);
        }
        if (BatchMode.isStepsRequested(getParameters())) {
            // Steps 2 to 5 have nothing to show without a shape, and an empty panel proves
            // nothing. Seed one from the view so each picture shows a populated panel.
            seeds.seedAreaFromView();
            final net.trdlo.wtf.studio.steps.Step[] steps = net.trdlo.wtf.studio.steps.Step.values();
            BatchMode.captureSteps(stage, stage.getScene(),
                    BatchMode.stepsDirFor(getParameters()), seeds.pendingDecodes(),
                    i -> window.rail().select(steps[i]), steps.length, steps[0].number());
            return;
        }
        if (BatchMode.isRequested(getParameters())) {
            BatchMode.capture(stage, stage.getScene(), BatchMode.outputFor(getParameters()),
                    seeds.pendingDecodes());
            return;
        }
        if (BatchMode.isOutlinesRequested(getParameters())) {
            final String[] a = BatchMode.outlinesArgs(getParameters());
            final boolean opened = net.trdlo.wtf.studio.outline.OutlinePicker.openForCapture(stage, a[1], scene ->
                    BatchMode.capture(stage, scene, java.nio.file.Path.of(a[0]), () -> { }));
            if (!opened) {
                // No outlines to pick from, said on the console already: nothing will be
                // photographed, so the run ends rather than leave its window up for good
                Platform.exit();
            }
            return;
        }
        if (BatchMode.isPreviewRequested(getParameters())) {
            // The preview needs a print to preview, so seed one exactly as the step pictures do
            seeds.seedAreaFromView();
            if (BatchMode.isFaded(getParameters())) {
                seeds.seedFadedTreatment();        // both regions, as the print has them
            }
            final net.trdlo.wtf.studio.steps.ScalePreview preview = seeds.openPreview();
            preview.setCalibrating(BatchMode.isPreviewCalibrating(getParameters()));
            // The preview's own decodes, each of which downloads its tile first when it must –
            // not the main map's, which may be done long before the preview has drawn a tile
            BatchMode.capture(stage, preview.scene(), BatchMode.previewOutputFor(getParameters()),
                    seeds.pendingDecodes(preview));
            return;
        }

        try {
            // A .wtf file opened from a file manager: the installers register the extension, and
            // Windows and Linux hand WTF the file as its argument. Else the project the window
            // last closed on, as an editor reopens what was open – nothing after New.
            final Path given = ProjectFiles.atStart(projectToOpen(getParameters().getUnnamed()),
                    prefs.getPath(Prefs.LAST_PROJECT));
            if (given != null) {
                window.openProjectFile(given);
            }
            // And the next one opened while this window is up comes here instead of starting a WTF,
            // opened once the window is asking nothing else
            instance = SingleInstance.serve(LogDir.path(),
                    new HandOver(stage, window::confirmDiscard, window::openProjectFile));
            if (pendingHandOverTimedOut) {
                // A running WTF was found, but never claimed the file this process was started with:
                // opened above like any other start with a project, and said now so the person at the
                // keyboard is not left wondering why there are suddenly two windows.
                pendingHandOverTimedOut = false;
                Dialogs.warn(stage, I18n.get("project.handOver.title"), I18n.get("project.handOver.timedOut"));
            }
        } finally {
            // Posted rather than asked here: with the splash on, this runs inside its animation,
            // where no dialog can wait for an answer. After the questions above, and when one of
            // them throws too, so this window keeps its copies whatever they did. The copies of
            // unsaved work first, then what is new.
            final Recovery recovery = startup.recovery();
            Platform.runLater(() -> {
                try {
                    if (recovery != null) {
                        window.keepRecoveryCopies(recovery, startup.orphans());
                    }
                } finally {
                    afterUpdate(stage, prefs, startup.catalog());
                }
            });
        }
        scheduleUpdateCheck(stage, prefs);
    }

    /** The first argument that names a project file, or null when WTF was started without one. */
    static @Nullable Path projectToOpen(final @NotNull List<String> unnamed) {
        for (final String arg : unnamed) {
            if (arg.toLowerCase(Locale.ROOT).endsWith(ProjectIo.EXTENSION)) {
                try {
                    return Path.of(arg);
                } catch (final InvalidPathException ignored) {
                    // not a path on this system, whatever it ends with
                }
            }
        }
        return null;
    }

    /**
     * Shows what changed, once, the first time a new version runs. The version last seen is
     * remembered rather than a "have I shown this" flag, so a downgrade does not re-announce.
     */
    private static void afterUpdate(final @NotNull Stage stage, final @NotNull Prefs prefs,
                                    final @NotNull ProviderCatalog catalog) {
        final String running = Changelog.baseVersion(AppInfo.get().version());
        final String seen = prefs.get("startup.lastVersion");
        prefs.put("startup.lastVersion", running);
        prefs.flush();

        if (seen == null || seen.equals(running) || !prefs.get(Prefs.SHOW_WHATS_NEW)) {
            return;     // a first run is not an update, and there is nothing to compare against
        }
        Platform.runLater(() -> AboutDialog.show(stage, catalog, prefs, true));
    }

    private static void scheduleUpdateCheck(final @NotNull Stage stage, final @NotNull Prefs prefs) {
        if (!prefs.get(Prefs.CHECK_UPDATES)) {
            return;
        }
        final javafx.animation.PauseTransition wait =
                new javafx.animation.PauseTransition(javafx.util.Duration.seconds(
                        UPDATE_CHECK_DELAY.toSeconds()));
        // Unasked, so quiet about anything but a release the user has not already declined
        wait.setOnFinished(e -> UpdateCheck.check(prefs, false).thenAccept(found ->
                found.newer().filter(release -> !UpdateCheck.isSkipped(prefs, release))
                        .ifPresent(release -> Platform.runLater(
                                () -> AboutDialog.offer(stage, prefs, release)))));
        wait.play();
    }

    @Override
    public void stop() {
        if (instance != null) {
            instance.close();
        }
        if (window != null) {
            window.stopRecoveryCopies();
            window.saveState();
        }
    }

    /** When the last one was shown; see {@link #catchWhatNobodyCaught}. */
    private static volatile long lastUncaughtDialog;

    /**
     * An exception nothing caught goes into the log, always, and in front of the user when it
     * happened on the FX thread – at most one dialog in ten seconds, because a fault in painting
     * repeats on every frame, and a dialog per frame is a window nobody can close.
     * <p>
     * Left to itself, the FX thread prints it to a console an installed application does not
     * have, and carries on.
     */
    private static void catchWhatNobodyCaught() {
        final Thread.UncaughtExceptionHandler handler = (thread, ex) -> {
            log.error("Uncaught on {}", thread.getName(), ex);
            final long now = System.currentTimeMillis();
            if (javafx.application.Platform.isFxApplicationThread() && now - lastUncaughtDialog > 10_000) {
                lastUncaughtDialog = now;
                Dialogs.error(null, ex);
            }
        };
        Thread.setDefaultUncaughtExceptionHandler(handler);
        Thread.currentThread().setUncaughtExceptionHandler(handler);
    }

    public static void main(final String[] args) {
        // A project opened from the file manager while WTF is up goes to the WTF that is up.
        // Only a start with nothing but a project: a flag means somebody is running it by hand.
        final List<String> plain = java.util.Arrays.stream(args).filter(a -> !a.startsWith("--")).toList();
        final Path project = plain.size() == args.length ? projectToOpen(plain) : null;
        if (project != null) {
            switch (SingleInstance.handOver(LogDir.path(), project)) {
                // Taken, or the running window already asked about it and got an answer – a
                // Cancel or a failure to open both were already said to the person at that
                // window, so opening a second one here would only argue with, or repeat, that.
                case TAKEN, DECLINED, FAILED -> System.exit(0);
                // Never claimed: this process opens the file itself, as projectToOpen below does
                // for a plain start, and says why once its window is up.
                case TIMED_OUT -> pendingHandOverTimedOut = true;
                case NONE -> { }        // nobody running there, or one that went without answering: opens below, quietly
            }
        }
        launch(args);
        // non-zero when an unattended run produced nothing, so a caller can tell a hang from a pass
        System.exit(BatchMode.exitCode());
    }
}
