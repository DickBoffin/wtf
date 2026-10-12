package net.trdlo.wtf.tiles;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;

/**
 * The only type the rest of WTF sees. {@code wtf-compose} asks it for bytes and never learns
 * whether they came off the disk or the network; {@code wtf-studio} asks in cache-only mode while
 * panning and in download mode when the user presses the button.
 */
public interface TileSource {

    /** Bytes of the tile, or null when it is not available in the mode this source runs in. */
    byte @Nullable [] get(@NotNull TileKey key) throws IOException;

    /** Where the tile is on disk, or null. Never downloads, whatever the mode. */
    @Nullable Path findCached(@NotNull TileKey key);

    default boolean isCached(final @NotNull TileKey key) {
        return findCached(key) != null;
    }

    /** What is here and what is not, without fetching anything. */
    @NotNull TileCache.Inventory inventory(@NotNull Iterable<TileKey> wanted);

    /**
     * Fetches many. Throws {@link TileDownloader.MissingCredentialsException} when the provider
     * rejects the key or there is none, which is what the GUI turns into the credentials hand-off;
     * the exception carries the report of what arrived before. Throws a {@link Refusal} with
     * {@link DownloadReason#REJECTED_WITHOUT_KEY} when a provider that needs no key is answered
     * 401 or 403 anyway – there is no key to send to a settings field, so this is not that dialog.
     */
    @NotNull TileDownloader.Report download(@NotNull Collection<TileKey> keys,
                                            @NotNull ProgressSink progress) throws IOException;

    /**
     * True when the provider has said this tile does not exist – a 204 or a 404, which for map
     * imagery means open sea or the edge of a national provider's coverage.
     * <p>
     * This is not a gap. A tile that was never fetched is a hole in the print and has to be
     * reported; a tile that does not exist <i>is</i> the map, and counting the two together is
     * how a fully-drawn view ends up claiming that seven of its sixteen tiles are missing.
     */
    default boolean isKnownEmpty(final @NotNull TileKey key) {
        return false;
    }

    /** True when this source will go to the network at all. */
    boolean canDownload();
}
