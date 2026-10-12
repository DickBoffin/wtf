package net.trdlo.wtf.studio.map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import net.trdlo.wtf.compose.Shapes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * The shape under the editor as the rings it edits, read from a geometry and written back to one.
 * <p>
 * <b>Every part of every shape.</b> A country is not one ring: Greece is seventy-four, Germany
 * twenty-two, and South Africa is one with a hole in it. A tool that took only a single simple
 * polygon would refuse exactly the shapes somebody would want to adjust. Each ring – every island,
 * every lake – is edited in place, and the parts stay parts.
 * <p>
 * <b>A rectangle stays a rectangle.</b> Dragging a corner moves its two neighbors with it, so the
 * thing never shears into a quadrilateral, and a corner dragged past its opposite gives a
 * rectangle the right way round rather than an inside-out ring – which is a shape JTS is entitled
 * to refuse and which nothing downstream should ever have to consider. Dragging the middle of a
 * side moves that side, and the box stays a box that way too.
 * <p>
 * <b>What no edit touched is not made again.</b> Each shape written back is a step of undo, kept
 * for as long as it can be undone, and a surveyed border is 125 000 points in one ring. So a part
 * no edit touched is the polygon it was read as, and a part made again is made of the points it was
 * read with, but for the ones that moved: a step shares with the one before it everything the drag
 * left where it was. That is sound only because a point is never changed once it is made, any more
 * than a shape is – an edit here puts a new point where the old one was – and it has to stay so.
 * Whatever sorted, reversed, normalized or moved the points of a shape in place would change every
 * step that shares them; {@code norm()} and {@code copy()} work on a copy, which is what they are
 * for.
 * <p>
 * In degrees only. Where the rings are on the screen and which of their handles show is
 * {@link HandleLayout}'s; what a gesture does with them is {@link EditTool}'s.
 */
final class RingModel {

    /** Below this a ring is not a ring, and what is left of it goes. */
    private static final int FEWEST_POINTS = 3;

    /**
     * One ring of one part: the shell, or a hole in it. Open – no repeated closing point.
     * <p>
     * <b>Rectangle is a property of the part, not of the shape.</b> A slot can hold a country and
     * a rectangle at once, and the rectangle is still one – it keeps its square corners while it
     * is dragged, and the country beside it does not suddenly acquire them.
     */
    static final class Ring {
        final int part;
        final boolean hole;
        final boolean box;
        final List<Coordinate> points;
        /** The polygon its part was read as, which the part stays until an edit touches it. */
        private final @NotNull Polygon readAs;

        /** Its extent in degrees, worked out when first asked for and again after {@link #moved}. */
        private @Nullable Envelope bounds;

        Ring(final int part, final boolean hole, final boolean box, final List<Coordinate> points,
             final @NotNull Polygon readAs) {
            this.part = part;
            this.hole = hole;
            this.box = box;
            this.points = points;
            this.readAs = readAs;
        }

        @NotNull Envelope bounds() {
            if (bounds == null) {
                final Envelope e = new Envelope();
                for (final Coordinate c : points) {
                    e.expandToInclude(c);
                }
                bounds = e;
            }
            return bounds;
        }

        /** A vertex was moved, added or removed, so the extent is worked out again. */
        void moved() {
            bounds = null;
        }
    }

    private final List<Ring> rings = new ArrayList<>();
    private boolean rectangle;
    private boolean point;
    /** The shape the rings were read from, which is what they make until an edit touches them. */
    private @Nullable Geometry source;
    /** The parts an edit has touched since, by their number in that shape; see {@link #edited}. */
    private final BitSet editedParts = new BitSet();

    /** Reads the rings of every polygon in the shape; null, for a shape that may not be edited, reads none. */
    void read(final @Nullable Geometry g) {
        rings.clear();
        editedParts.clear();
        source = g;
        rectangle = point = false;
        if (g == null) {
            return;
        }
        for (int i = 0; i < g.getNumGeometries(); i++) {
            if (!(g.getGeometryN(i) instanceof final Polygon p) || p.isEmpty()) {
                continue;
            }
            final boolean box = Shapes.isRectangle(p);
            rings.add(new Ring(i, false, box, open(p.getExteriorRing()), p));
            for (int h = 0; h < p.getNumInteriorRing(); h++) {
                rings.add(new Ring(i, true, false, open(p.getInteriorRingN(h)), p));
            }
        }
        rectangle = rings.size() == 1 && rings.getFirst().box;
        point = Shapes.isPoint(g);
    }

