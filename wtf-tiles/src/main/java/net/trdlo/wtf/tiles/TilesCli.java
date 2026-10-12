package net.trdlo.wtf.tiles;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * The {@code wtf-tiles} command line: how the module is exercised without a window, in CI, and by
 * an agent checking a change.
 * <p>
 * Exit codes are meaningful so a caller can branch on them: {@code 0} success, {@code 1} a job
 * error, {@code 2} bad arguments, {@code 3} missing credentials, {@code 4} tiles missing (from
 * {@code count}), {@code 5} bulk download refused.
 * Every line is prefixed {@code [tiles]} so tool output is separable from wrapper output.
 */
public final class TilesCli {

    private static final String PREFIX = "[tiles]";

    /** The exit code of a {@code count} that found tiles to download. */
    static final int MISSING = 4;

    /**
     * The most tiles one command takes on. Every key of a range is made before anything is asked,
     * and a zoom-19 bbox of the world is 275 billion of them – an out-of-memory error rather than
     * an answer. A million is more than any wall this is meant to print.
     */
    static final long MAX_TILES = 1_000_000;

    private TilesCli() {
    }

    public static void main(final String[] args) {
        ConsoleOutput.configureLogging();
        System.setOut(ConsoleOutput.wrap(System.out));
        System.setErr(ConsoleOutput.wrap(System.err));
        System.exit(run(args));
    }

    static int run(final String[] args) {
        if (args.length == 0 || isHelp(args[0])) {
            usage();
            return args.length == 0 ? 2 : 0;
        }
        // Inside the handling: a mistyped option is a bad argument, exit 2, not a stack trace
        return exitCode(() -> command(args));
    }

    private static int command(final String[] args) throws IOException {
        final Args a = Args.parse(Arrays.copyOfRange(args, 1, args.length));
        return switch (args[0]) {
            case "providers" -> providers(a);
            case "stats" -> stats(a);
            case "count" -> count(a);
            case "download" -> download(a);
            case "forget-empty" -> forgetEmpty(a);
            default -> {
                System.err.println(PREFIX + " unknown command: " + args[0]);
                usage();
                yield 2;
            }
        };
    }

    /**
     * The exit code of a command, or of what stopped it. Apart from {@link #run} so that a test can
     * hand it a refusal no argument of today's produces.
     */
    static int exitCode(final @NotNull Callable<Integer> command) {
        try {
            try {
                return command.call();
            } catch (final Refusal ex) {
                // A download stopped by something other than a bad argument – a keyless provider
                // refusing the request itself – is a job error; any other refusal is a mistake in
                // the arguments, which the branch below says
                if (!(ex.reason() instanceof DownloadReason)) {
                    throw ex;
                }
                System.err.println(PREFIX + " " + ex.getMessage());
                return 1;
            }
        } catch (final TileDownloader.MissingCredentialsException ex) {
            System.err.println(PREFIX + " " + ex.getMessage());
            System.err.println(PREFIX + " set one with --key, or WTF_KEY_<PROVIDER> in the environment");
            return 3;
        } catch (final TileDownloader.BulkDownloadRefusedException ex) {
            System.err.println(PREFIX + " " + ex.getMessage());
            return 5;
        } catch (final NothingThereException ex) {
            // Raised only once every answer is recorded: one the cache would not keep stops the
            // job with the cache's own error instead, which says so
            System.err.println(PREFIX + " " + ex.getMessage());
            System.err.println(PREFIX + " the answers are recorded; if the template was wrong, fix it and run"
                    + " forget-empty for the theme");
            return 1;
        } catch (final IllegalArgumentException ex) {
            System.err.println(PREFIX + " " + ex.getMessage());
            return 2;
        } catch (final Exception ex) {
            System.err.println(PREFIX + " " + ex);
            return 1;
        }
    }

    private static boolean isHelp(final String s) {
        return "-h".equals(s) || "--help".equals(s) || "help".equals(s);
    }

