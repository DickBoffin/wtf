package net.trdlo.wtf.studio.map;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.event.EventType;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.studio.project.History;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Mercator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the editor does to a shape, edit by edit: the removals, the midpoints, the holes, the pin
 * and the keys, which {@link EditToolTest} leaves alone – and what a step of undo keeps of it.
 * <p>
 * Written against the editor while it was still one class, before it was split into the rings it
 * edits, where their handles are, and what a gesture does – so that the split had something to
 * keep.
 */
class EditToolEditsTest {

    private static final LonLatBounds BOHEMIA = new LonLatBounds(12.0, 48.0, 18.0, 51.0);
    private static final LonLatBounds EAST = new LonLatBounds(20.0, 48.0, 22.0, 50.0);
    private static final LonLatBounds ELSEWHERE = new LonLatBounds(2.0, 42.0, 5.0, 44.0);

    private static final Coordinate[] TRIANGLE = {xy(12, 48), xy(18, 48), xy(15, 51)};
    private static final Coordinate[] QUAD = {xy(12, 48), xy(18, 48.5), xy(17, 51), xy(13, 50.5)};
    /** Inside both of the above, far enough from their edges to be a lake of its own. */
    private static final Coordinate[] LAKE = {xy(14.5, 48.8), xy(15.5, 48.8), xy(15, 49.6)};

    // ------------------------------------------------------------------------------- the fixtures

    private static Camera camera() {
        final Camera c = new Camera();
        c.setViewSize(800, 600);
        c.centerOn(15.0, 49.5, 6);
        return c;
    }

    private static Coordinate xy(final double lon, final double lat) {
        return new Coordinate(lon, lat);
    }

    private static LinearRing ring(final Coordinate... open) {
        final Coordinate[] closed = new Coordinate[open.length + 1];
        System.arraycopy(open, 0, closed, 0, open.length);
        closed[open.length] = open[0];
        return Shapes.GF.createLinearRing(closed);
    }

    private static Polygon polygon(final Coordinate... open) {
        return Shapes.GF.createPolygon(ring(open));
    }

    private static Polygon polygon(final Coordinate[] shell, final Coordinate[] hole) {
        return Shapes.GF.createPolygon(ring(shell), new LinearRing[]{ring(hole)});
    }

    private static Geometry multi(final Polygon... parts) {
        return Shapes.GF.createMultiPolygon(parts);
    }

    private static EditTool on(final ObjectProperty<Geometry> target, final EditTool.Mode mode,
                               final Runnable changed) {
        return new EditTool(ShapeTool.Slot.SUBJECT, mode, target, g -> { }, changed);
    }

    /** Counts what the shape was changed to from now on. */
    private static AtomicInteger writesOf(final ObjectProperty<Geometry> target) {
        final AtomicInteger writes = new AtomicInteger();
        target.addListener((o, was, is) -> writes.incrementAndGet());
        return writes;
    }

    private static double x(final Camera c, final double lon) {
        return c.toScreenX(Mercator.lonToUnitX(lon));
    }

    private static double y(final Camera c, final double lat) {
        return c.toScreenY(Mercator.latToUnitY(lat));
    }

    private static MouseEvent mouse(final EventType<MouseEvent> type, final double x, final double y,
                                    final MouseButton button, final boolean control) {
        return new MouseEvent(type, x, y, 0, 0, button, type == MouseEvent.MOUSE_MOVED ? 0 : 1,
                false, control, false, false,
                button == MouseButton.PRIMARY, false, button == MouseButton.SECONDARY,
                false, false, true, null);
    }

    private static MouseEvent press(final Camera c, final double lon, final double lat) {
        return mouse(MouseEvent.MOUSE_PRESSED, x(c, lon), y(c, lat), MouseButton.PRIMARY, false);
    }

    private static MouseEvent rightClick(final Camera c, final double lon, final double lat,
                                         final boolean control) {
        return mouse(MouseEvent.MOUSE_PRESSED, x(c, lon), y(c, lat), MouseButton.SECONDARY, control);
    }

    private static MouseEvent drag(final Camera c, final double lon, final double lat) {
        return mouse(MouseEvent.MOUSE_DRAGGED, x(c, lon), y(c, lat), MouseButton.PRIMARY, false);
    }

    private static MouseEvent release(final Camera c, final double lon, final double lat) {
        return mouse(MouseEvent.MOUSE_RELEASED, x(c, lon), y(c, lat), MouseButton.PRIMARY, false);
    }

    private static MouseEvent move(final Camera c, final double lon, final double lat) {
        return moveTo(x(c, lon), y(c, lat));
    }

    private static MouseEvent moveTo(final double x, final double y) {
        return mouse(MouseEvent.MOUSE_MOVED, x, y, MouseButton.NONE, false);
    }

    private static KeyEvent key(final KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }

    private static boolean hasVertex(final Geometry g, final double lon, final double lat) {
        for (final Coordinate c : g.getCoordinates()) {
            if (Math.abs(c.x - lon) < 1e-6 && Math.abs(c.y - lat) < 1e-6) {
                return true;
            }
        }
        return false;
    }

