package net.trdlo.wtf.studio.steps;

import net.trdlo.wtf.tiles.TileCache;
import net.trdlo.wtf.tiles.TileDownloader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The preview's downloader is the current cache's. */
class FollowingDownloaderTest {

    @Test
    @DisplayName("the downloader is kept over one cache and replaced when the cache is another")
    void followsTheCache(@TempDir final Path dir) {
        final List<TileDownloader> made = new ArrayList<>();
        final FollowingDownloader following = new FollowingDownloader(cache -> {
            final TileDownloader d = new TileDownloader(cache, 1, "test/0");
            made.add(d);
            return d;
        });
        final TileCache first = new TileCache(dir.resolve("first"));
        final TileCache moved = new TileCache(dir.resolve("moved"));

        assertFalse(following.follows(first), "nothing made yet");
        final TileDownloader d1 = following.over(first);
        assertSame(d1, following.over(first), "the same cache keeps the same downloader");
        assertTrue(following.follows(first));
        assertEquals(1, made.size(), "not a client per opening");

        final TileDownloader d2 = following.over(moved);
        assertNotSame(d1, d2, "Settings moved the cache: the preview fetches into the new folder");
        assertTrue(following.follows(moved));
        assertFalse(following.follows(first));
        assertEquals(2, made.size());

        following.close();
        assertFalse(following.follows(moved));
    }
}
