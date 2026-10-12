package net.trdlo.wtf.studio.project;

import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The outline itself, grown, is worked out off the FX thread once typing pauses – and nothing that
 * reads the area for good, a save or an export, gets the one from before.
 */
class BackgroundAreaTest {

    private static final Geometry BOHEMIA = Shapes.rectangle(new LonLatBounds(12.1, 48.6, 18.8, 51.0));

    private static ProjectModel model(final Runnable onFx) {
        final ProjectModel m = new ProjectModel();
        // The "FX thread" is whoever finishes the work; the count says how often an area came back
        m.deriveInBackground(r -> {
            r.run();
            onFx.run();
        });
        m.simplifyProperty().set(Shapes.Simplify.NONE);
        return m;
    }

    @Test
    @DisplayName("typing 50000 is one derivation, not five, and the area waits for it")
    void typingIsOneDerivation() throws InterruptedException {
        final AtomicInteger delivered = new AtomicInteger();
        // The subject's parts joined for the job, once, and the area
        final CountDownLatch done = new CountDownLatch(2);
        final ProjectModel m = model(() -> {
            delivered.incrementAndGet();
            done.countDown();
        });
        m.subjectProperty().set(BOHEMIA);
        assertTrue(m.areaPendingProperty().get(), "worked out later, not now");

        for (final double typed : new double[]{5, 50, 500, 5000, 50000}) {
            m.bufferValueProperty().set(typed);
        }
        assertTrue(done.await(5, TimeUnit.SECONDS));
        Thread.sleep(2 * ProjectModel.DERIVE_DELAY_MILLIS);     // anything else would have arrived by now

        assertEquals(2, delivered.get(), "the union, and only the last value's area");
        assertFalse(m.areaPendingProperty().get());
        final Geometry expected = Shapes.areaFrom(BOHEMIA, Shapes.Simplify.NONE, 50000);
        assertTrue(Shapes.reproduces(m.areaProperty().get(), expected), "the area for 50 km");
        assertEquals(ProjectModel.AreaSource.FROM_SUBJECT, m.areaSourceProperty().get(),
                "its own result does not count as somebody taking the area over");
    }

    @Test
    @DisplayName("a save or an export gets the finished area, not the one before")
    void settlingIsImmediate() {
        final ProjectModel m = model(() -> { });
        m.subjectProperty().set(BOHEMIA);
        m.bufferValueProperty().set(20000);
        assertTrue(m.areaPendingProperty().get());

        m.settleArea();
        assertFalse(m.areaPendingProperty().get());
        assertTrue(Shapes.reproduces(m.areaProperty().get(),
                Shapes.areaFrom(BOHEMIA, Shapes.Simplify.NONE, 20000)));
    }

    /** Two overlapping parts, to be joined; a box around them is cheap, the join is not. */
    private static final Geometry TWO_PARTS = BOHEMIA.getFactory().createMultiPolygon(new Polygon[]{
            (Polygon) BOHEMIA, (Polygon) Shapes.rectangle(new LonLatBounds(16.8, 47.7, 22.6, 49.6))});

    /** A model whose "FX thread" is a queue the test empties when it chooses. */
    private static ProjectModel queued(final BlockingQueue<Runnable> fx) {
        final ProjectModel m = new ProjectModel();
        m.deriveInBackground(fx::add);
        final Provider p = ProviderCatalog.load(null).all().iterator().next();
        m.providerProperty().set(p);
        m.themeProperty().set(p.themes().getFirst());
        m.simplifyProperty().set(Shapes.Simplify.BOUNDING_BOX);    // the area itself stays immediate
        return m;
    }

