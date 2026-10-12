package net.trdlo.wtf.studio.map;

import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.PixelReader;
import javafx.scene.paint.Color;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.studio.Headless;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Mercator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static net.trdlo.wtf.studio.Headless.onFx;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The line along the subject in the 1:1 preview: there when the print draws it, as wide on the
 * screen as it will be on the paper, and absent when it is not printed.
 */
class PrintedLineTest {

    private static final int SIZE = 300;
    private static final LonLatBounds SUBJECT = new LonLatBounds(14.9, 49.95, 15.1, 50.05);
    /** Four pixels to a millimeter of print, as a screen of 101.6 DPI shows it at 1:1. */
    private static final double PX_PER_MM = 4;

    @BeforeAll
    static void toolkit() {
        Headless.start();
    }

    /** How many pixels across the subject's west edge, halfway up, are not white. */
    private static int band(final boolean stroked, final double widthMm) throws Exception {
        return onFx(() -> {
            final ProjectModel m = new ProjectModel();
            m.subjectProperty().set(Shapes.rectangle(SUBJECT));
            m.boundaryStrokedProperty().set(stroked);
            m.boundaryWidthMmProperty().set(widthMm);
            final Camera camera = new Camera();
            camera.setViewSize(SIZE, SIZE);
            camera.centerOn(15, 50, 8);
            final Canvas canvas = new Canvas(SIZE, SIZE);
            canvas.getGraphicsContext2D().setFill(Color.WHITE);
            canvas.getGraphicsContext2D().fillRect(0, 0, SIZE, SIZE);
            new PrintedLine(m, () -> PX_PER_MM).draw(canvas.getGraphicsContext2D(), camera, SIZE, SIZE);

            final int x = (int) Math.round(camera.toScreenX(Mercator.lonToUnitX(SUBJECT.west())));
            final int y = (int) Math.round(camera.toScreenY(Mercator.latToUnitY(50)));
            final PixelReader px = canvas.snapshot(new SnapshotParameters(), null).getPixelReader();
            final Color line = ProjectOverlay.subjectLine(m.boundaryRgbProperty().get());
            int painted = 0;
            for (int dx = -20; dx <= 20; dx++) {
                final Color c = px.getColor(x + dx, y);
                if (!c.equals(Color.WHITE)) {
                    painted++;
                }
                if (dx == 0 && stroked) {
                    assertEquals(line.getRed(), c.getRed(), 0.05, "the boundary's own color on the edge");
                    assertEquals(line.getGreen(), c.getGreen(), 0.05);
                    assertEquals(line.getBlue(), c.getBlue(), 0.05);
                }
            }
            return painted;
        });
    }

    @Test
    @DisplayName("a printed line is as many millimeters wide on the glass as on the paper")
    void asWideAsOnThePaper() throws Exception {
        assertEquals(1.5 * PX_PER_MM, band(true, 1.5), 2, "1.5 mm at four pixels a millimeter");
        assertEquals(4 * PX_PER_MM, band(true, 4), 2, "and 4 mm is wider by as much");
    }

    @Test
    @DisplayName("a line the print does not draw is not in the preview")
    void notPrintedNotShown() throws Exception {
        assertEquals(0, band(false, 1.5));
    }
}