    /**
     * A ring without its repeated closing point, which is a point nobody can drag separately.
     * <p>
     * The ring's own points, not copies: a ring made of them again shares every point that did not
     * move with the shape it was read from, and so with the step of undo before it. Which holds
     * because {@code Shapes.GF}'s sequences keep the array they are given, and because nothing here
     * changes a point in place – see the class comment.
     */
    static @NotNull List<Coordinate> open(final @NotNull LinearRing ring) {
        final Coordinate[] closed = ring.getCoordinates();
        return new ArrayList<>(Arrays.asList(closed).subList(0, closed.length - 1));
    }

    /**
     * The geometry the rings make, every part and every hole where it was; null when no part is
     * left.
     * <p>
     * Made again only where an edit touched it: a part no edit touched is the polygon it was read
     * as, holes and all, and with nothing edited this is the very shape that was read – so a handle
     * pressed and let go where it was changes nothing, not even which object the shape is.
     */
    @Nullable Geometry shape() {
        if (editedParts.isEmpty()) {
            return source;
        }
        final List<Polygon> parts = new ArrayList<>();
        int part = -1;
        boolean kept = false;
        LinearRing shell = null;
        final List<LinearRing> holes = new ArrayList<>();
        for (final Ring ring : rings) {
            if (ring.part != part) {
                addPart(parts, shell, holes);
                part = ring.part;
                shell = null;
                holes.clear();
                kept = !editedParts.get(part);
                if (kept) {
                    parts.add(ring.readAs);
                }
            }
            if (kept) {
                continue;
            }
            final LinearRing built = ringOf(ring.points);
            if (built == null) {
                continue;
            }
            if (ring.hole) {
                holes.add(built);
            } else {
                shell = built;
            }
        }
        addPart(parts, shell, holes);

        if (parts.isEmpty()) {
            return null;
        }
        return parts.size() == 1 ? parts.getFirst()
                : Shapes.GF.createMultiPolygon(parts.toArray(new Polygon[0]));
    }

    private static void addPart(final @NotNull List<Polygon> parts, final @Nullable LinearRing shell,
                                final @NotNull List<LinearRing> holes) {
        if (shell != null) {
            parts.add(Shapes.GF.createPolygon(shell, holes.toArray(new LinearRing[0])));
        }
    }

    /** These points as a ring, closed with a copy of the first: no object is two of its points. */
    private static @Nullable LinearRing ringOf(final @NotNull List<Coordinate> open) {
        if (open.size() < FEWEST_POINTS) {
            return null;
        }
        final Coordinate[] closed = new Coordinate[open.size() + 1];
        for (int i = 0; i < open.size(); i++) {
            closed[i] = open.get(i);
        }
        closed[open.size()] = open.getFirst().copy();
        return Shapes.GF.createLinearRing(closed);
    }

    boolean isEmpty() {
        return rings.isEmpty();
    }

    int size() {
        return rings.size();
    }

    @NotNull Ring get(final int r) {
        return rings.get(r);
    }

    /** All of them, parts in order and each part's shell before its holes; not to be changed. */
    @NotNull List<Ring> rings() {
        return rings;
    }

    /** Whether it is one rectangle, which keeps its square corners however it is dragged. */
    boolean isRectangle() {
        return rectangle;
    }

    /** Whether it is a place, which is moved as a whole and has no vertices of its own. */
    boolean isPoint() {
        return point;
    }

    /** Where the pin is: the middle of its ring, which is ahead of the shape while it is dragged. */
    @NotNull Coordinate center() {
        double west = Double.MAX_VALUE, east = -Double.MAX_VALUE;
        double south = Double.MAX_VALUE, north = -Double.MAX_VALUE;
        for (final Coordinate c : rings.getFirst().points) {
            west = Math.min(west, c.x);
            east = Math.max(east, c.x);
            south = Math.min(south, c.y);
            north = Math.max(north, c.y);
        }
        return new Coordinate((west + east) / 2, (south + north) / 2);
    }

    // -------------------------------------------------------------------------------------------
    // The edits, by ring and vertex
    // -------------------------------------------------------------------------------------------

    /**
     * An edit to this ring: its extent is worked out again, and {@link #shape} makes its part again
     * rather than keeping the polygon it was read as. Every edit says so, a removal as much as a
     * move, or the part would come back as it was before the edit.
     */
    private void edited(final @NotNull Ring ring) {
        ring.moved();
        editedParts.set(ring.part);
    }

    /** Moves a vertex to where the pointer is. */
    void moveVertex(final int r, final int vertex, final double lon, final double lat) {
        rings.get(r).points.set(vertex, new Coordinate(lon, lat));
        edited(rings.get(r));
    }

