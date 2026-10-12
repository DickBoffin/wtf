package net.trdlo.wtf.studio.map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import net.trdlo.wtf.studio.outline.OutlineLibrary;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shape as the screen draws it. The clip has to be exact – it is a fill and a clipping
 * region, not only a stroke – and the simplification has to be worth having.
 */
class ScreenShapeTest {

    /** The shoelace area of an open ring of x, y pairs. */
    private static double area(final double[] xy) {
        double a = 0;
        for (int i = 0; i < xy.length; i += 2) {
            final int j = (i + 2) % xy.length;
            a += xy[i] * xy[j + 1] - xy[j] * xy[i + 1];
        }
        return Math.abs(a) / 2;
    }

    @Test
    @DisplayName("a ring half in the window is cut to exactly the half that is in it")
    void theClipCoversExactlyTheInside() {
        final double[] square = {0, 0, 4, 0, 4, 4, 0, 4};
        final double[] cut = ScreenShape.clip(square, 2, -1, 10, 10);
        assertEquals(8.0, area(cut), 1e-12, "the right half of a 4 by 4 square");
    }

    @Test
    @DisplayName("a triangle through a corner of the window keeps only what is inside")
    void anObliqueEdgeIsCutWhereItCrosses() {
        final double[] triangle = {0, 0, 10, 0, 0, 10};
        // the unit square at the right angle is entirely inside the triangle
        assertEquals(1.0, area(ScreenShape.clip(triangle, 0, 0, 1, 1)), 1e-12);
        // and this window straddles the hypotenuse: half of it is inside
        assertEquals(0.5, area(ScreenShape.clip(triangle, 4.5, 4.5, 5.5, 5.5)), 1e-12);
    }

    @Test
    @DisplayName("a ring wholly inside is returned as it is, and one wholly outside comes back empty")
    void insideAndOutside() {
        final double[] small = {1, 1, 2, 1, 2, 2};
        assertSame(small, ScreenShape.clip(small, 0, 0, 10, 10));
        assertEquals(0, ScreenShape.clip(small, 5, 5, 10, 10).length);
    }

    @Test
    @DisplayName("a country around the whole view is the view")
    void aViewInsideTheShapeIsTheView() {
        final double[] big = {-100, -100, 100, -100, 100, 100, -100, 100};
        assertEquals(4.0, area(ScreenShape.clip(big, 0, 0, 2, 2)), 1e-12);
    }

    @Test
    @DisplayName("a fine border traces a fraction of its points zoomed out, and less still zoomed in")
    void theBorderIsCheapToDraw() throws IOException {
        // The library's Czechia is 922 points; densified to a vertex every ~15 m it is the size
        // of a surveyed border, which is what the drawing has to cope with
        final OutlineLibrary.Entry cz = OutlineLibrary.get().search("czechia", 5).getFirst();
        final Geometry fine = org.locationtech.jts.densify.Densifier.densify(
                OutlineLibrary.get().geometry(cz), 0.0002);
        assertTrue(fine.getNumPoints() > 50_000, "a border as dense as a surveyed one");

        final Camera wide = new Camera();
        wide.setViewSize(1600, 1000);
        wide.centerOn(15.4, 49.8, 8);
        final int atZ8 = new ScreenShape().pointsFor(fine, wide);
        assertTrue(atZ8 < fine.getNumPoints() / 10, "z8 traces " + atZ8 + " of " + fine.getNumPoints());

        final Camera close = new Camera();
        close.setViewSize(1600, 1000);
        close.centerOn(16.94, 48.70, 15);       // on the border south of Breclav
        final int atZ15 = new ScreenShape().pointsFor(fine, close);
        assertTrue(atZ15 > 0, "some of the border is on screen");
        assertTrue(atZ15 < fine.getNumPoints() / 20, "z15 traces " + atZ15 + " of " + fine.getNumPoints());

    }

    @Test
    @DisplayName("a shape already in the unit square traces as its original in degrees does")
    void aShapeInTheUnitSquare() {
        final Geometry degrees = net.trdlo.wtf.compose.Shapes.polygon(new org.locationtech.jts.geom.Coordinate[]{
                new org.locationtech.jts.geom.Coordinate(14, 49), new org.locationtech.jts.geom.Coordinate(16, 49),
                new org.locationtech.jts.geom.Coordinate(15, 51), new org.locationtech.jts.geom.Coordinate(14, 49)});
        final Camera camera = new Camera();
        camera.setViewSize(400, 300);
        camera.centerOn(15, 50, 9);
        assertEquals(new ScreenShape().pointsFor(degrees, camera),
                ScreenShape.inUnitSquare().pointsFor(net.trdlo.wtf.compose.Shapes.toUnitSquare(degrees), camera));
    }
}
