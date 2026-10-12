package net.trdlo.wtf.studio.map;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import net.trdlo.wtf.compose.PrintRect;
import net.trdlo.wtf.compose.SheetLayout;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.studio.steps.Step;
import net.trdlo.wtf.tiles.Mercator;

import java.util.function.Supplier;

/**
 * The project drawn over the map: the two shapes, what will actually be printed, the sheets and
 * their overlaps, and the tile grid.
 * <p>
 * <b>What is shown follows the step.</b> All of it at once is a thicket – the sheet rectangles
 * over the shape handles over the tile grid – and none of it is useful while the user is still
 * deciding where the print is. Each step shows what that step is about plus the context needed to
 * judge it, and nothing else.
 */
public final class ProjectOverlay implements TileCanvas.Overlay {

    /*
     * The two shapes' colors, shared with the editor so a shape keeps its color while it is being
     * edited. An editor color of its own, a yellow, say, next to the subject's orange would make a
     * Custom area read as a second subject.
     */
    static final Color FILL_LINE = Color.web("#8ce99a");
    static final Color FILL_TINT = Color.web("#8ce99a", 0.08);
    /** How much of the subject's color its fill shows: enough to find it, not enough to tint the map. */
    static final double SUBJECT_FILL_OPACITY = 0.10;

