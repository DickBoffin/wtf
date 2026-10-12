package net.trdlo.wtf.studio.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.trdlo.wtf.compose.ComposeJob;
import net.trdlo.wtf.compose.Medium;
import net.trdlo.wtf.compose.MissingTilePolicy;
import net.trdlo.wtf.compose.PrintSize;
import net.trdlo.wtf.compose.ScaleMark;
import net.trdlo.wtf.compose.SheetLayout;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.compose.TileEffect;
import net.trdlo.wtf.compose.TilePlan;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.studio.Units;
import net.trdlo.wtf.studio.outline.OutlineLibrary;
import net.trdlo.wtf.studio.steps.StartPanel;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The examples the Start page opens: each file is what its recipe here makes, opens as a new
 * project, and comes out as its card says.
 * <p>
 * <b>The recipe is the example.</b> The bundled files are this class's output, as the golden PDFs
 * are {@code GoldenPdfTest}'s: when the format or the working-out changes, the first test fails
 * and leaves what the recipe makes now in {@code target/examples/}, to be looked at and copied
 * over the bundled file. WTF reads its current format and nothing older, so an example is made
 * again, never read the old way.
 * <p>
 * No toolkit and no network: the model works the areas out on the spot, and nothing is fetched.
 */
class ExampleProjectsTest {

    private static final ProviderCatalog CATALOG = ProviderCatalog.load(null);

    /** Where the files are in the sources, for the message that says to replace one. */
    private static final Path BUNDLED = Path.of("src", "main", "resources", "net", "trdlo", "wtf", "studio", "examples");

    // -------------------------------------------------------------------------------------------
    // The recipes
    // -------------------------------------------------------------------------------------------

    /** Each example, as a person would set it up in the five steps. */
    static @NotNull ProjectModel recipe(final @NotNull Example example) throws IOException {
        return switch (example) {
            case HIKE -> hike();
            case POSTER -> poster();
            case WALLPAPER -> wallpaper();
            case CITY -> city();
        };
    }

    /**
     * Zermatt, Schwarzsee, the Hörnli hut and the Matterhorn: a rectangle of no place in particular,
     * at 1:25 000 on A4 from a home printer, with a scale bar. swisstopo's map goes on past the
     * border, so the summit's south face is map too.
     */
    private static @NotNull ProjectModel hike() {
        final ProjectModel m = on("swisstopo", "pixelkarte-farbe");
        custom(m, new LonLatBounds(7.645, 45.966, 7.765, 46.028));
        sheets(m, "A4", Medium.Orientation.PORTRAIT, 15);
        m.sizeByProperty().set(ProjectModel.SizeBy.SCALE);
        m.mapScaleProperty().set(25_000);
        m.zoomProperty().set(15);
        export(m, "zermatt", ComposeJob.OutputGrouping.SINGLE_DOCUMENT);
        m.scaleOnPrintProperty().set(true);
        m.scaleMarkProperty().set(ScaleMark.BAR);
        return m;
    }

    /** Switzerland from the library, its outline drawn and nothing around it, on one A1 sheet. */
    private static @NotNull ProjectModel poster() throws IOException {
        final ProjectModel m = on("swisstopo", "pixelkarte-farbe");
        m.simplifyProperty().set(Shapes.Simplify.NONE);
        m.bufferValueProperty().set(5_000);
        library(m, "ne0:CHE");
        m.outsideDrawnProperty().set(false);
        m.boundaryStrokedProperty().set(true);
        m.boundaryWidthMmProperty().set(1.0);
        sheets(m, "A1", Medium.Orientation.LANDSCAPE, 15);
        m.printModeProperty().set(PrintSize.Mode.FIT_INSIDE);
        m.printWidthMmProperty().set(800);
        m.printHeightMmProperty().set(560);
        m.zoomProperty().set(10);
        export(m, "helvetia", ComposeJob.OutputGrouping.PER_SHEET);
        return m;
    }

    /**
     * From Martigny to past Brig, from the Grand Combin and Monte Rosa to the Rhône: five meters by
     * two and a half on a 914 mm roll, at the zoom whose names are still two millimeters tall.
     */
    private static @NotNull ProjectModel wallpaper() {
        final ProjectModel m = on("swisstopo", "pixelkarte-farbe");
        custom(m, new LonLatBounds(7.05, 45.92, 8.20, 46.33));
        m.mediumKindProperty().set(ProjectModel.MediumKind.ROLL);
        m.rollWidthMmProperty().set(StartPanel.WALLPAPER_ROLL_MM);
        m.stripAxisProperty().set(Medium.Axis.VERTICAL);
        m.overlapMmProperty().set(15);
        m.edgeMmProperty().set(5);
        m.printModeProperty().set(PrintSize.Mode.FILL_AND_CROP);
        m.printWidthMmProperty().set(StartPanel.WALLPAPER_WIDTH_MM);
        m.printHeightMmProperty().set(StartPanel.WALLPAPER_HEIGHT_MM);
        m.zoomProperty().set(15);
        export(m, "valais", ComposeJob.OutputGrouping.PER_SHEET);
        return m;
    }

