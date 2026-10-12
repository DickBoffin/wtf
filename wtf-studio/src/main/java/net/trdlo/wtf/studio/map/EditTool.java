package net.trdlo.wtf.studio.map;

import javafx.beans.property.ObjectProperty;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygonal;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.studio.map.RingModel.Ring;

import java.util.List;
import java.util.function.Consumer;

import static net.trdlo.wtf.studio.map.HandleLayout.hasRoomBetween;
import static net.trdlo.wtf.studio.map.HandleLayout.inView;
import static net.trdlo.wtf.studio.map.HandleLayout.screen;

/**
 * Changing a shape that already exists, rather than drawing another one.
 * <p>
 * Nobody gets a rectangle right on the first drag, and a polygon of any size is worse: one corner
 * in the wrong place should not mean throwing the whole thing away and starting again. This is the
 * tool that makes the first attempt good enough – drag a handle to move it, right-click a vertex
 * to remove it, drag an edge's midpoint to add one – or, on a rectangle, to move that side.
 * <p>
 * This is the editor's gestures and its drawing. The rings it edits, read from the shape and
 * written back to it, are {@link RingModel}'s; where their handles are on the screen and which of
 * them show is {@link HandleLayout}'s.
 */
public final class EditTool extends ShapeTool {

    /**
     * How near counts as on it, in screen pixels.
     * <p>
     * Seven is a fourteen-pixel target, which is small for a finger and generous for a mouse; a
     * pin gets more because its point is at the bottom of a tall glyph and people aim at the head.
     */
    public static final double HIT_RADIUS = 7;
    public static final double PIN_HIT_RADIUS = 10;

    /**
     * A vertex shows when its neighbors are this many hit radii away, so handles never overlap.
     * <p>
     * Three is a starting value and deliberately a single number: two would let handles touch,
     * and five hides vertices that there is plenty of room for.
     */
    public static final double HANDLE_SPACING = 3;

    /** How near the pointer has to come to a part's outline, in screen pixels, for its handles to show. */
    public static final double HOVER_RADIUS = 24;

    private static final Color PIN_BODY = Color.WHITE;
    private static final Color PIN_EDGE = Color.web("#1b1e23");
    private static final Color MIDPOINT = Color.web("#ffffff", 0.55);

    /**
     * What the tool is allowed to do to the shape under it.
     * <p>
     * {@code RECTANGLE} is the subject's box in the area step, which the subject decides: its
     * editor drags the four corners and nothing else, with no midpoints and no removals, and the
     * first drag makes the box a Custom shape. {@code FULL} is everything else – a subject or a
     * Custom shape is whatever shape a country is, and a rectangle there stops being one by losing
     * a corner to a right-click.
     */
    public enum Mode { FULL, RECTANGLE }

    private final ObjectProperty<Geometry> target;
    private final Runnable changed;
    private final Mode mode;

    /**
     * Set while this tool is putting its own result back, so its own listener ignores the write.
     * <p>
     * Without it every dragged vertex would re-read the rings it had just written, mid-drag.
     */
    private boolean writing;

    /**
     * Re-reads when the shape is replaced under the tool.
     * <p>
     * <b>The editor outlives any one shape.</b> Reading the rings once in the constructor is not
     * enough: it is installed for as long as its step is open, and in that time the shape can be
     * replaced by a library pick, an import, a project being opened, or – for the subject's box –
     * by the subject moving. A tool that read its rings once puts handles on a shape that is no longer
     * there.
     */
    private final javafx.beans.value.ChangeListener<Geometry> watch = (o, was, is) -> targetReplaced();

    private void targetReplaced() {
        if (writing) {
            return;
        }
        read();
        dragRing = dragVertex = -1;
        changed.run();
    }

    /** The shape as rings, which are what is edited; see {@link RingModel}. */
    private final RingModel rings = new RingModel();
    /** Where their handles are, and which of them show; see {@link HandleLayout}. */
    private final HandleLayout layout;
    /** Which ring and which of its vertices is being dragged, or -1. */
    private int dragRing = -1;
    private int dragVertex = -1;
    /** Whether what is dragged is a rectangle's side, from {@link #dragVertex} to the next one. */
    private boolean dragSide;
    private double cursorX = Double.NaN, cursorY = Double.NaN;

