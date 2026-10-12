package net.trdlo.wtf.studio;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.trdlo.wtf.tiles.*;
import net.trdlo.wtf.studio.project.ProjectModel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The window that says something is happening while the startup work runs.
 * <p>
 * <b>The world, lasered in.</b> Four scan heads sweep across a dark frame and the map appears
 * behind them, washed out at first and coming up to full color as the last one lands. The map is
 * the user's own cached imagery – whatever provider and theme they last looked at – read from the
 * coarsest zoom the cache holds, so the splash is a picture of the thing the application is for
 * rather than a logo on a gradient. No imagery is bundled: shipping somebody else's tiles inside
 * an installer is a license question nobody needs.
 * <p>
 * It runs for at least {@link #MINIMUM_MILLIS}, because a splash nobody ever sees is a splash
 * that cost work for nothing, and it keeps sweeping if the work outlasts it. It also waits for
 * the backdrop, up to {@link #MAXIMUM_MILLIS}: on a first run the backdrop is being fetched, and
 * closing on the minimum meant that a slow line – a phone hotspot was the case that found this –
 * got the whole animation and not one tile of map. When the cache holds no coarse tiles and
 * nothing can be fetched it degrades to the frame, the gradient and the same sweep, which still
 * looks deliberate.
 */
public final class Splash {

    private static final Logger log = LoggerFactory.getLogger(Splash.class);

    /** 16:9, and small enough not to dominate a laptop screen. */
    private static final int WIDTH = 800;
    private static final int HEIGHT = 450;

    /**
     * How long the tiles take to appear, one after another in scattered order, washed out.
     * <p>
     * <b>Paced, not as fast as they load.</b> From the cache all sixty-four are read in a few
     * milliseconds, and drawing each the moment it was read made the world appear at once. So
     * loading and showing are two things: every tile loads as soon as it can, exactly as before,
     * and is drawn when its turn comes. A tile still being fetched after its turn – a first run on
     * a slow line – is drawn the moment it lands.
     */
    private static final long TILES_MILLIS = 2_000;

    /**
     * When the scan heads set off – before the last tiles are in, on purpose. Waiting for all of
     * them made two separate acts of it; half a second of overlap makes it one, and a tile that
     * lands behind a head simply appears in color.
     */
    private static final long SWEEP_START_MILLIS = 1_500;

    /** One sweep of the scan heads. Four heads, a quarter each. */
    private static final long SWEEP_MILLIS = 1_500;

    /**
     * Long enough to be seen and read: the tiles, the sweep, and a moment of the finished world.
     * Startup is usually quicker, which is the point of it.
     */
    public static final long MINIMUM_MILLIS = SWEEP_START_MILLIS + SWEEP_MILLIS + 300;

    /**
     * The longest the backdrop may hold the splash open.
     * <p>
     * A first world is sixteen tiles over whatever line the machine has, and the point of waiting
     * is that the user sees one. But a line that is refusing rather than slow must not keep the
     * application out of its own window, and the downloader's own timeouts and retries can run
     * well past a minute, so the wait has an end of its own.
     */
    public static final long MAXIMUM_MILLIS = 25_000;

    /**
     * How long a backdrop that arrived late is held on screen before the splash goes.
     * <p>
     * Without it a world that lands at the nineteenth second is drawn and then immediately
     * replaced by the main window, which is the same as not having fetched it.
     */
    private static final long LINGER_MILLIS = 1_000;

    private static final int SCANNERS = 4;

    /** 16 tiles. Enough world for 800 pixels, and small enough to ask for unprompted. */
    private static final int WORLD_ZOOM = 2;


    private static final Color INK = Color.web("#0C1A18");
    private static final Color INK_DEEP = Color.web("#060D0C");
    private static final Color BEAM = Color.web("#5BE0C4");
    private static final Color BEAM_SOFT = Color.web("#5BE0C4", 0.16);

    private final Stage stage = new Stage(StageStyle.UNDECORATED);
    /** The whole world, washed out and dim, as the tiles arrive. */
    private final Canvas mapLayer = new Canvas(WIDTH, HEIGHT);
    /** The same world in full color, uncovered band by band behind the scan heads. */
    private final Canvas colorLayer = new Canvas(WIDTH, HEIGHT);
    private final Canvas beamLayer = new Canvas(WIDTH, HEIGHT);
    private final ColorAdjust wash = new ColorAdjust();
    private final Label status = new Label();

    private final long startedAt = System.currentTimeMillis();
    private volatile boolean workDone;
    /** Read by the backdrop thread to give up on a window that has already gone. */
    private volatile boolean closed;

    /** Set before the backdrop thread starts, so the splash never closes ahead of its own decision. */
    private volatile boolean backdropPending;
    /** When the backdrop stopped changing, or 0 while it still is. */
    private volatile long backdropReadyAt;

    private @Nullable Image world;
    private @Nullable Canvas partial;
    /** The tiles as they load, by {@code y * across + x}; drawn into {@link #partial} in turn. */
    private @Nullable Image @Nullable [] loaded;
    private boolean @Nullable [] drawn;
    /** The order the tiles take their turns in – scattered, so a part-shown world is a world. */
    private @NotNull List<int[]> turns = List.of();
    private int across;
    private @Nullable AnimationTimer timer;
    private @Nullable Runnable onClosed;

    public Splash() {
        final AppInfo info = AppInfo.get();

        mapLayer.setEffect(wash);
        wash.setSaturation(-1);
        wash.setBrightness(-0.45);

        final Label name = new Label(info.name());
        name.getStyleClass().add("splash-name");
        final Label version = new Label(info.version());
        version.getStyleClass().add("splash-version");
        status.getStyleClass().add("splash-status");
        status.setText(I18n.get("splash.starting"));

        final VBox words = new VBox(1, name, version);
        words.setAlignment(Pos.BOTTOM_LEFT);

        final HBox title = new HBox(14, iconView(), words);
        title.setAlignment(Pos.BOTTOM_LEFT);

        final Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        final VBox text = new VBox(10, spacer, title, status);
        text.setPadding(new Insets(26, 30, 24, 30));
        text.setPickOnBounds(false);
        text.setAlignment(Pos.BOTTOM_LEFT);

        final StackPane root = new StackPane(mapLayer, colorLayer, beamLayer, text);
        root.getStyleClass().add("splash");
        root.setPrefSize(WIDTH, HEIGHT);

        stage.setScene(new Scene(root, WIDTH, HEIGHT));
        stage.setTitle(info.name());
        stage.setResizable(false);
        // Undecorated, but still a window with a taskbar button, and that button would show Java's
        // coffee cup until the main window replaced it. No bounds of its own: it goes in the
        // middle of the monitor the main window is about to open on
        Windows.decorate(stage);
    }

    private @NotNull javafx.scene.Node iconView() {
        try {
            final ImageView view = new ImageView(new Image(java.util.Objects.requireNonNull(
                    getClass().getResourceAsStream("icon-64.png"))));
            view.setFitWidth(52);
            view.setFitHeight(52);
            return view;
        } catch (final Exception ignored) {
            return new Region();
        }
    }

    // -------------------------------------------------------------------------------------------

    public void show() {
        show(javafx.stage.Screen.getPrimary());
    }

    /**
     * On the given monitor, in its middle.
     * <p>
     * Not {@code centerOnScreen()}, which centers across but puts the window a third of the way
     * down – a dialog's convention, and on a splash it looks like a mistake.
     */
    public void show(final javafx.stage.@NotNull Screen screen) {
        stage.show();
        final javafx.geometry.Rectangle2D v = screen.getVisualBounds();
        stage.setX(v.getMinX() + (v.getWidth() - stage.getWidth()) / 2);
        stage.setY(v.getMinY() + (v.getHeight() - stage.getHeight()) / 2);
        paintMap(0);
        paintBeams(0, false);
        timer = new AnimationTimer() {
            @Override
            public void handle(final long now) {
                tick();
            }
        };
        timer.start();
    }

    /**
     * Loads the backdrop, off the FX thread.
     * <p>
     * Sixty-four tile reads and decodes is not something to do between showing a window and
     * drawing its first frame. The splash appears immediately on its frame and gradient; the map
     * arrives a moment later and the sweep reveals whatever is there by then.
     */
    public void loadBackdrop(final @NotNull Prefs prefs) {
        // Set here rather than on the thread: between the two there is a moment in which the
        // splash would otherwise believe there is no backdrop coming and be free to close.
        backdropPending = true;
        final Thread loader = new Thread(() -> {
            try {
                final Tiles tiles = readWorld(prefs);
                if (tiles != null) {
                    // Read and decoded here, off the FX thread, all at once; shown in turn by the
                    // frame loop – see TILES_MILLIS.
                    final Image[] images = new Image[tiles.bytes().size()];
                    for (int i = 0; i < images.length; i++) {
                        final Image image = new Image(new java.io.ByteArrayInputStream(tiles.bytes().get(i)));
                        images[i] = image.isError() ? null : image;
                    }
                    Platform.runLater(() -> {
                        startPartialWorld(tiles.across());
                        for (int i = 0; i < images.length; i++) {
                            if (loaded != null && images[i] != null) {
                                loaded[i] = images[i];
                            }
                        }
                    });
                    return;
                }
                fetchWorld(prefs);
            } catch (final Exception ex) {
                log.debug("No splash backdrop: {}", ex.toString());
            } finally {
                backdropSettled();
            }
        }, "splash-backdrop");
        loader.setDaemon(true);
        loader.start();
    }

    /**
     * The backdrop is as good as it is going to get, whether that is a world, a part of one or
     * nothing at all. The splash may close once it has been on screen for {@link #LINGER_MILLIS}.
     */
    private void backdropSettled() {
        // Through the FX queue, so that the tiles and the composed world this thread has already
        // handed over are on screen before the splash calls the backdrop done. Ordering, not
        // politeness: these two fields are what the closing rule reads.
        Platform.runLater(() -> {
            backdropReadyAt = System.currentTimeMillis();
            backdropPending = false;
        });
    }

    /**
     * Nothing cached, so there is nothing to laser. Fetch a world to laser.
     * <p>
     * Sixteen tiles of zoom 2, from the first provider that needs no key, drawn one at a time as
     * they arrive – which happens to be exactly what the sweep is already doing, so a first run
     * looks like the application discovering the world rather than an empty frame.
     * <p>
     * <b>Scattered, not row by row.</b> The tiles are asked for in
     * {@link TileRange#scattered() bit-reversed Morton order}, so the first four to land are the
     * four quadrants and a fetch that is only half done still looks like a world. Row-major on a
     * slow line gives a strip of Arctic ocean and nothing else, which is indistinguishable from
     * broken.
     * <p>
     * This is the one network request the application makes without being asked, so it is kept
     * small and conditional: only when the cache holds no world at all, which is once in the life
     * of an installation, and never when {@link Prefs#SHOW_SPLASH} is off. The tiles go into the
     * cache, so the second run reads them from disk. Sixteen tiles is browsing by any tile usage
     * policy, and they are fetched two at a time with the application's own User-Agent.
     * <p>
     * A world that arrives complete is recorded in {@link Prefs#FIRST_WORLD}, which is what the
     * main window opens on when the user has not chosen a provider yet. An incomplete one is not:
     * the next run's splash finds the cache short, asks again, and only the missing tiles cost
     * anything.
     */
    private void fetchWorld(final @NotNull Prefs prefs) {
        final ProviderCatalog catalog = ProviderCatalog.load(ProviderStore.defaultFile());
        final Provider free = worldSource(catalog);
        if (free == null) {
            log.debug("No global provider without a key is available to draw a first world from");
            return;
        }
        final Provider.Theme theme = free.themes().getFirst();
        final int n = Mercator.tileCount(WORLD_ZOOM);
        say(I18n.format("splash.fetching", free.displayName()));

        final TileCache cache = new TileCache(TileCache.resolveRoot(prefs.getPath(Prefs.CACHE_DIR)));
        Platform.runLater(() -> startPartialWorld(n));

        final List<int[]> order = new TileRange(0, 0, n - 1, n - 1).scattered();
        final int total = order.size();
        int landed = 0;

        // Two at a time, which is what a viewing-only provider is given anyway, and in pairs
        // rather than all sixteen at once so that the drawing keeps up with the arriving.
        try (final TileDownloader downloader = new TileDownloader(cache, TileDownloader.POLITE_CONCURRENCY,
                AppInfo.get().userAgent())) {
            for (int from = 0; from < total; from += TileDownloader.POLITE_CONCURRENCY) {
                if (closed) {
                    return;             // the window went while we were fetching
                }
                final List<int[]> batch = order.subList(from,
                        Math.min(total, from + TileDownloader.POLITE_CONCURRENCY));
                final List<TileKey> keys = new ArrayList<>(batch.size());
                for (final int[] xy : batch) {
                    keys.add(free.key(theme.id(), ProjectModel.defaultMapLang(), WORLD_ZOOM, xy[0], xy[1]));
                }
                try {
                    downloader.download(free, keys, null, ProgressSink.IGNORE);
                } catch (final Exception ex) {
                    // One pair that would not come is not a reason to abandon the other fourteen
                    log.debug("A first world tile did not arrive: {}", ex.toString());
                }
                for (final TileKey key : keys) {
                    final byte[] data = cache.read(key);
                    if (data != null) {
                        landed++;
                        // Decoded here, as the cached world is, and not on the FX thread that
                        // is painting the sweep
                        final Image tile = new Image(new java.io.ByteArrayInputStream(data));
                        if (!tile.isError()) {
                            Platform.runLater(() -> drawIntoWorld(tile, key.x(), key.y()));
                        }
                    }
                }
                say(I18n.format("splash.fetchingCount", free.displayName(),
                        I18n.integer(landed), I18n.integer(total)));
            }
        } catch (final Exception ex) {
            // A first run with no network still has a splash; it just has no map on it.
            log.debug("Could not fetch a first world: {}", ex.toString());
        }

        if (landed == total) {
            // What the main window opens on, so that a first run shows the world it just fetched
            // rather than a provider whose cache is empty and whose key nobody has entered.
            prefs.put(Prefs.FIRST_WORLD, free.id());
            prefs.flush();
            log.info("Fetched a {} tile world from {} for the first run", total, free.id());
        } else {
            log.info("Fetched {} of {} first world tiles from {}; leaving the rest for the next run",
                    landed, total, free.id());
        }
    }

    /**
     * The provider a first world is fetched from: global, no key, and able to serve zoom 2.
     * <p>
     * Asked of {@link Provider#isGlobal()} rather than of a shortlist of ids. Most providers that
     * need no key are <b>national</b>, and Swisstopo's zoom 2 is a white square with Switzerland
     * in the middle of it – a poor first impression of a mapping application, and the reason this
     * question exists at all. Catalog order decides between the ones that qualify, and
     * {@code providers.json} lists OpenStreetMap before Esri for that reason (D37).
     */
    static @Nullable Provider worldSource(final @NotNull ProviderCatalog catalog) {
        return catalog.visible().stream()
                .filter(Provider::isGlobal)
                .filter(p -> !p.needsKey())
                .filter(p -> p.minZoom() <= WORLD_ZOOM && p.maxZoom() >= WORLD_ZOOM)
                .findFirst()
                .orElse(null);
    }

    /** A canvas the tiles are drawn into as their turns come, which {@link #world} is taken from. */
    private void startPartialWorld(final int n) {
        across = n;
        partial = new Canvas((double) n * Mercator.TILE_SIZE, (double) n * Mercator.TILE_SIZE);
        loaded = new Image[n * n];
        drawn = new boolean[n * n];
        turns = new TileRange(0, 0, n - 1, n - 1).scattered();
    }

    /** A fetched tile has landed; it is drawn when its turn comes, or now if that has passed. */
    private void drawIntoWorld(final @NotNull Image tile, final int x, final int y) {
        if (loaded != null) {
            loaded[y * across + x] = tile;
        }
    }

    /**
     * Draws every tile whose turn has come and that has loaded, and takes a new picture of the
     * world if anything was drawn.
     * <p>
     * A tile that loads after its turn – a slow line, on a first run – is drawn the moment it
     * arrives: the schedule only ever holds tiles back, it never makes one wait for it.
     */
    private void showTiles(final long elapsed) {
        if (partial == null || loaded == null || drawn == null) {
            return;
        }
        final int due = (int) Math.ceil(turns.size() * Math.min(1, elapsed / (double) TILES_MILLIS));
        boolean changed = false;
        for (int t = 0; t < due; t++) {
            final int[] xy = turns.get(t);
            final int i = xy[1] * across + xy[0];
            if (!drawn[i] && loaded[i] != null) {
                partial.getGraphicsContext2D().drawImage(loaded[i],
                        (double) xy[0] * Mercator.TILE_SIZE, (double) xy[1] * Mercator.TILE_SIZE,
                        Mercator.TILE_SIZE, Mercator.TILE_SIZE);
                drawn[i] = true;
                changed = true;
            }
        }
        if (changed) {
            final javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
            params.setFill(Color.TRANSPARENT);
            world = partial.snapshot(params, null);
        }
    }

    /**
     * The world from the tile cache, at the deepest coarse zoom that is completely present.
     * <p>
     * Completeness matters more than detail here: half a world with gaps in it looks like a
     * fault, and at 800 pixels across, zoom 3 is already more than the screen can show.
     */
    private record Tiles(@NotNull List<byte[]> bytes, int across) { }

    private @Nullable Tiles readWorld(final @NotNull Prefs prefs) {
        final Path root = TileCache.resolveRoot(prefs.getPath(Prefs.CACHE_DIR));
        final TileCache cache = new TileCache(root);
        // The same answer the main window will reach, so that the second run reads the world the
        // first run fetched instead of looking for one under a provider nobody has chosen.
        final Provider provider = ProviderCatalog.load(ProviderStore.defaultFile())
                .find(prefs.openingProvider()).orElse(null);
        if (provider == null) {
            return null;
        }
        // On the map and in the language the window will open in, for a map lettered in one
        final String theme = ProviderChoice.theme(provider, prefs.themeOf(provider.id()), null).id();
        final String lang = ProviderChoice.language(provider, prefs.mapLangOf(provider.id()),
                ProjectModel.defaultMapLang());
        final TileKey origin = provider.key(theme, lang, 0, 0, 0);

        for (int zoom = 3; zoom >= 1; zoom--) {
            final List<byte[]> bytes = new ArrayList<>();
            final int n = Mercator.tileCount(zoom);
            boolean complete = true;
            for (int y = 0; y < n && complete; y++) {
                for (int x = 0; x < n && complete; x++) {
                    try {
                        final byte[] data = cache.read(origin.at(zoom, x, y));
                        if (data == null) {
                            complete = false;
                        } else {
                            bytes.add(data);
                        }
                    } catch (final Exception ex) {
                        complete = false;
                    }
                }
            }
            if (complete && !bytes.isEmpty()) {
                return new Tiles(bytes, n);
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------------------------

    private void tick() {
        final long elapsed = System.currentTimeMillis() - startedAt;

        // Two clocks, and they are not the same one. The reveal only ever goes forwards: once a
        // strip of map has been drawn it stays drawn. The beam loops, so that a start which
        // outlasts the first sweep keeps moving instead of freezing – and using one clock for
        // both made the whole map vanish and redraw every 2.2 seconds, which is a flicker, not
        // an animation.
        //
        // And the sweep waits for the tiles: first the world comes together, washed out, and then
        // the heads cross it and leave it in color. Its clock starts at SWEEP_START_MILLIS, a
        // little before the last tiles are in.
        final long swept = Math.max(0, elapsed - SWEEP_START_MILLIS);
        final double reveal = Math.min(1, swept / (double) SWEEP_MILLIS);
        final double beam = swept < SWEEP_MILLIS
                ? reveal : (swept % SWEEP_MILLIS) / (double) SWEEP_MILLIS;
        final boolean beamVisible = elapsed >= SWEEP_START_MILLIS
                && (swept < SWEEP_MILLIS || !workDone || backdropPending);

        showTiles(elapsed);
        paintMap(reveal);
        paintBeams(beam, beamVisible);

        if (workDone && elapsed >= MINIMUM_MILLIS && backdropIsDone(elapsed)) {
            close();
        }
    }

    /**
     * Whether the backdrop has had its turn.
     * <p>
     * Done means: nobody asked for one; or it settled – with a world, with part of one, or with
     * nothing – and has been on screen for {@link #LINGER_MILLIS}; or it has had
     * {@link #MAXIMUM_MILLIS} and the window is not waiting on it any longer.
     */
    private boolean backdropIsDone(final long elapsed) {
        if (elapsed >= MAXIMUM_MILLIS) {
            return true;
        }
        if (backdropPending) {
            return false;
        }
        final long readyAt = backdropReadyAt;
        return readyAt == 0 || System.currentTimeMillis() - readyAt >= LINGER_MILLIS;
    }

    /**
     * The map, revealed behind the scan heads and coming up to color behind them.
     * <p>
     * The wash is a {@link ColorAdjust} on the layer rather than a translucent rectangle over it,
     * so the color really is drained and restored instead of being tinted towards gray – and it
     * costs nothing, because the card does it.
     */
    private void paintMap(final double progress) {
        // The washed layer: the dark frame first, so a splash with no map at all still has its
        // gradient, then the whole world as far as it has come together
        final GraphicsContext g = mapLayer.getGraphicsContext2D();
        g.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, INK), new Stop(1, INK_DEEP)));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        final GraphicsContext c = colorLayer.getGraphicsContext2D();
        c.clearRect(0, 0, WIDTH, HEIGHT);
        if (world == null) {
            return;
        }
        // The world is square; show its middle band, which is where the land and the user are
        final double source = world.getWidth();
        final double visibleSource = source * HEIGHT / (double) WIDTH;
        final double sourceY = (source - visibleSource) / 2;
        g.drawImage(world, 0, sourceY, source, visibleSource, 0, 0, WIDTH, HEIGHT);

        // The color layer: what the heads have crossed
        final double bandWidth = WIDTH / (double) SCANNERS;
        for (int i = 0; i < SCANNERS; i++) {
            final double revealed = bandWidth * progress;
            if (revealed <= 0.5) {
                continue;
            }
            final double x = i * bandWidth;
            c.drawImage(world,
                    x / WIDTH * source, sourceY, revealed / WIDTH * source, visibleSource,
                    x, 0, revealed, HEIGHT);
        }
    }

    private void paintBeams(final double beam, final boolean beamVisible) {
        final GraphicsContext g = beamLayer.getGraphicsContext2D();
        g.clearRect(0, 0, WIDTH, HEIGHT);

        final double bandWidth = WIDTH / (double) SCANNERS;
        if (beamVisible) {
            for (int i = 0; i < SCANNERS; i++) {
                final double x = i * bandWidth + bandWidth * beam;
                g.setFill(BEAM_SOFT);
                g.fillRect(x - 9, 0, 18, HEIGHT);
                g.setStroke(BEAM);
                g.setLineWidth(1.6);
                g.strokeLine(x + 0.5, 0, x + 0.5, HEIGHT);
            }
        }
        // A scrim under the wording. The backdrop is whatever the user last looked at, so it
        // might be bright ocean or a snowfield; the text has to read over any of them.
        g.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.TRANSPARENT), new Stop(1, Color.web("#02100E", 0.82))));
        g.fillRect(0, HEIGHT * 0.58, WIDTH, HEIGHT * 0.42);

        // a frame, so the undecorated window has an edge
        g.setStroke(Color.web("#FFFFFF", 0.10));
        g.setLineWidth(1);
        g.strokeRect(0.5, 0.5, WIDTH - 1, HEIGHT - 1);
    }

    // -------------------------------------------------------------------------------------------

    /** For the unattended capture, which is the only way to look at this without a stopwatch. */
    public @NotNull Scene scene() {
        return stage.getScene();
    }

    /** Safe from any thread: the startup work reports from a background one. */
    public void say(final @NotNull String message) {
        if (Platform.isFxApplicationThread()) {
            status.setText(message);
        } else {
            Platform.runLater(() -> status.setText(message));
        }
    }

    /**
     * The work has finished. The splash closes at {@link #MINIMUM_MILLIS} or now, whichever is
     * later, and then runs {@code onClosed}.
     */
    public void finished(final @NotNull Runnable onClosed) {
        this.onClosed = onClosed;
        this.workDone = true;
    }

    private void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (timer != null) {
            timer.stop();
        }
        stage.hide();
        if (onClosed != null) {
            onClosed.run();
        }
    }

    /** For a failure path, where there is nothing to wait for. */
    public void hide() {
        closed = true;
        if (timer != null) {
            timer.stop();
        }
        stage.hide();
    }
}