    /** Paris from the library and a kilometer round its box, three A4 sheets across from a home printer. */
    private static @NotNull ProjectModel city() throws IOException {
        final ProjectModel m = on("ign-fr", "plan-ign");
        m.simplifyProperty().set(Shapes.Simplify.BOUNDING_BOX);
        m.bufferValueProperty().set(1_000);
        library(m, "ne1:FRA-5333");
        sheets(m, "A4", Medium.Orientation.LANDSCAPE, 20);
        m.sizeByProperty().set(ProjectModel.SizeBy.SHEETS);
        m.countAcrossProperty().set(true);
        m.sheetCountProperty().set(3);
        m.zoomProperty().set(15);
        export(m, "paris", ComposeJob.OutputGrouping.SINGLE_DOCUMENT);
        return m;
    }

    /** A new project on a provider's map, in the language the provider's own is (D40). */
    private static @NotNull ProjectModel on(final @NotNull String providerId, final @NotNull String themeId) {
        final ProjectModel m = new ProjectModel();
        final Provider p = CATALOG.require(providerId);
        m.providerProperty().set(p);
        m.themeProperty().set(p.theme(themeId).orElseThrow());
        m.mapLangProperty().set(p.defaultLanguage());
        return m;
    }

    private static void custom(final @NotNull ProjectModel m, final @NotNull LonLatBounds box) {
        m.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
        m.customProperty().set(Shapes.rectangle(box));
        m.customOriginProperty().set("drawn");
    }

    /** A subject from the library, recorded as a pick from it records one. */
    private static void library(final @NotNull ProjectModel m, final @NotNull String id) throws IOException {
        final OutlineLibrary.Entry entry = OutlineLibrary.get().find(id).orElseThrow();
        m.subjectProperty().set(OutlineLibrary.get().geometry(entry));
        m.subjectOriginProperty().set("library:" + entry.id() + ":" + entry.name());
    }

    private static void sheets(final @NotNull ProjectModel m, final @NotNull String paper,
                               final Medium.@NotNull Orientation orientation, final double overlapMm) {
        m.mediumKindProperty().set(ProjectModel.MediumKind.SHEET);
        m.paperProperty().set(Medium.paper(paper));
        m.orientationProperty().set(orientation);
        m.overlapMmProperty().set(overlapMm);
        m.edgeMmProperty().set(5);
    }

    private static void export(final @NotNull ProjectModel m, final @NotNull String baseName,
                               final ComposeJob.@NotNull OutputGrouping grouping) {
        m.formats().clear();
        m.formats().add(ComposeJob.OutputFormat.PDF);
        m.groupingProperty().set(grouping);
        m.baseNameProperty().set(baseName);
        m.creditOnPrintProperty().set(true);
        m.missingTilesProperty().set(MissingTilePolicy.REFUSE);
    }

    // -------------------------------------------------------------------------------------------
    // The files
    // -------------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Example.class)
    @DisplayName("each bundled example is what its recipe makes")
    void eachExampleIsItsRecipe(final Example example) throws IOException {
        final Path made = Path.of("target", "examples", example.fileName());
        ProjectIo.toFile(recipe(example)).write(made);
        final byte[] bundled;
        try {
            bundled = example.read();
        } catch (final FileNotFoundException none) {
            throw new AssertionError(example.fileName() + " is not bundled; the recipe's is "
                    + made.toAbsolutePath() + " – look at it, and copy it to " + BUNDLED.resolve(example.fileName()));
        }
        assertEquals(tree(Files.readAllBytes(made)), tree(bundled), () -> example.fileName()
                + " is not what its recipe makes now; the recipe's is " + made.toAbsolutePath()
                + " – look at it, and copy it over " + BUNDLED.resolve(example.fileName()) + " if the change is meant");
    }

    /** A project file as a tree, without the moment it was written, which nothing reads back. */
    private static @NotNull JsonNode tree(final byte @NotNull [] json) throws IOException {
        final ObjectNode file = (ObjectNode) new ObjectMapper().readTree(json);
        file.remove("saved");
        return file;
    }

