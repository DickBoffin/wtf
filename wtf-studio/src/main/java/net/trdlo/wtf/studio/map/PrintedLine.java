package net.trdlo.wtf.studio.map;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.studio.project.ProjectModel;
import org.jetbrains.annotations.NotNull;
import org.locationtech.jts.geom.Geometry;

import java.util.function.DoubleSupplier;

/**
 * The line the print draws along the subject's outline, as it will be on the paper: in its color,
 * as many millimeters wide as the print's, joined round as the exporters join it – and nothing at
 * all when the print draws none.
 * <p>
 * For a view at a known scale, the 1:1 preview, where a millimeter of paper is a known number of
 * pixels. No casing and no fill, which the paper has neither of: this is the print, not a guide
 * drawn over a map ({@link ProjectOverlay}).
 */
public final class PrintedLine implements TileCanvas.Overlay {

    private final ProjectModel model;
    private final DoubleSupplier pixelsPerPrintMm;
    private final ScreenShape onScreen = new ScreenShape();

    /** @param pixelsPerPrintMm how many screen pixels a millimeter of the finished print is now */
    public PrintedLine(final @NotNull ProjectModel model, final @NotNull DoubleSupplier pixelsPerPrintMm) {
        this.model = model;
        this.pixelsPerPrintMm = pixelsPerPrintMm;
    }

    @Override
    public void draw(final @NotNull GraphicsContext g, final @NotNull Camera camera,
                     final double width, final double height) {
        final Geometry subject = model.subjectProperty().get();
        // A place is not stroked: the print strokes an outline, and a point has none
        if (!model.boundaryStrokedProperty().get() || subject == null || subject.isEmpty()
                || Shapes.isPoint(subject)) {
            return;
        }
        g.setStroke(ProjectOverlay.subjectLine(model.boundaryRgbProperty().get()));
        g.setLineWidth(model.boundaryWidthMmProperty().get() * pixelsPerPrintMm.getAsDouble());
        g.setLineJoin(StrokeLineJoin.ROUND);
        g.setLineCap(StrokeLineCap.BUTT);
        g.setLineDashes((double[]) null);
        g.beginPath();
        onScreen.append(g, subject, camera);
        g.stroke();
    }
}
