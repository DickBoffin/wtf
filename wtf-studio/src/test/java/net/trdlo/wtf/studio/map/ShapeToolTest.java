package net.trdlo.wtf.studio.map;

import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.tiles.Mercator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.algorithm.distance.DiscreteHausdorffDistance;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The point and polygon tools: what a click, a drag and an idle move do while a shape is being
 * drawn. No toolkit is started, as {@link EditToolTest}'s is not: a mouse event is a plain object
 * and a camera is arithmetic.
 */
class ShapeToolTest {

    private static Camera camera() {
        final Camera c = new Camera();
        c.setViewSize(800, 600);
        c.centerOn(15.0, 49.5, 6);
        return c;
    }

    /** A click, real or the one JavaFX sends after a drag – which {@code stillSincePress} tells apart. */
    private static MouseEvent click(final double x, final double y, final boolean stillSincePress) {
        return new MouseEvent(MouseEvent.MOUSE_CLICKED, x, y, 0, 0, MouseButton.PRIMARY, 1,
                false, false, false, false,
                true, false, false,
                false, false, stillSincePress, null);
    }

    private static MouseEvent moveTo(final double x, final double y) {
        return new MouseEvent(MouseEvent.MOUSE_MOVED, x, y, 0, 0, MouseButton.NONE, 0,
                false, false, false, false, false, false, false, false, false, true, null);
    }

    // ------------------------------------------------------------------------------- the point tool

    @Test
    @DisplayName("a click drops a point, but the click JavaFX sends at the end of a pan does not")
    void aPanDoesNotDropAPoint() {
        final Camera c = camera();
        final AtomicReference<Geometry> got = new AtomicReference<>();
        final AtomicInteger done = new AtomicInteger();
        final ShapeTool.PointTool t = new ShapeTool.PointTool(ShapeTool.Slot.AREA, got::set, done::incrementAndGet);

        assertFalse(t.clicked(click(100, 100, false), c), "not still since the press: a pan, not a click");
        assertNull(got.get());
        assertEquals(0, done.get());

        assertTrue(t.clicked(click(100, 100, true), c), "a real click still drops one");
        assertNotNull(got.get());
        assertEquals(1, done.get());
    }

    // ------------------------------------------------------------------------------ the polygon tool

    @Test
    @DisplayName("a drag inside a half-drawn polygon does not add a second vertex where it ends")
    void aDragDoesNotAddAVertex() {
        final Camera c = camera();
        final AtomicReference<Geometry> got = new AtomicReference<>();
        final ShapeTool.PolygonTool t = new ShapeTool.PolygonTool(ShapeTool.Slot.SUBJECT, got::set, () -> { });

        assertTrue(t.clicked(click(100, 100, true), c), "the first vertex, a real click");
        assertTrue(t.clicked(click(150, 100, true), c), "the second, also real");
        // A drag inside the polygon: JavaFX still sends a click at the end of it
        assertTrue(t.clicked(click(400, 400, false), c), "still the tool's event, but not a vertex");
        assertTrue(t.clicked(click(100, 250, true), c), "the third real vertex");
        t.keyPressed(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                javafx.scene.input.KeyCode.ENTER, false, false, false, false));

        assertNotNull(got.get(), "closed with exactly the three real vertices");
        assertEquals(4, got.get().getNumPoints(), "three corners and the ring closed - the drag added none");
    }

    /**
     * Two clicks more than half the world apart are a straight line on the flat map, which a file
     * could not tell from an edge across the antimeridian; the polygon arrives with a point in the
     * middle of each such edge, where the band was drawn, and nowhere else.
     */
    @Test
    @DisplayName("a polygon drawn with an edge longer than half the world arrives split where it was drawn")
    void aLongEdgeArrivesSplit() {
        final Camera c = new Camera();
        c.setViewSize(800, 600);
        c.centerOn(0.0, 15.0, 1);           // the whole world, 512 pixels across
        final AtomicReference<Geometry> got = new AtomicReference<>();
        final ShapeTool.PolygonTool t = new ShapeTool.PolygonTool(ShapeTool.Slot.SUBJECT, got::set, () -> { });

        final Coordinate[] clicked = {new Coordinate(-100, 10), new Coordinate(100, 60),
                new Coordinate(100, 70), new Coordinate(-100, 70)};
        for (final Coordinate at : clicked) {
            assertTrue(t.clicked(click(c.toScreenX(Mercator.lonToUnitX(at.x)),
                    c.toScreenY(Mercator.latToUnitY(at.y)), true), c));
        }
        t.keyPressed(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                javafx.scene.input.KeyCode.ENTER, false, false, false, false));

        final Geometry drawn = got.get();
        assertNotNull(drawn);
        assertEquals(7, drawn.getNumPoints(),
                () -> "four corners, a point in each of the two long edges, and the ring closed: " + drawn);
        assertDoesNotThrow(() -> Shapes.checkExtent(drawn, Shapes.Role.SUBJECT));
        // The shape the band drew, on the map: the new points lie on its edges there
        assertEquals(0, new DiscreteHausdorffDistance(Shapes.toUnitSquare(drawn),
                Shapes.toUnitSquare(Shapes.polygon(clicked))).distance(), 1e-9);
    }

    @Test
    @DisplayName("the rubber band asks for a repaint as the pointer idles between clicks")
    void theBandFollowsTheIdlePointer() {
        final Camera c = camera();
        final ShapeTool.PolygonTool t = new ShapeTool.PolygonTool(ShapeTool.Slot.SUBJECT, g -> { }, () -> { });

        assertFalse(t.moved(moveTo(50, 50), c), "no vertex yet, nothing for the band to reach from");
        assertTrue(t.clicked(click(100, 100, true), c), "the first vertex");
        assertTrue(t.moved(moveTo(220, 240), c),
                "with a vertex down, the band follows the pointer and needs a repaint");
    }
}