    @ParameterizedTest
    @EnumSource(Example.class)
    @DisplayName("an example opens as a new project: no file, nothing unsaved, nothing to undo, its card's name")
    void anExampleOpensAsANewProject(final Example example) throws IOException {
        final ProjectModel m = new ProjectModel();
        m.fileProperty().set(Path.of("the project before.wtf"));
        m.titleProperty().set("The project before");
        m.history().closeStep();

        final List<String> warnings = ProjectIo.openExample(m, example, CATALOG);

        assertEquals(List.of(), warnings, "every example's provider and map ship");
        assertNull(m.fileProperty().get(), "Save asks for a name, and nothing writes over the example");
        assertFalse(m.dirtyProperty().get(), "nothing unsaved: closing an untouched example asks nothing");
        assertFalse(m.history().canUndoProperty().get(), "nothing to undo");
        assertEquals(example.title(), m.titleProperty().get(), "titled as its card");
        final ProjectModel.Built built = m.build();
        assertTrue(built.ok(), built::problem);

        // The first change is unsaved – which is when a recovery copy is due – and undone, clean again
        m.zoomProperty().set(m.zoomProperty().get() - 1);
        m.history().closeStep();
        assertTrue(m.dirtyProperty().get());
        m.history().undo();
        assertFalse(m.dirtyProperty().get());
    }

    @ParameterizedTest
    @EnumSource(Example.class)
    @DisplayName("an example exports as it opens: a provider with no key that allows a whole print, every tile copied")
    void anExampleExportsAsItOpens(final Example example) throws IOException {
        final ProjectModel m = opened(example);
        final Provider p = m.providerProperty().get();
        assertFalse(p.needsKey(), p.id());
        assertTrue(p.bulkDownload(), p.id());
        assertEquals(MissingTilePolicy.REFUSE, m.missingTilesProperty().get());
        assertNull(m.outputDirProperty().get(), "exported where the person exports, not where it was made");
        assertTrue(m.creditOnPrintProperty().get(), "the provider is credited");
        assertEquals(Set.of(ComposeJob.OutputFormat.PDF), Set.copyOf(m.formats()));
        // A treatment that changes the colors would decode every JPEG and full-color PNG tile
        assertEquals(TileEffect.NONE, m.insideEffectProperty().get());
        assertEquals(TileEffect.NONE, m.outsideTreatment());

        // What is drawn is where the provider has a map
        final LonLatBounds drawn = Shapes.boundsOf(m.outsideDrawnProperty().get()
                ? m.areaProperty().get() : m.subjectProperty().get());
        final LonLatBounds covered = p.coverage();
        assertNotNull(covered, p.id());
        assertTrue(covered.contains(drawn.west(), drawn.south()) && covered.contains(drawn.east(), drawn.north()),
                () -> drawn + " is not inside " + p.id() + "'s " + covered);

        // No name comes out unreadable, and each example asks for a download one can wait for
        final SheetLayout layout = m.layout();
        assertNotNull(layout);
        final int zoom = m.zoomProperty().get();
        assertTrue(layout.print().labelHeightMm(zoom) >= 1.5, () -> "names " + layout.print().labelHeightMm(zoom));
        final int tiles = TilePlan.of(m.build().job()).distinctKeys().size();
        assertTrue(tiles < 8_000, () -> example + " asks for " + tiles + " tiles");
    }

    @Test
    @DisplayName("the hike: no subject, a Custom rectangle, 1:25 000 on two A4 sheets, a scale bar")
    void theHike() throws IOException {
        final ProjectModel m = opened(Example.HIKE);
        assertNull(m.subjectProperty().get());
        assertEquals(ProjectModel.AreaSource.CUSTOM, m.areaSourceProperty().get());
        assertTrue(Shapes.isRectangle(m.customProperty().get()));
        assertEquals(ProjectModel.SizeBy.SCALE, m.sizeByProperty().get());
        assertEquals(25_000, m.mapScaleProperty().get());
        assertSheets(m, "A4", 2, 1);
        assertTrue(m.scaleOnPrintProperty().get());
        assertEquals(ScaleMark.BAR, m.scaleMarkProperty().get());
        assertEquals(ComposeJob.OutputGrouping.SINGLE_DOCUMENT, m.groupingProperty().get(), "one file for a home printer");
    }

    @Test
    @DisplayName("the poster: Switzerland from the library, alone on white, its outline drawn, on one A1 sheet")
    void thePoster() throws IOException {
        final ProjectModel m = opened(Example.POSTER);
        assertEquals("library:ne0:CHE:Switzerland", m.subjectOriginProperty().get());
        assertEquals(ProjectModel.AreaSource.FROM_SUBJECT, m.areaSourceProperty().get());
        assertEquals(Shapes.Simplify.NONE, m.simplifyProperty().get(), "the outline, so the spare paper stays white");
        assertTrue(m.insideDrawnProperty().get());
        assertFalse(m.outsideDrawnProperty().get(), "alone on white");
        assertTrue(m.boundaryStrokedProperty().get(), "its border drawn");
        assertSheets(m, "A1", 1, 1);
        assertEquals(Medium.Orientation.LANDSCAPE, m.orientationProperty().get());
    }

