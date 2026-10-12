package net.trdlo.wtf.studio.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import net.trdlo.wtf.compose.*;
import net.trdlo.wtf.studio.ErrorText;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.studio.Units;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import net.trdlo.wtf.tiles.Refusal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Saving and loading a project, without a display.
 * <p>
 * {@link ProjectModel} is built out of {@code javafx.base} properties only – no {@code Scene}, no
 * toolkit – precisely so this can be a plain unit test. A project file is a thing people mail to
 * each other; "it looked right on screen" is not evidence that it round-trips.
 */
class ProjectIoTest {

    private static final ProviderCatalog CATALOG = ProviderCatalog.load(null);

    private static ProjectModel filled() {
        final ProjectModel m = new ProjectModel();
        final Provider p = CATALOG.all().iterator().next();
        m.providerProperty().set(p);
        m.themeProperty().set(p.themes().getFirst());
        m.titleProperty().set("Bohemia on the wall");
        // A Custom area, which is the second of the two ways to have one; the subject-first route
        // has a test of its own below. Grown, as the subject's fit would be.
        m.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
        m.customProperty().set(Shapes.rectangle(new LonLatBounds(12.0, 48.5, 18.9, 51.1)));
        m.simplifyProperty().set(Shapes.Simplify.BOUNDING_BOX);
        m.bufferValueProperty().set(2500);
        m.printWidthMmProperty().set(5000);
        m.overlapMmProperty().set(20);
        m.zoomProperty().set(13);
        m.baseNameProperty().set("wall");
        return m;
    }

    @Test
    void roundTripsEverythingThatWasSet(@TempDir final Path dir) throws IOException {
        final ProjectModel before = filled();
        final Path file = dir.resolve("wall.wtf");
        ProjectIo.save(before, file);

        final ProjectModel after = new ProjectModel();
        assertTrue(ProjectIo.load(after, file, CATALOG).isEmpty(), "no warnings expected");

        assertEquals("Bohemia on the wall", after.titleProperty().get());
        assertEquals(before.providerProperty().get().id(), after.providerProperty().get().id());
        assertEquals(before.themeProperty().get().id(), after.themeProperty().get().id());
        // The growth, which a Custom area has; not the fit, which it has none of, so the file
        // records none. It round-trips in theAreaSourceSurvivesARoundTrip, where it means something.
        assertEquals(ProjectModel.AreaSource.CUSTOM, after.areaSourceProperty().get());
        assertEquals(2500, after.bufferValueProperty().get(), 1e-9);
        assertEquals(Shapes.boundsOf(before.customProperty().get()), Shapes.boundsOf(after.customProperty().get()));
        assertEquals(5000, after.printWidthMmProperty().get());
        assertEquals(20, after.overlapMmProperty().get());
        assertEquals(13, after.zoomProperty().get());
        assertEquals("wall", after.baseNameProperty().get());

        // The shape itself, not just its settings
        assertEquals(Shapes.boundsOf(before.areaProperty().get()),
                Shapes.boundsOf(after.areaProperty().get()));
    }

    @Test
    void saveClearsDirtyAndRemembersWhere(@TempDir final Path dir) throws IOException {
        final ProjectModel m = filled();
        assertTrue(m.dirtyProperty().get(), "setting things marks it unsaved");
        final Path file = dir.resolve("a.wtf");
        ProjectIo.save(m, file);
        assertFalse(m.dirtyProperty().get());
        assertEquals(file, m.fileProperty().get());
    }

    @Test
    void loadingDoesNotMarkTheProjectUnsaved(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("b.wtf");
        ProjectIo.save(filled(), file);
        final ProjectModel m = new ProjectModel();
        m.titleProperty().set("something before it");
        ProjectIo.load(m, file, CATALOG);
        assertFalse(m.dirtyProperty().get(),
                "opening a file is not a change to it; otherwise every open offers to save");
        assertFalse(m.history().canUndoProperty().get(),
                "nor is there anything to undo: the project before it is not a step back");
    }

    /**
     * A country outline is islands and holes, and four levels of nesting is what that actually
     * needs – the level it is easy to be off by one on.
     */
    @Test
    void aMultiPartSubjectKeepsEveryPartAndEveryHole(@TempDir final Path dir) throws IOException {
        final Geometry mainland = Shapes.rectangle(new LonLatBounds(12.0, 48.5, 18.9, 51.1));
        final Geometry island = Shapes.rectangle(new LonLatBounds(20.0, 48.5, 20.4, 48.9));
        final Geometry both = Shapes.GF.createGeometryCollection(
                new Geometry[]{mainland, island}).union();

        final ProjectModel m = filled();
        m.subjectProperty().set(both);

        final Path file = dir.resolve("islands.wtf");
        ProjectIo.save(m, file);

        final WtfFile raw = WtfFile.read(file);
        assertNotNull(raw.subject(), "a subject with two parts needs the multi-ring field");
        assertEquals(2, raw.subject().polygons().size());

        final ProjectModel after = new ProjectModel();
        ProjectIo.load(after, file, CATALOG);
        assertEquals(2, after.subjectProperty().get().getNumGeometries());
    }

    /**
     * An area worked out under "the outline itself" is a real outline, and the file has to hold
     * all of it: the area may be several polygons, as long as the subject is what decided so.
     */
    @Test
    void aDerivedOutlineAreaKeepsEveryPart(@TempDir final Path dir) throws IOException {
        final Geometry west = Shapes.rectangle(new LonLatBounds(12.0, 48.5, 14.0, 51.1));
        final Geometry east = Shapes.rectangle(new LonLatBounds(20.0, 48.5, 20.4, 48.9));
        final ProjectModel m = filled();
        m.subjectProperty().set(Shapes.GF.createGeometryCollection(
                new Geometry[]{west, east}).union());
        m.simplifyProperty().set(Shapes.Simplify.NONE);
        m.bufferValueProperty().set(0);
        m.areaSourceProperty().set(ProjectModel.AreaSource.FROM_SUBJECT);

        assertEquals(2, m.areaProperty().get().getNumGeometries(), "two islands, two parts");

        final Path file = dir.resolve("islands.wtf");
        ProjectIo.save(m, file);
        assertEquals(2, WtfFile.read(file).area().polygons().size());

        final ProjectModel after = new ProjectModel();
        assertTrue(ProjectIo.load(after, file, CATALOG).isEmpty());
        assertEquals(2, after.areaProperty().get().getNumGeometries());
        assertEquals(ProjectModel.AreaSource.FROM_SUBJECT, after.areaSourceProperty().get());
    }

    /** A Custom box grows into a bigger box, as the subject's does, and the file keeps the one it grew from. */
    @Test
    void aCustomBoxGrowsIntoABox(@TempDir final Path dir) throws IOException {
        final ProjectModel m = filled();
        final Path file = dir.resolve("simple.wtf");
        ProjectIo.save(m, file);
        final WtfFile f = WtfFile.read(file);
        assertEquals(1, f.area().polygons().size());
        assertEquals("rectangle", f.area().kind(), "square corners, not a buffer's arcs");
        assertEquals("custom", f.area().source());
        assertEquals(1, f.refine().custom().polygons().size(), "and the box it grew from");
        assertNull(f.refine().simplify());
        assertEquals(2500, f.refine().bufferValue(), 1e-9);
    }

    @Test
    @DisplayName("a rectangle printed exactly stays so through a save, and the job knows it")
    void anExactAreaSurvivesARoundTrip(@TempDir final Path dir) throws IOException {
        final ProjectModel m = filled();
        final Path plain = dir.resolve("plain.wtf");
        ProjectIo.save(m, plain);
        assertFalse(Files.readString(plain).contains("\"exactArea\""), "left out while it is off");
        assertFalse(m.build().job().exactArea());

        m.areaExactProperty().set(true);
        final Path file = dir.resolve("exact.wtf");
        ProjectIo.save(m, file);
        assertEquals(Boolean.TRUE, WtfFile.read(file).print().exactArea());
        assertTrue(m.build().job().exactArea());

        final ProjectModel back = new ProjectModel();
        ProjectIo.load(back, file, CATALOG);
        assertTrue(back.areaExactProperty().get());
        assertTrue(WtfFile.read(file).toJob(CATALOG, dir).exactArea(), "the command line reads it too");
    }

    @Test
    @DisplayName("a fade across the growth stays through a save, and the job starts it at the fit")
    void aFadeSurvivesARoundTrip(@TempDir final Path dir) throws IOException {
        final Geometry subject = Shapes.rectangle(new LonLatBounds(12.5, 48.8, 18.5, 50.9));
        final ProjectModel m = filled();
        m.subjectProperty().set(subject);
        m.areaSourceProperty().set(ProjectModel.AreaSource.FROM_SUBJECT);
        final Path plain = dir.resolve("plain.wtf");
        ProjectIo.save(m, plain);
        assertFalse(Files.readString(plain).contains("\"fade\""), "left out while it is off");
        assertNull(m.build().job().fadeFrom());

        m.areaFadeProperty().set(true);
        final Geometry from = m.build().job().fadeFrom();
        assertNotNull(from);
        assertEquals(0.0, Shapes.deviation(subject, from), 1e-9, "the box of a box, before its growth");
        final Path file = dir.resolve("fade.wtf");
        ProjectIo.save(m, file);
        assertEquals(Boolean.TRUE, WtfFile.read(file).refine().fade());

        final ProjectModel back = new ProjectModel();
        assertTrue(ProjectIo.load(back, file, CATALOG).isEmpty());
        assertTrue(back.areaFadeProperty().get());
        assertNotNull(WtfFile.read(file).toJob(CATALOG, dir).fadeFrom(), "the command line reads it too");

        m.bufferValueProperty().set(0);
        assertNull(m.build().job().fadeFrom(), "nothing grown, nothing to fade across");
    }

