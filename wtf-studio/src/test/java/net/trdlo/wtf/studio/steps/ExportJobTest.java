package net.trdlo.wtf.studio.steps;

import com.sun.net.httpserver.HttpServer;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.stage.Stage;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.studio.Dialogs;
import net.trdlo.wtf.studio.Headless;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.studio.Prefs;
import net.trdlo.wtf.studio.ViewJob;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import net.trdlo.wtf.tiles.TileCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.prefs.Preferences;
import java.util.stream.Stream;

import static net.trdlo.wtf.studio.Headless.find;
import static net.trdlo.wtf.studio.Headless.onFx;
import static net.trdlo.wtf.studio.Headless.waitUntil;
import static org.junit.jupiter.api.Assertions.*;

/**
 * One press of Export, which downloads what is missing and then exports, stops where it is told
 * to in either of its jobs. Cancel during the download ends the press there; Cancel during the
 * export the download started reaches that export; the window's close
 * ({@link ExportPanel#stopAndWait}) cancels the export and returns only once it has ended, and
 * waits for the download's tile in hand no longer than {@link ViewJob#STOP_WAIT}. While a job runs,
 * Export stays off, whatever changes meanwhile.
 * <p>
 * On JavaFX's headless platform ({@link Headless}), in batch mode, so that anything unexpected is a
 * line on stderr rather than a window nobody would close. The tiles come from a server in this
 * process, which holds a request until the test lets it answer. The export is held where it asks
 * its context for the cache, on its own thread, before it has written anything. The cache and the
 * print's folder are temporary, and a finished export opens nothing.
 */
class ExportJobTest {

    /** A handful of tiles over the Bay of Biscay at zoom 6, as {@code ExportPanelTest}'s print. */
    private static final LonLatBounds BISCAY = new LonLatBounds(-10, 46, 4, 50);

    /** What the panel names its threads: the two jobs', and its counts' ({@code count-tiles}). */
    private static final Set<String> PANEL_THREADS =
            Set.of(ExportPanel.DOWNLOAD_THREAD, ExportPanel.EXPORT_THREAD, "count-tiles");

    @TempDir
    Path dir;

    private HttpServer server;
    /** Counted down when the server has a tile request in hand. */
    private final CountDownLatch inHand = new CountDownLatch(1);
    /** What the server waits for before it answers a tile: the test letting the download go on. */
    private final CountDownLatch answer = new CountDownLatch(1);

    /** Counted down when the export, on its own thread, asks for the cache. */
    private final CountDownLatch exportArrived = new CountDownLatch(1);
    /** What the export waits for there before it goes on. */
    private final CountDownLatch exportGoesOn = new CountDownLatch(1);
    /** The download's and the export's threads, as they asked for the cache. */
    private final AtomicReference<Thread> downloading = new AtomicReference<>();
    private final AtomicReference<Thread> exporting = new AtomicReference<>();

    private final boolean batch = Dialogs.isBatchMode();
    private TileCache cache;
    private String prefsPath;
    private ProjectModel model;
    private ExportPanel panel;
    private Stage stage;
    private Button export;
    private Button cancel;

    @BeforeAll
    static void toolkit() {
        Headless.start();
    }

