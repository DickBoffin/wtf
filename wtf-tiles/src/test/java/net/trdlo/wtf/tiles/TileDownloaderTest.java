package net.trdlo.wtf.tiles;

import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The downloader against a tile server in this process, which has land where a test puts it,
 * answers what a test queued for a path, and 404 everywhere else. Nothing here goes near the
 * network, and nothing near the real cache: every cache is a {@code @TempDir}.
 * <p>
 * Every download here also checks that its progress was told {@code finished} exactly once,
 * whichever way the call ended.
 */
class TileDownloaderTest {

    private static final int ZOOM = 10;

    private static final String USER_AGENT = TileDownloader.userAgent("WTF test", "9.8.7");

    private HttpServer server;
    private ExecutorService handlers;
    /** Where the downloads of a test that runs several at once run. */
    private final ExecutorService jobs = Executors.newVirtualThreadPerTaskExecutor();
    private String base;
    private byte[] png;

    /** A provider that allows bulk downloading, one that is for viewing only, and one that needs a key. */
    private Provider provider;
    private Provider viewingOnly;
    private Provider keyed;

    /** Every path the server was asked for, in the order it was asked. */
    private final Queue<String> asked = new ConcurrentLinkedQueue<>();

    /** The headers of every request, in the same order. */
    private final Queue<com.sun.net.httpserver.Headers> heard = new ConcurrentLinkedQueue<>();

    /** The query of every request that had one, as it arrived – escapes and all – in the same order. */
    private final Queue<String> queries = new ConcurrentLinkedQueue<>();

    /** The paths that have a tile. */
    private final Set<String> land = ConcurrentHashMap.newKeySet();

    /** Answers a test has queued for a path, one per request, before it falls back to land or sea. */
    private final Map<String, Queue<Answer>> queued = new ConcurrentHashMap<>();

    /** What a path that is neither queued nor land answers. */
    private volatile int otherwise = 404;

    /** How long the server takes over each request, so that requests overlap. */
    private volatile long slowMillis;

    /** Run as each request arrives, before it is answered. */
    private volatile Runnable onAsk = () -> { };

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger mostInFlight = new AtomicInteger();

    /** How many requests were at the server, this one included, as each arrived, in order. */
    private final Queue<Integer> company = new ConcurrentLinkedQueue<>();

    /** When each path was asked for, in {@link System#nanoTime()}, in order. */
    private final Map<String, Queue<Long>> arrivals = new ConcurrentHashMap<>();

    /** A request as it arrived: the path, {@link System#nanoTime()} and how many were at the server with it. */
    private record Visit(String path, long at, int company) { }

    private final Queue<Visit> visits = new ConcurrentLinkedQueue<>();

    /** The downloader's waits, short so that a test of the retries does not sit through them. */
    private Duration backoff = Duration.ofMillis(10);
    private Duration cap = Duration.ofMillis(50);

    private record Answer(int status, @Nullable String retryAfter, byte @Nullable [] body, long delayMillis) {
        static Answer of(final int status) {
            return new Answer(status, null, null, 0);
        }

        static Answer rateLimited(final @Nullable String retryAfter) {
            return new Answer(429, retryAfter, null, 0);
        }

        static Answer notAnImage() {
            return new Answer(200, null, "<html><body>Sorry</body></html>".getBytes(StandardCharsets.UTF_8), 0);
        }

        /** The same answer, given this much later. */
        Answer after(final long millis) {
            return new Answer(status, retryAfter, body, millis);
        }
    }