    /**
     * Moving the box's corner is what takes the area over – there is no button, and no second shape
     * kept for the answer that is not showing.
     */
    @Test
    void theAreaDetachesWhenItsFitIsTouched() {
        final ProjectModel m = new ProjectModel();
        m.subjectProperty().set(Shapes.rectangle(new LonLatBounds(12.0, 48.5, 18.9, 51.1)));
        assertEquals(ProjectModel.AreaSource.FROM_SUBJECT, m.areaSourceProperty().get());
        assertNotNull(m.areaProperty().get(), "the subject worked one out");

        final Geometry dragged = Shapes.rectangle(new LonLatBounds(10.0, 47.0, 20.0, 52.0));
        m.areaFitProperty().set(dragged);
        assertEquals(ProjectModel.AreaSource.CUSTOM, m.areaSourceProperty().get());
        assertSame(dragged, m.customProperty().get(), "the box as it was let go is the Custom shape");

        // and the subject no longer decides it
        m.subjectProperty().set(Shapes.rectangle(new LonLatBounds(0.0, 0.0, 1.0, 1.0)));
        assertEquals(Shapes.boundsOf(dragged), Shapes.boundsOf(m.areaProperty().get()));
    }

    /** Going back to the subject discards the Custom shape rather than parking it out of sight. */
    @Test
    void switchingBackToTheSubjectKeepsNoSecondShape() {
        final ProjectModel m = new ProjectModel();
        final LonLatBounds bohemia = new LonLatBounds(12.0, 48.5, 18.9, 51.1);
        m.subjectProperty().set(Shapes.rectangle(bohemia));
        m.areaFitProperty().set(Shapes.rectangle(new LonLatBounds(10.0, 47.0, 20.0, 52.0)));
        assertEquals(ProjectModel.AreaSource.CUSTOM, m.areaSourceProperty().get());

        m.areaSourceProperty().set(ProjectModel.AreaSource.FROM_SUBJECT);
        assertNull(m.customProperty().get(), "dropped, not kept out of sight");
        assertEquals(bohemia, Shapes.boundsOf(m.areaProperty().get()));

        // and taking it over again starts from what is on screen, not from the discarded one
        m.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
        assertEquals(bohemia, Shapes.boundsOf(m.customProperty().get()));
        assertEquals(bohemia, Shapes.boundsOf(m.areaProperty().get()));
    }

    @Test
    void anUnknownProviderIsAWarningRatherThanAFailure(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("foreign.wtf");
        ProjectIo.save(filled(), file);
        Files.writeString(file, Files.readString(file)
                .replaceFirst("\"provider\"\\s*:\\s*\"[^\"]+\"", "\"provider\" : \"no-such-provider\""));

        final ProjectModel m = new ProjectModel();
        final List<String> warnings = ProjectIo.load(m, file, CATALOG);
        assertEquals(1, warnings.size(), warnings::toString);
        assertNotNull(m.providerProperty().get(), "it still opens, on a provider that exists");
    }

    /** The model must be able to turn itself into something the engine will actually run. */
    @Test
    void theModelBuildsARunnableJob() {
        final ProjectModel.Built built = filled().build();
        assertTrue(built.ok(), () -> "not buildable: " + built.problem());
        final ComposeJob job = built.job();
        assertEquals(5000, job.print().widthMm(), 0.001);
        final SheetLayout layout = job.layout();
        assertTrue(layout.count() >= 1);
        assertEquals(layout.print().widthMm() / layout.print().unitWidth(),
                layout.print().heightMm() / layout.print().unitHeight(), 0.001,
                "a print with a non-uniform scale is a stretched map");
    }

    @Test
    void anEmptyModelSaysWhatIsMissingRatherThanThrowing() {
        final ProjectModel m = new ProjectModel();
        assertFalse(m.build().ok());
        assertEquals(net.trdlo.wtf.studio.I18n.get("problem.noSource"), m.build().problem());

        final Provider p = CATALOG.all().iterator().next();
        m.providerProperty().set(p);
        m.themeProperty().set(p.themes().getFirst());
        // A new project works out its area from the subject, so the subject is what is missing
        assertEquals(net.trdlo.wtf.studio.I18n.get("problem.noSubject"), m.build().problem());

        m.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
        assertEquals(net.trdlo.wtf.studio.I18n.get("problem.noArea"), m.build().problem());
    }

    @Test
    @DisplayName("the area is worked out from the subject, and the fit and the growth act on it")
    void theAreaFollowsFromTheSubject() {
        final ProjectModel m = new ProjectModel();
        final Provider p = CATALOG.all().iterator().next();
        m.providerProperty().set(p);
        m.themeProperty().set(p.themes().getFirst());
        m.printWidthMmProperty().set(1000);

        final LonLatBounds bohemia = new LonLatBounds(12.0, 48.5, 18.9, 51.1);
        m.subjectProperty().set(Shapes.rectangle(bohemia));
        m.simplifyProperty().set(Shapes.Simplify.BOUNDING_BOX);
        m.bufferValueProperty().set(0);

        assertTrue(m.build().ok(), () -> "not buildable: " + m.build().problem());
        assertEquals(bohemia.west(), Shapes.boundsOf(m.fill()).west(), 1e-6,
                "with no growth the area is the subject's own box");

        m.bufferValueProperty().set(10_000);
        assertTrue(Shapes.boundsOf(m.fill()).west() < bohemia.west(),
                "ten kilometers of room reaches further west than the subject does");

        // And the subject itself is untouched: growing a border would be moving a border
        assertEquals(bohemia.west(), Shapes.boundsOf(m.subjectProperty().get()).west(), 1e-9);
    }

    @Test
    @DisplayName("the outside follows the inside, and keeps its own treatment while it does")
    void theOutsideFollowsTheInside() {
        final ProjectModel m = new ProjectModel();
        assertFalse(m.outsideDiffersProperty().get(), "one map, until somebody asks for two");

        m.insideEffectProperty().set(TileEffect.FADED);
        assertEquals(TileEffect.FADED, m.outsideTreatment(), "the outside follows");
        assertEquals(TileEffect.FADED, m.regions(true).outside().effect(), "on the print as well");

        m.outsideDiffersProperty().set(true);
        final TileEffect own = new TileEffect(0.5, 0, 0);
        m.outsideEffectProperty().set(own);
        m.insideEffectProperty().set(TileEffect.NONE);
        assertEquals(own, m.outsideTreatment(), "asked to differ, it differs");

        m.outsideDiffersProperty().set(false);
        assertEquals(TileEffect.NONE, m.outsideTreatment(), "told to follow again, it follows");
        assertEquals(own, m.outsideEffectProperty().get(), "and keeps what it had");
        m.outsideDiffersProperty().set(true);
        assertEquals(own, m.outsideTreatment(), "for when it is asked to differ again");
    }

    @Test
    @DisplayName("a print asked for in sheets comes out that many sheets wide")
    void sizingBySheets() {
        final ProjectModel m = filled();
        m.mediumKindProperty().set(ProjectModel.MediumKind.SHEET);
        m.paperProperty().set(net.trdlo.wtf.compose.Medium.paper("A4"));
        m.orientationProperty().set(net.trdlo.wtf.compose.Medium.Orientation.PORTRAIT);
        m.overlapMmProperty().set(10);
        m.sizeByProperty().set(ProjectModel.SizeBy.SHEETS);
        m.countAcrossProperty().set(true);
        m.sheetCountProperty().set(3);

        // Three 210 mm sheets sharing two 10 mm seams, and the printer's 5 mm edge kept free at
        // either end: 630 − 20 − 10 = 600
        assertEquals(600, m.millimetersOf(3), 1e-9);
        final SheetLayout layout = m.build().job().layout();
        assertEquals(3, layout.columns(), "asked for three across, got three across");
        assertTrue(layout.rows() >= 1, "and the other side follows from the area");
    }

    /**
     * The printer's white edge decides the layout, so it is the project's: a file has to mean one
     * print wherever it is opened, and its count is checked against its own edge, not the one the
     * window last had.
     */
    @Test
    @DisplayName("the printer's white edge is the project's: saved, opened, and what a count is checked against")
    void theEdgeIsTheProjects(@TempDir final Path dir) throws IOException {
        final ProjectModel m = derivedOnSheets();           // three A3 sheets across
        m.edgeMmProperty().set(8);
        final Path file = dir.resolve("edge.wtf");
        ProjectIo.save(m, file);
        final WtfFile saved = WtfFile.read(file);
        assertEquals(8, saved.medium().edgeMm(), 1e-9);
        assertEquals(3 * 420 - 2 * 25 - 2 * 8, saved.print().widthMm(), 1e-6, "the count came to less paper");

        final ProjectModel open = filled();
        open.edgeMmProperty().set(3);
        ProjectIo.load(open, file, CATALOG);
        assertEquals(8, open.edgeMmProperty().get(), 1e-9, "the file's edge, not the window's");
        assertEquals(ProjectModel.SizeBy.SHEETS, open.sizeByProperty().get(), "and its count still counts");
        assertEquals(m.printSize(), open.printSize());
        assertEquals(saved.toJob(CATALOG, dir).layout(), open.build().job().layout(),
                "the editor's sheets are the command line's");
    }

    @Test
    @DisplayName("a project remembers that it was sized in sheets, and on which axis")
    void theSheetCountSurvivesARoundTrip(@TempDir final Path dir) throws IOException {
        final ProjectModel m = filled();
        m.sizeByProperty().set(ProjectModel.SizeBy.SHEETS);
        m.countAcrossProperty().set(false);
        m.sheetCountProperty().set(4);

        final Path file = dir.resolve("by-sheets.wtf");
        ProjectIo.save(m, file);

        final ProjectModel back = new ProjectModel();
        ProjectIo.load(back, file, CATALOG);
        assertEquals(ProjectModel.SizeBy.SHEETS, back.sizeByProperty().get());
        assertFalse(back.countAcrossProperty().get(), "it was counted down, not across");
        assertEquals(4, back.sheetCountProperty().get());
    }

    @Test
    @DisplayName("a project remembers whether its area was worked out or drawn")
    void theAreaSourceSurvivesARoundTrip(@TempDir final Path dir) throws IOException {
        final ProjectModel m = new ProjectModel();
        final Provider p = CATALOG.all().iterator().next();
        m.providerProperty().set(p);
        m.themeProperty().set(p.themes().getFirst());
        m.subjectProperty().set(Shapes.rectangle(new LonLatBounds(12.0, 48.5, 18.9, 51.1)));

        final Path file = dir.resolve("from-subject.wtf");
        ProjectIo.save(m, file);

        final ProjectModel back = new ProjectModel();
        ProjectIo.load(back, file, CATALOG);
        assertEquals(ProjectModel.AreaSource.FROM_SUBJECT, back.areaSourceProperty().get());
        assertNotNull(back.subjectProperty().get());

        // And the file still carries the resulting area ring, so a reader that knows nothing
        // about where it came from – the CLI, the engine – opens it unchanged.
        assertTrue(Files.readString(file).contains("\"area\""));
    }

