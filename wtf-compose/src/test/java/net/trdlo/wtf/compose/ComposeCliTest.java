package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.ConsoleOutput;
import net.trdlo.wtf.tiles.ProviderCatalog;
import net.trdlo.wtf.tiles.Refusal;
import net.trdlo.wtf.tiles.TileCache;
import net.trdlo.wtf.tiles.TileKey;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** The command line, against a project in a {@code @TempDir}. */
class ComposeCliTest {

    /**
     * A home of the class's own, with no {@code .wtf} in it: the command line merges
     * {@code ~/.wtf/providers.json} unless {@code --providers} names another, and a developer's own
     * file that defines {@code cli-test} or hides or overrides a shipped provider is not what CI,
     * which has none, sees. The test of the default file points it at a home of its own again.
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
    @DisplayName("a bad option is a bad argument, exit 2, and not a stack trace")
    void badOptionExitsTwo() {
        assertEquals(2, ComposeCli.run(new String[]{"layout", "--nope"}),
                "parsing happens inside the handling, not before it");
        assertEquals(2, ComposeCli.run(new String[]{"layout", "--zoom"}), "an option without its value");
    }

    @Test
    @DisplayName("~/.wtf/providers.json is merged in by default, and --providers can point elsewhere")
    void providersFileIsMerged(@TempDir final Path home) throws Exception {
        final String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            final Path project = home.resolve("sample.wtf");
            WtfFile.sample("cli-test", "t", 14, 50, 15, 51, 10, 200).write(project);

            assertEquals(2, ComposeCli.run(new String[]{"layout", project.toString()}),
                    "unknown to the shipped catalog, and nothing is at the default file yet");

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
            assertEquals(0, ComposeCli.run(new String[]{"layout", project.toString()}),
                    "merged in from the default file, with no --providers at all");
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    @DisplayName("--providers names a file of its own, elsewhere")
    void providersOptionNamesAnotherFile(@TempDir final Path dir) throws Exception {
        final Path project = dir.resolve("sample.wtf");
        WtfFile.sample("cli-test", "t", 14, 50, 15, 51, 10, 200).write(project);
        assertEquals(2, ComposeCli.run(new String[]{"layout", project.toString()}),
                "unknown to the shipped catalog alone");

        final Path providersFile = dir.resolve("providers.json");
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
        assertEquals(0, ComposeCli.run(new String[]{"layout", project.toString(),
                "--providers", providersFile.toString()}));
    }

    @Test
    @DisplayName("inventory, --dry-run and export refuse a print with nothing in it under every --missing")
    void nothingThereIsRefusedByEveryCommand(@TempDir final Path dir) throws Exception {
        final Path providersFile = dir.resolve("providers.json");
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
        final Path project = dir.resolve("sea.wtf");
        WtfFile.sample("cli-test", "t", 14, 50, 15, 51, 10, 200).write(project);
        final Path cacheDir = dir.resolve("cache");
        final TileCache cache = new TileCache(cacheDir);
        final ComposeJob job = WtfFile.read(project).toJob(ProviderCatalog.load(providersFile), dir);
        final List<TileKey> keys = List.copyOf(TilePlan.of(job).distinctKeys());
        for (final TileKey key : keys) {
            cache.recordEmpty(key);         // the provider has answered "no map here" for every one
        }
        final Path out = dir.resolve("out");
        final String[] where = {"--cache", cacheDir.toString(), "--providers", providersFile.toString(),
                "--out", out.toString()};
        final String refusal = "CLI Test says that none of these " + keys.size() + " tiles exist";

        // Said before an export is, and the export's own refusal, word for word
        for (final String[] command : List.of(new String[]{"inventory", project.toString()},
                new String[]{"inventory", project.toString(), "--json"},
                new String[]{"export", project.toString(), "--dry-run"})) {
            final Said said = runSaying(with(command, where));
            assertEquals(1, said.exit(), () -> String.join(" ", command));
            assertTrue(said.err().contains(refusal), said.err());
        }
        // And no policy lets it through: they say what goes in a gap, and this has none
        for (final MissingTilePolicy policy : MissingTilePolicy.values()) {
            final String missing = policy.name().toLowerCase(Locale.ROOT);
            assertEquals(1, runSaying(with(new String[]{"export", project.toString(), "--dry-run",
                    "--missing", missing}, where)).exit(), missing);
            final Said export = runSaying(with(new String[]{"export", project.toString(),
                    "--missing", missing}, where));
            assertEquals(1, export.exit(), missing);
            assertTrue(export.err().contains(refusal), export.err());
        }
        assertFalse(Files.exists(out), "nothing is written, under any policy");

        // One real tile beside the sea is a coast: counted, dry-run and exported as ever
        cache.store(keys.getFirst(), png(), null);
        assertEquals(0, runSaying(with(new String[]{"inventory", project.toString()}, where)).exit());
        assertEquals(0, runSaying(with(new String[]{"export", project.toString(), "--dry-run"}, where)).exit());
        assertEquals(0, runSaying(with(new String[]{"export", project.toString()}, where)).exit());
        assertTrue(Files.isDirectory(out), "and the export is written");
    }

    @Test
    @DisplayName("a ring across the antimeridian is a bad argument to every command, exit 2, with D22's reason")
    void aRingAcrossTheAntimeridianIsRefused(@TempDir final Path dir) throws Exception {
        final Path providersFile = dir.resolve("providers.json");
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
        // Fiji's Vanua Levu as a program that does not cut at ±180° writes it, as the subject
        final WtfFile s = WtfFile.sample("cli-test", "t", 177, -19, 180, -15, 8, 500);
        final Geometry island = Shapes.polygon(new Coordinate[]{new Coordinate(179.0, -16.0),
                new Coordinate(-179.5, -16.2), new Coordinate(-179.8, -17.0), new Coordinate(179.2, -16.9)});
        final Path project = dir.resolve("fiji.wtf");
        new WtfFile(s.format(), s.title(), s.notes(), s.saved(), s.source(), s.area(),
                WtfFile.multiRingOf(island, "polygon", "fiji.geojson"), s.refine(), s.regions(), s.print(),
                s.medium(), s.export()).write(project);
        final String[] where = {"--providers", providersFile.toString(), "--cache", dir.resolve("cache").toString(),
                "--out", dir.resolve("out").toString()};

        for (final String[] command : List.of(new String[]{"layout", project.toString()},
                new String[]{"inventory", project.toString()},
                new String[]{"export", project.toString(), "--dry-run"},
                new String[]{"export", project.toString()})) {
            final Said said = runSaying(with(command, where));
            assertEquals(2, said.exit(), () -> String.join(" ", command));
            assertTrue(said.err().contains("The subject shape crosses the antimeridian between longitudes 179"
                    + " and -179.5"), said.err());
            assertTrue(said.err().contains("the print would be two, one at each end of the map"), said.err());
        }
        assertFalse(Files.exists(dir.resolve("out")), "nothing is written");

        // The whole world is a range like any other
        final Path world = dir.resolve("world.wtf");
        WtfFile.sample("cli-test", "t", -180, -85, 180, 85, 2, 1000).write(world);
        assertEquals(0, runSaying(with(new String[]{"layout", world.toString()}, where)).exit());
    }

    /**
     * Every tile of the print a WebP one, which no decoder here reads: the export is a refused
     * job, as compose-module.md §11 says, with the engine's English on stderr and nothing printed.
     */
    @Test
    @DisplayName("a tile no decoder can read is a refused job, exit 2, said in English")
    void anUnreadableTileIsARefusedJob(@TempDir final Path dir) throws Exception {
        final Path providersFile = dir.resolve("providers.json");
        Files.writeString(providersFile, """
                { "providers": [ {
                  "id": "cli-test",
                  "displayName": "CLI Test",
                  "urlTemplate": "http://127.0.0.1:1/{z}/{x}/{y}.webp",
                  "minZoom": 0,
                  "maxZoom": 19,
                  "themes": [ { "id": "t", "displayName": "T" } ],
                  "attribution": "test",
                  "bulkDownload": true
                } ] }
                """);
        final Path project = dir.resolve("webp.wtf");
        WtfFile.sample("cli-test", "t", 14, 50, 15, 51, 10, 200).write(project);
        final Path cacheDir = dir.resolve("cache");
        final TileCache cache = new TileCache(cacheDir);
        final ComposeJob job = WtfFile.read(project).toJob(ProviderCatalog.load(providersFile), dir);
        final byte[] webp = "RIFF\0\0\0\0WEBPVP8 not really a picture".getBytes(StandardCharsets.US_ASCII);
        for (final TileKey key : TilePlan.of(job).distinctKeys()) {
            cache.store(key, webp, null);
        }
        final Path out = dir.resolve("out");

        final Said said = runSaying("export", project.toString(), "--cache", cacheDir.toString(),
                "--providers", providersFile.toString(), "--out", out.toString());
        assertEquals(2, said.exit(), said.err());
        assertTrue(said.err().contains("[compose] WTF cannot read tile cli-test/t/10/"), said.err());
        assertTrue(said.err().contains(" (format WEBP), so it cannot be printed. Choose another map."), said.err());
        try (final var left = Files.list(out)) {
            assertEquals(List.of(), left.toList(), "nothing is printed");
        }
    }

