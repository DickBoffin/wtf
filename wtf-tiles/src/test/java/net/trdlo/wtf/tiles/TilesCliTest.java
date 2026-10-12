package net.trdlo.wtf.tiles;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/** The command line, against a cache in a {@code @TempDir} and the shipped catalog. */
class TilesCliTest {

    /**
     * A home of the class's own, with no {@code .wtf} in it: the command line merges
     * {@code ~/.wtf/providers.json} unless {@code --providers} names another, and a developer's own
     * file that hides or overrides {@code osm} or {@code mapy-com} is not what CI, which has none,
     * sees. The test of the default file points it at a home of its own again.
     */
    @TempDir
    static Path classHome;
    private static String originalHome;

    @BeforeAll
    static void homeOfItsOwn() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", classHome.toString());
    }

    @AfterAll
    static void homeGivenBack() {
        System.setProperty("user.home", originalHome);
    }

    @Test
    @DisplayName("forget-empty forgets one theme's empty tiles")
    void forgetEmpty(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final TileKey sea = new TileKey("mapy-com", "outdoor", 10, 550, 350);
        final TileKey winter = new TileKey("mapy-com", "winter", 10, 550, 350);
        cache.recordEmpty(sea);
        cache.recordEmpty(winter);

        assertEquals(0, TilesCli.run(new String[]{"forget-empty",
                "--provider", "mapy-com", "--theme", "outdoor", "--cache", dir.toString()}));
        assertFalse(cache.isKnownEmpty(sea));
        assertTrue(cache.isKnownEmpty(winter));
    }

    @Test
    @DisplayName("forget-empty refuses a theme the catalog does not know")
    void forgetEmptyUnknownTheme(@TempDir final Path dir) {
        assertEquals(2, TilesCli.run(new String[]{"forget-empty",
                "--provider", "mapy-com", "--theme", "no-such-theme", "--cache", dir.toString()}));
        assertEquals(2, TilesCli.run(new String[]{"forget-empty", "--provider", "mapy-com",
                "--cache", dir.toString()}), "both are required");
    }

    private static byte[] png() throws IOException {
        final var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1, 1,
                java.awt.image.BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("a bad option is a bad argument, exit 2, and not a stack trace")
    void badOptionsExitTwo(@TempDir final Path dir) {
        final String cache = dir.toString();
        assertEquals(2, TilesCli.run(new String[]{"count", "--nope"}));
        assertEquals(2, TilesCli.run(new String[]{"count", "--zoom"}), "an option without its value");
        assertEquals(2, TilesCli.run(new String[]{"count", "--provider", "osm", "--theme", "standard",
                "--zoom", "ten", "--bbox", "14,50,15,51", "--cache", cache}));
        assertEquals(2, TilesCli.run(new String[]{"stats", "--threads", "many", "--cache", cache}));
        assertEquals(2, TilesCli.run(new String[]{"download", "--yes"}), "--yes is gone, since nothing read it");
    }

    @Test
    @DisplayName("count exits 4 while tiles are missing and 0 once they are not")
    void countSaysWhetherTilesAreMissing(@TempDir final Path dir) throws IOException {
        final String[] count = {"count", "--provider", "osm", "--theme", "standard", "--zoom", "3",
                "--bbox", "14.4,50.0,14.5,50.1", "--cache", dir.toString()};
        assertEquals(TilesCli.MISSING, TilesCli.run(count));

        final TileCache cache = new TileCache(dir);
        for (final int[] xy : Mercator.range(new LonLatBounds(14.4, 50.0, 14.5, 50.1), 3)) {
            cache.store(new TileKey("osm", "standard", 3, xy[0], xy[1]), png(), null);
        }
        assertEquals(0, TilesCli.run(count));
    }

    @Test
    @DisplayName("count --json exits 4 while tiles are missing too, not always 0")
    void countJsonSaysWhetherTilesAreMissing(@TempDir final Path dir) throws IOException {
        final String[] count = {"count", "--json", "--provider", "osm", "--theme", "standard", "--zoom", "3",
                "--bbox", "14.4,50.0,14.5,50.1", "--cache", dir.toString()};
        assertEquals(TilesCli.MISSING, TilesCli.run(count));

        final TileCache cache = new TileCache(dir);
        for (final int[] xy : Mercator.range(new LonLatBounds(14.4, 50.0, 14.5, 50.1), 3)) {
            cache.store(new TileKey("osm", "standard", 3, xy[0], xy[1]), png(), null);
        }
        assertEquals(0, TilesCli.run(count));
    }

    /**
     * Without {@code --lang}, a map lettered in a language is counted in its provider's own (D40)
     * – the first language its definition lists, or English for one that lists none – and not in
     * one language the command line assumes for every provider.
     */
    @Test
    @DisplayName("without --lang, a lettered map is counted in its provider's first language, or English")
    void withoutLangAMapIsInItsProvidersOwnLanguage(@TempDir final Path dir) throws IOException {
        final Path providersFile = dir.resolve("providers.json");
        Files.writeString(providersFile, """
                { "providers": [ {
                  "id": "lettered",
                  "displayName": "Lettered",
                  "urlTemplate": "http://127.0.0.1:1/{z}/{x}/{y}.png?lang={lang}",
                  "languages": ["de", "en"],
                  "minZoom": 0,
                  "maxZoom": 19,
                  "themes": [ { "id": "t", "displayName": "T" } ],
                  "attribution": "test",
                  "bulkDownload": true
                }, {
                  "id": "any-language",
                  "displayName": "Any language",
                  "urlTemplate": "http://127.0.0.1:1/{z}/{x}/{y}.png?lang={lang}",
                  "minZoom": 0,
                  "maxZoom": 19,
                  "themes": [ { "id": "t", "displayName": "T" } ],
                  "attribution": "test",
                  "bulkDownload": true
                } ] }
                """);
        final Path cacheDir = dir.resolve("cache");
        final TileCache cache = new TileCache(cacheDir);
        for (final int[] xy : Mercator.range(new LonLatBounds(14.4, 50.0, 14.5, 50.1), 3)) {
            cache.store(new TileKey("lettered", "t", "de", 3, xy[0], xy[1]), png(), null);
            cache.store(new TileKey("any-language", "t", "en", 3, xy[0], xy[1]), png(), null);
        }
        final String[] where = {"--theme", "t", "--zoom", "3", "--bbox", "14.4,50.0,14.5,50.1",
                "--cache", cacheDir.toString(), "--providers", providersFile.toString()};

        assertEquals(0, TilesCli.run(with(new String[]{"count", "--provider", "lettered"}, where)),
                "in the first language it lists, German, which is cached");
        assertEquals(0, TilesCli.run(with(new String[]{"count", "--provider", "any-language"}, where)),
                "and in English for one that lists none");
        assertEquals(TilesCli.MISSING, TilesCli.run(with(new String[]{"count", "--provider", "lettered",
                "--lang", "en"}, where)), "--lang still names another, which is not");
    }

    private static String[] with(final String[] command, final String[] options) {
        final String[] all = Arrays.copyOf(command, command.length + options.length);
        System.arraycopy(options, 0, all, command.length, options.length);
        return all;
    }

    @Test
    @DisplayName("a world at zoom 19 is refused before a single key of it is made")
    void aWorldAtZoom19IsRefused(@TempDir final Path dir) {
        assertEquals(2, TilesCli.run(new String[]{"count", "--provider", "osm", "--theme", "standard",
                "--zoom", "19", "--bbox", "-180,-85,180,85", "--cache", dir.toString()}));
    }

    @Test
    @DisplayName("a keyless provider refused with 403 is a job error, not \"missing credentials\"")
    void keylessProviderRefused(@TempDir final Path dir) throws IOException {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        server.start();
        try {
            final Path providersFile = dir.resolve("providers.json");
            Files.writeString(providersFile, """
                    { "providers": [ {
                      "id": "cli-test",
                      "displayName": "CLI Test",
                      "urlTemplate": "http://127.0.0.1:%d/{z}/{x}/{y}.png",
                      "minZoom": 0,
                      "maxZoom": 19,
                      "themes": [ { "id": "t", "displayName": "T" } ],
                      "attribution": "test",
                      "bulkDownload": true
                    } ] }
                    """.formatted(server.getAddress().getPort()));

            assertEquals(1, TilesCli.run(new String[]{"download",
                    "--provider", "cli-test", "--theme", "t", "--zoom", "1", "--bbox", "14,50,15,51",
                    "--cache", dir.resolve("cache").toString(), "--providers", providersFile.toString()}),
                    "not exit 3: there is no key for this provider to reject");
        } finally {
            server.stop(0);
        }
    }

    /**
     * No argument reaches the command line's handling with a refusal of another kind today – the
     * catalog drops a definition it refuses – so the command is handed one, through the same
     * handling {@code run} uses.
     */
    @Test
    @DisplayName("a refusal other than a download's is a bad argument, exit 2, not a job error")
    void onlyADownloadsRefusalIsAJobError() {
        assertEquals(2, TilesCli.exitCode(() -> {
            throw new Refusal(ProviderReason.MAP_ID_UNFIT, "a map!");
        }));
        assertEquals(1, TilesCli.exitCode(() -> {
            throw new Refusal(DownloadReason.REJECTED_WITHOUT_KEY, "127.0.0.1", 403);
        }));
    }

    @Test
    @DisplayName("a download whose tiles are all known empty exits 1 again on the second run, not 0")
    void allEmptyDownloadFailsBothRuns(@TempDir final Path dir) throws IOException {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            final String[] args = {"download",
                    "--provider", "cli-test", "--theme", "t", "--zoom", "1", "--bbox", "14,50,15,51",
                    "--cache", dir.resolve("cache").toString(),
                    "--providers", providersAt(dir, server.getAddress().getPort()).toString()};

            final Said first = runSaying(args);
            assertEquals(1, first.exit(), "nothing at this address exists at all");
            assertTrue(first.err().contains("CLI Test says that none of these"),
                    "the one refusal of a job with nothing in it, as an export says it: " + first.err());
            assertTrue(first.err().contains("the answers are recorded"), first.err());
            assertEquals(1, TilesCli.run(args), "still nothing, not a silent success the second time");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("a download whose answers the cache will not record says so, not that they are recorded")
    void answersNotRecordedAreNotClaimed(@TempDir final Path dir) throws IOException {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            // A cache whose root is a file, where no marker can be written
            final Path cache = Files.createFile(dir.resolve("not-a-directory"));
            final Said said = runSaying("download",
                    "--provider", "cli-test", "--theme", "t", "--zoom", "1", "--bbox", "14,50,15,51",
                    "--cache", cache.toString(),
                    "--providers", providersAt(dir, server.getAddress().getPort()).toString());

            assertEquals(1, said.exit());
            assertTrue(said.err().contains("would not record that"), said.err());
            assertFalse(said.err().contains("the answers are recorded"), said.err());
        } finally {
            server.stop(0);
        }
    }

    /** A providers file with one provider, {@code cli-test}, served on this port. */
    private static Path providersAt(final Path dir, final int port) throws IOException {
        final Path providersFile = dir.resolve("providers.json");
        Files.writeString(providersFile, """
                { "providers": [ {
                  "id": "cli-test",
                  "displayName": "CLI Test",
                  "urlTemplate": "http://127.0.0.1:%d/{z}/{x}/{y}.png",
                  "minZoom": 0,
                  "maxZoom": 19,
                  "themes": [ { "id": "t", "displayName": "T" } ],
                  "attribution": "test",
                  "bulkDownload": true
                } ] }
                """.formatted(port));
        return providersFile;
    }

    /** An exit code and what was said on stderr on the way to it. */
    private record Said(int exit, String err) { }

    private static Said runSaying(final String... args) {
        final PrintStream originalErr = System.err;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        final int exit;
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            exit = TilesCli.run(args);
        } finally {
            System.setErr(originalErr);
        }
        return new Said(exit, captured.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("~/.wtf/providers.json is merged in by default, with no --providers at all")
    void defaultProvidersFileIsMerged(@TempDir final Path home) throws IOException {
        final String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            assertFalse(providersJson().contains("cli-test"), "nothing at the default file yet");

            final Path providersFile = home.resolve(".wtf").resolve("providers.json");
            Files.createDirectories(providersFile.getParent());
            Files.writeString(providersFile, """
                    { "providers": [ {
                      "id": "cli-test",
                      "displayName": "CLI Test",
                      "urlTemplate": "http://127.0.0.1:1/{z}/{x}/{y}.png",
                      "minZoom": 0,
                      "maxZoom": 19,
                      "themes": [ { "id": "t", "displayName": "T" } ],
                      "attribution": "test",
                      "bulkDownload": true
                    } ] }
                    """);
            assertTrue(providersJson().contains("cli-test"), "merged in with no --providers at all");
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    /** {@code providers --json}, captured, with the default file wherever {@code user.home} points. */
    private static String providersJson() {
        final PrintStream originalOut = System.out;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            assertEquals(0, TilesCli.run(new String[]{"providers", "--json"}));
        } finally {
            System.setOut(originalOut);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("usage lists --providers")
    void usageListsProvidersOption() {
        final PrintStream originalOut = System.out;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            assertEquals(2, TilesCli.run(new String[]{}));
        } finally {
            System.setOut(originalOut);
        }
        assertTrue(captured.toString(StandardCharsets.UTF_8).contains("--providers"));
    }

    @Test
    @DisplayName("stats prints its numbers in Locale.ROOT, not the Czech default")
    void statsNumbersAreLocaleIndependentUnderCzech(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        for (final int[] xy : Mercator.range(new LonLatBounds(14.0, 50.0, 15.0, 51.0), 8)) {
            cache.store(new TileKey("osm", "standard", 8, xy[0], xy[1]), png(), null);
        }
        final long totalFiles = cache.statistics().stream().mapToLong(TileCache.ThemeStats::files).sum();
        final long totalBytes = cache.statistics().stream().mapToLong(TileCache.ThemeStats::bytes).sum();
        final String expected = String.format(Locale.ROOT, "total %,d files, %,.2f GB",
                totalFiles, totalBytes / 1_073_741_824.0);

        final Locale original = Locale.getDefault();
        Locale.setDefault(new Locale("cs", "CZ"));
        final PrintStream originalOut = System.out;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            assertEquals(0, TilesCli.run(new String[]{"stats", "--cache", dir.toString()}));
        } finally {
            System.setOut(originalOut);
            Locale.setDefault(original);
        }
        final String output = captured.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains(expected), () -> "expected \"" + expected + "\" in:" + System.lineSeparator() + output);
    }

    @Test
    @DisplayName("a key comes from WTF_KEY_<PROVIDER>, and the downloads say who is asking")
    void environmentAndUserAgent() {
        final ProviderCatalog catalog = ProviderCatalog.builtIn();
        assertEquals("WTF_KEY_MAPY_COM", TilesCli.keyVariable(catalog.require("mapy-com")));
        final String agent = TilesCli.userAgent();
        assertTrue(agent.startsWith("WTF-tiles/"), agent);
        assertTrue(agent.contains(TileDownloader.CONTACT), agent);
    }
}
