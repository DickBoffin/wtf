package net.trdlo.wtf.tiles;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ProviderCatalogTest {

    private static final ProviderCatalog CATALOG = ProviderCatalog.builtIn();

    static Stream<Provider> shippedProviders() {
        return CATALOG.all().stream();
    }

    @Test
    @DisplayName("the shipped catalog loads and holds the providers the docs promise")
    void loads() {
        assertTrue(CATALOG.size() >= 15, "expected the full shipped set, got " + CATALOG.size());
        for (final String id : List.of("mapy-com", "swisstopo", "ign-fr", "pdok-nl", "kartverket",
                "basemap-de", "usgs", "gsi-jp", "esri", "maptiler", "thunderforest", "mapbox",
                "stadia", "geoapify", "osm", "opentopomap")) {
            assertTrue(CATALOG.find(id).isPresent(), "missing provider: " + id);
        }
    }

    @ParameterizedTest(name = "{0} is well formed")
    @MethodSource("shippedProviders")
    @DisplayName("every shipped provider passes validation and can build a URL")
    void wellFormed(final Provider p) {
        assertDoesNotThrow(() -> ProviderCatalog.validate(p));
        assertFalse(p.themes().isEmpty());
        assertFalse(p.attribution().isBlank(), p.id() + " must credit its source");
        assertNotNull(p.signupHint(), p.id() + " must tell the user where a key comes from");

        for (final Provider.Theme t : p.themes()) {
            final int zoom = Math.min(10, p.maxZoomOf(t));
            final TileKey key = p.key(t.id(), "cs", zoom, 5, 5);
            final String url = p.url(key, "SECRET");
            assertFalse(url.contains("{"), p.id() + "/" + t.id() + " left a token unresolved: " + url);
            assertTrue(url.startsWith("https://"), p.id() + " must use https");
        }
    }

    @ParameterizedTest(name = "{0} links its terms")
    @MethodSource("shippedProviders")
    @DisplayName("every shipped provider links the terms its tiles are used under")
    void termsLinked(final Provider p) {
        // About and the Providers dialog show a credit beside each provider; without this link
        // there is nothing to read behind it. Each one was fetched and is the provider's own
        // terms or license; providers.md says what has been read of each.
        assertNotNull(p.termsUrl(), p.id() + " has no terms link");
        assertTrue(p.termsUrl().startsWith("https://"), () -> p.id() + " must link its terms over https");
    }

    @Test
    @DisplayName("the shipped file writes its dashes as en dashes, as the rest of the interface does")
    void noEmDashes() throws IOException {
        // The signup hints and notes are shown as they are, so the house style applies to them
        final String json;
        try (final InputStream in = ProviderCatalog.class.getResourceAsStream("providers.json")) {
            assertNotNull(in);
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        final int at = json.indexOf('—');
        assertEquals(-1, at, () -> "an em dash in providers.json: "
                + json.substring(Math.max(0, at - 40), Math.min(json.length(), at + 40)));
    }

    @Test
    @DisplayName("the community-run providers refuse bulk downloading")
    void donatedInfrastructureIsProtected() {
        assertFalse(CATALOG.require("osm").bulkDownload(), "OSM's usage policy rules this out");
        assertFalse(CATALOG.require("opentopomap").bulkDownload());
        assertFalse(CATALOG.require("esri").bulkDownload(), "held back until the terms are read");
        assertFalse(CATALOG.require("mapy-com").bulkDownload(), "its terms forbid storing tiles");
    }

    @Test
    @DisplayName("ArcGIS-style y-before-x templates place the coordinates the right way round")
    void axisOrder() {
        // Kartverket and USGS both use {z}/{y}/{x}; getting this wrong maps somewhere else entirely
        final Provider k = CATALOG.require("kartverket");
        final String url = k.url(new TileKey("kartverket", "topo", 13, 4373, 2427), null);
        assertTrue(url.endsWith("/13/2427/4373.png"), "y must precede x: " + url);

        final Provider u = CATALOG.require("usgs");
        final String usgs = u.url(new TileKey("usgs", "USGSTopo", 13, 1651, 3127), null);
        assertTrue(usgs.endsWith("/13/3127/1651"), "y must precede x: " + usgs);
    }

    @Test
    @DisplayName("a KVP WMTS template is filled just like an XYZ one")
    void kvpWmts() {
        final String url = CATALOG.require("ign-fr")
                .url(new TileKey("ign-fr", "plan-ign", 15, 16594, 11277), null);
        assertTrue(url.contains("TILEMATRIX=15"), url);
        assertTrue(url.contains("TILECOL=16594"), url);
        assertTrue(url.contains("TILEROW=11277"), url);
        assertTrue(url.contains("TILEMATRIXSET=PM"), "PM is the Web Mercator matrix set");
    }

    @Test
    @DisplayName("a theme may override the provider template, format and zoom ceiling")
    void themeOverrides() {
        final Provider maptiler = CATALOG.require("maptiler");
        final Provider.Theme satellite = maptiler.requireTheme("satellite");
        assertEquals(TileFormat.JPEG, maptiler.expectedFormatOf(satellite));
        assertTrue(maptiler.url(new TileKey("maptiler", "satellite", 10, 1, 1), "K")
                .contains("/tiles/satellite/"), "tilesets live under a different path than styles");

        final Provider stadia = CATALOG.require("stadia");
        assertEquals(16, stadia.maxZoomOf(stadia.requireTheme("stamen_watercolor")),
                "Watercolor stops at 16, which caps how large it can be printed");
        assertEquals(20, stadia.maxZoomOf(stadia.requireTheme("outdoors")));
    }

    @Test
    @DisplayName("subdomain rotation is deterministic, so a tile always goes to the same host")
    void subdomains() {
        final Provider otm = CATALOG.require("opentopomap");
        final TileKey key = new TileKey("opentopomap", "default", 12, 2200, 1400);
        final String first = otm.url(key, null);
        assertEquals(first, otm.url(key, null), "same tile, same host");
        assertFalse(first.contains("{s}"));
        assertTrue(first.matches("https://[abc]\\.tile\\.opentopomap\\.org/12/2200/1400\\.png"), first);
    }

    @Test
    @DisplayName("mapy.com aerial is declared JPEG, as measured")
    void mapyAerialIsJpeg() {
        final Provider p = CATALOG.require("mapy-com");
        assertEquals(TileFormat.JPEG, p.expectedFormatOf(p.requireTheme("aerial")));
        assertEquals(TileFormat.PNG, p.expectedFormatOf(p.requireTheme("outdoor")));
        assertEquals(20, p.maxZoomOf(p.requireTheme("aerial")), "Czechia reaches z20");
    }

    @Test
    @DisplayName("Mapy.com's credit is printed with its logo, bundled as an SVG, and a logo WTF lacks is refused")
    void aLogoIsOneWtfBundles() {
        final Provider mapy = CATALOG.require("mapy-com");
        assertEquals("mapy-com.svg", mapy.logo(), "its terms ask for the logo with the attribution");
        final String svg = new String(ProviderCatalog.logo(mapy.logo()), StandardCharsets.UTF_8);
        assertTrue(svg.startsWith("<svg") && svg.contains("<path"), "the file as Mapy.com publishes it");
        assertNull(CATALOG.require("osm").logo(), "every other credit is words only");

        for (final String name : List.of("nonesuch.svg", "../providers.json", "logos/mapy-com.svg", "Mapy-com.svg")) {
            final Provider named = withLogo(keyedBy(Provider.Auth.NONE), name);
            final Refusal r = assertThrows(Refusal.class, () -> ProviderCatalog.validate(named), name);
            assertEquals(ProviderReason.LOGO_UNKNOWN, r.reason());
            assertEquals(List.of("keyed", name), r.args());
            assertThrows(IllegalArgumentException.class, () -> ProviderCatalog.logo(name), name);
        }
        assertNull(withLogo(mapy, " ").logo(), "a blank name is none, as a blank list of languages is");
    }

    private static Provider withLogo(final Provider p, final String logo) {
        return new Provider(p.id(), p.displayName(), p.urlTemplate(), p.auth(), p.minZoom(), p.maxZoom(),
                p.expectedFormat(), p.themes(), p.subdomains(), p.attribution(), p.signupUrl(),
                p.signupHint(), p.termsUrl(), p.bulkDownload(), p.quotaNote(), p.coverage(), p.languages(), logo);
    }

    @Test
    @DisplayName("the redacted template never shows a key")
    void redaction() {
        for (final Provider p : CATALOG.all()) {
            final String redacted = p.redactedTemplate();
            assertFalse(redacted.contains("{key}") && redacted.contains("SECRET"));
        }
        final String s = CATALOG.require("geoapify").redactedTemplate();
        assertFalse(s.matches(".*apiKey=\\{key}.*"), "the key token must be masked: " + s);
    }

    @Test
    @DisplayName("a provider that states no coverage is global, and one that states a box is not")
    void coverageTellsANationalProviderFromAGlobalOne() {
        assertTrue(CATALOG.require("osm").isGlobal(), "OpenStreetMap has the world");
        assertTrue(CATALOG.require("esri").isGlobal());
        assertFalse(CATALOG.require("swisstopo").isGlobal(), "Swisstopo has Switzerland");
        assertFalse(CATALOG.require("usgs").isGlobal());
    }

    @Test
    @DisplayName("every national provider's probe point is inside its own coverage")
    void probePointsAreUsable() {
        for (final Provider p : CATALOG.all()) {
            final LonLatBounds.Point probe = p.probePoint();
            assertTrue(p.coverageOrWorld().contains(probe.lon(), probe.lat()),
                    () -> p.id() + " would test its key on a tile it does not have: " + probe);
        }
    }

    @Test
    @DisplayName("the coverage boxes are the right way round and on the planet")
    void coverageBoxesAreSane() {
        for (final Provider p : CATALOG.all()) {
            final LonLatBounds c = p.coverage();
            if (c == null) {
                continue;
            }
            assertTrue(c.west() >= -180 && c.east() <= 180, () -> p.id() + ": longitude off the map");
            assertTrue(c.south() >= -90 && c.north() <= 90, () -> p.id() + ": latitude off the map");
            assertTrue(c.widthDegrees() > 0 && c.heightDegrees() > 0,
                    () -> p.id() + ": a coverage with no area covers nothing");
        }
    }

    @Test
    @DisplayName("a first world can be drawn from a global provider that needs no key")
    void thereIsAlwaysSomewhereToFetchAFirstWorldFrom() {
        // What Splash.worldSource asks of the catalog. If this ever comes back empty, a first run
        // shows an empty frame and nobody finds out until they install it on a clean machine.
        assertTrue(CATALOG.all().stream()
                        .anyMatch(p -> p.isGlobal() && !p.needsKey() && p.minZoom() <= 2 && p.maxZoom() >= 2),
                "no global, key-free provider is left to fetch a first world from");
    }

    /**
     * {@code {scale}} is where a template puts {@code @2x}, and a theme offers it exactly when its
     * template has the token: Mapy.com's aerial map, which answers {@code 256@2x} with 404, has a
     * template of its own without it.
     */
    @Test
    @DisplayName("{scale} is where @2x goes, and only a theme whose template has it offers @2x")
    void scaleToken() {
        final Provider mapy = CATALOG.require("mapy-com");
        final TileKey basic = mapy.key("basic", "cs", 12, 2211, 1387);
        assertTrue(mapy.offersRetina(mapy.requireTheme("basic")));
        assertEquals("https://api.mapy.com/v1/maptiles/basic/256/12/2211/1387?lang=cs", mapy.url(basic, "k"));
        assertEquals("https://api.mapy.com/v1/maptiles/basic/256@2x/12/2211/1387?lang=cs",
                mapy.url(basic.atScale(2), "k"));

        final TileKey aerial = mapy.key("aerial", "cs", 12, 2211, 1387);
        assertFalse(mapy.offersRetina(mapy.requireTheme("aerial")));
        assertEquals("https://api.mapy.com/v1/maptiles/aerial/256/12/2211/1387?lang=cs", mapy.url(aerial, "k"));
        assertThrows(IllegalArgumentException.class, () -> mapy.url(aerial.atScale(2), "k"),
                "a scale the map does not have is not asked for");

        final Provider maptiler = CATALOG.require("maptiler");
        assertEquals("https://api.maptiler.com/maps/topo-v2/256/12/2211/1387@2x.png?key=k",
                maptiler.url(maptiler.key("topo-v2", null, 12, 2211, 1387).atScale(2), "k"),
                "the 256 px tiles, at @2x; without 256 MapTiler sends 512 px tiles drawn a zoom deeper");
        assertFalse(CATALOG.require("osm").offersRetina(CATALOG.require("osm").themes().getFirst()));
    }

    /**
     * {@code {key}} puts the key in as one value, wherever the template has it: a key is whatever
     * its provider issued, and an {@code &}, a {@code #}, a {@code +} or a {@code /} in it would
     * otherwise end a parameter, end the address, become a space or split a path. Only what RFC
     * 3986 leaves unreserved goes in as it is, so a key of only that makes the same address as before.
     */
    @Test
    @DisplayName("the key goes into the address percent-encoded, as one value, in a query or a path")
    void theKeyGoesInEncoded() {
        final Provider inQuery = keyedAt("https://tiles.example.com/{z}/{x}/{y}.png?key={key}&style=a");
        final TileKey k = inQuery.key("t", null, 3, 1, 2);
        assertEquals("https://tiles.example.com/3/1/2.png?key=a%20b%26c%2Bd%23e%2Ff%25g&style=a",
                inQuery.url(k, "a b&c+d#e/f%g"));
        assertEquals("https://tiles.example.com/3/1/2.png?key=Az09-._~&style=a", inQuery.url(k, "Az09-._~"),
                "the unreserved characters, as they are");
        assertEquals("https://tiles.example.com/3/1/2.png?key=kl%C3%AD%C4%8D&style=a", inQuery.url(k, "klíč"),
                "anything else as its UTF-8 bytes");
        assertEquals("https://tiles.example.com/3/1/2.png?key=&style=a", inQuery.url(k, null), "no key, nothing");

        final Provider inPath = keyedAt("https://tiles.example.com/{key}/{z}/{x}/{y}.png");
        assertEquals("https://tiles.example.com/a%2Fb%20c/3/1/2.png", inPath.url(k, "a/b c"),
                "one segment of the path, whatever is in it");

        final Provider geoapify = CATALOG.require("geoapify");
        assertTrue(geoapify.url(geoapify.key("osm-bright", null, 3, 1, 2), "k+1 ").endsWith("?apiKey=k%2B1%20"),
                "a shipped provider's, as any other's");
    }

    /** A provider of one map whose address is {@code template}, carrying the key in it. */
    private static Provider keyedAt(final String template) {
        return new Provider("test", "Test", template, new Provider.Auth(Provider.AuthKind.QUERY, "key"), 0, 19, null,
                List.of(new Provider.Theme("t", "T", null, null, null, null, false, null)),
                null, "test", null, null, null, true, null, null, null, null);
    }

    @Test
    @DisplayName("a tile's scale is 1 or 2, and its neighbors keep it")
    void aTilesScale() {
        final TileKey k = new TileKey("p", "t", null, 3, 1, 2, 2);
        assertEquals(512, k.pixels());
        assertEquals(2, k.at(4, 2, 4).scale(), "a child of a @2x tile is a @2x tile");
        assertEquals("p/t/3/1/2@2x", k.toString());
        assertEquals(256, k.atScale(1).pixels());
        assertThrows(IllegalArgumentException.class, () -> new TileKey("p", "t", null, 3, 1, 2, 3));
    }

    @Test
    @DisplayName("an unknown template token is refused at load time, not at request time")
    void unknownTokenRejected() {
        final Provider bad = new Provider("bad", "Bad", "https://x/{zoom}/{x}/{y}.png",
                null, 0, 19, null,
                List.of(new Provider.Theme("t", "T", null, null, null, null, false, null)),
                null, "none", null, null, null, true, null, null, null, null);
        final IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> ProviderCatalog.validate(bad));
        assertTrue(ex.getMessage().contains("{zoom}"), ex.getMessage());
    }

    @Test
    @DisplayName("a user file overrides a shipped provider by id and adds new ones")
    void userFileMerges(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("providers.json");
        Files.writeString(file, """
                {"providers": [
                  {"id": "osm", "displayName": "My OSM mirror",
                   "urlTemplate": "https://tiles.example.org/{z}/{x}/{y}.png",
                   "minZoom": 0, "maxZoom": 18, "attribution": "mine", "bulkDownload": true,
                   "themes": [{"id": "standard", "displayName": "Standard"}]},
                  {"id": "mine", "displayName": "Something local",
                   "urlTemplate": "https://local.example/{z}/{x}/{y}.png",
                   "minZoom": 0, "maxZoom": 16, "attribution": "mine",
                   "themes": [{"id": "only", "displayName": "Only"}]}
                ]}
                """);
        final ProviderCatalog merged = ProviderCatalog.load(file);
        assertEquals("My OSM mirror", merged.require("osm").displayName(), "replaced by id");
        assertTrue(merged.require("osm").bulkDownload(), "a mirror the user owns may be bulk-fetched");
        assertTrue(merged.find("mine").isPresent(), "added");
        assertTrue(merged.find("mapy-com").isPresent(), "untouched entries survive");
    }

    @Test
    @DisplayName("a broken user file is ignored loudly rather than stopping the application")
    void brokenUserFileIsSurvivable(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("providers.json");
        Files.writeString(file, "{ this is not json");
        final ProviderCatalog merged = assertDoesNotThrow(() -> ProviderCatalog.load(file));
        assertTrue(merged.find("mapy-com").isPresent(), "the built-ins still load");
    }

    /**
     * An id becomes a folder in the tile cache, and a hand-edited file reaches the catalog without
     * passing the store's check. So the catalog checks too, and one bad entry costs that entry.
     */
    @Test
    @DisplayName("an entry whose id or map id is not one folder name is dropped at load")
    void idsThatWouldLeaveTheCacheAreDropped(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("providers.json");
        Files.writeString(file, """
                {"providers": [
                  {"id": "../escape", "displayName": "Up and out", "attribution": "x",
                   "urlTemplate": "https://x.example/{z}/{x}/{y}.png", "themes": [{"id": "t", "displayName": "T"}]},
                  {"id": "sneaky", "displayName": "Down and out", "attribution": "x",
                   "urlTemplate": "https://x.example/{z}/{x}/{y}.png", "themes": [{"id": "x/../../y", "displayName": "T"}]},
                  {"id": "twice", "displayName": "One folder for two", "attribution": "x",
                   "urlTemplate": "https://x.example/{theme}/{z}/{x}/{y}.png",
                   "themes": [{"id": "topo", "displayName": "T"}, {"id": "TOPO", "displayName": "T"}]},
                  {"id": "aux", "displayName": "A device", "attribution": "x",
                   "urlTemplate": "https://x.example/{z}/{x}/{y}.png", "themes": [{"id": "t", "displayName": "T"}]},
                  {"id": "devices", "displayName": "A map for a device", "attribution": "x",
                   "urlTemplate": "https://x.example/{z}/{x}/{y}.png", "themes": [{"id": "Con.x", "displayName": "T"}]},
                  {"id": "fine", "displayName": "Fine", "attribution": "x",
                   "urlTemplate": "https://x.example/{theme}/{z}/{x}/{y}.png", "themes": [{"id": "USGSTopo", "displayName": "T"}]}
                ]}
                """);
        final ProviderCatalog merged = ProviderCatalog.load(file);
        assertTrue(merged.find("../escape").isEmpty());
        assertTrue(merged.find("sneaky").isEmpty());
        assertTrue(merged.find("twice").isEmpty(), "their tiles would share one lower-case folder");
        assertTrue(merged.find("aux").isEmpty(), "Windows makes no folder called aux");
        assertTrue(merged.find("devices").isEmpty(), "nor one called con.x");
        assertTrue(merged.find("fine").isPresent(), "capitals in a map id are the server's, and fine");
    }

    /**
     * The zooms and hosts a hand-edited file gives are held to what the Providers dialog lets
     * through: a z31 reached the Detail step, where the tile count overflows, a hundred million
     * looped, and a null host failed every tile as a server not answering.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unfitZoomsAndHosts")
    @DisplayName("a zoom outside 0–22 or an empty subdomain is refused, with its reason, and dropped at load")
    void zoomsAndHostsAsTheFormHasThem(final String entry, final ProviderReason reason, final List<String> args,
                                       @TempDir final Path dir) throws IOException {
        final Provider p = ProviderCatalog.mapper().readValue(entry, Provider.class);
        final Refusal refused = assertThrows(Refusal.class, () -> ProviderCatalog.validate(p));
        assertEquals(reason, refused.reason());
        assertEquals(args, refused.args());

        final Path file = dir.resolve("providers.json");
        Files.writeString(file, "{\"providers\": [" + entry + "]}");
        assertTrue(ProviderCatalog.load(file).find("hand").isEmpty(), "loaded: " + entry);
    }

    static Stream<Arguments> unfitZoomsAndHosts() {
        final String head = """
                {"id": "hand", "displayName": "By hand", "attribution": "x",
                 "urlTemplate": "https://{s}.x.example/{z}/{x}/{y}.png", """;
        final String map = "\"themes\": [{\"id\": \"t\", \"displayName\": \"T\"";
        return Stream.of(
                Arguments.of(
                        head + "\"minZoom\": 0, \"maxZoom\": 31, " + map + "}]}",
                        ProviderReason.ZOOM_OUT_OF_RANGE, List.of("hand", "31")),
                Arguments.of(
                        head + "\"minZoom\": 0, \"maxZoom\": 100000000, " + map + "}]}",
                        ProviderReason.ZOOM_OUT_OF_RANGE, List.of("hand", "100000000")),
                Arguments.of(
                        head + "\"minZoom\": -1, \"maxZoom\": 19, " + map + "}]}",
                        ProviderReason.ZOOM_OUT_OF_RANGE, List.of("hand", "-1")),
                Arguments.of(
                        head + "\"minZoom\": 0, \"maxZoom\": 19, " + map + ", \"maxZoom\": 23}]}",
                        ProviderReason.MAP_ZOOM_OUT_OF_RANGE, List.of("hand", "t", "23")),
                Arguments.of(
                        head + "\"minZoom\": 0, \"maxZoom\": 19, " + map + ", \"minZoom\": -2}]}",
                        ProviderReason.MAP_ZOOM_OUT_OF_RANGE, List.of("hand", "t", "-2")),
                Arguments.of(
                        head + "\"minZoom\": 0, \"maxZoom\": 19, \"subdomains\": [\"a\", null], " + map + "}]}",
                        ProviderReason.SUBDOMAIN_BLANK, List.of("hand")),
                Arguments.of(
                        head + "\"minZoom\": 0, \"maxZoom\": 19, \"subdomains\": [\"a\", \" \"], " + map + "}]}",
                        ProviderReason.SUBDOMAIN_BLANK, List.of("hand")));
    }

    @Test
    @DisplayName("the edges of 0–22 and named subdomains pass")
    void theFormsZoomsPass() throws IOException {
        final Provider p = ProviderCatalog.mapper().readValue("""
                {"id": "hand", "displayName": "By hand", "attribution": "x", "minZoom": 0, "maxZoom": 22,
                 "subdomains": ["a", "b"], "urlTemplate": "https://{s}.x.example/{z}/{x}/{y}.png",
                 "themes": [{"id": "t", "displayName": "T", "minZoom": 22}]}""", Provider.class);
        assertDoesNotThrow(() -> ProviderCatalog.validate(p));
    }

    private static Provider withIds(final String id, final String themeId) {
        return new Provider(id, "Ids", "https://x.example/{theme}/{z}/{x}/{y}.png", null, 0, 19, null,
                List.of(new Provider.Theme(themeId, "T", null, null, null, null, false, null)),
                null, "x", null, null, null, true, null, null, null, null);
    }

    /**
     * Windows will not make a folder called {@code con}, {@code aux.x} or {@code COM1}, and makes
     * {@code a.} the folder {@code a}: ids that pass as one folder name elsewhere and are none there.
     */
    @ParameterizedTest
    @ValueSource(strings = {"con", "prn", "aux", "nul", "com1", "lpt9", "CON", "Nul", "aux.x", "LPT1.backup",
            "nul.", "a.", "osm.."})
    @DisplayName("a device name, in any case and with an extension, or a trailing dot, is no id")
    void windowsFolderNamesRefused(final String id) {
        final Refusal forProvider = assertThrows(Refusal.class,
                () -> ProviderCatalog.validate(withIds(id.toLowerCase(Locale.ROOT), "t")));
        assertEquals(ProviderReason.ID_UNFIT, forProvider.reason());
        final Refusal forTheme = assertThrows(Refusal.class, () -> ProviderCatalog.validate(withIds("fine", id)));
        assertEquals(ProviderReason.MAP_ID_UNFIT, forTheme.reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"console", "com", "auxiliary", "nul_", "con-x", "a.b", "a_", "a-", "x"})
    @DisplayName("a name that only begins like a device, or has a dot inside, is an id")
    void nearlyDeviceNamesPass(final String id) {
        assertDoesNotThrow(() -> ProviderCatalog.validate(withIds(id, id)));
    }

    @Test
    @DisplayName("an id is still one to sixty-four characters")
    void idLength() {
        assertTrue(Provider.ID.matcher("a".repeat(64)).matches());
        assertFalse(Provider.ID.matcher("a".repeat(65)).matches());
        assertTrue(Provider.THEME_ID.matcher("A".repeat(64)).matches());
        assertFalse(Provider.THEME_ID.matcher("A".repeat(65)).matches());
        assertFalse(Provider.ID.matcher("").matches());
    }

    private static Provider keyedBy(final Provider.Auth auth) {
        return new Provider("keyed", "Keyed", "https://x.example/{z}/{x}/{y}.png", auth, 0, 19, null,
                List.of(new Provider.Theme("t", "T", null, null, null, null, false, null)),
                null, "x", null, null, null, true, null, null, null, null);
    }

    @Test
    @DisplayName("a key sent in a header needs the header's name, and a value with the key in it")
    void aHeaderSaysWhereTheKeyGoes() {
        for (final Provider.Auth bad : List.of(
                new Provider.Auth(Provider.AuthKind.HEADER, null),
                new Provider.Auth(Provider.AuthKind.HEADER, " "),
                new Provider.Auth(Provider.AuthKind.HEADER, "Authorization", "Bearer"))) {
            assertThrows(IllegalArgumentException.class, () -> ProviderCatalog.validate(keyedBy(bad)),
                    () -> "should have refused " + bad);
        }
        assertDoesNotThrow(() -> ProviderCatalog.validate(keyedBy(
                new Provider.Auth(Provider.AuthKind.HEADER, "Authorization", "Stadia-Auth {key}"))));
        assertEquals("Stadia-Auth k", CATALOG.require("stadia").authOrNone().headerValue("k"),
                "Stadia's prefix is in the shipped file, not in the downloader");
        assertEquals("k", CATALOG.require("mapy-com").authOrNone().headerValue("k"));
    }

    /**
     * The example in the design document, read from the document, so that the two cannot drift
     * apart: it writes its enums in lower case, and until they were read in any case it did not load.
     */
    @Test
    @DisplayName("the provider definition the design document shows loads as written")
    void theDesignDocumentsExampleLoads(@TempDir final Path dir) throws IOException {
        final Path doc = Path.of("..", "docs", "design", "tiles-module.md");
        assertTrue(Files.isRegularFile(doc), () -> "read from beside wtf-tiles: " + doc.toAbsolutePath());
        final String text = Files.readString(doc);
        final int section = text.indexOf("### Provider definitions");
        final int start = section < 0 ? -1 : text.indexOf("```json", section);
        final int end = start < 0 ? -1 : text.indexOf("```", start + "```json".length());
        assertTrue(section >= 0 && start > section && end > start,
                "the example is the first json block under \"Provider definitions\", and it has moved");
        final String example = text.substring(start + "```json".length(), end);

        final Provider p = ProviderCatalog.mapper().readValue(example, Provider.class);
        assertDoesNotThrow(() -> ProviderCatalog.validate(p));
        assertEquals(Provider.AuthKind.HEADER, p.authOrNone().kind());
        assertEquals(TileFormat.PNG, p.expectedFormat());
        assertEquals(TileFormat.JPEG, p.expectedFormatOf(p.requireTheme("aerial")));

        // And as a user file, where it replaces the shipped definition of the same id
        final Path file = dir.resolve("providers.json");
        Files.writeString(file, "{\"providers\": " + example + "}");
        assertEquals(p, ProviderCatalog.load(file).require(p.id()));
    }

    @Test
    @DisplayName("an unknown provider or theme names what is known")
    void helpfulErrors() {
        final IllegalArgumentException p =
                assertThrows(IllegalArgumentException.class, () -> CATALOG.require("nope"));
        assertTrue(p.getMessage().contains("mapy-com"), "should list what is available");

        final IllegalArgumentException t = assertThrows(IllegalArgumentException.class,
                () -> CATALOG.require("mapy-com").requireTheme("nope"));
        assertTrue(t.getMessage().contains("outdoor"));
    }
}
