package net.trdlo.wtf.studio.map;

import javafx.scene.effect.ColorAdjust;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.compose.TileEffect;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.tiles.LonLatBounds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two treatments on the main map, as the window wires them. No toolkit – the filters are plain
 * objects until something draws with them.
 */
class MapTreatmentsTest {

    private static ProjectModel withSubject() {
        final ProjectModel m = new ProjectModel();
        m.subjectProperty().set(Shapes.rectangle(new LonLatBounds(14.2, 49.9, 14.7, 50.2)));
        return m;
    }

    @Test
    @DisplayName("two regions treated alike are one filter on the whole map")
    void alike() {
        final ProjectModel m = withSubject();
        m.insideEffectProperty().set(TileEffect.FADED);
        final AtomicReference<MapTreatments.Shown> shown = new AtomicReference<>();
        MapTreatments.watch(m, shown::set);
        assertNull(shown.get().region(), "nothing to split");
        assertInstanceOf(ColorAdjust.class, shown.get().base());
    }

    @Test
    @DisplayName("faded outside, full color inside: the map splits along the subject, and follows an edit")
    void splits() {
        final ProjectModel m = withSubject();
        final AtomicReference<MapTreatments.Shown> shown = new AtomicReference<>();
        MapTreatments.watch(m, shown::set);
        m.outsideDiffersProperty().set(true);
        m.outsideEffectProperty().set(TileEffect.FADED);

        assertSame(m.subjectProperty().get(), shown.get().region());
        assertInstanceOf(ColorAdjust.class, shown.get().base(), "the outside, faded");
        assertNull(shown.get().regionEffect(), "the inside, as it is");

        m.outsideDrawnProperty().set(false);
        assertFalse(shown.get().drawOutside(), "a region not drawn is shown as paper");
        assertNull(shown.get().base(), "white, which no fade of its own may gray");
        assertTrue(shown.get().drawInside());
    }

    @Test
    @DisplayName("an inside not drawn is paper with no filter, and the outside keeps its own")
    void anInsideNotDrawn() {
        final ProjectModel m = withSubject();
        final AtomicReference<MapTreatments.Shown> shown = new AtomicReference<>();
        MapTreatments.watch(m, shown::set);
        m.insideEffectProperty().set(TileEffect.FADED);
        m.insideDrawnProperty().set(false);

        assertFalse(shown.get().drawInside());
        assertNull(shown.get().regionEffect(), "the inside is white");
        assertInstanceOf(ColorAdjust.class, shown.get().base(), "the outside, faded as it was");
    }
}
