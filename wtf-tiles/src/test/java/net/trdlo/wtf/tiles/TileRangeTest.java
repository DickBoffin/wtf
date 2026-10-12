package net.trdlo.wtf.tiles;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TileRangeTest {

    @Test
    @DisplayName("empty stays empty through every operation")
    void empty() {
        final TileRange e = TileRange.EMPTY;
        assertTrue(e.isEmpty());
        assertEquals(0, e.width());
        assertEquals(0, e.height());
        assertEquals(0, e.count());
        assertTrue(e.expandedBy(5).isEmpty(), "growing nothing gives nothing");
        assertTrue(e.clampedTo(10).isEmpty());
        assertTrue(e.intersect(new TileRange(0, 0, 9, 9)).isEmpty());
        assertFalse(e.contains(0, 0));
        assertFalse(e.iterator().hasNext());
    }

    @Test
    @DisplayName("expandedToInclude on an empty range starts a new one")
    void expandFromEmpty() {
        assertEquals(new TileRange(3, 4, 3, 4), TileRange.EMPTY.expandedToInclude(3, 4));
        assertEquals(new TileRange(3, 2, 7, 4),
                TileRange.EMPTY.expandedToInclude(3, 4).expandedToInclude(7, 2));
    }

    @Test
    @DisplayName("count is a long, because a full zoom 19 world overflows an int")
    void countDoesNotOverflow() {
        final int last = Mercator.tileCount(19) - 1;
        final TileRange whole = new TileRange(0, 0, last, last);
        assertEquals(274_877_906_944L, whole.count());
        assertTrue(whole.count() > Integer.MAX_VALUE, "this is exactly why it is a long");
    }

    @Test
    @DisplayName("clamping to a zoom can empty a range that lies entirely outside the world")
    void clamp() {
        assertEquals(new TileRange(0, 0, 3, 3), new TileRange(-2, -2, 3, 3).clampedTo(4));
        assertTrue(new TileRange(100, 100, 200, 200).clampedTo(4).isEmpty(),
                "zoom 4 only has 16 tiles per side");
    }

    @Test
    @DisplayName("intersection of disjoint ranges is empty, not negative")
    void intersect() {
        assertEquals(new TileRange(5, 5, 6, 6),
                new TileRange(0, 0, 6, 6).intersect(new TileRange(5, 5, 9, 9)));
        assertTrue(new TileRange(0, 0, 2, 2).intersect(new TileRange(5, 5, 9, 9)).isEmpty());
    }

    @Test
    @DisplayName("iteration is row-major, so an interrupted job leaves complete rows")
    void iterationOrder() {
        final List<String> seen = new ArrayList<>();
        for (final int[] xy : new TileRange(10, 20, 12, 21)) {
            seen.add(xy[0] + "," + xy[1]);
        }
        assertEquals(List.of("10,20", "11,20", "12,20", "10,21", "11,21", "12,21"), seen);
    }

    @Test
    @DisplayName("the scattered order holds every tile exactly once")
    void scatteredIsAPermutation() {
        final TileRange r = new TileRange(3, 7, 10, 12);
        final List<String> seen = new ArrayList<>();
        for (final int[] xy : r.scattered()) {
            assertTrue(r.contains(xy[0], xy[1]), () -> "outside the range: " + xy[0] + "," + xy[1]);
            seen.add(xy[0] + "," + xy[1]);
        }
        assertEquals(r.count(), seen.size());
        assertEquals(seen.size(), new java.util.HashSet<>(seen).size(), "no tile twice");
    }

    @Test
    @DisplayName("the scattered order starts with the four quadrants, not the first row")
    void scatteredSpreadsBeforeItFills() {
        final List<int[]> order = new TileRange(0, 0, 3, 3).scattered();
        final List<String> firstFour = new ArrayList<>();
        for (final int[] xy : order.subList(0, 4)) {
            firstFour.add(xy[0] + "," + xy[1]);
        }
        assertEquals(List.of("0,0", "0,2", "2,0", "2,2"), firstFour);
    }

    @Test
    @DisplayName("the scattered order is the same every time")
    void scatteredIsDeterministic() {
        final TileRange r = new TileRange(5, 5, 9, 11);
        final List<int[]> a = r.scattered();
        final List<int[]> b = r.scattered();
        for (int i = 0; i < a.size(); i++) {
            assertArrayEquals(a.get(i), b.get(i));
        }
    }

    @Test
    @DisplayName("a scattered single tile, and a scattered empty range, are not special cases")
    void scatteredEdges() {
        assertTrue(TileRange.EMPTY.scattered().isEmpty());
        final List<int[]> one = new TileRange(4, 9, 4, 9).scattered();
        assertEquals(1, one.size());
        assertArrayEquals(new int[]{4, 9}, one.getFirst());
    }

    @Test
    @DisplayName("iteration visits exactly count() tiles")
    void iterationCount() {
        final TileRange r = new TileRange(3, 7, 31, 19);
        long n = 0;
        for (final int[] ignored : r) {
            n++;
        }
        assertEquals(r.count(), n);
        assertEquals(29L * 13, n);
    }
}