    private static void usage() {
        System.out.println("""
                wtf-tiles - the tile cache and downloader

                  providers                                  list providers and their themes
                  stats     [--provider P]                   what is in the cache
                  count     --provider P --theme T --zoom Z --bbox W,S,E,N
                                                             how many tiles, how many missing
                                                             (exit 4 when some are)
                  download  --provider P --theme T --zoom Z --bbox W,S,E,N [--key K]
                                                             fetch what is missing
                  forget-empty --provider P --theme T        forget the tiles the provider said
                                                             do not exist, so they are asked again

                Common options:
                  --cache DIR       cache root (default: the per-user cache directory)
                  --providers FILE  merged over the shipped ones (default ~/.wtf/providers.json)
                  --lang LL         the language of a map lettered in one, cached apart
                                    (default: the first the provider lists, or en)
                  --threads N       concurrent requests (default 6)
                  --json            machine-readable output on stdout
                """);
    }

    // -------------------------------------------------------------------------------------------

    private static int providers(final @NotNull Args a) {
        final ProviderCatalog catalog = catalog(a);
        if (a.json) {
            final StringBuilder sb = new StringBuilder("{\"providers\":[");
            boolean first = true;
            for (final Provider p : catalog.all()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append("{\"id\":\"").append(p.id()).append("\",\"needsKey\":").append(p.needsKey())
                        .append(",\"bulkDownload\":").append(p.bulkDownload())
                        .append(",\"themes\":[");
                sb.append(String.join(",", p.themes().stream().map(t -> "\"" + t.id() + "\"").toList()));
                sb.append("]}");
            }
            System.out.println(sb.append("]}"));
            return 0;
        }
        System.out.printf(Locale.ROOT, "%s %d provider(s)%n", PREFIX, catalog.size());
        for (final Provider p : catalog.all()) {
            System.out.printf(Locale.ROOT, "%n  %-14s %s%s%s%n", p.id(), p.displayName(),
                    p.needsKey() ? "  [key required]" : "  [no key]",
                    p.bulkDownload() ? "" : "  [bulk download refused]");
            System.out.printf(Locale.ROOT, "      %s%n", p.redactedTemplate());
            for (final Provider.Theme t : p.themes()) {
                System.out.printf(Locale.ROOT, "      - %-18s %-26s z%d..%d%s%n", t.id(), t.displayName(),
                        p.minZoomOf(t), p.maxZoomOf(t),
                        p.expectedFormatOf(t) == null ? "" : "  " + p.expectedFormatOf(t));
            }
        }
        return 0;
    }

    private static int stats(final @NotNull Args a) throws IOException {
        final TileCache cache = new TileCache(TileCache.resolveRoot(a.cache));
        System.out.println(PREFIX + " cache root: " + cache.root());
        final List<TileCache.ThemeStats> all = cache.statistics();
        if (all.isEmpty()) {
            System.out.println(PREFIX + " empty");
            return 0;
        }
        long totalFiles = 0, totalBytes = 0;
        for (final TileCache.ThemeStats t : all) {
            if (a.provider != null && !a.provider.equalsIgnoreCase(t.providerId())) {
                continue;
            }
            System.out.printf(Locale.ROOT, "%n  %s / %s - %,d files, %,.1f MB%n",
                    t.providerId(), t.themeId() + (t.lang() == null ? "" : "@" + t.lang()),
                    t.files(), t.bytes() / 1_048_576.0);
            for (final TileCache.ZoomStats z : t.zooms()) {
                System.out.printf(Locale.ROOT, "      z%-5s %,9d files %,9.1f MB   %s%n",
                        z.zoom() + (z.scale() == 1 ? "" : "@" + z.scale() + "x"), z.files(),
                        z.bytes() / 1_048_576.0, z.range());
            }
            totalFiles += t.files();
            totalBytes += t.bytes();
        }
        System.out.printf(Locale.ROOT, "%n%s total %,d files, %,.2f GB%n",
                PREFIX, totalFiles, totalBytes / 1_073_741_824.0);
        return 0;
    }

