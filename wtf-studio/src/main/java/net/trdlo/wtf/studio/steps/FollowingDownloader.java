package net.trdlo.wtf.studio.steps;

import net.trdlo.wtf.tiles.TileCache;
import net.trdlo.wtf.tiles.TileDownloader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * A downloader kept for as long as its cache is the window's cache, and no longer.
 * <p>
 * The scale preview fetches, and a downloader is bound to the cache it was given. Keeping one for
 * the window's life is right about the {@code HttpClient} – building one to throw it away is the
 * expensive half of a request – and wrong about the cache: after Settings moves the tile folder the
 * preview would go on fetching into the previous one, and the export, asking the new one, would
 * fetch every tile again. So the downloader is asked for through the cache that is current, and
 * follows it: the same object while the cache is the same, a new one, the old closed, when it is
 * not.
 */
final class FollowingDownloader {

    private final Function<TileCache, TileDownloader> make;
    private @Nullable TileCache boundTo;
    private @Nullable TileDownloader downloader;

    FollowingDownloader(final @NotNull Function<TileCache, TileDownloader> make) {
        this.make = make;
    }

    /** The downloader over this cache: the one kept, if it was made over the same object. */
    @NotNull TileDownloader over(final @NotNull TileCache cache) {
        if (downloader == null || boundTo != cache) {
            close();
            downloader = make.apply(cache);
            boundTo = cache;
        }
        return downloader;
    }

    /** Whether the downloader kept, if any, is the one over this cache. */
    boolean follows(final @NotNull TileCache cache) {
        return downloader != null && boundTo == cache;
    }

    void close() {
        if (downloader != null) {
            downloader.close();
            downloader = null;
            boundTo = null;
        }
    }
}