    @BeforeEach
    void start() throws IOException {
        png = png();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // The default executor answers one request at a time, which would hide every question
        // about concurrency this class asks
        handlers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(handlers);
        server.createContext("/", exchange -> {
            final String path = exchange.getRequestURI().getPath();
            asked.add(path);
            heard.add(exchange.getRequestHeaders());
            final String query = exchange.getRequestURI().getRawQuery();
            if (query != null) {
                queries.add(query);
            }
            arrivals.computeIfAbsent(path, p -> new ConcurrentLinkedQueue<>()).add(System.nanoTime());
            final long at = System.nanoTime();
            final int here = inFlight.incrementAndGet();
            company.add(here);
            visits.add(new Visit(path, at, here));
            mostInFlight.accumulateAndGet(here, Math::max);
            final Queue<Answer> mine = queued.get(path);
            final Answer a = mine == null ? null : mine.poll();
            try {
                onAsk.run();
                final long delay = slowMillis + (a == null ? 0 : a.delayMillis());
                if (delay > 0) {
                    Thread.sleep(delay);
                }
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();     // before the answer, which the next request may follow
            }
            if (a != null) {
                if (a.retryAfter() != null) {
                    exchange.getResponseHeaders().add("Retry-After", a.retryAfter());
                }
                if (a.body() != null) {
                    exchange.getResponseHeaders().add("Content-Type", "text/html");
                    exchange.sendResponseHeaders(a.status(), a.body().length);
                    exchange.getResponseBody().write(a.body());
                } else {
                    exchange.sendResponseHeaders(a.status(), -1);
                }
            } else if (land.contains(path)) {
                exchange.getResponseHeaders().add("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, png.length);
                exchange.getResponseBody().write(png);
            } else {
                exchange.sendResponseHeaders(otherwise, -1);
            }
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        provider = provider(base + "/{z}/{x}/{y}.png", null, true);
        viewingOnly = provider(base + "/{z}/{x}/{y}.png", null, false);
        keyed = provider(base + "/{z}/{x}/{y}.png?key={key}", new Provider.Auth(Provider.AuthKind.QUERY, "key"), true);
    }

    @AfterEach
    void stop() {
        jobs.shutdownNow();
        server.stop(0);
        handlers.shutdownNow();
    }

    private static Provider provider(final String template, final Provider.@Nullable Auth auth, final boolean bulk) {
        return provider("test", "Test", template, auth, bulk);
    }

    private static Provider provider(final String id, final String name, final String template,
                                     final Provider.@Nullable Auth auth, final boolean bulk) {
        return new Provider(id, name, template, auth, 0, 19, null,
                List.of(new Provider.Theme("t", "T", null, null, null, null, false, null)),
                null, "test", null, null, null, bulk, null, null, null);
    }

    private static TileKey key(final int x, final int y) {
        return new TileKey("test", "t", ZOOM, x, y);
    }

    /** {@code n} tiles in one row, which is also the order they are asked in. */
    private static List<TileKey> row(final int n) {
        final List<TileKey> keys = new ArrayList<>(n);
        for (int x = 0; x < n; x++) {
            keys.add(key(x, 0));
        }
        return keys;
    }

    private static String path(final TileKey k) {
        return "/" + k.zoom() + "/" + k.x() + "/" + k.y() + ".png";
    }

    private void land(final Collection<TileKey> keys) {
        keys.forEach(k -> land.add(path(k)));
    }

    private void answer(final TileKey k, final Answer... answers) {
        queued.computeIfAbsent(path(k), p -> new ConcurrentLinkedQueue<>()).addAll(List.of(answers));
    }

    /**
     * Holds the answers to the first {@code n} requests until all of them are at the server, so
     * that they go together as a job's first round does, and none is answered before the last
     * is sent.
     */
    private void firstRoundTogether(final int n) {
        final CountDownLatch round = new CountDownLatch(n);
        onAsk = () -> {
            round.countDown();
            try {
                if (!round.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the first " + n + " requests never went together");
                }
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        };
    }

    private long askedFor(final TileKey k) {
        return asked.stream().filter(path(k)::equals).count();
    }

    private static byte[] png() throws IOException {
        return png(1);
    }

    private static byte[] png(final int pixels) throws IOException {
        final var img = new java.awt.image.BufferedImage(pixels, pixels, java.awt.image.BufferedImage.TYPE_INT_RGB);
        final var out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** Counts what the downloader says, and cancels when it is told to. */
    private static final class Recorder implements ProgressSink {
        /**
         * How many looks since its download phase began make a job one that is waiting. A worker
         * waiting – for the provider's room, or for its quiet – looks whether it is canceled every
         * 100 ms; one sending a request, or done, does not look at all, and a job that is not
         * waiting looks a few times a tile.
         */
        static final int WAITING = 10;

        final AtomicInteger finished = new AtomicInteger();
        final AtomicLong advanced = new AtomicLong();
        /** How many times the downloader looked whether it was canceled. */
        final AtomicInteger looks = new AtomicInteger();
        /** Counted down once the downloader has looked {@link #WAITING} times since its phase began. */
        final CountDownLatch waiting = new CountDownLatch(1);
        volatile @Nullable String closing;
        private volatile boolean canceled;
        /** How many looks there had been when the download phase began, or -1 before it. */
        private volatile int lookedBeforePhase = -1;
        private final long cancelAfter;
        private final int cancelAtLook;

        Recorder() {
            this(Long.MAX_VALUE);
        }

        /** Cancels once this many tiles are done. */
        Recorder(final long cancelAfter) {
            this(cancelAfter, Integer.MAX_VALUE);
        }

        private Recorder(final long cancelAfter, final int cancelAtLook) {
            this.cancelAfter = cancelAfter;
            this.cancelAtLook = cancelAtLook;
        }

        /** Cancels at the downloader's {@code n}th look whether it is canceled, before any tile is done. */
        static Recorder cancelingAtLook(final int n) {
            return new Recorder(Long.MAX_VALUE, n);
        }

        void cancel() {
            canceled = true;
        }

        @Override
        public void phase(final @NotNull String name, final long total, final @NotNull Unit unit) {
            lookedBeforePhase = looks.get();
        }

        @Override
        public void advance(final long delta) {
            if (advanced.addAndGet(delta) >= cancelAfter) {
                canceled = true;
            }
        }

        @Override
        public void note(final @NotNull String detail) {
        }

        @Override
        public boolean isCanceled() {
            final int look = looks.incrementAndGet();
            if (look >= cancelAtLook) {
                canceled = true;
            }
            final int before = lookedBeforePhase;
            if (before >= 0 && look - before >= WAITING) {
                waiting.countDown();
            }
            return canceled;
        }

        @Override
        public void finished(final @NotNull String summary) {
            closing = summary;
            finished.incrementAndGet();
        }
    }

    private TileDownloader.Report download(final TileCache cache, final TileKey... keys) throws IOException {
        return download(cache, provider, 2, null, new Recorder(), List.of(keys));
    }

    private TileDownloader.Report download(final TileCache cache, final Provider p, final int concurrency,
                                           final @Nullable String apiKey, final Recorder progress,
                                           final List<TileKey> keys) throws IOException {
        try (final TileDownloader downloader = new TileDownloader(cache, concurrency, USER_AGENT, backoff, cap)) {
            return downloader.download(p, keys, apiKey, progress);
        } finally {
            assertEquals(1, progress.finished.get(), "finished, once, whichever way the call ended");
        }
    }

    // -------------------------------------------------------------------------------------------
    // @2x tiles, proven by their pixels

    /**
     * A @2x address answered with a tile of the usual size – Esri's server ignores the suffix – is
     * not a @2x tile: it is not cached as one, the map is remembered as having none, and no further
     * @2x tile of it is asked for.
     */
    @Test
    @DisplayName("a @2x tile is kept only at 512 px, and a map that answers @2x at the usual size is not asked again")
    void retinaIsProvenByItsPixels(@TempDir final Path dir) throws IOException {
        final Provider retina = provider(base + "/{z}/{x}/{y}{scale}.png", null, true);
        final TileCache cache = new TileCache(dir);
        final TileKey sharp = key(0, 0).atScale(2);
        final String sharpPath = "/" + ZOOM + "/0/0@2x.png";
        queued.put(sharpPath, new ConcurrentLinkedQueue<>(List.of(new Answer(200, null, png(512), 0))));
        assertEquals(1, download(cache, retina, 2, null, new Recorder(), List.of(sharp)).downloaded());
        assertNotNull(cache.find(sharp), "512 px: a @2x tile");

        final TileKey ignored = key(1, 0).atScale(2);
        queued.put("/" + ZOOM + "/1/0@2x.png", new ConcurrentLinkedQueue<>(List.of(new Answer(200, null, png(256), 0))));
        final TileDownloader.Report r = download(cache, retina, 2, null, new Recorder(), List.of(ignored));
        assertEquals(0, r.downloaded());
        assertEquals(List.of(ignored), r.failedKeys());
        assertNull(cache.find(ignored), "a 256 px answer is not cached as a @2x tile");
        assertTrue(cache.knowsNoRetina("test", "t"));

        asked.clear();
        download(cache, retina, 2, null, new Recorder(), List.of(key(2, 0).atScale(2)));
        assertEquals(List.of(), new ArrayList<>(asked), "the map is not asked for @2x again");
        cache.forgetEmpty("test", "t");
        assertFalse(cache.knowsNoRetina("test", "t"), "and forgetting its answers forgets that too");
    }

    @Test
    @DisplayName("a tile's width is read from its header, without decoding it")
    void widthFromTheHeader() throws IOException {
        assertEquals(512, TileFormat.widthOf(png(512)));
        assertEquals(256, TileFormat.widthOf(png(256)));
        final var out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(300, 20,
                java.awt.image.BufferedImage.TYPE_INT_RGB), "jpeg", out);
        assertEquals(300, TileFormat.widthOf(out.toByteArray()));
        assertEquals(-1, TileFormat.widthOf("<html>".getBytes(StandardCharsets.UTF_8)));
        assertEquals(-1, TileFormat.widthOf(Arrays.copyOf(png(512), 13)), "too short to say");
    }

    // -------------------------------------------------------------------------------------------
    // Sea, and a job that came to nothing

    @Test
    @DisplayName("a tile the provider has said does not exist is not asked for again")
    void knownEmptyIsNotAskedAgain(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final TileKey sea = key(0, 0);
        final TileKey coast = key(1, 0);
        cache.recordEmpty(sea);
        land.add(path(coast));

        final TileDownloader.Report r = download(cache, sea, coast);

        assertEquals(List.of(path(coast)), new ArrayList<>(asked), "only the tile nobody has answered for");
        assertEquals(1, r.downloaded());
        assertEquals(1, r.empty(), "the known sea is still counted, so the report adds up to the job");
    }

    @Test
    @DisplayName("a tile stored after the inventory is not requested, nor one found not to exist")
    void storedAfterTheInventoryIsNotAsked(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final TileKey fetched = key(0, 0);
        final TileKey previewed = key(1, 0);
        final TileKey sea = key(2, 0);
        land(List.of(fetched, previewed));
        final TileCache.Inventory inventory = cache.inventory(List.of(fetched, previewed, sea));
        assertEquals(3, inventory.missing().size());
        // Meanwhile the 1:1 preview fetches one tile, and the view's download learns of the sea
        cache.store(previewed, png(), null);
        cache.recordEmpty(sea);

        final Recorder progress = new Recorder();
        final TileDownloader.Report r;
        try (final TileDownloader downloader = new TileDownloader(cache, 1, USER_AGENT, backoff, cap)) {
            r = downloader.download(provider, inventory, null, progress);
        }

        assertEquals(List.of(path(fetched)), new ArrayList<>(asked), "only the tile still missing");
        assertEquals(1, r.downloaded());
        assertEquals(1, r.fromCache(), "counted as a tile already cached, not fetched and not failed");
        assertEquals(1, r.empty());
        assertEquals(0, r.failed());
        assertEquals(3, progress.advanced.get(), "the progress still reaches the whole job");
        assertEquals(1, progress.finished.get());
    }

    @Test
    @DisplayName("a job whose every tile answers 404 fails, and the answers are kept")
    void entirelyEmptyFails(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final TileKey a = key(0, 0);
        final TileKey b = key(1, 0);

        final NothingThereException ex =
                assertThrows(NothingThereException.class, () -> download(cache, a, b));
        assertEquals(2, ex.tiles());
        assertEquals("Test", ex.provider().displayName());
        assertTrue(cache.isKnownEmpty(a) && cache.isKnownEmpty(b),
                "real sea is not asked for twice; a wrong template is what forgetting is for");

        // A second run asks nothing – it already knows both answers – but that is not a second
        // success either: nothing real was ever here, and a script that ran this again deserves the
        // same refusal, not a silent exit 0 now that the sea is on record.
        asked.clear();
        final NothingThereException again =
                assertThrows(NothingThereException.class, () -> download(cache, a, b));
        assertTrue(asked.isEmpty(), "nothing is asked the second time either");
        assertEquals(2, again.tiles(), "still nothing here, not a silent success");
    }

    @Test
    @DisplayName("an answer the cache will not record stops the job, and nothing says it was recorded")
    void anAnswerNotRecordedIsNotClaimed(@TempDir final Path dir) throws IOException {
        // A cache whose root is a file: every tile answers 404, and no marker can be written
        final TileCache cache = new TileCache(Files.createFile(dir.resolve("not-a-directory")));
        final List<TileKey> keys = row(10);
        final Recorder progress = new Recorder();

        final IOException ex = assertThrows(IOException.class,
                () -> download(cache, provider, 1, null, progress, keys));

        assertFalse(ex instanceof NothingThereException,
                "not the refusal that says the answers are recorded, since none of them is");
        assertTrue(ex.getMessage().startsWith("The tile cache would not record that " + keys.getFirst()
                + " does not exist"), ex.getMessage());
        assertNotNull(ex.getCause(), "the cache's own reason travels with it");
        assertEquals(1, asked.size(), "nothing is asked after it, as after a tile the cache would not take");
        assertFalse(cache.isKnownEmpty(keys.getFirst()));
        assertTrue(progress.closing.contains("0 empty, 1 failed"), progress.closing);
    }

    @Test
    @DisplayName("a job refused before its first request closes with nothing of its own, not \"already cached\"")
    void refusedBeforeAskingClosesWithNothing(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final TileKey a = key(0, 0);
        final TileKey b = key(1, 0);
        cache.recordEmpty(a);
        cache.recordEmpty(b);

        final Recorder rerun = new Recorder();
        assertThrows(NothingThereException.class,
                () -> download(cache, provider, 2, null, rerun, List.of(a, b)));
        assertEquals("", rerun.closing, "all of it sea, which the refusal says");

        final Recorder noKey = new Recorder();
        assertThrows(TileDownloader.MissingCredentialsException.class,
                () -> download(cache, keyed, 1, null, noKey, row(3)));
        assertEquals("", noKey.closing, "a report of a job that never asked would be all zeros");
        assertTrue(asked.isEmpty());
    }

    @Test
    @DisplayName("a rerun over land and sea says how many do not exist; only a wholly cached one is all cached")
    void closingLineOfARealRerun(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final TileKey landed = key(0, 0);
        final TileKey sea = key(1, 0);
        cache.store(landed, png(), null);
        cache.recordEmpty(sea);

        final Recorder progress = new Recorder();
        download(cache, provider, 2, null, progress, List.of(landed, sea));
        assertTrue(progress.closing.contains("1 already cached, 1 empty"), progress.closing);

        final Recorder cachedOnly = new Recorder();
        download(cache, provider, 2, null, cachedOnly, List.of(landed));
        assertEquals("Everything was already cached (1 tiles)", cachedOnly.closing);
    }

    @Test
    @DisplayName("a 404 beside a cached tile is sea, not a job that came to nothing")
    void seaBesideCachedLand(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final TileKey sea = key(0, 0);
        final TileKey cached = key(1, 0);
        cache.store(cached, png(), null);

        final TileDownloader.Report r = download(cache, sea, cached);

        assertEquals(List.of(path(sea)), new ArrayList<>(asked));
        assertEquals(1, r.fromCache());
        assertEquals(1, r.empty());
        assertTrue(cache.isKnownEmpty(sea));
    }

    @Test
    @DisplayName("a 404 beside a tile that arrives is sea")
    void seaBesideFetchedLand(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final TileKey sea = key(0, 0);
        final TileKey coast = key(1, 0);
        land.add(path(coast));

        final TileDownloader.Report r = download(cache, sea, coast);

        assertEquals(1, r.downloaded());
        assertEquals(1, r.empty());
        assertEquals(0, r.failed());
        assertTrue(cache.contains(coast));
    }

    @Test
    @DisplayName("a 204 is sea, as a 404 is")
    void noContentIsSea(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final TileKey sea = key(0, 0);
        final TileKey coast = key(1, 0);
        answer(sea, Answer.of(204));
        land.add(path(coast));

        final TileDownloader.Report r = download(cache, sea, coast);

        assertEquals(1, r.downloaded());
        assertEquals(1, r.empty());
        assertTrue(cache.isKnownEmpty(sea));
    }

    // -------------------------------------------------------------------------------------------
    // Every other answer

    @Test
    @DisplayName("a 200 that is not an image fails the tile, and nothing is kept of it")
    void notAnImage(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final TileKey k = key(0, 0);
        answer(k, Answer.notAnImage());

        final TileDownloader.Report r = download(cache, k);

        assertEquals(List.of(k), r.failedKeys());
        assertEquals(new TileDownloader.Failure(200, null, false, false), r.failure(), "asking again changes nothing");
        assertEquals(1, askedFor(k), "an answer that is wrong is not a server in trouble: it is not asked again");
        assertFalse(cache.contains(k));
        assertFalse(cache.isKnownEmpty(k));
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {401, 403})
    @DisplayName("a rejected key stops the job, is not asked again, and says what arrived before it")
    void rejectedKey(final int status, @TempDir final Path dir) {
        final TileCache cache = new TileCache(dir);
        final List<TileKey> keys = row(20);
        land(keys.subList(0, 10));
        answer(keys.get(10), Answer.of(status));
        land(keys.subList(10, 20));       // would arrive, if anybody asked
        final Recorder progress = new Recorder();

        final TileDownloader.MissingCredentialsException ex = assertThrows(
                TileDownloader.MissingCredentialsException.class,
                () -> download(cache, keyed, 1, "secret", progress, keys));

        assertEquals(status, ex.statusCode());
        assertEquals("test", ex.providerId());
        assertEquals(10, ex.report().downloaded(), "the tiles before the rejection are in the report");
        assertEquals(11, asked.size(), "the rejected tile once, and nothing after it");
        assertEquals(ex.report().summary(), progress.closing, "the closing line is the report the exception carries");
        assertTrue(cache.contains(keys.get(9)));
    }

    @Test
    @DisplayName("a rejected key stops every worker, and nothing waiting for its turn is sent")
    void rejectedKeyStopsEveryWorker(@TempDir final Path dir) {
        final TileCache cache = new TileCache(dir);
        otherwise = 401;

        assertThrows(TileDownloader.MissingCredentialsException.class,
                () -> download(cache, keyed, 6, "secret", new Recorder(), row(500)));

        assertTrue(asked.size() <= 6, "one request per worker at most, not one per tile: " + asked.size());
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {401, 403})
    @DisplayName("a keyless provider answered 401 or 403 is not \"the API key is wrong\"")
    void rejectedWithoutAKey(final int status, @TempDir final Path dir) {
        final TileCache cache = new TileCache(dir);
        final List<TileKey> keys = row(20);
        land(keys.subList(0, 10));
        answer(keys.get(10), Answer.of(status));
        land(keys.subList(10, 20));       // would arrive, if anybody asked
        final Recorder progress = new Recorder();

        final Refusal ex = assertThrows(Refusal.class,
                () -> download(cache, provider, 1, null, progress, keys));

        assertEquals(DownloadReason.REJECTED_WITHOUT_KEY, ex.reason());
        assertEquals("127.0.0.1", ex.args().get(0), "names the host that answered");
        assertEquals(status, ex.args().get(1), "names the status");
        assertEquals(11, asked.size(), "the refused tile once, and nothing after it");
        assertTrue(progress.closing.startsWith("10 downloaded"), "finished is still told what arrived before it: "
                + progress.closing);
        assertTrue(cache.contains(keys.get(9)));
    }

    @Test
    @DisplayName("a keyless provider refusing every worker stops every worker, same as a rejected key")
    void rejectedWithoutAKeyStopsEveryWorker(@TempDir final Path dir) {
        final TileCache cache = new TileCache(dir);
        otherwise = 403;

        assertThrows(Refusal.class, () -> download(cache, provider, 6, null, new Recorder(), row(500)));

        assertTrue(asked.size() <= 6, "one request per worker at most, not one per tile: " + asked.size());
    }

    @Test
    @DisplayName("a 429 is asked again after its Retry-After, in seconds or as an HTTP date")
    void rateLimitedThenServed(@TempDir final Path dir) {
        final TileCache cache = new TileCache(dir);
        final TileKey inSeconds = key(0, 0);
        final TileKey byDate = key(1, 0);
        land(List.of(inSeconds, byDate));
        answer(inSeconds, Answer.rateLimited("0"));
        answer(byDate, Answer.rateLimited(DateTimeFormatter.RFC_1123_DATE_TIME.format(
                Instant.now().minusSeconds(5).atZone(ZoneOffset.UTC))));
        // A Retry-After that was not understood would wait the backoff instead, and take the timeout
        backoff = Duration.ofSeconds(30);
        cap = Duration.ofSeconds(60);

        final TileDownloader.Report r = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> download(cache, inSeconds, byDate));

        assertEquals(2, r.downloaded());
        assertEquals(4, asked.size());
    }

    @Test
    @DisplayName("a Retry-After longer than a job waits ends the job, what is left fails, and the report says until when")
    void retryAfterPastTheCapEndsTheJob(@TempDir final Path dir) {
        final TileCache cache = new TileCache(dir);
        final List<TileKey> keys = row(3);
        land(keys);
        answer(keys.get(0), Answer.rateLimited("3600"));
        final Recorder progress = new Recorder();
        final Instant before = Instant.now();

        final TileDownloader.Report r = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> download(cache, provider, 1, null, progress, keys));

        assertEquals(1, asked.size(), "asking again before the hour is what the server asked not to happen");
        assertEquals(keys, r.failedKeys(), "a job its limit cut short is not whole, and says so");
        assertEquals(0, r.downloaded());
        assertTheHour(r, before);
        assertEquals(r.summary(), progress.closing, "and the closing line says it too");
    }

    /** That a report says the server's rate limit ended it, until the hour it asked for. */
    private static void assertTheHour(final TileDownloader.Report r, final Instant before) {
        final TileDownloader.RateLimited limited = r.rateLimited();
        assertNotNull(limited, "the report says what ended the job: " + r.summary());
        final Instant until = Objects.requireNonNull(limited.until(), "and until when, since the server said");
        assertFalse(until.isBefore(before.plusSeconds(3600 - 1)), until::toString);
        assertFalse(until.isAfter(Instant.now().plusSeconds(3600 + 1)), until::toString);
        assertTrue(r.summary().endsWith(String.format(Locale.ROOT, TileDownloader.FINISHED_RATE_LIMITED_UNTIL,
                until.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))), r::summary);
    }

    @Test
    @DisplayName("a job's fifth 429 fails the tile it answered, and the rest of the job")
    void rateLimitedFiveTimes(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final List<TileKey> keys = row(2);
        land(keys);      // what a sixth request would get, and the other tile
        for (int i = 0; i < 5; i++) {
            answer(keys.get(0), Answer.rateLimited("0"));
        }

        final TileDownloader.Report r = download(cache, provider, 1, null, new Recorder(), keys);

        assertEquals(keys, r.failedKeys());
        assertEquals(5, asked.size(), "five for the first tile, and none for the second");
    }

    @Test
    @DisplayName("under a limit that lasts, six workers end the job at its fifth refusal, not a tile's")
    void aLastingLimitEndsTheJobAtItsFifth429(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final List<TileKey> keys = row(40);
        otherwise = 429;     // every request, for as long as the job asks
        // Each worker holds a tile when the first round's 429s come back – the case in which each
        // tile's own five attempts let the job send up to thirty
        firstRoundTogether(6);

        final TileDownloader.Report r = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> download(cache, provider, 6, null, new Recorder(), keys));

        // in the order the workers gave them up, which is not the order they were taken in
        assertEquals(Set.copyOf(keys), Set.copyOf(r.failedKeys()), "a job its limit cut short is not whole");
        assertEquals(keys.size(), r.failedKeys().size());
        assertEquals(0, r.downloaded());
        // The first round's six 429s are one refusal, said six times; after it the job asks one
        // request at a time, and the fourth of those is its fifth refusal
        assertEquals(6 + 4, asked.size(), "the first round, and four more one at a time: " + asked);
        assertEquals(new TileDownloader.RateLimited(null), r.rateLimited(), "a limit that named no time");
        assertTrue(r.summary().endsWith(TileDownloader.FINISHED_RATE_LIMITED), r::summary);
        assertEquals(new TileDownloader.Failure(429, null, true, false), r.failure(), "a limit is over some time");
    }

