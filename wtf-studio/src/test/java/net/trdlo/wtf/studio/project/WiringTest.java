package net.trdlo.wtf.studio.project;

import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a listener on the model sees, and when: the order the model's own listeners run in, the
 * flag that tells its own writes from a person's, and the work that comes back from the worker.
 * <p>
 * Nothing here is about what a setting means. It is about the wiring between the properties, the
 * area worked out from the subject and the job built from both – written before the model was split
 * into those three, so that the split had to keep every one of these orders.
 */
class WiringTest {

    private static final ProviderCatalog CATALOG = ProviderCatalog.load(null);
    private static final Geometry BOHEMIA = Shapes.rectangle(new LonLatBounds(12.1, 48.6, 18.8, 51.0));
    private static final Geometry BY_HAND = Shapes.rectangle(new LonLatBounds(13.0, 49.0, 17.0, 50.0));

    /** Two overlapping parts, which the job wants joined. */
    private static final Geometry TWO_PARTS = parts(BOHEMIA, new LonLatBounds(16.8, 47.7, 22.6, 49.6));
    private static final Geometry TWO_OTHERS = parts(Shapes.rectangle(new LonLatBounds(2.0, 42.0, 5.0, 44.0)),
            new LonLatBounds(4.0, 43.0, 7.0, 46.0));

    private static Geometry parts(final Geometry first, final LonLatBounds second) {
        return Shapes.GF.createMultiPolygon(new Polygon[]{(Polygon) first, Shapes.rectangle(second)});
    }

    /** A source, set as where the project starts, so a job can be built. */
    private static ProjectModel sourced(final ProjectModel m) {
        final Provider p = CATALOG.all().iterator().next();
        m.startOver(() -> {
            m.providerProperty().set(p);
            m.themeProperty().set(p.themes().getFirst());
        });
        return m;
    }

    /** A model whose "FX thread" is a queue the test empties when it chooses. */
    private static ProjectModel queued(final BlockingQueue<Runnable> fx) {
        final ProjectModel m = new ProjectModel();
        m.deriveInBackground(fx::add);
        return sourced(m);
    }

    /** Runs what the worker sends back, waiting for each of {@code count} answers. */
    private static void take(final BlockingQueue<Runnable> fx, final int count) throws InterruptedException {
        for (int i = 0; i < count; i++) {
            final Runnable answer = fx.poll(5, TimeUnit.SECONDS);
            assertNotNull(answer, "the worker answered");
            answer.run();
        }
    }

    /** Runs whatever has come back by now, after waiting long enough for anything delayed to have come. */
    private static void drainLate(final BlockingQueue<Runnable> fx) throws InterruptedException {
        Thread.sleep(2 * ProjectModel.DERIVE_DELAY_MILLIS);
        for (Runnable late = fx.poll(); late != null; late = fx.poll()) {
            late.run();
        }
    }

    // ------------------------------------------------------------------- the order listeners run in

    @Test
    @DisplayName("a revision listener hears of the subject, then of the edge the area grows from, then of the area")
    void theSubjectIsHeardBeforeItsArea() {
        final ProjectModel m = new ProjectModel();          // no worker: the box is worked out at once
        final List<Geometry[]> seen = new ArrayList<>();
        m.revisionProperty().addListener((o, a, b) -> seen.add(new Geometry[]{
                m.subjectProperty().get(), m.areaFitProperty().get(), m.areaProperty().get()}));

        m.subjectProperty().set(BOHEMIA);

        assertEquals(3, seen.size(), "the subject, the edge worked out from it, and the area grown from that");
        assertSame(BOHEMIA, seen.get(0)[0]);
        assertNull(seen.get(0)[1], "the subject is heard before anything is worked out from it");
        assertNull(seen.get(0)[2]);
        final Geometry fit = m.areaFitProperty().get();
        final Geometry area = m.areaProperty().get();
        assertNotNull(fit);
        assertNotNull(area);
        assertSame(fit, seen.get(1)[1]);
        assertNull(seen.get(1)[2], "the edge before the area, in the order undo puts them back");
        assertSame(fit, seen.get(2)[1]);
        assertSame(area, seen.get(2)[2]);
    }

    @Test
    @DisplayName("a listener on the subject from outside finds the area already worked out")
    void anOutsideSubjectListenerFindsTheArea() {
        final ProjectModel m = new ProjectModel();
        final int start = m.revisionProperty().get();
        final Object[] seen = new Object[2];
        m.subjectProperty().addListener((o, a, b) -> {
            seen[0] = m.areaProperty().get();
            seen[1] = m.revisionProperty().get();
        });

        m.subjectProperty().set(BOHEMIA);

        assertNotNull(seen[0], "the model's own listener ran first");
        assertEquals(start + 3, seen[1]);
    }

