package net.trdlo.wtf.studio.map;

import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.paint.Color;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.studio.Headless;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.studio.steps.Step;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Mercator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static net.trdlo.wtf.studio.Headless.onFx;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The subject's line on the map: the guide while the subject and the area are chosen, and in the
 * steps that show the print, the line the print draws – or none.
 */
class SubjectLineTest {

    private static final int SIZE = 200;
    private static final LonLatBounds SUBJECT = new LonLatBounds(14.9, 49.95, 15.1, 50.05);

    @BeforeAll
    static void toolkit() {
        Headless.start();
    }

    /** Whether anything is drawn on the subject's west edge, halfway up, after the overlay has drawn. */
    private static boolean lineShown(final Step step, final boolean stroked) throws Exception {
        return onFx(() -> {
            final ProjectModel m = new ProjectModel();
            m.subjectProperty().set(Shapes.rectangle(SUBJECT));
            m.boundaryStrokedProperty().set(stroked);
            final Camera camera = new Camera();
            camera.setViewSize(SIZE, SIZE);
            camera.centerOn(15, 50, 8);
            final Canvas canvas = new Canvas(SIZE, SIZE);
            canvas.getGraphicsContext2D().setFill(Color.WHITE);
            canvas.getGraphicsContext2D().fillRect(0, 0, SIZE, SIZE);
            new ProjectOverlay(m, () -> step).draw(canvas.getGraphicsContext2D(), camera, SIZE, SIZE);

            final int x = (int) Math.round(camera.toScreenX(Mercator.lonToUnitX(SUBJECT.west())));
            final int y = (int) Math.round(camera.toScreenY(Mercator.latToUnitY(50)));
            // The line and its casing, wherever across them a pixel lands
            final javafx.scene.image.PixelReader px =
                    canvas.snapshot(new SnapshotParameters(), null).getPixelReader();
            for (int dx = -3; dx <= 3; dx++) {
                if (!px.getColor(x + dx, y).equals(Color.WHITE)) {
                    return true;
                }
            }
            return false;
        });
    }

    @ParameterizedTest(name = "{0}, stroked {1}")
    @CsvSource({"SUBJECT, false, true", "AREA, false, true", "MEDIUM, false, true",
            "DETAIL, false, false", "EXPORT, false, false", "DETAIL, true, true", "EXPORT, true, true"})
    @DisplayName("the subject's line is the guide until the print is shown, and then the print's own")
    void theLine(final Step step, final boolean stroked, final boolean shown) throws Exception {
        assertEquals(shown, lineShown(step, stroked));
    }
}