    @Test
    @DisplayName("a first round refused all at once is one refusal, which the job waits out")
    void aBurstLimitIsWaitedOut(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final List<TileKey> keys = row(12);
        land(keys);
        // A burst limit: the six requests that go together are refused, and then it has passed
        for (final TileKey k : keys.subList(0, 6)) {
            answer(k, Answer.rateLimited(null));
        }
        firstRoundTogether(6);

        final TileDownloader.Report r = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> download(cache, provider, 6, null, new Recorder(), keys));

        assertEquals(12, r.downloaded(), "six 429s at once used to be more than the five a job has");
        assertEquals(List.of(), r.failedKeys());
        assertEquals(12 + 6, asked.size(), "each tile once, and the six refused ones again");
    }

    @Test
    @DisplayName("after a 429 nothing is asked before the quiet time, and then one request at a time")
    void oneWorkerAfterA429(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final List<TileKey> keys = row(40);
        land(keys);
        // All four workers' first requests are refused, so all four hold a tile through the quiet
        for (int i = 0; i < 4; i++) {
            answer(keys.get(i), Answer.rateLimited("1"));
        }
        slowMillis = 20;
        cap = Duration.ofSeconds(5);

        final TileDownloader.Report r = download(cache, provider, 4, null, new Recorder(), keys);

        assertEquals(40, r.downloaded());
        final List<Visit> all = new ArrayList<>(visits);
        assertEquals(44, all.size(), "each tile once, and the four refused ones again: " + all);
        final long lastRefusedAt = all.subList(0, 4).stream().mapToLong(Visit::at).max().orElseThrow();
        // The four are at the server within milliseconds of each other. Every request after them
        // is the server's second later and alone, not four workers holding a tile waking together
        // at the end of the quiet time to ask at once – and each of them is looked at, since the
        // last twenty alone would miss exactly that
        final List<Visit> after = all.subList(4, all.size());
        for (final Visit v : after) {
            final long later = TimeUnit.NANOSECONDS.toMillis(v.at() - lastRefusedAt);
            assertTrue(later >= 1000, "asked " + later + " ms after a 429 that asked for a second: " + v);
            assertEquals(1, v.company(), "alone at the server: " + v + " in " + after);
        }
    }

    @Test
    @DisplayName("a Retry-After holds back the whole job, not only the tile it answered")
    void retryAfterHoldsTheWholeJob(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final TileKey limited = key(0, 0);
        final TileKey troubled = key(1, 0);
        land(List.of(limited, troubled));
        answer(limited, Answer.rateLimited("1"));
        // Answered after the 429, and retried by the worker that holds it, which no throttle stops
        answer(troubled, Answer.of(503).after(300));
        cap = Duration.ofSeconds(5);

        final TileDownloader.Report r = download(cache, provider, 2, null, new Recorder(), List.of(limited, troubled));

        assertEquals(2, r.downloaded());
        final long limitedAt = Objects.requireNonNull(arrivals.get(path(limited)).peek());
        final List<Long> troubledAt = new ArrayList<>(arrivals.get(path(troubled)));
        assertEquals(2, troubledAt.size());
        final long waited = TimeUnit.NANOSECONDS.toMillis(troubledAt.get(1) - limitedAt);
        assertTrue(waited >= 900, "the other tile's retry waited for the server's second: " + waited + " ms");
    }

    @Test
    @DisplayName("a cache that will not take a tile stops the job, and nothing is asked for twice")
    void unwritableCacheStopsTheJob(@TempDir final Path dir) throws IOException {
        // A cache whose root is a file: every tile arrives, and none can be written
        final TileCache cache = new TileCache(Files.createFile(dir.resolve("not-a-directory")));
        final List<TileKey> keys = row(10);
        land(keys);

        final IOException ex = assertThrows(IOException.class,
                () -> download(cache, provider, 1, null, new Recorder(), keys));

        assertEquals(1, asked.size(), "one tile, asked once, where each of the ten used to be asked three times");
        assertTrue(ex.getMessage().startsWith("The tile cache would not take " + keys.getFirst()), ex.getMessage());
        assertNotNull(ex.getCause(), "the cache's own reason travels with it");
    }

    @Test
    @DisplayName("a server error is asked twice more, then the tile fails and the job carries on")
    void serverErrorThreeTimes(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final TileKey troubled = key(0, 0);
        final TileKey fine = key(1, 0);
        land(List.of(troubled, fine));
        answer(troubled, Answer.of(503), Answer.of(500), Answer.of(503));

        final TileDownloader.Report r = download(cache, troubled, fine);

        assertEquals(List.of(troubled), r.failedKeys());
        assertEquals(3, askedFor(troubled));
        assertEquals(1, r.downloaded());
        assertTrue(cache.contains(fine));
        assertEquals(new TileDownloader.Failure(503, null, true, false), r.failure(),
                "the last answer, and worth asking again");
        assertTrue(r.retryable());
    }

    @Test
    @DisplayName("a 429 and a 5xx each have a budget of their own")
    void separateBudgets(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final TileKey k = key(0, 0);
        land.add(path(k));
        answer(k, Answer.rateLimited("0"), Answer.rateLimited("0"), Answer.rateLimited("0"),
                Answer.rateLimited("0"), Answer.of(503), Answer.of(503));

        final TileDownloader.Report r = download(cache, k);

        assertEquals(1, r.downloaded(), "four 429s leave the two retries a 503 has");
        assertEquals(7, asked.size());
    }

    @Test
    @DisplayName("any other answer fails the tile at once")
    void otherStatusFailsAtOnce(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final TileKey k = key(0, 0);
        answer(k, Answer.of(400));
        land.add(path(k));

        final TileDownloader.Report r = download(cache, k);

        assertEquals(List.of(k), r.failedKeys());
        assertEquals(1, asked.size());
        assertEquals(new TileDownloader.Failure(400, null, false, false), r.failure());
        assertFalse(r.retryable());
    }

    @Test
    @DisplayName("a server that cannot be reached fails the tiles, not the job")
    void unreachable(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final int closed;
        try (final ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = s.getLocalPort();
        }
        final Provider nowhere = provider("http://127.0.0.1:" + closed + "/{z}/{x}/{y}.png", null, true);

        final TileDownloader.Report r = download(cache, nowhere, 2, null, new Recorder(), row(2));

        assertEquals(2, r.failed());
        assertEquals(0, r.downloaded());
        final TileDownloader.Failure f = Objects.requireNonNull(r.failure(), "why they failed");
        assertEquals(0, f.status(), "nothing answered");
        assertNotNull(f.message(), "what the connection said");
        assertTrue(f.retryable(), "a server not up now may be up later");
        assertFalse(f.local(), "the request was made, and went nowhere");
    }

    @Test
    @DisplayName("a job with nothing failed says no failure")
    void nothingFailedNoFailure(@TempDir final Path dir) throws IOException {
        final TileKey k = key(0, 0);
        land.add(path(k));

        final TileDownloader.Report r = download(new TileCache(dir), k);

        assertEquals(1, r.downloaded());
        assertNull(r.failure());
        assertFalse(r.retryable());
    }

    // -------------------------------------------------------------------------------------------
    // Refused before the first request

    @ParameterizedTest(name = "key \"{0}\"")
    @NullSource
    @ValueSource(strings = {"", "  "})
    @DisplayName("a provider that needs a key and has none is refused before anything is asked")
    void noKeyNoRequest(final String apiKey, @TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final TileKey cached = key(0, 0);
        final TileKey missing = key(1, 0);
        cache.store(cached, png(), null);
        land.add(path(missing));

        final TileDownloader.MissingCredentialsException ex = assertThrows(
                TileDownloader.MissingCredentialsException.class,
                () -> download(cache, keyed, 2, apiKey, new Recorder(), List.of(cached, missing)));

        assertEquals(0, ex.statusCode(), "nobody answered anything");
        assertTrue(asked.isEmpty());
        assertEquals(1, ex.report().fromCache());
    }

    @Test
    @DisplayName("a job with nothing to fetch needs no key")
    void nothingToFetchNeedsNoKey(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final TileKey cached = key(0, 0);
        cache.store(cached, png(), null);

        final TileDownloader.Report r = download(cache, keyed, 2, null, new Recorder(), List.of(cached));

        assertEquals(1, r.fromCache());
        assertTrue(asked.isEmpty());
    }

    @Test
    @DisplayName("a viewing-only provider refuses a bulk job before anything is asked")
    void bulkRefused(@TempDir final Path dir) {
        final TileCache cache = new TileCache(dir);

        assertThrows(TileDownloader.BulkDownloadRefusedException.class, () -> download(cache, viewingOnly, 2,
                null, new Recorder(), row(TileDownloader.BROWSE_LIMIT + 1)));

        assertTrue(asked.isEmpty());
    }

    // -------------------------------------------------------------------------------------------
    // How the key is sent

    /** What the server heard in one header, on the one request a test made for one tile of land. */
    private String sentWith(final Provider.Auth auth, final String apiKey, final String header,
                            final Path dir) throws IOException {
        final TileKey k = key(0, 0);
        land.add(path(k));
        download(new TileCache(dir), provider(base + "/{z}/{x}/{y}.png", auth, true), 2, apiKey,
                new Recorder(), List.of(k));
        assertEquals(1, heard.size(), "one request");
        return Objects.requireNonNull(heard.peek()).getFirst(header);
    }

    @Test
    @DisplayName("every request says who is asking, in the version it was given, with a contact")
    void userAgent(@TempDir final Path dir) throws IOException {
        final String sent = sentWith(new Provider.Auth(Provider.AuthKind.HEADER, "X-Key"), "k", "User-Agent", dir);
        assertEquals("WTF-test/9.8.7 (+" + TileDownloader.CONTACT + ")", sent);
    }

    @Test
    @DisplayName("a header carries the key the way the provider's value says, as Stadia wants it")
    void headerValue(@TempDir final Path dir) throws IOException {
        assertEquals("Stadia-Auth k", sentWith(new Provider.Auth(Provider.AuthKind.HEADER,
                "Authorization", "Stadia-Auth {key}"), "k", "Authorization", dir));
    }

    /** Authorization, of all headers: it gets no provider's prefix unless its definition gives one. */
    @Test
    @DisplayName("a header without a value carries the key alone, whatever the header is called")
    void headerAlone(@TempDir final Path dir) throws IOException {
        assertEquals("k", sentWith(new Provider.Auth(Provider.AuthKind.HEADER, "Authorization"),
                "k", "Authorization", dir));
    }

    @Test
    @DisplayName("basic authentication sends the user and the password, encoded as RFC 7617 says")
    void basic(@TempDir final Path dir) throws IOException {
        assertEquals("Basic dXNlcjpwdw==", sentWith(new Provider.Auth(Provider.AuthKind.BASIC, null),
                "user:pw", "Authorization", dir));
    }

    /**
     * A key in the address is one value of it, whatever is in it: put in as it was, its space
     * made the address no address at all, its {@code &} ended it and began another parameter, its
     * {@code +} reached the server as a space and its {@code #} ended the address there.
     */
    @Test
    @DisplayName("a key with a space and an & reaches the server encoded, and is read there as the key it was")
    void aKeyInTheAddressReachesTheServerEncoded(@TempDir final Path dir) throws IOException {
        final TileKey k = key(0, 0);
        land.add(path(k));
        final String apiKey = "pa ss&word+1#ü";

        final TileDownloader.Report r = download(new TileCache(dir), keyed, 2, apiKey, new Recorder(), List.of(k));

        assertEquals(1, r.downloaded(), "asked once, and the tile came back");
        assertEquals(List.of("key=pa%20ss%26word%2B1%23%C3%BC"), List.copyOf(queries), "the key, encoded");
        assertEquals(Map.of("key", apiKey), parameters(Objects.requireNonNull(queries.peek())),
                "one parameter, which a server reads as the key it was given");
    }

    /** A query as a server reads it: its parameters, each decoded as a form's is. */
    private static Map<String, String> parameters(final String rawQuery) {
        final Map<String, String> read = new LinkedHashMap<>();
        for (final String pair : rawQuery.split("&")) {
            final int eq = pair.indexOf('=');
            read.put(java.net.URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8),
                    eq < 0 ? "" : java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return read;
    }

    // -------------------------------------------------------------------------------------------
    // A request that cannot be made

    /**
     * A template that, filled in, is no address – a space in its query, put there by hand – one
     * whose host is no name the client connects to, and a key a header cannot carry, pasted with
     * a zero-width space in it. Each fails its tile here, with nothing asked of anybody, and says
     * why; and neither what the report says nor any line of the log holds the address the tile
     * would have been asked at, or the key. The client's own words for each held one or the
     * other, and they were what the report and the log said.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"address", "host", "header"})
    @DisplayName("a request that cannot be made fails its tile, saying why without the address in the failure or the log")
    void aRequestThatCannotBeMadeSaysWhyWithoutTheAddress(final String what, @TempDir final Path dir)
            throws IOException {
        final Provider.Auth inQuery = new Provider.Auth(Provider.AuthKind.QUERY, "key");
        final Provider p = switch (what) {
            case "address" -> provider(base + "/{z}/{x}/{y}.png?style=two words&key={key}", inQuery, true);
            case "host" -> provider("http://tiles_a.example.invalid/{z}/{x}/{y}.png?key={key}", inQuery, true);
            default -> provider(base + "/{z}/{x}/{y}.png", new Provider.Auth(Provider.AuthKind.HEADER, "X-Key"), true);
        };
        final String apiKey = what.equals("header") ? "SECRET\u200bkey" : "SECRET";
        final String why = switch (what) {
            case "address" -> String.format(Locale.ROOT, TileDownloader.UNMADE_ADDRESS, "Illegal character in query");
            case "host" -> String.format(Locale.ROOT, TileDownloader.UNMADE_ADDRESS, "Illegal character in hostname");
            default -> TileDownloader.UNMADE_KEY;
        };
        final TileKey k = key(0, 0);
        land.add(path(k));

        final TileDownloader.Report r;
        final List<String> logged;
        try (final Logged log = new Logged()) {
            r = download(new TileCache(dir), p, 2, apiKey, new Recorder(), List.of(k));
            logged = log.lines();
        }

        assertTrue(asked.isEmpty(), "nothing was asked");
        assertEquals(List.of(k), r.failedKeys());
        assertEquals(new TileDownloader.Failure(0, why, false, true), r.failure(),
                "failed here, saying why, and not worth asking again");
        assertTrue(logged.stream().anyMatch(line -> line.contains(why)), () -> "the log says why: " + logged);
        final String address = p.url(k, apiKey);
        final List<String> said = new ArrayList<>(logged);
        said.add(Objects.requireNonNull(r.failure()).message());
        for (final String line : said) {
            assertFalse(line.contains(address), () -> "the address the tile would have been asked at: " + line);
            assertFalse(line.contains("SECRET"), () -> "the key: " + line);
        }
    }

    /**
     * Every line the tiles module logs while this is open, at every level – DEBUG among them, which
     * {@code wtf.log} has when it is asked for – with the message of each exception logged with
     * one; kept in a list, not printed into the build's log, and the logger put back on close.
     */
    private static final class Logged implements AutoCloseable {
        private final ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("net.trdlo.wtf.tiles");
        private final ch.qos.logback.classic.Level level = logger.getLevel();
        private final boolean additive = logger.isAdditive();
        private final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();

        Logged() {
            appender.start();
            logger.addAppender(appender);
            logger.setLevel(ch.qos.logback.classic.Level.ALL);
            logger.setAdditive(false);
        }

        List<String> lines() {
            final List<String> lines = new ArrayList<>();
            for (final ch.qos.logback.classic.spi.ILoggingEvent e : List.copyOf(appender.list)) {
                lines.add(e.getFormattedMessage());
                for (var t = e.getThrowableProxy(); t != null; t = t.getCause()) {
                    lines.add(t.getClassName() + ": " + t.getMessage());
                }
            }
            return lines;
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            logger.setLevel(level);
            logger.setAdditive(additive);
        }
    }

    // -------------------------------------------------------------------------------------------
    // Redirects

    /**
     * The headers of the request for one tile that the provider's server redirects – under
     * {@code /moved}, to the same path at {@code to} – as they arrived where it was sent.
     * {@code to} is either this test's server or {@code elsewhere}, on a port of its own.
     */
    private com.sun.net.httpserver.Headers redirectedWith(final Provider.Auth auth, final String apiKey,
                                                          final boolean elsewhere, final Path dir)
            throws IOException {
        final TileKey k = key(0, 0);
        land.add(path(k));
        final Queue<com.sun.net.httpserver.Headers> there = new ConcurrentLinkedQueue<>();
        final HttpServer other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        other.createContext("/", exchange -> {
            there.add(exchange.getRequestHeaders());
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, png.length);
            exchange.getResponseBody().write(png);
            exchange.close();
        });
        other.start();
        final String to = elsewhere ? "http://127.0.0.1:" + other.getAddress().getPort() : base;
        server.createContext("/moved", exchange -> {
            exchange.getResponseHeaders().add("Location",
                    to + exchange.getRequestURI().getPath().substring("/moved".length()));
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        try {
            final TileCache cache = new TileCache(dir);
            final TileDownloader.Report r = download(cache, provider(base + "/moved/{z}/{x}/{y}.png", auth, true),
                    2, apiKey, new Recorder(), List.of(k));
            assertEquals(1, r.downloaded(), "the tile arrived where it was sent");
        } finally {
            other.stop(0);
        }
        final Queue<com.sun.net.httpserver.Headers> arrived = elsewhere ? there : heard;
        assertEquals(1, arrived.size(), "one request where it was sent");
        return Objects.requireNonNull(arrived.peek());
    }

    /** The JDK's client drops its own Authorization on a change of origin, and sent X-Key on. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"X-Key", "Authorization", "basic"})
    @DisplayName("a tile redirected to another origin is asked there without the key, whatever carries it")
    void redirectElsewhereDropsTheKey(final String carrier, @TempDir final Path dir) throws IOException {
        final Provider.Auth auth = carrier.equals("basic") ? new Provider.Auth(Provider.AuthKind.BASIC, null)
                : new Provider.Auth(Provider.AuthKind.HEADER, carrier);
        final String header = carrier.equals("basic") ? "Authorization" : carrier;

        final com.sun.net.httpserver.Headers there = redirectedWith(auth, "user:pw", true, dir);

        assertNull(there.getFirst(header), "the key stayed with the provider's host");
        assertEquals(USER_AGENT, there.getFirst("User-Agent"), "and the rest of the request went");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"X-Key", "Authorization"})
    @DisplayName("a tile redirected within its origin keeps the key")
    void redirectWithinKeepsTheKey(final String header, @TempDir final Path dir) throws IOException {
        final com.sun.net.httpserver.Headers there =
                redirectedWith(new Provider.Auth(Provider.AuthKind.HEADER, header), "k", false, dir);

        assertEquals("k", there.getFirst(header));
    }

    @Test
    @DisplayName("one origin is one scheme, host and port, with a default port spelled or not")
    void origins() {
        final java.net.URI a = java.net.URI.create("https://Tiles.example.com/1/2/3.png");
        assertTrue(TileDownloader.sameOrigin(a, java.net.URI.create("https://tiles.example.com:443/x")));
        assertFalse(TileDownloader.sameOrigin(a, java.net.URI.create("http://tiles.example.com/1/2/3.png")));
        assertFalse(TileDownloader.sameOrigin(a, java.net.URI.create("https://tiles.example.com:8443/1/2/3.png")));
        assertFalse(TileDownloader.sameOrigin(a, java.net.URI.create("https://cdn.example.com/1/2/3.png")));
    }

    // -------------------------------------------------------------------------------------------
    // Cancel

    @Test
    @DisplayName("a cancel stops the job between tiles and returns what arrived")
    void cancelBetweenTiles(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final List<TileKey> keys = row(200);
        land(keys);
        final Recorder progress = new Recorder(5);

        final TileDownloader.Report r = download(cache, provider, 2, null, progress, keys);

        assertTrue(asked.size() <= 5 + 1, "the five, and at most the one the other worker had in flight: "
                + asked.size());
        assertEquals(progress.advanced.get(), r.downloaded(), "whatever arrived is counted");
        assertEquals(0, r.failed(), "what was not asked has not failed");
    }

    /**
     * The look through the cache before anything is asked – a listing of each zoom folder and a
     * lookup of each tile – is a job's first minute on a print of tens of thousands of tiles, and a
     * cancel stops it there rather than letting it go on to the end and then ask.
     */
    @ParameterizedTest(name = "at look {0}")
    @ValueSource(ints = {1, 50})
    @DisplayName("a cancel while the cache is looked through stops the look, and nothing is asked")
    void cancelDuringThePreScan(final int look, @TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final List<TileKey> keys = row(200);    // one folder, which the look lists
        land(keys);
        final Recorder progress = Recorder.cancelingAtLook(look);

        final TileDownloader.Report r = download(cache, provider, 2, null, progress, keys);

        assertTrue(asked.isEmpty(), "nothing asked: " + asked.size());
        assertEquals(look, progress.looks.get(), "the look ended at the cancel, not after 200 tiles");
        assertEquals(0, r.downloaded());
        assertEquals(0, r.failed(), "what was not asked has not failed");
        assertFalse(String.valueOf(progress.closing).startsWith("Everything was already cached"),
                progress.closing);
    }

    @Test
    @DisplayName("sea in a listed folder is not asked for again, and is counted")
    void knownEmptyInAListedFolder(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final List<TileKey> keys = row(100);     // past the listing threshold, in one folder
        final List<TileKey> sea = keys.subList(0, 80);
        for (final TileKey k : sea) {
            cache.recordEmpty(k);
        }
        final List<TileKey> coast = keys.subList(80, 100);
        cache.store(coast.getFirst(), png(), null);
        land(coast);

        final TileDownloader.Report r = download(cache, provider, 2, null, new Recorder(), keys);

        assertEquals(19, asked.size(), "only the tiles nobody has answered for");
        assertTrue(sea.stream().noneMatch(k -> askedFor(k) > 0));
        assertEquals(80, r.empty());
        assertEquals(1, r.fromCache());
        assertEquals(19, r.downloaded());
    }

    @Test
    @DisplayName("an inventory handed over is the job's, and the cache is not looked through again")
    void anInventoryHandedOverIsNotMadeAgain(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir.resolve("cache"));
        final List<TileKey> keys = row(100);
        for (final TileKey k : keys) {
            cache.store(k, png(), null);
        }
        final TileCache.Inventory inv = cache.inventory(keys);
        // Gone after the inventory: a downloader that looked again would find nothing and ask
        deleteTree(cache.root());
        final Recorder progress = new Recorder();

        final TileDownloader.Report r;
        try (final TileDownloader downloader = new TileDownloader(cache, 2, USER_AGENT, backoff, cap)) {
            r = downloader.download(provider, inv, null, progress);
        }

        assertTrue(asked.isEmpty());
        assertEquals(100, r.fromCache());
        assertEquals("Everything was already cached (100 tiles)", progress.closing);
    }

    @Test
    @DisplayName("an inventory handed over that is all known sea is still nothing there")
    void anInventoryOfSeaIsNothingThere(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final List<TileKey> keys = row(3);
        for (final TileKey k : keys) {
            cache.recordEmpty(k);
        }
        final TileCache.Inventory inv = cache.inventory(keys);

        try (final TileDownloader downloader = new TileDownloader(cache, 2, USER_AGENT, backoff, cap)) {
            final NothingThereException ex = assertThrows(NothingThereException.class,
                    () -> downloader.download(provider, inv, null, new Recorder()));
            assertEquals(3, ex.tiles());
        }
        assertTrue(asked.isEmpty());
    }

    private static void deleteTree(final Path root) throws IOException {
        try (final var walk = Files.walk(root)) {
            for (final Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    @Test
    @DisplayName("a cancel ends a wait for a Retry-After")
    void cancelDuringWait(@TempDir final Path dir) {
        final TileCache cache = new TileCache(dir);
        final TileKey k = key(0, 0);
        land.add(path(k));
        answer(k, Answer.rateLimited("30"));
        cap = Duration.ofSeconds(60);
        final Recorder progress = new Recorder();
        onAsk = () -> CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS).execute(progress::cancel);

        final TileDownloader.Report r = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> download(cache, provider, 1, null, progress, List.of(k)));

        assertEquals(1, asked.size());
        assertEquals(0, r.failed(), "a tile left by a cancel has not failed");
        assertEquals(0, r.downloaded());
    }

    // -------------------------------------------------------------------------------------------
    // Concurrency and order

    @Test
    @DisplayName("never more requests at once than there are workers")
    void concurrencyIsBounded(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final List<TileKey> keys = row(24);
        land(keys);
        slowMillis = 40;

        final TileDownloader.Report r = download(cache, provider, 3, null, new Recorder(), keys);

        assertEquals(24, r.downloaded());
        assertTrue(mostInFlight.get() <= 3, "at most three at once: " + mostInFlight.get());
        assertTrue(mostInFlight.get() > 1, "and in parallel: " + mostInFlight.get());
    }

    @Test
    @DisplayName("a viewing-only provider is asked two at a time, whatever the setting")
    void viewingOnlyIsAskedPolitely(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final List<TileKey> keys = row(24);
        land(keys);
        slowMillis = 40;

        download(cache, viewingOnly, 6, null, new Recorder(), keys);

        assertTrue(mostInFlight.get() <= TileDownloader.POLITE_CONCURRENCY, "at most two: " + mostInFlight.get());
    }

    @Test
    @DisplayName("tiles are asked coarse zoom first, then row by row")
    void order(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final List<TileKey> keys = new ArrayList<>();
        for (final int zoom : new int[] {ZOOM, ZOOM - 1}) {
            for (int y = 0; y < 3; y++) {
                for (int x = 0; x < 3; x++) {
                    keys.add(new TileKey("test", "t", zoom, x, y));
                }
            }
        }
        Collections.shuffle(keys, new Random(23));
        land(keys);

        download(cache, provider, 1, null, new Recorder(), keys);

        final List<String> expected = keys.stream()
                .sorted(Comparator.comparingInt(TileKey::zoom).thenComparingInt(TileKey::y)
                        .thenComparingInt(TileKey::x))
                .map(TileDownloaderTest::path).toList();
        assertEquals(expected, new ArrayList<>(asked));
    }

    // -------------------------------------------------------------------------------------------
    // What a provider allows is the process's, not a job's

    /** A download on a thread of its own, as the preview's decoders and the view's download run. */
    private record Running(@NotNull Recorder progress, @NotNull CompletableFuture<TileDownloader.Report> report) {

        /** The report, once the download is done, which told {@code finished} once. */
        TileDownloader.@NotNull Report join() throws Exception {
            final TileDownloader.Report r = report.get(10, TimeUnit.SECONDS);
            assertEquals(1, progress.finished.get(), "finished, once, whichever way the call ended");
            return r;
        }
    }

    /** {@code keys}, by a downloader of their own over {@code limits}, started now on a thread of their own. */
    private Running start(final ProviderLimits limits, final TileCache cache, final Provider p,
                          final int concurrency, final List<TileKey> keys) {
        final Recorder progress = new Recorder();
        return new Running(progress, CompletableFuture.supplyAsync(() -> {
            try (final TileDownloader downloader = new TileDownloader(cache, concurrency, USER_AGENT, limits)) {
                return downloader.download(p, keys, null, progress);
            } catch (final IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }, jobs));
    }

    /**
     * Waits until {@code job} is waiting – it has looked whether it is canceled
     * {@link Recorder#WAITING} times since its phase began – and fails as soon as it is done
     * instead, or {@code wrong} comes true meanwhile: what a job asking on its own would do.
     */
    private static void awaitWaiting(final Running job, final BooleanSupplier wrong,
                                     final Supplier<String> what) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!job.progress().waiting.await(10, TimeUnit.MILLISECONDS)) {
            assertFalse(wrong.getAsBoolean(), what);
            assertFalse(job.report().isDone(), () -> "done without waiting: " + what.get());
            assertTrue(System.nanoTime() - deadline < 0, () -> "never waited: " + what.get());
        }
    }

    /** Holds a request at the server until the test lets it go. */
    private static void hold(final CountDownLatch letGo) {
        try {
            if (!letGo.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the test never let the request go");
            }
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("two jobs asking a viewing-only provider at once hold no more than its two connections")
    void twoJobsHoldNoMoreThanTheProvidersConnections(@TempDir final Path dir) throws Exception {
        final TileCache cache = new TileCache(dir);
        final List<TileKey> keys = row(8);
        land(keys);
        // Every request is held at the server until the second job has been seen waiting
        final CountDownLatch firstTwo = new CountDownLatch(2);
        final CountDownLatch letGo = new CountDownLatch(1);
        onAsk = () -> {
            firstTwo.countDown();
            hold(letGo);
        };
        final ProviderLimits limits = new ProviderLimits(backoff, cap);

        final Running first = start(limits, cache, viewingOnly, 6, keys.subList(0, 4));
        assertTrue(firstTwo.await(10, TimeUnit.SECONDS), "each of the first job's two workers has a request out");
        // The preview's job beside the view's: the two share two connections, not two each
        final Running second = start(limits, cache, viewingOnly, 6, keys.subList(4, 8));
        awaitWaiting(second, () -> inFlight.get() > 2, () -> inFlight.get() + " at the server: " + asked);
        assertEquals(2, inFlight.get(), "the first job's two alone at the server: " + asked);

        letGo.countDown();
        assertEquals(4, first.join().downloaded());
        assertEquals(4, second.join().downloaded());
        assertEquals(TileDownloader.POLITE_CONCURRENCY, mostInFlight.get(),
                "never more than the provider's two, whichever job asked");
    }

    @Test
    @DisplayName("a 429 in one job quiets another asking the same provider, which asks nothing during it")
    void a429InOneJobQuietsTheOther(@TempDir final Path dir) throws Exception {
        final TileCache cache = new TileCache(dir);
        final TileKey refused = key(0, 0);
        final TileKey other = key(1, 0);
        land(List.of(refused, other));
        answer(refused, Answer.rateLimited("30"));
        cap = Duration.ofSeconds(60);       // so that half a minute is a quiet a job waits out
        final ProviderLimits limits = new ProviderLimits(backoff, cap);

        final Running first = start(limits, cache, provider, 2, List.of(refused));
        // Waiting out its quiet, which it can only be once its 429 is counted: it looks three
        // times up to its request, and none while the request is out
        awaitWaiting(first, () -> false, () -> "the refused job: " + asked);
        final Running second = start(limits, cache, provider, 2, List.of(other));
        awaitWaiting(second, () -> asked.size() > 1, () -> "the other job asked: " + asked);
        assertEquals(List.of(path(refused)), new ArrayList<>(asked), "nothing asked during the quiet");

        first.progress().cancel();
        second.progress().cancel();
        final TileDownloader.Report waited = second.join();
        first.join();
        assertEquals(1, asked.size(), "nor after the cancel");
        assertEquals(0, waited.downloaded());
        assertEquals(0, waited.failed(), "a tile left by a cancel has not failed");
        assertNull(waited.rateLimited(), "a quiet waited out is not a limit that outlasted the job");
    }

    @Test
    @DisplayName("under a limit that lasts, two jobs asking one provider end together at its fifth refusal")
    void twoJobsEndTogetherAtTheProvidersFifth429(@TempDir final Path dir) throws Exception {
        final TileCache cache = new TileCache(dir);
        final List<TileKey> keys = row(20);
        otherwise = 429;     // every request, for as long as anybody asks
        // Each job's three workers have a request out before any is answered, as a first round goes
        firstRoundTogether(6);
        final ProviderLimits limits = new ProviderLimits(backoff, cap);

        final Running first = start(limits, cache, provider, 3, keys.subList(0, 10));
        final Running second = start(limits, cache, provider, 3, keys.subList(10, 20));
        final TileDownloader.Report one = first.join();
        final TileDownloader.Report two = second.join();

        // in the order the workers gave them up, which is not the order they were taken in
        assertEquals(Set.copyOf(keys.subList(0, 10)), Set.copyOf(one.failedKeys()), "a job its limit cut short is not whole");
        assertEquals(Set.copyOf(keys.subList(10, 20)), Set.copyOf(two.failedKeys()));
        // The first round's six 429s are one refusal, said six times, whichever job's they were;
        // after it the provider is asked one request at a time, whichever job sends it, and the
        // fourth is its fifth refusal. Each job with a limit of its own sent seven.
        assertEquals(6 + 4, asked.size(), "the first round, and four more one at a time: " + asked);
        assertEquals(new TileDownloader.RateLimited(null), one.rateLimited(), "both say what ended them");
        assertEquals(new TileDownloader.RateLimited(null), two.rateLimited());
    }

    /**
     * The process's own limits, which every downloader of the public constructor shares – and
     * which outlast this test, so that it asks a provider of its own, at its own server.
     */
    @Test
    @DisplayName("every downloader in the process shares a provider's limit at its address, and one it holds off says why")
    void everyDownloaderSharesTheProvidersLimit(@TempDir final Path dir) throws IOException {
        final String here = base + "/{z}/{x}/{y}.png";
        final Provider shared = provider("process-test", "Process test", here, null, true);
        final TileKey refused = new TileKey(shared.id(), "t", ZOOM, 0, 0);
        final TileKey next = new TileKey(shared.id(), "t", ZOOM, 1, 0);
        final TileKey renamed = new TileKey(shared.id(), "t", ZOOM, 2, 0);
        final TileKey moved = new TileKey(shared.id(), "t", ZOOM, 3, 0);
        answer(refused, Answer.rateLimited("3600"));
        land(List.of(next, renamed));
        land.add("/elsewhere" + path(moved));
        final Instant before = Instant.now();

        final TileDownloader.Report hour = throughTheProcess(dir.resolve("first"), shared, refused, new Recorder());
        assertEquals(List.of(refused), hour.failedKeys());
        assertTheHour(hour, before);

        // Another downloader, as a Test in the Providers dialog builds one while the preview is refused
        final Recorder progress = new Recorder();
        final TileDownloader.Report heldOff = throughTheProcess(dir.resolve("second"), shared, next, progress);
        assertEquals(List.of(path(refused)), new ArrayList<>(asked), "nothing asked before the hour is up");
        assertEquals(List.of(next), heldOff.failedKeys(), "and what it did not ask for failed");
        assertTheHour(heldOff, before);
        assertEquals(heldOff.summary(), progress.closing, "its closing line says why, and until when");

        // An edit that leaves the address – another name – is the same server, and held off the same
        final TileDownloader.Report edited = throughTheProcess(dir.resolve("third"),
                provider("process-test", "Renamed", here, null, true), renamed, new Recorder());
        assertEquals(List.of(renamed), edited.failedKeys());
        assertEquals(1, asked.size());

        // Moved to another address, it is another server's to answer
        final TileDownloader.Report elsewhere = throughTheProcess(dir.resolve("fourth"),
                provider("process-test", "Process test", base + "/elsewhere/{z}/{x}/{y}.png", null, true),
                moved, new Recorder());
        assertEquals(1, elsewhere.downloaded());
        assertNull(elsewhere.rateLimited());
        assertEquals(2, asked.size());
    }

    /** One tile, through a downloader of the public constructor's, which shares the process's limits. */
    private static TileDownloader.Report throughTheProcess(final Path dir, final Provider p, final TileKey key,
                                                           final Recorder progress) throws IOException {
        try (final TileDownloader downloader = new TileDownloader(new TileCache(dir), 2, USER_AGENT)) {
            return downloader.download(p, List.of(key), null, progress);
        } finally {
            assertEquals(1, progress.finished.get(), "finished, once, whichever way the call ended");
        }
    }

    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Retry-After is read in seconds or as an HTTP date, and nothing else is")
    void retryAfterParsing() {
        final Instant now = Instant.parse("2026-10-21T07:27:30Z");
        assertEquals(Optional.of(Duration.ofSeconds(120)), TileDownloader.retryAfter(" 120 ", now));
        assertEquals(Optional.of(Duration.ofSeconds(30)),
                TileDownloader.retryAfter("Wed, 21 Oct 2026 07:28:00 GMT", now));
        assertEquals(Optional.of(Duration.ZERO), TileDownloader.retryAfter("Wed, 21 Oct 2026 07:00:00 GMT", now),
                "a date already past means now");
        for (final String nonsense : Arrays.asList("-5", "soon", "", null)) {
            assertEquals(Optional.empty(), TileDownloader.retryAfter(nonsense, now), String.valueOf(nonsense));
        }
    }
}