    /** A buffer that eats the shape is a message, not a stack trace two steps later. */
    @Test
    void anImpossibleBufferIsReportedAsAProblem() {
        final ProjectModel m = filled();
        // Inward by more than the shape has, whichever shape it is: the Custom one first
        m.bufferValueProperty().set(-10_000_000);
        assertFalse(m.build().ok());
        assertNotNull(m.build().problem());

        m.subjectProperty().set(m.customProperty().get());
        m.areaSourceProperty().set(ProjectModel.AreaSource.FROM_SUBJECT);
        assertFalse(m.build().ok());
        assertNotNull(m.build().problem(), "and the subject's");
    }

    @Test
    @DisplayName("a Custom area is grown as the fit is, and the fit has nothing to say about it")
    void aCustomAreaIsGrown() {
        final ProjectModel m = filled();
        m.simplifyProperty().set(Shapes.Simplify.CONVEX_HULL);      // the subject's, which is not asked
        m.bufferValueProperty().set(50_000);

        assertEquals(ProjectModel.AreaSource.CUSTOM, m.areaSourceProperty().get());
        final Geometry custom = m.customProperty().get();
        assertTrue(Shapes.reproduces(m.areaProperty().get(), Shapes.areaFrom(custom, Shapes.Simplify.NONE, 50_000)));
        assertTrue(Shapes.boundsOf(m.fill()).west() < Shapes.boundsOf(custom).west(),
                "fifty kilometers of room reach further west than the shape does");
        assertTrue(Shapes.isRectangle(m.areaProperty().get()), "and a box grows into a box");
    }

    @Test
    void shapeImportReadsGeoJsonWithAHole(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("hole.geojson");
        Files.writeString(file, """
                {"type":"Feature","geometry":{"type":"Polygon","coordinates":[
                  [[12,48],[19,48],[19,51],[12,51],[12,48]],
                  [[14,49],[15,49],[15,50],[14,50],[14,49]]]}}
                """);
        final Geometry g = ShapeImport.read(file);
        assertEquals(1, g.getNumGeometries());
        assertEquals(1, ((org.locationtech.jts.geom.Polygon) g).getNumInteriorRing());
    }

    @Test
    void shapeImportReadsKmlAndCsv(@TempDir final Path dir) throws IOException {
        final Path kml = dir.resolve("a.kml");
        Files.writeString(kml, """
                <?xml version="1.0"?><kml><Placemark><Polygon><outerBoundaryIs><LinearRing>
                <coordinates>12,48,0 19,48,0 19,51,0 12,51,0</coordinates>
                </LinearRing></outerBoundaryIs></Polygon></Placemark></kml>
                """);
        assertEquals(new LonLatBounds(12, 48, 19, 51), Shapes.boundsOf(ShapeImport.read(kml)));

        final Path csv = dir.resolve("a.csv");
        Files.writeString(csv, "# lon,lat\n12,48\n19,48\n19,51\n12,51\n");
        assertEquals(new LonLatBounds(12, 48, 19, 51), Shapes.boundsOf(ShapeImport.read(csv)));
    }

    @Test
    void shapeImportRejectsWhatItCannotRead(@TempDir final Path dir) throws IOException {
        final Path bad = dir.resolve("a.dbf");
        Files.write(bad, new byte[]{1, 2, 3});
        final IllegalArgumentException kind = assertThrows(IllegalArgumentException.class,
                () -> ShapeImport.read(bad));
        // Said in the window as a refusal, with its reason – not as something nobody saw coming
        assertEquals(I18n.get("error.refused.title"), ErrorText.explain(kind).headline());
        assertEquals(kind.getMessage(), ErrorText.explain(kind).body());

        final Path empty = dir.resolve("empty.geojson");
        Files.writeString(empty, "{\"type\":\"FeatureCollection\",\"features\":[]}");
        assertThrows(IllegalArgumentException.class, () -> ShapeImport.read(empty));

        final Path noPolygon = dir.resolve("empty.kml");
        Files.writeString(noPolygon, "<?xml version=\"1.0\"?><kml><Placemark></Placemark></kml>");
        assertThrows(IllegalArgumentException.class, () -> ShapeImport.read(noPolygon));
    }

    @Test
    void csvNeedsThreePoints(@TempDir final Path dir) throws IOException {
        final Path csv = dir.resolve("two.csv");
        Files.writeString(csv, "12,48\n19,48\n");
        assertThrows(IllegalArgumentException.class, () -> ShapeImport.read(csv));
    }

    @Test
    void polygonFromClickedPointsClosesItself() {
        final Geometry g = Shapes.polygon(new Coordinate[]{
                new Coordinate(12, 48), new Coordinate(19, 48), new Coordinate(19, 51)});
        assertEquals(4, g.getCoordinates().length, "an open ring must be closed on the way in");
        assertTrue(g.isValid());
    }

    // ------------------------------------------------------------------- files that contradict themselves

    /**
     * A project whose area is not what its own settings produce is refused rather than opened.
     * <p>
     * The rule everywhere else in this loader is that a file opens with a note rather than not at
     * all, because a project is something you can mail somebody. That rule is about a machine that
     * differs. This is a file that disagrees with itself, and a project whose screen does not match
     * its own settings is worse than one that will not open.
     */
    @Test
    void aProjectThatDoesNotReproduceItsAreaIsRefused(@TempDir final Path dir) throws IOException {
        final Path file = driftedFile(dir);
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ProjectIo.load(new ProjectModel(), file, CATALOG));
        assertTrue(ex.getMessage().contains("does not reproduce"), ex.getMessage());
        assertTrue(ex.getMessage().contains("\"simplify\""), "it has to say which line to delete");

