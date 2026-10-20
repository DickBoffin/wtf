package net.trdlo.wtf.studio.steps;

import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.stage.Stage;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.compose.WtfFile;
import net.trdlo.wtf.studio.Headless;
import net.trdlo.wtf.studio.Units;
import net.trdlo.wtf.studio.project.ProjectIo;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.function.Consumer;

import static net.trdlo.wtf.studio.Headless.find;
import static net.trdlo.wtf.studio.Headless.onFx;
import static org.junit.jupiter.api.Assertions.*;

/**
 * In the step the window builds: the roll width the medium step shows is the model's, an undone
 * one too, and building the step writes nothing into the project – its roll-width combo follows
 * the model rather than being filled once, and a roll's count axis is not set from its strips as
 * the step is built, so a project opened counted the other way comes up saved.
 * <p>
 * On JavaFX's headless platform ({@link Headless}), in millimeters, with a preference node of the
 * test's own for the printer's edge the step shows. The step asks its context for nothing else, so
 * the stage and the map are left out, as {@code StepContextCatalogTest} leaves them.
 */
class MediumPanelTest {

    private static final LonLatBounds BISCAY = new LonLatBounds(-10, 46, 4, 50);

    @BeforeAll
    static void toolkit() {
        Headless.start();
    }

    /** A roll project as one opened from a file is: nothing unsaved and nothing to undo. */
    private static ProjectModel roll(final Consumer<ProjectModel> more) {
        final ProjectModel m = new ProjectModel();
        final Provider nowhere = Headless.nowhere();
        m.startOver(() -> {
            m.providerProperty().set(nowhere);
            m.themeProperty().set(nowhere.themes().getFirst());
            m.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
            m.customProperty().set(Shapes.rectangle(BISCAY));
            more.accept(m);
            m.mediumKindProperty().set(ProjectModel.MediumKind.ROLL);
        });
        return m;
    }

    /** The step built and shown as the window hosts it, in a scroll pane. */
    private static Stage show(final MediumPanel panel) {
        return Headless.show(new ScrollPane(panel.node()), 360, 800);
    }

    /** The roll-width combo: the step's one editable combo, the one that lists the rolls. */
    @SuppressWarnings("unchecked")
    private static ComboBox<Double> rollWidth(final Node step) {
        return (ComboBox<Double>) find(step, ComboBox.class,
                c -> c.isEditable() && c.getItems().contains(914.0)).orElseThrow();
    }

    private static void close(final Stage stage) throws Exception {
        if (stage != null) {
            onFx(() -> {
                stage.close();
                return null;
            });
        }
    }

    @Test
    @DisplayName("a roll width undone is the width the step shows")
    void anUndoneWidthIsShown() throws Exception {
        final Units units = Units.current();
        Stage stage = null;
        try {
            Units.use("mm");
            final ProjectModel m = roll(p -> { });
            final MediumPanel panel = new MediumPanel(m);
            stage = onFx(() -> show(panel));
            final ComboBox<Double> width = onFx(() -> rollWidth(panel.node()));
            assertEquals(914, onFx(() -> m.rollWidthMmProperty().get()), 0.0, "a new project's roll");
            assertEquals(RollWidthText.text(Units.current(), 914), onFx(() -> width.getEditor().getText()));

            // Picked from the list, and the step closed, as the window's next press closes it
            onFx(() -> {
                width.getSelectionModel().select(Double.valueOf(610));
                m.history().closeStep();
                return null;
            });
            assertEquals(610, onFx(() -> m.rollWidthMmProperty().get()), 0.0);
            assertEquals(RollWidthText.text(Units.current(), 610), onFx(() -> width.getEditor().getText()));

            onFx(() -> {
                m.history().undo();
                return null;
            });
            assertEquals(914, onFx(() -> m.rollWidthMmProperty().get()), 0.0, "the project is back at 914 mm ...");
            assertEquals(914, onFx(width::getValue), 0.0, "... and so is the combo");
            assertEquals(RollWidthText.text(Units.current(), 914), onFx(() -> width.getEditor().getText()),
                    "the width shown is the one undone to, not the one picked");
        } finally {
            close(stage);
            Units.use(units.key());
        }
    }

