package net.trdlo.wtf.studio.map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import net.trdlo.wtf.studio.map.EditTool.Mode;
import net.trdlo.wtf.studio.map.RingModel.Ring;
import net.trdlo.wtf.tiles.Mercator;

import java.util.List;

import static net.trdlo.wtf.studio.map.EditTool.HANDLE_SPACING;
import static net.trdlo.wtf.studio.map.EditTool.HIT_RADIUS;
import static net.trdlo.wtf.studio.map.EditTool.HOVER_RADIUS;
import static net.trdlo.wtf.studio.map.EditTool.PIN_HEAD_ABOVE;
import static net.trdlo.wtf.studio.map.EditTool.PIN_HIT_RADIUS;

/**
 * Where the editor's handles are on the screen, which of them show, and what is under the pointer.
 * <p>
 * <b>Handles thin out rather than pile up.</b> An imported outline has thousands of vertices, and
 * at a zoom where the whole country is on screen they are a millimeter apart – a fur of handles
 * that hides the shape they belong to and that no mouse could pick between. A vertex is drawn and
 * grabbable only where both its neighbors are at least {@link EditTool#HANDLE_SPACING} times the
 * hit radius away on screen, so zooming in reveals more of them, exactly where there is room.
 * <p>
 * <b>And only on the part under the pointer.</b> A country with its islands is hundreds of parts,
 * and every one of them wearing its handles at once would make the outline hard to read. A part
 * shows them while the pointer is inside it or within {@link EditTool#HOVER_RADIUS} of its outline
 * – reaching for a shape is what reveals it – and the rule above still decides which of its
 * vertices have room.
 */
final class HandleLayout {

    private final RingModel rings;
    private final Mode mode;
    /** The parts under the pointer, which are the ones with handles; see {@link #hover}. */
    private final java.util.BitSet hovered = new java.util.BitSet();

    HandleLayout(final @NotNull RingModel rings, final @NotNull Mode mode) {
        this.rings = rings;
        this.mode = mode;
    }

    boolean isHovered(final int part) {
        return hovered.get(part);
    }

    boolean hoversNothing() {
        return hovered.isEmpty();
    }

    void clearHover() {
        hovered.clear();
    }

    /**
     * Works out which parts are under the pointer.
     * <p>
     * A part is under it when the pointer is inside its shell or within
     * {@link EditTool#HOVER_RADIUS} of any of its rings – so a thin island is as easy to reach as a
     * country, and a hole's edge reveals the part it is a hole in.
     *
     * @return whether the answer changed
     */
    boolean hover(final double x, final double y, final @NotNull Camera camera) {
        final java.util.BitSet now = new java.util.BitSet();
        for (final Ring ring : rings.rings()) {
            if (!now.get(ring.part) && mayBeWithin(ring, x, y, HOVER_RADIUS, camera) && isNear(ring, x, y, camera)) {
                now.set(ring.part);
            }
        }
        if (now.equals(hovered)) {
            return false;
        }
        hovered.clear();
        hovered.or(now);
        return true;
    }

