package net.trdlo.wtf.studio;

import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.compose.WtfFile;
import net.trdlo.wtf.studio.project.ProjectIo;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * When a window's recovery copy of unsaved work is written, and when it goes – with the
 * wait and the clock in the test's hands, and the copy written as it is asked for.
 */
class RecoveryWatchTest {

    private static final ProviderCatalog CATALOG = ProviderCatalog.load(null);

    @TempDir
    Path dir;

    @TempDir
    Path projects;

    /** The wait, by hand: the test says when the interval has passed. */
    private static final class Hand implements RecoveryWatch.Wait {
        final List<Long> asked = new ArrayList<>();
        private Runnable waiting;

        @Override
        public @NotNull Runnable after(final long millis, final @NotNull Runnable onFx) {
            asked.add(millis);
            waiting = onFx;
            return () -> {
                if (waiting == onFx) {
                    waiting = null;
                }
            };
        }

        boolean isWaiting() {
            return waiting != null;
        }

        /** The interval passes, and what was waiting runs, on the "FX thread" that is this test. */
        void passes() {
            final Runnable due = waiting;
            waiting = null;
            assertNotNull(due, "nothing was waiting");
            due.run();
        }
    }

    private final Hand hand = new Hand();
    private long now = 1_000_000;
    private Recovery copies;
    private final List<Recovery> others = new ArrayList<>();
    private final List<Recovery.Orphan> offered = new ArrayList<>();

    @AfterEach
    void letGo() {
        offered.forEach(Recovery.Orphan::keep);
        others.forEach(Recovery::close);
        if (copies != null) {
            copies.close();
        }
    }

    private @NotNull RecoveryWatch watching(final @NotNull ProjectModel m) {
        copies = new Recovery(dir);
        final RecoveryWatch watch = new RecoveryWatch(m, copies, hand, Runnable::run, () -> now);
        watch.start();
        return watch;
    }

    /** A project just opened, or started: a provider, a map and an area, and nothing unsaved. */
    private static @NotNull ProjectModel opened() {
        final ProjectModel m = new ProjectModel();
        final Provider p = CATALOG.all().iterator().next();
        m.startOver(() -> {
            m.providerProperty().set(p);
            m.themeProperty().set(p.themes().getFirst());
            m.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
            m.customProperty().set(Shapes.rectangle(new LonLatBounds(12.0, 48.5, 18.9, 51.1)));
        });
        return m;
    }

    private @NotNull List<Path> files() throws IOException {
        try (Stream<Path> all = Files.list(dir)) {
            return all.sorted().toList();
        }
    }

    private @NotNull List<Path> copiesOnDisk() throws IOException {
        return files().stream().filter(p -> p.getFileName().toString().endsWith(ProjectIo.EXTENSION)).toList();
    }

    private @NotNull String titleOfTheCopy() throws IOException {
        final List<Path> on = copiesOnDisk();
        assertEquals(1, on.size(), "one copy");
        return String.valueOf(WtfFile.read(on.getFirst()).title());
    }

    @Test
    @DisplayName("a change leaves a recovery copy once the interval passes, and not before")
    void aChangeLeavesACopyOnceTheIntervalPasses() throws IOException {
        final ProjectModel m = opened();
        watching(m);
        assertFalse(hand.isWaiting(), "nothing unsaved, nothing to copy");

        m.titleProperty().set("Bohemia, changed");
        assertEquals(List.of(), files(), "not before the interval");
        assertEquals(RecoveryWatch.QUIET_MILLIS, hand.asked.getLast());

        hand.passes();
        assertEquals("Bohemia, changed", titleOfTheCopy(), "the project as it stands");

        m.titleProperty().set("Bohemia, changed again");
        hand.passes();
        assertEquals("Bohemia, changed again", titleOfTheCopy(), "the same copy, written over");
    }

    @Test
    @DisplayName("a save removes it, and so do an undo back to the save and a new project")
    void aSaveRemovesIt() throws IOException {
        final ProjectModel m = opened();
        watching(m);
        m.titleProperty().set("one");
        hand.passes();
        assertEquals(2, files().size(), "a copy and its lock");

        ProjectIo.save(m, projects.resolve("wall.wtf"));
        assertEquals(List.of(), files(), "saved: the copy and its lock are gone");
        assertFalse(hand.isWaiting());

        m.titleProperty().set("two");
        hand.passes();
        assertEquals("two", titleOfTheCopy());
        m.history().undo();
        assertFalse(m.dirtyProperty().get());
        assertEquals(List.of(), files(), "undone back to the save, nothing is unsaved");

        m.titleProperty().set("three");
        hand.passes();
        assertEquals("three", titleOfTheCopy());
        m.startNew();
        assertEquals(List.of(), files(), "a new project replaced it");
    }

