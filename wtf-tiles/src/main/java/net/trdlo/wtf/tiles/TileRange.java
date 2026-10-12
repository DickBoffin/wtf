package net.trdlo.wtf.tiles;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * An inclusive rectangle of tile indices. Immutable: nothing grows one in place, and an operation
 * returns a new range.
 * <p>
 * Empty is represented by {@code maxX < minX || maxY < minY}, which {@link #EMPTY} produces and
 * {@link #isEmpty()} recognizes; every operation keeps an empty range empty.
 */
public record TileRange(int minX, int minY, int maxX, int maxY) implements Iterable<int[]> {

    public static final TileRange EMPTY = new TileRange(0, 0, -1, -1);

    public boolean isEmpty() {
        return maxX < minX || maxY < minY;
    }

    public int width() {
        return isEmpty() ? 0 : maxX - minX + 1;
    }

    public int height() {
        return isEmpty() ? 0 : maxY - minY + 1;
    }

    /** Tiles in the rectangle. A {@code long} because a full zoom 19 world is 2.7e11 of them. */
    public long count() {
        return (long) width() * height();
    }

    public boolean contains(final int x, final int y) {
        return !isEmpty() && x >= minX && x <= maxX && y >= minY && y <= maxY;
    }

    public @NotNull TileRange expandedBy(final int tiles) {
        return isEmpty() ? this : new TileRange(minX - tiles, minY - tiles, maxX + tiles, maxY + tiles);
    }

    public @NotNull TileRange expandedToInclude(final int x, final int y) {
        return isEmpty() ? new TileRange(x, y, x, y)
                : new TileRange(Math.min(minX, x), Math.min(minY, y), Math.max(maxX, x), Math.max(maxY, y));
    }

    /** Clipped to the world at this zoom. Can produce an empty range. */
    public @NotNull TileRange clampedTo(final int zoom) {
        if (isEmpty()) {
            return this;
        }
        final int last = Mercator.tileCount(zoom) - 1;
        final int lo = Math.max(0, minX), hi = Math.min(last, maxX);
        final int lo2 = Math.max(0, minY), hi2 = Math.min(last, maxY);
        return (hi < lo || hi2 < lo2) ? EMPTY : new TileRange(lo, lo2, hi, hi2);
    }

    public @NotNull TileRange intersect(final @NotNull TileRange other) {
        if (isEmpty() || other.isEmpty()) {
            return EMPTY;
        }
        final int lo = Math.max(minX, other.minX), hi = Math.min(maxX, other.maxX);
        final int lo2 = Math.max(minY, other.minY), hi2 = Math.min(maxY, other.maxY);
        return (hi < lo || hi2 < lo2) ? EMPTY : new TileRange(lo, lo2, hi, hi2);
    }

    /**
     * Row-major {@code {x, y}} pairs. Row-major on purpose: it is the order the downloader uses, so
     * an interrupted job leaves a band of complete rows rather than scattered holes.
     * <p>
     * The array is reused between iterations – copy it if you keep it.
     */
    @Override
    public @NotNull Iterator<int[]> iterator() {
        return new Iterator<>() {
            private final int[] xy = new int[2];
            private int x = minX, y = minY;

            @Override
            public boolean hasNext() {
                return !isEmpty() && y <= maxY;
            }

            @Override
            public int[] next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                xy[0] = x;
                xy[1] = y;
                if (++x > maxX) {
                    x = minX;
                    y++;
                }
                return xy;
            }
        };
    }

    /**
     * The same tiles, ordered so that any prefix of the list is spread over the whole rectangle.
     * <p>
     * Row-major is the right order for a job that will finish: it is sequential on disk, and what
     * it leaves behind when it is interrupted is a band of complete rows. It is the wrong order
     * for a job somebody is <b>watching</b> – a first world arriving over a phone hotspot fills
     * the top edge and leaves the rest blank for as long as it takes, which reads as a fault
     * rather than as progress.
     * <p>
     * The order is a bit-reversed Morton code rather than a shuffle. Deterministic, so two runs
     * ask for the same tiles in the same order and the second one is answered by the cache; and
     * low-discrepancy, so every prefix covers the rectangle evenly instead of clumping the way a
     * shuffle sometimes does. On a 4 × 4 world the first four tiles are the four quadrants.
     * <p>
     * The whole rectangle is held in memory, so this is for the ranges a person waits on – a
     * screenful, a first world – and not for a zoom 19 export.
     */
    public @NotNull List<int[]> scattered() {
        if (isEmpty()) {
            return List.of();
        }
        final int side = Math.max(width(), height());
        final int bits = Math.max(1, 32 - Integer.numberOfLeadingZeros(side - 1));
        final List<int[]> cells = new ArrayList<>(Math.toIntExact(count()));
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                cells.add(new int[]{x, y});
            }
        }
        // Stable, so tiles that cannot be told apart – there are none, the key is injective –
        // would still come out row-major.
        cells.sort(Comparator.comparingLong(c -> scatterKey(c[0] - minX, c[1] - minY, bits)));
        return cells;
    }

    /**
     * Interleaves the two coordinates least significant bit first, so the bit that splits the
     * rectangle in half ends up deciding the order and the bit that picks a single tile ends up
     * deciding nothing until the end.
     */
    private static long scatterKey(final int dx, final int dy, final int bits) {
        long key = 0;
        for (int b = 0; b < bits; b++) {
            key = (key << 2) | (((dx >> b) & 1L) << 1) | ((dy >> b) & 1L);
        }
        return key;
    }

    @Override
    public @NotNull String toString() {
        return isEmpty() ? "TileRange[empty]"
                : "TileRange[x " + minX + ".." + maxX + ", y " + minY + ".." + maxY
                        + " = " + width() + "x" + height() + "]";
    }
}
