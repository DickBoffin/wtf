package net.trdlo.wtf.studio.map;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.tiles.LonLatBounds;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Drawing a shape on the map.
 * <p>
 * A tool owns the primary mouse button while it is installed; the middle button keeps panning
 * whatever is installed, so a half-drawn polygon never has to be thrown away just to see what is
 * over the next hill. Every tool hands back a WGS84 polygon and then reports itself finished –
 * the panel decides which slot it lands in.
 */
public abstract class ShapeTool {

    /** Where a finished shape goes. The same three tools serve both slots. */
    public enum Slot { AREA, SUBJECT }

    protected static final Color LINE = Color.web("#61d0ff");
    protected static final Color FILL = Color.web("#61d0ff", 0.12);
    protected static final Color HANDLE = Color.WHITE;

    private final Slot slot;
    private final Consumer<Geometry> onFinished;
    private final Runnable onDone;

    protected ShapeTool(final @NotNull Slot slot, final @NotNull Consumer<Geometry> onFinished,
                        final @NotNull Runnable onDone) {
        this.slot = slot;
        this.onFinished = onFinished;
        this.onDone = onDone;
    }

    public @NotNull Slot slot() {
        return slot;
    }

    /** What the panel shows while the tool is live: "drag a rectangle", "click each corner". */
    public abstract @NotNull String hintKey();

    /** @return true when the tool consumed the event and the canvas must not pan */
    public boolean pressed(final @NotNull MouseEvent e, final @NotNull Camera camera) {
        return false;
    }

    public boolean dragged(final @NotNull MouseEvent e, final @NotNull Camera camera) {
        return false;
    }

    public boolean released(final @NotNull MouseEvent e, final @NotNull Camera camera) {
        return false;
    }

    public boolean clicked(final @NotNull MouseEvent e, final @NotNull Camera camera) {
        return false;
    }

    /** The pointer moved with no button down. @return true when the tool needs a repaint. */
    public boolean moved(final @NotNull MouseEvent e, final @NotNull Camera camera) {
        return false;
    }

    /** The pointer left the map. @return true when the tool needs a repaint. */
    public boolean exited() {
        return false;
    }

    /** Enter closes a polygon, Escape abandons it. @return true when the key was used. */
    public boolean keyPressed(final javafx.scene.input.@NotNull KeyEvent e) {
        return false;
    }

    public abstract void draw(final @NotNull GraphicsContext g, final @NotNull Camera camera);

    /** Hands the finished shape over and stands down. */
    protected void finish(final @NotNull Geometry shape) {
        onFinished.accept(shape);
        onDone.run();
    }

    /** Abandons whatever was in progress. Called when the tool is swapped out, too. */
    public void cancel() {
    }

    /**
     * The pointer while this tool is installed.
     * <p>
     * A crosshair says "you are about to draw something", which is true of every tool here except
     * the editor – and the editor is installed for as long as its step is open. A map permanently
     * wearing a crosshair would be telling the user they are in a mode they are not in.
     */
    public @NotNull javafx.scene.Cursor cursor() {
        return javafx.scene.Cursor.CROSSHAIR;
    }

    protected static double lon(final @NotNull MouseEvent e, final @NotNull Camera camera) {
        return camera.lonAt(e.getX());
    }

    protected static double lat(final @NotNull MouseEvent e, final @NotNull Camera camera) {
        return camera.latAt(e.getY());
    }

    protected static void handle(final @NotNull GraphicsContext g, final double x, final double y) {
        g.setFill(HANDLE);
        g.fillRect(x - 3, y - 3, 6, 6);
        g.setStroke(Color.BLACK);
        g.setLineWidth(1);
        g.strokeRect(x - 3.5, y - 3.5, 7, 7);
    }

    // ===========================================================================================

    /**
     * A single click. Becomes the smallest rectangle rather than a point, so that everything
     * downstream deals in areas and there is no "what is the bounding box of a point" case.
     */
    public static final class PointTool extends ShapeTool {