    @Test
    @DisplayName("a DPI typed into the step is the project's, and the step says what it comes to")
    @SuppressWarnings("unchecked")
    void aTypedDpiIsTheProjects() throws Exception {
        Stage stage = null;
        try {
            final ProjectModel m = roll(p -> p.sizeByProperty().set(ProjectModel.SizeBy.DPI));
            final MediumPanel panel = new MediumPanel(m);
            stage = onFx(() -> show(panel));
            final ComboBox<Integer> dpi = onFx(() -> (ComboBox<Integer>) find(panel.node(), ComboBox.class,
                    c -> c.isEditable() && c.getItems().contains(150)).orElseThrow());
            assertTrue(onFx(dpi::isVisible), "shown while the print is sized at a DPI");

            onFx(() -> {
                dpi.getEditor().setText("152");
                dpi.commitValue();
                m.history().closeStep();
                return null;
            });
            assertEquals(152, onFx(() -> m.dpiProperty().get()));
            assertTrue(onFx(() -> find(panel.node(), javafx.scene.control.Label.class,
                    l -> l.getText() != null && l.getText().contains("152 DPI")).isPresent()),
                    "the step says what 152 DPI comes to");

            onFx(() -> {
                dpi.getEditor().setText("junk");
                dpi.commitValue();
                return null;
            });
            assertEquals(152, onFx(() -> m.dpiProperty().get()), "what is not a DPI changes nothing");
        } finally {
            close(stage);
        }
    }

    @Test
    @DisplayName("a scale typed into the step is the project's, shown as a ratio, and the step says what it comes to")
    @SuppressWarnings("unchecked")
    void aTypedScaleIsTheProjects() throws Exception {
        Stage stage = null;
        try {
            final ProjectModel m = roll(p -> p.sizeByProperty().set(ProjectModel.SizeBy.SCALE));
            final MediumPanel panel = new MediumPanel(m);
            stage = onFx(() -> show(panel));
            final ComboBox<Integer> scale = onFx(() -> (ComboBox<Integer>) find(panel.node(), ComboBox.class,
                    c -> c.isEditable() && c.getItems().contains(50_000)).orElseThrow());
            assertTrue(onFx(scale::isVisible), "shown while the print is sized at a scale");

            onFx(() -> {
                scale.getEditor().setText("2:75000");
                scale.commitValue();
                m.history().closeStep();
                return null;
            });
            assertEquals(37_500, onFx(() -> m.mapScaleProperty().get()));
            assertEquals(37_500, onFx(() -> m.printRect().mapScale()), 1e-6);
            assertEquals("1:37,500", onFx(() -> scale.getEditor().getText()), "rewritten as the ratio");
            assertTrue(onFx(() -> find(panel.node(), javafx.scene.control.Label.class,
                    l -> l.getText() != null && l.getText().startsWith("At 1:37,500 the print is")).isPresent()),
                    "the step says what 1:37 500 comes to");

            onFx(() -> {
                scale.getEditor().setText("1e5");
                scale.commitValue();
                return null;
            });
            assertEquals(37_500, onFx(() -> m.mapScaleProperty().get()), "what is not a scale changes nothing");
        } finally {
            close(stage);
        }
    }

    @Test
    @DisplayName("building the step writes nothing – a roll counted down stays so, and saved")
    void buildingTheStepWritesNothing() throws Exception {
        final Units units = Units.current();
        Stage stage = null;
        try {
            Units.use("mm");
            // Sheets counted down, then a roll: a file of such a project opens so
            final ProjectModel m = roll(p -> {
                p.sizeByProperty().set(ProjectModel.SizeBy.SHEETS);
                p.sheetCountProperty().set(3);
                p.countAcrossProperty().set(false);
            });
            final MediumPanel panel = new MediumPanel(m);
            stage = onFx(() -> show(panel));
            onFx(() -> {
                m.history().closeStep();        // what the window's next press does
                return null;
            });
            assertFalse(onFx(() -> m.dirtyProperty().get()), "nothing to save after the step was only looked at");
            assertFalse(onFx(() -> m.countAcrossProperty().get()), "the axis chosen for sheets is not written over");
            assertTrue(onFx(m::countsAcross), "while the roll is counted in its strips, across");
        } finally {
            close(stage);
            Units.use(units.key());
        }
    }