    private static int count(final @NotNull Args a) {
        final Job job = Job.of(a, catalog(a));
        final TileCache cache = new TileCache(TileCache.resolveRoot(a.cache));
        final TileCache.Inventory inv = cache.inventory(job.keys);
        if (a.json) {
            System.out.printf(Locale.ROOT,
                    "{\"needed\":%d,\"cached\":%d,\"empty\":%d,\"missing\":%d,\"range\":\"%s\"}%n",
                    inv.needed(), inv.cached().size(), inv.empty().size(), inv.missing().size(), job.range);
            return inv.missing().isEmpty() ? 0 : MISSING;
        }
        System.out.printf(Locale.ROOT, "%s %s %s z%d %s%n",
                PREFIX, job.provider.id(), job.theme.id(), job.zoom, job.range);
        System.out.printf(Locale.ROOT, "%s needed %,d | cached %,d (%.0f %%) | empty %,d | to download %,d%n",
                PREFIX, inv.needed(), inv.cached().size(), inv.cachedFraction() * 100, inv.empty().size(),
                inv.missing().size());
        return inv.missing().isEmpty() ? 0 : MISSING;
    }

    private static int download(final @NotNull Args a) throws IOException {
        final ProviderCatalog catalog = catalog(a);
        final Job job = Job.of(a, catalog);
        final TileCache cache = new TileCache(TileCache.resolveRoot(a.cache));
        cache.cleanPartFiles(java.time.Duration.ofHours(1));

        // A provider that needs a key and has none is refused by the downloader, before its first request
        final String key = a.key != null ? a.key : keyFromEnvironment(job.provider);
        final TileCache.Inventory inv = cache.inventory(job.keys);
        System.out.printf(Locale.ROOT, "%s %s %s z%d: needed %,d, cached %,d, empty %,d, to download %,d%n",
                PREFIX, job.provider.id(), job.theme.id(), job.zoom,
                inv.needed(), inv.cached().size(), inv.empty().size(), inv.missing().size());
        // Always asked, even with nothing missing: a job in which every key answered empty on an
        // earlier run – nothing here is cached, all of it known not to exist – is not a second
        // success, and only the downloader tells the two apart (NothingThereException)
        final ConsoleProgress progress = new ConsoleProgress(PREFIX);
        try (final TileDownloader downloader = new TileDownloader(cache, a.threads, userAgent())) {
            // The whole job, not only its missing part: whether it came to nothing is judged over
            // all of it. As the inventory just made of it, so that the cache is looked through once
            final TileDownloader.Report r =
                    downloader.download(job.provider, inv, key, progress);
            return r.failed() == 0 ? 0 : 1;
        }
    }

    /** For a template that was wrong: the 404s it collected would otherwise stand for sea for good. */
    private static int forgetEmpty(final @NotNull Args a) throws IOException {
        // Read once: the catalog is built from the same arguments, and a second read of a field
        // is one SpotBugs cannot trust to be the value just checked
        final String providerId = a.provider;
        final String themeId = a.theme;
        if (providerId == null || themeId == null) {
            throw new IllegalArgumentException("--provider and --theme are both required");
        }
        // Through the catalog, so that what is deleted is always a theme folder WTF knows
        final Provider p = catalog(a).require(providerId);
        final Provider.Theme t = p.requireTheme(themeId);
        final TileCache cache = new TileCache(TileCache.resolveRoot(a.cache));
        final int forgotten = cache.forgetEmpty(p.id(), t.id());
        System.out.printf(Locale.ROOT, "%s %s %s: forgot %,d empty tile(s)%n", PREFIX, p.id(), t.id(), forgotten);
        return 0;
    }

    /** {@code WTF_KEY_MAPY_COM} for provider {@code mapy-com}. */
    static @NotNull String keyVariable(final @NotNull Provider p) {
        return "WTF_KEY_" + p.id().toUpperCase(Locale.ROOT).replace('-', '_').replace('.', '_');
    }

    static @Nullable String keyFromEnvironment(final @NotNull Provider p) {
        return System.getenv(keyVariable(p));
    }

    /**
     * The command line's own {@code User-Agent}: its version from the jar's manifest, the same
     * version the application's is, or {@code dev} when it runs from classes.
     */
    static @NotNull String userAgent() {
        final String version = TilesCli.class.getPackage().getImplementationVersion();
        return TileDownloader.userAgent("WTF-tiles", version != null ? version : "dev");
    }

    /** The user's own file merged in by default, as the window does; {@code --providers} names another. */
    private static @NotNull ProviderCatalog catalog(final @NotNull Args a) {
        return ProviderCatalog.load(a.providersFile != null ? a.providersFile : ProviderStore.defaultFile());
    }