    private static void assertBounds(final LonLatBounds expected, final Geometry g) {
        final LonLatBounds b = Shapes.boundsOf(g);
        assertEquals(expected.west(), b.west(), 1e-6, "west");
        assertEquals(expected.south(), b.south(), 1e-6, "south");
        assertEquals(expected.east(), b.east(), 1e-6, "east");
        assertEquals(expected.north(), b.north(), 1e-6, "north");
    }

    // --------------------------------------------------------------------------------- rectangles

    @Test
    @DisplayName("a corner dragged past the opposite one comes out a rectangle the right way round")
    void aCornerDraggedPastItsOpposite() {
        final Camera c = camera();
        final ObjectProperty<Geometry> target = new SimpleObjectProperty<>(Shapes.rectangle(BOHEMIA));
        final AtomicInteger writes = writesOf(target);
        final EditTool t = on(target, EditTool.Mode.FULL, () -> { });

        assertTrue(t.pressed(press(c, 12.0, 51.0), c), "the northwest corner");
        assertTrue(t.dragged(drag(c, 20.0, 46.0), c), "past the southeast one");
        // Held now by whichever corner is nearest the pointer, so this moves that one
        assertTrue(t.dragged(drag(c, 19.0, 47.0), c), "and back a little");
        assertTrue(t.released(release(c, 19.0, 47.0), c));

        assertEquals(1, writes.get());
        assertTrue(Shapes.isRectangle(target.get()), "still a rectangle");
        assertTrue(target.get().isValid(), "and not an inside-out ring");
        assertBounds(new LonLatBounds(18.0, 47.0, 19.0, 48.0), target.get());
    }

    /**
     * A midpoint on a rectangle in the subject's editor moves that side and adds no fifth corner:
     * the ring is read as a box, and a box is dragged by its four corners, so a corner added on its
     * edge would not follow the pointer and would leave a pentagon.
     */
    @Test
    @DisplayName("a rectangle's midpoint dragged moves its side, leaves four corners, and one undo gives it back")
    void aRectanglesMidpointMovesItsSide() {
        final Camera c = camera();
        final ProjectModel m = new ProjectModel();
        final History h = m.history();
        m.subjectProperty().set(Shapes.rectangle(BOHEMIA));
        h.closeStep();
        final AtomicInteger writes = writesOf(m.subjectProperty());
        final EditTool t = on(m.subjectProperty(), EditTool.Mode.FULL, () -> { });

        assertTrue(t.pressed(press(c, 15.0, 51.0), c), "the midpoint of the top edge");
        assertTrue(t.dragged(drag(c, 15.5, 51.5), c));
        assertTrue(t.dragged(drag(c, 14.0, 52.0), c), "sideways too, which a side ignores");
        assertTrue(t.released(release(c, 14.0, 52.0), c));
        h.endRun();
        h.closeStep();

        assertEquals(1, writes.get());
        assertTrue(Shapes.isRectangle(m.subjectProperty().get()), "four corners, square");
        assertBounds(new LonLatBounds(12.0, 48.0, 18.0, 52.0), m.subjectProperty().get());

        h.undo();
        assertBounds(BOHEMIA, m.subjectProperty().get());
        assertTrue(Shapes.isRectangle(m.subjectProperty().get()));
    }

    @Test
    @DisplayName("a rectangle's side dragged past the opposite one comes out the right way round, and stays held")
    void aSideDraggedPastItsOpposite() {
        final Camera c = camera();
        final ObjectProperty<Geometry> target = new SimpleObjectProperty<>(Shapes.rectangle(BOHEMIA));
        final EditTool t = on(target, EditTool.Mode.FULL, () -> { });

        assertTrue(t.pressed(press(c, 18.0, 49.5), c), "the midpoint of the east edge");
        assertTrue(t.dragged(drag(c, 10.0, 49.5), c), "past the west one");
        // Held now by the side on the west, so this moves that one and leaves the other at 12
        assertTrue(t.dragged(drag(c, 11.0, 49.5), c), "and back a little");
        assertTrue(t.released(release(c, 11.0, 49.5), c));

        assertTrue(Shapes.isRectangle(target.get()));
        assertTrue(target.get().isValid(), "and not an inside-out ring");
        assertBounds(new LonLatBounds(11.0, 48.0, 12.0, 51.0), target.get());
    }

    @Test
    @DisplayName("the area's editor refuses both removals, and writes and redraws nothing for them")
    void aRectangleRefusesEveryRemoval() {
        final Camera c = camera();
        final ObjectProperty<Geometry> target = new SimpleObjectProperty<>(Shapes.rectangle(BOHEMIA));
        final AtomicInteger writes = writesOf(target);
        final AtomicInteger changed = new AtomicInteger();
        final EditTool t = on(target, EditTool.Mode.RECTANGLE, changed::incrementAndGet);

        assertFalse(t.pressed(rightClick(c, 12.0, 51.0, false), c), "a corner");
        assertFalse(t.pressed(rightClick(c, 12.0, 51.0, true), c), "the whole part");
        assertEquals(0, writes.get());
        assertEquals(0, changed.get());
    }

