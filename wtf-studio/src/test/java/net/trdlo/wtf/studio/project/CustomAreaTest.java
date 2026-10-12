package net.trdlo.wtf.studio.project;

import net.trdlo.wtf.compose.ComposeJob;
import net.trdlo.wtf.compose.PrintRect;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A Custom area: any shape, grown and faded as the subject's fit is, taken over from the fit by a
 * corner dragged or by the radio, and dropped by the other radio in a step undo gives back.
 */
class CustomAreaTest {

    private static final ProviderCatalog CATALOG = ProviderCatalog.load(null);
    private static final LonLatBounds BOHEMIA = new LonLatBounds(12.0, 48.5, 18.9, 51.1);
    private static final Geometry TRIANGLE = Shapes.polygon(new Coordinate[]{
            new Coordinate(12.0, 48.5), new Coordinate(18.9, 48.8), new Coordinate(15.0, 51.1)});

    /** A project a job can be built from, with nothing unsaved; a worker behind it when {@code fx} is given. */
    private static ProjectModel sourced(final BlockingQueue<Runnable> fx) {
        final ProjectModel m = new ProjectModel();
        if (fx != null) {
            m.deriveInBackground(fx::add);
        }
        final Provider p = CATALOG.all().iterator().next();
        m.startOver(() -> {
            m.providerProperty().set(p);
            m.themeProperty().set(p.themes().getFirst());
        });
        return m;
    }

    private static ProjectModel custom(final Geometry shape, final double grow) {
        final ProjectModel m = sourced(null);
        m.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
        m.customProperty().set(shape);
        m.bufferValueProperty().set(grow);
        return m;
    }

    @Test
    @DisplayName("a Custom triangle grown by 10 km is the triangle grown, and the print is cut from the grown box")
    void aCustomAreaGrows() {
        final ProjectModel m = custom(TRIANGLE, 10_000);

        final Geometry grown = Shapes.areaFrom(TRIANGLE, Shapes.Simplify.NONE, 10_000);
        assertTrue(Shapes.reproduces(m.areaProperty().get(), grown), "the area is the shape grown");
        assertSame(TRIANGLE, m.areaFitProperty().get(), "and it grows from the shape itself");
        assertTrue(m.build().ok(), m.build().problem());
        final ComposeJob job = m.build().job();
        assertEquals(Shapes.boundsOf(grown), Shapes.boundsOf(job.fillShape()));
        final PrintRect print = job.print();
        assertEquals(m.printSize().apply(Shapes.boundsOf(grown)), print, "the paper fits the grown box");
        assertTrue(Shapes.boundsOf(job.fillShape()).west() < 12.0, "ten kilometers further west than the shape");
    }

    @Test
    @DisplayName("a Custom area fades from its shape: the job's fade starts at the shape made one")
    void aCustomAreaFades() {
        // Two overlapping parts, as the slot keeps them: the edge is their union, not either of them
        final Geometry twoParts = Shapes.GF.createMultiPolygon(new Polygon[]{(Polygon) TRIANGLE,
                Shapes.rectangle(new LonLatBounds(17.0, 48.0, 20.0, 50.0))});
        final ProjectModel m = custom(twoParts, 20_000);
        assertFalse(m.fades(), "not asked for");
        assertNull(m.build().job().fadeFrom());

        m.areaFadeProperty().set(true);
        assertTrue(m.fades(), "asked for, grown, and a shape to grow from");
        final Geometry from = m.build().job().fadeFrom();
        assertNotNull(from);
        assertEquals(1, from.getNumGeometries(), "one shape");
        assertEquals(0.0, Shapes.deviation(Shapes.fitOf(twoParts, Shapes.Simplify.NONE), from), 1e-12,
                "the Custom shape made one, as the command line works it out");
        assertSame(from, m.build().job().fadeFrom(), "kept, not made again");
        m.titleProperty().set("another revision");
        assertSame(from, m.build().job().fadeFrom(), "kept across revisions that leave the shape alone");

        m.bufferValueProperty().set(0);
        assertFalse(m.fades(), "nothing grown, nothing to fade across");
        assertNull(m.build().job().fadeFrom());
    }