    /**
     * The closing line of an export says how many images went into the PDF decoded rather
     * than copied, and why – here an alpha channel, which a PDF wants as a mask of its own and five
     * themes of sixteen have – and says nothing more when every tile was copied.
     */
    @Test
    @DisplayName("an export's last line says how many images it decoded and why, and nothing when it copied all")
    void theClosingLineSaysWhatWasDecoded(@TempDir final Path dir) throws Exception {
        final Path providersFile = dir.resolve("providers.json");
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
        final Path project = dir.resolve("sample.wtf");
        WtfFile.sample("cli-test", "t", 14, 50, 15, 51, 10, 200).write(project);
        final ComposeJob job = WtfFile.read(project).toJob(ProviderCatalog.load(providersFile), dir);

        final Printed decoded = exportOf(project, providersFile, job, dir.resolve("rgba"),
                png(java.awt.image.BufferedImage.TYPE_INT_ARGB));
        assertEquals(0, decoded.exit(), decoded.err());
        // One image: every tile has the same bytes, and a PDF holds them once
        assertTrue(decoded.out().contains("; images decoded rather than copied: 1 of 1 (alpha channel: 1)"
                + System.lineSeparator()), decoded.out());

        final Printed copied = exportOf(project, providersFile, job, dir.resolve("rgb"),
                png(java.awt.image.BufferedImage.TYPE_INT_RGB));
        assertEquals(0, copied.exit(), copied.err());
        assertTrue(copied.out().contains(" tile placements, "), copied.out());
        assertFalse(copied.out().contains("decoded"), copied.out());
    }