    // ---------------------------------------------------------------------------------- polygons

    @Test
    @DisplayName("a polygon's midpoint dragged adds a vertex where it is let go, in one write")
    void aMidpointAddsAVertexWhereItIsLetGo() {
        final Camera c = camera();
        final ObjectProperty<Geometry> target = new SimpleObjectProperty<>(polygon(TRIANGLE));
        final AtomicInteger writes = writesOf(target);
        final EditTool t = on(target, EditTool.Mode.FULL, () -> { });

        assertTrue(t.pressed(press(c, 15.0, 48.0), c), "the midpoint of the base");
        assertTrue(t.dragged(drag(c, 15.5, 47.5), c));
        assertTrue(t.dragged(drag(c, 15.0, 47.0), c));
        assertTrue(t.released(release(c, 15.0, 47.0), c));

        assertEquals(1, writes.get());
        assertEquals(5, target.get().getNumPoints(), "four vertices and the closing one");
        assertTrue(hasVertex(target.get(), 15.0, 47.0), "the new one where the drag ended");
        assertTrue(target.get().isValid());
    }

    @Test
    @DisplayName("a drag writes once and asks for one redraw, when it is let go")
    void aGestureWritesOnceAndRedrawsOnce() {
        final Camera c = camera();
        final ObjectProperty<Geometry> target = new SimpleObjectProperty<>(polygon(TRIANGLE));
        final AtomicInteger writes = writesOf(target);
        final AtomicInteger changed = new AtomicInteger();
        final EditTool t = on(target, EditTool.Mode.FULL, changed::incrementAndGet);

        assertTrue(t.pressed(press(c, 15.0, 51.0), c));
        for (final double lat : new double[]{51.2, 51.4, 51.6, 51.8}) {
            assertTrue(t.dragged(drag(c, 15.0, lat), c));
        }
        assertEquals(0, changed.get(), "the canvas repaints a drag by itself");
        assertTrue(t.released(release(c, 15.0, 51.8), c));

        assertEquals(1, writes.get());
        assertEquals(1, changed.get());
    }

    @Test
    @DisplayName("a right-click takes a vertex from a quadrilateral, and a triangle's ring with its vertex")
    void aRightClickTakesAVertex() {
        final Camera c = camera();
        final ObjectProperty<Geometry> quad = new SimpleObjectProperty<>(polygon(QUAD));
        assertTrue(on(quad, EditTool.Mode.FULL, () -> { }).pressed(rightClick(c, 17.0, 51.0, false), c));
        assertEquals(4, quad.get().getNumPoints(), "a triangle is left");
        assertFalse(hasVertex(quad.get(), 17.0, 51.0));

        final ObjectProperty<Geometry> triangle = new SimpleObjectProperty<>(polygon(TRIANGLE));
        assertTrue(on(triangle, EditTool.Mode.FULL, () -> { }).pressed(rightClick(c, 15.0, 51.0, false), c));
        assertNull(triangle.get(), "below three corners there is no ring, and no shape");
    }

    // ------------------------------------------------------------------------- parts and holes

    @Test
    @DisplayName("a right-click on a holed triangle's corner takes the part and its hole, and leaves the other part")
    void aShellGoesWithItsHole() {
        final Camera c = camera();
        final ObjectProperty<Geometry> target = new SimpleObjectProperty<>(
                multi(polygon(TRIANGLE, LAKE), Shapes.rectangle(EAST)));
        assertTrue(on(target, EditTool.Mode.FULL, () -> { }).pressed(rightClick(c, 15.0, 51.0, false), c));

        final Polygon left = assertInstanceOf(Polygon.class, target.get(), "one part left");
        assertEquals(0, left.getNumInteriorRing(), "the hole went with the ring around it");
        assertBounds(EAST, left);
    }

    @Test
    @DisplayName("control and a right-click take a whole part, holes and all")
    void controlRightClickTakesAPart() {
        final Camera c = camera();
        final ObjectProperty<Geometry> target = new SimpleObjectProperty<>(
                multi(polygon(QUAD, LAKE), Shapes.rectangle(EAST)));
        assertTrue(on(target, EditTool.Mode.FULL, () -> { }).pressed(rightClick(c, 17.0, 51.0, true), c));

        final Polygon left = assertInstanceOf(Polygon.class, target.get(), "one part left");
        assertEquals(0, left.getNumInteriorRing());
        assertBounds(EAST, left);
    }

    @Test
    @DisplayName("a shell dragged keeps the hole inside it")
    void aShellEditKeepsItsHole() {
        final Camera c = camera();
        final Polygon holed = polygon(QUAD, LAKE);
        final ObjectProperty<Geometry> target = new SimpleObjectProperty<>(holed);
        final EditTool t = on(target, EditTool.Mode.FULL, () -> { });

        assertTrue(t.pressed(press(c, 17.0, 51.0), c));
        assertTrue(t.dragged(drag(c, 17.5, 51.5), c));
        assertTrue(t.released(release(c, 17.5, 51.5), c));

        final Polygon after = assertInstanceOf(Polygon.class, target.get());
        assertEquals(1, after.getNumInteriorRing());
        assertTrue(after.getInteriorRingN(0).equalsExact(holed.getInteriorRingN(0), 1e-9), "the same lake");
        assertTrue(hasVertex(after, 17.5, 51.5));
    }