    @Test
    @DisplayName("a Custom area's fade waits for the worker to join its shape, and an export does not wait")
    void aCustomFadeIsJoinedOnTheWorker() throws InterruptedException {
        final BlockingQueue<Runnable> fx = new LinkedBlockingQueue<>();
        final ProjectModel m = sourced(fx);
        m.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
        m.customProperty().set(TRIANGLE);
        m.bufferValueProperty().set(10_000);
        m.areaFadeProperty().set(true);
        assertTrue(m.areaPendingProperty().get(), "a Custom area is worked out on the worker");
        m.settleArea();
        assertEquals(I18n.get("area.working"), m.build().problem(), "the edge of the fade is on its way");

        final Runnable landed = fx.poll(5, TimeUnit.SECONDS);
        assertNotNull(landed, "the worker answered");
        landed.run();
        assertTrue(m.build().ok(), m.build().problem());
        assertNotNull(m.build().job().fadeFrom());

        // A new shape, and the export settles its join here rather than refusing
        m.customProperty().set(Shapes.rectangle(BOHEMIA));
        m.settleArea();
        assertFalse(m.build().ok(), "on its way again");
        m.settleUnions();
        assertTrue(m.build().ok(), m.build().problem());
        assertEquals(0.0, Shapes.deviation(Shapes.rectangle(BOHEMIA), m.build().job().fadeFrom()), 1e-12);
    }

    @Test
    @DisplayName("a corner of the subject's box dragged makes the box the Custom shape, and the growth stays")
    void aTakeoverKeepsTheGrowth() {
        final ProjectModel m = sourced(null);
        m.subjectProperty().set(Shapes.rectangle(BOHEMIA));
        m.simplifyProperty().set(Shapes.Simplify.BOUNDING_BOX);
        m.bufferValueProperty().set(20_000);
        assertEquals(BOHEMIA, Shapes.boundsOf(m.areaFitProperty().get()), "the handles are on the box, ungrown");
        assertNotEquals(BOHEMIA, Shapes.boundsOf(m.areaProperty().get()), "and the area is the box grown");

        final Geometry moved = Shapes.rectangle(new LonLatBounds(11.0, 48.0, 18.9, 51.1));
        m.areaFitProperty().set(moved);

        assertEquals(ProjectModel.AreaSource.CUSTOM, m.areaSourceProperty().get());
        assertSame(moved, m.customProperty().get());
        assertEquals("subject", m.customOriginProperty().get(), "worked out from the subject, then moved");
        assertEquals(20_000, m.bufferValueProperty().get(), 1e-9, "the growth is unchanged");
        assertTrue(Shapes.reproduces(m.areaProperty().get(), Shapes.areaFrom(moved, Shapes.Simplify.NONE, 20_000)),
                "and the area is the moved box grown");
        assertSame(moved, m.areaFitProperty().get(), "with the handles still on the box");
    }

    @Test
    @DisplayName("Custom takes the fit over; From the subject drops the Custom shape, and one undo brings it back")
    void theRadioTakesOverAndDrops() {
        final ProjectModel m = sourced(null);
        final History h = m.history();
        m.subjectProperty().set(Shapes.rectangle(BOHEMIA));
        m.simplifyProperty().set(Shapes.Simplify.CONVEX_HULL);
        m.bufferValueProperty().set(5_000);
        h.closeStep();
        final Geometry fit = m.areaFitProperty().get();
        final Geometry derived = m.areaProperty().get();

        m.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);         // the radio
        h.closeStep();
        assertSame(fit, m.customProperty().get(), "the fit, taken over as it is");
        assertEquals("subject", m.customOriginProperty().get());
        assertTrue(Shapes.reproduces(derived, m.areaProperty().get()), "and nothing on the paper changed");

        // Something of one's own, then back to the subject
        final Geometry own = Shapes.withPart(m.customProperty().get(), TRIANGLE, Shapes.Role.AREA);
        m.customProperty().set(own);
        h.closeStep();
        m.areaSourceProperty().set(ProjectModel.AreaSource.FROM_SUBJECT);
        h.closeStep();
        assertNull(m.customProperty().get(), "dropped, not kept out of sight");
        assertNull(m.customOriginProperty().get());
        assertTrue(Shapes.reproduces(derived, m.areaProperty().get()), "the subject's area again");

        h.undo();
        assertEquals(ProjectModel.AreaSource.CUSTOM, m.areaSourceProperty().get(), "one undo: the source");
        assertSame(own, m.customProperty().get(), "and the shape with it");
        assertTrue(Shapes.reproduces(m.areaProperty().get(), Shapes.areaFrom(own, Shapes.Simplify.NONE, 5_000)));

        h.redo();
        assertEquals(ProjectModel.AreaSource.FROM_SUBJECT, m.areaSourceProperty().get());
        assertNull(m.customProperty().get());
    }

    @Test
    @DisplayName("choosing Custom with no subject takes nothing over, and asks for a shape")
    void customWithNothingToTakeOver() {
        final ProjectModel m = sourced(null);
        m.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
        assertNull(m.customProperty().get());
        assertNull(m.areaProperty().get());
        assertEquals(I18n.get("problem.noArea"), m.build().problem());

        m.subjectProperty().set(Shapes.rectangle(BOHEMIA));
        assertNull(m.areaProperty().get(), "a subject picked now is a subject, not the Custom shape");
    }
}
