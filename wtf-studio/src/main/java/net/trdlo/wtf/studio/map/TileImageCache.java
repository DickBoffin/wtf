package net.trdlo.wtf.studio.map;

import javafx.application.Platform;
import javafx.scene.image.Image;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.trdlo.wtf.tiles.TileKey;
import net.trdlo.wtf.tiles.TileSource;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decoded tiles, ready to draw.
 * <p>
 * Two rules keep the canvas responsive. <b>Bounded by bytes, not by count</b> – a 256x256 image is
 * 256 kB whatever the file on disk weighs, so counting files is counting the wrong thing.
 * <b>Decoding happens off the FX thread</b>, and a tile that is not ready yet is simply not drawn
 * this frame; the canvas asks again when the decode lands.
 */
public final class TileImageCache {

    private static final Logger log = LoggerFactory.getLogger(TileImageCache.class);

    /** A decoded 256x256 tile, four bytes a pixel. */
    public static final long BYTES_PER_TILE = 256L * 256 * 4;

    /** Three or four threads is plenty: the bottleneck is the disk, not the decoder. */
    private static final int DECODER_THREADS = 4;

    private final TileSource tiles;
    /** What one decoded tile takes: four times {@link #BYTES_PER_TILE} for {@code @2x} tiles. */
    private final long bytesPerTile;
    /** Not final: it grows to hold one screen, see {@link #ensureRoomFor}. */
    private volatile long budgetBytes;
    private final Runnable onTileReady;

    private final LinkedHashMap<TileKey, Image> images;
    private final InFlightDecodes decodes = new InFlightDecodes();
    private final ExecutorService decoders;

    /**
     * Tiles a decode has already looked for and not found.
     * <p>
     * Without this, an uncached tile is re-requested on <b>every frame</b>: {@link #get} misses,
     * starts a decode, the decode finds nothing, and the next repaint asks again. A screen of two
     * hundred uncached tiles then means two hundred futile disk lookups sixty times a second,
     * which is felt as stutter long before it is noticed as a bug. Remembering the answer also
     * lets the canvas tell "not in the cache" apart from "not decoded yet", which is the
     * difference between an honest count in the status bar and a guess.
     */
    private final LinkedHashMap<TileKey, Absence> absent;

    /** Why a tile is not on screen. The two are not the same thing and must not be counted alike. */
    public enum Absence {
        /** Never fetched. A hole in the print, and what the view's download fetches. */
        NOT_CACHED,
        /** The provider answered 204 or 404: open sea, or past the edge of its coverage. */
        DOES_NOT_EXIST
    }

    /** Cheap to keep: a key is small, and this is the only thing that stops the request storm. */
    private static final int MAX_ABSENT = 200_000;

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public TileImageCache(final @NotNull TileSource tiles, final long budgetBytes,
                          final @NotNull Runnable onTileReady) {
        this(tiles, budgetBytes, onTileReady, 1);
    }