    @Test
    @DisplayName("the pointer over a lake still shows the part around it, and the lake's corners take a press")
    void aHoleShowsItsPart() {
        final Camera c = camera();
        final Polygon frame = polygon(new Coordinate[]{xy(10, 46), xy(20, 46), xy(20, 52), xy(10, 52)},
                new Coordinate[]{xy(13, 48), xy(17, 48), xy(17, 50), xy(13, 50)});
        final EditTool t = on(new SimpleObjectProperty<>(frame), EditTool.Mode.FULL, () -> { });

        assertTrue(t.moved(move(c, 15.0, 49.0), c), "the middle of the lake, far from every edge");
        assertTrue(t.pressed(press(c, 13.0, 48.0), c), "a corner of the lake");
    }

    // ------------------------------------------------------------------------------------ the pin

    private static EditTool pin() {
        return on(new SimpleObjectProperty<>(Shapes.point(15.0, 49.5)), EditTool.Mode.FULL, () -> { });
    }

    @Test
    @DisplayName("the pin is taken by its head, within its own reach, and never by the right button")
    void thePinIsTakenByItsHead() {
        final Camera c = camera();
        final double px = x(c, 15.0), tip = y(c, 49.5), head = tip - EditTool.PIN_HEAD_ABOVE;

        assertFalse(pin().pressed(mouse(MouseEvent.MOUSE_PRESSED, px, tip, MouseButton.PRIMARY, false), c),
                "its point is below its head");
        assertTrue(pin().pressed(mouse(MouseEvent.MOUSE_PRESSED, px, head, MouseButton.PRIMARY, false), c),
                "its head");
        assertTrue(pin().pressed(mouse(MouseEvent.MOUSE_PRESSED, px + EditTool.PIN_HIT_RADIUS - 1, head,
                MouseButton.PRIMARY, false), c), "just inside its reach");
        assertFalse(pin().pressed(mouse(MouseEvent.MOUSE_PRESSED, px + EditTool.PIN_HIT_RADIUS + 1, head,
                MouseButton.PRIMARY, false), c), "just outside it");
        assertFalse(pin().pressed(mouse(MouseEvent.MOUSE_PRESSED, px, head, MouseButton.SECONDARY, false), c),
                "a place has no corner to take away");
        assertFalse(pin().pressed(mouse(MouseEvent.MOUSE_PRESSED, px, head, MouseButton.SECONDARY, true), c),
                "nor a part");
    }

    // ----------------------------------------------------------------------- keys and interruptions

    @Test
    @DisplayName("Escape throws a drag away: nothing is written, and the release after it is not the tool's")
    void escapeThrowsADragAway() {
        final Camera c = camera();
        final Geometry before = polygon(TRIANGLE);
        final ObjectProperty<Geometry> target = new SimpleObjectProperty<>(before);
        final AtomicInteger writes = writesOf(target);
        final AtomicInteger changed = new AtomicInteger();
        final EditTool t = on(target, EditTool.Mode.FULL, changed::incrementAndGet);

        assertTrue(t.pressed(press(c, 15.0, 51.0), c));
        assertTrue(t.dragged(drag(c, 16.0, 52.0), c));
        assertTrue(t.keyPressed(key(KeyCode.ESCAPE)));
        assertEquals(1, changed.get(), "the map is asked to draw the shape as it was");
        assertFalse(t.released(release(c, 16.0, 52.0), c));

        assertEquals(0, writes.get());
        assertSame(before, target.get());
        assertFalse(t.keyPressed(key(KeyCode.ENTER)), "the editor has no other key");
    }

    @Test
    @DisplayName("a shape replaced from outside in the middle of a drag ends the drag")
    void aReplacementEndsTheDrag() {
        final Camera c = camera();
        final ObjectProperty<Geometry> target = new SimpleObjectProperty<>(Shapes.rectangle(BOHEMIA));
        final EditTool t = on(target, EditTool.Mode.FULL, () -> { });
        assertTrue(t.pressed(press(c, 12.0, 51.0), c));
        assertTrue(t.dragged(drag(c, 11.0, 52.0), c));

        final Geometry replacement = Shapes.rectangle(ELSEWHERE);
        target.set(replacement);

        assertFalse(t.released(release(c, 11.0, 52.0), c), "nothing of the old drag is left to let go");
        assertSame(replacement, target.get(), "and nothing of it is written over the new shape");
        assertTrue(t.pressed(press(c, 2.0, 44.0), c), "the new shape's corner is the one to take");
    }

    // ----------------------------------------------------------------------------- the pointer

