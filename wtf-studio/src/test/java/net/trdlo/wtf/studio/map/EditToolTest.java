package net.trdlo.wtf.studio.map;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.tiles.LonLatBounds;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The editor, installed for as long as its step is open. No toolkit is started: a mouse event is a plain object, a camera is arithmetic, and
 * nothing here draws.
 * <p>
 * Two of these are contracts the rest of the design rests on. A press the editor does not want has
 * to be refused, or the canvas cannot pan while a step that owns a shape is open – which is both of
 * the steps where people position the map. And the editor has to notice its shape being replaced,
 * because a derived area is rewritten every time the subject moves.
 */
class EditToolTest {

    private static final LonLatBounds BOHEMIA = new LonLatBounds(12.0, 48.0, 18.0, 51.0);
    private static final LonLatBounds ELSEWHERE = new LonLatBounds(2.0, 42.0, 5.0, 44.0);

    private static Camera camera() {
        final Camera c = new Camera();
        c.setViewSize(800, 600);
        c.centerOn(15.0, 49.5, 6);
        return c;
    }

    private static MouseEvent at(final Camera c, final double lon, final double lat,
                                 final MouseButton button) {
        return atPixel(c.toScreenX(net.trdlo.wtf.tiles.Mercator.lonToUnitX(lon)),
                c.toScreenY(net.trdlo.wtf.tiles.Mercator.latToUnitY(lat)), button);
    }

    private static MouseEvent atPixel(final double x, final double y, final MouseButton button) {
        return new MouseEvent(MouseEvent.MOUSE_PRESSED, x, y, 0, 0, button, 1,
                false, false, false, false,
                button == MouseButton.PRIMARY, false, button == MouseButton.SECONDARY,
                false, false, true, null);
    }

    private static EditTool on(final ObjectProperty<Geometry> target, final EditTool.Mode mode,
                               final Runnable changed) {
        return new EditTool(ShapeTool.Slot.SUBJECT, mode, target, g -> { }, changed);
    }

    // --------------------------------------------------------------------------- press and refuse

    @Test
    @DisplayName("a press that misses the shape is refused, so the canvas can pan")
    void aPressOnEmptyMapIsRefused() {
        final Camera c = camera();
        final ObjectProperty<Geometry> target = new SimpleObjectProperty<>(Shapes.rectangle(BOHEMIA));
        final EditTool t = on(target, EditTool.Mode.FULL, () -> { });

        assertFalse(t.pressed(at(c, 15.0, 49.5, MouseButton.PRIMARY), c),
                "the middle of the shape is not a handle");
    }

    @Test
    @DisplayName("an editor over an empty slot refuses everything rather than misbehaving")
    void anEmptySlotIsHarmless() {
        final Camera c = camera();
        final EditTool t = on(new SimpleObjectProperty<>(), EditTool.Mode.FULL, () -> { });
        assertFalse(t.pressed(at(c, 15.0, 49.5, MouseButton.PRIMARY), c));
    }

    @Test
    @DisplayName("a press on a corner is taken, and dragging it moves that corner")
    void aPressOnAHandleIsTaken() {
        final Camera c = camera();
        final ObjectProperty<Geometry> target = new SimpleObjectProperty<>(Shapes.rectangle(BOHEMIA));
        final EditTool t = on(target, EditTool.Mode.FULL, () -> { });

        assertTrue(t.pressed(at(c, 12.0, 51.0, MouseButton.PRIMARY), c), "the northwest corner");
        assertTrue(t.dragged(at(c, 11.0, 51.5, MouseButton.PRIMARY), c));
        t.released(at(c, 11.0, 51.5, MouseButton.PRIMARY), c);

        final LonLatBounds after = Shapes.boundsOf(target.get());
        assertEquals(11.0, after.west(), 1e-6);
        assertEquals(51.5, after.north(), 1e-6);
    }

    // ------------------------------------------------------------------------ one write per gesture

