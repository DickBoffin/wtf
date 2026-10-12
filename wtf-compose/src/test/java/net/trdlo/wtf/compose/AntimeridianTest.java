package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Mercator;
import net.trdlo.wtf.tiles.ProviderCatalog;
import net.trdlo.wtf.tiles.Refusal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.algorithm.distance.DiscreteHausdorffDistance;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A shape across the antimeridian is refused, as D22 decided, and a shape WTF makes itself never
 * is.
 * <p>
 * A ring from elsewhere that runs from 179° to −179.5° means the island between them, joined the
 * short way as a globe joins two points. Taken as the plane has it, it would be a band round the
 * world, 359° wide, and print as one: 1000 by 3 mm of sea with the island at both ends. What WTF
 * draws, edits or works out is a straight line on the flat map, so an edge of its own that long is
 * split where the map draws it, and reads back as drawn.
 */
class AntimeridianTest {

    /** An island a degree across, over the antimeridian, as a globe or another program writes it. */
    private static final Coordinate[] ISLAND = {
            xy(179.0, -16.0), xy(-179.5, -16.2), xy(-179.8, -17.0), xy(179.2, -16.9)};

    private static final LonLatBounds BOHEMIA = new LonLatBounds(12.09, 49.44, 15.98, 50.73);

    /** Two edges of 200° on the flat map, one of them slanted: nothing crosses anything. */
    private static final Coordinate[] LONG = {xy(-100, 10), xy(100, 60), xy(100, 70), xy(-100, 70)};

    /**
     * A U from California to the Urals whose every edge is shorter than half the world, and whose
     * hull is not: its top runs 185° from the one arm to the other.
     */
    private static final Coordinate[] U = {
            xy(-130, 20), xy(-130, 70), xy(-125, 70), xy(-125, 30), xy(50, 30), xy(50, 60),
            xy(60, 60), xy(60, 20), xy(-35, 20)};

    private static Coordinate xy(final double lon, final double lat) {
        return new Coordinate(lon, lat);
    }

    private static LinearRing ring(final Coordinate... open) {
        final Coordinate[] closed = java.util.Arrays.copyOf(open, open.length + 1);
        closed[open.length] = new Coordinate(open[0]);
        return Shapes.GF.createLinearRing(closed);
    }

    private static Refusal refused(final Geometry g, final Shapes.Role role) {
        return assertThrows(Refusal.class, () -> Shapes.validate(g, role));
    }

    /** Every edge of every ring whose ends are more than half the world apart, as "a -> b". */
    private static List<String> longEdges(final Geometry g) {
        final List<String> found = new ArrayList<>();
        for (int i = 0; i < g.getNumGeometries(); i++) {
            final Polygon p = (Polygon) g.getGeometryN(i);
            final List<LineString> rings = new ArrayList<>(List.of(p.getExteriorRing()));
            for (int h = 0; h < p.getNumInteriorRing(); h++) {
                rings.add(p.getInteriorRingN(h));
            }
            for (final LineString r : rings) {
                final Coordinate[] cs = r.getCoordinates();
                for (int k = 1; k < cs.length; k++) {
                    if (Math.abs(cs[k].x - cs[k - 1].x) > 180) {
                        found.add(cs[k - 1] + " -> " + cs[k]);
                    }
                }
            }
        }
        return found;
    }

    /** How far apart two shapes are on the flat map – in the unit square, where both are drawn. */
    private static double apartOnTheMap(final Geometry a, final Geometry b) {
        return new DiscreteHausdorffDistance(Shapes.toUnitSquare(a), Shapes.toUnitSquare(b)).distance();
    }

    // ---------------------------------------------------------------------------------- the rule

