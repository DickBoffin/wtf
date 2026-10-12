package net.trdlo.wtf.studio.steps;

import com.sun.net.httpserver.HttpServer;
import net.trdlo.wtf.compose.*;
import net.trdlo.wtf.studio.ErrorText;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.tiles.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A print that is sea from edge to edge is refused alike by the export step's two presses,
 * under every missing-tile policy – the first, which counts every tile missing and downloads the
 * print, and the second, which counts every tile known empty and goes straight to the export.
 * Both end in the one {@link NothingThereException}, which the step says as the view's download
 * does, in {@link ErrorText}'s words. The first press is run as {@code ExportPanel.downloadFirst}
 * runs it, against a provider in this process that has nothing anywhere.
 */
class AllSeaPrintTest {

    /** Six tiles over the Bay of Biscay at zoom 6, as {@code KnownEmptyTilesTest} builds them. */
    private static final LonLatBounds BISCAY = new LonLatBounds(-10, 46, 4, 50);

    private HttpServer server;

    /** Every path the server was asked for. */
    private final Queue<String> asked = new ConcurrentLinkedQueue<>();

    /** A provider with no map anywhere: every tile it is asked for answers 404. */
    private Provider sea;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            asked.add(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        sea = new Provider("sea-test", "Sea Test",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/{z}/{x}/{y}.png", null, 0, 19, null,
                List.of(new Provider.Theme("t", "T", null, null, null, null, false, null)),
                null, "test", null, null, null, true, null, null, null);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** On a sheet the print's own size, so that no paper is spare and the print is its six tiles. */
    private ComposeJob job(final Path out, final MissingTilePolicy policy) {
        final PrintRect print = PrintSize.width(200).apply(BISCAY);
        return new ComposeJob(Shapes.rectangle(BISCAY), null, sea, "t", null, Regions.single(6), print,
                new Medium.CutSheet(print.widthMm(), print.heightMm(), Medium.Orientation.LANDSCAPE), 0,
                0x3366CC, policy, EnumSet.of(ComposeJob.OutputFormat.PDF),
                ComposeJob.OutputGrouping.SINGLE_DOCUMENT, out, "sea", Marks.NONE);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(MissingTilePolicy.class)
    @DisplayName("the press that downloads an all-sea print and the press that exports it are refused alike")
    void bothPressesAlike(final MissingTilePolicy policy, @TempDir final Path dir) throws IOException {
        final ComposeJob job = job(dir.resolve("out"), policy);
        final TileCache cache = new TileCache(dir.resolve("cache"));
        final TileSource tiles = CachedTileSource.cacheOnly(cache, sea);
        final ExportCount count = new ExportCount();

        // The first press: the count finds every tile missing, and the whole print goes to the
        // downloader, which asks for each tile and hears that none exists
        count.set(TilePlan.inventory(TilePlan.of(job), tiles));
        final TilePlan.Inventory before = count.get();
        assertNotNull(before);
        assertEquals(6, before.missing().size(), "nothing is known of this print yet");
        final NothingThereException downloaded;
        try (final TileDownloader downloader = new TileDownloader(cache, 2, "WTF-test/0")) {
            downloaded = assertThrows(NothingThereException.class,
                    () -> downloader.download(sea, before.wanted(), null, ProgressSink.IGNORE));
        }
        assertEquals(6, asked.size(), "every tile was asked for, once");
        count.failed(downloaded);
        assertNull(count.get(), "a refused download may have changed the cache: the next press counts again");

        // The second press: the count finds every tile known empty and none missing, so the press
        // goes straight to the export, which no policy lets through
        final TilePlan.Inventory after = TilePlan.inventory(TilePlan.of(job), tiles);
        assertTrue(after.missing().isEmpty() && after.nothingThere(), "all of it known to be sea now");
        final NothingThereException exported = assertThrows(NothingThereException.class,
                () -> new Composer().export(job, tiles, 0, ProgressSink.IGNORE));
        assertEquals(6, asked.size(), "the export asks nobody anything");
        assertFalse(Files.exists(job.outputDir()), "and writes nothing");

        // One refusal, said one way: the same provider and the same count, so the same dialog
        assertSame(downloaded.provider(), exported.provider());
        assertEquals(downloaded.tiles(), exported.tiles());
        final ErrorText.Explained first = ErrorText.explain(downloaded);
        final ErrorText.Explained second = ErrorText.explain(exported);
        assertEquals(I18n.get("download.nothing.title"), first.headline());
        assertEquals(first.headline(), second.headline());
        assertEquals(first.body(), second.body());
        assertTrue(first.body().contains("Sea Test") && first.body().contains("6"), first.body());
    }
}