    private static boolean isNear(final @NotNull Ring ring, final double x, final double y,
                                  final @NotNull Camera camera) {
        final List<Coordinate> points = ring.points;
        boolean inside = false;
        double[] a = screen(points.getLast(), camera);
        for (final Coordinate c : points) {
            final double[] b = screen(c, camera);
            if (distanceToSegment(x, y, a, b) <= HOVER_RADIUS) {
                return true;
            }
            // Ray casting, for inside a shell; a hole's inside is its part's outside, so it does not count
            if (!ring.hole && (a[1] > y) != (b[1] > y)
                    && x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]) {
                inside = !inside;
            }
            a = b;
        }
        return inside;
    }

    private static double distanceToSegment(final double x, final double y,
                                            final double @NotNull [] a, final double @NotNull [] b) {
        final double dx = b[0] - a[0], dy = b[1] - a[1];
        final double lengthSquared = dx * dx + dy * dy;
        final double t = lengthSquared == 0 ? 0
                : Math.max(0, Math.min(1, ((x - a[0]) * dx + (y - a[1]) * dy) / lengthSquared));
        return Math.hypot(x - (a[0] + t * dx), y - (a[1] + t * dy));
    }

    /**
     * Whether the screen point can be within {@code radius} pixels of this ring at all.
     * <p>
     * Asked before any vertex is: without it the hover would walk every vertex of every ring on
     * every mouse move, and the handle test twice more, which for a surveyed border is a few
     * hundred thousand projections to learn that the pointer is over another country.
     */
    static boolean mayBeWithin(final @NotNull Ring ring, final double x, final double y, final double radius,
                               final @NotNull Camera camera) {
        final Envelope e = ring.bounds();
        if (e.isNull()) {
            return false;
        }
        final double west = camera.toScreenX(Mercator.lonToUnitX(e.getMinX()));
        final double east = camera.toScreenX(Mercator.lonToUnitX(e.getMaxX()));
        final double north = camera.toScreenY(Mercator.latToUnitY(e.getMaxY()));
        final double south = camera.toScreenY(Mercator.latToUnitY(e.getMinY()));
        return x >= west - radius && x <= east + radius && y >= north - radius && y <= south + radius;
    }

    /** Whether a handle is under the pointer now, which is drawn larger and in the shape's color. */
    boolean hotHandle(final double cursorX, final double cursorY, final @NotNull Camera camera) {
        if (Double.isNaN(cursorX) || rings.isEmpty() || rings.isPoint()) {
            return false;
        }
        for (int r = 0; r < rings.size(); r++) {
            if (!hovered.get(rings.get(r).part)
                    || !mayBeWithin(rings.get(r), cursorX, cursorY, HIT_RADIUS, camera)) {
                continue;
            }
            final List<Coordinate> ring = rings.get(r).points;
            for (int i = 0; i < ring.size(); i++) {
                final double[] p = screen(ring.get(i), camera);
                if (isUnderCursor(cursorX, cursorY, p) && isVisible(r, i, camera)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** @return {@code {ring, vertex}}, or null when the pointer is on none of them */
    int @Nullable [] vertexAt(final double x, final double y, final @NotNull Camera camera) {
        if (rings.isEmpty()) {
            return null;
        }
        if (rings.isPoint()) {
            final double[] p = screen(rings.center(), camera);
            // The pin's point is at the coordinate and its head is above it, so the target is too
            return Math.hypot(x - p[0], y - (p[1] - PIN_HEAD_ABOVE)) <= PIN_HIT_RADIUS
                    ? new int[]{0, 0} : null;
        }
        for (int r = 0; r < rings.size(); r++) {
            if (!hovered.get(rings.get(r).part)) {
                continue;
            }
            final List<Coordinate> ring = rings.get(r).points;
            for (int i = 0; i < ring.size(); i++) {
                if (!isVisible(r, i, camera)) {
                    continue;
                }
                final double[] p = screen(ring.get(i), camera);
                if (Math.hypot(x - p[0], y - p[1]) <= HIT_RADIUS) {
                    return new int[]{r, i};
                }
            }
        }
        return null;
    }

    /** @return {@code {ring, vertex}} for the edge starting there, or null when the click missed */
    int @Nullable [] midpointAt(final double x, final double y, final @NotNull Camera camera) {
        if (rings.isPoint() || mode == Mode.RECTANGLE) {
            return null;
        }
        for (int r = 0; r < rings.size(); r++) {
            if (!hovered.get(rings.get(r).part)) {
                continue;
            }
            final List<Coordinate> ring = rings.get(r).points;
            for (int i = 0; i < ring.size(); i++) {
                final double[] a = screen(ring.get(i), camera);
                final double[] b = screen(ring.get((i + 1) % ring.size()), camera);
                if (!hasRoomBetween(a, b)) {
                    continue;
                }
                if (Math.hypot(x - (a[0] + b[0]) / 2, y - (a[1] + b[1]) / 2) <= HIT_RADIUS) {
                    return new int[]{r, i};
                }
            }
        }
        return null;
    }

    static boolean hasRoomBetween(final double @NotNull [] a, final double @NotNull [] b) {
        return Math.hypot(a[0] - b[0], a[1] - b[1]) >= HANDLE_SPACING * HIT_RADIUS * 2;
    }

    /**
     * Whether this vertex is far enough from both neighbors to be worth drawing.
     * <p>
     * The rule is about the screen, not the geometry: the same vertex of the same country is
     * hidden at one zoom and grabbable at the next, which is what makes a four-thousand-point
     * outline editable at all.
     */
    boolean isVisible(final int r, final int index, final @NotNull Camera camera) {
        final List<Coordinate> ring = rings.get(r).points;
        // One rule for every vertex of every ring, whatever its size. Exempting small rings, so
        // that a drawn triangle keeps its corners, would show Europe from the library, which is
        // hundreds of small islands, as a clump of handles on each one.
        final double needed = HANDLE_SPACING * HIT_RADIUS;
        final double[] here = screen(ring.get(index), camera);
        final double[] before = screen(ring.get((index - 1 + ring.size()) % ring.size()), camera);
        final double[] after = screen(ring.get((index + 1) % ring.size()), camera);
        return Math.hypot(here[0] - before[0], here[1] - before[1]) >= needed
                && Math.hypot(here[0] - after[0], here[1] - after[1]) >= needed;
    }

    /** Whether a screen point is on the canvas, with room for a handle at the edge. */
    static boolean inView(final double @NotNull [] p, final @NotNull Camera camera) {
        return p[0] >= -8 && p[1] >= -8 && p[0] <= camera.viewWidth() + 8 && p[1] <= camera.viewHeight() + 8;
    }

    /** Whether the pointer is on the handle at this screen point; nowhere, while it is off the map. */
    static boolean isUnderCursor(final double cursorX, final double cursorY, final double @NotNull [] p) {
        return !Double.isNaN(cursorX) && Math.hypot(cursorX - p[0], cursorY - p[1]) <= HIT_RADIUS;
    }

    static double @NotNull [] screen(final @NotNull Coordinate c, final @NotNull Camera camera) {
        return new double[]{camera.toScreenX(Mercator.lonToUnitX(c.x)),
                camera.toScreenY(Mercator.latToUnitY(c.y))};
    }
}
