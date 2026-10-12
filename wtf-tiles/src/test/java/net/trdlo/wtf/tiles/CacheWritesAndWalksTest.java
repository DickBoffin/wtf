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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How the cache writes a tile and how it walks its folders: two writers of one tile, the sweep
 * of {@code .part} files, the statistics and an inventory answered from one listing per folder.
 */
class CacheWritesAndWalksTest {

    private static byte[] png(final int width) throws IOException {
        final java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(width, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            img.setRGB(x, 0, x * 7919);         // noise, so that a wider tile is a longer file
        }
        final var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static List<Path> partsIn(final Path dir) throws IOException {
        try (final Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> p.getFileName().toString().endsWith(".part")).toList();
        }
    }

    @Test
    @DisplayName("two writers of one tile publish a whole tile, one or the other, and leave no .part")
    void twoWritersOfOneTile(@TempDir final Path dir) throws Exception {
        final TileCache cache = new TileCache(dir);
        final TileKey key = new TileKey("mapy-com", "outdoor", 10, 550, 350);
        final byte[] small = png(4);
        final byte[] large = png(4000);
        final ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 20; round++) {
                final CountDownLatch go = new CountDownLatch(1);
                final List<Future<Path>> writes = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    final byte[] data = i % 2 == 0 ? small : large;
                    writes.add(pool.submit(() -> {
                        go.await();
                        return cache.store(key, data, null);
                    }));
                }
                go.countDown();
                for (final Future<Path> w : writes) {
                    w.get();
                }
                final byte[] published = cache.read(key);
                assertNotNull(published);
                assertTrue(Arrays.equals(small, published) || Arrays.equals(large, published),
                        "round " + round + ": a tile of " + published.length + " bytes is neither");
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(List.of(), partsIn(dir));
    }

    @Test
    @DisplayName("the server's date goes on the tile once it is in place")
    void theDateGoesOnTheTile(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final Instant taken = Instant.parse("2018-04-12T15:27:45Z");
        final Path tile = cache.store(new TileKey("mapy-com", "aerial", 15, 17696, 11100), png(4), taken);
        assertEquals(taken.getEpochSecond(), Files.getLastModifiedTime(tile).toInstant().getEpochSecond());
    }

    @Test
    @DisplayName("a sweep after the first looks only in the folders touched since shortly before it")
    void theSweepSkipsUntouchedFolders(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final Instant longAgo = Instant.now().minus(Duration.ofDays(30));
        final Path first = Files.createDirectories(dir.resolve("mapy-com").resolve("outdoor").resolve("10"));
        final Path left = Files.writeString(first.resolve("0550_0350.png.1.part"), "x");
        Files.setLastModifiedTime(left, FileTime.from(longAgo));
        assertEquals(1, cache.cleanPartFiles(Duration.ofHours(1)), "the first sweep walks everything");
        assertFalse(Files.exists(left));

        // A folder nobody has written to for a month cannot hold a .part the last sweep missed ...
        final Path quiet = Files.createDirectories(dir.resolve("mapy-com").resolve("outdoor").resolve("11"));
        final Path unseen = Files.writeString(quiet.resolve("1100_0700.png.2.part"), "x");
        Files.setLastModifiedTime(unseen, FileTime.from(longAgo));
        Files.setLastModifiedTime(quiet, FileTime.from(longAgo));
        // ... and one written to since can
        final Path busy = Files.createDirectories(dir.resolve("mapy-com").resolve("outdoor").resolve("12"));
        final Path stale = Files.writeString(busy.resolve("2200_1400.png.3.part"), "x");
        Files.setLastModifiedTime(stale, FileTime.from(longAgo));

        assertEquals(1, cache.cleanPartFiles(Duration.ofHours(1)));
        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(unseen), "the untouched folder was not walked");
    }

    @Test
    @DisplayName("the statistics add up the sizes of the tiles and nothing else")
    void statisticsAddUpTheSizes(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final Path a = cache.store(new TileKey("mapy-com", "outdoor", 10, 550, 350), png(4), null);
        final Path b = cache.store(new TileKey("mapy-com", "outdoor", 10, 551, 350), png(400), null);
        cache.recordEmpty(new TileKey("mapy-com", "outdoor", 10, 552, 350));
        Files.writeString(a.resolveSibling("0553_0350.png.9.part"), "half a tile");

        final TileCache.ZoomStats z = cache.statistics().getFirst().zooms().getFirst();
        assertEquals(2, z.files());
        assertEquals(Files.size(a) + Files.size(b), z.bytes());
    }

    @Test
    @DisplayName("an inventory from one listing gives the same answer as asking for each tile")
    void inventoryFromOneListing(@TempDir final Path dir) throws IOException {
        final TileCache cache = new TileCache(dir);
        final List<TileKey> wanted = new ArrayList<>();
        for (int x = 0; x < TileCache.LISTING_THRESHOLD * 2; x++) {
            wanted.add(new TileKey("mapy-com", "outdoor", 10, 500 + x, 350));
        }
        wanted.add(new TileKey("mapy-com", "outdoor", 11, 1000, 700));     // a folder asked about once
        wanted.add(new TileKey("mapy-com", "winter", 10, 500, 350));       // a folder that is not there
        final byte[] jpeg;
        {
            final var out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1, 1,
                    java.awt.image.BufferedImage.TYPE_INT_RGB), "jpg", out);
            jpeg = out.toByteArray();
        }
        for (int i = 0; i < TileCache.LISTING_THRESHOLD * 2; i += 3) {
            cache.store(wanted.get(i), png(4), null);
        }
        cache.store(wanted.get(1), jpeg, null);                         // found under any extension
        for (int i = 2; i < TileCache.LISTING_THRESHOLD * 2; i += 3) {
            cache.recordEmpty(wanted.get(i));
        }
        cache.store(wanted.get(wanted.size() - 2), png(4), null);

        final TileCache.Inventory inv = cache.inventory(wanted);
        final List<TileKey> cached = new ArrayList<>(), missing = new ArrayList<>(), empty = new ArrayList<>();
        final TileCache fresh = new TileCache(dir);
        for (final TileKey k : wanted) {
            (fresh.contains(k) ? cached : fresh.isKnownEmpty(k) ? empty : missing).add(k);
        }
        assertEquals(cached, inv.cached(), "cached, in the order asked");
        assertEquals(missing, inv.missing());
        assertEquals(empty, inv.empty());
        assertEquals(43 + 1 + 1, inv.cached().size(), "every third, the JPEG and the one at zoom 11");
        assertTrue(inv.missing().contains(wanted.getLast()));
    }
}