    public EditTool(final @NotNull Slot slot, final @NotNull Mode mode,
                    final @NotNull ObjectProperty<Geometry> target,
                    final @NotNull Consumer<Geometry> onFinished, final @NotNull Runnable onDone) {
        super(slot, onFinished, onDone);
        this.target = target;
        this.changed = onDone;
        this.mode = mode;
        this.layout = new HandleLayout(rings, mode);
        read();
        target.addListener(watch);
    }

    /**
     * Lets go of the shape.
     * <p>
     * Called only from {@code TileCanvas.setTool}, which is every path by which this tool stops
     * being the installed one. Without the removal each step change would leave another listener
     * on the property, and each of those would redraw.
     */
    @Override
    public void cancel() {
        target.removeListener(watch);
    }

    /** The subject's color as it is now – the project's, which the user can change. */
    private java.util.function.@NotNull IntSupplier subjectRgb =
            () -> net.trdlo.wtf.studio.project.ProjectModel.SUBJECT_RGB;

    /** Where to ask for the subject's color, so the edited outline is drawn as the resting one is. */
    public void setSubjectColor(final java.util.function.@NotNull IntSupplier rgb) {
        subjectRgb = rgb;
    }

    /** The outline at rest, as the screen needs it; see {@link ScreenShape}. */
    private final ScreenShape onScreen = new ScreenShape();

    /** An arrow, not a crosshair: editing is what the step does, not a mode you switched on. */
    @Override
    public @NotNull javafx.scene.Cursor cursor() {
        return javafx.scene.Cursor.DEFAULT;
    }

    /** Writes to the target without waking {@link #watch}. */
    private void put(final @Nullable Geometry g) {
        writing = true;
        try {
            target.set(g);
        } finally {
            writing = false;
        }
    }

    @Override
    public @NotNull String hintKey() {
        return rings.isPoint() ? "area.hint.editPoint"
                : rings.isRectangle() ? "area.hint.editRectangle" : "area.hint.editPolygon";
    }

    /** The same answer for a shape no tool has read yet, so a panel can say it without building one. */
    public static @NotNull String hintKeyFor(final @Nullable Geometry g) {
        return Shapes.isPoint(g) ? "area.hint.editPoint"
                : Shapes.isRectangle(g) ? "area.hint.editRectangle" : "area.hint.editPolygon";
    }

    /** Whether there is anything here to edit at all, for a tool that may edit anything. */
    public static boolean isEditable(final @Nullable Geometry g) {
        return isEditable(g, Mode.FULL);
    }

    /**
     * Whether there is anything here that this mode may edit.
     * <p>
     * A rectangle-only editor over a shape that is not a rectangle shows nothing rather than
     * showing handles that would have to break the shape to move. That is the fit under "the
     * outline itself" or a convex hull: it follows the subject's own edge, and the way to change
     * it is to change the subject, or to choose Custom.
     */
    public static boolean isEditable(final @Nullable Geometry g, final @NotNull Mode mode) {
        return mode == Mode.RECTANGLE
                ? Shapes.isRectangle(g)
                : g instanceof Polygonal && !g.isEmpty();
    }

    /** Reads the shape again: its rings, when this mode may edit it, and no part under the pointer. */
    private void read() {
        layout.clearHover();
        final Geometry g = target.get();
        rings.read(isEditable(g, mode) ? g : null);
    }

    // -------------------------------------------------------------------------------------------
    // Mouse
    // -------------------------------------------------------------------------------------------

