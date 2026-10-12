package net.trdlo.wtf.tiles;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;

/**
 * The ordinary {@link TileSource}: a cache, and optionally a downloader behind it.
 * <p>
 * Built for one provider and one credential, because that is the granularity at which a key
 * applies and at which a download job is confirmed.
 */
public final class CachedTileSource implements TileSource {

    private final TileCache cache;
    private final @Nullable TileDownloader downloader;
    private final @NotNull Provider provider;
    private final @Nullable String apiKey;

    /** Reads only. Panning the map uses this, so a missing tile is a placeholder, not a request. */
    public static @NotNull CachedTileSource cacheOnly(final @NotNull TileCache cache,
                                                      final @NotNull Provider provider) {
        return new CachedTileSource(cache, null, provider, null);
    }

    public static @NotNull CachedTileSource downloading(final @NotNull TileCache cache,
                                                        final @NotNull TileDownloader downloader,
                                                        final @NotNull Provider provider,
                                                        final @Nullable String apiKey) {
        return new CachedTileSource(cache, downloader, provider, apiKey);
    }

    private CachedTileSource(final @NotNull TileCache cache, final @Nullable TileDownloader downloader,
                             final @NotNull Provider provider, final @Nullable String apiKey) {
        this.cache = cache;
        this.downloader = downloader;
        this.provider = provider;
        this.apiKey = apiKey;
    }

    @Override
    public byte @Nullable [] get(final @NotNull TileKey key) throws IOException {
        final byte[] cached = cache.read(key);
        if (cached != null || downloader == null) {
            return cached;
        }
        try {
            downloader.download(provider, java.util.List.of(key), apiKey, ProgressSink.IGNORE);
        } catch (final NothingThereException sea) {
            // A job of one tile that does not exist is this tile being sea, which is an answer:
            // it is recorded, and isKnownEmpty says so from now on
            return null;
        }
        return cache.read(key);
    }

    @Override
    public @Nullable Path findCached(final @NotNull TileKey key) {
        return cache.find(key);
    }

    @Override
    public @NotNull TileCache.Inventory inventory(final @NotNull Iterable<TileKey> wanted) {
        return cache.inventory(wanted);
    }

    @Override
    public @NotNull TileDownloader.Report download(final @NotNull Collection<TileKey> keys,
                                                   final @NotNull ProgressSink progress) throws IOException {
        if (downloader == null) {
            throw new IOException("This tile source is cache-only; nothing was downloaded");
        }
        return downloader.download(provider, keys, apiKey, progress);
    }

    @Override
    public boolean isKnownEmpty(final @NotNull TileKey key) {
        return cache.isKnownEmpty(key);
    }

    @Override
    public boolean canDownload() {
        return downloader != null;
    }

    public @NotNull TileCache cache() {
        return cache;
    }

    public @NotNull Provider provider() {
        return provider;
    }
}