    @Test
    @DisplayName("a listener on the fit from outside finds the takeover already made")
    void anOutsideFitListenerFindsTheTakeover() {
        final ProjectModel m = new ProjectModel();
        m.subjectProperty().set(BOHEMIA);                   // the box is the subject's
        final List<String> order = new ArrayList<>();
        m.areaSourceProperty().addListener((o, a, b) -> order.add("source " + b));
        m.areaFitProperty().addListener((o, a, b) -> order.add("fit, from " + m.areaSourceProperty().get()
                + (m.customProperty().get() == b ? ", the Custom shape" : "")));

        m.areaFitProperty().set(BY_HAND);                   // a hand on a corner of the box

        assertEquals(List.of("source CUSTOM", "fit, from CUSTOM, the Custom shape"), order);
    }

    // --------------------------------------------------------------------- the subject made one

    @Test
    @DisplayName("the subject joined on the worker lands as one revision, is built with, and marks nothing unsaved")
    void aLandedUnionIsOneRevision() throws InterruptedException {
        final BlockingQueue<Runnable> fx = new LinkedBlockingQueue<>();
        final ProjectModel m = queued(fx);
        m.subjectProperty().set(TWO_PARTS);
        m.history().closeStep();
        assertEquals(I18n.get("area.working"), m.build().problem(), "the parts are not joined yet");
        final int before = m.revisionProperty().get();
        final boolean dirty = m.dirtyProperty().get();
        final boolean canUndo = m.history().canUndoProperty().get();

        take(fx, 1);

        assertEquals(before + 1, m.revisionProperty().get(), "one new revision");
        assertTrue(m.build().ok(), m.build().problem());
        assertEquals(dirty, m.dirtyProperty().get(), "not a change anybody made");
        assertEquals(canUndo, m.history().canUndoProperty().get());
        assertNull(fx.poll(2 * ProjectModel.DERIVE_DELAY_MILLIS, TimeUnit.MILLISECONDS), "and nothing else came");
    }

    @Test
    @DisplayName("the join of a subject that has been replaced since is dropped when it lands")
    void aUnionForAReplacedSubjectIsDropped() throws InterruptedException {
        final BlockingQueue<Runnable> fx = new LinkedBlockingQueue<>();
        final ProjectModel m = queued(fx);
        m.subjectProperty().set(TWO_PARTS);
        m.subjectProperty().set(TWO_OTHERS);
        final Runnable first = fx.poll(5, TimeUnit.SECONDS);
        final Runnable second = fx.poll(5, TimeUnit.SECONDS);
        assertNotNull(first);
        assertNotNull(second);
        final int before = m.revisionProperty().get();

        first.run();
        assertEquals(before, m.revisionProperty().get(), "the first subject's join answers an old question");
        second.run();
        assertEquals(before + 1, m.revisionProperty().get());

        assertTrue(m.build().ok(), m.build().problem());
        assertEquals(Shapes.boundsOf(TWO_OTHERS), Shapes.boundsOf(m.build().job().subject()),
                "the job has the subject that is there now");
    }

    @Test
    @DisplayName("joining the subject on the spot forgets the job without a revision, and twice changes nothing")
    void settlingTheSubjectForgetsTheJob() {
        final ProjectModel m = queued(new LinkedBlockingQueue<>());   // the worker's answer is never taken
        m.subjectProperty().set(TWO_PARTS);
        assertFalse(m.build().ok(), "the join is on its way");
        final int revision = m.revisionProperty().get();

        m.settleUnions();
        assertEquals(revision, m.revisionProperty().get(), "joining it now is not a change");
        final ProjectModel.Built joined = m.build();
        assertTrue(joined.ok(), joined.problem());

        m.settleUnions();
        assertSame(joined, m.build(), "already joined: the job is kept");
    }

    @Test
    @DisplayName("a job asked for inside a batch is the one with what was just written, before the revision moves")
    void theJobIsForgottenAtTheWrite() {
        final ProjectModel m = sourced(new ProjectModel());
        final ProjectModel.Built before = m.build();
        assertFalse(before.ok(), "nothing to print yet");
        final ProjectModel.Built[] inside = new ProjectModel.Built[1];
        m.subjectProperty().addListener((o, a, b) -> inside[0] = m.build());

        m.startOver(() -> m.subjectProperty().set(BOHEMIA));

        assertNotSame(before, inside[0], "not the job from before the batch");
        assertTrue(inside[0].ok(), "but the one with the subject in it: " + inside[0].problem());
    }