    /** @param scale 2 for {@code @2x} tiles, which take four times the memory */
    public TileImageCache(final @NotNull TileSource tiles, final long budgetBytes,
                          final @NotNull Runnable onTileReady, final int scale) {
        this.tiles = tiles;
        this.bytesPerTile = BYTES_PER_TILE * scale * scale;
        this.budgetBytes = Math.max(bytesPerTile * 64, budgetBytes);
        this.onTileReady = onTileReady;
        this.images = new LinkedHashMap<>(512, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<TileKey, Image> eldest) {
                return (long) super.size() * bytesPerTile > TileImageCache.this.budgetBytes;
            }
        };
        this.absent = new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<TileKey, Absence> eldest) {
                return super.size() > MAX_ABSENT;
            }
        };
        this.decoders = Executors.newFixedThreadPool(DECODER_THREADS, r -> {
            final Thread t = new Thread(r, "tile-decoder");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Makes sure one screen's worth of tiles fits, with room to spare.
     * <p>
     * <b>A budget smaller than the view is a flicker, not a saving.</b> The 1:1 preview asked for
     * 48 MB – 192 tiles – on the reasoning that it shows a patch of paper. But it shows the tiles
     * at the print's zoom, which on screen are often smaller than their 256 pixels, and a
     * maximized preview then has several hundred on screen at once. Every frame drew them in
     * order, evicting the first ones to decode the last, and the next frame found the first ones
     * gone: the whole view flashed. Twice what is on screen keeps a pan from doing the same thing,
     * and the heap's own share is the ceiling, so a request can never ask for more than the map
     * behind it is allowed.
     */
    public void ensureRoomFor(final long tiles) {
        final long wanted = Math.min(defaultBudget(), tiles * 2 * bytesPerTile);
        if (wanted > budgetBytes) {
            budgetBytes = wanted;
        }
    }

    /**
     * What the 1:1 preview starts with, for the patch of paper it shows; {@link #ensureRoomFor}
     * grows it to what is on screen. A constant, as {@link #defaultBudget} is: a setting would ask
     * somebody to size a cache of decoded images, which nobody can judge better than the heap.
     */
    public static final long PREVIEW_BUDGET = 48L * 1024 * 1024;

    /** The map's budget: a slice of the heap, never less than 64 MB. */
    public static long defaultBudget() {
        return Math.max(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4);
    }

    /**
     * The decoded tile if it is ready, else null and a decode is started.
     * <p>
     * Must be called on the FX thread; the map is not synchronized because the only writer off the
     * FX thread hands its result back through {@link Platform#runLater}.
     */
    public @Nullable Image get(final @NotNull TileKey key) {
        final Image ready = images.get(key);
        if (ready != null) {
            hits.incrementAndGet();
            return ready;
        }
        misses.incrementAndGet();
        if (!absent.containsKey(key)) {
            request(key);
        }
        return null;
    }

    /**
     * True when a decode has looked and found nothing – the tile is genuinely not in the cache,
     * as opposed to merely not decoded yet.
     * <p>
     * The view's download and the status bar need this distinction. Counting every undrawn tile
     * as missing reports a screen of holes for the half-second after a pan, and counting none of
     * them reports a full cache over a screen of placeholders.
     */
    public @Nullable Absence absenceOf(final @NotNull TileKey key) {
        return absent.get(key);
    }

    /** Only tiles that were never fetched. A tile that does not exist is not a gap. */
    public boolean isMissing(final @NotNull TileKey key) {
        return absent.get(key) == Absence.NOT_CACHED;
    }

    /** Whether it is here now, without starting anything. For the parent-tile substitute. */
    public @Nullable Image peek(final @NotNull TileKey key) {
        return images.get(key);
    }

    private void request(final @NotNull TileKey key) {
        if (!decodes.start(key)) {
            return;
        }
        final int startedAt = decodes.generation();
        decoders.execute(() -> {
            Image decoded = null;
            boolean known = false;
            boolean failed = false;
            try {
                final byte[] bytes = tiles.get(key);
                // Asked once, on the decoder thread, so the answer is ready when the result lands
                known = bytes == null && tiles.isKnownEmpty(key);
                if (bytes != null) {
                    // no background loading: the executor already provides the concurrency, and
                    // JavaFX's own would make the completion order unobservable
                    final Image image = new Image(new ByteArrayInputStream(bytes));
                    decoded = image.isError() ? null : image;
                }
            } catch (final Exception ex) {
                // Not debug. A tile that will not read is either a corrupt file or a fault in
                // this application, and the last one hid a ConcurrentModificationException in
                // the shared cache for a while precisely because nobody saw it.
                log.warn("Could not read tile {}: {}", key, ex.toString());
                failed = true;
            }
            final Image result = decoded;
            final boolean empty = known;
            final boolean error = failed;
            Platform.runLater(() -> {
                // A decode started before an invalidate: the tile it read is still the tile, but
                // "not there" may be what the download has just changed, so that is not kept
                final boolean current = decodes.land(key, startedAt);
                if (result != null) {
                    images.put(key, result);
                } else if (error) {
                    // We do not know whether it is there; saying "not cached" would be a guess
                    // that then sticks. Leaving it unrecorded lets the next pan try again.
                    log.debug("Leaving {} unrecorded after a read error", key);
                } else if (current) {
                    absent.put(key, empty ? Absence.DOES_NOT_EXIST : Absence.NOT_CACHED);
                }
                // Repaint either way: "it is not there" is news too, and it is what moves the
                // view's download off zero without waiting for the user to nudge the map.
                onTileReady.run();
            });
        });
    }

    /**
     * Called after a download lands, and when empty tiles are forgotten: a tile drawn as missing or
     * as sea may be neither now, so it is looked for again.
     * <p>
     * Only what was found absent is dropped, with what the decodes still out will find absent. The
     * decoded images stay, since a download does not change a tile the cache had; dropping them
     * too put the whole view back to placeholders on every download, and on every pan with "fetch
     * as I pan". A new source is a new cache ({@code TileLayer.setSource}), so nothing here starts
     * over.
     */
    public void invalidate() {
        decodes.bump();
        absent.clear();
    }

    /**
     * How many tiles a decode has found absent since the last {@link #invalidate}, not cached and
     * not existing alike, and not only this view's. Nothing calls it.
     */
    public int absentCount() {
        return absent.size();
    }

    public int size() {
        return images.size();
    }

    public long bytes() {
        return (long) images.size() * bytesPerTile;
    }

    public int pending() {
        return decodes.pending();
    }

    public double hitRate() {
        final long h = hits.get(), m = misses.get();
        return h + m == 0 ? 1 : (double) h / (h + m);
    }

    public void shutdown() {
        decoders.shutdownNow();
    }
}