    /** The subject's outline, in the color the print's boundary line has. */
    static @NotNull Color subjectLine(final int rgb) {
        return Color.rgb((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    /** And its fill: the same color, mostly transparent. */
    static @NotNull Color subjectFill(final int rgb) {
        return Color.rgb((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, SUBJECT_FILL_OPACITY);
    }
    private static final Color SHEET_LINE = Color.web("#f1f3f5", 0.95);
    private static final Color OVERLAP_FILL = Color.web("#ffd43b", 0.22);
    private static final Color SHEET_LABEL = Color.web("#f8f9fa");
    private static final Color GRID_LINE = Color.web("#ffffff", 0.22);

    /**
     * Drawn under every line, wider than it.
     * <p>
     * The overlay sits on somebody else's map, and there is no color that reads on all of them:
     * white vanishes over a snowfield or a limestone plateau, black vanishes over forest and deep
     * bathymetry, and red vanishes over exactly the motorways it is most likely to cross. A
     * casing – a dark stroke under a light one, which is what a cartographer does to a road or a
     * label – needs no bet at all. Whichever of the two the background swallows, the other shows.
     */
    private static final Color CASING = Color.web("#000000", 0.55);
    private static final Color GRID_CASING = Color.web("#000000", 0.22);

    private final ProjectModel model;
    private final Supplier<Step> step;

    public ProjectOverlay(final @NotNull ProjectModel model, final @NotNull Supplier<Step> step) {
        this.model = model;
        this.step = step;
    }

    @Override
    public void draw(final @NotNull GraphicsContext g, final @NotNull Camera camera,
                     final double width, final double height) {
        final Step now = step.get();

        // The two shapes the print is made of, in every step. They are what everything else is
        // measured against – the sheets, the zoom, the tile count – and a step that hides them
        // asks the user to remember what they cannot see.
        //
        // The area is drawn once, dashed: what is printed. In step 2 the editor draws the shape it
        // grows from – the subject's box or the Custom shape – with the handles on that, so the
        // growth is the band between the two lines, and no third line is drawn in register.
        //
        // Except the subject's line in the two steps that show the print as it will be: there it
        // is the line the print draws along the outline, or nothing when it draws none, and its
        // tint, which no paper has, is left out.
        final int rgb = model.boundaryRgbProperty().get();
        final boolean asPrinted = now == Step.DETAIL || now == Step.EXPORT;
        if (!asPrinted || model.boundaryStrokedProperty().get()) {
            drawShape(g, camera, subjectOnScreen, model.subjectProperty().get(),
                    subjectLine(rgb), asPrinted ? null : subjectFill(rgb), false);
        }
        drawShape(g, camera, fillOnScreen, model.fill(), FILL_LINE, null, true);

        final SheetLayout layout = model.layout();
        if (layout == null) {
            return;
        }
        if (now == Step.MEDIUM || now == Step.DETAIL || now == Step.EXPORT) {
            drawPrintRect(g, camera, layout.print());
            drawSheets(g, camera, layout);
        }
        if (now == Step.DETAIL || now == Step.EXPORT) {
            drawTileGrid(g, camera, layout.print());
        }
    }

    // -------------------------------------------------------------------------------------------

    /** The two shapes as the screen needs them; each remembers its own last zoom. */
    private final ScreenShape subjectOnScreen = new ScreenShape();
    private final ScreenShape fillOnScreen = new ScreenShape();

    private static void drawShape(final @NotNull GraphicsContext g, final @NotNull Camera camera,
                                  final @NotNull ScreenShape onScreen,
                                  final @Nullable Geometry wgs84, final @NotNull Color line,
                                  final @Nullable Color fill, final boolean dashed) {
        if (wgs84 == null || wgs84.isEmpty()) {
            return;
        }
        if (Shapes.isPoint(wgs84)) {
            // A place, not an area. Drawn as a pin rather than as the hundred-meter rectangle it
            // technically is: at anything wider than a village that rectangle is one pixel, and a
            // single pixel of outline says neither "this is a place" nor which pixel it is.
            drawPin(g, camera, wgs84, line);
            return;
        }
        g.setLineDashes(dashed ? new double[]{6, 4} : null);
        // One path for every ring of every part, simplified to the zoom and cut to the view. The
        // fill is even-odd so a hole – a lake, an enclave – stays a hole; filling each outer ring
        // on its own would paint straight over them.
        g.beginPath();
        onScreen.append(g, wgs84, camera);
        if (fill != null) {
            g.setFill(fill);
            g.setFillRule(javafx.scene.shape.FillRule.EVEN_ODD);
            g.fill();
            g.setFillRule(javafx.scene.shape.FillRule.NON_ZERO);
        }
        strokeCased(g, line, 1.6);
        g.setLineDashes((double[]) null);
    }

    /**
     * The pin that marks a point shape: a round head on a tapering stem, tip on the coordinate.
     * <p>
     * The same drawing as {@link EditTool}'s, so that a place looks like itself whether or not
     * the editor happens to be open on it.
     */
    private static void drawPin(final @NotNull GraphicsContext g, final @NotNull Camera camera,
                                final @NotNull Geometry wgs84, final @NotNull Color line) {
        final var e = wgs84.getEnvelopeInternal();
        final double x = camera.toScreenX(Mercator.lonToUnitX((e.getMinX() + e.getMaxX()) / 2));
        final double tip = camera.toScreenY(Mercator.latToUnitY((e.getMinY() + e.getMaxY()) / 2));
        final double head = tip - PIN_HEAD_ABOVE;

        g.setLineDashes((double[]) null);
        g.beginPath();
        g.moveTo(x, tip);
        g.lineTo(x - PIN_HEAD_RADIUS * 0.55, head + PIN_HEAD_RADIUS * 0.6);
        g.lineTo(x + PIN_HEAD_RADIUS * 0.55, head + PIN_HEAD_RADIUS * 0.6);
        g.closePath();
        g.setFill(Color.WHITE);
        g.fill();
        strokeCased(g, line, 1.6);

        g.setFill(Color.WHITE);
        g.fillOval(x - PIN_HEAD_RADIUS, head - PIN_HEAD_RADIUS, PIN_HEAD_RADIUS * 2, PIN_HEAD_RADIUS * 2);
        g.setStroke(CASING);
        g.setLineWidth(3.2);
        g.strokeOval(x - PIN_HEAD_RADIUS, head - PIN_HEAD_RADIUS, PIN_HEAD_RADIUS * 2, PIN_HEAD_RADIUS * 2);
        g.setStroke(line);
        g.setLineWidth(1.6);
        g.strokeOval(x - PIN_HEAD_RADIUS, head - PIN_HEAD_RADIUS, PIN_HEAD_RADIUS * 2, PIN_HEAD_RADIUS * 2);
        g.setFill(line);
        g.fillOval(x - 2.4, head - 2.4, 4.8, 4.8);
    }

    private static final double PIN_HEAD_ABOVE = 13;
    private static final double PIN_HEAD_RADIUS = 7;

    /**
     * Strokes the current path twice: a dark casing, then the line itself.
     * <p>
     * Built once and stroked twice, which keeps the two in exact register – rebuilding the path
     * from the same coordinates would be the same picture, more slowly.
     */
    private static void strokeCased(final @NotNull GraphicsContext g, final @NotNull Color line,
                                    final double width) {
        g.setStroke(CASING);
        g.setLineWidth(width + 2.4);
        g.stroke();
        g.setStroke(line);
        g.setLineWidth(width);
        g.stroke();
    }

    /** The same for a rectangle, which has no path of its own to reuse. */
    private static void strokeCasedRect(final @NotNull GraphicsContext g, final @NotNull Color line,
                                        final double width, final double x, final double y,
                                        final double w, final double h) {
        g.beginPath();
        g.rect(x, y, w, h);
        strokeCased(g, line, width);
    }

    private static void path(final @NotNull GraphicsContext g, final @NotNull Camera camera,
                             final @NotNull Coordinate @NotNull [] ring) {
        g.beginPath();
        for (int i = 0; i < ring.length; i++) {
            final double x = camera.toScreenX(Mercator.lonToUnitX(ring[i].x));
            final double y = camera.toScreenY(Mercator.latToUnitY(ring[i].y));
            if (i == 0) {
                g.moveTo(x, y);
            } else {
                g.lineTo(x, y);
            }
        }
        g.closePath();
    }

    private static void drawPrintRect(final @NotNull GraphicsContext g, final @NotNull Camera camera,
                                      final @NotNull PrintRect print) {
        final double x = camera.toScreenX(print.x0());
        final double y = camera.toScreenY(print.y0());
        final double w = camera.toScreenX(print.x1()) - x;
        final double h = camera.toScreenY(print.y1()) - y;
        strokeCasedRect(g, FILL_LINE, 2, x, y, w, h);
    }

    /**
     * The sheets, with the shared band filled. The overlap is the part people get wrong, so it is
     * the part that is colored rather than merely outlined.
     */
    private static void drawSheets(final @NotNull GraphicsContext g, final @NotNull Camera camera,
                                   final @NotNull SheetLayout layout) {
        final PrintRect print = layout.print();
        final double perUnit = print.mmPerUnit();
        final double pxPerMm = camera.pixelsPerUnit() / perUnit;

        // Below this the strips are a gray smear and the labels collide; the panel has the numbers.
        final boolean labels = pxPerMm * layout.sheets().getFirst().widthMm() > 44;

        g.setFont(Font.font(11));
        g.setTextAlign(TextAlignment.CENTER);

        for (final SheetLayout.Sheet s : layout.sheets()) {
            final double x = camera.toScreenX(print.x0() + s.xMm() / perUnit);
            final double y = camera.toScreenY(print.y0() + s.yMm() / perUnit);
            final double w = s.widthMm() * pxPerMm;
            final double h = s.heightMm() * pxPerMm;

            // the band this sheet shares with the next one across, and the one below
            final double overlap = layout.overlapMm() * pxPerMm;
            if (overlap > 0 && s.column() < layout.columns() - 1) {
                g.setFill(OVERLAP_FILL);
                g.fillRect(x + w - overlap, y, overlap, h);
            }
            if (overlap > 0 && s.row() < layout.rows() - 1) {
                g.setFill(OVERLAP_FILL);
                g.fillRect(x, y + h - overlap, w, overlap);
            }
            strokeCasedRect(g, SHEET_LINE, 1.2, x + 0.5, y + 0.5, w, h);

            if (labels) {
                // The number gets a casing too, for the same reason the lines do
                final String text = String.valueOf(s.index());
                g.setStroke(CASING);
                g.setLineWidth(3);
                g.strokeText(text, x + w / 2, y + h / 2 + 4);
                g.setFill(SHEET_LABEL);
                g.fillText(text, x + w / 2, y + h / 2 + 4);
            }
        }
    }

    /**
     * The tile grid at the print's zoom, but only while a tile is big enough on screen to be worth
     * drawing. Below about twelve pixels the grid is solid gray and tells nobody anything.
     */
    private void drawTileGrid(final @NotNull GraphicsContext g, final @NotNull Camera camera,
                              final @NotNull PrintRect print) {
        final int zoom = model.zoomProperty().get();
        final int n = Mercator.tileCount(zoom);
        final double tilePx = camera.pixelsPerUnit() / n;
        if (tilePx < 12) {
            return;
        }
        final int minX = (int) Math.floor(print.x0() * n);
        final int maxX = (int) Math.ceil(print.x1() * n);
        final int minY = (int) Math.floor(print.y0() * n);
        final int maxY = (int) Math.ceil(print.y1() * n);
        if ((long) (maxX - minX) * (maxY - minY) > 40_000) {
            return;     // a grid nobody asked to wait for
        }
        final double top = camera.toScreenY((double) minY / n);
        final double bottom = camera.toScreenY((double) maxY / n);
        final double left = camera.toScreenX((double) minX / n);
        final double right = camera.toScreenX((double) maxX / n);

        // The grid is a texture rather than a boundary, so its casing is lighter: enough to stay
        // visible over pale ground without turning the map into graph paper.
        g.beginPath();
        for (int x = minX; x <= maxX; x++) {
            final double sx = Math.floor(camera.toScreenX((double) x / n)) + 0.5;
            g.moveTo(sx, top);
            g.lineTo(sx, bottom);
        }
        for (int y = minY; y <= maxY; y++) {
            final double sy = Math.floor(camera.toScreenY((double) y / n)) + 0.5;
            g.moveTo(left, sy);
            g.lineTo(right, sy);
        }
        g.setStroke(GRID_CASING);
        g.setLineWidth(2.2);
        g.stroke();
        g.setStroke(GRID_LINE);
        g.setLineWidth(0.7);
        g.stroke();
    }

    // -------------------------------------------------------------------------------------------

    /** Frames the whole print, for the "show me what I made" button. */
    public void frame(final @NotNull Camera camera) {
        final Geometry fill = model.fill();
        if (fill != null && !fill.isEmpty()) {
            camera.zoomTo(Shapes.boundsOf(fill), 0.12);
        } else if (model.areaProperty().get() != null) {
            camera.zoomTo(Shapes.boundsOf(model.areaProperty().get()), 0.12);
        }
    }
}