    @Test
    @DisplayName("an area settled on the spot joins the subject for whoever builds in the meantime")
    void settlingTheAreaJoinsTheSubjectMeanwhile() {
        final BlockingQueue<Runnable> fx = new LinkedBlockingQueue<>();
        final ProjectModel m = queued(fx);
        m.simplifyProperty().set(Shapes.Simplify.NONE);     // the outline itself: the area goes to the worker too
        m.subjectProperty().set(TWO_PARTS);
        assertTrue(m.areaPendingProperty().get());
        final ProjectModel.Built[] meanwhile = new ProjectModel.Built[1];
        m.areaProperty().addListener((o, a, b) -> meanwhile[0] = m.build());

        m.settleArea();

        assertNotNull(meanwhile[0], "the area arrived");
        assertTrue(meanwhile[0].ok(), "and a build then joined the subject here rather than waiting for the worker: "
                + meanwhile[0].problem());
    }

    // --------------------------------------------------------------------- the area on its way

    @Test
    @DisplayName("an undo puts the area back without sending the worker for it, and takes nothing over")
    void anUndoAsksTheWorkerForNothing() throws InterruptedException {
        final BlockingQueue<Runnable> fx = new LinkedBlockingQueue<>();
        final ProjectModel m = queued(fx);
        m.simplifyProperty().set(Shapes.Simplify.NONE);
        m.subjectProperty().set(BOHEMIA);
        take(fx, 2);                                        // the subject joined, and its outline
        m.history().closeStep();
        m.bufferValueProperty().set(20_000);
        take(fx, 1);                                        // the outline grown
        m.history().closeStep();

        m.history().undo();

        assertEquals(0, m.bufferValueProperty().get());
        assertEquals(ProjectModel.AreaSource.FROM_SUBJECT, m.areaSourceProperty().get(),
                "putting the area back is nobody taking it over");
        assertFalse(m.areaPendingProperty().get(), "the area came back with its step");
        assertNull(fx.poll(2 * ProjectModel.DERIVE_DELAY_MILLIS, TimeUnit.MILLISECONDS),
                "and the worker was not asked for it again");
    }

    @Test
    @DisplayName("a takeover while an area is being worked out drops the one on its way for the Custom shape's")
    void aTakeoverDropsTheAreaOnItsWay() throws InterruptedException {
        final BlockingQueue<Runnable> fx = new LinkedBlockingQueue<>();
        final ProjectModel m = queued(fx);
        m.simplifyProperty().set(Shapes.Simplify.NONE);
        m.subjectProperty().set(BOHEMIA);
        assertTrue(m.areaPendingProperty().get());
        assertSame(BOHEMIA, m.areaFitProperty().get(), "the outline itself of a box is the box, corners and all");

        m.areaFitProperty().set(BY_HAND);

        assertEquals(ProjectModel.AreaSource.CUSTOM, m.areaSourceProperty().get());
        assertTrue(m.areaPendingProperty().get(), "a Custom area is worked out on the worker too");
        drainLate(fx);
        assertFalse(m.areaPendingProperty().get());
        assertTrue(Shapes.reproduces(m.areaProperty().get(), Shapes.areaFrom(BY_HAND, Shapes.Simplify.NONE, 0)),
                "the area that landed is the Custom shape's, not the subject's that was on its way");
    }

    @Test
    @DisplayName("a file opened while an area is being worked out does not receive it")
    void openingAFileDropsTheAreaOnItsWay(@TempDir final Path dir) throws Exception {
        final ProjectModel other = sourced(new ProjectModel());
        other.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
        other.customProperty().set(BY_HAND);
        final Path file = dir.resolve("other.wtf");
        ProjectIo.save(other, file);

        final BlockingQueue<Runnable> fx = new LinkedBlockingQueue<>();
        final ProjectModel m = queued(fx);
        m.simplifyProperty().set(Shapes.Simplify.NONE);
        m.subjectProperty().set(BOHEMIA);
        assertTrue(m.areaPendingProperty().get());

        ProjectIo.load(m, file, CATALOG);

        assertFalse(m.areaPendingProperty().get(), "it belonged to the project that was replaced");
        final Geometry opened = m.areaProperty().get();
        assertNotNull(opened);
        drainLate(fx);
        assertSame(opened, m.areaProperty().get(), "and it does not land on the one opened");
    }

    @Test
    @DisplayName("a growth that ate the shape comes back with its step, problem and all, and is not worked out again")
    void aProblemComesBackWithItsStep() {
        final ProjectModel m = sourced(new ProjectModel());
        m.subjectProperty().set(BOHEMIA);
        m.history().closeStep();
        m.bufferValueProperty().set(-10_000_000);           // inward by more than the shape has
        m.history().closeStep();
        m.history().endRun();                               // the focus moves on, so the next edit is a step
        final String problem = m.build().problem();
        assertNotNull(problem);
        assertNull(m.areaProperty().get());
        m.bufferValueProperty().set(0);
        m.history().closeStep();
        assertTrue(m.build().ok(), m.build().problem());

        m.history().undo();

        assertNull(m.areaProperty().get());
        assertEquals(problem, m.build().problem());
    }
}
