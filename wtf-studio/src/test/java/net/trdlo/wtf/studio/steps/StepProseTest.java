package net.trdlo.wtf.studio.steps;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.stage.Stage;
import net.trdlo.wtf.compose.ComposeJob;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.studio.Forms;
import net.trdlo.wtf.studio.Headless;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;

import java.util.EnumSet;
import java.util.Set;

import static net.trdlo.wtf.studio.Headless.find;
import static net.trdlo.wtf.studio.Headless.onFx;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What the steps say in words that are worked out rather than written: the "?" beside a control,
 * the line under a shape, where the paper whole sheets leave over goes, the zoom step's intro for
 * a print sized at a DPI, and the formats the export's rail line names.
 */
class StepProseTest {

    private static final LonLatBounds BOHEMIA = new LonLatBounds(12, 48.5, 18, 51);

    @BeforeAll
    static void toolkit() {
        Headless.start();
    }

    /** A rectangle drawn as the Custom area, as one opened from a file is. */
    private static ProjectModel project() {
        final ProjectModel m = new ProjectModel();
        final Provider nowhere = Headless.nowhere();
        m.startOver(() -> {
            m.providerProperty().set(nowhere);
            m.themeProperty().set(nowhere.themes().getFirst());
            m.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
            m.customProperty().set(Shapes.rectangle(BOHEMIA));
            m.customOriginProperty().set("drawn");
            m.zoomProperty().set(8);
        });
        return m;
    }

    @Test
    @DisplayName("the help mark carries its text in a tooltip that wraps and stays")
    void theHelpMark() throws Exception {
        final Button help = onFx(() -> Forms.help("area.grow.help"));
        assertEquals(I18n.get("area.grow.help"), help.getTooltip().getText());
        assertTrue(help.getTooltip().isWrapText());
        assertEquals(javafx.util.Duration.ZERO, help.getTooltip().getShowDelay());
        assertEquals(javafx.util.Duration.INDEFINITE, help.getTooltip().getShowDuration());
        assertTrue(help.isFocusTraversable(), "a keyboard reaches it");
        assertTrue(help.getStyleClass().contains("help-icon"));
    }

    @Test
    @DisplayName("a shape is said by where it came from and its size on the ground, its corners in the tooltip")
    void aShapeInWords() {
        final Geometry one = Shapes.rectangle(BOHEMIA);
        final String line = ShapeSlot.describe(one, "drawn");
        assertEquals(I18n.format("shape.summary", I18n.get("area.origin.drawn"), AreaPanel.ground(BOHEMIA)), line,
                "one part is not counted");
        assertEquals(I18n.format("area.slot.bounds", I18n.number(12, 3), I18n.number(48.5, 3),
                I18n.number(18, 3), I18n.number(51, 3)), ShapeSlot.bounds(one));

        final Geometry two = Shapes.withPart(one, Shapes.rectangle(new LonLatBounds(20, 48, 21, 49)),
                Shapes.Role.SUBJECT);
        assertTrue(ShapeSlot.describe(two, "drawn + 2").endsWith(I18n.format("shape.parts", 2)),
                "two parts are counted once, by the line and not by the origin");
        assertEquals(I18n.format("shape.summary", I18n.get("area.origin.drawn"), I18n.format("shape.parts", 2)),
                ShapeSlot.summary(two, "drawn + 2"));
        assertEquals("Nowhere", ShapeSlot.summary(one, "library:xx:NOPE:Nowhere"),
                "the rail names a library entry by its name alone");
        assertEquals("a + b.geojson", ShapeSlot.summary(one, "a + b.geojson"), "only a number is a count");
    }

    @Test
    @DisplayName("the paper whole sheets leave over is more map round a rectangle, and white when printed exactly")
    void whereTheSparePaperGoes() {
        final ProjectModel m = project();
        // 600 mm across on A4 is three sheets, which hold more than 600 mm
        m.printWidthMmProperty().set(600);
        assertEquals(MediumPanel.Spare.MAP, MediumPanel.spare(m));
        m.areaExactProperty().set(true);
        assertEquals(MediumPanel.Spare.PAPER, MediumPanel.spare(m));

        // One strip of a roll exactly as wide as the print asked for leaves nothing over
        m.areaExactProperty().set(false);
        m.mediumKindProperty().set(ProjectModel.MediumKind.ROLL);
        m.printWidthMmProperty().set(m.rollWidthMmProperty().get() - 2 * m.edgeMmProperty().get());
        assertEquals(MediumPanel.Spare.NONE, MediumPanel.spare(m));
    }

    @Test
    @DisplayName("the zoom step's intro says what a zoom does to a print sized at a DPI")
    void theDetailIntroFollowsTheSizing() throws Exception {
        Stage stage = null;
        try {
            final ProjectModel m = project();
            final DetailPanel panel = new DetailPanel(m, new StepContext(null, null, null, ProviderCatalog::builtIn,
                    () -> null, () -> false, () -> false, p -> { }));
            stage = onFx(() -> Headless.show(new ScrollPane(panel.node()), 360, 1000));
            assertTrue(onFx(() -> find(panel.node(), Label.class,
                    l -> I18n.get("detail.intro").equals(l.getText())).isPresent()));
            onFx(() -> {
                m.sizeByProperty().set(ProjectModel.SizeBy.DPI);
                return null;
            });
            assertTrue(onFx(() -> find(panel.node(), Label.class,
                    l -> I18n.get("detail.intro.dpi").equals(l.getText())).isPresent()));
        } finally {
            final Stage shown = stage;
            if (shown != null) {
                onFx(() -> {
                    shown.close();
                    return null;
                });
            }
        }
    }

    @Test
    @DisplayName("the export's rail line names the formats, and how a PDF is split when there is one")
    void theExportSummaryNamesTheFormats() {
        final Set<ComposeJob.OutputFormat> both = EnumSet.of(ComposeJob.OutputFormat.PNG, ComposeJob.OutputFormat.PDF);
        assertEquals(I18n.format("export.summary", "PDF, PNG", I18n.get("export.grouping.short.PER_SHEET")),
                ExportPanel.summary(both, ComposeJob.OutputGrouping.PER_SHEET));
        assertEquals(I18n.format("export.summary", "PDF", I18n.get("export.grouping.short.SINGLE_DOCUMENT")),
                ExportPanel.summary(EnumSet.of(ComposeJob.OutputFormat.PDF), ComposeJob.OutputGrouping.SINGLE_DOCUMENT));
        assertEquals("PNG, TIFF", ExportPanel.summary(
                EnumSet.of(ComposeJob.OutputFormat.TIFF, ComposeJob.OutputFormat.PNG), ComposeJob.OutputGrouping.PER_SHEET),
                "a picture is a file per sheet whatever the PDF would be");
    }
}
