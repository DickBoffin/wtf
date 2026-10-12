package net.trdlo.wtf.tiles;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Everything here runs in a {@code @TempDir}. The real cache holds 242 000 files paid for out of a
 * monthly request allowance, and no test may go anywhere near it.
 */
class TileCacheTest {

    /** The smallest valid PNG this side of hand-assembling one: 1x1, indexed. */
    private static byte[] png() throws IOException {
        final java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        final var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static byte[] jpeg() throws IOException {
        final java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        final var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    /**
     * An id reaches the cache from a command line and a project file as well as from the catalog, and
     * {@code ../..} resolved against the cache is somewhere else on the disk.
     */
    @Test
    @DisplayName("an id is one lower-case folder in the cache, and never a path")
    void anIdIsOneFolder(@TempDir final Path dir) {
        final TileCache cache = new TileCache(dir);
        assertEquals(dir.resolve("usgs").resolve("usgstopo").resolve("10"),
                cache.directoryOf("usgs", "USGSTopo", 10), "the server's capitals, the cache's lower case");
        for (final String bad : List.of("", ".", "..", "../x", "a/b", "a\\b", "C:", "has space")) {
            assertThrows(IllegalArgumentException.class, () -> cache.directoryOf(bad, "t", 10),
                    () -> "should have refused the provider id '" + bad + "'");
            assertThrows(IllegalArgumentException.class, () -> cache.directoryOf("p", bad, 10),
                    () -> "should have refused the map id '" + bad + "'");
        }
    }

    @Test
    @DisplayName("a stored tile is found again, and its extension follows the bytes")
    void storeAndFind(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final TileKey pngKey = new TileKey("mapy-com", "outdoor", 15, 17696, 11100);
        final TileKey jpgKey = new TileKey("mapy-com", "aerial", 15, 17696, 11100);

        assertNull(cache.find(pngKey), "nothing there yet");

        final Path p = cache.store(pngKey, png(), null);
        assertTrue(p.getFileName().toString().endsWith(".png"));
        assertTrue(p.toString().replace('\\', '/').contains("/mapy-com/outdoor/15/"));

        final Path j = cache.store(jpgKey, jpeg(), null);
        assertTrue(j.getFileName().toString().endsWith(".jpg"),
                "the aerial theme is JPEG and the file must say so");

        assertNotNull(cache.find(pngKey));
        assertNotNull(cache.find(jpgKey), "find probes every known extension, not just png");
        assertArrayEquals(png(), cache.read(pngKey));
    }

    @Test
    @DisplayName("a @2x tile is its own file, in a zoom folder beside the usual one, and counted as one")
    void aRetinaTileIsItsOwnFile(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final TileKey one = new TileKey("mapy-com", "basic", "cs", 15, 17696, 11100);
        final TileKey two = one.atScale(2);
        cache.store(one, png(), null);
        assertNull(cache.find(two), "the 256 px tile is not the @2x one");

        final Path p = cache.store(two, png(), null);
        assertTrue(p.toString().replace('\\', '/').contains("/mapy-com/basic@cs/15@2x/"), p.toString());
        assertNotNull(cache.find(one));
        assertNotNull(cache.find(two));

        final List<TileCache.ZoomStats> zooms = cache.statistics().getFirst().zooms();
        assertEquals(List.of(1, 2), zooms.stream().map(TileCache.ZoomStats::scale).toList());
        assertEquals(List.of(15, 15), zooms.stream().map(TileCache.ZoomStats::zoom).toList());
    }

    @Test
    @DisplayName("the extension comes from the bytes even when the theme usually differs")
    void bytesWinOverExpectation(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        // outdoor is normally PNG; this one arrives as JPEG and must still be found
        final TileKey key = new TileKey("mapy-com", "outdoor", 12, 2200, 1400);
        cache.store(key, jpeg(), null);
        final Path found = cache.find(key);
        assertNotNull(found);
        assertTrue(found.getFileName().toString().endsWith(".jpg"));
    }

    @Test
    @DisplayName("a non-image is refused rather than cached as a tile")
    void refusesErrorPages(@TempDir final Path dir) {
        final TileCache cache = new TileCache(dir);
        final TileKey key = new TileKey("mapy-com", "outdoor", 10, 550, 350);
        final byte[] html = "<!DOCTYPE html><html><body>Rate limit exceeded</body></html>"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final IOException ex = assertThrows(IOException.class, () -> cache.store(key, html, null));
        assertTrue(ex.getMessage().contains("not an image"), ex.getMessage());
        assertNull(cache.find(key), "and nothing was written");
    }

    @Test
    @DisplayName("Last-Modified becomes the file time, because for imagery it is the age of the photo")
    void imageryDate(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final TileKey key = new TileKey("mapy-com", "aerial", 15, 17696, 11100);
        final Instant taken = Instant.parse("2018-04-12T15:27:45Z");
        cache.store(key, jpeg(), taken);
        final Instant read = cache.imageryDate(key);
        assertNotNull(read);
        assertEquals(taken.getEpochSecond(), read.getEpochSecond());
    }

    @Test
    @DisplayName("no .part file is left behind by a successful write")
    void noPartLeftOver(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        cache.store(new TileKey("mapy-com", "outdoor", 10, 550, 350), png(), null);
        try (final var walk = Files.walk(dir)) {
            assertTrue(walk.noneMatch(p -> p.toString().endsWith(".part")));
        }
    }

    @Test
    @DisplayName("stale .part files are cleaned up, fresh ones are left alone")
    void cleanPartFiles(@TempDir final Path dir) throws IOException {
        final Path zoomDir = dir.resolve("mapy-com").resolve("outdoor").resolve("10");
        Files.createDirectories(zoomDir);
        final Path stale = Files.writeString(zoomDir.resolve("0550_0350.png.part"), "x");
        final Path fresh = Files.writeString(zoomDir.resolve("0551_0350.png.part"), "x");
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(Duration.ofHours(5))));

        final int removed = new TileCache(dir).cleanPartFiles(Duration.ofHours(1));
        assertEquals(1, removed);
        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(fresh), "a .part from a running download must survive");
    }

    @Test
    @DisplayName("inventory splits wanted tiles into cached and missing")
    void inventory(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final List<TileKey> wanted = List.of(
                new TileKey("mapy-com", "outdoor", 10, 550, 350),
                new TileKey("mapy-com", "outdoor", 10, 551, 350),
                new TileKey("mapy-com", "outdoor", 10, 552, 350));
        cache.store(wanted.get(1), png(), null);

        final TileCache.Inventory inv = cache.inventory(wanted);
        assertEquals(3, inv.needed());
        assertEquals(1, inv.cached().size());
        assertEquals(2, inv.missing().size());
        assertEquals(1 / 3.0, inv.cachedFraction(), 1e-9);
    }

    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("statistics count files, bytes and the populated range per zoom")
    void statistics(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        cache.store(new TileKey("mapy-com", "outdoor", 15, 17696, 11100), png(), null);
        cache.store(new TileKey("mapy-com", "outdoor", 15, 17698, 11102), png(), null);

        final List<TileCache.ThemeStats> stats = cache.statistics();
        assertEquals(1, stats.size());
        assertEquals("mapy-com", stats.getFirst().providerId());
        assertEquals(2, stats.getFirst().files());
        assertTrue(stats.getFirst().bytes() > 0);
        final TileCache.ZoomStats z = stats.getFirst().zooms().getFirst();
        assertEquals(new TileRange(17696, 11100, 17698, 11102), z.range());
    }

    @Test
    @DisplayName("file names are zero-padded to the zoom's digit count, as the original cache is")
    void fileNames() {
        assertEquals("0550_0350.png", TileCache.fileName(10, 550, 350, "png"));
        assertEquals("17696_11100.png", TileCache.fileName(15, 17696, 11100, "png"));
        assertEquals("0_0.png", TileCache.fileName(0, 0, 0, "png"));
        assertEquals("00_00.jpg", TileCache.fileName(4, 0, 0, "jpg"));
    }

    @Test
    @DisplayName("tile names are parsed, and anything else is ignored")
    void parseTileName() {
        assertArrayEquals(new int[]{17696, 11100}, TileCache.parseTileName("17696_11100.png"));
        assertArrayEquals(new int[]{550, 350}, TileCache.parseTileName("0550_0350.jpg"));
        assertNull(TileCache.parseTileName("notes.json"));
        assertNull(TileCache.parseTileName("0550_0350.png.part"));
        assertNull(TileCache.parseTileName("readme.txt"));
        assertNull(TileCache.parseTileName("not_a_tile.png"));
    }

    @Test
    @DisplayName("the cache root is the configured directory, or the platform default - nothing else")
    void rootResolution() {
        assertEquals(Path.of("elsewhere"), TileCache.resolveRoot(Path.of("elsewhere")),
                "a configured directory wins");
        assertEquals(TileCache.platformDefault(), TileCache.resolveRoot(null),
                "and without one there is exactly one answer, not a chain of fallbacks");
        assertTrue(TileCache.platformDefault().isAbsolute(), "the platform default must be absolute");
    }

    @Test
    @DisplayName("a tile the provider says does not exist is recorded, and is not a missing tile")
    void knownEmptyIsNotMissing(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final TileKey sea = new TileKey("mapy-com", "outdoor", 10, 550, 350);
        final TileKey never = new TileKey("mapy-com", "outdoor", 10, 551, 350);
        final TileKey have = new TileKey("mapy-com", "outdoor", 10, 552, 350);

        cache.store(have, png(), null);
        cache.recordEmpty(sea);

        assertTrue(cache.isKnownEmpty(sea));
        assertFalse(cache.isKnownEmpty(never));

        final TileCache.Inventory inv = cache.inventory(List.of(sea, never, have));
        assertEquals(1, inv.cached().size());
        assertEquals(1, inv.empty().size(), "sea is an answer, not a gap");
        assertEquals(1, inv.missing().size(), "only the never-fetched tile is missing");
        assertEquals(0.5, inv.cachedFraction(), 1e-9,
                "the fraction is of obtainable tiles; sea is not obtainable");
    }

    @Test
    @DisplayName("a tile that does not exist is a zero-byte marker beside the tiles, named as the tile would be")
    void emptyIsAMarkerFile(@TempDir final Path dir) throws IOException {
        final TileKey sea = new TileKey("mapy-com", "outdoor", 10, 550, 350);
        new TileCache(dir).recordEmpty(sea);

        final Path marker = dir.resolve("mapy-com").resolve("outdoor").resolve("10").resolve("0550_0350.empty");
        assertTrue(Files.isRegularFile(marker), "one file per tile, where the tile would be");
        assertEquals(0, Files.size(marker));
        assertTrue(new TileCache(dir).isKnownEmpty(sea), "it is a file, not a field");
    }

    @Test
    @DisplayName("forgetting a theme's empty tiles removes its markers and nothing else")
    void forgetEmpty(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final TileKey sea10 = new TileKey("mapy-com", "outdoor", 10, 550, 350);
        final TileKey sea12 = new TileKey("mapy-com", "outdoor", 12, 2200, 1400);
        final TileKey land = new TileKey("mapy-com", "outdoor", 10, 551, 350);
        final TileKey otherTheme = new TileKey("mapy-com", "winter", 10, 550, 350);
        cache.recordEmpty(sea10);
        cache.recordEmpty(sea12);
        cache.recordEmpty(otherTheme);
        cache.store(land, png(), null);

        assertEquals(2, cache.forgetEmpty("mapy-com", "outdoor"), "every zoom of the theme");
        assertFalse(cache.isKnownEmpty(sea10));
        assertFalse(cache.isKnownEmpty(sea12));
        assertTrue(cache.isKnownEmpty(otherTheme), "another theme keeps its answers");
        assertTrue(cache.contains(land), "a tile is never deleted");
        assertEquals(0, cache.forgetEmpty("mapy-com", "outdoor"), "nothing left to forget");
        assertEquals(0, cache.forgetEmpty("mapy-com", "never-used"), "a theme with no folder is not an error");
    }

    @Test
    @DisplayName("a marker is not counted as a tile")
    void statisticsIgnoreMarkers(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        cache.store(new TileKey("mapy-com", "outdoor", 10, 551, 350), png(), null);
        cache.recordEmpty(new TileKey("mapy-com", "outdoor", 10, 550, 350));

        final TileCache.ZoomStats z = cache.statistics().getFirst().zooms().getFirst();
        assertEquals(1, z.files());
        assertEquals(new TileRange(551, 350, 551, 350), z.range());
    }

    /**
     * The scale preview, an export and a second window each hold their own object over the one
     * folder; a tile one of them stores must be a tile the others find, or the map shows a
     * placeholder over what just arrived.
     */
    @Test
    @DisplayName("two caches over one folder see each other's stores")
    void twoCachesOverOneFolder(@TempDir final Path dir) throws IOException {
        final TileCache one = new TileCache(dir);
        final TileCache other = new TileCache(dir);
        final TileKey key = new TileKey("mapy-com", "outdoor", 12, 2212, 1387);

        assertNull(one.find(key), "asked and not found, which used to be remembered");
        assertNull(other.find(key));
        other.store(key, png(), null);
        assertNotNull(one.find(key), "the other object stored it; this one asks the disk");
        assertTrue(one.contains(key));
        assertTrue(one.inventory(List.of(key)).cached().contains(key));
    }

    /** A probe that misses while a store is landing must not leave the tile marked missing. */
    @Test
    @DisplayName("a store during a probe is not lost")
    void storeDuringProbe(@TempDir final Path dir) throws Exception {
        final TileCache cache = new TileCache(dir);
        final byte[] png = png();
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 200; i++) {
                final TileKey key = new TileKey("mapy-com", "outdoor", 14, 8000 + i, 5000);
                final Future<?> probing = pool.submit(() -> {
                    for (int n = 0; n < 20; n++) {
                        cache.find(key);
                    }
                });
                final Future<?> storing = pool.submit(() -> {
                    cache.store(key, png, null);
                    return null;
                });
                probing.get();
                storing.get();
                assertNotNull(cache.find(key), "stored while being probed, and still found");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
