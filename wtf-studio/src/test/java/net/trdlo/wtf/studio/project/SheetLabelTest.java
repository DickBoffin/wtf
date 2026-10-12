package net.trdlo.wtf.studio.project;

import net.trdlo.wtf.compose.ComposeJob;
import net.trdlo.wtf.compose.Marks;
import net.trdlo.wtf.compose.Medium;
import net.trdlo.wtf.compose.SeamMarks;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.compose.SheetLayout;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The label in each sheet's hidden strip, as the studio has it printed: in the interface language,
 * which the engine has none of, and calling the print by the name step 5 gives it – the name its
 * files have – never by the project's title, which nothing in the window shows.
 * <p>
 * The test JVM runs in English; a test about Czech chooses it, and everything is put back after.
 */
class SheetLabelTest {

    private static final ProviderCatalog CATALOG = ProviderCatalog.load(null);
    private static final Locale CZECH = Locale.forLanguageTag("cs-CZ");
    private static final LonLatBounds BOHEMIA = new LonLatBounds(12.0, 48.5, 18.9, 51.1);

    /** The day every label here says. */
    private static final String DAY = "2026-10-10";

    private Locale language;
    private @Nullable String dayBefore;

    @BeforeEach
    void theDayIsGiven() {
        language = I18n.locale();
        dayBefore = System.getProperty(Marks.DATE_PROPERTY);
        System.setProperty(Marks.DATE_PROPERTY, DAY);
    }

    @AfterEach
    void andTakenBack() {
        I18n.setLocale(language);
        if (dayBefore == null) {
            System.clearProperty(Marks.DATE_PROPERTY);
        } else {
            System.setProperty(Marks.DATE_PROPERTY, dayBefore);
        }
    }

    /** Bohemia on two A4 sheets side by side, with room for text where they overlap, the marks on. */
    private static @NotNull ProjectModel twoSheets() {
        final ProjectModel m = new ProjectModel();
        final Provider osm = CATALOG.require("osm");
        m.providerProperty().set(osm);
        m.themeProperty().set(osm.themes().getFirst());
        m.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
        m.customProperty().set(Shapes.rectangle(BOHEMIA));
        m.paperProperty().set(Medium.paper("A4"));
        m.orientationProperty().set(Medium.Orientation.PORTRAIT);
        m.overlapMmProperty().set(20);
        m.sizeByProperty().set(ProjectModel.SizeBy.SHEETS);
        m.countAcrossProperty().set(true);
        m.sheetCountProperty().set(2);
        m.setPrinting(new ProjectModel.Printing(true));
        return m;
    }

    private static @NotNull ComposeJob job(final @NotNull ProjectModel m) {
        final ProjectModel.Built built = m.build();
        assertTrue(built.ok(), built::problem);
        return Objects.requireNonNull(built.job());
    }

    /** What a sheet's label says, as the export would print it; null on a sheet with none. */
    private static @Nullable String labelOn(final @NotNull ComposeJob job, final SheetLayout.@NotNull Sheet sheet) {
        return SeamMarks.forSheet(job, job.layout(), sheet).stream()
                .filter(mark -> mark instanceof SeamMarks.Text t && t.style() == SeamMarks.Style.FAINT
                        && !t.text().matches("\\d+"))       // an arrow's number
                .map(mark -> ((SeamMarks.Text) mark).text())
                .findFirst().orElse(null);
    }

    /** What the first sheet's label says. */
    private static @NotNull String label(final @NotNull ProjectModel m) {
        final ComposeJob job = job(m);
        final String said = labelOn(job, job.layout().sheets().getFirst());
        assertNotNull(said, "a label on the first sheet");
        return said;
    }