    @Test
    @DisplayName("moving onto a handle asks for a repaint, moving along it does not, moving off it does")
    void aHandleLightsUp() {
        final Camera c = camera();
        final EditTool t = on(new SimpleObjectProperty<>(Shapes.rectangle(BOHEMIA)), EditTool.Mode.FULL, () -> { });
        final double cornerX = x(c, 12.0), cornerY = y(c, 51.0);

        assertTrue(t.moved(move(c, 15.0, 49.5), c), "into the shape: its handles show");
        assertTrue(t.moved(moveTo(cornerX + 2, cornerY + 2), c), "onto the northwest corner");
        assertFalse(t.moved(moveTo(cornerX + 3, cornerY + 2), c), "a pixel further, still on it");
        assertTrue(t.moved(moveTo(cornerX + 20, cornerY), c), "off it, along the edge");
    }

    @Test
    @DisplayName("leaving the map during a drag keeps the handles, and leaving it after takes them away")
    void exitingMidDragKeepsTheHandles() {
        final Camera c = camera();
        final EditTool t = on(new SimpleObjectProperty<>(Shapes.rectangle(BOHEMIA)), EditTool.Mode.FULL, () -> { });

        assertTrue(t.pressed(press(c, 12.0, 51.0), c));
        assertTrue(t.dragged(drag(c, 11.0, 51.5), c));
        assertFalse(t.exited(), "the part being dragged keeps its handles");
        assertTrue(t.released(release(c, 11.0, 51.5), c));
        assertTrue(t.exited(), "once it is let go they go with the pointer");
    }

    @Test
    @DisplayName("a click is the editor's only on a handle, so a double-click elsewhere still zooms the map")
    void aClickIsTakenOnlyOnAHandle() {
        final Camera c = camera();
        final EditTool t = on(new SimpleObjectProperty<>(Shapes.rectangle(BOHEMIA)), EditTool.Mode.FULL, () -> { });

        assertTrue(t.moved(move(c, 12.0, 51.0), c), "the pointer reaches the corner");
        assertTrue(t.clicked(press(c, 12.0, 51.0), c), "a click on it is the editor's");
        assertFalse(t.clicked(press(c, 15.0, 49.5), c), "a click inside the shape is not");
        assertFalse(on(new SimpleObjectProperty<>(), EditTool.Mode.FULL, () -> { }).clicked(press(c, 15.0, 49.5), c),
                "nor any click over an empty slot");
    }

    // ---------------------------------------------------------------------- past half the world

    /**
     * An edge dragged longer than half the world is a straight line on the flat map, and a file
     * could not tell it from an edge across the antimeridian, which is refused – so it is written
     * with a point in its middle, where the map draws it, and the file then says which way round
     * it goes.
     */
    @Test
    @DisplayName("an edge dragged longer than half the world is written split where the map draws it")
    void anEdgeDraggedPastHalfTheWorldIsSplit() {
        final Camera c = new Camera();
        c.setViewSize(800, 600);
        c.centerOn(0.0, 15.0, 1);           // the whole world, 512 pixels across
        final ObjectProperty<Geometry> target = new SimpleObjectProperty<>(
                polygon(xy(-90, 0), xy(80, 5), xy(80, 30), xy(-90, 30)));
        final AtomicInteger writes = writesOf(target);
        final EditTool t = on(target, EditTool.Mode.FULL, () -> { });

        assertTrue(t.pressed(press(c, 80, 5), c), "the corner at 80°");
        assertTrue(t.dragged(drag(c, 100, 5), c), "dragged to 100°, 190° from its neighbor at -90°");
        assertTrue(t.released(release(c, 100, 5), c));

        assertEquals(1, writes.get(), "one write, as ever");
        final Geometry written = target.get();
        assertEquals(6, written.getNumPoints(),
                () -> "the four corners, one point in the long edge, and the ring closed: " + written);
        assertTrue(hasVertex(written, -90, 0) && hasVertex(written, 100, 5)
                && hasVertex(written, 80, 30) && hasVertex(written, -90, 30), written::toString);
        // Halfway on the map: in longitude, and at the Mercator middle of 0° and 5° in latitude
        assertTrue(hasVertex(written, 5,
                Mercator.unitYToLat((Mercator.latToUnitY(0) + Mercator.latToUnitY(5)) / 2)), written::toString);
        assertDoesNotThrow(() -> Shapes.checkExtent(written, Shapes.Role.SUBJECT),
                "and it passes what a file read back must pass");

        // Within half the world nothing is added
        final ObjectProperty<Geometry> near = new SimpleObjectProperty<>(
                polygon(xy(-90, 0), xy(80, 5), xy(80, 30), xy(-90, 30)));
        final EditTool n = on(near, EditTool.Mode.FULL, () -> { });
        assertTrue(n.pressed(press(c, 80, 5), c));
        assertTrue(n.dragged(drag(c, 85, 5), c));
        assertTrue(n.released(release(c, 85, 5), c));
        assertEquals(5, near.get().getNumPoints(), () -> "175° is an edge like any other: " + near.get());
    }

    // -------------------------------------------------------------------------------- the hints

