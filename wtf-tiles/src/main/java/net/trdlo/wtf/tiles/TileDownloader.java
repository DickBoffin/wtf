package net.trdlo.wtf.tiles;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Fetches tiles, politely and in parallel, into a {@link TileCache}.
 * <p>
 * It keeps one {@code HttpClient} for all its jobs, never one per tile, and a fixed set of workers,
 * each taking the next tile when it is done with the last. Tiles are taken row-major within a
 * zoom and zooms ascending, so a job that is interrupted after an hour leaves a usable coarse map
 * rather than a quarter of a detailed one. It is interrupted between one tile and the next: after
 * a cancel or a rejected key, nothing more is asked.
 * <p>
 * What a provider allows is the process's, not a job's or a downloader's: its connections, and
 * after a 429 its quiet, one request at a time and its refusals, are shared by every job that
 * asks it, from whichever downloader ({@code ProviderLimits}).
 * <p>
 * <b>Credentials are never logged.</b> Only {@link Provider#redactedTemplate()} is: never a resolved
 * address, which holds a key sent in it, and never the message of what refused to make a request,
 * which quotes the address or the header it refused.
 */
public final class TileDownloader implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TileDownloader.class);

    /** Polite to a tile server, and enough to saturate a domestic line. */
    public static final int DEFAULT_CONCURRENCY = 6;

    /**
     * How many tiles a viewing-only provider will serve in one go.
     * <p>
     * Providers like OpenStreetMap run on donated hardware and their usage policies forbid bulk
     * downloading while explicitly allowing ordinary browsing. A screenful is browsing; an export
     * of forty thousand tiles is not. This is where the line is drawn, and 256 is comfortably
     * more than a 4K window shows at once.
     */
    public static final int BROWSE_LIMIT = 256;

    /**
     * And they ask for very few parallel connections, so a viewing-only provider gets two – from
     * the whole process, whichever jobs ask it.
     */
    public static final int POLITE_CONCURRENCY = 2;

    private static final int MAX_ATTEMPTS_SERVER_ERROR = 3;
    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);
    /**
     * The longest wait between two attempts. A server whose {@code Retry-After} asks for longer is
     * not asked again before that time, by any job.
     */
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /** How often a wait looks up to see whether the job still wants it. */
    static final long STOP_CHECK_MILLIS = 100;
    /** How many redirects one request follows, as many as the JDK's client follows by default. */
    private static final int MAX_REDIRECTS = 5;

    /**
     * What each provider allows this process, which every downloader the public constructor builds
     * shares: the view's download, the export's, the 1:1 preview's, the splash's and the Tests of
     * the two provider dialogs alike.
     */
    private static final ProviderLimits PROCESS = new ProviderLimits(FIRST_BACKOFF, MAX_BACKOFF);

    private final TileCache cache;
    private final HttpClient http;
    private final int concurrency;
    private final String userAgent;
    private final ProviderLimits limits;
    private final Duration firstBackoff;
    private final Duration maxWait;

    /**
     * Where somebody running a tile server can reach the people behind WTF, for the
     * {@code User-Agent}. OpenStreetMap's tile usage policy asks for one, and the provider that
     * makes the application usable out of the box must not be the one that gets it blocked.
     */
    public static final String CONTACT = "https://github.com/DickBoffin/wtf/issues";

    /**
     * A downloader that shares what each provider allows with every other one in the process.
     *
     * @param userAgent who is asking, as {@link #userAgent(String, String)} writes it: the
     *                  application's name and version, which only the application knows
     */
    public TileDownloader(final @NotNull TileCache cache, final int concurrency, final @NotNull String userAgent) {
        this(cache, concurrency, userAgent, PROCESS);
    }

    /**
     * For tests: waits short enough that a test of the retries does not sit through them, and
     * limits of its own, so that no test's 429 quiets another test's provider.
     */
    TileDownloader(final @NotNull TileCache cache, final int concurrency, final @NotNull String userAgent,
                   final @NotNull Duration firstBackoff, final @NotNull Duration maxWait) {
        this(cache, concurrency, userAgent, new ProviderLimits(firstBackoff, maxWait));
    }

    /** For tests of jobs that share what a provider allows: the downloader waits as its limits do. */
    TileDownloader(final @NotNull TileCache cache, final int concurrency, final @NotNull String userAgent,
                   final @NotNull ProviderLimits limits) {
        this.cache = cache;
        this.concurrency = Math.max(1, concurrency);
        this.limits = limits;
        this.firstBackoff = limits.firstBackoff();
        this.maxWait = limits.maxWait();
        this.userAgent = userAgent;
        // Redirects are followed here, not by the client, which on a change of origin drops its
        // own Authorization and sends every other header on – a provider's key among them
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * A {@code User-Agent} as the tile usage policies want one: the application, its version and
     * a contact. {@code WTF/0.1.0 (+https://github.com/DickBoffin/wtf/issues)}.
     */
    public static @NotNull String userAgent(final @NotNull String product, final @NotNull String version) {
        return product.replace(' ', '-') + "/" + version + " (+" + CONTACT + ")";
    }

    /**
     * Raised when the provider rejected the credential, or when it needs one and none was given.
     * The GUI turns this into the settings hand-off.
     * <p>
     * It carries the report of what the job did before it stopped: the tiles that arrived are in
     * the cache all the same, and whoever says how far the job got should not have to guess.
     */
    public static final class MissingCredentialsException extends IOException {
        private final String providerId;
        private final int statusCode;
        private final transient Report report;

        public MissingCredentialsException(final @NotNull String providerId, final int statusCode,
                                           final @NotNull Report report) {
            super(statusCode == 0
                    ? "Provider '" + providerId + "' needs an API key and none was given, so nothing was requested"
                    : "Provider '" + providerId + "' rejected the request with HTTP " + statusCode
                            + " – the API key is missing, wrong or out of allowance");
            this.providerId = providerId;
            this.statusCode = statusCode;
            this.report = report;
        }

        public @NotNull String providerId() {
            return providerId;
        }

        /** What the provider answered, or 0 when the job was refused before its first request. */
        public int statusCode() {
            return statusCode;
        }

        /** What the job did before it stopped. */
        public @NotNull Report report() {
            return report;
        }
    }

    /**
     * Raised before a single request when a job is larger than a viewing-only provider allows.
     * <p>
     * The message is written to be shown to a person, because it will be: this is a policy, not
     * a fault, and the only useful response is to choose another provider.
     */
    public static final class BulkDownloadRefusedException extends IOException {
        private final transient Provider provider;
        private final int tiles;

        public BulkDownloadRefusedException(final @NotNull Provider p, final int tiles) {
            super(p.displayName() + " does not allow bulk downloading, and this asks for "
                    + String.format(Locale.ROOT, "%,d", tiles) + " tiles at once. Up to "
                    + BROWSE_LIMIT + " at a time is fine - that is browsing. "
                    + (p.quotaNote() == null ? "" : p.quotaNote())
                    + " For a print this size, use a provider that permits it.");
            this.provider = p;
            this.tiles = tiles;
        }

        public @NotNull Provider provider() {
            return provider;
        }

        public int tiles() {
            return tiles;
        }
    }

    /**
     * What this tells a {@link ProgressSink}, as constants so that an interface in another language
     * can recognize them and say them its own way. The engine has no language of its own.
     */
    public static final String PHASE_DOWNLOADING = "Downloading tiles";
    public static final String FINISHED_ALL_CACHED = "Everything was already cached (%d tiles)";
    /** The closing line of a job that asked for something: {@link Report#summary()}. */
    public static final String FINISHED_REPORT =
            "%,d downloaded, %,d already cached, %,d empty, %,d failed, %,.1f MB in %s";
    /** How the closing line ends when the server's rate limit outlasted the job, naming no time. */
    public static final String FINISHED_RATE_LIMITED = "; rate limited, nothing more asked";
    /** How it ends when the server named the time: an ISO-8601 instant, to the second. */
    public static final String FINISHED_RATE_LIMITED_UNTIL = "; rate limited until %s, nothing more asked";

    /**
     * Why no request could be made for a tile, as its {@linkplain Failure#local() local} failure
     * says it: the template, filled in, is not an address a request can go to, and the parser's
     * reason is why – never the address itself, which holds a key sent in it.
     */
    public static final String UNMADE_ADDRESS = "The address is not valid (%s)";
    /** Or the key has a character the header it goes in cannot carry; said without the key. */
    public static final String UNMADE_KEY = "The key has a character that a request header cannot carry";

    /**
     * What a job did.
     *
     * @param rateLimited why nothing more was asked, when the server's rate limit outlasted the
     *                    job and its tiles not yet fetched failed; null when it did not
     * @param failure     why a tile failed – the last one worth asking for again, else the last –
     *                    or null when none failed for a reason worth saying
     */
    public record Report(int downloaded, int fromCache, int empty, int failed,
                         long bytes, @NotNull Duration elapsed, @NotNull List<TileKey> failedKeys,
                         @Nullable RateLimited rateLimited, @Nullable Failure failure) {

        /** Whether asking for the failed tiles again may get them: the server was in trouble, not saying no. */
        public boolean retryable() {
            return failure != null && failure.retryable();
        }

        /** The job in one line: its counts, and why nothing more was asked when a rate limit said so. */
        public @NotNull String summary() {
            final String counts = String.format(Locale.ROOT, FINISHED_REPORT,
                    downloaded, fromCache, empty, failed, bytes / 1_048_576.0, human(elapsed));
            if (rateLimited == null) {
                return counts;
            }
            final Instant until = rateLimited.until();
            return counts + (until == null ? FINISHED_RATE_LIMITED
                    : String.format(Locale.ROOT, FINISHED_RATE_LIMITED_UNTIL, until.truncatedTo(ChronoUnit.SECONDS)));
        }

        private static String human(final Duration d) {
            final long s = d.getSeconds();
            return s < 60 ? s + " s" : (s / 60) + " min " + (s % 60) + " s";
        }
    }

    /**
     * The server's rate limit, when it outlasted what a job waits: the jobs asking the provider
     * were refused a fifth time, or asked to wait longer than a job waits, and asked nothing more;
     * a job that began before that quiet was over asked nothing at all. Every tile the job had not
     * fetched failed.
     *
     * @param until the time before which the server asked for nothing, if it named one
     */
    public record RateLimited(@Nullable Instant until) {
    }

    /**
     * Why a tile did not arrive, after the attempts its kind of trouble allows.
     *
     * @param status    what the server answered, or 0 when nothing answered or nothing was asked
     * @param message   what the connection said when nothing answered, or why the tile failed here;
     *                  null when the server answered. Never the address or a header, either of
     *                  which may hold the key.
     * @param retryable true for a server in trouble – a 5xx, no answer, a rate limit – which may
     *                  answer another time; false for an answer that will not change, such as a
     *                  400 or a 200 that is not an image, and for a failure here
     * @param local     true when the tile failed here, and no server heard of it: no request could
     *                  be made – {@link #UNMADE_ADDRESS}, {@link #UNMADE_KEY} – or WTF itself
     *                  failed, which the message names by the kind of exception
     */
    public record Failure(int status, @Nullable String message, boolean retryable, boolean local) {
    }

    /**
     * Fetches everything in {@code keys} that is neither cached nor known not to exist.
     * <p>
     * Pass the whole job – the view, the print – rather than only what is missing from it: whether
     * a job came to nothing is judged over all of it, and a coastal print whose last missing tiles
     * are all sea has not.
     * <p>
     * A cancel is seen while the cache is looked through for what it has, which comes before
     * anything is asked and ends with nothing asked; between one tile and the next; and during a
     * wait before a retry: the requests in flight finish, nothing more is asked, and the report
     * says what arrived. Every way out – a report, a refusal, a failure – tells {@code progress}
     * it is {@code finished}, once.
     * <p>
     * A 429 is taken as meant for everybody asking the provider, from whichever downloader: from
     * then on one request is in flight at a time, whichever job sends it, and nothing is asked by
     * anyone before the time the server gave. A server that asks for longer than a job waits, or
     * refuses the jobs asking it a fifth time, whichever tiles it refused, is not asked again by
     * them: the tiles in hand and every one not yet fetched are reported failed, and the report
     * says why ({@link Report#rateLimited()}). A job that starts before that quiet is over asks
     * nothing, and its report says the same. A 429 to a request sent before the provider's last
     * 429 came back is that refusal again, not another: the first round of six requests refused
     * together is one refusal, which the jobs wait out. A viewing-only provider is asked at most
     * {@link #POLITE_CONCURRENCY} tiles at once by the whole process.
     *
     * @param apiKey the credential, or null for a provider that needs none
     * @throws MissingCredentialsException when the provider needs a key and none was given, before
     *         anything is asked; or when it rejected the key, after which nothing more is asked
     * @throws Refusal with {@link DownloadReason#REJECTED_WITHOUT_KEY} when a provider that needs no
     *         key is answered 401 or 403 anyway – blocked or banned, not a wrong credential – after
     *         which nothing more is asked
     * @throws BulkDownloadRefusedException when a viewing-only provider is asked for more than
     *         {@link #BROWSE_LIMIT} tiles, before anything is asked
     * @throws NothingThereException when every tile asked for answered 204 or 404 and none was
     *         cached, whether that was found out just now or already known from an earlier call –
     *         and every answer is recorded
     * @throws IOException when the cache would not take a tile, or would not record that one does
     *         not exist, after which nothing more is asked
     */
    public @NotNull Report download(final @NotNull Provider provider,
                                    final @NotNull Collection<TileKey> keys,
                                    final @Nullable String apiKey,
                                    final @NotNull ProgressSink progress) throws IOException {
        // The listing an inventory makes, not five questions of the disk a tile
        return download(provider, apiKey, progress, job -> cache.inventory(keys, job::canceled));
    }

    /**
     * {@link #download(Provider, Collection, String, ProgressSink)} of a job whose inventory the
     * caller has just made of this downloader's cache – the command line, which says the numbers
     * before it asks – so that the cache is looked through once, not twice. It is the whole job
     * all the same: its cached and empty tiles are what judge whether it came to nothing.
     */
    public @NotNull Report download(final @NotNull Provider provider,
                                    final TileCache.@NotNull Inventory inventory,
                                    final @Nullable String apiKey,
                                    final @NotNull ProgressSink progress) throws IOException {
        return download(provider, apiKey, progress, job -> inventory);
    }

    private @NotNull Report download(final @NotNull Provider provider, final @Nullable String apiKey,
                                     final @NotNull ProgressSink progress,
                                     final @NotNull Function<Job, TileCache.Inventory> scan) throws IOException {
        final Job job = new Job(provider, apiKey, progress);
        boolean returned = false;
        try {
            final Report report = job.run(scan);
            returned = true;
            return report;
        } finally {
            // A rejected key and a cancel included: a caller showing progress would otherwise be
            // left with a bar that never ends. A job refused before its first request has no report
            // to give, and the refusal says why.
            final String closing = returned || job.started ? job.closingLine() : "";
            if (job.started) {
                log.info("Download finished: {}", closing);
            }
            progress.finished(closing);
        }
    }

    /** CACHED and KNOWN_EMPTY: found in the cache after the job's inventory, and not asked for. */
    private enum Kind { STORED, EMPTY, CACHED, KNOWN_EMPTY, FAILED, CREDENTIALS, REFUSED, STOPPED }

    /**
     * {@code bytes} carries the status code when the kind is CREDENTIALS or REFUSED; {@code failure}
     * says why a FAILED tile failed, null when it is not worth saying.
     */
    private record Outcome(Kind kind, int bytes, @Nullable Failure failure) {
        static final Outcome FAILED = new Outcome(Kind.FAILED, 0, null);
        static final Outcome EMPTY = new Outcome(Kind.EMPTY, 0, null);
        static final Outcome STOPPED = new Outcome(Kind.STOPPED, 0, null);
        static final Outcome CACHED = new Outcome(Kind.CACHED, 0, null);
        static final Outcome KNOWN_EMPTY = new Outcome(Kind.KNOWN_EMPTY, 0, null);

        Outcome(final Kind kind, final int bytes) {
            this(kind, bytes, null);
        }

        static Outcome failed(final int status, final @Nullable String message, final boolean retryable) {
            return new Outcome(Kind.FAILED, 0, new Failure(status, message, retryable, false));
        }

        /** A tile that failed here, before any server heard of it, and would fail so again. */
        static Outcome local(final @NotNull String why) {
            return new Outcome(Kind.FAILED, 0, new Failure(0, why, false, true));
        }
    }

    /**
     * One call's worth of state. A downloader is shared – the scale preview's image loaders call
     * it from several threads at once – so nothing that belongs to one job lives in its fields;
     * and what belongs to the provider, which every job asking it shares, is its
     * {@link ProviderLimits.Limit}.
     */
    private final class Job {
        private final Provider provider;
        private final @Nullable String apiKey;
        private final ProgressSink progress;
        private final Instant start = Instant.now();
        /** What the provider allows every job asking it, this one among them. */
        private final ProviderLimits.Limit limit;
        /** How many requests the provider takes at once from the whole process, by this job's definition. */
        private final int connections;

        private final List<TileKey> todo = new ArrayList<>();
        private int fromCache;
        private int knownEmpty;
        /** Whether the download phase began, which is when there is something worth logging. */
        private boolean started;
        /** Whether a cancel ended the look through the cache, before anything was known or asked. */
        private boolean scanStopped;

        /** The index of the next tile a worker takes. */
        private final AtomicInteger next = new AtomicInteger();
        private final AtomicInteger downloaded = new AtomicInteger();
        private final AtomicInteger empty = new AtomicInteger();
        /**
         * Tiles missing at the inventory that something else – the 1:1 preview, the view's
         * download, another process – cached, or found not to exist, before this job asked: counted
         * as the inventory's cached and empty ones are, since they are the same thing found later.
         */
        private final AtomicInteger cachedOnTheWay = new AtomicInteger();
        private final AtomicInteger emptyOnTheWay = new AtomicInteger();
        private final AtomicLong bytes = new AtomicLong();
        private final List<TileKey> failed = Collections.synchronizedList(new ArrayList<>());
        /** Why the last of them failed, the last worth asking again kept over any later one that is not. */
        private final AtomicReference<Failure> failure = new AtomicReference<>();
        /** Set by the first rejected key; every worker stops at its next look. */
        private final AtomicBoolean halt = new AtomicBoolean();
        private final AtomicInteger rejectedWith = new AtomicInteger();
        /**
         * Set by the first 401 or 403 from a provider that needs no key; every worker stops at its
         * next look, the same as {@link #halt}. Not a rejected credential – there is none to
         * reject – so it ends the job with a {@link Refusal} rather than a
         * {@link MissingCredentialsException}, which would send the user to a key field this
         * provider does not have.
         */
        private final AtomicBoolean refused = new AtomicBoolean();
        private final AtomicInteger refusedWith = new AtomicInteger();
        private final AtomicReference<String> refusedHost = new AtomicReference<>();
        /** Why the cache would not take a tile, or a tile's answer; nothing more is asked after it. */
        private final AtomicReference<IOException> unwritable = new AtomicReference<>();
        /** The tiles a stop left in a worker's hands, unasked or half asked. */
        private final List<TileKey> left = Collections.synchronizedList(new ArrayList<>());
        /**
         * Set when the provider's rate limit outlasted the job, read before the job let go of it:
         * a limit begins afresh once nobody is asking, and this job's report is to say what ended it.
         */
        private @Nullable RateLimited rateLimited;

        private @Nullable Report report;

        // Not annotated: an inner class's constructor has the outer instance as a hidden first
        // parameter, and SpotBugs, reading the annotations from the first one, took provider's
        // @NotNull for apiKey's – whenever the order it happened to analyze the classes in let it
        Job(final Provider provider, final String apiKey, final ProgressSink progress) {
            this.provider = provider;
            this.apiKey = apiKey;
            this.progress = progress;
            this.limit = limits.of(provider);
            this.connections = provider.bulkDownload() ? Integer.MAX_VALUE : POLITE_CONCURRENCY;
        }

        /** @param scan what the cache has of the job, or null when a cancel ended the look */
        @NotNull Report run(final @NotNull Function<Job, TileCache.Inventory> scan) throws IOException {
            final TileCache.Inventory inventory = scan.apply(this);
            if (inventory == null) {
                scanStopped = true;
                return report();
            }
            fromCache = inventory.cached().size();
            // the provider has answered these already; asking again changes nothing
            knownEmpty = inventory.empty().size();
            todo.addAll(inventory.missing());
            // A viewing-only provider may still be browsed, which is what a screenful is. The old
            // rule refused anything past a single tile, so panning to a new view and pressing
            // "download this view" threw – correct about the policy, wrong about what browsing is.
            // No confirmation unlocks this: the limit is the provider's, not the user's to waive.
            if (!provider.bulkDownload() && todo.size() > BROWSE_LIMIT) {
                throw new BulkDownloadRefusedException(provider, todo.size());
            }
            if (todo.isEmpty()) {
                // Nothing left to ask. If none of it is real – every key answered empty on an
                // earlier run, and none is cached – that is the "nothing here" a first run raises
                // after asking, not a second success.
                if (fromCache == 0 && knownEmpty > 0) {
                    throw new NothingThereException(provider, knownEmpty);
                }
                return report();
            }
            // Without a key every request is a 401, and a job that knows so before it starts has no
            // business sending them. A job that asks nothing needs none, which is why this is here.
            if (provider.needsKey() && (apiKey == null || apiKey.isBlank())) {
                throw new MissingCredentialsException(provider.id(), 0, report());
            }

            // coarse zooms first, then row-major: an interrupted job still leaves a usable map
            todo.sort(Comparator.comparingInt(TileKey::zoom).thenComparingInt(TileKey::y).thenComparingInt(TileKey::x));
            final int workers = Math.min(todo.size(), provider.bulkDownload()
                    ? concurrency : Math.min(concurrency, POLITE_CONCURRENCY));

            log.debug("Downloading {} tile(s) from {} ({}), {} already cached, {} known empty, {} worker(s)",
                    todo.size(), provider.id(), provider.redactedTemplate(), fromCache, knownEmpty, workers);
            progress.phase(PHASE_DOWNLOADING, todo.size(), ProgressSink.Unit.TILES);
            started = true;

            // One of the jobs asking the provider until every request of its own is answered: what
            // a 429 starts is theirs together, and whether it ended them is read before this one
            // lets go
            limit.join();
            try {
                try (final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                    for (int w = 0; w < workers; w++) {
                        final int worker = w;
                        pool.execute(() -> work(worker));
                    }
                }
                if (limit.outlasted()) {
                    rateLimited = new RateLimited(limit.namedUntil());
                }
            } finally {
                limit.leave();
            }

            final RateLimited limited = rateLimited;
            if (limited != null) {
                // Not fetched because the server would not have it, which a caller asking whether
                // the job is whole has to hear: they fail, the report says why, and they can be
                // asked for another time
                failed.addAll(left);
                failed.addAll(todo.subList(Math.min(next.get(), todo.size()), todo.size()));
                failure.set(new Failure(429, null, true, false));
                log.debug("{} of {} tile(s) from {} not fetched: its rate limit outlasts what a job waits{}",
                        failed.size(), todo.size(), provider.id(),
                        limited.until() == null ? "" : ", until " + limited.until());
            }
            final Report done = report();
            if (refused.get()) {
                throw new Refusal(DownloadReason.REJECTED_WITHOUT_KEY, refusedHost.get(), refusedWith.get());
            }
            if (halt.get()) {
                throw new MissingCredentialsException(provider.id(), rejectedWith.get(), done);
            }
            final IOException notStored = unwritable.get();
            if (notStored != null) {
                throw notStored;
            }
            // Every tile asked for answered "none here" and nothing was cached: a job that came to
            // nothing, which is either the wrong place for this provider or the wrong address
            final int nothing = empty.get() + emptyOnTheWay.get();
            if (!progress.isCanceled() && fromCache + cachedOnTheWay.get() == 0 && nothing == todo.size()) {
                throw new NothingThereException(provider, knownEmpty + nothing);
            }
            return done;
        }

        /**
         * The line {@code finished} is told: that everything was already cached when it was, and
         * otherwise the report, which says how many do not exist, since sea is not "cached".
         */
        @NotNull String closingLine() {
            return !scanStopped && todo.isEmpty() && knownEmpty == 0
                    ? String.format(Locale.ROOT, FINISHED_ALL_CACHED, fromCache)
                    : report().summary();
        }

        /**
         * Worked out the first time it is asked for, which is never while a worker runs, so the
         * exception and the closing line say the same thing.
         */
        private @NotNull Report report() {
            if (report == null) {
                report = new Report(downloaded.get(), fromCache + cachedOnTheWay.get(),
                        knownEmpty + empty.get() + emptyOnTheWay.get(), failed.size(),
                        bytes.get(), Duration.between(start, Instant.now()), List.copyOf(failed), rateLimited,
                        failure.get());
            }
            return report;
        }

        /**
         * Whether the job is to ask nothing more: a rejected key, a keyless refusal, a cache that
         * will not take a tile, the provider's rate limit outlasting it, or its caller's cancel.
         * Asked only once the job is one of those asking the provider.
         */
        private boolean stopped() {
            return halt.get() || refused.get() || limit.outlasted() || unwritable.get() != null || canceled();
        }

        /**
         * Whether its caller wants the job no longer. All the look through the cache asks, before
         * the job asks the provider anything: a limit that ended the jobs asking before is theirs.
         */
        private boolean canceled() {
            return progress.isCanceled() || Thread.currentThread().isInterrupted();
        }

        /**
         * The request for a tile, sent, following its redirects.
         * <p>
         * The provider's credential goes only to the origin of the address its template gave: a
         * redirect elsewhere is asked without it, whatever header the provider carries it in. A
         * key in the address is the server's to repeat or not – WTF follows a {@code Location}
         * as it is written and adds nothing to it. Like the client's {@code NORMAL}, a redirect
         * from https to http is not followed, and neither is a sixth; the answer is then the
         * redirect itself, which fails the tile.
         *
         * @param first the request for the address the template gave, credential and all
         */
        private @NotNull HttpResponse<byte[]> send(final @NotNull HttpRequest first)
                throws IOException, InterruptedException {
            HttpRequest at = first;
            for (int hops = 0; ; hops++) {
                final HttpResponse<byte[]> res = http.send(at, HttpResponse.BodyHandlers.ofByteArray());
                final URI next = hops < MAX_REDIRECTS ? redirect(res) : null;
                if (next == null) {
                    return res;
                }
                at = request(next, sameOrigin(next, first.uri()));
            }
        }

        /**
         * One worker: the next tile in order, until there is none or the job has been stopped.
         * After a 429 only the first worker takes another tile, since the limit is the provider's;
         * the others finish the tile in their hands, one request at a time, and go.
         */
        private void work(final int worker) {
            while (!stopped()) {
                if (worker > 0 && limit.throttled()) {
                    return;
                }
                final int i = next.getAndIncrement();
                if (i >= todo.size()) {
                    return;
                }
                final TileKey key = todo.get(i);
                Outcome o;
                try {
                    o = fetchOne(key);
                } catch (final RuntimeException ex) {
                    // WTF's own fault, named by its kind and where it was thrown, never by its
                    // message: what refuses to make a request quotes what it refused – an
                    // address, a header, and the key in either
                    final StackTraceElement[] at = ex.getStackTrace();
                    log.debug("{} failed here, not at the server: {} at {}", key, ex.getClass().getName(),
                            at.length == 0 ? "?" : at[0]);
                    o = Outcome.local(ex.getClass().getName());
                }
                final boolean counted = switch (o.kind()) {
                    case STORED -> {
                        downloaded.incrementAndGet();
                        bytes.addAndGet(o.bytes());
                        yield true;
                    }
                    case EMPTY -> {
                        empty.incrementAndGet();
                        yield true;
                    }
                    case CACHED -> {
                        cachedOnTheWay.incrementAndGet();
                        yield true;
                    }
                    case KNOWN_EMPTY -> {
                        emptyOnTheWay.incrementAndGet();
                        yield true;
                    }
                    case FAILED -> {
                        failed.add(key);
                        final Failure f = o.failure();
                        if (f != null) {
                            failure.getAndUpdate(was -> f.retryable() || was == null || !was.retryable() ? f : was);
                        }
                        yield true;
                    }
                    case CREDENTIALS -> {
                        rejectedWith.compareAndSet(0, o.bytes());
                        halt.set(true);
                        yield false;
                    }
                    case REFUSED -> {
                        refusedWith.compareAndSet(0, o.bytes());
                        refused.set(true);
                        yield false;
                    }
                    // Left by a stop: not fetched, and not failed unless the stop was the server's
                    case STOPPED -> {
                        left.add(key);
                        yield false;
                    }
                };
                if (counted) {
                    progress.advance(1);
                    progress.note(key.toString());
                }
            }
        }

        private @NotNull Outcome fetchOne(final @NotNull TileKey key) {
            // The inventory is as old as the job: the 1:1 preview or the view's download may have
            // fetched this tile since, and a tile cached is never fetched again (tiles-module §2) –
            // on a paid provider, asked twice is paid for twice. One look, as the inventory looked.
            if (cache.contains(key)) {
                return Outcome.CACHED;
            }
            if (cache.isKnownEmpty(key)) {
                return Outcome.KNOWN_EMPTY;
            }
            if (key.scale() != 1 && cache.knowsNoRetina(key.providerId(), key.themeId())) {
                return Outcome.FAILED;      // asked before, and answered at the usual size
            }
            // Made once, before anything is sent: a request that cannot be made fails the tile
            // here, said without the address – a key sent in it is part of it – and without the
            // words of what refused it, which quote it
            final URI uri;
            try {
                uri = address(key);
            } catch (final URISyntaxException ex) {
                return unmade(key, String.format(Locale.ROOT, UNMADE_ADDRESS, ex.getReason()));
            }
            final HttpRequest first;
            try {
                first = request(uri, true);
            } catch (final IllegalArgumentException ex) {
                // The address is one a request can go to, so what the client refused is the header
                // the key goes in, whose value its message quotes
                return unmade(key, UNMADE_KEY);
            }
            // A busy server and a broken one are different troubles: two 429s are no reason to give
            // up on a tile at its first 503, nor two 503s at its first 429. A 5xx is the tile's,
            // and a 429 the provider's, which counts it for every job asking
            final Budget troubled = new Budget(MAX_ATTEMPTS_SERVER_ERROR, firstBackoff, maxWait);

            while (true) {
                // What the provider allows everybody asking it, not the tile or the job: nothing
                // before a 429's time, one request at a time after one, and a viewing-only
                // provider two at a time in any case
                if (!limit.enter(connections, this::stopped)) {
                    return Outcome.STOPPED;
                }
                Duration wait;
                try {
                    final long sentAt = System.nanoTime();
                    final HttpResponse<byte[]> res = send(first);
                    final int code = res.statusCode();

                    if (code == 200) {
                        final byte[] body = res.body();
                        final TileFormat sniffed = TileFormat.sniff(body);
                        if (!sniffed.isImage()) {
                            log.debug("{} returned HTTP 200 but not an image ({} bytes)", key, body.length);
                            return Outcome.failed(200, null, false);
                        }
                        final TileFormat declared = TileFormat.ofContentType(
                                res.headers().firstValue("content-type").orElse(null));
                        if (declared != sniffed && declared != TileFormat.UNKNOWN) {
                            log.debug("{}/{}: Content-Type says {} but the bytes are {} - trusting the bytes",
                                    provider.id(), key.themeId(), declared, sniffed);
                        }
                        // A @2x tile is proven by its pixels: a server that ignores the @2x answers
                        // 200 with the usual tile, which stored as a @2x one would print at half
                        // the size of its neighbors' detail. Remembered, so the map is not asked again.
                        final int width = key.scale() == 1 ? -1 : TileFormat.widthOf(body);
                        if (width > 0 && width != key.pixels()) {
                            log.info("{}/{} answered @2x with {} px tiles; it has no @2x tiles",
                                    provider.id(), key.themeId(), width);
                            try {
                                cache.recordNoRetina(key.providerId(), key.themeId());
                            } catch (final IOException ex) {
                                log.warn("Could not record that {}/{} has no @2x tiles: {}",
                                        provider.id(), key.themeId(), ex.getMessage());
                            }
                            return Outcome.FAILED;
                        }
                        try {
                            cache.store(key, body, lastModified(res));
                        } catch (final IOException ex) {
                            // Neither the network nor this tile: a cache that will not take one tile
                            // rarely takes the next, and each tile asked for after it would be a
                            // request spent for nothing. The job stops and says why.
                            unwritable.compareAndSet(null, new IOException(
                                    "The tile cache would not take " + key + ": " + ex.getMessage(), ex));
                            return Outcome.FAILED;
                        }
                        return new Outcome(Kind.STORED, body.length);
                    }
                    if (code == 204 || code == 404) {
                        // Not a failure and not a gap: the provider says there is no map here. Recorded,
                        // so a later export knows the difference between sea and an unfinished download,
                        // and so that it is never asked for again.
                        try {
                            cache.recordEmpty(key);
                        } catch (final IOException ex) {
                            // An answer the cache would not keep is a tile the export takes for a
                            // missing one, and a job of nothing but sea would say its answers were
                            // recorded when they were not. A cache that will not take one marker
                            // rarely takes the next: the job stops, as for a tile, and says why.
                            unwritable.compareAndSet(null, new IOException("The tile cache would not"
                                    + " record that " + key + " does not exist: " + ex.getMessage(), ex));
                            return Outcome.FAILED;
                        }
                        return Outcome.EMPTY;
                    }
                    if (code == 401 || code == 403) {
                        if (!provider.needsKey()) {
                            // No key was ever sent, so none was rejected: the server is refusing
                            // WTF for some other reason, and calling it a wrong or missing key
                            // would send the user to a settings field this provider does not have
                            refusedHost.compareAndSet(null, uri.getHost());
                            return new Outcome(Kind.REFUSED, code);
                        }
                        return new Outcome(Kind.CREDENTIALS, code);   // never retry a rejected key
                    }
                    if (code == 429) {
                        // Counted for the provider, whose quiet then holds every job asking it. Asked
                        // to wait longer than a job waits, or refused a fifth time, the limit is not
                        // a burst: this tile fails, and the limit has said so once in the log
                        final Duration quiet = limit.refused(sentAt,
                                retryAfter(res.headers().firstValue("retry-after").orElse(null), Instant.now()));
                        if (quiet == null) {
                            return Outcome.failed(429, null, true);
                        }
                        log.info("Rate limited by {}, asking nothing for {} ms", provider.id(), quiet.toMillis());
                        wait = Duration.ZERO;       // the quiet is waited for at the top, by everybody
                    } else if (code >= 500) {
                        if (!troubled.spend()) {
                            return Outcome.failed(code, null, true);
                        }
                        wait = troubled.backoff();
                    } else {
                        log.debug("{} returned unexpected HTTP {}", key, code);
                        return Outcome.failed(code, null, false);
                    }
                } catch (final IOException ex) {
                    if (!troubled.spend()) {
                        // The JDK's client leaves some messages empty, a refused connection's among them
                        return Outcome.failed(0, ex.getMessage() == null || ex.getMessage().isBlank()
                                ? ex.getClass().getSimpleName() : ex.getMessage(), true);
                    }
                    wait = troubled.backoff();
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return Outcome.STOPPED;
                } finally {
                    // Given back once the answer is taken in, however it was answered: a 429 given
                    // back before it was counted would let the next request go out before it, and
                    // that request's own 429 be taken for the same refusal again
                    limit.exit();
                }
                if (!pause(wait, this::stopped)) {
                    return Outcome.STOPPED;
                }
            }
        }

        /**
         * The address of a tile: its template filled in, the key encoded. One the client takes:
         * http or https, and a host – which a name the parser reads as some other kind of
         * authority, such as {@code tiles_a.example.com}, is not.
         *
         * @throws URISyntaxException when the template makes no such address; its reason says why
         *         without the address, which its message quotes
         */
        private @NotNull URI address(final @NotNull TileKey key) throws URISyntaxException {
            final String address = provider.url(key, apiKey);
            final URI uri = new URI(address).parseServerAuthority();
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new URISyntaxException(address, "Not http or https");
            }
            if (uri.getHost() == null) {
                throw new URISyntaxException(address, "No host");
            }
            return uri;
        }

        /**
         * A tile no request could be made for: no server heard of it, and asking again would fail
         * the same way. The log and the report say why in words that hold neither its address nor
         * its headers.
         */
        private @NotNull Outcome unmade(final @NotNull TileKey key, final @NotNull String why) {
            log.debug("{}: no request could be made: {}", key, why);
            return Outcome.local(why);
        }

        /**
         * A request for this address, carrying the provider's credential when {@code credited}.
         *
         * @throws IllegalArgumentException when the client will not make it – an address without a
         *         host, a header it will not carry – with a message that quotes either
         */
        private @NotNull HttpRequest request(final @NotNull URI uri, final boolean credited) {
            final HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", userAgent)
                    .header("Accept", "image/*")
                    .GET();
            final Provider.Auth auth = provider.authOrNone();
            if (apiKey != null && credited) {
                // What the header carries is the provider's to say – the bare key, or the key in a
                // phrase such as Stadia's "Stadia-Auth <key>" – and the catalog has made sure a
                // header provider names its header
                switch (auth.kind()) {
                    case HEADER -> b.header(Objects.requireNonNull(auth.name()), auth.headerValue(apiKey));
                    // The key is "user:password", as RFC 7617 puts the two together
                    case BASIC -> b.header("Authorization", "Basic " + Base64.getEncoder()
                            .encodeToString(apiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    case NONE, QUERY -> { }
                }
            }
            return b.build();
        }
    }

    /**
     * How often one kind of trouble may be met – on one tile, or by the jobs asking a provider
     * together, inside its {@link ProviderLimits.Limit}, whose lock it is used under – and how
     * long to wait after each.
     */
    static final class Budget {
        private final int attempts;
        private final Duration cap;
        private int failures;
        private Duration backoff;

        /**
         * @param first the wait after the first failure, doubling with each one after it
         * @param cap   the longest wait
         */
        Budget(final int attempts, final @NotNull Duration first, final @NotNull Duration cap) {
            this.attempts = attempts;
            this.cap = cap;
            this.backoff = first;
        }

        /** Counts one failure; true when it may be asked for again. */
        boolean spend() {
            return ++failures < attempts;
        }

        /** The wait before the next attempt, doubling each time up to the cap. */
        @NotNull Duration backoff() {
            final Duration d = atMost(backoff, cap);
            backoff = atMost(backoff.multipliedBy(2), cap);
            return d;
        }
    }

    /**
     * Where a redirect sends the request, or null when the answer is not one to follow: not a
     * 301, 302, 303, 307 or 308, no {@code Location} that is an address, or one from https to http.
     */
    private static @Nullable URI redirect(final @NotNull HttpResponse<?> res) {
        final int code = res.statusCode();
        if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308) {
            return null;
        }
        final Optional<String> location = res.headers().firstValue("location");
        if (location.isEmpty()) {
            return null;
        }
        final URI next;
        try {
            next = res.uri().resolve(location.get().trim());
        } catch (final IllegalArgumentException ex) {
            return null;
        }
        final String scheme = next.getScheme() == null ? "" : next.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || next.getHost() == null
                || scheme.equals("http") && "https".equalsIgnoreCase(res.uri().getScheme())) {
            return null;
        }
        return next;
    }

    /** Whether two addresses have one origin: the same scheme, host and port, a default port spelled or not. */
    static boolean sameOrigin(final @NotNull URI a, final @NotNull URI b) {
        return a.getScheme() != null && a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost())
                && port(a) == port(b);
    }

    private static int port(final @NotNull URI u) {
        if (u.getPort() != -1) {
            return u.getPort();
        }
        return "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    private static @NotNull Duration atMost(final @NotNull Duration d, final @NotNull Duration cap) {
        return d.compareTo(cap) > 0 ? cap : d;
    }

    /**
     * Waits, looking every {@value #STOP_CHECK_MILLIS} ms whether the job still wants it.
     *
     * @return false when the job was stopped meanwhile, and the tile is to be left
     */
    private static boolean pause(final @NotNull Duration d, final @NotNull BooleanSupplier stopped) {
        final long end = System.nanoTime() + d.toNanos();
        while (!stopped.getAsBoolean()) {
            final long left = end - System.nanoTime();
            if (left <= 0) {
                return true;
            }
            try {
                Thread.sleep(Math.min(STOP_CHECK_MILLIS, TimeUnit.NANOSECONDS.toMillis(left) + 1));
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * How long a server asked to be left alone. {@code Retry-After} is a number of seconds or an
     * HTTP date, and a date already past means now. Empty when it said neither, and the backoff
     * decides; the cap is the caller's.
     */
    static @NotNull Optional<Duration> retryAfter(final @Nullable String value, final @NotNull Instant now) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            final long seconds = Long.parseLong(value.trim());
            return seconds < 0 ? Optional.empty() : Optional.of(Duration.ofSeconds(seconds));
        } catch (final NumberFormatException notSeconds) {
            final Instant at = httpDate(value);
            if (at == null) {
                return Optional.empty();
            }
            final Duration d = Duration.between(now, at);
            return Optional.of(d.isNegative() ? Duration.ZERO : d);
        }
    }

    private static @Nullable Instant lastModified(final @NotNull HttpResponse<?> res) {
        return res.headers().firstValue("last-modified").map(TileDownloader::httpDate).orElse(null);
    }

    /** An HTTP date – {@code Sun, 06 Nov 1994 08:49:37 GMT} – or null when it is not one. */
    private static @Nullable Instant httpDate(final @NotNull String value) {
        try {
            return ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (final RuntimeException ignored) {
            return null;
        }
    }

    @Override
    public void close() {
        http.close();
    }
}