    // -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a job built in Czech has its label in Czech, the numbers filled in plain")
    void aJobBuiltInCzechHasACzechLabel() {
        I18n.setLocale(CZECH);
        final ProjectModel m = twoSheets();
        m.baseNameProperty().set("Čelákovicko");
        final ComposeJob job = job(m);
        assertEquals(2, job.layout().count(), "two sheets, side by side");

        // The interface language may change after; the job keeps the words it was built with
        I18n.setLocale(Locale.ENGLISH);
        assertEquals("List 1 z 2 · sloupec 1, řádek 1 · Čelákovicko · " + DAY, label(m));
    }

    @Test
    @DisplayName("in English the studio's words are the engine's own, which the command line prints")
    void inEnglishTheWordsAreTheEngines() {
        I18n.setLocale(Locale.ENGLISH);
        final ProjectModel m = twoSheets();
        m.baseNameProperty().set("Wall");
        assertEquals(Marks.SHEET_LINE, job(m).marks().sheetLine());
        assertEquals("Sheet 1 of 2 · column 1, row 1 · Wall · " + DAY, label(m));
    }

    @Test
    @DisplayName("the label names the print as step 5 does, its files' name, and never the project's title")
    void theLabelNamesTheExportNotTheTitle() {
        final ProjectModel m = twoSheets();
        m.titleProperty().set("Nothing in the window says this");
        m.baseNameProperty().set("Čelákovicko");
        final ComposeJob job = job(m);
        assertEquals("Čelákovicko", job.marks().name());
        assertEquals(job.baseName(), job.marks().name(), "the name the files are written under");
        assertFalse(label(m).contains("Nothing in the window"), label(m));
    }

    @Test
    @DisplayName("with step 5's name left blank, the label names the print as its files are then named")
    void aBlankNameIsTheFilesFallback() {
        I18n.setLocale(CZECH);
        final ProjectModel m = twoSheets();
        m.titleProperty().set("Nothing in the window says this");
        m.baseNameProperty().set("  ");
        final ComposeJob job = job(m);
        assertEquals(I18n.get("project.baseName"), job.baseName(), "the files fall back to the word");
        assertEquals(job.baseName(), job.marks().name(), "and the label says it too");
        assertEquals("List 1 z 2 · sloupec 1, řádek 1 · tisk · " + DAY, label(m));
    }

    /**
     * As it was reported: the wallpaper opened from the Start page in Czech, a rectangle drawn as
     * its subject and the print named in step 5 – and every sheet's label said "Sheet 1 of …" in
     * English and named the example's card.
     */
    @Test
    @DisplayName("an example opened and changed names the print as step 5 does, not as its card does")
    void anExampleOpenedAndChanged() throws IOException {
        I18n.setLocale(CZECH);
        final ProjectModel m = new ProjectModel();
        ProjectIo.openExample(m, Example.WALLPAPER, CATALOG);
        m.setPrinting(new ProjectModel.Printing(true));
        final String card = Example.WALLPAPER.title();
        assertEquals("Tapeta do obýváku", card);
        assertEquals(card, m.titleProperty().get(), "titled as its card, which is what Save proposes");

        final String opened = label(m);
        assertEquals("List 1 z 6 · sloupec 1, řádek 1 · valais · " + DAY, opened,
                "the name its files have as it opens");

        m.subjectProperty().set(Shapes.rectangle(new LonLatBounds(7.55, 46.00, 7.85, 46.20)));
        m.subjectOriginProperty().set("drawn");
        m.baseNameProperty().set("Čelákovicko");
        final ComposeJob job = job(m);
        assertNotNull(job.subject(), "the rectangle is the subject");
        for (final SheetLayout.Sheet sheet : job.layout().sheets()) {
            final String said = labelOn(job, sheet);
            if (sheet.column() == job.layout().columns() - 1) {
                assertNull(said, "nothing covers the last strip, so it has no label");
                continue;
            }
            assertEquals("List " + sheet.index() + " z 6 · sloupec " + (sheet.column() + 1) + ", řádek 1 · Čelákovicko · "
                    + DAY, said);
        }
        assertEquals(card, m.titleProperty().get(), "the title stays the card's, for Save");
    }
}
