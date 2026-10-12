package net.trdlo.wtf.studio.steps;

import net.trdlo.wtf.compose.PrintSize;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.studio.Units;
import net.trdlo.wtf.studio.project.ProjectIo;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A length field writes what is typed into it and nothing it is shown – under inches, where what
 * it is shown is rounded. The two fields of the medium step, wired to the model as the step wires
 * them, without the toolkit.
 */
class LengthTextTest {

    private static final ProviderCatalog CATALOG = ProviderCatalog.load(null);

    private final Units before = Units.current();

    @BeforeEach
    void inches() {
        Units.use("inch");
    }

    @AfterEach
    void back() {
        Units.use(before.key());
    }

    /** A project 210 mm wide whose height follows. */
    private static ProjectModel a4Wide() {
        final ProjectModel m = new ProjectModel();
        final Provider p = CATALOG.all().iterator().next();
        m.providerProperty().set(p);
        m.themeProperty().set(p.themes().getFirst());
        m.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
        m.customProperty().set(Shapes.rectangle(new LonLatBounds(12.0, 48.5, 18.9, 51.1)));
        m.printModeProperty().set(PrintSize.Mode.FIT_WIDTH);
        m.printWidthMmProperty().set(210);
        return m;
    }

    /** The medium step's width and height, as it builds them. */
    private record Sides(LengthText width, LengthText height) {
        static Sides of(final ProjectModel m) {
            final Units u = Units.current();
            final Sides s = new Sides(new LengthText(m.printWidthMmProperty(), u, 0),
                    new LengthText(m.printHeightMmProperty(), u, 0));
            MediumPanel.showTheOtherSide(m, s.width, s.height);
            m.revisionProperty().addListener((o, a, b) -> MediumPanel.showTheOtherSide(m, s.width, s.height));
            m.printModeProperty().addListener((o, was, is) -> MediumPanel.takeTheOtherSide(was, is, s.width, s.height));
            return s;
        }
    }

    @Test
    @DisplayName("under inches, a project opened and saved again keeps 210 mm, and the height shown is not written")
    void openedAndSavedAgain(@TempDir final Path dir) throws IOException {
        final Path first = dir.resolve("first.wtf");
        ProjectIo.save(a4Wide(), first);

        final ProjectModel m = new ProjectModel();
        // Another width before, so opening the file changes what the field shows
        m.printWidthMmProperty().set(500);
        final Sides sides = Sides.of(m);
        final double heightBefore = m.printHeightMmProperty().get();
        assertTrue(ProjectIo.load(m, first, CATALOG).isEmpty());

        assertEquals("8.27", sides.width.text().get());
        assertEquals(210.0, m.printWidthMmProperty().get(), "not 210.058, read back from 8.27 in");
        final double shownHeight = m.printRect().heightMm();
        assertEquals(Units.INCHES.number(shownHeight, 0), sides.height.text().get(),
                "the height the width comes to, for information");
        assertEquals(heightBefore, m.printHeightMmProperty().get(), "and not written");
        assertFalse(m.history().dirtyProperty().get());

        final Path second = dir.resolve("second.wtf");
        ProjectIo.save(m, second);
        assertEquals(withoutTime(first), withoutTime(second));
    }

    /** The file, but for the moment it was saved. */
    private static String withoutTime(final Path file) throws IOException {
        return Files.readString(file).replaceFirst("\"saved\" : \"[^\"]*\"", "");
    }

    @Test
    @DisplayName("8.27 typed in inches is 210.058 mm, and leaving the field does not round it")
    void typedIsWritten() {
        final ProjectModel m = a4Wide();
        final Sides sides = Sides.of(m);
        sides.width.focused().set(true);
        sides.width.text().set("8.2");
        sides.width.text().set("8.27");
        assertEquals(210.058, m.printWidthMmProperty().get(), 1e-9);
        sides.width.focused().set(false);
        assertEquals("8.27", sides.width.text().get());
        assertEquals(210.058, m.printWidthMmProperty().get(), 1e-9);
    }

    @Test
    @DisplayName("fixing the height it showed leaves the same print, and undoing that brings the old height back")
    void switchingWhichSideIsFixed() {
        final ProjectModel m = a4Wide();
        final Sides sides = Sides.of(m);
        final double heightBefore = m.printHeightMmProperty().get();
        m.history().closeStep();
        final double shownHeight = m.printRect().heightMm();

        m.printModeProperty().set(PrintSize.Mode.FIT_HEIGHT);
        m.history().closeStep();
        assertEquals(shownHeight, m.printHeightMmProperty().get(), "the length, not the rounded text");
        assertEquals(210.0, m.printRect().widthMm(), 1e-6);
        assertEquals("8.27", sides.width.text().get());

        m.history().undo();
        assertEquals(PrintSize.Mode.FIT_WIDTH, m.printModeProperty().get());
        assertEquals(210.0, m.printWidthMmProperty().get());
        assertEquals(heightBefore, m.printHeightMmProperty().get());
    }
}
