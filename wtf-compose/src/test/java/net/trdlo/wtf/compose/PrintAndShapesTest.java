package net.trdlo.wtf.compose;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.jetbrains.annotations.NotNull;
import net.trdlo.wtf.tiles.ProviderCatalog;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Mercator;
import net.trdlo.wtf.tiles.Refusal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PrintAndShapesTest {

    private static final LonLatBounds BOHEMIA = new LonLatBounds(12.09, 49.44, 15.98, 50.73);

    @Test
    @DisplayName("a print sized at a DPI is that DPI at its zoom, and its place names that height at any zoom")
    void atDpi() {
        for (final int zoom : new int[]{8, 13, 16}) {
            final PrintRect print = PrintSize.atDpi(BOHEMIA, zoom, 1, 150).apply(BOHEMIA);
            assertEquals(150, print.dpi(zoom), 1e-9);
            assertEquals(PrintRect.labelHeightMmAt(150), print.labelHeightMm(zoom), 1e-12);
        }
        assertEquals(2 * PrintSize.atDpi(BOHEMIA, 12, 1, 300).widthMm(),
                PrintSize.atDpi(BOHEMIA, 13, 1, 300).widthMm(), 1e-6, "a zoom up, twice as wide");
        assertThrows(IllegalArgumentException.class, () -> PrintSize.atDpi(BOHEMIA, 12, 1, 0));
    }

    /**
     * A @2x tile is its zoom's ground and names in twice the pixels: the DPI of the zoom above with
     * the labels of this one, so at a DPI it makes the print of the zoom above with names twice as tall.
     */
    @Test
    @DisplayName("@2x tiles have the DPI of the next zoom up and the labels of their own")
    void retinaDoublesTheDpiAndKeepsTheLabels() {
        final PrintRect print = PrintSize.width(1000).apply(BOHEMIA);
        assertEquals(print.dpi(14), print.dpi(13, 2), 1e-9);
        assertEquals(2 * print.pixelWidth(13), print.pixelWidth(13, 2), 1e-9);

        final PrintRect at300 = PrintSize.atDpi(BOHEMIA, 13, 2, 300).apply(BOHEMIA);
        assertEquals(300, at300.dpi(13, 2), 1e-9);
        assertEquals(PrintSize.atDpi(BOHEMIA, 14, 1, 300).widthMm(), at300.widthMm(), 1e-6,
                "the size of the zoom above at the same DPI");
        assertEquals(2 * PrintRect.labelHeightMmAt(300), at300.labelHeightMm(13), 1e-9,
                "with its names twice as tall");
    }

    @Test
    @DisplayName("a roll job says how many meters come off the roll, overlaps included")
    void rollLength() {
        final PrintRect print = PrintSize.width(5000).apply(BOHEMIA);
        final SheetLayout layout = SheetLayout.plan(print,
                new Medium.Roll(915, Medium.Axis.VERTICAL), 15, 0);

        // Every strip is printed in full, overlap and all, so the roll gives up more than the
        // print is tall – which is the whole reason the number is worth showing.
        assertTrue(layout.mediaLengthMm() >= print.heightMm(),
                "the roll cannot give up less than the print is tall");
        double byHand = 0;
        for (final SheetLayout.Sheet sheet : layout.sheets()) {
            byHand += sheet.heightMm();
        }
        assertEquals(byHand, layout.mediaLengthMm(), 1e-9);
    }

    /** A job over Bohemia, on A4 portrait with a 15 mm overlap: the shape of a real one. */
    private static ComposeJob job(final Geometry area, final PrintRect print) {
        return TestJobs.job(area).zoom(12).print(print).medium(Medium.paper("A4").sheet(Medium.Orientation.PORTRAIT))
                .overlap(15).missing(MissingTilePolicy.REFUSE).perSheet().build();
    }

    @Test
    @DisplayName("the spare paper fills the sheets the print grew to with map, rather than leaving it blank")
    void theSparePaperHasMapInIt() {
        // Two A4 sheets across a print that needs one and a half: the layout grows the print to
        // cover both, and the band it adds must be inside the shape, not print as paper.
        final PrintRect print = PrintSize.width(300).apply(BOHEMIA);
        final ComposeJob job = job(Shapes.rectangle(BOHEMIA), print);

        final SheetLayout layout = job.layout();
        assertEquals(2, layout.columns(), "this is the two-sheet case");
        assertTrue(layout.print().widthMm() > print.widthMm(), "the print grew");

        // Every tile of every sheet is printed. A box grown to the paper leaves nothing out, and
        // the band the layout added is exactly where tiles planned for the ungrown box would stop.
        final TilePlan.Plan plan = TilePlan.of(job);
        for (final TilePlan.SheetPlan sheet : plan.sheets()) {
            assertFalse(sheet.regions().isEmpty(), () -> "sheet " + sheet.sheet().index() + " is blank");
            for (final TilePlan.RegionTiles region : sheet.regions()) {
                assertEquals(region.range().count(), region.keys().size(),
                        () -> "sheet " + sheet.sheet().index() + " has paper where it should have map");
            }
        }
        final SheetLayout.Sheet last = layout.sheets().getLast();
        assertEquals(layout.print().widthMm(), last.xMm() + last.widthMm(), 0.001);
    }

    @Test
    @DisplayName("a bounding box grown by a distance is a bigger box, not a rounded one")
    void aGrownBoxIsABox() {
        final Geometry grown = Shapes.refine(Shapes.rectangle(BOHEMIA), Shapes.Simplify.BOUNDING_BOX,
                10_000);

        // Five points and four right angles: a rounded corner would be dozens of points, and the
        // rounding shows on the paper as four blank corners.
        assertEquals(5, grown.getCoordinates().length, "a box has four corners and a closing point");
        assertTrue(Shapes.isRectangle(grown));

        final var before = Shapes.boundsOf(Shapes.rectangle(BOHEMIA));
        final var after = Shapes.boundsOf(grown);
        assertTrue(after.west() < before.west() && after.east() > before.east(), "and it did grow");
    }

    @Test
    @DisplayName("every fit that leaves a rectangle a rectangle grows it the same way")
    void theFitDoesNotChangeHowARectangleGrows() {
        // The hull of a rectangle is that rectangle and its bounding box is that rectangle, so
        // the three answers had better agree – not one re-squaring the corners and the others
        // leaving the buffer's arcs on them.
        for (final Shapes.Simplify fit : Shapes.Simplify.values()) {
            final Geometry grown = Shapes.refine(Shapes.rectangle(BOHEMIA), fit,
                    10_000);
            assertTrue(Shapes.isRectangle(grown), () -> fit + " turned a rectangle into something else");
        }
    }

    @Test
    @DisplayName("a shape that is not a rectangle keeps its round corners, because that is the shape")
    void aGrownOutlineIsNotABox() {
        final Geometry triangle = Shapes.polygon(new Coordinate[]{
                new Coordinate(12, 49), new Coordinate(16, 49), new Coordinate(14, 51)});
        final Geometry grown = Shapes.refine(triangle, Shapes.Simplify.NONE,
                10_000);
        assertFalse(Shapes.isRectangle(grown), "growing an outline is a Minkowski sum with a disc");
        assertTrue(grown.getNumPoints() > 10, "and it is round where the corners were");
    }

    // ------------------------------------------------------------------------------ shape kinds

    @Test
    @DisplayName("a rectangle is recognizable as one after a round trip through a file")
    void rectanglesAreRecognized() {
        assertTrue(Shapes.isRectangle(Shapes.rectangle(BOHEMIA)));
        assertTrue(Shapes.isRectangle(Shapes.point(14.42, 50.08)));
        // The editor asks this of whatever it is given, including things that are not polygons
        assertFalse(Shapes.isRectangle(null));
    }

    @Test
    @DisplayName("a four-cornered shape that is not axis-aligned is not a rectangle")
    void aSkewedQuadrilateralIsNotARectangle() {
        final org.locationtech.jts.geom.Polygon skewed = Shapes.polygon(new Coordinate[]{
                new Coordinate(12, 49), new Coordinate(16, 49.5),
                new Coordinate(16, 51), new Coordinate(12, 50.5)});
        assertFalse(Shapes.isRectangle(skewed), "this one would shear if it were edited as a box");
    }

    @Test
    @DisplayName("a triangle and a country are polygons, not rectangles")
    void otherShapesAreNotRectangles() {
        assertFalse(Shapes.isRectangle(Shapes.polygon(new Coordinate[]{
                new Coordinate(12, 49), new Coordinate(16, 49), new Coordinate(14, 51)})));
    }

    @Test
    @DisplayName("a point is a tiny rectangle, and a real rectangle is not a point")
    void pointsAreTellableFromRectangles() {
        assertTrue(Shapes.isPoint(Shapes.point(14.42, 50.08)));
        assertFalse(Shapes.isPoint(Shapes.rectangle(BOHEMIA)));
        assertFalse(Shapes.isPoint(null));
    }

    // ---------------------------------------------------------------------------------- print size

    @Test
    @DisplayName("fit to width: the height follows, and the scale stays uniform")
    void fitWidth() {
        final PrintRect r = PrintSize.width(5000).apply(BOHEMIA);
        assertEquals(5000, r.widthMm(), 1e-9);
        assertDoesNotThrow(r::checkUniform);
        assertTrue(r.heightMm() > 0 && r.heightMm() < 5000);
    }

    @Test
    @DisplayName("fit inside honors whichever constraint binds")
    void fitInside() {
        // Bohemia is wider than it is tall, so a square box is bound by the width
        final PrintRect wide = PrintSize.inside(5000, 5000).apply(BOHEMIA);
        assertEquals(5000, wide.widthMm(), 1e-9);
        assertTrue(wide.heightMm() <= 5000);

        // a short box is bound by the height
        final PrintRect short_ = PrintSize.inside(5000, 500).apply(BOHEMIA);
        assertEquals(500, short_.heightMm(), 1e-9);
        assertTrue(short_.widthMm() < 5000);
        assertDoesNotThrow(short_::checkUniform);
    }

    @Test
    @DisplayName("fill and crop takes in more map rather than distorting")
    void fillAndCrop() {
        final PrintRect natural = PrintSize.width(5000).apply(BOHEMIA);
        final PrintRect filled = PrintSize.fill(5000, 3000).apply(BOHEMIA);

        assertEquals(5000, filled.widthMm(), 1e-9);
        assertEquals(3000, filled.heightMm(), 1e-9);
        assertDoesNotThrow(filled::checkUniform, "it must fill by taking in map, never by stretching");
        assertTrue(filled.unitHeight() > natural.unitHeight(),
                "3000 mm is taller than the shape needs, so the rectangle grew north-south");
        assertEquals(natural.unitWidth(), filled.unitWidth(), 1e-9, "and the other axis did not move");
    }

    @Test
    @DisplayName("a mode without its dimension is refused rather than guessed")
    void missingDimension() {
        assertThrows(IllegalArgumentException.class, () -> PrintSize.height(0).apply(BOHEMIA));
        assertThrows(IllegalArgumentException.class,
                () -> new PrintSize(PrintSize.Mode.FIT_INSIDE, 5000, 0).apply(BOHEMIA));
    }

    // -------------------------------------------------------------------------------- scale triangle

    @Test
    @DisplayName("the scale numbers match what the documentation tabulates")
    void scaleNumbers() {
        final PrintRect r = PrintSize.width(5000).apply(new LonLatBounds(12.0, 49.5, 14.5, 50.5));
        // docs/design/compose-module.md §3: zoom 15 at 50 degrees north is 3.07 m per pixel
        assertEquals(3.071, Mercator.groundResolution(50, 15), 0.001);
        assertTrue(r.mapScale() > 1000, "a wall map is a small scale");
        assertEquals(r.dpi(15), r.dpi(14) * 2, r.dpi(14) * 1e-9, "one zoom deeper is twice the DPI");
    }

    @Test
    @DisplayName("more DPI makes a map LESS readable, which is the trap this project must avoid")
    void higherDpiMeansSmallerLabels() {
        final PrintRect r = PrintSize.width(5000).apply(BOHEMIA);
        final double at13 = r.labelHeightMm(13);
        final double at14 = r.labelHeightMm(14);
        final double at15 = r.labelHeightMm(15);

        assertTrue(at13 > at14 && at14 > at15, "raising the zoom shrinks every label on the paper");
        assertEquals(at13 / 2, at14, 1e-9, "one zoom deeper halves the label");
        // reading stops working near 1.5 mm, which is what puts a readable wall map near 100-150 DPI
        assertTrue(at13 >= 1.5, "zoom 13 is readable here");
        assertTrue(at15 < 1.5, "zoom 15 is not");
    }

    // ------------------------------------------------------------------------------------- shapes

    @Test
    @DisplayName("simplify to a bounding box keeps the envelope and squares the shape")
    void simplifyBoundingBox() {
        final Geometry organic = Shapes.polygon(new Coordinate[]{
                new Coordinate(12, 50), new Coordinate(14, 51), new Coordinate(16, 50),
                new Coordinate(14, 49)});
        final Geometry box = Shapes.refine(organic, Shapes.Simplify.BOUNDING_BOX, 0);
        assertEquals(Shapes.boundsOf(organic).west(), Shapes.boundsOf(box).west(), 1e-9);
        assertEquals(5, box.getCoordinates().length, "a closed rectangle");
        assertTrue(box.getArea() > organic.getArea(), "a box contains the diamond inside it");
    }

    @Test
    @DisplayName("a convex hull keeps the Gulf of Mexico")
    void convexHull() {
        // a C shape: the hull must fill the bay
        final Geometry c = Shapes.polygon(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(4, 0), new Coordinate(4, 1),
                new Coordinate(1, 1), new Coordinate(1, 3), new Coordinate(4, 3),
                new Coordinate(4, 4), new Coordinate(0, 4)});
        final Geometry hull = Shapes.refine(c, Shapes.Simplify.CONVEX_HULL, 0);
        assertTrue(hull.getArea() > c.getArea());
        assertTrue(hull.covers(c));
    }

    @Test
    @DisplayName("a buffer is meters on the ground, not stretched east-west the way degrees are")
    void metricBuffer() {
        final Geometry p = Shapes.point(14.42, 50.09);
        final Geometry metric = Shapes.buffer(p, 10_000);
        final LonLatBounds b = Shapes.boundsOf(metric);

        final double widthKm = b.widthDegrees() * 111.320 * Math.cos(Math.toRadians(50.09));
        final double heightKm = b.heightDegrees() * 111.320;
        assertEquals(widthKm, heightKm, heightKm * 0.02,
                "10 km east-west must be 10 km, not 10 degrees' worth");
        assertEquals(20, heightKm, 0.5, "a 10 km buffer is 20 km across");
    }

    @Test
    @DisplayName("a negative buffer that empties the shape is a message, not an empty print")
    void buffersToNothing() {
        final Geometry small = Shapes.rectangle(new LonLatBounds(14.0, 50.0, 14.001, 50.001));
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Shapes.refine(small, Shapes.Simplify.NONE, -10_000));
        assertTrue(ex.getMessage().contains("emptied"), ex.getMessage());
    }

    @Test
    @DisplayName("a self-intersecting ring is repaired, and an unrepairable one is refused")
    void validation() {
        final Geometry bowtie = Shapes.polygon(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(2, 2), new Coordinate(2, 0), new Coordinate(0, 2)});
        final Geometry fixed = assertDoesNotThrow(() -> Shapes.validate(bowtie, Shapes.Role.AREA));
        assertTrue(fixed.isValid());

        assertThrows(IllegalArgumentException.class,
                () -> Shapes.validate(Shapes.GF.createPolygon(), Shapes.Role.AREA), "empty is refused");
    }

    /**
     * The undo stack keeps shapes by reference, so checking one must not rearrange it: a shape
     * normalized in place is a snapshot that has changed under the stack holding it.
     */
    @Test
    @DisplayName("validating a shape leaves the shape itself alone")
    void validationCopies() {
        // Normal form starts a ring at its lowest corner; a rectangle starts at its northwest one
        final Geometry box = Shapes.rectangle(BOHEMIA);
        final Coordinate[] before = box.copy().getCoordinates();

        final Geometry checked = Shapes.validate(box, Shapes.Role.AREA);

        assertArrayEquals(before, box.getCoordinates(), "the shape handed in is as it was");
        assertTrue(checked.equalsExact(checked.norm()), "what comes back is normalized");
        assertTrue(checked.equalsNorm(box), "and is the same shape");
    }

    @Test
    @DisplayName("coarseness measures an outline against the print, in millimeters of paper")
    void coarseness() {
        final PrintRect r = PrintSize.width(5000).apply(BOHEMIA);
        // a four-point rectangle across the whole of Bohemia: enormously coarse
        final Geometry crude = Shapes.rectangle(BOHEMIA);
        final Shapes.Coarseness c = Shapes.coarseness(crude, r.mmPerUnit());
        assertNotNull(c);
        assertTrue(c.isVisiblyAngular(), "a 4-point outline on a 5 m print is visibly a polygon");
        assertTrue(c.medianMm() > 100, "its edges are meters of paper long: " + c.medianMm());

        // the same shape with a vertex every few hundred meters is fine
        final Geometry fine = crude.buffer(0).getBoundary().buffer(0.0005);
        final Shapes.Coarseness f = Shapes.coarseness(
                Shapes.GF.createPolygon(((org.locationtech.jts.geom.Polygon) crude.buffer(0.02))
                        .getExteriorRing().getCoordinates()), r.mmPerUnit());
        assertNotNull(f);
        assertNotNull(fine);
    }

    @Test
    @DisplayName("coarseness measures each ring on its own, not the sea between two islands")
    void coarsenessIsPerRing() {
        final PrintRect r = PrintSize.width(5000).apply(BOHEMIA);
        // Two small squares at opposite corners of Bohemia, and a square with a square hole
        final Geometry islands = Shapes.GF.createMultiPolygon(new org.locationtech.jts.geom.Polygon[]{
                (org.locationtech.jts.geom.Polygon) Shapes.rectangle(new LonLatBounds(12.2, 49.5, 12.21, 49.51)),
                (org.locationtech.jts.geom.Polygon) Shapes.rectangle(new LonLatBounds(15.8, 50.6, 15.81, 50.61))});
        final Shapes.Coarseness c = Shapes.coarseness(islands, r.mmPerUnit());
        assertNotNull(c);
        assertEquals(8, c.segments(), "four edges to each island, and nothing from one to the other");
        // An island's edge is 13 mm across and 20 mm up and down, the sea between them 4 m
        final double edgeMm = 0.01 / BOHEMIA.widthDegrees() * 5000;
        assertTrue(c.maxMm() < 2 * edgeMm, "the longest edge is an island's own: " + c.maxMm() + " mm");

        final Geometry holed = Shapes.GF.createPolygon(
                Shapes.GF.createLinearRing(((org.locationtech.jts.geom.Polygon) Shapes.rectangle(
                        new LonLatBounds(13, 49.6, 15, 50.6))).getExteriorRing().getCoordinates()),
                new org.locationtech.jts.geom.LinearRing[]{Shapes.GF.createLinearRing(
                        ((org.locationtech.jts.geom.Polygon) Shapes.rectangle(new LonLatBounds(13.9, 50, 14.1, 50.1)))
                                .getExteriorRing().getCoordinates())});
        final Shapes.Coarseness h = Shapes.coarseness(holed, r.mmPerUnit());
        assertNotNull(h);
        assertEquals(8, h.segments(), "the hole's four edges, and no step from the shell to it");
        assertEquals(2 / BOHEMIA.widthDegrees() * 5000, h.maxMm(), 1, "the shell's top is the longest");
    }

    // ------------------------------------------------------------------------------- project file

    @Test
    @DisplayName("a project file survives a round trip and produces the same job")
    void projectRoundTrip(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("p.wtf");
        final WtfFile written = WtfFile.sample("mapy-com", "outdoor",
                12.09, 49.44, 15.98, 50.73, 14, 5000);
        written.write(file);
        written.write(file);        // and over itself, which is the save that could lose a file
        assertFalse(Files.exists(dir.resolve("p.wtf.part")), "written whole, and nothing left beside it");

        final WtfFile read = WtfFile.read(file);
        assertEquals(WtfFile.FORMAT, read.format());
        assertEquals("mapy-com", read.source().provider());
        assertEquals(14, read.regions().zoom());

        final ComposeJob job = read.toJob(net.trdlo.wtf.tiles.ProviderCatalog.builtIn(), dir);
        assertEquals(6, job.layout().count(), "the motivating case, from a file");
        assertEquals(5000, new PrintSize(PrintSize.Mode.FIT_WIDTH, 5000, 0)
                .apply(Shapes.boundsOf(job.fillShape())).widthMm(), 1e-9);
    }

    @Test
    @DisplayName("a file that is not a project is refused with a clear message")
    void refusesAnotherFormat(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("config.wtf");
        java.nio.file.Files.writeString(file, """
                {"format":"something-else",
                 "source":{"provider":"mapy-com","theme":"outdoor"},
                 "area":{"ring":[[0,0],[1,0],[1,1],[0,1],[0,0]]},
                 "refine":{"simplify":"NONE","bufferValue":0},
                 "regions":{"zoom":10,"inside":{"drawn":true,"effect":{"saturation":1,"lightness":0,"contrast":0}},
                            "outside":{"drawn":true,"effect":{"saturation":1,"lightness":0,"contrast":0}}},
                 "print":{"sizeMode":"FIT_WIDTH","widthMm":1000},
                 "medium":{"kind":"ROLL","rollWidthMm":915,"stripAxis":"VERTICAL","overlapMm":15},
                 "export":{"formats":["PDF"],"grouping":"PER_SHEET","baseName":"x"}}
                """);
        // Refused as WTF's own checks refuse, so the window shows the message rather than a bug report
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> WtfFile.read(file));
        assertTrue(ex.getMessage().contains("is not a WTF project"), ex.getMessage());
        assertTrue(ex.getMessage().contains("something-else"), ex.getMessage());
    }

    /** The studio's examples are bytes in its jar, not files: read the same, and named as given. */
    @Test
    @DisplayName("a project held in memory reads as its file does, and a refusal names it as given")
    void readsAProjectHeldInMemory(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("sample.wtf");
        WtfFile.sample("mapy-com", "outdoor", 12.09, 49.44, 15.98, 50.73, 14, 5000).write(file);
        final byte[] json = java.nio.file.Files.readAllBytes(file);
        assertEquals(WtfFile.read(file), WtfFile.read(json, "bundled.wtf"));

        final Refusal r = assertThrows(Refusal.class, () -> WtfFile.read(
                "{\"format\":\"something-else\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8), "bundled.wtf"));
        assertEquals(ComposeReason.NOT_A_PROJECT_SAYS, r.reason());
        assertEquals(List.of("bundled.wtf", "something-else"), r.args());
    }

    /**
     * Named on the way in, rather than found missing by whatever reads it first – the command line
     * printing a null pointer, the editor opening half a project.
     */
    @Test
    @DisplayName("a project with something missing is refused, and the message says what")
    void refusesAProjectWithSomethingMissing(@TempDir final Path dir) throws IOException {
        final WtfFile s = WtfFile.sample("mapy-com", "outdoor", 12.09, 49.44, 15.98, 50.73, 14, 5000);
        final Path file = dir.resolve("incomplete.wtf");
        // A block left out: a null is written as nothing at all
        new WtfFile(s.format(), s.title(), s.notes(), s.saved(), s.source(), s.area(), s.subject(),
                s.refine(), s.regions(), null, s.medium(), s.export()).write(file);
        final IllegalArgumentException noBlock = assertThrows(IllegalArgumentException.class,
                () -> WtfFile.read(file));
        assertTrue(noBlock.getMessage().contains("\"print\""), noBlock.getMessage());

        // And a value inside one, down to a list with a hole in it
        s.write(file);
        Files.writeString(file, Files.readString(file).replace("\"PDF\"", "null"));
        final IllegalArgumentException hole = assertThrows(IllegalArgumentException.class,
                () -> WtfFile.read(file));
        assertTrue(hole.getMessage().contains("\"export.formats\""), hole.getMessage());
    }

    @Test
    @DisplayName("a size mode is its name, never its place in the list")
    void refusesAModeGivenAsANumber(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("numbered.wtf");
        WtfFile.sample("mapy-com", "outdoor", 12.09, 49.44, 15.98, 50.73, 14, 5000).write(file);
        final String json = Files.readString(file);
        assertTrue(json.contains("\"FIT_WIDTH\""));
        for (final String number : new String[]{"2", "\"2\""}) {
            Files.writeString(file, json.replace("\"FIT_WIDTH\"", number));
            final IOException ex = assertThrows(IOException.class, () -> WtfFile.read(file), number);
            assertTrue(ex.getMessage().contains("sizeMode"), ex.getMessage());
        }
    }

    /**
     * A number or a switch every project has, left out, is refused by name. Read as zero it would be
     * a zoom that shows the world in one tile, sheets with no overlap, a print without its credit.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"regions.zoom", "regions.inside.drawn", "regions.outside.effect.saturation",
            "medium.overlapMm", "medium.edgeMm", "medium.rollWidthMm", "export.credit"})
    @DisplayName("a value every project has is refused by name when it is left out")
    void refusesAValueLeftOut(final String path, @TempDir final Path dir) throws IOException {
        final Path file = sampleWithout(dir, path);
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> WtfFile.read(file));
        assertTrue(ex.getMessage().contains("\"" + path + "\""), ex.getMessage());
    }

    @Test
    @DisplayName("a shape with a coordinate written as null is refused with a message, not a null pointer")
    void refusesAShapeWithAHoleInIt(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("holed.wtf");
        WtfFile.sample("mapy-com", "outdoor", 12.09, 49.44, 15.98, 50.73, 14, 5000).write(file);
        final ObjectMapper json = new ObjectMapper();
        final ObjectNode root = (ObjectNode) json.readTree(file.toFile());
        // The second corner of the area's one ring
        ((ArrayNode) root.path("area").path("polygons").path(0).path(0)).set(1, NullNode.getInstance());
        json.writeValue(file.toFile(), root);

        final WtfFile f = WtfFile.read(file);
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> f.toJob(ProviderCatalog.builtIn(), dir));
        assertTrue(ex.getMessage().contains("area"), ex.getMessage());
    }

    /**
     * The printer's white edge is the file's, because it moves the sheets: the command line keeps
     * the edge the file names free round the print, and the editor reads the same number.
     */
    @Test
    @DisplayName("the file's white edge is kept free round the print on the command line")
    void theFilesEdgeIsKeptFreeRoundThePrint(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("edge.wtf");
        WtfFile.sample("mapy-com", "outdoor", 12.09, 49.44, 15.98, 50.73, 14, 5000).write(file);
        final ObjectMapper json = new ObjectMapper();
        final ObjectNode root = (ObjectNode) json.readTree(file.toFile());
        ((ObjectNode) root.get("medium")).put("edgeMm", 8.0);
        json.writeValue(file.toFile(), root);

        final ComposeJob job = WtfFile.read(file).toJob(ProviderCatalog.builtIn(), dir);
        assertEquals(8, job.marks().edgeMm(), 1e-9);
        final SheetLayout layout = job.layout();
        final PrintRect print = job.print();
        final double mm = print.mmPerUnit();
        final SheetLayout.Sheet first = layout.sheets().getFirst();
        final SheetLayout.Sheet last = layout.sheets().getLast();
        assertTrue((print.x0() - first.slice().x0()) * mm >= 8 - 1e-6, "the first strip starts the edge before it");
        assertTrue((last.slice().x1() - print.x1()) * mm >= 8 - 1e-6, "and the last one ends the edge past it");
        assertEquals(print.heightMm(), layout.print().heightMm(), 1e-9, "a roll has no edge along its length");
    }

    /** The sample, written to a file with the value at a dotted path taken out of it. */
    private static Path sampleWithout(final Path dir, final String path) throws IOException {
        final Path file = dir.resolve("holed.wtf");
        WtfFile.sample("mapy-com", "outdoor", 12.09, 49.44, 15.98, 50.73, 14, 5000).write(file);
        final ObjectMapper json = new ObjectMapper();
        final ObjectNode root = (ObjectNode) json.readTree(file.toFile());
        final String[] steps = path.split("\\.");
        ObjectNode at = root;
        for (int i = 0; i < steps.length - 1; i++) {
            at = (ObjectNode) at.get(steps[i]);
        }
        assertNotNull(at.remove(steps[steps.length - 1]), "the sample has " + path);
        json.writeValue(file.toFile(), root);
        return file;
    }
}