    @Test
    @DisplayName("a ring across the antimeridian is refused with D22's reason, as a shell, a hole or a part")
    void aRingAcrossIsRefused() {
        final Refusal shell = refused(Shapes.polygon(ISLAND), Shapes.Role.AREA);
        assertEquals(ComposeReason.SHAPE_CROSSES_ANTIMERIDIAN, shell.reason());
        assertEquals(List.of(Shapes.Role.AREA, 179.0, -179.5), shell.args(),
                "the first two neighbors more than half the world apart, as the ring has them");
        assertEquals("The area shape crosses the antimeridian between longitudes 179 and -179.5: neighboring"
                + " points more than half the world apart are joined the short way, across ±180°. The map"
                + " ends there, and WTF does not print across it – the print would be two, one at each end"
                + " of the map.", shell.getMessage());

        // A band round the world with the island as a lake in it: the band's own long edges run
        // along the edge of the map, and the lake's go across the antimeridian
        final Polygon band = Shapes.GF.createPolygon(
                ring(xy(-180, -20), xy(180, -20), xy(180, -10), xy(-180, -10)), new LinearRing[]{ring(ISLAND)});
        assertEquals(List.of(Shapes.Role.SUBJECT, 179.0, -179.5), refused(band, Shapes.Role.SUBJECT).args());

        // And one part of several is enough
        final Geometry parts = Shapes.GF.createMultiPolygon(new Polygon[]{
                Shapes.rectangle(BOHEMIA), Shapes.polygon(ISLAND)});
        assertEquals(ComposeReason.SHAPE_CROSSES_ANTIMERIDIAN, refused(parts, Shapes.Role.SUBJECT).reason());
        assertEquals(ComposeReason.SHAPE_CROSSES_ANTIMERIDIAN, assertThrows(Refusal.class,
                () -> Shapes.withPart(Shapes.rectangle(BOHEMIA), Shapes.polygon(ISLAND), Shapes.Role.SUBJECT))
                .reason(), "an arrival in the subject is checked on its way in");
    }

    @Test
    @DisplayName("the whole world, rings along ±180° that do not cross it, and a box wider than half the world pass")
    void whatDoesNotCrossPasses() {
        // The whole world: its top and bottom run from one end of the map to the other
        assertDoesNotThrow(() -> Shapes.validate(
                Shapes.rectangle(new LonLatBounds(-180, -85, 180, 85)), Shapes.Role.AREA));
        // Round the pole, closed along the bottom of the map as Antarctica is
        assertDoesNotThrow(() -> Shapes.validate(Shapes.polygon(new Coordinate[]{
                xy(-180, -85), xy(-180, -60), xy(0, -70), xy(180, -60), xy(180, -85)}), Shapes.Role.SUBJECT));
        // Up to ±180° from either side, as Natural Earth cuts Chukotka and Fiji
        assertDoesNotThrow(() -> Shapes.validate(Shapes.polygon(new Coordinate[]{
                xy(170, 60), xy(180, 60), xy(180, 70), xy(175, 72)}), Shapes.Role.SUBJECT));
        assertDoesNotThrow(() -> Shapes.validate(Shapes.polygon(new Coordinate[]{
                xy(-180, 60), xy(-170, 62), xy(-175, 70), xy(-180, 70)}), Shapes.Role.SUBJECT));
        // A box is a range from its west to its east, however wide: California to the Urals
        assertDoesNotThrow(() -> Shapes.validate(
                Shapes.rectangle(new LonLatBounds(-130, 20, 60, 70)), Shapes.Role.AREA));
    }

    // ----------------------------------------------------------------------- what WTF makes itself

    @Test
    @DisplayName("an edge of WTF's own longer than half the world is split where the map draws it")
    void aLongEdgeIsSplitWhereTheMapDrawsIt() {
        final Polygon drawn = Shapes.polygon(LONG);
        assertEquals(ComposeReason.SHAPE_CROSSES_ANTIMERIDIAN, refused(drawn, Shapes.Role.SUBJECT).reason(),
                "as it stands, the file could not tell it from a shape across the antimeridian");

        final Geometry split = Shapes.splitLongEdges(drawn);
        assertInstanceOf(Polygon.class, split);
        final Coordinate[] cs = split.getCoordinates();
        assertEquals(7, cs.length, () -> "a point in each long edge, and nothing else: " + split);
        // The four drawn corners, where they were and in their order, with a point between the pairs
        assertTrue(cs[0].equals2D(LONG[0]) && cs[2].equals2D(LONG[1]) && cs[3].equals2D(LONG[2])
                && cs[5].equals2D(LONG[3]) && cs[6].equals2D(LONG[0]), split::toString);
        // Halfway along each edge on the flat map: the slanted one at the Mercator middle of its
        // ends, 39.26° and not the 35° a middle in degrees would give, which would bend it on screen
        assertEquals(0, cs[1].x, 1e-9);
        assertEquals((Mercator.latToUnitY(10) + Mercator.latToUnitY(60)) / 2, Mercator.latToUnitY(cs[1].y), 1e-12);
        assertEquals(39.26, cs[1].y, 0.01);
        assertEquals(0, cs[4].x, 1e-9);
        assertEquals(70, cs[4].y, "a level edge keeps its latitude exactly");

        assertTrue(longEdges(split).isEmpty(), () -> "none left: " + longEdges(split));
        assertEquals(0, apartOnTheMap(drawn, split), 1e-12, "the same shape on the map and on paper");
        assertDoesNotThrow(() -> Shapes.validate(split, Shapes.Role.SUBJECT));
    }

