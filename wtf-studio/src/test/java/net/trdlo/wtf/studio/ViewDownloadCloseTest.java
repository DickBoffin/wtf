package net.trdlo.wtf.studio;

import com.sun.net.httpserver.HttpServer;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.TileCache;
import net.trdlo.wtf.tiles.TileDownloader;
import net.trdlo.wtf.tiles.TileKey;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A close during the download of the view stops it and waits for the tile in
 * hand, with no question and no {@code .part} left – run as {@code WtfApp}'s close request runs
 * it, {@link Closing#decide} calling {@link ViewJob#stopAndWait} on a job built as
 * {@code ViewDownload} builds it, against a server in this process and a real cache. A tile that
 * never comes holds the close up for {@link ViewJob#STOP_WAIT} and no longer.
 */
class ViewDownloadCloseTest {

    private HttpServer server;
    private Provider provider;
    private final Queue<String> asked = new ConcurrentLinkedQueue<>();
    /** Counted down when the first tile has been asked for: the download has one in hand. */
    private final CountDownLatch inHand = new CountDownLatch(1);
    /** What the server waits for before it answers the first tile: the close's cancel. */
    private final CountDownLatch answer = new CountDownLatch(1);

    @BeforeEach
    void start() throws IOException {
        final byte[] png = png();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            asked.add(exchange.getRequestURI().getPath());
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
        provider = new Provider("close-test", "Close Test",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/{z}/{x}/{y}.png", null, 0, 19, null,
                List.of(new Provider.Theme("t", "T", null, null, null, null, false, null)),
                null, "test", null, null, null, true, null, null, null, null);
    }

    @AfterEach
    void stop() {
        answer.countDown();
        server.stop(0);
    }

    /** A job built as {@code ViewDownload} builds one, over twenty tiles of which the first is held. */
    private final class Download {
        final TileCache cache;
        final List<TileKey> view = new ArrayList<>();
        final AtomicReference<TileDownloader.Report> report = new AtomicReference<>();
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        /** Counted down when the download's thread is done, whenever that is. */
        final CountDownLatch done = new CountDownLatch(1);
        final ViewJob job;

        /** @param onCancel counted down by the cancel: the server's answer, or nothing */
        Download(final Path dir, final CountDownLatch onCancel) {
            cache = new TileCache(dir);
            for (int x = 0; x < 20; x++) {
                view.add(new TileKey(provider.id(), "t", 10, x, 7));
            }
            final Canceling sink = new Canceling(onCancel);
            job = new ViewJob("download-view", () -> {
                try (final TileDownloader downloader = new TileDownloader(cache, 1, "WTF-test/0")) {
                    report.set(downloader.download(provider, view, null, sink));
                } catch (final Throwable ex) {
                    thrown.set(ex);
                } finally {
                    done.countDown();
                }
            }, sink::cancel);
            job.start();
        }

        /** The window's close, as {@code WtfApp} runs it, with nothing unsaved and no export. */
        boolean close(final AtomicBoolean askedAnything) {
            return Closing.decide(false,
                    () -> {
                        askedAnything.set(true);
                        return false;
                    },
                    () -> true,
                    () -> fail("no export is running"),
                    job::stopAndWait);
        }
    }

    private static List<Path> parts(final Path dir) throws IOException {
        try (final Stream<Path> files = Files.walk(dir)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".part")).toList();
        }
    }

    @Test
    @DisplayName("a close during a view's download stops it, waits for the tile in hand and leaves no .part")
    void closeStopsTheViewsDownload(@TempDir final Path dir) throws Exception {
        final Download d = new Download(dir, answer);
        assertTrue(inHand.await(10, TimeUnit.SECONDS), "the download has a tile in hand");

        final AtomicBoolean askedAnything = new AtomicBoolean();
        assertTrue(d.close(askedAnything));
        assertFalse(askedAnything.get(), "a view's download is not asked about");
        assertNull(d.thrown.get(), "a stop is not a failure");
        assertNotNull(d.report.get(), "the download had ended by the time the close returned");
        assertEquals(1, asked.size(), "nothing was asked after the stop: " + asked);
        assertTrue(d.cache.contains(d.view.getFirst()), "the tile in hand was stored whole");
        assertEquals(List.of(), parts(dir));
    }

    @Test
    @DisplayName("a tile that never comes holds the close up for the bound, not for the request's timeout")
    void aStalledTileDoesNotHoldTheCloseUp(@TempDir final Path dir) throws Exception {
        // The cancel releases nothing: the server holds the tile in hand until the test lets it go
        final Download d = new Download(dir, new CountDownLatch(1));
        assertTrue(inHand.await(10, TimeUnit.SECONDS), "the download has a tile in hand");

        final long start = System.nanoTime();
        assertTrue(d.close(new AtomicBoolean()), "the close goes on");
        final long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(took >= ViewJob.STOP_WAIT.toMillis() - 50, "it waited for the tile first: " + took + " ms");
        assertTrue(took < ViewJob.STOP_WAIT.toMillis() + 5_000, "and no longer than the bound: " + took + " ms");
        assertEquals(1, d.done.getCount(), "the download is still waiting for its tile, left to the exit");
        assertNull(d.report.get());

        // The tile comes after all: the canceled download stores it and asks for nothing more
        answer.countDown();
        assertTrue(d.done.await(10, TimeUnit.SECONDS));
        assertNull(d.thrown.get());
        assertEquals(1, asked.size(), "nothing was asked after the stop: " + asked);
        assertEquals(List.of(), parts(dir));
    }

    private static byte[] png() throws IOException {
        final var img = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        final var out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** A sink that is canceled as {@code FxProgress} is, and lets the server answer once it is. */
    private static final class Canceling implements ProgressSink {
        private final CountDownLatch then;
        private volatile boolean canceled;

        Canceling(final @NotNull CountDownLatch then) {
            this.then = then;
        }

        void cancel() {
            canceled = true;
            then.countDown();
        }

        @Override public void phase(final @NotNull String name, final long total, final @NotNull Unit unit) { }
        @Override public void advance(final long delta) { }
        @Override public void note(final @NotNull String detail) { }
        @Override public boolean isCanceled() { return canceled; }
        @Override public void finished(final @NotNull String summary) { }
    }
}