    @Test
    @DisplayName("the hint says what the editor has read: a place, a rectangle or a shape")
    void theHintFollowsWhatIsRead() {
        final ObjectProperty<Geometry> target = new SimpleObjectProperty<>(Shapes.rectangle(BOHEMIA));
        final EditTool t = on(target, EditTool.Mode.FULL, () -> { });
        assertEquals("area.hint.editRectangle", t.hintKey());
        target.set(polygon(TRIANGLE));
        assertEquals("area.hint.editPolygon", t.hintKey(), "read again when the shape is replaced");
        target.set(Shapes.point(15.0, 49.5));
        assertEquals("area.hint.editPoint", t.hintKey());
        target.set(multi(Shapes.rectangle(BOHEMIA), Shapes.rectangle(EAST)));
        assertEquals("area.hint.editPolygon", t.hintKey(), "two rectangles are a shape, not a rectangle");
        assertEquals("area.hint.editPolygon",
                on(new SimpleObjectProperty<>(polygon(TRIANGLE)), EditTool.Mode.RECTANGLE, () -> { }).hintKey(),
                "a shape the area's editor may not touch");

        assertEquals("area.hint.editRectangle", EditTool.hintKeyFor(Shapes.rectangle(BOHEMIA)));
        assertEquals("area.hint.editPolygon", EditTool.hintKeyFor(polygon(TRIANGLE)));
        assertEquals("area.hint.editPoint", EditTool.hintKeyFor(Shapes.point(15.0, 49.5)));
        assertEquals("area.hint.editPolygon", EditTool.hintKeyFor(null));
        assertTrue(EditTool.isEditable(polygon(TRIANGLE)));
        assertFalse(EditTool.isEditable(null));
    }

    // ------------------------------------------------------------------ what a step of undo keeps

    /** West of the other two and clear of them, in view of {@link #camera()}. */
    private static final Coordinate[] WEST = {xy(8, 46.5), xy(10.5, 46.5), xy(9.25, 48.5)};

    /** A quadrilateral with a lake in it, a rectangle and a triangle: three parts that touch nowhere. */
    private static Geometry threeParts() {
        return multi(polygon(QUAD, LAKE), Shapes.rectangle(EAST), polygon(WEST));
    }

    /** Takes a handle, drags it there and lets it go, closing the step as the window does. */
    private static void dragStep(final History h, final EditTool t, final Camera c, final double lon,
                                 final double lat, final double toLon, final double toLat) {
        h.closeStep();
        assertTrue(t.pressed(press(c, lon, lat), c), () -> "a handle at " + lon + ", " + lat);
        assertTrue(t.dragged(drag(c, toLon, toLat), c));
        assertTrue(t.released(release(c, toLon, toLat), c));
        h.endRun();
        h.closeStep();
    }

    /** Every point of a shape, as the objects they are. */
    private static Set<Coordinate> pointsOf(final Geometry... shapes) {
        final Set<Coordinate> points = Collections.newSetFromMap(new IdentityHashMap<>());
        for (final Geometry g : shapes) {
            points.addAll(List.of(g.getCoordinates()));
        }
        return points;
    }

    /**
     * A step keeps what its drag did not touch as the step before had it. A surveyed border is
     * 125 000 points, and no step holds all of it anew: the other parts are the polygons
     * they were, and the part dragged is made of the points it had, but for the one that moved.
     */
    @Test
    @DisplayName("a drag's step shares what the drag did not touch, and undo and redo give back the same shapes")
    void aDragSharesWhatItDidNotTouch() {
        final Camera c = camera();
        final ProjectModel m = new ProjectModel();
        final History h = m.history();
        m.subjectProperty().set(threeParts());
        h.closeStep();
        final Geometry before = m.subjectProperty().get();
        final EditTool t = on(m.subjectProperty(), EditTool.Mode.FULL, () -> { });

        dragStep(h, t, c, 17.0, 51.0, 17.5, 51.5);
        final Geometry after = m.subjectProperty().get();

        assertNotSame(before, after);
        assertSame(before.getGeometryN(1), after.getGeometryN(1), "the rectangle, untouched");
        assertSame(before.getGeometryN(2), after.getGeometryN(2), "the triangle, untouched");
        final Polygon was = (Polygon) before.getGeometryN(0), is = (Polygon) after.getGeometryN(0);
        for (final int corner : new int[]{0, 1, 3}) {
            // Shapes.GF's sequences keep the array they are given, so the ring is made of these points
            assertSame(was.getExteriorRing().getCoordinateN(corner), is.getExteriorRing().getCoordinateN(corner),
                    "a corner that did not move is the point it was");
        }
        assertTrue(hasVertex(is, 17.5, 51.5), "and the one that did is where it was let go");
        for (int i = 0; i < LAKE.length; i++) {
            assertSame(was.getInteriorRingN(0).getCoordinateN(i), is.getInteriorRingN(0).getCoordinateN(i),
                    "the lake is made of its own points");
        }

        h.undo();
        assertSame(before, m.subjectProperty().get(), "undone: the shape before, itself");
        h.redo();
        assertSame(after, m.subjectProperty().get(), "redone: the shape after");
    }

