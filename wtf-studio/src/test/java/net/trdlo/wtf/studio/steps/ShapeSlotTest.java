package net.trdlo.wtf.studio.steps;

import javafx.event.EventType;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.studio.Headless;
import net.trdlo.wtf.studio.map.Camera;
import net.trdlo.wtf.studio.map.EditTool;
import net.trdlo.wtf.studio.map.ShapeTool;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Mercator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;

import static net.trdlo.wtf.studio.Headless.onFx;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The area step's Custom slot: what arrives joins what is there, as it does in the subject's slot,
 * and a part taken out on the map works the area out again.
 * <p>
 * On JavaFX's headless platform ({@link Headless}), since a slot is controls. Its context is left
 * empty, as {@code StepContextCatalogTest} leaves it: a slot whose step is not on screen asks it for
 * nothing, and a shape handed to it is what a drawing tool, the library or a file would hand it.
 */
class ShapeSlotTest {

    private static final LonLatBounds WEST = new LonLatBounds(12.0, 48.5, 14.0, 50.5);
    private static final LonLatBounds EAST = new LonLatBounds(16.0, 48.5, 18.0, 50.5);

    @BeforeAll
    static void toolkit() {
        Headless.start();
    }

    private static StepContext nothing() {
        return new StepContext(null, null, null, null, null, null, null, null);
    }

    /** Control and a right-click on a point: what takes a whole part out. */
    private static MouseEvent controlRightClick(final Camera c, final double lon, final double lat) {
        final EventType<MouseEvent> type = MouseEvent.MOUSE_PRESSED;
        return new MouseEvent(type, c.toScreenX(Mercator.lonToUnitX(lon)), c.toScreenY(Mercator.latToUnitY(lat)),
                0, 0, MouseButton.SECONDARY, 1, false, true, false, false,
                false, false, true, false, false, true, null);
    }

    @Test
    @DisplayName("two arrivals in the Custom slot are two parts, and a part taken out works the area out again")
    void theCustomSlotGathersParts() throws Exception {
        final ProjectModel m = new ProjectModel();          // no worker: the area is worked out at once
        m.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
        m.bufferValueProperty().set(10_000);
        final ShapeSlot slot = onFx(() -> new ShapeSlot(m, nothing(), ShapeTool.Slot.AREA, "area.slot", "area.slot.hint", "area.slot.help",
                m.customProperty(), m.customOriginProperty(), new Label()));

        onFx(() -> {
            slot.add(Shapes.rectangle(WEST), "drawn");
            slot.add(Shapes.rectangle(EAST), "library:ne0:AUT:Austria");
            return null;
        });

        final Geometry both = m.customProperty().get();
        assertEquals(2, both.getNumGeometries(), "the second joined the first rather than replacing it");
        assertEquals("drawn + 2", m.customOriginProperty().get());
        assertTrue(Shapes.reproduces(m.areaProperty().get(), Shapes.areaFrom(both, Shapes.Simplify.NONE, 10_000)),
                "and the area grows from both");

        // Control and a right-click on a corner of the eastern one, with the editor the step puts on
        // the Custom shape
        final Camera c = new Camera();
        c.setViewSize(800, 600);
        c.centerOn(15.0, 49.5, 6);
        final EditTool editor = new EditTool(ShapeTool.Slot.AREA, EditTool.Mode.FULL, m.customProperty(),
                g -> { }, () -> { });
        assertTrue(editor.pressed(controlRightClick(c, 18.0, 50.5), c), "a corner of the eastern part");

        assertEquals(1, m.customProperty().get().getNumGeometries(), "the part is gone");
        assertEquals(WEST, Shapes.boundsOf(m.customProperty().get()));
        assertTrue(Shapes.reproduces(m.areaProperty().get(),
                        Shapes.areaFrom(Shapes.rectangle(WEST), Shapes.Simplify.NONE, 10_000)),
                "and the area is worked out again, from what is left");
    }
}