        // The way out it names: the fit deleted, the area stands, as a Custom area grown by nothing
        final ObjectMapper json = new ObjectMapper();
        final ObjectNode root = (ObjectNode) json.readTree(file.toFile());
        ((ObjectNode) root.get("refine")).remove("simplify");
        json.writeValue(file.toFile(), root);
        final ProjectModel opened = new ProjectModel();
        ProjectIo.load(opened, file, CATALOG);
        assertEquals(ProjectModel.AreaSource.CUSTOM, opened.areaSourceProperty().get());
        assertEquals(0, opened.bufferValueProperty().get(), 1e-9);
        assertTrue(Shapes.reproduces(WtfFile.read(file).areaGeometry(), opened.areaProperty().get()));
    }

    /**
     * A file that names neither a fit nor a Custom shape has an area worked out from nothing: it
     * stands as given, as every reader prints it, and the editor opens it as the Custom shape it is,
     * grown by nothing – whatever shape that is.
     */
    @Test
    @DisplayName("an area worked out from nothing the file names opens as a Custom area of its shape, grown by nothing")
    void anAreaFromNothingOpensAsCustom(@TempDir final Path dir) throws IOException {
        final Path file = triangleAreaFile(dir);
        final ProjectModel m = new ProjectModel();
        assertTrue(ProjectIo.load(m, file, CATALOG).isEmpty(), "no warnings expected");

        assertEquals(ProjectModel.AreaSource.CUSTOM, m.areaSourceProperty().get());
        assertEquals(0, m.bufferValueProperty().get(), 1e-9);
        final Geometry stated = WtfFile.read(file).areaGeometry();
        assertTrue(Shapes.reproduces(stated, m.customProperty().get()), "the stated shape is the Custom shape");
        assertTrue(Shapes.reproduces(stated, m.areaProperty().get()), "and it prints as it stands");
        assertEquals(WtfFile.read(file).toJob(CATALOG, dir).print(), m.build().job().print(),
                "as the command line prints it");
    }

    /** A refused file leaves whatever was open exactly as it was. */
    @Test
    void aRefusedProjectDoesNotHalfLoadTheModel(@TempDir final Path dir) throws IOException {
        final Path file = driftedFile(dir);
        final ProjectModel open = new ProjectModel();
        open.titleProperty().set("something I was working on");
        assertThrows(IllegalArgumentException.class, () -> ProjectIo.load(open, file, CATALOG));
        assertEquals("something I was working on", open.titleProperty().get());
        assertNull(open.areaProperty().get());
    }

    /** How a test makes a file that is refused. */
    @FunctionalInterface
    private interface Refused {
        Path in(Path dir) throws IOException;
    }

    static Stream<Arguments> refusedFiles() {
        return Stream.of(
                Arguments.of("JSON of another kind", (Refused) dir -> {
                    final Path file = dir.resolve("other.wtf");
                    ProjectIo.save(filled(), file);
                    Files.writeString(file, Files.readString(file)
                            .replace("\"format\" : \"wtf\"", "\"format\" : \"wow\""));
                    return file;
                }),
                Arguments.of("an area that has drifted from its subject", (Refused) ProjectIoTest::driftedFile),
                Arguments.of("a Custom shape and a fit both", (Refused) ProjectIoTest::bothFile),
                Arguments.of("a ring of two points", (Refused) ProjectIoTest::twoPointRingFile),
                Arguments.of("a subject across the antimeridian", (Refused) ProjectIoTest::acrossTheAntimeridianFile));
    }

    /** Fiji's Vanua Levu as a program that does not cut at ±180° writes it: a degree across. */
    private static final Coordinate[] ISLAND = {new Coordinate(179.0, -16.0), new Coordinate(-179.5, -16.2),
            new Coordinate(-179.8, -17.0), new Coordinate(179.2, -16.9)};

    /** {@link #filled}'s Custom area with a fit of the subject beside it, which a file has one or the other of. */
    private static Path bothFile(final Path dir) throws IOException {
        final Path file = dir.resolve("both.wtf");
        ProjectIo.save(filled(), file);
        final WtfFile f = WtfFile.read(file);
        new WtfFile(f.format(), f.title(), f.notes(), f.saved(), f.source(), f.area(), f.subject(),
                new WtfFile.Refine(f.refine().custom(), Shapes.Simplify.BOUNDING_BOX, f.refine().bufferValue(), null),
                f.regions(), f.print(), f.medium(), f.export()).write(file);
        return file;
    }

    @Test
    @DisplayName("a Custom shape and a fit both are refused as the file is read, and the open project stays")
    void aCustomShapeAndAFitBothAreRefused(@TempDir final Path dir) throws IOException {
        final Path file = bothFile(dir);
        final ProjectModel open = new ProjectModel();
        open.titleProperty().set("something I was working on");
        final Refusal ex = assertThrows(Refusal.class, () -> ProjectIo.load(open, file, CATALOG));
        assertEquals(ComposeReason.REFINE_TWO_SOURCES, ex.reason());
        assertEquals("something I was working on", open.titleProperty().get());
    }

    /** The Custom area, as {@link #filled} has it, with the island as its subject. */
    private static Path acrossTheAntimeridianFile(final Path dir) throws IOException {
        final Path file = dir.resolve("fiji.wtf");
        ProjectIo.save(filled(), file);
        final WtfFile f = WtfFile.read(file);
        new WtfFile(f.format(), f.title(), f.notes(), f.saved(), f.source(), f.area(),
                WtfFile.multiRingOf(Shapes.polygon(ISLAND), "polygon", "fiji.geojson"),
                f.refine(), f.regions(), f.print(), f.medium(), f.export()).write(file);
        return file;
    }

    /**
     * A Custom area's subject was taken in unchecked and said only at the export, 359° wide by
     * then; the command line refused the same file outright.
     */
    @Test
    @DisplayName("a subject across the antimeridian is refused as the file is read, in the window's language")
    void aSubjectAcrossTheAntimeridianIsRefusedAsItIsRead(@TempDir final Path dir) throws IOException {
        final Path file = acrossTheAntimeridianFile(dir);
        final ProjectModel open = new ProjectModel();
        open.titleProperty().set("something I was working on");
        final Refusal ex = assertThrows(Refusal.class, () -> ProjectIo.load(open, file, CATALOG));
        assertEquals(ComposeReason.SHAPE_CROSSES_ANTIMERIDIAN, ex.reason());
        assertEquals(List.of(Shapes.Role.SUBJECT, 179.0, -179.5), ex.args());
        assertEquals("something I was working on", open.titleProperty().get(), "the open project stays");

        final Locale before = I18n.locale();
        try {
            I18n.setLocale(Locale.ENGLISH);
            assertEquals("The subject shape crosses the antimeridian between longitudes 179 and -179.5:"
                    + " neighboring points more than half the world apart are joined the short way, across"
                    + " ±180°. The map ends there, and WTF does not print across it – the print would be"
                    + " two, one at each end of the map.", ErrorText.explain(ex).body());
            I18n.setLocale(Locale.forLanguageTag("cs"));
            final ErrorText.Explained cs = ErrorText.explain(ex);
            assertEquals(I18n.get("error.refused.title"), cs.headline());
            assertEquals(I18n.format("engine.compose.shapeCrossesAntimeridian",
                    I18n.get("engine.role.subject"), 179.0, -179.5), cs.body());
            assertTrue(cs.body().startsWith("Tvar předmětu přechází přes 180. poledník"), cs.body());
            assertTrue(cs.body().contains("179,5"), () -> "the longitude written the Czech way: " + cs.body());
        } finally {
            I18n.setLocale(before);
        }
    }

    /**
     * What WTF draws is a straight line on the flat map, and one longer than half the world is
     * split where the map draws it before it is kept – so the file says which way round it goes,
     * and opens again. The same edge written whole by another program is taken the short way.
     */
    @Test
    @DisplayName("a subject drawn wider than half the world is saved split, and opens again as drawn")
    void aSubjectDrawnWiderThanHalfTheWorldOpensAgain(@TempDir final Path dir) throws IOException {
        final Geometry drawn = Shapes.splitLongEdges(Shapes.polygon(new Coordinate[]{
                new Coordinate(-100, 10), new Coordinate(100, 60), new Coordinate(100, 70), new Coordinate(-100, 70)}));
        final ProjectModel m = filled();
        m.subjectProperty().set(Shapes.withPart(null, drawn, Shapes.Role.SUBJECT));
        m.customProperty().set(Shapes.rectangle(new LonLatBounds(-100, 10, 100, 70)));
        m.bufferValueProperty().set(0);
        final Path file = dir.resolve("wide.wtf");
        ProjectIo.save(m, file);

        final ProjectModel back = new ProjectModel();
        assertTrue(ProjectIo.load(back, file, CATALOG).isEmpty());
        assertTrue(back.subjectProperty().get().equalsExact(m.subjectProperty().get()),
                () -> back.subjectProperty().get().toString());
        assertEquals(200, Shapes.boundsOf(back.areaProperty().get()).widthDegrees(), 1e-9,
                "and the box beside it is the 200° range it was, not the 160° across the antimeridian");
    }

    /** A subject with a ring of two points: a line, which JTS refuses in its own English. */
    private static Path twoPointRingFile(final Path dir) throws IOException {
        final Path file = dir.resolve("line.wtf");
        ProjectIo.save(filled(), file);
        final WtfFile f = WtfFile.read(file);
        final List<List<Double>> line = List.of(List.of(12.0, 48.5), List.of(18.9, 51.1));
        new WtfFile(f.format(), f.title(), f.notes(), f.saved(), f.source(), f.area(),
                new WtfFile.MultiRing("polygon", "drawn", List.of(List.of(line))),
                f.refine(), f.regions(), f.print(), f.medium(), f.export()).write(file);
        return file;
    }

    /**
     * Before this, the ring reached JTS, which said "Invalid number of points in LinearRing" – in
     * English under the refusal's headline, since any exception with a message counted as one.
     */
    @Test
    @DisplayName("a ring too short to be one is refused by name, in the window's language")
    void aRingTooShortIsRefusedByName(@TempDir final Path dir) throws IOException {
        final Path file = twoPointRingFile(dir);
        final Refusal ex = assertThrows(Refusal.class, () -> ProjectIo.load(new ProjectModel(), file, CATALOG));
        assertEquals(ComposeReason.RING_TOO_SHORT, ex.reason());
        assertEquals(List.of(Shapes.Role.SUBJECT, 2), ex.args());

        final Locale before = I18n.locale();
        try {
            I18n.setLocale(Locale.ENGLISH);
            assertEquals("The subject shape has a ring of 2 points; a ring needs at least three",
                    ErrorText.explain(ex).body());
            I18n.setLocale(Locale.forLanguageTag("cs"));
            final ErrorText.Explained cs = ErrorText.explain(ex);
            assertEquals(I18n.get("error.refused.title"), cs.headline());
            assertEquals(I18n.format("engine.compose.ringTooShort", I18n.get("engine.role.subject"), 2), cs.body());
            assertFalse(cs.body().contains("LinearRing"), cs.body());
        } finally {
            I18n.setLocale(before);
        }
    }

    /**
     * What the window makes of a refusal: its reason, written for a person, and not "WTF did not see
     * this one coming" with the reason tucked into the details for a bug report.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedFiles")
    @DisplayName("a refused project says why in the error dialog")
    void aRefusalIsSaidAsOne(final String what, final Refused refused, @TempDir final Path dir)
            throws IOException {
        final Path file = refused.in(dir);
        final Exception ex = assertThrows(Exception.class, () -> ProjectIo.load(new ProjectModel(), file, CATALOG));
        assertTrue(ex instanceof Refusal || ex instanceof ProjectRefusal, () -> "refused by type, not by wording: " + ex);
        final ErrorText.Explained said = ErrorText.explain(ex);
        assertEquals(I18n.get("error.refused.title"), said.headline(), ex::toString);
        assertEquals(ex.getMessage(), said.body());
    }

    /** A project whose area was worked out from its subject, with a different shape put in its place. */
    private static Path driftedFile(final Path dir) throws IOException {
        final ProjectModel m = new ProjectModel();
        final Provider p = CATALOG.all().iterator().next();
        m.providerProperty().set(p);
        m.themeProperty().set(p.themes().getFirst());
        m.subjectProperty().set(Shapes.rectangle(new LonLatBounds(12.0, 48.5, 18.9, 51.1)));
        m.bufferValueProperty().set(10_000);

        final Path file = dir.resolve("drifted.wtf");
        ProjectIo.save(m, file);

        // Somebody, or an older build, put a different shape in it
        final String json = Files.readString(file).replace("12.0,", "10.0,");
        Files.writeString(file, json);
        return file;
    }

    /** A file with three corners in the area and nothing claiming to have worked them out. */
    private static Path triangleAreaFile(final Path dir) throws IOException {
        final Path file = dir.resolve("triangle.wtf");
        ProjectIo.save(filled(), file);
        final WtfFile f = WtfFile.read(file);
        final Geometry triangle = Shapes.GF.createPolygon(new Coordinate[]{
                new Coordinate(12.0, 48.5), new Coordinate(18.9, 48.5),
                new Coordinate(15.0, 51.1), new Coordinate(12.0, 48.5)});
        new WtfFile(f.format(), f.title(), f.notes(), f.saved(),
                f.source(), WtfFile.multiRingOf(triangle, "polygon", "drawn"), f.subject(),
                WtfFile.Refine.NONE, f.regions(), f.print(), f.medium(), f.export())
                .write(file);
        return file;
    }

    // -------------------------------------------------------------------- the whole file, or none of it

    /**
     * A project that differs from a new one in every setting the file holds while the area is worked
     * out from the subject and the print is sized in sheets – and in nothing it does not hold.
     */
    private static ProjectModel derivedOnSheets() {
        // Not the first provider, which is what a file naming an unknown one falls back to
        final Provider p = List.copyOf(CATALOG.all()).get(1);
        final ProjectModel m = new ProjectModel();
        m.providerProperty().set(p);
        m.themeProperty().set(p.themes().getLast());
        m.mapLangProperty().set("de");
        m.titleProperty().set("Bohemia and an island");
        m.notesProperty().set("For the hall");
        m.simplifyProperty().set(Shapes.Simplify.CONVEX_HULL);
        m.bufferValueProperty().set(5_000);
        m.subjectProperty().set(Shapes.GF.createGeometryCollection(new Geometry[]{
                Shapes.rectangle(new LonLatBounds(12.0, 48.5, 18.9, 51.1)),
                Shapes.rectangle(new LonLatBounds(20.0, 48.5, 20.4, 48.9))}).union());
        m.subjectOriginProperty().set("bohemia.geojson");
        m.paperProperty().set(Medium.paper("A3"));
        m.orientationProperty().set(Medium.Orientation.LANDSCAPE);
        m.overlapMmProperty().set(25);
        // The size step's answer, which the sheets set aside
        m.printModeProperty().set(PrintSize.Mode.FILL_AND_CROP);
        m.printWidthMmProperty().set(3000);
        m.printHeightMmProperty().set(2000);
        m.sizeByProperty().set(ProjectModel.SizeBy.SHEETS);
        m.countAcrossProperty().set(true);
        m.sheetCountProperty().set(3);
        m.outsideDiffersProperty().set(true);
        m.zoomProperty().set(14);
        m.insideEffectProperty().set(new TileEffect(0.5, 0.1, 0.2));
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
        return m;
    }

    /**
     * The other branches: a Custom area over a subject, on a roll, sized in millimeters. Built from a
     * new project and changed only where the file holds it – a fit has no place in it beside a
     * Custom shape, so it could not come back.
     */
    private static ProjectModel customOnARoll() {
        final Provider p = CATALOG.all().iterator().next();
        final ProjectModel m = new ProjectModel();
        m.providerProperty().set(p);
        m.themeProperty().set(p.themes().getFirst());
        m.subjectProperty().set(Shapes.rectangle(new LonLatBounds(14.0, 49.5, 15.0, 50.3)));
        m.subjectOriginProperty().set("drawn");
        m.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
        m.customProperty().set(Shapes.rectangle(new LonLatBounds(13.0, 49.0, 16.0, 50.8)));
        m.customOriginProperty().set("drawn");
        m.mediumKindProperty().set(ProjectModel.MediumKind.ROLL);
        m.rollWidthMmProperty().set(610);
        m.stripAxisProperty().set(Medium.Axis.HORIZONTAL);
        m.printModeProperty().set(PrintSize.Mode.FIT_INSIDE);
        m.printWidthMmProperty().set(1200);
        m.printHeightMmProperty().set(800);
        m.zoomProperty().set(12);
        // Not stroked, and still the subject's color on the map, which the file keeps
        m.boundaryWidthMmProperty().set(2.5);
        m.boundaryRgbProperty().set(0x224466);
        m.baseNameProperty().set("valley");
        return m;
    }

    /**
     * A project with no subject and no area yet: the paper, the map and the zoom chosen, and the fit
     * and the growth the subject picked next is to get. There is nothing to print, and it is still
     * worth keeping.
     */
    private static ProjectModel nothingDrawnYet() {
        final Provider p = List.copyOf(CATALOG.all()).get(1);
        final ProjectModel m = new ProjectModel();
        m.providerProperty().set(p);
        m.themeProperty().set(p.themes().getLast());
        m.mapLangProperty().set("de");
        m.simplifyProperty().set(Shapes.Simplify.CONVEX_HULL);
        m.bufferValueProperty().set(10_000);
        m.paperProperty().set(Medium.paper("A2"));
        m.orientationProperty().set(Medium.Orientation.LANDSCAPE);
        m.sizeByProperty().set(ProjectModel.SizeBy.SHEETS);
        m.countAcrossProperty().set(true);
        m.sheetCountProperty().set(2);
        m.zoomProperty().set(9);
        return m;
    }

    /** A subject, and a Custom area chosen before there was anything to take over: no shape yet. */
    private static ProjectModel noCustomShapeYet() {
        final Provider p = CATALOG.all().iterator().next();
        final ProjectModel m = new ProjectModel();
        m.providerProperty().set(p);
        m.themeProperty().set(p.themes().getFirst());
        m.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
        m.subjectProperty().set(Shapes.rectangle(new LonLatBounds(14.0, 49.5, 15.0, 50.3)));
        m.mediumKindProperty().set(ProjectModel.MediumKind.ROLL);
        m.zoomProperty().set(12);
        return m;
    }

    /**
     * A Custom area of two parts over a subject, grown and fading, and reopened as it was: the
     * file holds the shape as parts, the growth and the fade, and checks the area against them.
     */
    private static ProjectModel customGrownAndFading() {
        final Provider p = CATALOG.all().iterator().next();
        final ProjectModel m = new ProjectModel();
        m.providerProperty().set(p);
        m.themeProperty().set(p.themes().getFirst());
        m.subjectProperty().set(Shapes.rectangle(new LonLatBounds(14.0, 49.5, 15.0, 50.3)));
        m.subjectOriginProperty().set("drawn");
        m.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
        m.customProperty().set(Shapes.withPart(
                Shapes.withPart(null, Shapes.rectangle(new LonLatBounds(12.0, 48.5, 15.5, 51.1)), Shapes.Role.AREA),
                Shapes.polygon(new Coordinate[]{new Coordinate(15.0, 49.0), new Coordinate(18.9, 48.6),
                        new Coordinate(17.0, 50.5)}), Shapes.Role.AREA));
        m.customOriginProperty().set("drawn + 2");
        m.bufferValueProperty().set(20_000);
        m.areaFadeProperty().set(true);
        m.zoomProperty().set(11);
        return m;
    }

    @Test
    @DisplayName("a Custom area grown and fading saves, reproduces its own area, and opens as it was")
    void aCustomAreaSurvivesARoundTrip(@TempDir final Path dir) throws IOException {
        final ProjectModel m = customGrownAndFading();
        final Path file = dir.resolve("custom.wtf");
        ProjectIo.save(m, file);

        final WtfFile f = WtfFile.read(file);
        assertEquals(2, f.refine().custom().polygons().size(), "the parts, as parts");
        assertEquals("drawn + 2", f.refine().custom().source());
        assertNull(f.refine().simplify(), "and no fit beside them");
        assertEquals(20_000, f.refine().bufferValue(), 1e-9);
        assertEquals(Boolean.TRUE, f.refine().fade());
        assertEquals("custom", f.area().source());
        final WtfFile.AreaCheck check = f.checkArea();
        assertTrue(check.claimed() && check.reproduces(), () -> "off by " + check.deviationMeters() + " m");

        final ProjectModel back = new ProjectModel();
        assertTrue(ProjectIo.load(back, file, CATALOG).isEmpty(), "no warnings expected");
        assertEquals(ProjectModel.AreaSource.CUSTOM, back.areaSourceProperty().get());
        assertTrue(back.customProperty().get().equalsExact(m.customProperty().get()), "the same shape, part for part");
        assertEquals("drawn + 2", back.customOriginProperty().get());
        assertTrue(back.areaFadeProperty().get());
        assertTrue(back.areaProperty().get().equalsExact(m.areaProperty().get()), "and the same area worked out");
        assertTrue(back.sameProject(m.snapshot(), back.snapshot()));
        assertEquals(0.0, Shapes.deviation(Shapes.fitOf(m.customProperty().get(), Shapes.Simplify.NONE),
                f.toJob(CATALOG, dir).fadeFrom()), 1e-12, "the command line fades from the shape made one");
    }

    /** Having no area is not a refusal, and nothing asks for a subject. */
    @Test
    @DisplayName("a project with nothing drawn yet saves, opens as it was left, and prints nothing")
    void aProjectWithNothingDrawnYetSaves(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("unfinished.wtf");
        ProjectIo.save(nothingDrawnYet(), file);
        final String json = Files.readString(file);
        assertFalse(json.contains("\"area\""), json);
        assertFalse(json.contains("\"subject\""), json);

        final ProjectModel back = new ProjectModel();
        assertTrue(ProjectIo.load(back, file, CATALOG).isEmpty(), "no warnings expected");
        assertNull(back.areaProperty().get());
        assertEquals(ProjectModel.AreaSource.FROM_SUBJECT, back.areaSourceProperty().get(),
                "still to be worked out from the subject, not a Custom area with no shape");
        back.subjectProperty().set(Shapes.rectangle(new LonLatBounds(14.0, 49.5, 15.0, 50.3)));
        back.settleArea();
        assertNotNull(back.areaProperty().get(), "so the subject picked next gets its area");

        final Refusal nothing = assertThrows(Refusal.class, () -> WtfFile.read(file).toJob(CATALOG, dir));
        assertEquals(ComposeReason.NOTHING_TO_PRINT, nothing.reason(), "the command line has nothing to print");
    }

    /**
     * The subject's color is its color on the map as well as the line the print draws, so it stays
     * when the line is switched off – a project saved that way reopens in it, not the default orange.
     */
    @Test
    @DisplayName("the subject keeps its color while its outline is not stroked")
    void theSubjectKeepsItsColorUnstroked(@TempDir final Path dir) throws IOException {
        final ProjectModel m = filled();
        m.subjectProperty().set(Shapes.rectangle(new LonLatBounds(14.0, 49.5, 15.0, 50.3)));
        m.boundaryStrokedProperty().set(false);
        m.boundaryWidthMmProperty().set(2.5);
        m.boundaryRgbProperty().set(0x336699);
        final Path file = dir.resolve("unstroked.wtf");
        ProjectIo.save(m, file);

        final ProjectModel back = new ProjectModel();
        ProjectIo.load(back, file, CATALOG);
        assertEquals(0x336699, back.boundaryRgbProperty().get());
        assertEquals(2.5, back.boundaryWidthMmProperty().get(), 1e-9);
        assertFalse(back.boundaryStrokedProperty().get());
        assertNull(WtfFile.read(file).toJob(CATALOG, dir).regions().boundary(), "and nothing is stroked");

        // Stroked, the print draws the line, in that color
        m.boundaryStrokedProperty().set(true);
        ProjectIo.save(m, file);
        final Regions.BoundaryStroke line = WtfFile.read(file).toJob(CATALOG, dir).regions().boundary();
        assertNotNull(line);
        assertEquals(0x336699, line.rgb());
    }

    /**
     * A sheet the paper list does not offer – a file edited by hand, a paper this build no longer
     * has – opens as it is, so the editor prints what the command line prints rather than whatever
     * paper happened to be open.
     */
    @Test
    @DisplayName("a sheet size that is no standard paper opens as it is")
    void aSheetOfNoStandardSizeOpensAsItIs(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("custom.wtf");
        ProjectIo.save(filled(), file);
        final WtfFile f = WtfFile.read(file);
        final WtfFile.MediumJson sheet = f.medium();
        new WtfFile(f.format(), f.title(), f.notes(), f.saved(), f.source(), f.area(), f.subject(),
                f.refine(), f.regions(), f.print(),
                new WtfFile.MediumJson(sheet.kind(), null, null, 500.0, 700.0, Medium.Orientation.LANDSCAPE,
                        sheet.overlapMm(), sheet.edgeMm(), null, null),
                f.export()).write(file);

        final ProjectModel open = new ProjectModel();
        open.paperProperty().set(Medium.paper("A3"));
        ProjectIo.load(open, file, CATALOG);

        final Medium.Paper paper = open.paperProperty().get();
        assertEquals(500, paper.widthMm(), 1e-9);
        assertEquals(700, paper.heightMm(), 1e-9);
        assertEquals(I18n.get("medium.paper.custom"), paper.name());
        assertEquals(WtfFile.read(file).toJob(CATALOG, dir).medium(), open.build().job().medium(),
                "the editor's sheets are the command line's");

        ProjectIo.save(open, file);
        final WtfFile.MediumJson saved = WtfFile.read(file).medium();
        assertEquals(500, (double) saved.sheetWidthMm(), 1e-9, "and saved again, as it was");
        assertEquals(700, (double) saved.sheetHeightMm(), 1e-9);
    }

    /**
     * Under inches the refusal says the size in inches (D32), and quotes the print block as the
     * file spells it, since that is what the reader is to find there.
     */
    @Test
    @DisplayName("under inches a refused count gives its size in inches")
    void aRefusedCountSaysInches(@TempDir final Path dir) throws IOException {
        final Units before = Units.current();
        try {
            Units.use("inch");
            final Path file = dir.resolve("recounted.wtf");
            ProjectIo.save(derivedOnSheets(), file);
            Files.writeString(file, Files.readString(file).replace("\"sheetsAcross\" : 3", "\"sheetsAcross\" : 4"));
            final String said = assertThrows(IllegalArgumentException.class,
                    () -> ProjectIo.load(filled(), file, CATALOG)).getMessage();
            assertTrue(said.matches("(?s).*comes to [\\d.,]+ in,.*"), said);
            assertFalse(said.matches("(?s).*\\d mm\\b.*"), said);
            assertTrue(said.contains("widthMm"), "the file's own words stay the file's");
        } finally {
            Units.use(before.key());
        }
    }

    /**
     * A count of sheets is a note, as the area's working-out is, and like it has to still come to the
     * size the file states – or the editor would open one print while the command line made another.
     */
    @Test
    @DisplayName("a project whose sheet count no longer comes to its size is refused, and says how to open it")
    void aCountThatDoesNotComeToTheSizeIsRefused(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("recounted.wtf");
        ProjectIo.save(derivedOnSheets(), file);            // three A3 sheets across, 1 200 mm
        Files.writeString(file, Files.readString(file).replace("\"sheetsAcross\" : 3", "\"sheetsAcross\" : 4"));

        final ProjectModel open = filled();
        final int revision = open.revisionProperty().get();
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ProjectIo.load(open, file, CATALOG));
        assertTrue(ex.getMessage().contains("does not reproduce its own size"), ex.getMessage());
        assertTrue(ex.getMessage().contains("\"sheetsAcross\""), "it has to say which line to delete");
        assertEquals(revision, open.revisionProperty().get(), "and nothing of it reached the open project");

        // The way out it names: the count deleted, the project opens as the print block states
        final ObjectMapper json = new ObjectMapper();
        final ObjectNode root = (ObjectNode) json.readTree(file.toFile());
        ((ObjectNode) root.get("medium")).remove("sheetsAcross");
        json.writeValue(file.toFile(), root);
        ProjectIo.load(open, file, CATALOG);
        assertEquals(ProjectModel.SizeBy.MILLIMETERS, open.sizeByProperty().get());
        assertEquals(PrintSize.Mode.FIT_WIDTH, open.printSize().mode());
        assertEquals(3 * 420 - 2 * 25 - 2 * 5, open.printSize().widthMm(), 1e-6);
    }

    /**
     * A DPI is the same kind of note as a count of sheets: the print block holds the width it comes
     * to at the zoom, which is what the command line prints, and the size step's own answer is set
     * aside beside it.
     */
    @Test
    @DisplayName("a print sized at a DPI is that DPI at its zoom, saved as the width it comes to")
    void sizingAtADpi(@TempDir final Path dir) throws IOException {
        final ProjectModel m = customAtADpi();
        assertEquals(150, m.printRect().dpi(13, 2), 1e-9, "the DPI asked for, at the zoom and scale chosen");
        final double width = m.printSize().widthMm();
        m.zoomProperty().set(14);
        assertEquals(2 * width, m.printSize().widthMm(), 1e-6, "a zoom up, twice as wide");
        assertEquals(150, m.printRect().dpi(14, 2), 1e-9, "at the same DPI");
        assertEquals(2 * PrintRect.labelHeightMmAt(150), m.printRect().labelHeightMm(14), 1e-9,
                "and the same place names, twice as tall as the usual tiles make them at 150 DPI");

        final Path file = dir.resolve("dpi.wtf");
        ProjectIo.save(m, file);
        final WtfFile saved = WtfFile.read(file);
        assertEquals(150, saved.print().dpi());
        assertEquals(PrintSize.Mode.FIT_WIDTH, saved.print().sizeMode());
        assertEquals(2 * width, saved.print().widthMm(), 1e-6);
        assertEquals(PrintSize.Mode.FILL_AND_CROP, saved.print().setAside().sizeMode(), "the size step's own, set aside");
        assertEquals(saved.toJob(CATALOG, dir).print(), m.build().job().print(),
                "the command line prints the editor's print");

        final ProjectModel open = filled();
        ProjectIo.load(open, file, CATALOG);
        assertEquals(ProjectModel.SizeBy.DPI, open.sizeByProperty().get());
        assertEquals(150, open.dpiProperty().get());
        assertEquals(PrintSize.Mode.FILL_AND_CROP, open.printModeProperty().get());
        assertEquals(m.printSize(), open.printSize());
    }

    @Test
    @DisplayName("a project whose DPI no longer comes to its size is refused, and says how to open it")
    void aDpiThatDoesNotComeToTheSizeIsRefused(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("redpi.wtf");
        final ProjectModel m = customAtADpi();
        ProjectIo.save(m, file);
        final double stated = m.printSize().widthMm();
        Files.writeString(file, Files.readString(file).replace("\"dpi\" : 150", "\"dpi\" : 300"));

        final ProjectModel open = filled();
        final int revision = open.revisionProperty().get();
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ProjectIo.load(open, file, CATALOG));
        assertTrue(ex.getMessage().contains("does not reproduce its own size"), ex.getMessage());
        assertTrue(ex.getMessage().contains("\"dpi\""), "it has to say which line to delete");
        assertEquals(revision, open.revisionProperty().get(), "and nothing of it reached the open project");

        final ObjectMapper json = new ObjectMapper();
        final ObjectNode root = (ObjectNode) json.readTree(file.toFile());
        ((ObjectNode) root.get("print")).remove("dpi");
        json.writeValue(file.toFile(), root);
        ProjectIo.load(open, file, CATALOG);
        assertEquals(ProjectModel.SizeBy.MILLIMETERS, open.sizeByProperty().get());
        assertEquals(PrintSize.Mode.FIT_WIDTH, open.printSize().mode());
        assertEquals(stated, open.printSize().widthMm(), 1e-6, "the size the print block states");
    }

    /** A scale is a note as a DPI is, and the zoom changes only the DPI it prints at. */
    @Test
    @DisplayName("a print sized at a scale is that scale at any zoom, saved as the width it comes to")
    void sizingAtAScale(@TempDir final Path dir) throws IOException {
        final ProjectModel m = customAtAScale();
        assertEquals(250_000, m.printRect().mapScale(), 1e-6, "the scale asked for");
        final double width = m.printSize().widthMm();
        final double dpi = m.printRect().dpi(13, 2);
        m.zoomProperty().set(14);
        assertEquals(width, m.printSize().widthMm(), 1e-9, "a zoom up, the same size");
        assertEquals(2 * dpi, m.printRect().dpi(14, 2), 1e-9, "at twice the DPI");
        assertEquals(net.trdlo.wtf.compose.ScaleMark.BAR, m.build().job().marks().scale(), "and the bar printed");

        final Path file = dir.resolve("scale.wtf");
        ProjectIo.save(m, file);
        final WtfFile saved = WtfFile.read(file);
        assertEquals(250_000, saved.print().mapScale());
        assertNull(saved.print().dpi());
        assertEquals(PrintSize.Mode.FIT_WIDTH, saved.print().sizeMode());
        assertEquals(width, saved.print().widthMm(), 1e-6);
        assertEquals(PrintSize.Mode.FILL_AND_CROP, saved.print().setAside().sizeMode(), "the size step's own, set aside");
        assertEquals(net.trdlo.wtf.compose.ScaleMark.BAR, saved.export().scale());
        assertEquals(saved.toJob(CATALOG, dir).print(), m.build().job().print(),
                "the command line prints the editor's print");
        assertEquals(m.build().job().marks().scale(), saved.toJob(CATALOG, dir).marks().scale(),
                "and its scale");

        final ProjectModel open = filled();
        ProjectIo.load(open, file, CATALOG);
        assertEquals(ProjectModel.SizeBy.SCALE, open.sizeByProperty().get());
        assertEquals(250_000, open.mapScaleProperty().get());
        assertTrue(open.scaleOnPrintProperty().get());
        assertEquals(net.trdlo.wtf.compose.ScaleMark.BAR, open.scaleMarkProperty().get());
        assertEquals(m.printSize(), open.printSize());

        m.scaleOnPrintProperty().set(false);
        assertNull(ProjectIo.toFile(m).export().scale(), "a scale not printed is left out");
        assertNull(m.build().job().marks().scale());
    }

    @Test
    @DisplayName("a project whose scale no longer comes to its size is refused, and says how to open it")
    void aScaleThatDoesNotComeToTheSizeIsRefused(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("rescale.wtf");
        ProjectIo.save(customAtAScale(), file);
        Files.writeString(file, Files.readString(file).replace("\"mapScale\" : 250000", "\"mapScale\" : 100000"));

        final ProjectModel open = filled();
        final int revision = open.revisionProperty().get();
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ProjectIo.load(open, file, CATALOG));
        assertTrue(ex.getMessage().contains("does not reproduce its own size"), ex.getMessage());
        assertTrue(ex.getMessage().contains("\"mapScale\""), "it has to say which line to delete");
        assertTrue(ex.getMessage().contains("1:100,000"), ex.getMessage());
        assertEquals(revision, open.revisionProperty().get(), "and nothing of it reached the open project");
    }

    /**
     * A print at ×2 is made of the map's @2x tiles and says so in the file; on a map that has none it
     * is made at the usual scale, and the choice is kept for a map that has them again – as the axis
     * of a count is kept for sheets while a roll is counted in its strips.
     */
    @Test
    @DisplayName("a print at ×2 is saved as one, and on a map without @2x tiles is made at the usual scale")
    void aPrintAtTwiceTheScale(@TempDir final Path dir) throws IOException {
        final ProjectModel m = customOnARoll();                // Mapy.com's basic map, which has @2x tiles
        final double dpi = m.printRect().dpi(12);
        m.scaleProperty().set(2);
        assertEquals(2, m.printScale());
        assertEquals(2 * dpi, m.build().job().print().dpi(12, m.build().job().regions().scale()), 1e-9);
        final Path file = dir.resolve("sharp.wtf");
        ProjectIo.save(m, file);
        assertEquals(2, WtfFile.read(file).regions().scale());
        assertEquals(2, WtfFile.read(file).toJob(CATALOG, dir).regions().scale(), "and the command line prints it so");

        m.themeProperty().set(m.providerProperty().get().requireTheme("aerial"));
        assertEquals(1, m.printScale(), "the aerial map has no @2x tiles");
        assertTrue(m.build().ok(), m.build().problem());
        assertEquals(1, m.build().job().regions().scale());
        assertNull(ProjectIo.toFile(m).regions().scale(), "the usual scale is left out of the file");
        assertEquals(2, m.scaleProperty().get(), "and the choice is kept");
    }

    /**
     * A Custom area on a roll, sized at 150 DPI at zoom 13 from Mapy.com's @2x tiles, with a box
     * of its own set aside.
     */
    private static ProjectModel customAtADpi() {
        final ProjectModel m = customOnARoll();
        m.zoomProperty().set(13);
        m.scaleProperty().set(2);
        m.printModeProperty().set(PrintSize.Mode.FILL_AND_CROP);
        m.printWidthMmProperty().set(3000);
        m.printHeightMmProperty().set(2000);
        m.sizeByProperty().set(ProjectModel.SizeBy.DPI);
        m.dpiProperty().set(150);
        return m;
    }

    /** {@link #customAtADpi}, sized at 1:250 000 instead, with the scale printed as a bar. */
    private static ProjectModel customAtAScale() {
        final ProjectModel m = customAtADpi();
        m.sizeByProperty().set(ProjectModel.SizeBy.SCALE);
        m.mapScaleProperty().set(250_000);
        m.scaleOnPrintProperty().set(true);
        m.scaleMarkProperty().set(net.trdlo.wtf.compose.ScaleMark.BAR);
        return m;
    }

    static Stream<Arguments> projects() {
        return Stream.of(
                Arguments.of("Custom, on a roll, sized at a DPI",
                        (Supplier<ProjectModel>) ProjectIoTest::customAtADpi),
                Arguments.of("Custom, on a roll, sized at a scale and printing it",
                        (Supplier<ProjectModel>) ProjectIoTest::customAtAScale),
                Arguments.of("worked out from the subject, on sheets, sized in sheets",
                        (Supplier<ProjectModel>) ProjectIoTest::derivedOnSheets),
                Arguments.of("Custom, on a roll, sized in millimeters",
                        (Supplier<ProjectModel>) ProjectIoTest::customOnARoll),
                Arguments.of("no subject and no area yet",
                        (Supplier<ProjectModel>) ProjectIoTest::nothingDrawnYet),
                Arguments.of("a subject, and a Custom area with no shape yet",
                        (Supplier<ProjectModel>) ProjectIoTest::noCustomShapeYet),
                Arguments.of("Custom, in two parts, grown and fading",
                        (Supplier<ProjectModel>) ProjectIoTest::customGrownAndFading));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("projects")
    @DisplayName("every field round-trips")
    void everyFieldRoundTrips(final String what, final Supplier<ProjectModel> project,
                              @TempDir final Path dir) throws IOException {
        final ProjectModel before = project.get();
        final Path file = dir.resolve("every.wtf");
        ProjectIo.save(before, file);

        final ProjectModel after = new ProjectModel();
        assertTrue(ProjectIo.load(after, file, CATALOG).isEmpty(), "no warnings expected");

        // Every field of the file, which the project opened writes again as it was
        assertEquals(withoutTime(ProjectIo.toFile(before)), withoutTime(ProjectIo.toFile(after)));
        // And the same project, as the model itself tells two apart
        assertTrue(after.sameProject(before.snapshot(), after.snapshot()));
        // The size one setting at a time, since it is the part with two answers
        assertEquals(before.sizeByProperty().get(), after.sizeByProperty().get());
        assertEquals(before.printModeProperty().get(), after.printModeProperty().get());
        assertEquals(before.printWidthMmProperty().get(), after.printWidthMmProperty().get(), 1e-9);
        assertEquals(before.printHeightMmProperty().get(), after.printHeightMmProperty().get(), 1e-9);
        if (before.sizeByProperty().get() == ProjectModel.SizeBy.SHEETS) {
            assertEquals(before.sheetCountProperty().get(), after.sheetCountProperty().get());
            assertEquals(before.countAcrossProperty().get(), after.countAcrossProperty().get());
        }
        if (before.sizeByProperty().get() == ProjectModel.SizeBy.DPI) {
            assertEquals(before.dpiProperty().get(), after.dpiProperty().get());
        }
        if (before.sizeByProperty().get() == ProjectModel.SizeBy.SCALE) {
            assertEquals(before.mapScaleProperty().get(), after.mapScaleProperty().get());
        }
        assertEquals(before.scaleOnPrintProperty().get(), after.scaleOnPrintProperty().get());
        if (before.scaleOnPrintProperty().get()) {
            assertEquals(before.scaleMarkProperty().get(), after.scaleMarkProperty().get());
        }
        assertEquals(before.scaleProperty().get(), after.scaleProperty().get());
    }

    // -------------------------------------------------------------------- the document's example

    private static final Path DOC = Path.of("..", "docs", "design", "project-file.md");

    /** The first json block under "The whole file", as ProjectFileExampleTest reads it. */
    private static ObjectNode documentedExample() throws IOException {
        final String text = Files.readString(DOC);
        final int section = text.indexOf("## The whole file");
        final int start = section < 0 ? -1 : text.indexOf("```json", section);
        final int end = start < 0 ? -1 : text.indexOf("```", start + "```json".length());
        assertTrue(section >= 0 && start > section && end > start, "the example has moved in " + DOC);
        return (ObjectNode) new ObjectMapper().readTree(text.substring(start + "```json".length(), end));
    }

    /**
     * The example, and the two projects the document describes without printing – saved before the
     * subject was picked, and saved with the subject picked and the area still to be drawn – each as
     * the rules say the editor writes it.
     */
    static Stream<Arguments> documentedProjects() {
        return Stream.of(
                Arguments.of("the example", (UnaryOperator<ObjectNode>) root -> root),
                Arguments.of("the example before its subject was picked",
                        (UnaryOperator<ObjectNode>) root -> {
                            // No area, no subject, and one region with no outline to stroke
                            root.remove(List.of("area", "subject"));
                            final ObjectNode regions = (ObjectNode) root.get("regions");
                            regions.set("outside", regions.get("inside").deepCopy());
                            regions.remove("boundaryStroke");
                            return root;
                        }),
                Arguments.of("the example with its area still to be drawn",
                        (UnaryOperator<ObjectNode>) root -> {
                            // A Custom area with no shape yet, so nothing to grow and nothing to fade
                            root.remove("area");
                            ((ObjectNode) root.get("refine")).removeAll();
                            return root;
                        }));
    }

    /**
     * The example in the project-file document is what the editor writes: opened through the editor
     * and saved again, it comes back field for field. Read through {@link WtfFile} alone it could not
     * show a side of the size the mode does not use, which the editor wrote from its model.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("documentedProjects")
    @DisplayName("the editor's round trip of the document's example changes nothing")
    void theEditorsRoundTripOfTheExampleChangesNothing(final String what,
                                                       final UnaryOperator<ObjectNode> variant,
                                                       @TempDir final Path dir) throws IOException {
        final ObjectMapper json = new ObjectMapper();
        final ObjectNode example = variant.apply(documentedExample());
        final Path file = dir.resolve("example.wtf");
        json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), example);

        final ProjectModel m = new ProjectModel();
        assertEquals(List.of(), ProjectIo.load(m, file, CATALOG), "no warnings expected");
        final Path again = dir.resolve("again.wtf");
        ProjectIo.save(m, again);

        // As trees, and but for the moment each was written
        final ObjectNode saved = (ObjectNode) json.readTree(again.toFile());
        example.remove("saved");
        saved.remove("saved");
        assertEquals(example, saved, () -> "the editor writes instead:\n" + saved.toPrettyString());
    }

    /**
     * "Opens it on the first provider it has" means the first one the pickers offer: a provider the
     * person hid is not one to put a mailed project on, although one the file names opens hidden.
     */
    @Test
    @DisplayName("a project on a provider not installed opens on the first one not hidden")
    void anUnknownProviderFallsBackToOneNotHidden(@TempDir final Path dir) throws IOException {
        final Provider first = CATALOG.all().iterator().next();
        final Path providers = dir.resolve("providers.json");
        Files.writeString(providers, "{ \"hidden\": [ \"" + first.id() + "\" ] }");
        final ProviderCatalog hiding = ProviderCatalog.load(providers);
        assertTrue(hiding.isHidden(first.id()));

        final ObjectNode example = documentedExample();
        ((ObjectNode) example.get("source")).put("provider", "not-installed");
        final Path file = dir.resolve("elsewhere.wtf");
        new ObjectMapper().writeValue(file.toFile(), example);

        final ProjectModel m = new ProjectModel();
        final List<String> warnings = ProjectIo.load(m, file, hiding);
        final Provider instead = hiding.visible().getFirst();
        assertEquals(instead.id(), m.providerProperty().get().id(), "not the hidden " + first.id());
        assertEquals(I18n.format("project.warn.provider", "not-installed", instead.displayName()),
                warnings.getFirst());

        // Named by the file, a hidden provider still opens
        ProjectIo.save(filled(), file);
        ProjectIo.load(m, file, hiding);
        assertEquals(first.id(), m.providerProperty().get().id());
    }

    /**
     * The command line knows nothing of sheets. It prints the size the file states, so the file has
     * to state the size the sheets come to – not beside it the mode the size step last showed, which
     * made this one "fill a box" with a width and no height, and no print at all.
     */
    @Test
    @DisplayName("a project sized in sheets is the same print on the command line")
    void theCommandLinePrintsWhatTheEditorShows(@TempDir final Path dir) throws IOException {
        final ProjectModel m = derivedOnSheets();
        assertTrue(m.build().ok(), () -> "not buildable: " + m.build().problem());
        final Path file = dir.resolve("sheets.wtf");
        ProjectIo.save(m, file);

        final WtfFile saved = WtfFile.read(file);
        assertEquals(m.printSize().mode(), saved.print().sizeMode());
        final PrintRect shown = m.build().job().print();
        final PrintRect printed = saved.toJob(CATALOG, dir).print();
        // Three A3 sheets across, turned landscape, sharing two 25 mm seams, with the printer's
        // 5 mm edge kept free at either end
        assertEquals(3 * 420 - 2 * 25 - 2 * 5, printed.widthMm(), 1e-6);
        assertEquals(shown.widthMm(), printed.widthMm(), 1e-6);
        assertEquals(shown.heightMm(), printed.heightMm(), 1e-6);
    }

    /** What a test does to a good file to have it refused. */
    @FunctionalInterface
    private interface Breaking {
        void apply(Path file, WtfFile intact) throws IOException;
    }

    /** Refused by Jackson, by the check for what every project has, and halfway into being read. */
    static Stream<Arguments> refusals() {
        return Stream.of(
                Arguments.of("a size mode misspelled", (Breaking) (file, f) -> Files.writeString(file,
                        Files.readString(file).replace("\"FIT_WIDTH\"", "\"FIT_WIDHT\"")),
                        IOException.class, "sizeMode"),
                Arguments.of("no print block", (Breaking) (file, f) -> new WtfFile(f.format(), f.title(),
                                f.notes(), f.saved(), f.source(), f.area(), f.subject(), f.refine(),
                                f.regions(), null, f.medium(), f.export()).write(file),
                        IllegalArgumentException.class, "\"print\""),
                // Read and checked without a word: it is refused only by the effect it would become,
                // with the provider, the shapes, the medium and the size already written
                Arguments.of("a saturation below nothing", (Breaking) (file, f) -> new WtfFile(f.format(),
                                f.title(), f.notes(), f.saved(), f.source(), f.area(), f.subject(), f.refine(),
                                new WtfFile.RegionsJson(f.regions().zoom(), f.regions().scale(),
                                        new WtfFile.RegionJson(f.regions().inside().drawn(),
                                                new WtfFile.EffectJson(-1.0, 0.0, 0.0)),
                                        f.regions().outside(), f.regions().boundaryStroke()),
                                f.print(), f.medium(), f.export()).write(file),
                        IllegalArgumentException.class, "saturation"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    @DisplayName("a refused file leaves the model untouched")
    void aRefusedFileLeavesTheModelUntouched(final String what, final Breaking breaking,
                                             final Class<? extends Exception> refusal, final String named,
                                             @TempDir final Path dir) throws IOException {
        // A project open, saved, and changed since: something to lose
        final ProjectModel open = filled();
        final Path mine = dir.resolve("mine.wtf");
        ProjectIo.save(open, mine);
        open.titleProperty().set("something I was working on");
        open.history().closeStep();

        final Path file = dir.resolve("broken.wtf");
        ProjectIo.save(filled(), file);
        breaking.apply(file, WtfFile.read(file));

        final ProjectModel.Snapshot before = open.snapshot();
        final int revision = open.revisionProperty().get();
        final Exception ex = assertThrows(refusal, () -> ProjectIo.load(open, file, CATALOG));
        assertTrue(ex.getMessage().contains(named), ex.getMessage());

        assertEquals(revision, open.revisionProperty().get(), "not one write reached it");
        assertTrue(open.sameProject(before, open.snapshot()));
        assertTrue(open.dirtyProperty().get(), "still changed since it was saved");
        assertTrue(open.history().canUndoProperty().get(), "and the change still there to undo");
        assertFalse(open.history().canRedoProperty().get());
        assertEquals(mine, open.fileProperty().get());
    }

    /**
     * A file does not hold everything a project has – no fit beside a Custom shape, no sheet
     * count beside a size in millimeters, no paper beside a roll – and what it does not hold comes
     * from a new project, not from the one that happened to be open. The paper is the exception: the
     * one in use stays, since the window remembers every change to it as the paper last used. The
     * map's language is neither the open one's nor a new project's, the system's: a file that names
     * none means its provider's own, the first Mapy.com lists, as the command line reads it (D40).
     */
    @Test
    @DisplayName("what a file does not say comes from a new project, but the paper stays")
    void whatTheFileDoesNotSayComesFromANewProject(@TempDir final Path dir) throws IOException {
        final ProjectModel written = filled();
        written.mediumKindProperty().set(ProjectModel.MediumKind.ROLL);
        final Path file = dir.resolve("roll.wtf");
        ProjectIo.save(written, file);
        // And no language named in it
        final WtfFile f = WtfFile.read(file);
        new WtfFile(f.format(), f.title(), f.notes(), f.saved(),
                new WtfFile.Source(f.source().provider(), f.source().theme(), null),
                f.area(), f.subject(), f.refine(), f.regions(), f.print(), f.medium(), f.export()).write(file);

        final ProjectModel open = new ProjectModel();
        open.mapLangProperty().set("fr");
        open.simplifyProperty().set(Shapes.Simplify.CONVEX_HULL);
        open.sheetCountProperty().set(7);
        open.paperProperty().set(Medium.paper("A3"));
        open.orientationProperty().set(Medium.Orientation.LANDSCAPE);

        ProjectIo.load(open, file, CATALOG);

        final ProjectModel blank = new ProjectModel();
        assertEquals("cs", open.mapLangProperty().get(),
                "no language of its own: its provider's first, not the open one's or a fresh model's");
        assertEquals(blank.simplifyProperty().get(), open.simplifyProperty().get());
        assertEquals(blank.sheetCountProperty().get(), open.sheetCountProperty().get());
        assertEquals(Medium.paper("A3"), open.paperProperty().get(), "the paper in use stays");
        assertEquals(Medium.Orientation.LANDSCAPE, open.orientationProperty().get());
    }

    /**
     * A file whose map is lettered in a language and that names none opens in its
     * provider's own (D40), the first language its definition lists – the one the command line
     * exports it in. Not in the language that was open, nor in the one last used with the
     * provider, which the window brings back as soon as the provider is written.
     */
    @Test
    @DisplayName("a lettered map without source.lang opens in its provider's first language, as it exports")
    void aFileWithoutALanguageOpensAsTheCommandLineReadsIt(@TempDir final Path dir) throws IOException {
        final Path providersFile = dir.resolve("providers.json");
        Files.writeString(providersFile, """
                { "providers": [ {
                  "id": "lettered",
                  "displayName": "Lettered",
                  "urlTemplate": "http://127.0.0.1:1/{z}/{x}/{y}.png?lang={lang}",
                  "languages": ["de", "en"],
                  "minZoom": 0,
                  "maxZoom": 19,
                  "themes": [ { "id": "t", "displayName": "T" } ],
                  "attribution": "test",
                  "bulkDownload": true
                } ] }
                """);
        final ProviderCatalog catalog = ProviderCatalog.load(providersFile);
        final WtfFile s = WtfFile.sample("lettered", "t", 14, 50, 15, 51, 10, 200);
        final Path file = dir.resolve("lettered.wtf");
        new WtfFile(s.format(), s.title(), s.notes(), s.saved(), new WtfFile.Source("lettered", "t", null),
                s.area(), s.subject(), s.refine(), s.regions(), s.print(), s.medium(), s.export()).write(file);

        final ProjectModel open = new ProjectModel();
        open.mapLangProperty().set("fr");
        // As MapSource does: writing a provider brings back the language last used with it
        open.providerProperty().addListener((o, was, now) -> open.mapLangProperty().set("en"));

        ProjectIo.load(open, file, catalog);

        assertEquals("de", open.mapLangProperty().get(),
                "the provider's first, not the language that was open or the one last used with it");
        final ComposeJob exported = WtfFile.read(file).toJob(catalog, dir);
        final ProjectModel.Built opened = open.build();
        assertTrue(opened.ok(), opened.problem());
        assertEquals(exported.key(10, 550, 350), opened.job().key(10, 550, 350),
                "the tile the command line prints is the one the window prints, from the same folder");
    }

    /** The same file, but for the moment it was written. */
    private static WtfFile withoutTime(final WtfFile f) {
        return new WtfFile(f.format(), f.title(), f.notes(), null, f.source(), f.area(), f.subject(),
                f.refine(), f.regions(), f.print(), f.medium(), f.export());
    }
}