    /** Moves the place, the one ring there is, to where the pointer is. */
    void movePin(final double lon, final double lat) {
        final List<Coordinate> pin = rings.getFirst().points;
        pin.clear();
        pin.addAll(open(Shapes.point(lon, lat).getExteriorRing()));
        edited(rings.getFirst());
    }

    /** A new vertex after this one, where the pointer is: the vertex after it is the new one. */
    void insertAfter(final int r, final int vertex, final double lon, final double lat) {
        rings.get(r).points.add(vertex + 1, new Coordinate(lon, lat));
        edited(rings.get(r));
    }

    /**
     * Moves one corner of one rectangle, and the two beside it, keeping the box a box.
     * <p>
     * Rebuilt from its four numbers rather than nudged, which is what makes a corner dragged past
     * its opposite come out the right way round instead of inside out. Only this part is touched,
     * so a rectangle beside a country stays a rectangle and the country is left alone.
     *
     * @return the corner held now, which is whichever of the four is nearest the pointer – or the
     *         one given, when the ring does not have four
     */
    int moveCorner(final int r, final int corner, final double lon, final double lat) {
        final int held = moveRectangleCorner(rings.get(r), corner, lon, lat);
        edited(rings.get(r));
        return held;
    }

    private static int moveRectangleCorner(final @NotNull Ring ring, final int corner,
                                           final double lon, final double lat) {
        if (ring.points.size() != 4) {
            return corner;
        }
        final Coordinate opposite = ring.points.get((corner + 2) % 4);
        final double west = Math.min(lon, opposite.x), east = Math.max(lon, opposite.x);
        final double south = Math.min(lat, opposite.y), north = Math.max(lat, opposite.y);

        ring.points.clear();
        ring.points.add(new Coordinate(west, north));
        ring.points.add(new Coordinate(east, north));
        ring.points.add(new Coordinate(east, south));
        ring.points.add(new Coordinate(west, south));
        // Follow the corner: it is now whichever of the four is nearest where the pointer is
        return nearest(ring.points, lon, lat);
    }

    /**
     * Moves one side of one rectangle to where the pointer is, keeping the box a box.
     * <p>
     * What pressing the middle of a rectangle's side means: that side, further out or further in,
     * and the two corners on it with it. A side along a parallel moves north or south, one along a
     * meridian east or west, and the opposite side stays. Rebuilt from its four numbers, as a
     * corner is, so a side dragged past its opposite comes out the right way round.
     *
     * @param edge the side from this corner to the next
     * @return the side held now, from the same corner numbering – the one the pointer is on, which
     *         after a drag past the opposite side is the other one; or the one given, when the ring
     *         does not have four
     */
    int moveEdge(final int r, final int edge, final double lon, final double lat) {
        final Ring ring = rings.get(r);
        if (ring.points.size() != 4) {
            return edge;
        }
        final Coordinate a = ring.points.get(edge), b = ring.points.get((edge + 1) % 4);
        final Coordinate opposite = ring.points.get((edge + 2) % 4);
        final Envelope was = ring.bounds();
        double west = was.getMinX(), east = was.getMaxX();
        double south = was.getMinY(), north = was.getMaxY();
        final boolean alongParallel = Math.abs(a.x - b.x) >= Math.abs(a.y - b.y);
        if (alongParallel) {
            south = Math.min(lat, opposite.y);
            north = Math.max(lat, opposite.y);
        } else {
            west = Math.min(lon, opposite.x);
            east = Math.max(lon, opposite.x);
        }
        ring.points.clear();
        ring.points.add(new Coordinate(west, north));
        ring.points.add(new Coordinate(east, north));
        ring.points.add(new Coordinate(east, south));
        ring.points.add(new Coordinate(west, south));
        edited(ring);
        // In that order the sides are north, east, south and west
        return alongParallel ? (lat >= opposite.y ? 0 : 2) : (lon >= opposite.x ? 1 : 3);
    }

    private static int nearest(final @NotNull List<Coordinate> ring, final double lon, final double lat) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < ring.size(); i++) {
            final double d = Math.hypot(ring.get(i).x - lon, ring.get(i).y - lat);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    /** Takes a vertex out, and its ring with it when a triangle is all it was. */
    void removeVertex(final int r, final int vertex) {
        final Ring ring = rings.get(r);
        edited(ring);
        if (ring.points.size() <= FEWEST_POINTS) {
            rings.remove(r);
        } else {
            ring.points.remove(vertex);
        }
    }

    /** Takes out every ring of the part this one belongs to, its shell and its holes. */
    void removePart(final int r) {
        final int part = rings.get(r).part;
        editedParts.set(part);
        rings.removeIf(ring -> ring.part == part);
    }
}