    @Test
    @DisplayName("the wallpaper: five meters by two and a half, exactly, on six strips of a 914 mm roll, names of 2 mm")
    void theWallpaper() throws IOException {
        final ProjectModel m = opened(Example.WALLPAPER);
        assertEquals(ProjectModel.MediumKind.ROLL, m.mediumKindProperty().get());
        assertEquals(StartPanel.WALLPAPER_ROLL_MM, m.rollWidthMmProperty().get(), "as the card says it");
        assertEquals(Medium.Axis.VERTICAL, m.stripAxisProperty().get());
        assertEquals(ProjectModel.SizeBy.MILLIMETERS, m.sizeByProperty().get());
        assertEquals(PrintSize.Mode.FILL_AND_CROP, m.printModeProperty().get(), "fill both exactly");
        final var asked = m.printRect();
        assertNotNull(asked);
        assertEquals(StartPanel.WALLPAPER_WIDTH_MM, asked.widthMm(), 0.01, "as the card says it");
        assertEquals(StartPanel.WALLPAPER_HEIGHT_MM, asked.heightMm(), 0.01, "as the card says it");
        final SheetLayout layout = m.layout();
        assertNotNull(layout);
        assertEquals(6, layout.count(), "six strips");
        assertEquals(6, layout.columns());
        assertTrue(layout.print().labelHeightMm(m.zoomProperty().get()) >= 2.0, "names of 2 mm or more");
    }

    @Test
    @DisplayName("the city: Paris from the library, three A4 sheets across, nine in all")
    void theCity() throws IOException {
        final ProjectModel m = opened(Example.CITY);
        assertEquals("ign-fr", m.providerProperty().get().id());
        assertEquals("library:ne1:FRA-5333:Paris", m.subjectOriginProperty().get());
        assertEquals(ProjectModel.AreaSource.FROM_SUBJECT, m.areaSourceProperty().get());
        assertEquals(Shapes.Simplify.BOUNDING_BOX, m.simplifyProperty().get());
        assertEquals(ProjectModel.SizeBy.SHEETS, m.sizeByProperty().get(), "sheets by count");
        assertTrue(m.countAcrossProperty().get());
        assertEquals(3, m.sheetCountProperty().get());
        assertSheets(m, "A4", 3, 3);
        assertTrue(m.insideDrawnProperty().get() && m.outsideDrawnProperty().get(), "the whole map");
        assertEquals(ComposeJob.OutputGrouping.SINGLE_DOCUMENT, m.groupingProperty().get(), "one file for a home printer");
        // The sheet numbers go where the next sheet covers them: room for them in each overlap
        final double strip = m.overlapMmProperty().get() - 2 * m.edgeMmProperty().get()
                - 2 * net.trdlo.wtf.compose.SeamMarks.GAP_MM;
        assertTrue(strip >= net.trdlo.wtf.compose.SeamMarks.TEXT_STRIP_MM, () -> "a strip of " + strip + " mm");
    }

    @Test
    @DisplayName("the wallpaper's card says its lengths as lengths on paper are chosen, each on one line")
    void theWallpapersCardFollowsTheUnits() {
        final Units was = Units.current();
        try {
            Units.use("mm");
            assertEquals(I18n.format("example.wallpaper.body", "5.0 m", "2.5 m", "914 mm"),
                    StartPanel.body(Example.WALLPAPER));
            Units.use("inch");
            assertEquals(I18n.format("example.wallpaper.body", "16.4 ft", "8.2 ft", "36 in"),
                    StartPanel.body(Example.WALLPAPER));
        } finally {
            Units.use(was.key());
        }
    }

    // -------------------------------------------------------------------------------------------

    private static @NotNull ProjectModel opened(final @NotNull Example example) throws IOException {
        final ProjectModel m = new ProjectModel();
        ProjectIo.openExample(m, example, CATALOG);
        return m;
    }

    private static void assertSheets(final @NotNull ProjectModel m, final @NotNull String paper,
                                     final int across, final int down) {
        assertEquals(ProjectModel.MediumKind.SHEET, m.mediumKindProperty().get());
        assertEquals(Medium.paper(paper), m.paperProperty().get());
        final SheetLayout layout = m.layout();
        assertNotNull(layout);
        assertEquals(across, layout.columns(), "across");
        assertEquals(down, layout.rows(), "down");
        assertEquals(across * down, layout.count());
    }
}
