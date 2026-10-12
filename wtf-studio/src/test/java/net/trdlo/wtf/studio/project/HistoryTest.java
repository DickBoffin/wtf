package net.trdlo.wtf.studio.project;

import net.trdlo.wtf.compose.ComposeJob;
import net.trdlo.wtf.compose.Medium;
import net.trdlo.wtf.compose.MissingTilePolicy;
import net.trdlo.wtf.compose.PrintSize;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.compose.TileEffect;
import net.trdlo.wtf.compose.WtfFile;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Geometry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Undo and redo, and whether the project is saved – without a window.
 * <p>
 * The window closes a step at every press of a mouse button or a key; these call
 * {@link History#closeStep()} where that press would come. Where the focus would move or a mouse
 * button be let go, they call {@link History#endRun()}.
 */
class HistoryTest {

    private static final ProviderCatalog CATALOG = ProviderCatalog.load(null);
    private static final LonLatBounds BOHEMIA = new LonLatBounds(12.0, 48.5, 18.9, 51.1);

    /** A project that can be saved – a source and a Custom area – and nothing to undo yet. */
    private static ProjectModel saveable() {
        final ProjectModel m = new ProjectModel();
        final Provider p = CATALOG.all().iterator().next();
        m.startOver(() -> {
            m.providerProperty().set(p);
            m.themeProperty().set(p.themes().getFirst());
            m.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
            m.customProperty().set(Shapes.rectangle(BOHEMIA));
        });
        return m;
    }

    // --------------------------------------------------------------------------------- the stack

    @Test
    @DisplayName("the fade across the growth is a step like any other")
    void theFadeIsUndone() {
        final ProjectModel m = saveable();
        final History h = m.history();
        m.areaFadeProperty().set(true);
        h.closeStep();
        assertTrue(m.dirtyProperty().get());
        h.undo();
        assertFalse(m.areaFadeProperty().get());
        assertFalse(m.dirtyProperty().get(), "and the project is as it was");
        h.redo();
        assertTrue(m.areaFadeProperty().get());
    }

    @Test
    @DisplayName("undo past a save marks the project clean again, and an edit there leaves no clean place")
    void undoPastASave(@TempDir final Path dir) throws IOException {
        final ProjectModel m = saveable();
        final History h = m.history();
        m.overlapMmProperty().set(20);
        h.closeStep();
        ProjectIo.save(m, dir.resolve("saved.wtf"));
        assertFalse(m.dirtyProperty().get(), "just saved");

        m.zoomProperty().set(11);
        h.closeStep();
        assertTrue(m.dirtyProperty().get(), "changed since");

        h.undo();
        assertFalse(m.dirtyProperty().get(), "undone back to where it was saved: clean again");
        assertEquals(13, m.zoomProperty().get());
        h.redo();
        assertTrue(m.dirtyProperty().get(), "and redone: changed again");
        assertEquals(11, m.zoomProperty().get());

        h.undo();
        h.undo();
        assertTrue(m.dirtyProperty().get(), "past the save");
        assertEquals(15, m.overlapMmProperty().get(), 1e-9, "before the change the save kept");

        // A change made here takes the way forward away, and the saved state with it
        m.orientationProperty().set(Medium.Orientation.LANDSCAPE);
        h.closeStep();
        assertFalse(h.canRedoProperty().get(), "nothing to redo any more");
        h.undo();
        assertTrue(m.dirtyProperty().get(), "and no position is the one on disk");
    }

    /**
     * What the area's editor does when a corner of the subject's box is let go: one write to the
     * fit, which the model reads as somebody taking the area over. One undo must put the subject
     * back in charge – restored under the derivation's own flag, so the restore is not read as a
     * takeover of its own.
     */
    @Test
    @DisplayName("dragging the box's corner is one step, and undoing it gives the area back to the subject")
    void aTakeoverIsOneStep() {
        final ProjectModel m = new ProjectModel();
        final History h = m.history();
        m.subjectProperty().set(Shapes.rectangle(BOHEMIA));
        h.closeStep();
        final Geometry derived = m.areaProperty().get();

        m.areaFitProperty().set(Shapes.rectangle(new LonLatBounds(10.0, 47.0, 20.0, 52.0)));
        h.closeStep();
        assertEquals(ProjectModel.AreaSource.CUSTOM, m.areaSourceProperty().get());
        assertNotNull(m.customProperty().get());

        h.undo();
        assertEquals(ProjectModel.AreaSource.FROM_SUBJECT, m.areaSourceProperty().get(),
                "putting the fit back is not somebody taking it over");
        assertNull(m.customProperty().get(), "and the Custom shape went with the step");
        assertEquals(Shapes.boundsOf(derived), Shapes.boundsOf(m.areaProperty().get()));
        h.undo();
        assertNull(m.subjectProperty().get(), "the subject was a step of its own");
        assertFalse(h.canUndoProperty().get());
    }

    @Test
    @DisplayName("a run of typing is one step, and typing back to where it started leaves nothing to undo")
    void aRunOfTypingIsOneStep() {
        final ProjectModel m = new ProjectModel();
        final History h = m.history();

        // A letter typed and taken away again: the project is as it was, and says so
        m.baseNameProperty().set("prints");
        h.closeStep();
        m.baseNameProperty().set("print");
        h.closeStep();
        assertFalse(h.canUndoProperty().get(), "nothing to undo");
        assertFalse(m.dirtyProperty().get(), "and nothing unsaved");

        // The name typed over, a key at a time, each closed by the next key's press
        for (final String typed : new String[]{"w", "wa", "wal", "wall"}) {
            m.baseNameProperty().set(typed);
            h.closeStep();
        }
        // The focus moves on, to a field whose typing is a step of its own
        h.endRun();
        m.overlapMmProperty().set(2);
        h.closeStep();
        m.overlapMmProperty().set(25);
        h.closeStep();

        h.undo();
        assertEquals(15, m.overlapMmProperty().get(), 1e-9, "the overlap, all of it");
        assertEquals("wall", m.baseNameProperty().get());
        h.undo();
        assertEquals("print", m.baseNameProperty().get(), "the name, all of it");
        assertFalse(h.canUndoProperty().get());
    }

    @Test
    @DisplayName("two clicks on a spinner are two steps, held down or typed they would be one")
    void twoClicksAreTwoSteps() {
        final ProjectModel m = new ProjectModel();
        final History h = m.history();
        // The zoom spinner: each click writes on its press and ends its run when it is let go
        m.zoomProperty().set(14);
        h.endRun();
        h.closeStep();
        m.zoomProperty().set(15);
        h.endRun();
        h.closeStep();

        h.undo();
        assertEquals(14, m.zoomProperty().get());
        h.undo();
        assertEquals(13, m.zoomProperty().get());
    }

    @Test
    @DisplayName("the side of the print its mode works out is an answer, not a change")
    void anAnswerIsNotAChange() {
        final ProjectModel m = new ProjectModel();
        final History h = m.history();
        assertEquals(PrintSize.Mode.FIT_WIDTH, m.printModeProperty().get());

        // What the medium step does when it first opens: writes in the height the width comes out as
        m.printHeightMmProperty().set(123);
        assertFalse(m.dirtyProperty().get(), "not unsaved, not even until the step closes");
        h.closeStep();
        assertFalse(m.dirtyProperty().get());
        assertFalse(h.canUndoProperty().get(), "and nothing to undo");

        // Where both sides are asked for, the height is somebody's decision
        m.printModeProperty().set(PrintSize.Mode.FIT_INSIDE);
        h.closeStep();
        m.printHeightMmProperty().set(200);
        h.closeStep();
        h.undo();
        assertEquals(123, m.printHeightMmProperty().get(), 1e-9);
        assertEquals(PrintSize.Mode.FIT_INSIDE, m.printModeProperty().get());
    }

    @Test
    @DisplayName("the DPI is a change only while the print is sized at one")
    void theDpiCountsOnlyAtADpi() {
        final ProjectModel m = saveable();
        final History h = m.history();
        m.dpiProperty().set(300);
        h.closeStep();
        assertFalse(m.dirtyProperty().get(), "sized in millimeters, the DPI changes nothing");
        assertFalse(h.canUndoProperty().get());

        m.sizeByProperty().set(ProjectModel.SizeBy.DPI);
        h.closeStep();
        m.dpiProperty().set(200);
        h.closeStep();
        assertTrue(m.dirtyProperty().get());
        h.undo();
        assertEquals(300, m.dpiProperty().get());
        assertEquals(ProjectModel.SizeBy.DPI, m.sizeByProperty().get());
        h.undo();
        assertEquals(ProjectModel.SizeBy.MILLIMETERS, m.sizeByProperty().get());
        assertFalse(m.dirtyProperty().get());
    }

    @Test
    @DisplayName("the scale is a change only while the print is sized at one, and its look only while printed")
    void theScaleCountsOnlyAtAScale() {
        final ProjectModel m = saveable();
        final History h = m.history();
        m.mapScaleProperty().set(25_000);
        h.closeStep();
        m.scaleMarkProperty().set(net.trdlo.wtf.compose.ScaleMark.RATIO);
        h.closeStep();
        assertFalse(m.dirtyProperty().get(), "sized in millimeters and not printed, neither changes anything");
        assertFalse(h.canUndoProperty().get());

        m.sizeByProperty().set(ProjectModel.SizeBy.SCALE);
        h.closeStep();
        m.mapScaleProperty().set(10_000);
        h.closeStep();
        m.scaleOnPrintProperty().set(true);
        h.closeStep();
        m.scaleMarkProperty().set(net.trdlo.wtf.compose.ScaleMark.BAR);
        h.closeStep();
        h.undo();
        assertEquals(net.trdlo.wtf.compose.ScaleMark.RATIO, m.scaleMarkProperty().get());
        h.undo();
        assertFalse(m.scaleOnPrintProperty().get());
        h.undo();
        assertEquals(25_000, m.mapScaleProperty().get());
        h.undo();
        assertEquals(ProjectModel.SizeBy.MILLIMETERS, m.sizeByProperty().get());
        assertFalse(m.dirtyProperty().get());
    }

    // --------------------------------------------------------------------------- the worked-out area

    /**
     * The outline itself is worked out off the FX thread, and arrives on its own some time after the
     * change that asked for it. A queue drained here stands in for the FX thread, so the model is
     * only ever touched from this one.
     */
    @Test
    @DisplayName("under the outline itself the area arriving later is part of its step, and an undo leaves nothing on its way")
    void theOutlineItself() throws InterruptedException {
        final BlockingQueue<Runnable> fx = new LinkedBlockingQueue<>();
        final ProjectModel m = new ProjectModel();
        m.deriveInBackground(fx::add);
        final History h = m.history();
        m.startOver(() -> m.simplifyProperty().set(Shapes.Simplify.NONE));

        m.subjectProperty().set(Shapes.rectangle(BOHEMIA));
        h.closeStep();
        assertTrue(m.areaPendingProperty().get(), "worked out in the background");
        // The subject's parts joined for the job come back first, then the area
        for (int i = 0; i < 2; i++) {
            final Runnable arrival = fx.poll(5, TimeUnit.SECONDS);
            assertNotNull(arrival, "the union and the area came back");
            arrival.run();
        }
        assertNotNull(m.areaProperty().get());
        h.closeStep();

        h.undo();
        assertNull(m.subjectProperty().get());
        assertFalse(h.canUndoProperty().get(), "one undo reached the start: the area was no step of its own");
        h.redo();
        assertFalse(m.areaPendingProperty().get(), "redone, the area is back at once rather than worked out again");
        final Geometry area = m.areaProperty().get();
        assertNotNull(area);

        m.bufferValueProperty().set(20_000);
        h.closeStep();
        assertTrue(m.areaPendingProperty().get());
        h.undo();                           // before the grown area has arrived
        assertFalse(m.areaPendingProperty().get(), "nothing left on its way");
        assertEquals(0, m.bufferValueProperty().get());
        Thread.sleep(2 * ProjectModel.DERIVE_DELAY_MILLIS);
        for (Runnable late = fx.poll(); late != null; late = fx.poll()) {
            late.run();
        }
        assertSame(area, m.areaProperty().get(), "and nothing that lands afterwards changes the area put back");
        assertTrue(h.canRedoProperty().get());
    }

    // ------------------------------------------------------------------------------- all of it

    /**
     * Every setting, through the file rather than through a snapshot: a property the snapshot
     * leaves out is one the comparison of two snapshots would leave out as well.
     */
    @Test
    @DisplayName("every setting comes back with one undo")
    void everySettingComesBack() {
        final List<Provider> providers = List.copyOf(CATALOG.all());
        final Provider first = providers.get(0);
        final Provider second = providers.get(1);
        final ProjectModel m = new ProjectModel();
        m.startOver(() -> {
            m.providerProperty().set(first);
            m.themeProperty().set(first.themes().getFirst());
            m.mapLangProperty().set("de");
            m.titleProperty().set("Bohemia");
            m.notesProperty().set("For the hall");
            m.subjectProperty().set(Shapes.rectangle(BOHEMIA));
            m.subjectOriginProperty().set("drawn");
            m.simplifyProperty().set(Shapes.Simplify.CONVEX_HULL);
            m.bufferValueProperty().set(5_000);
            m.mediumKindProperty().set(ProjectModel.MediumKind.ROLL);
            m.rollWidthMmProperty().set(610);
            m.stripAxisProperty().set(Medium.Axis.HORIZONTAL);
            m.overlapMmProperty().set(25);
            m.printModeProperty().set(PrintSize.Mode.FIT_INSIDE);
            m.printWidthMmProperty().set(3000);
            m.printHeightMmProperty().set(2000);
            m.outsideDiffersProperty().set(true);
            m.zoomProperty().set(14);
            m.scaleProperty().set(2);
            m.outsideEffectProperty().set(TileEffect.FADED);
            m.outsideDrawnProperty().set(false);
            m.boundaryStrokedProperty().set(true);
            m.boundaryWidthMmProperty().set(1.5);
            m.boundaryRgbProperty().set(0x336699);
            m.backgroundRgbProperty().set(0xEEEEEE);
            m.formats().add(ComposeJob.OutputFormat.PNG);
            m.groupingProperty().set(ComposeJob.OutputGrouping.SINGLE_DOCUMENT);
            m.outputDirProperty().set(Path.of("hall"));
            m.baseNameProperty().set("hall");
            m.missingTilesProperty().set(MissingTilePolicy.TRANSPARENT);
            m.creditOnPrintProperty().set(false);
        });
        final WtfFile before = withoutTime(ProjectIo.toFile(m));

        // All of it changed, in one step
        m.providerProperty().set(second);
        m.themeProperty().set(second.themes().getFirst());
        m.mapLangProperty().set("fr");
        m.titleProperty().set("Moravia");
        m.notesProperty().set("For the stairs");
        m.subjectProperty().set(Shapes.rectangle(new LonLatBounds(15.5, 48.6, 18.8, 50.3)));
        m.subjectOriginProperty().set("moravia.geojson");
        m.simplifyProperty().set(Shapes.Simplify.BOUNDING_BOX);
        m.bufferValueProperty().set(0);
        m.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
        m.customProperty().set(Shapes.rectangle(new LonLatBounds(15.0, 48.0, 19.5, 51.0)));
        m.customOriginProperty().set("drawn");
        m.mediumKindProperty().set(ProjectModel.MediumKind.SHEET);
        m.paperProperty().set(Medium.paper("A3"));
        m.orientationProperty().set(Medium.Orientation.LANDSCAPE);
        m.overlapMmProperty().set(10);
        m.sizeByProperty().set(ProjectModel.SizeBy.SHEETS);
        m.sheetCountProperty().set(3);
        m.countAcrossProperty().set(false);
        m.printModeProperty().set(PrintSize.Mode.FIT_WIDTH);
        m.printWidthMmProperty().set(1000);
        m.outsideDiffersProperty().set(false);
        m.zoomProperty().set(9);
        m.scaleProperty().set(1);
        m.insideEffectProperty().set(TileEffect.FADED);
        m.insideDrawnProperty().set(false);
        m.boundaryStrokedProperty().set(false);
        m.boundaryWidthMmProperty().set(3);
        m.boundaryRgbProperty().set(0x112233);
        m.backgroundRgbProperty().set(0x000000);
        m.formats().remove(ComposeJob.OutputFormat.PNG);
        m.formats().add(ComposeJob.OutputFormat.TIFF);
        m.groupingProperty().set(ComposeJob.OutputGrouping.PER_SHEET);
        m.outputDirProperty().set(Path.of("stairs"));
        m.baseNameProperty().set("stairs");
        m.missingTilesProperty().set(MissingTilePolicy.PLACEHOLDER);
        m.creditOnPrintProperty().set(true);
        m.history().closeStep();
        assertNotEquals(before, withoutTime(ProjectIo.toFile(m)), "all of it did change");

        m.history().undo();

        assertEquals(before, withoutTime(ProjectIo.toFile(m)));
        assertTrue(m.outsideDiffersProperty().get(), "and what the file does not hold");
        assertEquals(TileEffect.NONE, m.insideEffectProperty().get());
        assertFalse(m.dirtyProperty().get());
    }

    /** The same file, but for the moment it was written. */
    private static WtfFile withoutTime(final WtfFile f) {
        return new WtfFile(f.format(), f.title(), f.notes(), null, f.source(), f.area(), f.subject(),
                f.refine(), f.regions(), f.print(), f.medium(), f.export());
    }

    @Test
    @DisplayName("once the start has fallen off the far end of the stack, undo never gets back to clean")
    void theStartFallsOffTheEnd() {
        final ProjectModel m = new ProjectModel();
        final History h = m.history();
        for (int i = 0; i <= History.LIMIT; i++) {
            m.areaExactProperty().set(!m.areaExactProperty().get());
            h.closeStep();
        }
        int undone = 0;
        while (h.canUndoProperty().get()) {
            h.undo();
            undone++;
        }
        assertEquals(History.LIMIT, undone, "as far back as the limit, and no further");
        assertTrue(m.dirtyProperty().get(), "and not as far as the state it started in");
    }
}