        public PointTool(final @NotNull Slot slot, final @NotNull Consumer<Geometry> onFinished,
                         final @NotNull Runnable onDone) {
            super(slot, onFinished, onDone);
        }

        @Override
        public @NotNull String hintKey() {
            return "area.hint.point";
        }

        @Override
        public boolean clicked(final @NotNull MouseEvent e, final @NotNull Camera camera) {
            if (!e.isStillSincePress()) {
                return false;      // JavaFX sends a click after a drag too; a pan must not drop a point
            }
            finish(Shapes.point(lon(e, camera), lat(e, camera)));
            return true;
        }

        @Override
        public void draw(final @NotNull GraphicsContext g, final @NotNull Camera camera) {
        }
    }

    // ===========================================================================================

    /** Drag one corner to the other. The common case, because a wall is a rectangle. */
    public static final class RectangleTool extends ShapeTool {

        private double x0, y0, x1, y1;
        private boolean dragging;

        public RectangleTool(final @NotNull Slot slot, final @NotNull Consumer<Geometry> onFinished,
                             final @NotNull Runnable onDone) {
            super(slot, onFinished, onDone);
        }

        @Override
        public @NotNull String hintKey() {
            return "area.hint.rectangle";
        }

        @Override
        public boolean pressed(final @NotNull MouseEvent e, final @NotNull Camera camera) {
            if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY) {
                return false;       // the middle button still pans, and the right one is not ours
            }
            x0 = x1 = e.getX();
            y0 = y1 = e.getY();
            dragging = true;
            return true;
        }

        @Override
        public boolean dragged(final @NotNull MouseEvent e, final @NotNull Camera camera) {
            if (!dragging) {
                return false;
            }
            x1 = e.getX();
            y1 = e.getY();
            return true;
        }

        @Override
        public boolean released(final @NotNull MouseEvent e, final @NotNull Camera camera) {
            if (!dragging) {
                return false;
            }
            dragging = false;
            x1 = e.getX();
            y1 = e.getY();
            // A stray click is a click, not a zero-area rectangle nobody can see or grab.
            if (Math.abs(x1 - x0) < 4 || Math.abs(y1 - y0) < 4) {
                return true;
            }
            final double west = Math.min(camera.lonAt(x0), camera.lonAt(x1));
            final double east = Math.max(camera.lonAt(x0), camera.lonAt(x1));
            final double south = Math.min(camera.latAt(y0), camera.latAt(y1));
            final double north = Math.max(camera.latAt(y0), camera.latAt(y1));
            finish(Shapes.rectangle(new LonLatBounds(west, south, east, north)));
            return true;
        }

        @Override
        public void cancel() {
            dragging = false;
        }