    /**
     * A drag is one change to the shape, whatever handle it holds.
     * <p>
     * Every write works the area out again and is a step of undo, so a handle that wrote on every
     * mouse move made a hundred steps of one drag. The pin did, and the corners never have.
     */
    @Test
    @DisplayName("a drag writes the shape once, when it is let go – a corner, a vertex and the pin alike")
    void oneWritePerGesture() {
        final Camera c = camera();
        final Geometry triangle = Shapes.GF.createPolygon(new org.locationtech.jts.geom.Coordinate[]{
                new org.locationtech.jts.geom.Coordinate(12, 48),
                new org.locationtech.jts.geom.Coordinate(18, 48),
                new org.locationtech.jts.geom.Coordinate(15, 51),
                new org.locationtech.jts.geom.Coordinate(12, 48)});

        assertEquals(1, writesOfADrag(c, Shapes.rectangle(BOHEMIA), at(c, 12.0, 51.0, MouseButton.PRIMARY)),
                "a rectangle's corner");
        assertEquals(1, writesOfADrag(c, triangle, at(c, 15.0, 51.0, MouseButton.PRIMARY)),
                "a polygon's vertex");

        // A person takes a pin by its head, which stands above the place it marks
        final MouseEvent head = at(c, 15.0, 49.5, MouseButton.PRIMARY);
        final ObjectProperty<Geometry> pin = new SimpleObjectProperty<>(Shapes.point(15.0, 49.5));
        assertEquals(1, writesOfADrag(c, pin, atPixel(head.getX(), head.getY() - EditTool.PIN_HEAD_ABOVE,
                MouseButton.PRIMARY)), "the pin");
        assertTrue(Shapes.isPoint(pin.get()), "still a place");
        final LonLatBounds where = Shapes.boundsOf(pin.get());
        assertEquals(16.5, (where.west() + where.east()) / 2, 1e-6, "where the drag ended");
        assertEquals(50.0, (where.south() + where.north()) / 2, 1e-6);
    }

    private static int writesOfADrag(final Camera c, final Geometry shape, final MouseEvent press) {
        return writesOfADrag(c, new SimpleObjectProperty<>(shape), press);
    }

    /** Presses, drags three times and lets go; the changes the shape saw, which must all be at the end. */
    private static int writesOfADrag(final Camera c, final ObjectProperty<Geometry> target,
                                     final MouseEvent press) {
        final EditTool t = on(target, EditTool.Mode.FULL, () -> { });
        final AtomicInteger writes = new AtomicInteger();
        target.addListener((o, was, is) -> writes.incrementAndGet());

        assertTrue(t.pressed(press, c), "the press lands on a handle");
        for (final double[] to : new double[][]{{15.5, 49.7}, {16.0, 49.9}, {16.5, 50.0}}) {
            assertTrue(t.dragged(at(c, to[0], to[1], MouseButton.PRIMARY), c));
        }
        assertEquals(0, writes.get(), "nothing is written while the handle is still held");
        t.released(at(c, 16.5, 50.0, MouseButton.PRIMARY), c);
        return writes.get();
    }

    /** A small five-cornered island, about a tenth of a degree across. */
    private static Geometry island() {
        final org.locationtech.jts.geom.GeometryFactory f = new org.locationtech.jts.geom.GeometryFactory();
        final org.locationtech.jts.geom.Coordinate[] ring = new org.locationtech.jts.geom.Coordinate[6];
        for (int i = 0; i < 5; i++) {
            final double a = 2 * Math.PI * i / 5;
            ring[i] = new org.locationtech.jts.geom.Coordinate(15.0 + 0.05 * Math.sin(a), 49.5 + 0.05 * Math.cos(a));
        }
        ring[5] = ring[0];
        return f.createPolygon(ring);
    }

    @Test
    @DisplayName("an island a few pixels across has no handles, and has them once it is zoomed into")
    void aTinyRingHasNoHandles() {
        final ObjectProperty<Geometry> target = new SimpleObjectProperty<>(island());
        final EditTool t = on(target, EditTool.Mode.FULL, () -> { });

        // Zoom 6: the island is about five pixels wide, and its five handles would be one clump
        final Camera far = camera();
        assertFalse(t.pressed(at(far, 15.0, 49.55, MouseButton.PRIMARY), far),
                "no handle on its northern corner");

        final Camera near = new Camera();
        near.setViewSize(800, 600);
        near.centerOn(15.0, 49.5, 12);
        assertTrue(t.pressed(at(near, 15.0, 49.55, MouseButton.PRIMARY), near),
                "at zoom 12 the same corner is a handle");
    }

