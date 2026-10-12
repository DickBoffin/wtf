package net.trdlo.wtf.compose;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.algorithm.ConvexHull;
import org.locationtech.jts.algorithm.distance.DiscreteHausdorffDistance;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.util.AffineTransformation;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Mercator;
import net.trdlo.wtf.tiles.Refusal;

import java.util.ArrayList;
import java.util.List;

/**
 * The two shapes of a project and what may be done to them.
 * <p>
 * A project holds an <b>area shape</b> – what the print covers, and the only one simplify and
 * buffer touch – and an optional <b>subject shape</b>, the cut line that splits the print into
 * inside and outside. They are independent: the France-to-Slovakia print with Czechia in color
 * cannot be expressed with one. See docs/design/compose-module.md §2.
 * <p>
 * Everything here is WGS84 degrees in JTS order {@code (x = lon, y = lat)}.
 */
public final class Shapes {

    public static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);

    /** A point becomes this much of a rectangle, so that every shape is an area. */
    public static final double POINT_TO_RECTANGLE_DEGREES = 0.0001;

    /** Meters per degree of latitude. Constant enough for a print rounded to a tile boundary. */
    public static final double METERS_PER_DEGREE_LATITUDE = 111_320.0;

    public enum Simplify {
        /** The shape as drawn. */
        NONE,
        /** Its bounding box – the common case, because a wall is a rectangle. */
        BOUNDING_BOX,
        /** Its convex hull: a map of the United States had better contain the Gulf of Mexico. */
        CONVEX_HULL
    }

    /**
     * Which of the two shapes a refusal is about. A role and not a word, so a window can name the
     * shape in a sentence of its own language; its English is what a command line prints.
     */
    public enum Role {
        AREA("area shape"),
        SUBJECT("subject shape");

        private final String english;

        Role(final @NotNull String english) {
            this.english = english;
        }

        @Override
        public @NotNull String toString() {
            return english;
        }
    }

    private Shapes() {
    }

    // -------------------------------------------------------------------------------------------
    // Building
    // -------------------------------------------------------------------------------------------

    public static @NotNull Polygon rectangle(final @NotNull LonLatBounds b) {
        return GF.createPolygon(new Coordinate[]{
                new Coordinate(b.west(), b.north()),
                new Coordinate(b.east(), b.north()),
                new Coordinate(b.east(), b.south()),
                new Coordinate(b.west(), b.south()),
                new Coordinate(b.west(), b.north())});
    }

    /** A clicked point, widened into the smallest rectangle so that everything downstream is an area. */
    public static @NotNull Polygon point(final double lon, final double lat) {
        final double d = POINT_TO_RECTANGLE_DEGREES;
        return rectangle(new LonLatBounds(lon - d, lat - d, lon + d, lat + d));
    }

    /**
     * Whether this is one of the axis-aligned rectangles {@link #rectangle} makes.
     * <p>
     * Asked by the editor, which has to know what it is holding: a rectangle's handles move its
     * two neighbors with them so that it stays a rectangle, and a polygon's do not. Recognized by
     * its shape rather than remembered in a flag, because a project file read back has no flag
     * and a rectangle drawn last year is still a rectangle.
     */
    public static boolean isRectangle(final @Nullable Geometry g) {
        if (!(g instanceof final Polygon p) || p.getNumInteriorRing() > 0) {
            return false;
        }
        final Coordinate[] ring = p.getExteriorRing().getCoordinates();
        if (ring.length != 5) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            final Coordinate a = ring[i], b = ring[(i + 1) % 4];
            // Every edge runs along a parallel or a meridian, within a rounding error
            if (Math.abs(a.x - b.x) > AXIS_TOLERANCE_DEGREES
                    && Math.abs(a.y - b.y) > AXIS_TOLERANCE_DEGREES) {
                return false;
            }
        }
        return true;
    }

    /** True for what {@link #point} makes: a rectangle too small to be anything but a place. */
    public static boolean isPoint(final @Nullable Geometry g) {
        if (!isRectangle(g)) {
            return false;
        }
        final org.locationtech.jts.geom.Envelope e = g.getEnvelopeInternal();
        return e.getWidth() <= POINT_TO_RECTANGLE_DEGREES * 4
                && e.getHeight() <= POINT_TO_RECTANGLE_DEGREES * 4;
    }

    /**
     * A degree of slack, in degrees, when deciding whether an edge is axis-aligned.
     * <p>
     * A tenth of a millionth of a degree is a centimeter of ground; rectangles come out of
     * arithmetic on doubles and are written to a project file as decimals, so exact equality is
     * the wrong question to ask of them.
     */
    private static final double AXIS_TOLERANCE_DEGREES = 1e-7;

    public static @NotNull Polygon polygon(final @NotNull Coordinate @NotNull [] ring) {
        final Coordinate[] closed = closeRing(ring);
        return GF.createPolygon(closed);
    }

    private static @NotNull Coordinate @NotNull [] closeRing(final @NotNull Coordinate @NotNull [] ring) {
        if (ring.length >= 2 && !ring[0].equals2D(ring[ring.length - 1])) {
            final Coordinate[] closed = new Coordinate[ring.length + 1];
            System.arraycopy(ring, 0, closed, 0, ring.length);
            closed[ring.length] = new Coordinate(ring[0]);
            return closed;
        }
        return ring;
    }

    /**
     * Two shapes as one, keeping both whole.
     * <p>
     * <b>Added, not merged.</b> A subject is often several things – Germany and Slovakia, a
     * country and a rectangle over the bit that matters, an imported file with a hand-drawn
     * island added to it – and each of them stays a part of its own so it can be edited or
     * removed later. They are made one shape by {@link #unioned}, and that happens once, on the
     * way into the engine.
     */
    public static @NotNull Geometry combine(final @Nullable Geometry existing, final @NotNull Geometry added) {
        if (existing == null || existing.isEmpty()) {
            return added;
        }
        final List<Polygon> parts = new ArrayList<>();
        collectPolygons(existing, parts);
        collectPolygons(added, parts);
        return parts.size() == 1 ? parts.getFirst()
                : GF.createMultiPolygon(parts.toArray(new Polygon[0]));
    }

    /**
     * Adds one shape to a slot's parts, checking the newcomer and nothing else.
     * <p>
     * <b>The parts are not validated together, on purpose.</b> Two parts that overlap make a
     * multipolygon that JTS calls invalid, and the standard repair for an invalid geometry –
     * {@code buffer(0)} – is a union. Validating the combination therefore merged Germany into
     * the rectangle drawn over it the moment the rectangle arrived, which is the union this slot
     * exists to postpone. Each part is checked on its own way in; the overlaps are resolved once,
     * by {@link #unioned}, on the way to the engine.
     */
    public static @NotNull Geometry withPart(final @Nullable Geometry existing, final @NotNull Geometry added,
                                             final @NotNull Role what) {
        return combine(existing, validate(added, what));
    }

    private static void collectPolygons(final @NotNull Geometry g, final @NotNull List<Polygon> out) {
        for (int i = 0; i < g.getNumGeometries(); i++) {
            if (g.getGeometryN(i) instanceof final Polygon p && !p.isEmpty()) {
                out.add(p);
            }
        }
    }

    /**
     * The parts as one area, overlaps and all.
     * <p>
     * Parts are kept separate while they are being edited and are only ever asked to be one shape
     * here – which is the last possible moment, and the only one at which two overlapping parts
     * would otherwise produce a tile counted twice or an outline drawn through the middle of the
     * print.
     * <p>
     * The union is a shape WTF makes, so its long edges are split ({@link #splitLongEdges}): a box
     * wider than half the world joined with a part poking out of it is four corners no more.
     */
    public static @NotNull Geometry unioned(final @NotNull Geometry g) {
        return g.getNumGeometries() <= 1 ? g : splitLongEdges(g.union());
    }

    /**
     * Checks a shape on the way in, so that a bad polygon is a message rather than an unpredictable
     * clip on export.
     * <p>
     * <b>A copy comes back; the shape handed in is left as it was.</b> JTS normalizes in place, and
     * the argument is often the subject the model holds, the map draws and the undo stack keeps.
     * The stack holds shapes by reference and relies on nobody changing one after it is made, so a
     * shape that could be rearranged behind its back would be a snapshot that no longer said what
     * it said when it was taken.
     *
     * @return a normalized copy of the shape, with a {@code buffer(0)} repair applied when it was
     *         invalid
     * @throws IllegalArgumentException when it is empty, or not repairable, or crosses the antimeridian
     */
    public static @NotNull Geometry validate(final @NotNull Geometry g, final @NotNull Role what) {
        checkExtent(g, what);
        Geometry out = g;
        if (!out.isValid()) {
            // The standard repair: it closes self-intersections and joins overlapping parts, and
            // what it gives back is WTF's own shape – a wide box joined with another part is four
            // corners no more – so its long edges are split as the others are
            out = splitLongEdges(out.buffer(0));
            if (out.isEmpty() || !out.isValid()) {
                throw new Refusal(ComposeReason.SHAPE_UNREPAIRABLE, what);
            }
        }
        return out.norm();
    }

    /**
     * A shape WTF has made, with every edge longer than half the world split where the map draws
     * it.
     * <p>
     * <b>What WTF draws, edits or works out is a straight line on the flat map.</b> A polygon
     * clicked from California to the Urals, a vertex dragged that far, the hull or the growth of a
     * subject that wide can have two neighboring points more than 180° of longitude apart – and
     * {@link #checkExtent} takes such an edge the short way, across ±180°, as it has to take one in
     * a file from another program. So the edge gets a point in it, as many as keep each piece
     * within half the world, and the file then says which way round it goes. The point is on the
     * line the map and the print draw, which is straight in the projection, not in degrees: a
     * middle in degrees would bend a slanted edge by degrees of latitude on the screen.
     * <p>
     * The points already there stay; a box keeps its four corners, which are a range and are never
     * read the short way; an edge along ±180° is left, as the check leaves it; and a shape with
     * nothing to split comes back as the very same instance.
     */
    public static @NotNull Geometry splitLongEdges(final @NotNull Geometry g) {
        if (!(g instanceof Polygonal)) {
            return g;
        }
        final Polygon[] parts = new Polygon[g.getNumGeometries()];
        boolean changed = false;
        for (int i = 0; i < parts.length; i++) {
            final Polygon part = (Polygon) g.getGeometryN(i);
            parts[i] = isRectangle(part) ? part : split(part);
            changed |= parts[i] != part;
        }
        if (!changed) {
            return g;
        }
        return g instanceof Polygon ? parts[0] : GF.createMultiPolygon(parts);
    }

    private static @NotNull Polygon split(final @NotNull Polygon p) {
        final List<LinearRing> rings = ringsOf(p);
        boolean changed = false;
        for (int r = 0; r < rings.size(); r++) {
            final LinearRing ring = rings.get(r);
            rings.set(r, split(ring));
            changed |= rings.get(r) != ring;
        }
        return changed
                ? GF.createPolygon(rings.getFirst(), rings.subList(1, rings.size()).toArray(new LinearRing[0]))
                : p;
    }

    private static @NotNull LinearRing split(final @NotNull LinearRing ring) {
        final CoordinateSequence cs = ring.getCoordinateSequence();
        if (firstEdgeAcross(cs) < 0) {
            return ring;
        }
        final List<Coordinate> out = new ArrayList<>(cs.size() + 4);
        out.add(cs.getCoordinateCopy(0));
        for (int i = 1; i < cs.size(); i++) {
            final Coordinate from = cs.getCoordinate(i - 1);
            final Coordinate to = cs.getCoordinateCopy(i);
            if (acrossTheShortWay(from.x, to.x)) {
                final int pieces = (int) Math.floor(Math.abs(to.x - from.x) / 180) + 1;
                for (int k = 1; k < pieces; k++) {
                    out.add(along(from, to, (double) k / pieces));
                }
            }
            out.add(to);
        }
        return GF.createLinearRing(out.toArray(new Coordinate[0]));
    }

    /**
     * The point that far along an edge as the map draws it: straight in Web Mercator, where the
     * longitude is linear and the latitude is not. A level edge keeps its latitude to the bit.
     */
    private static @NotNull Coordinate along(final @NotNull Coordinate from, final @NotNull Coordinate to,
                                             final double fraction) {
        final double lon = from.x + (to.x - from.x) * fraction;
        if (Double.compare(from.y, to.y) == 0) {
            return new Coordinate(lon, from.y);
        }
        final double y = Mercator.latToUnitY(from.y);
        return new Coordinate(lon, Mercator.unitYToLat(y + (Mercator.latToUnitY(to.y) - y) * fraction));
    }

    /** How far past ±180° a longitude may lie and still be on the edge of the world, in degrees. */
    private static final double WORLD_EDGE_SLACK_DEGREES = 0.0001;

    /**
     * The cheap half of {@link #validate}: an area, not empty, inside the world and not across the
     * antimeridian. No repair.
     * <p>
     * <b>Across the antimeridian is decided edge by edge</b> (D22). Two neighboring points more
     * than half the world apart are joined the short way, across ±180°, as a globe joins them and
     * as a file from another program means them – a ring from 179° to −179.5° is an island, not a
     * band 359° wide round the world – so a ring with such an edge is refused. Not an edge whose
     * ends are both on ±180°: it runs along the edge of the map, as the whole world's top and
     * bottom do. Nor a box: its four corners are a range from its west to its east, as a
     * {@link LonLatBounds} is, and the view, a dragged box and the box around a subject can be
     * wider than half the world. What WTF draws, edits or works out itself has its long edges
     * split before it gets here, so that this never bites on it; see {@link #splitLongEdges}.
     */
    public static void checkExtent(final @NotNull Geometry g, final @NotNull Role what) {
        if (g.isEmpty()) {
            throw new Refusal(ComposeReason.SHAPE_EMPTY, what);
        }
        if (!(g instanceof Polygonal)) {
            throw new Refusal(ComposeReason.SHAPE_NOT_AN_AREA, what, g.getGeometryType());
        }
        final Envelope e = g.getEnvelopeInternal();
        if (e.getMinX() < -180 - WORLD_EDGE_SLACK_DEGREES || e.getMaxX() > 180 + WORLD_EDGE_SLACK_DEGREES) {
            throw new Refusal(ComposeReason.SHAPE_LEAVES_WORLD, what, e.getMinX(), e.getMaxX());
        }
        for (int i = 0; i < g.getNumGeometries(); i++) {
            final Polygon part = (Polygon) g.getGeometryN(i);
            if (isRectangle(part)) {
                continue;
            }
            for (final LinearRing ring : ringsOf(part)) {
                final CoordinateSequence cs = ring.getCoordinateSequence();
                final int at = firstEdgeAcross(cs);
                if (at >= 0) {
                    throw new Refusal(ComposeReason.SHAPE_CROSSES_ANTIMERIDIAN, what, cs.getX(at), cs.getX(at + 1));
                }
            }
        }
    }

    /** The shell, then the holes. */
    private static @NotNull List<LinearRing> ringsOf(final @NotNull Polygon p) {
        final List<LinearRing> rings = new ArrayList<>(1 + p.getNumInteriorRing());
        rings.add(p.getExteriorRing());
        for (int h = 0; h < p.getNumInteriorRing(); h++) {
            rings.add(p.getInteriorRingN(h));
        }
        return rings;
    }

    /** Where the first edge of a ring that goes the short way across ±180° starts, or -1. */
    private static int firstEdgeAcross(final @NotNull CoordinateSequence ring) {
        for (int i = 1; i < ring.size(); i++) {
            if (acrossTheShortWay(ring.getX(i - 1), ring.getX(i))) {
                return i - 1;
            }
        }
        return -1;
    }

    /**
     * Whether the short way from one longitude to the next runs across ±180°: they are more than
     * half the world apart, and not both on it. One rule for the check and for the split, so that
     * a split shape always passes.
     */
    private static boolean acrossTheShortWay(final double fromLon, final double toLon) {
        return Math.abs(toLon - fromLon) > 180 && !(onTheAntimeridian(fromLon) && onTheAntimeridian(toLon));
    }

    private static boolean onTheAntimeridian(final double lon) {
        return Math.abs(Math.abs(lon) - 180) <= WORLD_EDGE_SLACK_DEGREES;
    }

    // -------------------------------------------------------------------------------------------
    // Refining the area shape
    // -------------------------------------------------------------------------------------------

    /**
     * Applies simplify then buffer, in that order – the order changes the answer.
     * <p>
     * What comes out is a shape WTF made, so an edge of it longer than half the world is split
     * ({@link #splitLongEdges}): the hull of a subject that wide can have one, and a growth joins
     * the points of a straight line into one edge again.
     *
     * @return the <b>fill shape</b>: the area actually printed, whose envelope becomes the print
     *         rectangle
     */
    public static @NotNull Geometry refine(final @NotNull Geometry area, final @NotNull Simplify simplify,
                                           final double bufferValue) {
        Geometry g = fitted(area, simplify);
        if (bufferValue != 0) {
            // A box grown by ten kilometers is a bigger box, not a box with rounded corners. JTS
            // joins a buffer with arcs, which is right for an outline and wrong for a rectangle –
            // and the rounding shows on the paper as four blank corners.
            //
            // The question is asked of the shape, not of the fit that produced it. Asking the fit
            // would make a rectangular subject come out square-cornered under "bounding box" and
            // round-cornered under "convex hull", when the hull of a rectangle is that rectangle
            // and the two are told to do the same thing.
            final boolean box = isRectangle(g);
            g = buffer(g, bufferValue);
            if (box && !g.isEmpty()) {
                // Not when it is empty: a negative buffer can eat the shape, and the bounds of
                // nothing are an inside-out rectangle that throws about the antimeridian instead
                // of saying what actually happened. The check below says that.
                g = rectangle(boundsOf(g));
            }
        }
        if (g.isEmpty()) {
            throw new Refusal(ComposeReason.REFINING_EMPTIED, bufferValue);
        }
        return splitLongEdges(g);
    }

    private static @NotNull Geometry fitted(final @NotNull Geometry area, final @NotNull Simplify simplify) {
        return switch (simplify) {
            case NONE -> area;
            case BOUNDING_BOX -> rectangle(boundsOf(area));
            case CONVEX_HULL -> new ConvexHull(area).getConvexHull();
        };
    }

    /**
     * The shape an area grows from, before it is grown: a subject's outline, box or hull, or a
     * Custom shape's parts made one under {@link Simplify#NONE} – the edge a fade across the growth
     * starts from ({@link ComposeJob#fadeFrom}). The first half of {@link #areaFrom}, and the same
     * code, for the reason given there: an edge worked out by another sequence would not be the
     * edge the area was grown from.
     *
     * @param base the subject, or a Custom shape
     */
    public static @NotNull Geometry fitOf(final @NotNull Geometry base, final @NotNull Simplify simplify) {
        return validate(splitLongEdges(fitted(partsOf(base, simplify), simplify)), Role.AREA);
    }

    /**
     * The area a shape implies: the whole derivation, in one place – a subject fitted and grown, or
     * a Custom shape, fitted by {@link Simplify#NONE}, grown.
     * <p>
     * <b>Two callers, one method.</b> The editor runs this to work out the area it shows, and
     * {@link WtfFile#checkArea} runs it to ask whether a project file still agrees with itself. If
     * those two ever ran different sequences – one unioning the parts first, say, and the other
     * not – then every file the editor wrote would fail its own check the moment it was reopened,
     * and the failure would read as a corrupt file rather than as the missing line it was. So there
     * is nowhere else this sequence is written.
     *
     * @param base the subject, or a Custom shape
     */
    public static @NotNull Geometry areaFrom(final @NotNull Geometry base,
                                             final @NotNull Simplify simplify,
                                             final double bufferValue) {
        return validate(refine(partsOf(base, simplify), simplify, bufferValue), Role.AREA);
    }

    /** The shape as the fit takes it, for {@link #areaFrom} and {@link #fitOf} alike. */
    private static @NotNull Geometry partsOf(final @NotNull Geometry base, final @NotNull Simplify simplify) {
        // The parts as they stand, whatever the fit: an area inside the world and not across the
        // antimeridian. Before anything is made of them, since their union and their hull are
        // WTF's own shapes, whose long edges are split – and a part across ±180° would be taken
        // the long way round without a word.
        checkExtent(base, Role.SUBJECT);
        // Only the outline itself needs the parts made one. The box around them and their hull are
        // the box and the hull of their union, and the union is what costs: Europe from the
        // library is 575 parts, which took most of a second to join for an answer that is four
        // corners.
        return simplify == Simplify.NONE ? validate(unioned(base), Role.SUBJECT) : base;
    }

    /** One part in ten thousand of a shape's own diagonal; see {@link #reproduces}. */
    public static final double REPRODUCTION_TOLERANCE = 1e-4;

    /** A floor under that, in degrees: about 1.1 m, below which the numbers mean nothing anyway. */
    public static final double REPRODUCTION_FLOOR_DEGREES = 1e-5;

    /** How far apart two versions of one shape are at their worst, in degrees. */
    public static double deviation(final @NotNull Geometry a, final @NotNull Geometry b) {
        return new DiscreteHausdorffDistance(a, b).distance();
    }

    /**
     * The same as ground distance, for a message a person reads – and an upper bound, not a
     * measurement.
     * <p>
     * It converts degrees at the latitude rate, and a degree of longitude is shorter than that
     * everywhere but the equator, so an east-west disagreement reads longer than it is: at fifty
     * degrees north, by half again. That is the right way round for a number whose only job is to
     * say how badly a file disagrees with itself.
     */
    public static double deviationMeters(final @NotNull Geometry a, final @NotNull Geometry b) {
        return deviation(a, b) * METERS_PER_DEGREE_LATITUDE;
    }

    /**
     * Whether one shape is the same shape as another, to within a printed dot.
     * <p>
     * <b>Not coordinate equality.</b> JTS segments a buffer's arcs into a finite number of
     * straight pieces, {@code normalize()} may order rings differently, and doubles are doubles;
     * two runs of the same derivation agree to the eye and not to the last bit. A test that
     * demanded the last bit would refuse every file it was meant to protect.
     * <p>
     * <b>Hausdorff, not symmetric difference.</b> The question this answers is "is the edge of my
     * print in the wrong place, and by how far" – a displacement – and a stored area that does
     * not match its settings is off by a displacement: a different growth moves the edge by the
     * difference, a different fit moves the corners. A relative symmetric difference is blind to
     * a thin sliver everywhere and costs a full overlay on a four-thousand-point outline.
     * <p>
     * Undensified on purpose: {@code DiscreteHausdorffDistance} measures each vertex of one shape
     * against the <i>segments</i> of the other, so two shapes with the same edges and different
     * vertex spacing measure zero – which is exactly the tolerance wanted. Densifying would turn a
     * four-thousand-point outline into a comparison that visibly stalls opening a file.
     * <p>
     * The tolerance is one part in ten thousand of the shape's own diagonal: half a millimeter on
     * a five-meter wall print, three hundredths of one on A4. Below a single printer dot at any
     * size anybody will make, and four orders of magnitude above the arithmetic noise. Measuring
     * in degrees makes it anisotropic – a degree of longitude at 60 degrees north is half a degree
     * of latitude – but the measurement and the tolerance are in the same degree space and both
     * come from the same envelope, so it cancels to within the shape's aspect ratio. Do not
     * "fix" that by projecting; it would buy nothing and cost a projection.
     */
    public static boolean reproduces(final @NotNull Geometry stored, final @NotNull Geometry recomputed) {
        final Envelope e = stored.getEnvelopeInternal();
        final double diagonal = Math.hypot(e.getWidth(), e.getHeight());
        return deviation(stored, recomputed)
                <= Math.max(REPRODUCTION_FLOOR_DEGREES, REPRODUCTION_TOLERANCE * diagonal);
    }

    /**
     * Buffers by meters on the ground.
     * <p>
     * Buffering by a degree distance stretches east-west by 1/cos(latitude), which is not what
     * anybody means by "500 m". So the buffer scales x by cos(center latitude), buffers by the
     * latitude-equivalent degrees, and scales back. Exact enough for a print that is about to be
     * rounded to a tile boundary, and it keeps a projection library out of the module – do not
     * "fix" this into a dependency on Apache SIS.
     */
    public static @NotNull Geometry buffer(final @NotNull Geometry g, final double meters) {
        if (meters == 0) {
            return g;
        }
        // JTS spells it the other way; that is its API, not our prose.
        final double centerLat = g.getEnvelopeInternal().centre() == null
                ? 0 : g.getEnvelopeInternal().centre().y;
        final double cos = Math.max(0.01, Math.cos(Math.toRadians(centerLat)));
        final double degrees = meters / METERS_PER_DEGREE_LATITUDE;

        final AffineTransformation squash = AffineTransformation.scaleInstance(cos, 1);
        final AffineTransformation unsquash = AffineTransformation.scaleInstance(1 / cos, 1);
        return unsquash.transform(squash.transform(g).buffer(degrees));
    }

    // -------------------------------------------------------------------------------------------
    // Bounds
    // -------------------------------------------------------------------------------------------

    public static @NotNull LonLatBounds boundsOf(final @NotNull Geometry g) {
        final Envelope e = g.getEnvelopeInternal();
        return new LonLatBounds(e.getMinX(), e.getMinY(), e.getMaxX(), e.getMaxY());
    }

    /** The unit-square rectangle a geographic rectangle projects to. y runs 0..1 north to south. */
    public static double @NotNull [] toUnitSquare(final @NotNull LonLatBounds b) {
        return new double[]{
                Mercator.lonToUnitX(b.west()), Mercator.latToUnitY(b.north()),
                Mercator.lonToUnitX(b.east()), Mercator.latToUnitY(b.south())};
    }

    /**
     * The shape projected into the unit square, so a clip path can be built from it without every
     * consumer repeating the projection. Vertices only – Mercator is conformal, so a straight edge
     * in degrees is very slightly curved here, and at print scales the difference is far below a
     * dot.
     */
    public static @NotNull Geometry toUnitSquare(final @NotNull Geometry wgs84) {
        final Geometry copy = wgs84.copy();
        copy.apply((CoordinateSequenceFilter) new CoordinateSequenceFilter() {
            @Override
            public void filter(final CoordinateSequence seq, final int i) {
                final double lon = seq.getOrdinate(i, 0);
                final double lat = seq.getOrdinate(i, 1);
                seq.setOrdinate(i, 0, Mercator.lonToUnitX(lon));
                seq.setOrdinate(i, 1, Mercator.latToUnitY(lat));
            }

            @Override
            public boolean isDone() {
                return false;
            }

            @Override
            public boolean isGeometryChanged() {
                return true;
            }
        });
        copy.geometryChanged();
        return copy;
    }

    /** The geographic rectangle of a unit-square rectangle. */
    public static @NotNull LonLatBounds fromUnitSquare(final double x0, final double y0,
                                                       final double x1, final double y1) {
        return new LonLatBounds(Mercator.unitXToLon(x0), Mercator.unitYToLat(y1),
                Mercator.unitXToLon(x1), Mercator.unitYToLat(y0));
    }

    /**
     * How coarse an outline is, at a given print scale: the median and maximum edge length in
     * millimeters of print. A 34-point country outline draws a 34-sided polygon on the wall however
     * fine the tiles are, and this is the number that warns about it before the paper is bought.
     * See docs/design/subject-outlines.md §2.
     */
    public record Coarseness(double medianMm, double maxMm, int segments) {
        public boolean isFine() {
            return medianMm < 0.5;
        }

        public boolean isVisiblyAngular() {
            return medianMm > 2.0;
        }
    }

    public static @Nullable Coarseness coarseness(final @NotNull Geometry wgs84, final double mmPerUnit) {
        final Geometry unit = toUnitSquare(wgs84);
        // Ring by ring: the step from one ring's last point to the next ring's first is no edge,
        // and measured as one it made the Aegean the worst edge of Greece
        final List<Double> lengths = new ArrayList<>();
        for (int g = 0; g < unit.getNumGeometries(); g++) {
            final Geometry part = unit.getGeometryN(g);
            if (part instanceof final Polygon poly) {
                edges(poly.getExteriorRing(), mmPerUnit, lengths);
                for (int h = 0; h < poly.getNumInteriorRing(); h++) {
                    edges(poly.getInteriorRingN(h), mmPerUnit, lengths);
                }
            } else if (part instanceof final LineString line) {
                edges(line, mmPerUnit, lengths);
            }
        }
        if (lengths.isEmpty()) {
            return null;
        }
        final double[] sorted = lengths.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        final double median = sorted.length % 2 == 1
                ? sorted[sorted.length / 2]
                : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2;
        return new Coarseness(median, sorted[sorted.length - 1], sorted.length);
    }

    /** Each edge of one ring or line, in millimeters of print. */
    private static void edges(final @NotNull LineString line, final double mmPerUnit,
                              final @NotNull List<Double> into) {
        final Coordinate[] cs = line.getCoordinates();
        for (int i = 1; i < cs.length; i++) {
            into.add(Math.hypot((cs[i].x - cs[i - 1].x) * mmPerUnit, (cs[i].y - cs[i - 1].y) * mmPerUnit));
        }
    }
}
