package net.trdlo.wtf.tiles;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The map language in the cache (D25). Mapy.com letters its map in the language asked for, and a
 * cache that did not know it served a tile fetched in Czech to everybody who asked in German.
 */
class MapLanguageTest {

    private static final ProviderCatalog CATALOG = ProviderCatalog.builtIn();
    private static final Provider MAPY = CATALOG.require("mapy-com");
    private static final Provider OSM = CATALOG.require("osm");

    private static byte[] png() throws IOException {
        final java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        final var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("a map lettered in a language has it in its tiles, and a map that is not does not")
    void onlyWhereTheAddressTakesIt() {
        assertEquals("cs", MAPY.key("outdoor", "cs", 10, 550, 350).lang());
        assertNull(OSM.key(OSM.themes().getFirst().id(), "cs", 10, 550, 350).lang(),
                "one OpenStreetMap tile, whatever language it was looked at in");
        assertEquals(OSM.key(OSM.themes().getFirst().id(), "cs", 10, 550, 350),
                OSM.key(OSM.themes().getFirst().id(), "de", 10, 550, 350));
        assertNotEquals(MAPY.key("outdoor", "cs", 10, 550, 350), MAPY.key("outdoor", "de", 10, 550, 350));
        assertThrows(IllegalArgumentException.class, () -> MAPY.key("outdoor", null, 10, 550, 350),
                "a map that needs a language is not fetched in a guessed one");
    }

    /**
     * Where nothing names a language, a map is in its provider's own (D40) – decided by
     * the definition, so that a file means the same print on every machine.
     */
    @Test
    @DisplayName("a provider's own language is the first it lists, or English for one that lists none")
    void theProvidersOwnLanguage() {
        assertEquals("cs", MAPY.defaultLanguage(), "Mapy.com lists Czech first, its server's own default");
        final Provider german = new Provider(MAPY.id(), MAPY.displayName(), MAPY.urlTemplate(), MAPY.auth(),
                MAPY.minZoom(), MAPY.maxZoom(), MAPY.expectedFormat(), MAPY.themes(), MAPY.subdomains(),
                MAPY.attribution(), MAPY.signupUrl(), MAPY.signupHint(), MAPY.termsUrl(), MAPY.bulkDownload(),
                MAPY.quotaNote(), MAPY.coverage(), List.of("de", "en"), MAPY.logo());
        assertEquals("de", german.defaultLanguage(), "the first it lists, which its definition decides");
        assertEquals("en", OSM.defaultLanguage(), "one that lists none");
    }

    @Test
    @DisplayName("the address asks in the tile's own language")
    void theAddressFollowsTheKey() {
        assertTrue(MAPY.url(MAPY.key("outdoor", "de", 10, 550, 350), "K").endsWith("?lang=de"));
        assertThrows(IllegalArgumentException.class,
                () -> MAPY.url(new TileKey("mapy-com", "outdoor", 10, 550, 350), "K"));
    }

    @Test
    @DisplayName("a neighbor, a parent and a child are in the same language")
    void relativesKeepTheLanguage() {
        final TileKey key = MAPY.key("outdoor", "cs", 10, 550, 350);
        assertEquals("cs", key.at(11, 1100, 700).lang());
        assertEquals("mapy-com/outdoor@cs/10/550/350", key.toString());
    }

    @Test
    @DisplayName("a language is one folder name and nothing else")
    void aLanguageIsNotAPath() {
        for (final String bad : List.of("", "c", "..", "cs/..", "cs\\x", "cs@de", "c s")) {
            assertThrows(IllegalArgumentException.class, () -> new TileKey("mapy-com", "outdoor", bad, 1, 0, 0),
                    () -> "should have refused '" + bad + "'");
        }
        assertDoesNotThrow(() -> new TileKey("mapy-com", "outdoor", "pt-BR", 1, 0, 0));
    }

    @Test
    @DisplayName("each language of a map is a folder of its own, <theme>@<lang>")
    void oneFolderPerLanguage(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final TileKey cs = MAPY.key("outdoor", "cs", 10, 550, 350);
        final TileKey de = MAPY.key("outdoor", "de", 10, 550, 350);

        final Path stored = cache.store(cs, png(), null);
        assertEquals(dir.resolve("mapy-com").resolve("outdoor@cs").resolve("10"), stored.getParent());
        assertTrue(cache.contains(cs));
        assertFalse(cache.contains(de), "the Czech tile is not the German one");
        assertFalse(cache.contains(new TileKey("mapy-com", "outdoor", 10, 550, 350)),
                "nor the tile of a map with no language");

        cache.store(de, png(), null);
        assertTrue(Files.isDirectory(dir.resolve("mapy-com").resolve("outdoor@de")));
        assertEquals(dir.resolve("mapy-com").resolve("outdoor@pt-br").resolve("10"),
                cache.directoryOf("mapy-com", "outdoor", "pt-BR", 10), "lower case, like the ids");
    }

    @Test
    @DisplayName("the statistics name the map and its language apart")
    void statisticsSplitTheFolder(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        cache.store(MAPY.key("outdoor", "cs", 10, 550, 350), png(), null);
        cache.store(MAPY.key("outdoor", "de", 10, 550, 350), png(), null);
        cache.store(new TileKey("osm", "standard", 10, 550, 350), png(), null);

        final List<TileCache.ThemeStats> stats = cache.statistics();
        assertEquals(List.of("mapy-com/outdoor@cs", "mapy-com/outdoor@de", "osm/standard@null"),
                stats.stream().map(s -> s.providerId() + "/" + s.themeId() + "@" + s.lang()).toList());
    }

    @Test
    @DisplayName("forgetting a map's empty tiles forgets them in every language, and in no other map")
    void forgetEmptyInEveryLanguage(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final TileKey cs = MAPY.key("outdoor", "cs", 10, 550, 350);
        final TileKey de = MAPY.key("outdoor", "de", 10, 550, 350);
        final TileKey winter = MAPY.key("winter", "cs", 10, 550, 350);
        cache.recordEmpty(cs);
        cache.recordEmpty(de);
        cache.recordEmpty(winter);

        assertEquals(2, cache.forgetEmpty("mapy-com", "outdoor"));
        assertFalse(cache.isKnownEmpty(cs));
        assertFalse(cache.isKnownEmpty(de));
        assertTrue(cache.isKnownEmpty(winter));
    }
}