    /**
     * The command line has no language: the layout and the attribution it writes beside the print
     * are the engine's English, their numbers in {@link Locale#ROOT} on a Czech machine too, and a
     * provider without terms has no line for them.
     */
    @Test
    @DisplayName("an export's layout and attribution are the engine's English, whatever the machine speaks")
    void theFilesBesideThePrintAreEnglish(@TempDir final Path dir) throws Exception {
        final Path providersFile = dir.resolve("providers.json");
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
        final Path project = dir.resolve("sample.wtf");
        WtfFile.sample("cli-test", "t", 14, 50, 15, 51, 10, 1200).write(project);
        final ComposeJob job = WtfFile.read(project).toJob(ProviderCatalog.load(providersFile), dir);
        assertEquals(2, job.layout().count(), "two strips, so how they hang is said too");

        final Locale original = Locale.getDefault();
        Locale.setDefault(Locale.of("cs", "CZ"));
        final Printed printed;
        try {
            printed = exportOf(project, providersFile, job, dir, png());
        } finally {
            Locale.setDefault(original);
        }
        assertEquals(0, printed.exit(), printed.err());
        final List<String> layout = Files.readAllLines(dir.resolve("out").resolve("sample-layout.txt"),
                StandardCharsets.UTF_8);
        assertEquals(List.of("WTF layout for sample", "915 mm roll, vertical strips", "zoom 10, one region"),
                layout.subList(0, 3));
        assertTrue(layout.get(3).matches("Scale 1:\\d{1,3}(,\\d{3})+, \\d+ DPI"),
                () -> "thousands apart by a comma, as Locale.ROOT has them: " + layout.get(3));
        final PrintRect print = job.layout().print();
        assertTrue(layout.contains(String.format(Locale.ROOT,
                        "Print %.0f x %.0f mm, 2 sheet(s) in 2 column(s) x 1 row(s), overlap 15.0 mm",
                        print.widthMm(), print.heightMm())), () -> String.join("\n", layout));
        assertTrue(layout.contains("Hanging: the sheet to the right and the sheet below go on top."),
                () -> String.join("\n", layout));
        assertEquals(List.of("test", "Theme: t", "Produced by WTF."),
                Files.readAllLines(dir.resolve("out").resolve("sample-attribution.txt"), StandardCharsets.UTF_8),
                "the provider's credit, its map, and no terms for a provider that has none");
    }