    @Test
    @DisplayName("the subject's parts are joined on the worker, once, and the build waits for it")
    void theUnionIsTheWorkers() throws InterruptedException {
        final BlockingQueue<Runnable> fx = new LinkedBlockingQueue<>();
        final ProjectModel m = queued(fx);
        m.subjectProperty().set(TWO_PARTS);
        assertNotNull(m.areaProperty().get(), "the box is there at once");

        assertEquals(net.trdlo.wtf.studio.I18n.get("area.working"), m.build().problem(),
                "not joined on this thread");
        final int before = m.revisionProperty().get();
        final Runnable landed = fx.poll(5, TimeUnit.SECONDS);
        assertNotNull(landed, "the worker answered");
        landed.run();
        assertTrue(m.revisionProperty().get() > before, "and the job is built again");
        assertTrue(m.build().ok(), m.build().problem());
        assertEquals(1, m.build().job().subject().getNumGeometries(), "one shape, joined");

        m.baseNameProperty().set("again");
        assertTrue(m.build().ok(), "a build after another change does not ask again");
        assertNull(fx.poll(2 * ProjectModel.DERIVE_DELAY_MILLIS, TimeUnit.MILLISECONDS), "nothing else came back");
    }

    /** The same two parts, one of them moved: a vertex drag that ended. */
    private static final Geometry TWO_PARTS_MOVED = BOHEMIA.getFactory().createMultiPolygon(new Polygon[]{
            (Polygon) BOHEMIA, (Polygon) Shapes.rectangle(new LonLatBounds(17.1, 47.7, 22.6, 49.6))});

    @Test
    @DisplayName("undo before the join lands, then redo: the worker is asked again and the build is ok")
    void aRedoAsksAgain() throws InterruptedException {
        final BlockingQueue<Runnable> fx = new LinkedBlockingQueue<>();
        final ProjectModel m = queued(fx);
        m.subjectProperty().set(TWO_PARTS);
        final Runnable first = fx.poll(5, TimeUnit.SECONDS);
        assertNotNull(first);
        first.run();
        assertTrue(m.build().ok(), m.build().problem());
        m.history().closeStep();

        m.subjectProperty().set(TWO_PARTS_MOVED);
        m.history().closeStep();
        final Runnable moved = fx.poll(5, TimeUnit.SECONDS);
        assertNotNull(moved, "the worker answered for the moved subject");
        m.history().undo();                     // Ctrl+Z before the answer lands
        assertSame(TWO_PARTS, m.subjectProperty().get());
        moved.run();                            // lands for a subject that is gone: dropped
        assertTrue(m.build().ok(), "the first subject's join is still there");

        m.history().redo();                     // Ctrl+Y puts the same instance back
        assertSame(TWO_PARTS_MOVED, m.subjectProperty().get());
        final Runnable again = fx.poll(5, TimeUnit.SECONDS);
        assertNotNull(again, "the worker is asked again");
        again.run();
        assertTrue(m.build().ok(), m.build().problem());
        assertEquals(1, m.build().job().subject().getNumGeometries(), "one shape, joined");
    }

    @Test
    @DisplayName("undo and redo before the join lands: the answer is kept, and nothing is asked twice")
    void aRedoBeforeItLandsKeepsIt() throws InterruptedException {
        final BlockingQueue<Runnable> fx = new LinkedBlockingQueue<>();
        final ProjectModel m = queued(fx);
        m.subjectProperty().set(TWO_PARTS);
        final Runnable first = fx.poll(5, TimeUnit.SECONDS);
        assertNotNull(first);
        first.run();
        m.history().closeStep();

        m.subjectProperty().set(TWO_PARTS_MOVED);
        m.history().closeStep();
        final Runnable moved = fx.poll(5, TimeUnit.SECONDS);
        assertNotNull(moved);
        m.history().undo();
        m.history().redo();
        moved.run();                            // the subject is current again: kept
        assertTrue(m.build().ok(), m.build().problem());
        assertNull(fx.poll(2 * ProjectModel.DERIVE_DELAY_MILLIS, TimeUnit.MILLISECONDS), "not asked again");
    }

