package net.trdlo.wtf.compose;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The fade across a growth, as a grid: whole inside the shape it starts from, nothing past the
 * edge of the print, and the fraction of the way across the band in between.
 * <p>
 * In the unit square, at a scale that makes a cell a thousandth of it: a fitted square from 0.4
 * to 0.6 grown into one from 0.3 to 0.7, so the band is a hundred cells wide.
 */
class FadeMaskTest {

    /** Half a millimeter a cell, a thousandth of the unit square a cell. */
    private static final double MM_PER_UNIT = FadeMask.CELL_MM * 1000;

    private static final Geometry FIT = square(0.4, 0.6);
    private static final Geometry GROWN = square(0.3, 0.7);

    private static Geometry square(final double from, final double to) {
        return Shapes.GF.toGeometry(new org.locationtech.jts.geom.Envelope(from, to, from, to));
    }

    private static FadeMask.Grid grid(final Geometry from, final Geometry to) {
        final FadeMask fade = FadeMask.of(from, to, MM_PER_UNIT);
        assertNotNull(fade);
        return fade.grid();
    }

    @Test
    @DisplayName("whole inside the fit, nothing past the print, and linear across the band")
    void theFractionOfTheWayAcross() {
        final FadeMask.Grid g = grid(FIT, GROWN);
        assertEquals(1, g.alphaAt(0.5, 0.5), 1e-9, "inside the fit");
        assertEquals(1, g.alphaAt(0.401, 0.5), 1e-9, "just inside it");
        assertEquals(0, g.alphaAt(0.29, 0.5), 1e-9, "past the print");
        for (final double across : new double[]{0.1, 0.25, 0.5, 0.75, 0.9}) {
            // West, east, north and south alike
            final double d = 0.1 * across;
            assertEquals(1 - across, g.alphaAt(0.4 - d, 0.5), 0.015, () -> "west, " + across + " of the way");
            assertEquals(1 - across, g.alphaAt(0.6 + d, 0.5), 0.015, () -> "east, " + across + " of the way");
            assertEquals(1 - across, g.alphaAt(0.5, 0.4 - d), 0.015, () -> "north, " + across + " of the way");
            assertEquals(1 - across, g.alphaAt(0.5, 0.6 + d), 0.015, () -> "south, " + across + " of the way");
        }
    }

    @Test
    @DisplayName("a box grown into a bigger box fades to nothing in its square corners too")
    void theCornersReachNothing() {
        final FadeMask.Grid g = grid(FIT, GROWN);
        assertTrue(g.alphaAt(0.3005, 0.3005) < 0.02, "the grown box's corner");
        // Along the diagonal the fitted corner is farther than the band is wide, so the fade is
        // lower there than halfway across a side
        assertTrue(g.alphaAt(0.35, 0.35) < 0.5, "halfway to the corner on the diagonal");
        assertEquals(1, g.alphaAt(0.4005, 0.4005), 1e-9, "the fitted corner");
    }

    @Test
    @DisplayName("the same shapes give the same grid, byte for byte")
    void theSameEveryTime() {
        assertArrayEquals(grid(FIT, GROWN).digest(), grid(FIT, GROWN).digest());
    }

    @Test
    @DisplayName("nothing to fade from is no fade")
    void noFit() {
        assertNull(FadeMask.of(null, GROWN, MM_PER_UNIT));
        assertNull(FadeMask.of(Shapes.GF.createPolygon(), GROWN, MM_PER_UNIT));
    }

    @Test
    @DisplayName("a wall-sized print keeps to its budget of cells")
    void aBudgetOfCells() {
        // Twenty meters of print across the whole square: forty thousand half-millimeter cells a side
        final FadeMask fade = FadeMask.of(FIT, square(0, 1), 20_000);
        assertNotNull(fade);
        final FadeMask.Grid g = fade.grid();
        assertTrue((long) g.width * g.height <= FadeMask.MAX_CELLS * 1.01,
                () -> g.width + " x " + g.height + " cells");
        assertEquals(1, g.alphaAt(0.5, 0.5), 1e-9);
    }

    /**
     * The fill against JTS, cell by cell, for a shape with a hole and edges at every angle: a
     * wrong parity at one vertex would flip the rest of a row.
     */
    @Test
    @DisplayName("the fill covers the cells whose centers the shape covers, holes and all")
    void theFillIsTheShape() {
        final Geometry shape = Shapes.GF.createPolygon(
                Shapes.GF.createLinearRing(new Coordinate[]{
                        new Coordinate(0.05, 0.1), new Coordinate(0.9, 0.05), new Coordinate(0.95, 0.5),
                        new Coordinate(0.6, 0.6), new Coordinate(0.9, 0.95), new Coordinate(0.1, 0.9),
                        new Coordinate(0.3, 0.5), new Coordinate(0.05, 0.1)}),
                new org.locationtech.jts.geom.LinearRing[]{Shapes.GF.createLinearRing(new Coordinate[]{
                        new Coordinate(0.4, 0.2), new Coordinate(0.7, 0.3), new Coordinate(0.5, 0.45),
                        new Coordinate(0.4, 0.2)})});
        final int n = 97;
        final double cell = 1.0 / n;
        final boolean[] filled = FadeMask.fill(shape, 0, 0, cell, n, n);
        int wrong = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                final Point center = Shapes.GF.createPoint(new Coordinate((i + 0.5) * cell, (j + 0.5) * cell));
                if (filled[j * n + i] != shape.contains(center) && shape.getBoundary().distance(center) > 1e-9) {
                    wrong++;
                }
            }
        }
        assertEquals(0, wrong, "cells filled otherwise than the shape says");
    }
}