    @Test
    @DisplayName("a box, an edge along ±180° and a shape with nothing long are left as they are")
    void whatIsNotLongIsLeftAlone() {
        final Polygon box = Shapes.rectangle(new LonLatBounds(-130, 20, 60, 70));
        assertSame(box, Shapes.splitLongEdges(box), "four corners stay four corners");
        final Polygon world = Shapes.rectangle(new LonLatBounds(-180, -85, 180, 85));
        assertSame(world, Shapes.splitLongEdges(world));
        final Polygon pole = Shapes.polygon(new Coordinate[]{
                xy(-180, -85), xy(-180, -60), xy(0, -70), xy(180, -60), xy(180, -85)});
        assertSame(pole, Shapes.splitLongEdges(pole), "its long edge runs along the bottom of the map");
        final Polygon bohemia = Shapes.rectangle(BOHEMIA);
        assertSame(bohemia, Shapes.splitLongEdges(bohemia));

        // In a shape of several parts only the part with a long edge is rebuilt
        final Geometry parts = Shapes.GF.createMultiPolygon(new Polygon[]{box, Shapes.polygon(LONG)});
        final Geometry split = Shapes.splitLongEdges(parts);
        assertEquals(2, split.getNumGeometries());
        assertSame(box, split.getGeometryN(0));
        assertEquals(7, split.getGeometryN(1).getNumPoints());
    }

    @Test
    @DisplayName("the hull, the growth and the union of a wide subject that does not cross are not refused")
    void whatIsWorkedOutIsNotRefused() {
        final Polygon u = Shapes.polygon(U);
        assertTrue(longEdges(u).isEmpty(), "the subject itself has no long edge");

        final Geometry hull = assertDoesNotThrow(() -> Shapes.areaFrom(u, Shapes.Simplify.CONVEX_HULL, 0), "its hull runs 185° from arm to arm");
        assertTrue(longEdges(hull).isEmpty(), () -> "split: " + longEdges(hull));

        // A growth joins the points of a straight line into one edge again: 95° and 95° become 190°
        final Geometry grown = assertDoesNotThrow(() -> Shapes.areaFrom(u, Shapes.Simplify.NONE, 10_000));
        assertTrue(longEdges(grown).isEmpty(), () -> "split: " + longEdges(grown));

        // A box 340° wide is exempt as a box; joined with a part poking out of its top, it is not
        final Geometry poking = Shapes.GF.createMultiPolygon(new Polygon[]{
                Shapes.rectangle(new LonLatBounds(-170, 0, 170, 10)),
                Shapes.rectangle(new LonLatBounds(150, 5, 160, 20))});
        final Geometry joined = assertDoesNotThrow(() -> Shapes.areaFrom(poking, Shapes.Simplify.NONE, 0));
        assertTrue(longEdges(joined).isEmpty(), () -> "split: " + longEdges(joined));
        assertEquals(0, apartOnTheMap(poking.union(), joined), 1e-12, "and still the same shape");

        // The box around it is a box
        assertTrue(Shapes.isRectangle(Shapes.areaFrom(u, Shapes.Simplify.BOUNDING_BOX, 0)));
    }

    @Test
    @DisplayName("a subject across the antimeridian is refused before anything is worked out of it")
    void aSubjectAcrossIsRefusedWhateverTheFit() {
        final Geometry parts = Shapes.GF.createMultiPolygon(new Polygon[]{
                Shapes.rectangle(BOHEMIA), Shapes.polygon(ISLAND)});
        for (final Shapes.Simplify fit : Shapes.Simplify.values()) {
            final Refusal r = assertThrows(Refusal.class,
                    () -> Shapes.areaFrom(parts, fit, 0), fit::name);
            assertEquals(ComposeReason.SHAPE_CROSSES_ANTIMERIDIAN, r.reason(), fit::name);
            assertEquals(Shapes.Role.SUBJECT, r.args().getFirst(), fit::name);
        }
    }