    @Override
    public boolean pressed(final @NotNull MouseEvent e, final @NotNull Camera camera) {
        cursorX = e.getX();
        cursorY = e.getY();
        if (rings.isEmpty()) {
            return false;
        }
        // From the press itself, not from the last move: a press is where the pointer is, and a
        // handle must not depend on the mouse having been moved there first
        hover(e.getX(), e.getY(), camera);
        if (e.getButton() == MouseButton.SECONDARY) {
            return e.isControlDown() ? removePartAt(e, camera) : removeVertexAt(e, camera);
        }
        if (e.getButton() != MouseButton.PRIMARY) {
            return false;
        }
        final int[] hit = layout.vertexAt(e.getX(), e.getY(), camera);
        if (hit != null) {
            dragRing = hit[0];
            dragVertex = hit[1];
            dragSide = false;
            return true;
        }
        final int[] edge = layout.midpointAt(e.getX(), e.getY(), camera);
        if (edge != null && rings.get(edge[0]).box) {
            // A rectangle's side, moved as a whole: a fifth vertex on a ring still read as a box
            // could not follow the drag, since a box moves by its four corners
            dragRing = edge[0];
            dragVertex = edge[1];
            dragSide = true;
            return true;
        }
        if (edge != null && !rings.isPoint()) {
            // A new vertex, born where the midpoint handle was and dragged from there
            rings.insertAfter(edge[0], edge[1], lon(e, camera), lat(e, camera));
            dragRing = edge[0];
            dragVertex = edge[1] + 1;
            dragSide = false;
            return true;
        }
        return false;       // not on the shape: let the canvas pan
    }

    @Override
    public boolean dragged(final @NotNull MouseEvent e, final @NotNull Camera camera) {
        cursorX = e.getX();
        cursorY = e.getY();
        if (rings.isEmpty() || dragRing < 0) {
            return false;
        }
        final double lon = lon(e, camera);
        final double lat = lat(e, camera);
        if (rings.isPoint()) {
            // Moved here and written once, when it is let go, like every other handle. Written on
            // every mouse move, each write would work the area out again, and each would be a step
            // of undo of its own. Until the release the map still shows the pin
            // where it was, as it shows the old outline under a dragged vertex.
            rings.movePin(lon, lat);
        } else if (dragSide) {
            dragVertex = rings.moveEdge(dragRing, dragVertex, lon, lat);
        } else if (rings.get(dragRing).box) {
            dragVertex = rings.moveCorner(dragRing, dragVertex, lon, lat);
        } else {
            rings.moveVertex(dragRing, dragVertex, lon, lat);
        }
        return true;
    }

    @Override
    public boolean released(final @NotNull MouseEvent e, final @NotNull Camera camera) {
        if (dragRing < 0) {
            return false;
        }
        dragRing = dragVertex = -1;
        write();
        hover(e.getX(), e.getY(), camera);
        return true;
    }

    @Override
    public boolean moved(final @NotNull MouseEvent e, final @NotNull Camera camera) {
        final boolean wasHot = layout.hotHandle(cursorX, cursorY, camera);
        cursorX = e.getX();
        cursorY = e.getY();
        final boolean changed = hover(e.getX(), e.getY(), camera);
        return changed || wasHot != layout.hotHandle(cursorX, cursorY, camera);
    }

    @Override
    public boolean exited() {
        cursorX = cursorY = Double.NaN;
        if (layout.hoversNothing() || dragRing >= 0) {
            return false;
        }
        layout.clearHover();
        return true;
    }

    /**
     * Works out which parts are under the pointer, as {@link HandleLayout#hover} does – except
     * while a vertex is being dragged, when the answer does not change: the handles being dragged
     * stay.
     *
     * @return whether the answer changed
     */
    private boolean hover(final double x, final double y, final @NotNull Camera camera) {
        if (dragRing >= 0) {
            return false;
        }
        return layout.hover(x, y, camera);
    }

    @Override
    public boolean clicked(final @NotNull MouseEvent e, final @NotNull Camera camera) {
        // Swallow the click that ends a drag, so it does not also reach the canvas as a zoom
        return !rings.isEmpty() && layout.vertexAt(e.getX(), e.getY(), camera) != null;
    }