    // -------------------------------------------------------------------------------------------

    private record Job(Provider provider, Provider.Theme theme, int zoom, TileRange range, List<TileKey> keys) {

        static @NotNull Job of(final @NotNull Args a, final @NotNull ProviderCatalog catalog) {
            if (a.provider == null || a.theme == null || a.zoom == null || a.bbox == null) {
                throw new IllegalArgumentException("--provider, --theme, --zoom and --bbox are all required");
            }
            final Provider p = catalog.require(a.provider);
            final Provider.Theme t = p.requireTheme(a.theme);
            if (a.zoom < p.minZoomOf(t) || a.zoom > p.maxZoomOf(t)) {
                throw new IllegalArgumentException("Zoom " + a.zoom + " is outside " + p.id() + "/" + t.id()
                        + "'s range " + p.minZoomOf(t) + ".." + p.maxZoomOf(t));
            }
            final TileRange range = Mercator.range(a.bbox, a.zoom);
            if (range.count() > MAX_TILES) {
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "%s at zoom %d is %,d tiles; one command takes up to %,d - a smaller --bbox or a"
                                + " lower --zoom", a.bbox, a.zoom, range.count(), MAX_TILES));
            }
            // The provider's own where --lang names none (D40), as a project file naming none is read
            final String lang = a.lang != null ? a.lang : p.defaultLanguage();
            final List<TileKey> keys = new ArrayList<>((int) Math.min(Integer.MAX_VALUE, range.count()));
            for (final int[] xy : range) {
                keys.add(p.key(t.id(), lang, a.zoom, xy[0], xy[1]));
            }
            return new Job(p, t, a.zoom, range, keys);
        }
    }

    /** A small hand-rolled parser; a dependency for six options would be silly. */
    private static final class Args {
        @Nullable String provider, theme, key, lang;
        @Nullable Integer zoom;
        @Nullable LonLatBounds bbox;
        @Nullable Path cache, providersFile;
        int threads = TileDownloader.DEFAULT_CONCURRENCY;
        boolean json;

        static @NotNull Args parse(final String[] args) {
            final Args a = new Args();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--provider" -> a.provider = need(args, ++i, "--provider");
                    case "--theme" -> a.theme = need(args, ++i, "--theme");
                    case "--zoom" -> a.zoom = integer(need(args, ++i, "--zoom"), "--zoom");
                    case "--bbox" -> a.bbox = parseBbox(need(args, ++i, "--bbox"));
                    case "--key" -> a.key = need(args, ++i, "--key");
                    case "--lang" -> a.lang = need(args, ++i, "--lang");
                    case "--cache" -> a.cache = Path.of(need(args, ++i, "--cache"));
                    case "--providers" -> a.providersFile = Path.of(need(args, ++i, "--providers"));
                    case "--threads" -> a.threads = integer(need(args, ++i, "--threads"), "--threads");
                    case "--json" -> a.json = true;
                    default -> throw new IllegalArgumentException("unknown option: " + args[i]);
                }
            }
            return a;
        }

        private static String need(final String[] args, final int i, final String what) {
            if (i >= args.length) {
                throw new IllegalArgumentException(what + " needs a value");
            }
            return args[i];
        }

        private static int integer(final String value, final String what) {
            try {
                return Integer.parseInt(value.trim());
            } catch (final NumberFormatException ex) {
                throw new IllegalArgumentException(what + " wants a whole number, got: " + value);
            }
        }

        /** {@code W,S,E,N} in degrees. Always {@code Locale.ROOT}: a decimal comma here would be chaos. */
        private static @NotNull LonLatBounds parseBbox(final @NotNull String s) {
            final String[] p = s.split(",");
            if (p.length != 4) {
                throw new IllegalArgumentException("--bbox wants W,S,E,N in degrees, got: " + s);
            }
            try {
                return new LonLatBounds(Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim()),
                        Double.parseDouble(p[2].trim()), Double.parseDouble(p[3].trim()));
            } catch (final NumberFormatException ex) {
                throw new IllegalArgumentException("--bbox wants four numbers in W,S,E,N order, got: " + s);
            }
        }
    }
}
