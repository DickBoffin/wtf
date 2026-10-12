package net.trdlo.wtf.studio.map;

import org.jetbrains.annotations.NotNull;
import net.trdlo.wtf.tiles.TileKey;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Which tiles a decode is out for, and whether what one found when it lands is still news.
 * <p>
 * Apart from {@link TileImageCache} so that it can be tested without the toolkit that
 * {@link javafx.scene.image.Image} and {@link javafx.application.Platform} need.
 * <p>
 * A key leaves the set only when its own decode lands; nothing clears it wholesale. Clearing it
 * on {@link TileImageCache#invalidate()} let a tile already being decoded be asked for again, and
 * the first decode's landing then freed the second one's key: decoded twice, and
 * {@link #pending()}, which the capture waits on, short of what was running.
 */
final class InFlightDecodes {

    private final Set<TileKey> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicInteger generation = new AtomicInteger();

    /** True when nothing is decoding this key yet; the caller then starts one. */
    boolean start(final @NotNull TileKey key) {
        return inFlight.add(key);
    }

    /** The generation a decode starts in, handed back to {@link #land}. */
    int generation() {
        return generation.get();
    }

    /**
     * A decode for {@code key} has landed, and the key is free for another.
     *
     * @return false when {@link #bump} ran since it started: a tile it found is still the tile,
     *         but its finding that one was not there may be out of date
     */
    boolean land(final @NotNull TileKey key, final int startedAt) {
        inFlight.remove(key);
        return generation.get() == startedAt;
    }

    /** The cache may hold tiles it did not when the decodes still out started: after a download. */
    void bump() {
        generation.incrementAndGet();
    }

    int pending() {
        return inFlight.size();
    }
}