    @BeforeEach
    void start() throws Exception {
        final byte[] png = png();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            inHand.countDown();
            try {
                answer.await(10, TimeUnit.SECONDS);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, png.length);
            exchange.getResponseBody().write(png);
            exchange.close();
        });
        server.start();
        final Provider provider = new Provider("job-test", "Job Test",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/{z}/{x}/{y}.png", null, 0, 19, null,
                List.of(new Provider.Theme("t", "T", null, null, null, null, false, null)),
                null, "test", null, null, null, true, null, null, null, null);

        cache = new TileCache(dir.resolve("cache"));
        prefsPath = "trdlo.net/wtf-test-" + UUID.randomUUID();
        final Prefs prefs = new Prefs(prefsPath);
        prefs.put(Prefs.OPEN_AFTER_EXPORT, false);
        Dialogs.setBatchMode(true);

        final ProjectModel m = new ProjectModel();
        m.startOver(() -> {
            m.providerProperty().set(provider);
            m.themeProperty().set(provider.themes().getFirst());
            m.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
            m.customProperty().set(Shapes.rectangle(BISCAY));
            m.zoomProperty().set(6);
            m.outputDirProperty().set(dir.resolve("out"));
        });
        model = m;
        final StepContext ctx = new StepContext(null, null, prefs, ProviderCatalog::builtIn,
                this::cache, () -> false, () -> false, p -> { });
        panel = onFx(() -> new ExportPanel(m, ctx));
        stage = onFx(() -> Headless.show(new ScrollPane(panel.node()), 360, 800));
        export = onFx(() -> find(panel.node(), Button.class,
                b -> I18n.get("export.run").equals(b.getText())).orElseThrow());
        cancel = onFx(() -> find(panel.node(), Button.class,
                b -> I18n.get("button.cancel").equals(b.getText())).orElseThrow());
    }

    @AfterEach
    void stop() throws Exception {
        // Whatever a failure left held goes on and ends before the folders go: on Windows a folder
        // a thread still reads cannot be deleted. Hidden, the step starts no count of its own, so
        // the panel's threads alive after that are all there are to wait for.
        answer.countDown();
        exportGoesOn.countDown();
        try {
            final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (panel != null && onFx(panel::isRunning) && System.nanoTime() < end) {
                Thread.sleep(20);
            }
            onFx(() -> {
                if (panel != null) {
                    panel.hidden();
                }
                if (stage != null) {
                    stage.close();
                }
                return null;
            });
            for (final Thread t : Thread.getAllStackTraces().keySet()) {
                if (PANEL_THREADS.contains(t.getName())) {
                    t.join(TimeUnit.SECONDS.toMillis(30));
                }
            }
        } finally {
            Dialogs.setBatchMode(batch);
            if (server != null) {
                server.stop(0);
            }
            if (prefsPath != null) {
                Preferences.userRoot().node(prefsPath).removeNode();
            }
        }
    }

    @Test
    @DisplayName("Cancel during the download ends the press there, and Export stays off until it has")
    void cancelDuringTheDownload() throws Exception {
        press();
        assertTrue(inHand.await(10, TimeUnit.SECONDS), "the download has a tile in hand");
        onFx(() -> {
            assertTrue(panel.isRunning() && panel.isDownloading(), "the press is downloading");
            assertOffAcrossARevision();
            cancel.fire();
            assertTrue(cancel.isDisabled(), "Cancel reached the download");
            return null;
        });

        answer.countDown();
        waitUntil("the press to end", () -> !panel.isRunning());
        onFx(() -> {
            assertFalse(export.isDisabled(), "Export is back once the press has ended");
            assertTrue(find(panel.node(), Label.class,
                            l -> I18n.get("export.download.canceled").equals(l.getText())).isPresent(),
                    "the step says the download was canceled and nothing exported");
            return null;
        });
        assertNull(exporting.get(), "no export followed the canceled download");
        assertEquals(List.of(), written(), "and nothing was written");
    }

    @Test
    @DisplayName("Cancel during the export that follows the download reaches the export")
    void cancelDuringTheExport() throws Exception {
        answer.countDown();         // every tile is answered at once
        press();
        assertTrue(exportArrived.await(30, TimeUnit.SECONDS), "the download ended and started the export");
        onFx(() -> {
            assertTrue(panel.isRunning(), "the export is running");
            assertFalse(panel.isDownloading(), "and the close would say it is the export");
            assertOffAcrossARevision();
            assertTrue(cancel.isVisible() && !cancel.isDisabled(), "Cancel is offered");
            cancel.fire();
            assertTrue(cancel.isDisabled(), "Cancel reached the export");
            return null;
        });

        exportGoesOn.countDown();
        waitUntil("the export to end", () -> !panel.isRunning());
        assertFalse(onFx(export::isDisabled), "Export is back once the export has ended");
        assertNotNull(exporting.get(), "the export did run");
        assertEquals(List.of(), written(), "canceled before its first sheet, it wrote nothing");
    }

    @Test
    @DisplayName("the close's stop during the export that follows the download cancels it and waits for its end")
    void stopDuringTheExport() throws Exception {
        answer.countDown();
        press();
        assertTrue(exportArrived.await(30, TimeUnit.SECONDS), "the download ended and started the export");
        assertTrue(onFx(() -> panel.isRunning() && !panel.isDownloading()), "the export is running");

        // The export goes on only once the FX thread waits for it in stopAndWait, which cancels first
        final Thread fx = onFx(Thread::currentThread);
        final Thread letGo = new Thread(() -> {
            final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (exportGoesOn.getCount() > 0 && !waitingToStop(fx) && System.nanoTime() < end) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
            exportGoesOn.countDown();
        }, "let-the-export-go");
        letGo.setDaemon(true);
        letGo.start();
        final boolean alive = onFx(() -> {
            panel.stopAndWait();
            return exporting.get().isAlive();
        });
        assertFalse(alive, "stopAndWait returned while the export was still running");

        waitUntil("the export's landing", () -> !panel.isRunning());
        assertEquals(List.of(), written(), "canceled before its first sheet, it left nothing, no .part either");
    }

    @Test
    @DisplayName("the close's stop during the download waits for the tile in hand no longer than the bound")
    void stopDuringTheDownload() throws Exception {
        // The server holds the tile in hand until the test lets it go, after the stop has returned
        press();
        assertTrue(inHand.await(10, TimeUnit.SECONDS), "the download has a tile in hand");
        final long took = onFx(() -> {
            final long start = System.nanoTime();
            panel.stopAndWait();
            final long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(downloading.get().isAlive(), "the tile is still on its way, left to the exit");
            return ms;
        });
        assertTrue(took >= ViewJob.STOP_WAIT.toMillis() - 50, "it waited for the tile first: " + took + " ms");
        assertTrue(took < ViewJob.STOP_WAIT.toMillis() + 5_000, "and no longer than the bound: " + took + " ms");

        // The tile comes after all: the canceled download stores it and asks for nothing more, and
        // its landing starts no export
        answer.countDown();
        waitUntil("the download's landing", () -> !panel.isRunning());
        assertNull(exporting.get(), "no export followed the stopped download");
        assertEquals(List.of(), written(), "and nothing was written");
    }

    /** Shows the step and presses Export at once: it counts, finds every tile missing and downloads. */
    private void press() throws Exception {
        onFx(() -> {
            panel.shown();
            export.fire();
            return null;
        });
    }

    /**
     * An edit in another step while a job runs, which is a revision, leaves Export off. Called on
     * the FX thread.
     */
    private void assertOffAcrossARevision() {
        assertTrue(export.isDisabled(), "Export is off while a job runs");
        final int before = model.revisionProperty().get();
        model.titleProperty().set("Edited while a job runs");
        assertTrue(model.revisionProperty().get() > before, "the edit is a revision");
        assertTrue(export.isDisabled(), "Export stays off across a revision while a job runs");
    }

    /**
     * The cache, as the panel's context hands it over, asked for on the thread about to use it.
     * The export's thread waits here until the test lets it go on.
     */
    private TileCache cache() {
        final Thread asking = Thread.currentThread();
        if (ExportPanel.DOWNLOAD_THREAD.equals(asking.getName())) {
            downloading.set(asking);
        } else if (ExportPanel.EXPORT_THREAD.equals(asking.getName())) {
            exporting.set(asking);
            exportArrived.countDown();
            try {
                exportGoesOn.await(30, TimeUnit.SECONDS);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        return cache;
    }

    /**
     * Whether the FX thread waits in {@link ExportPanel#stopAndWait} for a job's thread to end,
     * which it does after the cancel. Read off its stack, since while it waits it can answer
     * nothing posted to it.
     */
    private static boolean waitingToStop(final Thread fx) {
        boolean joining = false;
        for (final StackTraceElement frame : fx.getStackTrace()) {
            if (Thread.class.getName().equals(frame.getClassName()) && "join".equals(frame.getMethodName())) {
                joining = true;
            } else if (joining && ExportPanel.class.getName().equals(frame.getClassName())
                    && "stopAndWait".equals(frame.getMethodName())) {
                return true;
            }
        }
        return false;
    }

    /** What is in the print's folder, if there is one: every file, finished, aside or in progress. */
    private List<Path> written() throws IOException {
        final Path out = dir.resolve("out");
        if (!Files.isDirectory(out)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(out)) {
            return files.toList();
        }
    }

    private static byte[] png() throws IOException {
        final var img = new java.awt.image.BufferedImage(256, 256, java.awt.image.BufferedImage.TYPE_INT_RGB);
        final var out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
