package net.trdlo.wtf.studio.project;

import net.trdlo.wtf.compose.ComposeJob;
import net.trdlo.wtf.compose.Marks;
import net.trdlo.wtf.compose.Medium;
import net.trdlo.wtf.compose.PrintSize;
import net.trdlo.wtf.compose.Regions;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.compose.TileEffect;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The job the settings make, in the corners {@link ProjectIoTest} does not reach: a roll, a count
 * below one, the machine's printing settings, the output folder, no format at all, the zooms on
 * offer, the two regions and the cached job.
 */
class ProjectJobTest {

    private static final ProviderCatalog CATALOG = ProviderCatalog.load(null);
    private static final LonLatBounds BOHEMIA = new LonLatBounds(12.0, 48.5, 18.9, 51.1);

    /** A project a job can be built from – a source and a Custom area – with nothing unsaved. */
    private static ProjectModel buildable() {
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

    @Test
    @DisplayName("a roll is counted in strips across it, and along it there is nothing to count")
    void aRollIsCountedAcrossOnly() {
        final ProjectModel m = new ProjectModel();
        m.mediumKindProperty().set(ProjectModel.MediumKind.ROLL);
        m.rollWidthMmProperty().set(914);
        m.stripAxisProperty().set(Medium.Axis.VERTICAL);
        m.overlapMmProperty().set(10);

        assertEquals(new Medium.Roll(914, Medium.Axis.VERTICAL), m.medium());
        assertTrue(m.isCountable(true), "strips side by side across the print");
        assertFalse(m.isCountable(false), "and each one the whole way down");
        m.countAcrossProperty().set(true);
        assertEquals(3 * 914 - 2 * 10 - 2 * 5, m.millimetersOf(3), 1e-9, "three strips, two seams, and the edge at either end");
        m.countAcrossProperty().set(false);
        m.printWidthMmProperty().set(1234);
        assertEquals(3 * 914 - 2 * 10 - 2 * 5, m.millimetersOf(3), 1e-9, "no sheets along a roll: still its strips");

        m.stripAxisProperty().set(Medium.Axis.HORIZONTAL);
        assertFalse(m.isCountable(true));
        assertTrue(m.isCountable(false));
    }

    @Test
    @DisplayName("a roll is counted in strips whichever way they lie, and sheets the way that was chosen")
    void aRollIsCountedInStripsWhicheverWayTheyLie() {
        final ProjectModel m = new ProjectModel();
        m.sizeByProperty().set(ProjectModel.SizeBy.SHEETS);
        m.sheetCountProperty().set(3);
        m.countAcrossProperty().set(true);      // sheets counted across, then a roll chosen
        m.mediumKindProperty().set(ProjectModel.MediumKind.ROLL);
        m.rollWidthMmProperty().set(610);
        m.overlapMmProperty().set(10);
        m.printWidthMmProperty().set(1234);

        // The strips turned to lie one above another: nothing but the strip axis written
        m.stripAxisProperty().set(Medium.Axis.HORIZONTAL);
        assertFalse(m.countsAcross(), "the strips are counted down");
        assertEquals(3 * 610 - 2 * 10 - 2 * 5, m.millimetersOf(3), 1e-9, "three strips, two seams – not the print's width");
        assertEquals(PrintSize.height(3 * 610 - 2 * 10 - 2 * 5), m.printSize());
        assertTrue(m.countAcrossProperty().get(), "the axis chosen for sheets is not written over");

        m.stripAxisProperty().set(Medium.Axis.VERTICAL);
        assertTrue(m.countsAcross(), "turned back, the strips are counted across again");
        assertEquals(PrintSize.width(3 * 610 - 2 * 10 - 2 * 5), m.printSize());

        m.countAcrossProperty().set(false);     // a sheet project counted down ...
        m.mediumKindProperty().set(ProjectModel.MediumKind.SHEET);
        assertFalse(m.countsAcross());
        m.mediumKindProperty().set(ProjectModel.MediumKind.ROLL);
        assertTrue(m.countsAcross(), "... is a roll counted in its strips across ...");
        m.mediumKindProperty().set(ProjectModel.MediumKind.SHEET);
        assertFalse(m.countsAcross(), "... and sheets counted down once more");
    }

    @Test
    @DisplayName("a roll counted down its strips says so in the file, and opened and saved again is the same file")
    void aRollCountedDownIsSavedAsItIsCounted(@TempDir final Path dir) throws java.io.IOException {
        final ProjectModel m = buildable();
        m.sizeByProperty().set(ProjectModel.SizeBy.SHEETS);
        m.sheetCountProperty().set(4);
        m.countAcrossProperty().set(true);      // what a new project, or sheets counted across, leaves
        m.mediumKindProperty().set(ProjectModel.MediumKind.ROLL);
        m.rollWidthMmProperty().set(914);
        m.stripAxisProperty().set(Medium.Axis.HORIZONTAL);

        final Path file = dir.resolve("roll.wtf");
        ProjectIo.save(m, file);
        final net.trdlo.wtf.compose.WtfFile.MediumJson saved = ProjectIo.toFile(m).medium();
        assertNull(saved.sheetsAcross(), "not across: horizontal strips cannot be counted that way");
        assertEquals(4, saved.sheetsDown());

        final ProjectModel back = new ProjectModel();
        ProjectIo.load(back, file, CATALOG);
        assertFalse(back.countsAcross());
        assertEquals(m.printSize(), back.printSize(), "the same print");
        assertEquals(saved, ProjectIo.toFile(back).medium(), "and the same file");
        assertFalse(back.dirtyProperty().get());
    }

    @Test
    @DisplayName("a count below one is one sheet, along whichever axis is counted")
    void aCountBelowOneIsOneSheet() {
        final ProjectModel m = new ProjectModel();
        m.sizeByProperty().set(ProjectModel.SizeBy.SHEETS);
        m.sheetCountProperty().set(0);
        assertEquals(PrintSize.width(m.millimetersOf(1)), m.printSize());
        m.countAcrossProperty().set(false);
        m.sheetCountProperty().set(-3);
        assertEquals(PrintSize.height(m.millimetersOf(1)), m.printSize());
    }

    @Test
    @DisplayName("the printer's settings reach the job's marks, as a new revision that leaves the project saved")
    void thePrintingReachesTheMarks() {
        final ProjectModel m = buildable();
        final int before = m.revisionProperty().get();

        m.setPrinting(new ProjectModel.Printing(false));

        assertEquals(before + 1, m.revisionProperty().get());
        assertFalse(m.dirtyProperty().get(), "the machine's settings are not the project's");
        final Marks marks = m.build().job().marks();
        assertFalse(marks.label());
        assertFalse(marks.arrows());
        assertFalse(marks.registration(), "the hairline is not offered");

        m.creditOnPrintProperty().set(false);
        m.baseNameProperty().set("Wall");
        assertFalse(m.build().job().marks().credit(), "the credit is the project's own");
        assertEquals("Wall", m.build().job().marks().name(), "and so is the name the label gives the print");
        m.edgeMmProperty().set(7);
        assertEquals(7, m.build().job().marks().edgeMm(), "and so is the printer's edge, which moves the sheets");

        assertNull(m.build().job().marks().scale(), "a new project prints no scale");
        m.scaleMarkProperty().set(net.trdlo.wtf.compose.ScaleMark.RATIO);
        assertNull(m.build().job().marks().scale(), "nor does choosing how, until it is asked for");
        m.scaleOnPrintProperty().set(true);
        assertEquals(net.trdlo.wtf.compose.ScaleMark.RATIO, m.build().job().marks().scale());
    }

    @Test
    @DisplayName("the job goes to the project's folder, else the one last used, else Documents")
    void theOutputFolder() {
        final ProjectModel m = buildable();
        assertEquals(ProjectModel.defaultOutputDir(), m.effectiveOutputDir());
        final Path last = Path.of("last", "used");
        final Path own = Path.of("its", "own");
        m.setRememberedOutputDir(last);
        assertEquals(last, m.effectiveOutputDir());
        m.outputDirProperty().set(own);
        assertEquals(own, m.effectiveOutputDir());
        assertEquals(own, m.build().job().outputDir());
    }

    @Test
    @DisplayName("with no format chosen the job still makes the portable PDF")
    void noFormatMakesThePortablePdf() {
        final ProjectModel m = buildable();
        m.formats().clear();
        assertEquals(Set.of(ComposeJob.OutputFormat.PDF), m.build().job().formats());
    }

    @Test
    @DisplayName("the zooms offered are the map's own, and none without a map")
    void theZoomsOffered() {
        final ProjectModel m = new ProjectModel();
        assertEquals(List.of(), m.availableZooms());
        final Provider p = CATALOG.all().iterator().next();
        final Provider.Theme t = p.themes().getFirst();
        m.providerProperty().set(p);
        m.themeProperty().set(t);

        final List<Integer> zooms = m.availableZooms();
        assertEquals(p.minZoomOf(t), zooms.getFirst());
        assertEquals(p.maxZoomOf(t), zooms.getLast());
        assertEquals(p.maxZoomOf(t) - p.minZoomOf(t) + 1, zooms.size(), "every one between");
    }

    @Test
    @DisplayName("the job is built once until something changes, the printer's settings included")
    void theJobIsKeptUntilSomethingChanges() {
        final ProjectModel m = buildable();
        final ProjectModel.Built first = m.build();
        assertTrue(first.ok(), first.problem());
        assertSame(first, m.build(), "asked twice, built once");

        m.titleProperty().set("another");
        final ProjectModel.Built retitled = m.build();
        assertNotSame(first, retitled);
        assertSame(retitled, m.build());

        m.setPrinting(new ProjectModel.Printing(false));
        assertNotSame(retitled, m.build(), "new printing settings are a new job, though the project is the same");
    }

    @Test
    @DisplayName("the job's area and subject are copies, since the map draws the model's own while a worker reads the job's")
    void theJobsShapesAreCopies() {
        final ProjectModel m = buildable();
        m.subjectProperty().set(Shapes.rectangle(BOHEMIA));
        final ComposeJob first = m.build().job();

        assertNotSame(m.areaProperty().get(), first.fillShape(),
                "the count and export threads must not read the same instance the map is drawing");
        assertEquals(m.areaProperty().get(), first.fillShape(), "the same shape, all the same");
        assertNotSame(m.subjectProperty().get(), first.subject());
        // Topologically, not exactly: subjectAsOne() joins and validates the subject, which can
        // reorder a ring's points without changing the shape it describes.
        assertTrue(m.subjectProperty().get().equalsTopo(first.subject()));

        // A setting the area and the subject have nothing to do with is still a new revision, and
        // a new job – but not a new copy of a shape nothing touched: JTS is not cheap to duplicate,
        // and the export name is typed one character at a time.
        m.titleProperty().set("another");
        final ComposeJob second = m.build().job();
        assertNotSame(first, second, "a new job, since the revision moved");
        assertSame(first.fillShape(), second.fillShape(), "the untouched area is not copied again");
        assertSame(first.subject(), second.subject(), "the untouched subject is not copied again");
    }

    @Test
    @DisplayName("one region without a subject; with one, the outside's own and the stroke only when drawn")
    void theRegions() {
        final ProjectModel m = new ProjectModel();
        m.zoomProperty().set(14);
        m.insideEffectProperty().set(TileEffect.FADED);
        final Regions one = m.regions(false);
        assertEquals(14, one.zoom());
        assertEquals(new Regions.RegionSpec(TileEffect.FADED, true), one.inside());
        assertEquals(one.inside(), one.outside(), "without a subject there is one region");
        assertNull(one.boundary());

        m.outsideDiffersProperty().set(true);
        m.outsideEffectProperty().set(TileEffect.NONE);
        m.outsideDrawnProperty().set(false);
        m.boundaryStrokedProperty().set(true);
        m.boundaryWidthMmProperty().set(2.5);
        m.boundaryRgbProperty().set(0x123456);
        final Regions two = m.regions(true);
        assertEquals(14, two.zoom(), "one zoom for both");
        assertEquals(new Regions.RegionSpec(TileEffect.FADED, true), two.inside());
        assertEquals(new Regions.RegionSpec(TileEffect.NONE, false), two.outside());
        assertEquals(new Regions.BoundaryStroke(2.5, 0x123456), two.boundary());

        m.boundaryStrokedProperty().set(false);
        assertNull(m.regions(true).boundary(), "a boundary that is not drawn is not in the job");
    }

    @Test
    @DisplayName("whether a region is drawn is its own, whether the two are treated alike or not")
    void eachRegionIsDrawnOnItsOwn() {
        final ProjectModel m = new ProjectModel();
        m.insideDrawnProperty().set(false);
        assertTrue(m.outsideDrawnProperty().get(), "a stencil: the outside without the subject");
        assertFalse(m.regions(true).inside().drawn());
        assertTrue(m.regions(true).outside().drawn());
        m.outsideDrawnProperty().set(false);
        m.insideDrawnProperty().set(true);
        assertFalse(m.outsideDrawnProperty().get(), "the subject without the outside");
    }

    @Test
    @DisplayName("the outside alone is treated on its own: it differs, and stays so")
    void theOutsideAloneDiffers() {
        final ProjectModel m = new ProjectModel();
        m.insideEffectProperty().set(TileEffect.FADED);
        assertFalse(m.outsideDiffersProperty().get());

        m.insideDrawnProperty().set(false);
        assertTrue(m.outsideDiffersProperty().get(), "its own controls are the only ones left");
        assertEquals(TileEffect.FADED, m.outsideEffectProperty().get(), "and it keeps what it had");
        m.outsideDiffersProperty().set(false);
        assertTrue(m.outsideDiffersProperty().get(), "not to be turned off while the inside is not drawn");

        m.outsideDrawnProperty().set(false);
        m.outsideDiffersProperty().set(false);
        assertFalse(m.outsideDiffersProperty().get(), "neither drawn, nothing forces it");
    }
}
