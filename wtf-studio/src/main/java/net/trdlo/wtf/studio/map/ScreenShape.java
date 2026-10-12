package net.trdlo.wtf.studio.map;

import javafx.scene.canvas.GraphicsContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;
import net.trdlo.wtf.tiles.Mercator;

import java.util.ArrayList;
import java.util.List;

/**
 * A shape as the screen needs it: no finer than a pixel, and no more of it than is in view.
 * <p>
 * <b>Why this exists.</b> A surveyed border is a hundred and twenty-five thousand points – a vertex
 * every eight meters – and the map traced every one of them on every frame, for the outline, the
 * fill and again for the treatment clip. At z8 a pixel is four hundred meters, so fifty of those
 * vertices landed on each pixel and the pan crawled. Two things fix it, and they are the two
 * halves of this class:
 * <ul>
 *   <li><b>Simplified to the zoom.</b> Once per zoom level, each ring is simplified so that no
 *       point moves by more than half a pixel. That is invisible by definition, and at z8 it is a
 *       few thousand points instead of a hundred thousand.</li>
 *   <li><b>Clipped to the view.</b> Zoomed in, simplification removes almost nothing, but almost
 *       none of the border is on screen. Each frame, each ring is cut to the view plus a margin –
 *       one linear pass, Sutherland-Hodgman against a rectangle – and only that is traced.</li>
 * </ul>
 * <p>
 * <b>For drawing only.</b> The export and the tile plan use the shape itself. A half-pixel
 * simplification is exact for a screen and wrong for a sheet of paper at forty times the
 * resolution.
 * <p>
 * The clip is exact for fills and for clipping regions, not just for strokes: clipping a polygon
 * against a convex window yields a polygon that covers precisely the part inside the window. The
 * edges it adds run along the margin, outside the canvas, so a stroke never shows them.
 * <p>
 * <b>Shells run counterclockwise on the screen, holes clockwise</b>, whichever way the shape gave
 * them. A canvas clips by the non-zero rule, whatever its fill rule says, so only rings running
 * opposite ways make a hole: a lake in the subject, and the subject itself in a clip of the whole
 * canvas – a rectangle traced clockwise – minus it.
 */
final class ScreenShape {

    /** No point moves further than this, in screen pixels. Half a pixel is below what can be seen. */
    private static final double TOLERANCE_PX = 0.5;

    /** How far past the canvas edge the rings are kept, so a thick stroke is not cut at the edge. */
    private static final double MARGIN_PX = 16;

    private static final org.locationtech.jts.geom.GeometryFactory LINES = new org.locationtech.jts.geom.GeometryFactory();

    /** One ring in the unit square, simplified for one zoom band. */
    private record Ring(double @NotNull [] xy) { }

    /** Whether the shapes come in longitude and latitude, as the model holds them, or in the unit square already. */
    private final boolean inDegrees;

    private @Nullable Geometry source;
    private int band = Integer.MIN_VALUE;
    private @NotNull List<Ring> rings = List.of();

    /** For shapes in longitude and latitude. */
    ScreenShape() {
        this(true);
    }

    private ScreenShape(final boolean inDegrees) {
        this.inDegrees = inDegrees;
    }

    /** For shapes in the unit square, as the tile plan gives what a print prints. */
    static @NotNull ScreenShape inUnitSquare() {
        return new ScreenShape(false);
    }

    /**
     * Appends the shape's rings to the current path, simplified and clipped for this view.
     * <p>
     * Only the rings: the caller begins the path, and fills, strokes or clips it.
     */
    void append(final @NotNull GraphicsContext g, final @NotNull Geometry shape, final @NotNull Camera camera) {
        prepare(shape, camera);
        final double ppu = camera.pixelsPerUnit();
        final double margin = MARGIN_PX / ppu;
        final double x0 = camera.toUnitX(0) - margin, y0 = camera.toUnitY(0) - margin;
        final double x1 = camera.toUnitX(camera.viewWidth()) + margin;
        final double y1 = camera.toUnitY(camera.viewHeight()) + margin;
        for (final Ring r : rings) {
            final double[] clipped = clip(r.xy(), x0, y0, x1, y1);
            if (clipped.length < 6) {
                continue;
            }
            for (int i = 0; i < clipped.length; i += 2) {
                final double sx = camera.toScreenX(clipped[i]), sy = camera.toScreenY(clipped[i + 1]);
                if (i == 0) {
                    g.moveTo(sx, sy);
                } else {
                    g.lineTo(sx, sy);
                }
            }
            g.closePath();
        }
    }

    /** How many points this view would trace – for the tests, which is how the gain is known. */
    int pointsFor(final @NotNull Geometry shape, final @NotNull Camera camera) {
        prepare(shape, camera);
        final double margin = MARGIN_PX / camera.pixelsPerUnit();
        int n = 0;
        for (final Ring r : rings) {
            n += clip(r.xy(), camera.toUnitX(0) - margin, camera.toUnitY(0) - margin,
                    camera.toUnitX(camera.viewWidth()) + margin,
                    camera.toUnitY(camera.viewHeight()) + margin).length / 2;
        }
        return n;
    }