    @Override
    public boolean keyPressed(final javafx.scene.input.@NotNull KeyEvent e) {
        if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
            read();             // back to what is in the model, throwing away an unfinished drag
            dragRing = dragVertex = -1;
            changed.run();
            return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------------------------
    // The edits
    // -------------------------------------------------------------------------------------------

    /**
     * Right-click takes a vertex out, down to the triangle below which there is no ring left –
     * and then the ring itself goes, which is how an island or a lake is removed one corner at a
     * time. A rectangle has no vertex it can spare, and a point is not made of vertices at all.
     */
    private boolean removeVertexAt(final @NotNull MouseEvent e, final @NotNull Camera camera) {
        if (rings.isPoint() || mode == Mode.RECTANGLE) {
            return false;
        }
        final int[] hit = layout.vertexAt(e.getX(), e.getY(), camera);
        if (hit == null) {
            return false;
        }
        rings.removeVertex(hit[0], hit[1]);
        write();
        return true;
    }

    /**
     * Control and a right-click take a whole part out.
     * <p>
     * Seventy-four clicks to drop an Aegean island is not editing. This is also how a subject
     * built out of several shapes loses one of them again – the bin empties the slot, and this
     * empties one part of it.
     */
    private boolean removePartAt(final @NotNull MouseEvent e, final @NotNull Camera camera) {
        if (rings.isPoint() || mode == Mode.RECTANGLE) {
            return false;
        }
        final int[] hit = layout.vertexAt(e.getX(), e.getY(), camera);
        if (hit == null) {
            return false;
        }
        rings.removePart(hit[0]);
        write();
        return true;
    }

    /**
     * Rebuilds the geometry from the rings, keeping every part and every hole where it was – with
     * an edge dragged longer than half the world split where the map draws it, as the polygon tool
     * splits one drawn that long, so that the file does not take it across the antimeridian.
     * <p>
     * Only what the edit touched is made again ({@link RingModel#shape}), so the step of undo this
     * write makes shares the rest with the one before it; and a handle let go where it was pressed
     * touched nothing, so the shape put back is the one already there, and nothing is written.
     */
    private void write() {
        final Geometry edited = rings.shape();
        put(edited == null ? null : Shapes.splitLongEdges(edited));
        read();
        changed.run();
    }

    // -------------------------------------------------------------------------------------------
    // Drawing
    // -------------------------------------------------------------------------------------------

    /** How far above the coordinate the pin's head sits. The point of it marks the place. */
    static final double PIN_HEAD_ABOVE = 13;
    private static final double PIN_HEAD_RADIUS = 7;

    @Override
    public void draw(final @NotNull GraphicsContext g, final @NotNull Camera camera) {
        if (rings.isEmpty()) {
            return;
        }
        if (rings.isPoint()) {
            drawPin(g, screen(rings.center(), camera));
            return;
        }
        g.setStroke(line());
        g.setLineWidth(1.5);
        g.setFill(slot() == Slot.AREA ? ProjectOverlay.FILL_TINT
                : ProjectOverlay.subjectFill(subjectRgb.getAsInt()));
        final Geometry shape = target.get();
        if (dragRing < 0 && shape != null) {
            // At rest the outline is the shape, simplified to the zoom and cut to the view: a
            // surveyed border is a hundred thousand points, and tracing them all on every frame is
            // what made panning over one crawl. See ScreenShape.
            g.beginPath();
            onScreen.append(g, shape, camera);
            g.setFillRule(javafx.scene.shape.FillRule.EVEN_ODD);
            if (fillsAtRest()) {
                g.fill();
            }
            g.setFillRule(javafx.scene.shape.FillRule.NON_ZERO);
            g.stroke();
        } else {
            // Mid-drag the rings are ahead of the shape – they are what is being moved – so they
            // are traced as they stand, every point, until the release writes them back
            for (final Ring ring : rings.rings()) {
                g.beginPath();
                for (int i = 0; i < ring.points.size(); i++) {
                    final double[] p = screen(ring.points.get(i), camera);
                    if (i == 0) {
                        g.moveTo(p[0], p[1]);
                    } else {
                        g.lineTo(p[0], p[1]);
                    }
                }
                g.closePath();
                if (!ring.hole) {
                    g.fill();
                }
                g.stroke();
            }
        }

        // The midpoints first, so a vertex handle is never hidden under one. A rectangle in a
        // slot has them too, and there one moves its side, keeping the box. The subject's box in
        // the area step has none of them: its editor drags the corners and nothing else.
        g.setFill(MIDPOINT);
        for (final Ring ring : mode == Mode.RECTANGLE ? List.<Ring>of() : rings.rings()) {
            if (!layout.isHovered(ring.part)) {
                continue;
            }
            for (int i = 0; i < ring.points.size(); i++) {
                final double[] a = screen(ring.points.get(i), camera);
                if (!inView(a, camera)) {
                    continue;
                }
                final double[] b = screen(ring.points.get((i + 1) % ring.points.size()), camera);
                if (hasRoomBetween(a, b)) {
                    g.fillOval((a[0] + b[0]) / 2 - 2.5, (a[1] + b[1]) / 2 - 2.5, 5, 5);
                }
            }
        }
        for (int r = 0; r < rings.size(); r++) {
            if (!layout.isHovered(rings.get(r).part)) {
                continue;
            }
            final List<Coordinate> ring = rings.get(r).points;
            for (int i = 0; i < ring.size(); i++) {
                final double[] p = screen(ring.get(i), camera);
                // Off screen first: one comparison, before the spacing test that looks at both
                // neighbors – zoomed in, nearly all of a border's vertices are off screen
                if (!inView(p, camera) || !layout.isVisible(r, i, camera)) {
                    continue;
                }
                drawHandle(g, p[0], p[1], isUnderCursor(p));
            }
        }
    }

    private boolean isUnderCursor(final double @NotNull [] p) {
        return HandleLayout.isUnderCursor(cursorX, cursorY, p);
    }

    /** The color of the shape being edited, which is the color it has when it is not. */
    private @NotNull Color line() {
        return slot() == Slot.AREA ? ProjectOverlay.FILL_LINE : ProjectOverlay.subjectLine(subjectRgb.getAsInt());
    }

    /**
     * Whether the resting shape needs its own fill here.
     * <p>
     * The subject's is {@link ProjectOverlay}'s, drawn once already every frame regardless of what
     * is editing it; filling it again on top would stack two 10 % fills into 19 %. The shape an area
     * grows from has no fill of its own there – the area is a dashed outline – so this is where its
     * translucent tint comes from.
     */
    boolean fillsAtRest() {
        return slot() != Slot.SUBJECT;
    }

    private void drawHandle(final @NotNull GraphicsContext g, final double x, final double y,
                            final boolean hot) {
        final double r = hot ? 5 : 4;
        g.setFill(hot ? line() : Color.WHITE);
        g.fillRect(x - r, y - r, r * 2, r * 2);
        g.setStroke(PIN_EDGE);
        g.setLineWidth(1);
        g.strokeRect(x - r - 0.5, y - r - 0.5, r * 2 + 1, r * 2 + 1);
    }

    /**
     * A map pin: a round head on a tapering stem, with its tip on the coordinate.
     * <p>
     * Not the same four-pixel square as every other handle, which at a country's zoom is a fleck of
     * dust and at any zoom says nothing about which pixel is the place. A pin says both, and it is
     * the shape on the button that made it.
     */
    private static void drawPin(final @NotNull GraphicsContext g, final double @NotNull [] p) {
        final double x = p[0], tip = p[1], cy = tip - PIN_HEAD_ABOVE;
        g.setFill(PIN_BODY);
        g.setStroke(PIN_EDGE);
        g.setLineWidth(1.5);

        g.beginPath();
        g.moveTo(x, tip);
        g.lineTo(x - PIN_HEAD_RADIUS * 0.55, cy + PIN_HEAD_RADIUS * 0.6);
        g.lineTo(x + PIN_HEAD_RADIUS * 0.55, cy + PIN_HEAD_RADIUS * 0.6);
        g.closePath();
        g.fill();
        g.stroke();

        g.fillOval(x - PIN_HEAD_RADIUS, cy - PIN_HEAD_RADIUS, PIN_HEAD_RADIUS * 2, PIN_HEAD_RADIUS * 2);
        g.strokeOval(x - PIN_HEAD_RADIUS, cy - PIN_HEAD_RADIUS, PIN_HEAD_RADIUS * 2, PIN_HEAD_RADIUS * 2);
        g.setFill(PIN_EDGE);
        g.fillOval(x - 2.2, cy - 2.2, 4.4, 4.4);
    }
}