    @Test
    @DisplayName("an export does not wait for the worker to join the subject")
    void settlingJoinsNow() {
        final ProjectModel m = queued(new LinkedBlockingQueue<>());
        m.subjectProperty().set(TWO_PARTS);
        m.settleUnions();
        assertTrue(m.build().ok(), m.build().problem());
    }

    @Test
    @DisplayName("the cheap fits stay immediate")
    void aBoxIsImmediate() {
        final ProjectModel m = model(() -> { });
        m.simplifyProperty().set(Shapes.Simplify.BOUNDING_BOX);
        m.subjectProperty().set(BOHEMIA);
        m.bufferValueProperty().set(10000);
        assertFalse(m.areaPendingProperty().get());
        assertNotNull(m.areaProperty().get());
    }

    // ---------------------------------------------------------- what the outline itself is made of

    /**
     * The outline itself is as detailed as the subject, and every vertex dragged works out another,
     * which the step of undo keeps. Worked out afresh, it shared nothing with the subject beside it
     * and was half of what the step kept; it is made of the subject's points wherever it has them.
     */
    @Test
    @DisplayName("the outline itself, worked out on the worker, is made of the subject's own points")
    void theOutlineIsMadeOfTheSubjectsPoints() throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(2);      // the subject's parts joined, and the area
        final ProjectModel m = model(done::countDown);
        m.subjectProperty().set(TWO_PARTS);
        assertTrue(done.await(5, TimeUnit.SECONDS));

        final Geometry area = m.areaProperty().get();
        assertTrue(area.equalsExact(Shapes.areaFrom(TWO_PARTS, Shapes.Simplify.NONE, 0)), "the outline Shapes.areaFrom gives, value for value");
        final Map<Coordinate, Coordinate> subject = new HashMap<>();
        for (final Coordinate c : TWO_PARTS.getCoordinates()) {
            subject.putIfAbsent(c, c);
        }
        final Coordinate[] ring = ((Polygon) area).getExteriorRing().getCoordinates();
        int shared = 0;
        for (int i = 0; i < ring.length - 1; i++) {
            final Coordinate same = subject.get(ring[i]);
            if (same != null) {
                assertSame(same, ring[i], "a point the subject has is the subject's");
                shared++;
            }
        }
        assertEquals(6, shared, "the six corners the join keeps; the two where the edges cross are its own");
        assertEquals(9, ring.length, "and those two, and the ring's closing point");
    }

    /**
     * A point is exchanged for one of the subject's only when they are the same point to the bit:
     * {@link Coordinate#equals} takes x and y alone, and -0 for 0 – and (-0, -0) has the hash of
     * (0, 0), so a map of points finds the one for the other – while the area has to stay the one
     * worked out, value for value.
     */
    @Test
    @DisplayName("a point is shared only when it is the same to the bit: not 0 for -0, nor one at another height")
    void onlyThePointToTheBitIsShared() {
        final Coordinate zero = new Coordinate(0.0, 0.0), high = new Coordinate(1.0, 1.0, 5.0);
        final Coordinate east = new Coordinate(1.0, 0.0), eastToo = new Coordinate(1.0, 0.0);
        final Geometry worked = Shapes.GF.createPolygon(new Coordinate[]{zero, east, high, zero.copy()});
        final Coordinate below = new Coordinate(-0.0, -0.0);
        final Geometry other = Shapes.GF.createPolygon(new Coordinate[]{
                below, eastToo, new Coordinate(1.0, 1.0), below.copy()});

        final Coordinate[] made = ProjectDerivation.withPointsOf(worked, null, other).getCoordinates();
        assertSame(zero, made[0], "0 is not -0");
        assertSame(eastToo, made[1], "the same point to the bit is the other shape's");
        assertSame(high, made[2], "a point with a height of its own is its own");
        assertTrue(ProjectDerivation.withPointsOf(worked, other).equalsExact(worked), "and the shape the same");
    }
}
