package net.trdlo.wtf.studio.steps;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.studio.Headless;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.studio.Units;
import net.trdlo.wtf.studio.map.ShapeTool;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Provider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static net.trdlo.wtf.studio.Headless.find;
import static net.trdlo.wtf.studio.Headless.onFx;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The controls of steps 1 to 3 as a newcomer meets them: the library and the import in words, the
 * room around the area in kilometers, the ways of sizing a print in the order people use them, and
 * no overlap to set on a print that has no seam.
 */
class StepControlsTest {

    private static final LonLatBounds BOHEMIA = new LonLatBounds(12, 48.5, 18, 51);

    private final Units before = Units.current();

    @BeforeAll
    static void toolkit() {
        Headless.start();
    }

    @AfterEach
    void back() {
        Units.use(before.key());
    }

    private static StepContext nothing() {
        return new StepContext(null, null, null, null, null, null, null, null);
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

    /** Every node of a kind under a root, in the order the panel lays them out. */
    private static <T extends Node> List<T> all(final Node root, final Class<T> type) {
        final List<T> found = new ArrayList<>();
        if (type.isInstance(root)) {
            found.add(type.cast(root));
        }
        if (root instanceof Parent p) {
            p.getChildrenUnmodifiable().forEach(n -> found.addAll(all(n, type)));
        }
        return found;
    }

    @Test
    @DisplayName("the library comes first and says what it does in words, and so does the import")
    void theLibraryAndTheImportInWords() throws Exception {
        final ProjectModel m = new ProjectModel();
        final Node slot = onFx(() -> new ShapeSlot(m, nothing(), ShapeTool.Slot.SUBJECT, "subject.slot", null,
                "subject.help", m.subjectProperty(), m.subjectOriginProperty(), new Label()).build());
        final List<ButtonBase> buttons = all(slot, ButtonBase.class).stream()
                .filter(b -> !b.getStyleClass().contains("help-icon")).toList();
        assertEquals(I18n.get("area.tool.library"), buttons.getFirst().getText(), "the first of the tools");
        assertNotNull(buttons.getFirst().getGraphic(), "with its globe");
        final Button importing = (Button) buttons.stream()
                .filter(b -> I18n.get("area.import").equals(b.getText())).findFirst().orElseThrow();
        assertEquals(I18n.get("area.tool.import"), importing.getTooltip().getText(), "the formats in its tooltip");
        assertEquals(2, buttons.stream().filter(b -> b.getText() != null && !b.getText().isEmpty()).count(),
                "the drawing tools and the bin stay icons");
    }

    @Test
    @DisplayName("the room around the area is said in kilometers on the rail, while the model keeps meters")
    void theRoomAroundItInKilometers() {
        Units.use("mm");
        final ProjectModel m = project();
        m.bufferValueProperty().set(50_000);
        final AreaPanel panel = new AreaPanel(m, nothing());
        assertEquals(I18n.format("area.summary.grown", I18n.get("area.source.custom"),
                I18n.format("area.unit.short.KILOMETERS", "50")), panel.summary());
        assertEquals(50_000, m.bufferValueProperty().get(), 1e-9);
    }

    @Test
    @DisplayName("a print is sized by width or height, by sheets, by scale and by DPI, in that order")
    void theWaysOfSizingInOrder() throws Exception {
        final ProjectModel m = project();
        final MediumPanel panel = new MediumPanel(m);
        final List<String> ways = List.of(I18n.get("medium.by.millimeters"), I18n.get("medium.by.sheets"),
                I18n.get("medium.by.scale"), I18n.get("medium.by.dpi"));
        final List<String> radios = onFx(() -> all(panel.node(), RadioButton.class).stream()
                .map(RadioButton::getText).filter(ways::contains).toList());
        assertEquals(ways, radios);
    }

    @Test
    @DisplayName("on one sheet there is no seam: no overlap to set and no line about it, but the white edge stays")
    void noOverlapOnOneSheet() throws Exception {
        Stage stage = null;
        try {
            final ProjectModel m = project();
            final MediumPanel panel = new MediumPanel(m);
            stage = onFx(() -> Headless.show(new ScrollPane(panel.node()), 360, 1400));

            // 600 mm across on A4 is several sheets
            onFx(() -> {
                m.printWidthMmProperty().set(600);
                return null;
            });
            assertTrue(MediumPanel.hasSeams(m));
            assertTrue(onFx(() -> shown(panel.node(), "medium.overlap.hint")));
            assertTrue(onFx(() -> shown(panel.node(), "medium.edge.hint")));

            // and 150 mm fits on one
            onFx(() -> {
                m.printWidthMmProperty().set(150);
                return null;
            });
            assertEquals(1, m.layout().count());
            assertFalse(MediumPanel.hasSeams(m));
            assertFalse(onFx(() -> shown(panel.node(), "medium.overlap.hint")), "the overlap goes");
            assertTrue(onFx(() -> shown(panel.node(), "medium.edge.hint")), "the white edge stays");
        } finally {
            final Stage open = stage;
            if (open != null) {
                onFx(() -> {
                    open.close();
                    return null;
                });
            }
        }
    }

    /** Whether the gray line of a key is on screen: it, and everything it sits in, visible. */
    private static boolean shown(final Node root, final String key) {
        final Text text = find(root, Text.class, t -> I18n.get(key).equals(t.getText())).orElseThrow();
        for (Node n = text; n != null; n = n.getParent()) {
            if (!n.isVisible()) {
                return false;
            }
        }
        return true;
    }
}