    /**
     * A project whose map is lettered in a language and that names none is exported in its
     * provider's own (D40), the first language its definition lists, and not refused for naming
     * none. The tiles cached in that language print, and tiles cached in another are not
     * taken for them.
     */
    @Test
    @DisplayName("a lettered map without source.lang is exported in its provider's first language")
    void aLetteredMapWithoutALanguageIsExportedInItsProvidersOwn(@TempDir final Path dir) throws Exception {
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
                } ] }
                """);
        final WtfFile s = WtfFile.sample("lettered", "t", 14, 50, 15, 51, 10, 200);
        final Path project = dir.resolve("lettered.wtf");
        new WtfFile(s.format(), s.title(), s.notes(), s.saved(), new WtfFile.Source("lettered", "t", null),
                s.area(), s.subject(), s.refine(), s.regions(), s.print(), s.medium(), s.export()).write(project);

        // The tiles the print needs, in a language named here rather than by the rule under test
        final ComposeJob job = WtfFile.read(project).toJob(ProviderCatalog.load(providersFile), dir);
        final List<TileKey> english = List.copyOf(TilePlan.of(TestJobs.from(job).lang("en").build()).distinctKeys());
        final Path cacheDir = dir.resolve("cache");
        final TileCache cache = new TileCache(cacheDir);
        for (final TileKey key : english) {
            cache.store(key, png(), null);
        }
        final Path out = dir.resolve("out");
        final String[] where = {"--cache", cacheDir.toString(), "--providers", providersFile.toString(),
                "--out", out.toString()};
        final Said inEnglish = runSaying(with(new String[]{"export", project.toString()}, where));
        assertEquals(4, inEnglish.exit(), "cached in English, and the print is in German: " + inEnglish.err());

        for (final TileKey key : english) {
            cache.store(new TileKey(key.providerId(), key.themeId(), "de", key.zoom(), key.x(), key.y()),
                    png(), null);
        }
        final Said inGerman = runSaying(with(new String[]{"export", project.toString()}, where));
        assertEquals(0, inGerman.exit(), inGerman.err());
        try (final Stream<Path> written = Files.list(out)) {
            assertTrue(written.anyMatch(f -> f.getFileName().toString().endsWith(".pdf")), "and it is printed");
        }
    }

    @Test
    @DisplayName("sample names no map language, so the map is in its provider's first, Czech for Mapy.com")
    void theSampleNamesNoLanguage(@TempDir final Path dir) throws Exception {
        final Path project = dir.resolve("sample.wtf");
        assertEquals(0, ComposeCli.run(new String[]{"sample", "--out", project.toString(),
                "--bbox", "14,50,15,51"}));
        final WtfFile sample = WtfFile.read(project);
        assertNull(sample.source().lang(), "a Czech written for every provider is no rule of the provider's");
        assertEquals("cs", sample.toJob(ProviderCatalog.builtIn(), dir).lang(), "Mapy.com lists Czech first");
    }

    /**
     * {@code inventory}'s line and {@code export --dry-run}'s write their counts in
     * {@link Locale#ROOT} under a Czech default locale, on a console {@code ConsoleOutput} leaves
     * alone and on one it wraps, code page 437's, alike.
     */
    @Test
    @DisplayName("inventory and --dry-run print the same digits under a Czech locale, wrapped or not")
    void theCountsAreRootOnEveryConsole(@TempDir final Path dir) throws Exception {
        final Path providersFile = dir.resolve("providers.json");
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
        final Path project = dir.resolve("sample.wtf");
        WtfFile.sample("cli-test", "t", 14, 50, 15, 51, 14, 200).write(project);
        final ComposeJob job = WtfFile.read(project).toJob(ProviderCatalog.load(providersFile), dir);
        final TilePlan.Plan plan = new Composer().plan(job);
        assertTrue(plan.distinctKeys().size() >= 1000, "enough tiles for a thousands separator");
        final String counts = String.format(Locale.ROOT, "%,d tile placements, %,d distinct tiles",
                plan.placements(), plan.distinctKeys().size());
        final String dryCounts = String.format(Locale.ROOT, "%,d placements, %,d distinct tiles",
                plan.placements(), plan.distinctKeys().size());
        final String[] where = {"--cache", dir.resolve("cache").toString(),
                "--providers", providersFile.toString()};

        final Locale original = Locale.getDefault();
        Locale.setDefault(Locale.of("cs", "CZ"));
        try {
            for (final Charset console : List.of(StandardCharsets.UTF_8, Charset.forName("IBM437"))) {
                final String inventory = printedOn(console,
                        with(new String[]{"inventory", project.toString()}, where));
                assertTrue(inventory.contains(counts), console + ": " + inventory);
                final String dryRun = printedOn(console,
                        with(new String[]{"export", project.toString(), "--dry-run"}, where));
                assertTrue(dryRun.contains(dryCounts), console + ": " + dryRun);
            }
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    @DisplayName("a zoom past the map's own is refused by name, whatever the provider's range, before a tile is listed")
    void aZoomPastTheMapIsRefused(@TempDir final Path dir) throws Exception {
        // The provider goes to 19, its map only from 5 to 16: the map's are the zooms that count
        final Path providersFile = dir.resolve("providers.json");
        Files.writeString(providersFile, """
                { "providers": [ {
                  "id": "cli-test",
                  "displayName": "CLI Test",
                  "urlTemplate": "http://127.0.0.1:1/{z}/{x}/{y}.png",
                  "minZoom": 0,
                  "maxZoom": 19,
                  "themes": [ { "id": "t", "displayName": "T", "minZoom": 5, "maxZoom": 16 } ],
                  "attribution": "test",
                  "bulkDownload": true
                } ] }
                """);
        final ProviderCatalog catalog = ProviderCatalog.load(providersFile);
        final String[] where = {"--providers", providersFile.toString(), "--cache", dir.resolve("cache").toString()};
        // Czechia, which at zoom 19 is about 70 million tiles
        for (final int zoom : new int[]{17, 19, 4}) {
            final Path project = dir.resolve("z" + zoom + ".wtf");
            WtfFile.sample("cli-test", "t", 12.09, 48.55, 18.86, 51.06, zoom, 2000).write(project);

            final Refusal r = assertThrows(Refusal.class, () -> WtfFile.read(project).toJob(catalog, dir));
            assertEquals(ComposeReason.ZOOM_OUT_OF_RANGE, r.reason());
            assertEquals(List.of("CLI Test", "t", 5, 16, zoom), r.args());

            final String said = "The map CLI Test / t has zooms 5 to 16, not " + zoom;
            for (final String[] command : List.of(new String[]{"layout", project.toString()},
                    new String[]{"inventory", project.toString()},
                    new String[]{"export", project.toString(), "--dry-run"},
                    new String[]{"export", project.toString()})) {
                final Said refused = assertTimeoutPreemptively(Duration.ofSeconds(30),
                        () -> runSaying(with(command, where)), () -> String.join(" ", command));
                assertEquals(2, refused.exit(), () -> String.join(" ", command));
                assertTrue(refused.err().contains(said), refused.err());
            }
        }
        // And the map's highest is a print
        final Path project = dir.resolve("z16.wtf");
        WtfFile.sample("cli-test", "t", 14, 50, 15, 51, 16, 200).write(project);
        assertEquals(16, WtfFile.read(project).toJob(catalog, dir).regions().zoom());
        assertEquals(0, runSaying(with(new String[]{"layout", project.toString()}, where)).exit());
    }

    @Test
    @DisplayName("layout of a print of tens of millions of tiles prints its sheets without listing a tile")
    void layoutDoesNotPlanTheTiles(@TempDir final Path dir) throws Exception {
        final Path providersFile = dir.resolve("providers.json");
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
        // Czechia at zoom 19, which the map has: about 70 million tiles, more than a plan can list
        final Path project = dir.resolve("czechia.wtf");
        WtfFile.sample("cli-test", "t", 12.09, 48.55, 18.86, 51.06, 19, 50_000).write(project);
        final ComposeJob job = WtfFile.read(project).toJob(ProviderCatalog.load(providersFile), dir);

        final Printed printed = assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> runPrinting("layout", project.toString(), "--providers", providersFile.toString()));
        assertEquals(0, printed.exit(), printed.err());
        assertTrue(printed.out().contains(job.layout().describe()), printed.out());
    }

    /**
     * What {@code wtf-compose} prints on stdout, through a console of this charset as its
     * {@code main} sets it up: {@code ConsoleOutput} wraps it, or leaves it alone.
     */
    private static String printedOn(final Charset console, final String... args) {
        final PrintStream originalOut = System.out;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(ConsoleOutput.wrap(new PrintStream(out, true, console)));
            ComposeCli.run(args);
        } finally {
            System.setOut(originalOut);
        }
        return out.toString(console);
    }

    /** {@code wtf-compose export} of the project, every tile it plans in a cache of its own as these bytes. */
    private static Printed exportOf(final Path project, final Path providersFile, final ComposeJob job,
                                    final Path where, final byte[] tile) throws IOException {
        final TileCache cache = new TileCache(where.resolve("cache"));
        for (final TileKey key : TilePlan.of(job).distinctKeys()) {
            cache.store(key, tile, null);
        }
        return runPrinting("export", project.toString(), "--cache", where.resolve("cache").toString(),
                "--providers", providersFile.toString(), "--out", where.resolve("out").toString());
    }

    private static String[] with(final String[] command, final String[] options) {
        return Stream.concat(Stream.of(command), Stream.of(options)).toArray(String[]::new);
    }

    private static byte[] png() throws IOException {
        return png(java.awt.image.BufferedImage.TYPE_INT_RGB);
    }

    private static byte[] png(final int type) throws IOException {
        return TestJobs.png(new java.awt.image.BufferedImage(256, 256, type));
    }

    /** An exit code and what was said on stderr on the way to it. */
    private record Said(int exit, String err) { }

    /** An exit code and what was printed on stdout and said on stderr on the way to it. */
    private record Printed(int exit, String out, String err) { }

    private static Printed runPrinting(final String... args) {
        final PrintStream originalOut = System.out;
        final PrintStream originalErr = System.err;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            exit = ComposeCli.run(args);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Printed(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static Said runSaying(final String... args) {
        final PrintStream originalErr = System.err;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        final int exit;
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            exit = ComposeCli.run(args);
        } finally {
            System.setErr(originalErr);
        }
        return new Said(exit, captured.toString(StandardCharsets.UTF_8));
    }
}
