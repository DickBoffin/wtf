package net.trdlo.wtf.studio;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import net.trdlo.wtf.tiles.TileKey;

import java.util.List;

/**
 * How many of the tiles on screen are ones the last download could not get, and which check of the
 * cache answers that – off {@link ViewDownload}, which reads the cache on a thread of its own, so
 * the order in which a check, a pan and a landing meet can be tested without the toolkit, as
 * {@link FetchAsIPan} is.
 * <p>
 * One check runs at a time. Every question asked moves the generation on, so a check that lands
 * after the view changed is dropped, even when the view has come back to the same tiles: the cache
 * may have changed meanwhile. A question asked while a check ran is kept, the latest one only, and
 * is what runs when that check lands; the landing itself asks nothing, so it does not run again
 * without end. A view with nothing failed on it is 0 at once, and it too drops the check still out.
 */
final class FailedOnScreen {

    /** A check of the cache to run: which tiles to look for, and the question it answers. */
    record Check(@NotNull List<TileKey> suspects, int generation) {
    }

    private int count;
    private int generation;
    private boolean running;
    /** The last question asked while a check ran, run when it lands; null when there is none. */
    private @Nullable List<TileKey> waiting;

    /** How many of the tiles on screen the last download could not get, as last answered. */
    int count() {
        return count;
    }

    /**
     * The view on screen has these tiles the last download failed on.
     *
     * @return the check to start now, or null when there is none to start – nothing failed on
     *         screen, or a check is out and this question waits for it to land
     */
    @Nullable Check ask(final @NotNull List<TileKey> suspects) {
        generation++;
        if (suspects.isEmpty()) {
            count = 0;
            waiting = null;
            return null;
        }
        if (running) {
            waiting = suspects;
            return null;
        }
        running = true;
        return new Check(suspects, generation);
    }

    /**
     * A check landed with how many of its suspects the cache still has not got, or with null when
     * it could not read the cache, which leaves the count as it was.
     *
     * @return the question asked while it ran, to start now, or null when none was
     */
    @Nullable Check landed(final @NotNull Check check, final @Nullable Integer found) {
        running = false;
        if (check.generation() == generation && found != null) {
            count = found;
        }
        final List<TileKey> next = waiting;
        if (next == null) {
            return null;
        }
        waiting = null;
        running = true;
        return new Check(next, generation);
    }
}