    // ----------------------------------------------------------------------------------- the files

    /** The sample, with its area and its subject replaced. */
    private static Path project(final Path dir, final String name, final Geometry area, final Geometry subject)
            throws IOException {
        final WtfFile s = WtfFile.sample("osm", "standard", 12, 49, 16, 51, 5, 1000);
        final Path file = dir.resolve(name);
        new WtfFile(s.format(), s.title(), s.notes(), s.saved(), s.source(),
                WtfFile.multiRingOf(area, "polygon", "drawn"),
                subject == null ? null : WtfFile.multiRingOf(subject, "polygon", "drawn"),
                s.refine(), s.regions(), s.print(), s.medium(), s.export()).write(file);
        return file;
    }

    @Test
    @DisplayName("a project file with a ring across the antimeridian is refused as it is read")
    void aFileIsRefusedAsItIsRead(@TempDir final Path dir) throws IOException {
        final WtfFile subject = WtfFile.read(project(dir, "subject.wtf", Shapes.rectangle(BOHEMIA),
                Shapes.polygon(ISLAND)));
        final Refusal read = assertThrows(Refusal.class, subject::subjectGeometry);
        assertEquals(ComposeReason.SHAPE_CROSSES_ANTIMERIDIAN, read.reason());
        assertEquals(List.of(Shapes.Role.SUBJECT, 179.0, -179.5), read.args());
        assertThrows(Refusal.class, subject::checkArea, "the check of a derivation reads it too");
        assertThrows(Refusal.class, () -> subject.toJob(ProviderCatalog.builtIn(), dir), "and so does the print");

        final WtfFile area = WtfFile.read(project(dir, "area.wtf", Shapes.polygon(ISLAND), null));
        assertEquals(List.of(Shapes.Role.AREA, 179.0, -179.5),
                assertThrows(Refusal.class, area::areaGeometry).args());
        assertThrows(Refusal.class, () -> area.toJob(ProviderCatalog.builtIn(), dir));
    }

    @Test
    @DisplayName("a long edge WTF has split goes into a file and comes out again, and one it has not does not")
    void aSplitShapeReadsBack(@TempDir final Path dir) throws IOException {
        final Geometry drawn = Shapes.splitLongEdges(Shapes.polygon(LONG));
        final WtfFile f = WtfFile.read(project(dir, "wide.wtf",
                Shapes.rectangle(new LonLatBounds(-100, 10, 100, 70)), drawn));
        final Geometry back = assertDoesNotThrow(f::subjectGeometry);
        assertTrue(back.equalsExact(drawn), back::toString);
        assertDoesNotThrow(() -> f.toJob(ProviderCatalog.builtIn(), dir));

        // The same shape as another program would write it, its long edges whole
        final WtfFile unsplit = WtfFile.read(project(dir, "unsplit.wtf",
                Shapes.rectangle(new LonLatBounds(-100, 10, 100, 70)), Shapes.polygon(LONG)));
        assertThrows(Refusal.class, unsplit::subjectGeometry);
    }

    @Test
    @DisplayName("an imported file's ring across the antimeridian is refused on its way in")
    void anImportIsRefusedOnItsWayIn(@TempDir final Path dir) throws IOException {
        final Path geojson = dir.resolve("fiji.geojson");
        Files.writeString(geojson, """
                {"type":"Feature","geometry":{"type":"Polygon","coordinates":
                  [[[179.0,-16.0],[-179.5,-16.2],[-179.8,-17.0],[179.2,-16.9],[179.0,-16.0]]]}}
                """);
        final Path kml = dir.resolve("fiji.kml");
        Files.writeString(kml, """
                <kml><Placemark><Polygon><outerBoundaryIs><LinearRing><coordinates>
                  179.0,-16.0 -179.5,-16.2 -179.8,-17.0 179.2,-16.9 179.0,-16.0
                </coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark></kml>
                """);
        for (final Path file : List.of(geojson, kml)) {
            final Geometry g = ShapeImport.read(file);
            assertEquals(ComposeReason.SHAPE_CROSSES_ANTIMERIDIAN, assertThrows(Refusal.class,
                    () -> Shapes.withPart(null, g, Shapes.Role.SUBJECT)).reason(), file::toString);
        }
    }
}