    private static MouseEvent move(final Camera c, final double lon, final double lat) {
        final double x = c.toScreenX(net.trdlo.wtf.tiles.Mercator.lonToUnitX(lon));
        final double y = c.toScreenY(net.trdlo.wtf.tiles.Mercator.latToUnitY(lat));
        return new MouseEvent(MouseEvent.MOUSE_MOVED, x, y, 0, 0, MouseButton.NONE, 0,
                false, false, false, false, false, false, false, false, false, true, null);
    }

    private static MouseEvent moveToPixel(final double x, final double y) {
        return new MouseEvent(MouseEvent.MOUSE_MOVED, x, y, 0, 0, MouseButton.NONE, 0,
                false, false, false, false, false, false, false, false, false, true, null);
    }

    @Test
    @DisplayName("the box a part is tested against first reaches as far as its hover does")
    void theBoxIsAsWideAsTheHover() {
        final Camera c = camera();
        final EditTool t = on(new SimpleObjectProperty<>(Shapes.rectangle(BOHEMIA)), EditTool.Mode.FULL, () -> { });
        final double east = c.toScreenX(net.trdlo.wtf.tiles.Mercator.lonToUnitX(BOHEMIA.east()));
        final double y = c.toScreenY(net.trdlo.wtf.tiles.Mercator.latToUnitY(49.5));

        assertFalse(t.moved(moveToPixel(east + EditTool.HOVER_RADIUS + 10, y), c), "well clear of it");
        assertTrue(t.moved(moveToPixel(east + EditTool.HOVER_RADIUS - 4, y), c),
                "outside its box, but within reach of its edge");
    }