    @Test
    @DisplayName("a file of 120 sheets across opens with 120, and clean, into a window showing the step")
    @SuppressWarnings("unchecked")
    void aFileOfManySheetsOpensWithItsCount(@TempDir final Path dir) throws Exception {
        final ProviderCatalog catalog = ProviderCatalog.load(null);
        final Provider provider = catalog.all().iterator().next();
        final ProjectModel written = new ProjectModel();
        written.providerProperty().set(provider);
        written.themeProperty().set(provider.themes().getFirst());
        written.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
        written.customProperty().set(Shapes.rectangle(BISCAY));
        written.sizeByProperty().set(ProjectModel.SizeBy.SHEETS);
        written.countAcrossProperty().set(true);
        written.sheetCountProperty().set(120);
        final Path file = dir.resolve("long.wtf");
        ProjectIo.save(written, file);
        assertEquals(120, WtfFile.read(file).medium().sheetsAcross());

        Stage stage = null;
        try {
            final ProjectModel m = new ProjectModel();
            final MediumPanel panel = new MediumPanel(m);
            stage = onFx(() -> show(panel));
            onFx(() -> {
                ProjectIo.load(m, file, catalog);
                m.history().closeStep();        // what the window's next press does
                return null;
            });
            assertEquals(120, onFx(() -> m.sheetCountProperty().get()), "the file's count, not the spinner's");
            assertFalse(onFx(() -> m.dirtyProperty().get()), "nothing to save after only opening it");
            final Spinner<Integer> count = onFx(() -> (Spinner<Integer>) find(panel.node(), Spinner.class,
                    s -> true).orElseThrow());
            assertEquals(120, onFx(count::getValue), "and the spinner shows it");
        } finally {
            close(stage);
        }
    }

    @Test
    @DisplayName("the sheet count takes a number typed into it, in the display locale")
    @SuppressWarnings("unchecked")
    void sheetCountIsTyped() throws Exception {
        final java.util.Locale was = net.trdlo.wtf.studio.I18n.locale();
        net.trdlo.wtf.studio.I18n.setLocale(java.util.Locale.forLanguageTag("cs-CZ"));
        final ProjectModel m = new ProjectModel();
        final Provider nowhere = Headless.nowhere();
        m.startOver(() -> {
            m.providerProperty().set(nowhere);
            m.themeProperty().set(nowhere.themes().getFirst());
            m.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
            m.customProperty().set(Shapes.rectangle(BISCAY));
            m.mediumKindProperty().set(ProjectModel.MediumKind.SHEET);
            m.sizeByProperty().set(ProjectModel.SizeBy.SHEETS);
            m.sheetCountProperty().set(2);
        });
        Stage stage = null;
        try {
            final MediumPanel panel = new MediumPanel(m);
            stage = onFx(() -> show(panel));
            final Spinner<Integer> count = onFx(() -> (Spinner<Integer>) find(panel.node(), Spinner.class,
                    s -> true).orElseThrow());
            assertTrue(onFx(count::isEditable), "a spinner nobody can type into");
            onFx(() -> {
                count.getEditor().setText("1 200");
                count.getEditor().fireEvent(new javafx.event.ActionEvent());   // Enter
                return null;
            });
            assertEquals(1200, onFx(() -> m.sheetCountProperty().get()), "the typed count is the project's");
            onFx(() -> {
                count.getEditor().setText("tři");
                count.getEditor().fireEvent(new javafx.event.ActionEvent());   // Enter
                return null;
            });
            assertEquals(1200, onFx(() -> m.sheetCountProperty().get()), "and a word changes nothing");
            assertEquals("1 200", onFx(() -> count.getEditor().getText()), "shown again as it was");
        } finally {
            close(stage);
            net.trdlo.wtf.studio.I18n.setLocale(was);
        }
    }
}