        @Override
        public void draw(final @NotNull GraphicsContext g, final @NotNull Camera camera) {
            if (!dragging) {
                return;
            }
            final double x = Math.min(x0, x1), y = Math.min(y0, y1);
            final double w = Math.abs(x1 - x0), h = Math.abs(y1 - y0);
            g.setFill(FILL);
            g.fillRect(x, y, w, h);
            g.setStroke(LINE);
            g.setLineWidth(1.5);
            g.strokeRect(x + 0.5, y + 0.5, w, h);
            handle(g, x0, y0);
            handle(g, x1, y1);
        }
    }

    // ===========================================================================================

    /**
     * Click each vertex; Enter or a click on the first one closes it, Backspace takes one back,
     * Escape abandons the lot.
     */
    public static final class PolygonTool extends ShapeTool {

        private final List<Coordinate> points = new ArrayList<>();
        private double cursorX = Double.NaN, cursorY = Double.NaN;

        /** How close to the first vertex counts as closing the ring. */
        private static final double SNAP_PIXELS = 10;

        public PolygonTool(final @NotNull Slot slot, final @NotNull Consumer<Geometry> onFinished,
                           final @NotNull Runnable onDone) {
            super(slot, onFinished, onDone);
        }

        @Override
        public @NotNull String hintKey() {
            return "area.hint.polygon";
        }

        @Override
        public boolean pressed(final @NotNull MouseEvent e, final @NotNull Camera camera) {
            cursorX = e.getX();
            cursorY = e.getY();
            if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY) {
                return false;       // the middle button still pans a half-drawn polygon around
            }
            return true;    // swallow the press so the canvas does not treat it as a pan anchor
        }

        @Override
        public boolean dragged(final @NotNull MouseEvent e, final @NotNull Camera camera) {
            cursorX = e.getX();
            cursorY = e.getY();
            return true;
        }

        @Override
        public boolean moved(final @NotNull MouseEvent e, final @NotNull Camera camera) {
            cursorX = e.getX();
            cursorY = e.getY();
            // Nothing to draw a band from until the first vertex is down
            return !points.isEmpty();
        }

        @Override
        public boolean clicked(final @NotNull MouseEvent e, final @NotNull Camera camera) {
            cursorX = e.getX();
            cursorY = e.getY();
            if (!e.isStillSincePress()) {
                // JavaFX sends a click after a drag too, the twin of the point tool's own bug; the
                // press already swallowed the drag, so this is still ours, just not a vertex
                return true;
            }
            if (e.getClickCount() >= 2) {
                close();
                return true;
            }
            if (points.size() >= 3 && nearFirst(e, camera)) {
                close();
                return true;
            }
            points.add(new Coordinate(lon(e, camera), lat(e, camera)));
            return true;
        }

        private boolean nearFirst(final @NotNull MouseEvent e, final @NotNull Camera camera) {
            if (points.isEmpty()) {
                return false;
            }
            final double[] p = screenOf(points.getFirst(), camera);
            return Math.hypot(e.getX() - p[0], e.getY() - p[1]) < SNAP_PIXELS;
        }

        @Override
        public boolean keyPressed(final javafx.scene.input.@NotNull KeyEvent e) {
            switch (e.getCode()) {
                case ENTER -> {
                    close();
                    return true;
                }
                case ESCAPE -> {
                    points.clear();
                    return true;
                }
                case BACK_SPACE, DELETE -> {
                    if (!points.isEmpty()) {
                        points.removeLast();
                    }
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }

        private void close() {
            if (points.size() < 3) {
                return;     // two points are a line; say nothing and let the user keep clicking
            }
            // A straight line on the flat map, however far apart its clicks: an edge longer than
            // half the world gets a point where the band was drawn, or the file would take it the
            // short way, across the antimeridian, and refuse it
            finish(Shapes.splitLongEdges(Shapes.polygon(points.toArray(new Coordinate[0]))));
            points.clear();
        }

        @Override
        public void cancel() {
            points.clear();
        }

        @Override
        public void draw(final @NotNull GraphicsContext g, final @NotNull Camera camera) {
            if (points.isEmpty()) {
                return;
            }
            g.setStroke(LINE);
            g.setLineWidth(1.5);
            g.beginPath();
            for (int i = 0; i < points.size(); i++) {
                final double[] p = screenOf(points.get(i), camera);
                if (i == 0) {
                    g.moveTo(p[0], p[1]);
                } else {
                    g.lineTo(p[0], p[1]);
                }
            }
            if (!Double.isNaN(cursorX)) {
                g.lineTo(cursorX, cursorY);     // the rubber band to where the mouse is now
            }
            g.stroke();
            for (final Coordinate c : points) {
                final double[] p = screenOf(c, camera);
                handle(g, p[0], p[1]);
            }
        }

        private static double @NotNull [] screenOf(final @NotNull Coordinate c,
                                                   final @NotNull Camera camera) {
            return new double[]{
                    camera.toScreenX(net.trdlo.wtf.tiles.Mercator.lonToUnitX(c.x)),
                    camera.toScreenY(net.trdlo.wtf.tiles.Mercator.latToUnitY(c.y))};
        }
    }

    // ===========================================================================================

    /** Nothing selected: the canvas pans and zooms as it always did. */
    public static @Nullable ShapeTool none() {
        return null;
    }
}