    @Test
    @DisplayName("a mouse move over one part does not walk the vertices of a border somewhere else")
    void aFarBorderCostsNothing() {
        final Camera c = camera();
        // A surveyed border's worth of vertices, around a place far from the pointer
        final int n = 200_000;
        final org.locationtech.jts.geom.Coordinate[] ring = new org.locationtech.jts.geom.Coordinate[n + 1];
        for (int i = 0; i < n; i++) {
            final double a = 2 * Math.PI * i / n;
            ring[i] = new org.locationtech.jts.geom.Coordinate(3.5 + Math.cos(a), 43 + Math.sin(a) * 0.8);
        }
        ring[n] = ring[0];
        final Geometry both = Shapes.GF.createMultiPolygon(new org.locationtech.jts.geom.Polygon[]{
                (org.locationtech.jts.geom.Polygon) Shapes.rectangle(BOHEMIA), Shapes.GF.createPolygon(ring)});
        final EditTool t = on(new SimpleObjectProperty<>(both), EditTool.Mode.FULL, () -> { });

        // Walking the far ring on each of these was four hundred million projections
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () -> {
            for (int i = 0; i < 2_000; i++) {
                t.moved(move(c, 14.0 + (i % 100) * 0.03, 49.5), c);
            }
        });
    }

    @Test
    @DisplayName("handles show on the part under the pointer, and a press finds its own")
    void handlesFollowThePointer() {
        final Camera c = camera();
        final Geometry two = Shapes.rectangle(BOHEMIA).union(Shapes.rectangle(new LonLatBounds(20.0, 48.0, 22.0, 50.0)));
        final ObjectProperty<Geometry> target = new SimpleObjectProperty<>(two);
        final EditTool t = on(target, EditTool.Mode.FULL, () -> { });

        assertTrue(t.moved(move(c, 15.0, 49.5), c), "into the first part: its handles appear");
        assertFalse(t.moved(move(c, 15.1, 49.6), c), "still inside it: nothing to repaint");
        assertTrue(t.moved(move(c, 21.0, 49.0), c), "into the other one: they move with the pointer");
        assertTrue(t.exited(), "off the map: they go");

        // No move before the press: the press decides for itself which part it is on
        assertTrue(t.pressed(at(c, 12.0, 51.0, MouseButton.PRIMARY), c), "the first part's corner");
    }

    // ------------------------------------------------------------------- the shape under the tool

    @Test
    @DisplayName("the editor follows its shape when something else replaces it")
    void anExternalChangeIsReadAgain() {
        final Camera c = camera();
        final ObjectProperty<Geometry> target = new SimpleObjectProperty<>(Shapes.rectangle(BOHEMIA));
        final EditTool t = on(target, EditTool.Mode.FULL, () -> { });

        target.set(Shapes.rectangle(ELSEWHERE));

        assertTrue(t.pressed(at(c, 2.0, 44.0, MouseButton.PRIMARY), c), "a corner of the new shape");
        assertFalse(t.pressed(at(c, 12.0, 51.0, MouseButton.PRIMARY), c), "and not of the old one");
    }

    @Test
    @DisplayName("a swapped-out editor lets go of the shape")
    void cancelDetachesTheListener() {
        final ObjectProperty<Geometry> target = new SimpleObjectProperty<>(Shapes.rectangle(BOHEMIA));
        final AtomicInteger redraws = new AtomicInteger();
        final EditTool t = on(target, EditTool.Mode.FULL, redraws::incrementAndGet);

        target.set(Shapes.rectangle(ELSEWHERE));
        assertEquals(1, redraws.get());

        t.cancel();
        target.set(Shapes.rectangle(BOHEMIA));
        assertEquals(1, redraws.get(), "a step change must not leave a listener behind");
    }

    // ------------------------------------------------------------------------ the area stays a box

    @Test
    @DisplayName("the area's editor will not let a rectangle stop being one")
    void rectangleModeKeepsFourCorners() {
        final Camera c = camera();
        final ObjectProperty<Geometry> target = new SimpleObjectProperty<>(Shapes.rectangle(BOHEMIA));
        final EditTool t = on(target, EditTool.Mode.RECTANGLE, () -> { });

        // No midpoint to grab on an edge, so no fifth corner can be born there
        assertFalse(t.pressed(at(c, 15.0, 51.0, MouseButton.PRIMARY), c), "the middle of the top edge");
        // and no corner can be taken away
        assertFalse(t.pressed(at(c, 12.0, 51.0, MouseButton.SECONDARY), c));
        assertEquals(5, target.get().getNumPoints(), "still a closed four-corner ring");
    }

    @Test
    @DisplayName("the subject's editor still allows both, a side to move and a corner to take away")
    void fullModeAllowsWhatRectangleModeRefuses() {
        final Camera c = camera();
        final ObjectProperty<Geometry> target = new SimpleObjectProperty<>(Shapes.rectangle(BOHEMIA));
        final EditTool t = on(target, EditTool.Mode.FULL, () -> { });

        assertTrue(t.pressed(at(c, 15.0, 51.0, MouseButton.PRIMARY), c),
                "the midpoint of the top edge is a handle here");
    }

    @Test
    @DisplayName("a rectangle has no corners to drag when it is not one")
    void whatCountsAsEditable() {
        final Geometry box = Shapes.rectangle(BOHEMIA);
        final Geometry triangle = Shapes.GF.createPolygon(new org.locationtech.jts.geom.Coordinate[]{
                new org.locationtech.jts.geom.Coordinate(12, 48),
                new org.locationtech.jts.geom.Coordinate(18, 48),
                new org.locationtech.jts.geom.Coordinate(15, 51),
                new org.locationtech.jts.geom.Coordinate(12, 48)});

        assertTrue(EditTool.isEditable(box, EditTool.Mode.RECTANGLE));
        assertFalse(EditTool.isEditable(triangle, EditTool.Mode.RECTANGLE));
        assertTrue(EditTool.isEditable(triangle, EditTool.Mode.FULL));
        assertFalse(EditTool.isEditable(null, EditTool.Mode.FULL));
    }

    // -------------------------------------------------------------------------------- drawing

    /**
     * ProjectOverlay draws the subject's fill every frame regardless of what is editing it, at
     * 10 %; the editor filling it again at rest stacked two 10 % fills into 19 %. The area has no
     * fill of its own there, so the editor's is the only one.
     */
    @Test
    @DisplayName("the resting subject is not filled twice, but the resting area still gets its tint")
    void theSubjectIsNotFilledTwiceAtRest() {
        final ObjectProperty<Geometry> target = new SimpleObjectProperty<>(Shapes.rectangle(BOHEMIA));
        final EditTool subject = on(target, EditTool.Mode.FULL, () -> { });
        assertFalse(subject.fillsAtRest(), "ProjectOverlay already fills the subject once");

        final EditTool area = new EditTool(ShapeTool.Slot.AREA, EditTool.Mode.RECTANGLE, target, g -> { }, () -> { });
        assertTrue(area.fillsAtRest(), "the area has no fill of its own to stack with");
    }
}