    /**
     * Projects and simplifies again only when the shape or the zoom band has changed.
     * <p>
     * The band is the power of two the scale falls in, and the tolerance is set for the finest
     * scale in it – so within a band nothing ever moves by more than half a pixel, and a pan or a
     * fractional zoom inside it reuses the same rings.
     */
    private void prepare(final @NotNull Geometry shape, final @NotNull Camera camera) {
        final int b = (int) Math.floor(Math.log(camera.pixelsPerUnit()) / Math.log(2));
        if (shape == source && b == band) {
            return;
        }
        final double tolerance = TOLERANCE_PX / Math.pow(2, b + 1);
        final List<Ring> out = new ArrayList<>();
        for (int i = 0; i < shape.getNumGeometries(); i++) {
            if (!(shape.getGeometryN(i) instanceof final Polygon p) || p.isEmpty()) {
                continue;
            }
            add(out, p.getExteriorRing().getCoordinates(), tolerance, false, inDegrees);
            for (int h = 0; h < p.getNumInteriorRing(); h++) {
                add(out, p.getInteriorRingN(h).getCoordinates(), tolerance, true, inDegrees);
            }
        }
        source = shape;
        band = b;
        rings = out;
    }

    private static void add(final @NotNull List<Ring> out, final @NotNull Coordinate @NotNull [] given,
                            final double tolerance, final boolean hole, final boolean inDegrees) {
        final Coordinate[] unit = new Coordinate[given.length];
        for (int i = 0; i < given.length; i++) {
            unit[i] = inDegrees
                    ? new Coordinate(Mercator.lonToUnitX(given[i].x), Mercator.latToUnitY(given[i].y))
                    : new Coordinate(given[i].x, given[i].y);
        }
        // As a line, not a ring: the polygon simplifier repairs what it produces with a buffer,
        // which on a hundred thousand points is the slow thing this class exists to avoid, and a
        // drawing does not need a valid polygon – only one that looks like the shape.
        final Coordinate[] simple = DouglasPeuckerSimplifier.simplify(
                LINES.createLineString(unit), tolerance).getCoordinates();
        // An island smaller than a pixel simplifies to a line or a point; it has nothing to show
        if (simple.length < 4) {
            return;
        }
        final int n = simple.length - 1;                            // open: the path closes itself
        // Twice the area, positive for a ring that runs clockwise down the screen, as y does
        double area = 0;
        for (int i = 0; i < n; i++) {
            area += simple[i].x * simple[i + 1].y - simple[i + 1].x * simple[i].y;
        }
        final boolean turn = hole ? area < 0 : area > 0;
        final double[] xy = new double[n * 2];
        for (int i = 0; i < n; i++) {
            final Coordinate c = simple[turn ? n - 1 - i : i];
            xy[2 * i] = c.x;
            xy[2 * i + 1] = c.y;
        }
        out.add(new Ring(xy));
    }

    // -------------------------------------------------------------------------------------------

    /** A ring cut to a rectangle, one edge of the rectangle at a time. Package-private for tests. */
    static double @NotNull [] clip(final double @NotNull [] xy, final double x0, final double y0,
                                   final double x1, final double y1) {
        // Nothing to do when the whole ring is inside, which zoomed out is every ring
        boolean inside = true;
        for (int i = 0; i < xy.length && inside; i += 2) {
            inside = xy[i] >= x0 && xy[i] <= x1 && xy[i + 1] >= y0 && xy[i + 1] <= y1;
        }
        if (inside) {
            return xy;
        }
        double[] r = xy;
        r = edge(r, 0, x0, true);
        r = edge(r, 0, x1, false);
        r = edge(r, 1, y0, true);
        r = edge(r, 1, y1, false);
        return r;
    }

    /**
     * One pass of Sutherland-Hodgman: keeps what is on the inner side of one axis-aligned line.
     *
     * @param axis 0 for x, 1 for y
     * @param low  true when the inside is {@code >= limit}
     */
    private static double @NotNull [] edge(final double @NotNull [] in, final int axis, final double limit,
                                           final boolean low) {
        final int n = in.length / 2;
        if (n == 0) {
            return in;
        }
        final double[] out = new double[in.length * 2 + 4];
        int m = 0;
        double px = in[in.length - 2], py = in[in.length - 1];
        boolean pIn = low ? (axis == 0 ? px : py) >= limit : (axis == 0 ? px : py) <= limit;
        for (int i = 0; i < n; i++) {
            final double cx = in[2 * i], cy = in[2 * i + 1];
            final double c = axis == 0 ? cx : cy;
            final boolean cIn = low ? c >= limit : c <= limit;
            if (cIn != pIn) {
                final double p = axis == 0 ? px : py;
                final double t = (limit - p) / (c - p);
                out[m++] = axis == 0 ? limit : px + t * (cx - px);
                out[m++] = axis == 0 ? py + t * (cy - py) : limit;
            }
            if (cIn) {
                out[m++] = cx;
                out[m++] = cy;
            }
            px = cx;
            py = cy;
            pIn = cIn;
        }
        return java.util.Arrays.copyOf(out, m);
    }
}