    /**
     * A right-click is an edit like a drag: the part it took from is made again, and the others are
     * left as they were. A removal nobody was told of would leave the part as it was read, with the
     * lake it took out still in it.
     */
    @Test
    @DisplayName("a vertex, a lake and a whole part taken out each leave the other parts as they were")
    void aRemovalLeavesTheOtherParts() {
        final Camera c = camera();

        final ObjectProperty<Geometry> corner = new SimpleObjectProperty<>(threeParts());
        final Geometry three = corner.get();
        assertTrue(on(corner, EditTool.Mode.FULL, () -> { }).pressed(rightClick(c, 17.0, 51.0, false), c));
        assertFalse(hasVertex(corner.get(), 17.0, 51.0), "the corner is gone");
        assertEquals(1, ((Polygon) corner.get().getGeometryN(0)).getNumInteriorRing(),
                "and the lake is still there");
        assertSame(three.getGeometryN(1), corner.get().getGeometryN(1));
        assertSame(three.getGeometryN(2), corner.get().getGeometryN(2));

        final ObjectProperty<Geometry> lake = new SimpleObjectProperty<>(threeParts());
        final Geometry withLake = lake.get();
        assertTrue(on(lake, EditTool.Mode.FULL, () -> { }).pressed(rightClick(c, 15.0, 49.6, false), c),
                "a corner of the lake, which is a triangle");
        assertEquals(0, ((Polygon) lake.get().getGeometryN(0)).getNumInteriorRing(),
                "the lake went with its corner");
        assertSame(withLake.getGeometryN(1), lake.get().getGeometryN(1));
        assertSame(withLake.getGeometryN(2), lake.get().getGeometryN(2));

        final ObjectProperty<Geometry> part = new SimpleObjectProperty<>(threeParts());
        final Geometry all = part.get();
        assertTrue(on(part, EditTool.Mode.FULL, () -> { }).pressed(rightClick(c, 22.0, 50.0, true), c),
                "control and a corner of the rectangle");
        assertEquals(2, part.get().getNumGeometries(), "the rectangle is gone");
        assertSame(all.getGeometryN(0), part.get().getGeometryN(0));
        assertSame(all.getGeometryN(2), part.get().getGeometryN(1));
    }

    /**
     * Presses a handle and lets it go where it was pressed; how often the shape was written.
     * <p>
     * Counted as invalidations, not as changes: a change listener is not told of a shape equal to
     * the one before, but what follows the shape by identity still works again for one – the job,
     * the subject's parts joined for it, the map's outline.
     */
    private static int writesOfAClick(final Camera c, final Geometry shape, final MouseEvent at) {
        final ObjectProperty<Geometry> target = new SimpleObjectProperty<>(shape);
        final AtomicInteger writes = new AtomicInteger();
        target.addListener((javafx.beans.Observable o) -> writes.incrementAndGet());
        final EditTool t = on(target, EditTool.Mode.FULL, () -> { });
        assertTrue(t.pressed(at, c), "the press lands on a handle");
        assertTrue(t.released(mouse(MouseEvent.MOUSE_RELEASED, at.getX(), at.getY(), MouseButton.PRIMARY, false),
                c));
        assertSame(shape, target.get(), "the shape it was");
        return writes.get();
    }

    @Test
    @DisplayName("a handle pressed and let go where it was writes nothing – a vertex, a corner and the pin alike")
    void aHandleLetGoWhereItWasWritesNothing() {
        final Camera c = camera();
        assertEquals(0, writesOfAClick(c, polygon(TRIANGLE), press(c, 15.0, 51.0)), "a polygon's vertex");
        assertEquals(0, writesOfAClick(c, Shapes.rectangle(BOHEMIA), press(c, 12.0, 51.0)),
                "a rectangle's corner");
        final MouseEvent head = mouse(MouseEvent.MOUSE_PRESSED, x(c, 15.0), y(c, 49.5) - EditTool.PIN_HEAD_ABOVE,
                MouseButton.PRIMARY, false);
        assertEquals(0, writesOfAClick(c, Shapes.point(15.0, 49.5), head), "the pin, by its head");
    }