    @Test
    @DisplayName("a close that went ahead removes it, and nothing is written after it")
    void aDiscardRemovesIt() throws IOException {
        final ProjectModel m = opened();
        final RecoveryWatch watch = watching(m);
        m.titleProperty().set("one");
        hand.passes();
        m.titleProperty().set("two");
        assertTrue(hand.isWaiting(), "the next copy is waiting");

        watch.discard();
        assertEquals(List.of(), files(), "Don't save: the copy goes with what it held");
        assertFalse(hand.isWaiting(), "and the next one is called off");
        m.titleProperty().set("three");
        assertFalse(hand.isWaiting(), "the window is closing");
        watch.stop();
        assertEquals(List.of(), files());
    }

    @Test
    @DisplayName("a window that goes unasked leaves its copy for the next start, with every change in it")
    void aStopKeepsIt() throws IOException {
        final ProjectModel m = opened();
        final RecoveryWatch watch = watching(m);
        m.titleProperty().set("copied");
        hand.passes();
        m.titleProperty().set("changed after the copy");

        watch.stop();
        final Recovery next = new Recovery(dir);
        others.add(next);
        final List<Recovery.Orphan> found = next.orphans();
        offered.addAll(found);
        assertEquals(1, found.size(), "the next start finds it");
        assertEquals("changed after the copy", String.valueOf(WtfFile.read(found.getFirst().copy()).title()),
                "what changed since the last copy went into it before the window did");
    }

    @Test
    @DisplayName("a project that never stops changing is copied thirty seconds after the first change the copy lacks")
    void theLongestWait() throws IOException {
        final ProjectModel m = opened();
        watching(m);
        final long first = now;
        m.titleProperty().set("a");
        assertEquals(RecoveryWatch.QUIET_MILLIS, hand.asked.getLast());
        for (int i = 1; i <= 18; i++) {
            now = first + i * 1_500L;
            m.titleProperty().set("a" + i);
        }
        assertEquals(List.of(), files(), "a change every second and a half, and no copy yet");
        assertEquals(RecoveryWatch.QUIET_MILLIS, hand.asked.getLast(), "27 s in, two seconds is still sooner");
        now = first + 29_000;
        m.titleProperty().set("b");
        assertEquals(1_000, hand.asked.getLast(), "thirty seconds after the first change, not two after the last");
        now = first + 31_000;
        m.titleProperty().set("c");
        assertEquals(0, hand.asked.getLast(), "overdue: now");

        hand.passes();
        assertEquals("c", titleOfTheCopy());
        now += 500;
        m.titleProperty().set("d");
        assertEquals(RecoveryWatch.QUIET_MILLIS, hand.asked.getLast(),
                "the copy has the rest, so it is two seconds again");
    }

    /** Two overlapping parts: the outline itself of them is a join, worked out on the worker. */
    private static final Geometry TWO_PARTS = Shapes.GF.createMultiPolygon(new Polygon[]{
            (Polygon) Shapes.rectangle(new LonLatBounds(12.1, 48.6, 18.8, 51.0)),
            (Polygon) Shapes.rectangle(new LonLatBounds(16.8, 47.7, 22.6, 49.6))});

    @Test
    @DisplayName("nothing is copied while an area is on its way, and the copy made once it lands opens")
    void notWhileAnAreaIsOnItsWay() throws Exception {
        final BlockingQueue<Runnable> fx = new LinkedBlockingQueue<>();
        final ProjectModel m = new ProjectModel();
        m.deriveInBackground(fx::add);
        final Provider p = CATALOG.all().iterator().next();
        m.startOver(() -> {
            m.providerProperty().set(p);
            m.themeProperty().set(p.themes().getFirst());
            m.simplifyProperty().set(Shapes.Simplify.NONE);
            m.bufferValueProperty().set(10_000);
        });
        watching(m);

        m.subjectProperty().set(TWO_PARTS);
        assertTrue(m.areaPendingProperty().get(), "the outline itself, grown, is worked out on the worker");
        hand.passes();
        assertEquals(List.of(), files(), "not with the area from before, nor by joining the subject here");

        while (m.areaPendingProperty().get()) {
            final Runnable landed = fx.poll(10, TimeUnit.SECONDS);
            assertNotNull(landed, "the worker answered");
            landed.run();
        }
        assertTrue(hand.isWaiting(), "the area's landing asks again");
        hand.passes();
        final List<Path> on = copiesOnDisk();
        assertEquals(1, on.size());

        final ProjectModel reopened = new ProjectModel();
        // A copy that did not reproduce its own area would be refused here
        ProjectIo.load(reopened, on.getFirst(), CATALOG);
        assertTrue(Shapes.reproduces(m.areaProperty().get(), reopened.areaProperty().get()), "the area that landed");
    }
}