    /**
     * Steps that share parts and points are still each the shapes they were: three drags under
     * "the outline itself", where every step keeps an area worked out from its subject as well,
     * undone to the start and redone to the end. Each shape is compared with a copy taken when its
     * step was made, which a point changed in place anywhere since would fail.
     */
    @Test
    @DisplayName("three drags under the outline itself undo and redo to the shapes and areas they were")
    void sharedStepsUndoExactly() {
        final Camera c = camera();
        final ProjectModel m = new ProjectModel();
        final History h = m.history();
        m.startOver(() -> {
            m.simplifyProperty().set(Shapes.Simplify.NONE);
            m.subjectProperty().set(threeParts());
        });
        final EditTool t = on(m.subjectProperty(), EditTool.Mode.FULL, () -> { });
        final List<Geometry> subjects = new ArrayList<>(), areas = new ArrayList<>();
        final List<Geometry> subjectsThen = new ArrayList<>(), areasThen = new ArrayList<>();
        final Runnable keep = () -> {
            subjects.add(m.subjectProperty().get());
            areas.add(m.areaProperty().get());
            subjectsThen.add(m.subjectProperty().get().copy());
            areasThen.add(m.areaProperty().get().copy());
        };
        keep.run();
        dragStep(h, t, c, 17.0, 51.0, 17.5, 51.5);        // the quadrilateral around the lake
        keep.run();
        dragStep(h, t, c, 22.0, 50.0, 22.5, 50.5);        // a corner of the rectangle
        keep.run();
        dragStep(h, t, c, 9.25, 48.5, 9.5, 49.0);         // the top of the triangle
        keep.run();

        assertSame(subjects.get(0).getGeometryN(2), subjects.get(2).getGeometryN(2),
                "the triangle, untouched by the first two drags, is one polygon in all three steps");
        for (int k = 0; k < subjects.size(); k++) {
            final Set<Coordinate> subject = pointsOf(subjects.get(k));
            final Set<Coordinate> closing = closingPointsOf(areas.get(k));
            for (final Coordinate p : areas.get(k).getCoordinates()) {
                // Each ring's closing point is its own; every other point is the subject's
                assertTrue(subject.contains(p) || closing.contains(p),
                        "step " + k + "'s outline is made of its subject's points");
            }
        }

        for (int k = subjects.size() - 2; k >= 0; k--) {
            h.undo();
            assertStep(m, k, subjects, subjectsThen, areas, areasThen);
        }
        assertFalse(h.canUndoProperty().get());
        assertFalse(m.dirtyProperty().get(), "back at the start, and clean");
        for (int k = 1; k < subjects.size(); k++) {
            h.redo();
            assertStep(m, k, subjects, subjectsThen, areas, areasThen);
        }
        assertFalse(h.canRedoProperty().get());
        assertTrue(m.dirtyProperty().get());
    }

    /** The last point of every ring, which repeats the first, as the objects they are. */
    private static Set<Coordinate> closingPointsOf(final Geometry g) {
        final Set<Coordinate> points = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < g.getNumGeometries(); i++) {
            final Polygon poly = (Polygon) g.getGeometryN(i);
            final List<LinearRing> rings = new ArrayList<>(List.of(poly.getExteriorRing()));
            for (int h = 0; h < poly.getNumInteriorRing(); h++) {
                rings.add(poly.getInteriorRingN(h));
            }
            for (final LinearRing r : rings) {
                points.add(r.getCoordinateN(r.getNumPoints() - 1));
            }
        }
        return points;
    }

    private static void assertStep(final ProjectModel m, final int k, final List<Geometry> subjects,
                                   final List<Geometry> subjectsThen, final List<Geometry> areas,
                                   final List<Geometry> areasThen) {
        assertSame(subjects.get(k), m.subjectProperty().get(), "step " + k + "'s subject");
        assertTrue(subjects.get(k).equalsExact(subjectsThen.get(k)), "unchanged since its step was made");
        assertSame(areas.get(k), m.areaProperty().get(), "and its area");
        assertTrue(areas.get(k).equalsExact(areasThen.get(k)), "unchanged too");
        assertTrue(areas.get(k).equalsExact(Shapes.areaFrom(subjects.get(k), Shapes.Simplify.NONE, 0)), "and the outline its subject gives, value for value");
    }

    /**
     * On a surveyed border's worth of points in one ring, where sharing parts alone would share
     * nothing, a drag under "the outline itself" makes three points: the one it moved, and the
     * closing points of the subject's ring and of the area's. Every other point of both is one the
     * step before already had.
     */
    @Test
    @DisplayName("a drag on a ring of 125 000 points makes three new points, the subject and its outline together")
    void aSurveyedBordersStepIsTheMovedPoint() {
        final int n = 125_000;
        final Coordinate[] ring = new Coordinate[n + 1];
        for (int i = 0; i < n; i++) {
            final double a = 2 * Math.PI * i / n;
            ring[i] = xy(15.0 + Math.cos(a), 49.5 + 0.7 * Math.sin(a));
        }
        ring[n] = ring[0].copy();
        final ProjectModel m = new ProjectModel();
        final History h = m.history();
        m.startOver(() -> {
            m.simplifyProperty().set(Shapes.Simplify.NONE);
            m.subjectProperty().set(Shapes.GF.createPolygon(ring));
        });
        final Geometry subject = m.subjectProperty().get(), area = m.areaProperty().get();
        final Set<Coordinate> had = pointsOf(subject, area);

        // The top of the ring, zoomed in until its vertices are handles
        final Coordinate top = ring[n / 4];
        final Camera c = new Camera();
        c.setViewSize(800, 600);
        c.centerOn(top.x, top.y, 20);
        final EditTool t = on(m.subjectProperty(), EditTool.Mode.FULL, () -> { });
        dragStep(h, t, c, top.x, top.y, top.x + 1.5e-5, top.y - 1e-5);

        int made = 0;
        for (final Coordinate p : pointsOf(m.subjectProperty().get(), m.areaProperty().get())) {
            if (!had.contains(p)) {
                made++;
            }
        }
        assertEquals(3, made, "the point moved, and the subject's and the outline's closing points");
        h.undo();
        assertSame(subject, m.subjectProperty().get());
        assertSame(area, m.areaProperty().get());
    }
}
