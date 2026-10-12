package net.trdlo.wtf.compose;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.ProviderCatalog;
import net.trdlo.wtf.tiles.Refusal;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a project file means by its area: the shape that gets printed, and nothing done to it twice.
 * <p>
 * The area is the answer; the refine block is a note about how the editor reached it. A reader
 * prints the area as it stands, and the editor checks on open that the note still leads to it.
 */
class AreaTest {

    private static final LonLatBounds BOHEMIA = new LonLatBounds(12.09, 49.44, 15.98, 50.73);

    /** The sample, with how its area was worked out claimed over it. */
    private static WtfFile claiming(final WtfFile.@NotNull Refine r) {
        final WtfFile s = WtfFile.sample("mapy-com", "outdoor",
                BOHEMIA.west(), BOHEMIA.south(), BOHEMIA.east(), BOHEMIA.north(), 14, 5000);
        return new WtfFile(s.format(), s.title(), s.notes(), s.saved(),
                s.source(), s.area(), s.subject(), r,
                s.regions(), s.print(), s.medium(), s.export());
    }

    /** Worked out from the subject with this fit and growth. */
    private static WtfFile.@NotNull Refine fitted(final Shapes.@NotNull Simplify fit, final double grow,
                                                  final @Nullable Boolean fade) {
        return new WtfFile.Refine(null, fit, grow, fade);
    }

    /** Grown from this Custom shape. */
    private static WtfFile.@NotNull Refine grownFrom(final @NotNull Geometry custom, final double grow,
                                                     final @Nullable Boolean fade) {
        return new WtfFile.Refine(WtfFile.multiRingOf(custom, null, "drawn"), null, grow, fade);
    }

    private static WtfFile withSubject(final WtfFile f, final Geometry subject,
                                            final Geometry area) {
        return new WtfFile(f.format(), f.title(), f.notes(), f.saved(),
                f.source(), WtfFile.multiRingOf(area, "polygon", null),
                WtfFile.multiRingOf(subject, "polygon", null), f.refine(),
                f.regions(), f.print(), f.medium(), f.export());
    }

    // ------------------------------------------------------------------ the file is what is printed

    @Test
    @DisplayName("the stored area is printed as it stands, however the file says it was reached")
    void theFileIsWhatIsPrinted(@TempDir final Path dir) {
        final WtfFile f = claiming(fitted(Shapes.Simplify.BOUNDING_BOX, 50_000, null));

        final ComposeJob job = f.toJob(ProviderCatalog.builtIn(), dir);
        final LonLatBounds printed = Shapes.boundsOf(job.fillShape());

        // Exactly the stored area: the growth in the refine block is not applied to it again.
        assertEquals(BOHEMIA.west(), printed.west(), 1e-9);
        assertEquals(BOHEMIA.east(), printed.east(), 1e-9);
        assertEquals(BOHEMIA.south(), printed.south(), 1e-9);
        assertEquals(BOHEMIA.north(), printed.north(), 1e-9);
    }

    @Test
    @DisplayName("an area worked out from nothing the file names is not boxed or grown by the engine either")
    void anAreaFromNothingIsLeftAlone(@TempDir final Path dir) {
        final ComposeJob job = claiming(WtfFile.Refine.NONE).toJob(ProviderCatalog.builtIn(), dir);
        assertEquals(BOHEMIA.east(), Shapes.boundsOf(job.fillShape()).east(), 1e-9);
    }

    // ------------------------------------------------------------------------- does it reproduce

    @Test
    @DisplayName("a file whose area follows from its subject says so")
    void aFileThatReproducesItsAreaSaysSo() {
        final Geometry subject = Shapes.rectangle(BOHEMIA);
        final Geometry area = Shapes.areaFrom(subject, Shapes.Simplify.BOUNDING_BOX, 10_000);
        final WtfFile f = withSubject(claiming(fitted(Shapes.Simplify.BOUNDING_BOX, 10_000, null)),
                subject, area);

        final WtfFile.AreaCheck check = f.checkArea();
        assertTrue(check.claimed());
        assertTrue(check.reproduces());
    }

    @Test
    @DisplayName("a file whose area does not follow from its subject says how far off it is")
    void aFileThatDoesNotReproduceItsAreaSaysSo() {
        final Geometry subject = Shapes.rectangle(BOHEMIA);
        // The area stored ungrown, while the file claims ten kilometers of growth
        final WtfFile f = withSubject(claiming(fitted(Shapes.Simplify.BOUNDING_BOX, 10_000, null)),
                subject, subject);

        final WtfFile.AreaCheck check = f.checkArea();
        assertTrue(check.claimed());
        assertFalse(check.reproduces());
        // The growth that was not applied, on the diagonal, and read at the latitude rate – so
        // comfortably more than ten kilometers, and nothing like zero. See Shapes.deviationMeters.
        assertTrue(check.deviationMeters() > 10_000 && check.deviationMeters() < 25_000,
                "unexpected deviation: " + check.deviationMeters());
    }

    @Test
    @DisplayName("a file that names neither a fit nor a Custom shape is not checked against anything")
    void aFileThatClaimsNothingIsNotChecked() {
        final WtfFile.AreaCheck check = claiming(WtfFile.Refine.NONE).checkArea();
        assertFalse(check.claimed());
        assertTrue(check.reproduces(), "nothing was claimed, so nothing failed");
    }

    @Test
    @DisplayName("the check tolerates arithmetic noise and catches a mistake")
    void reproductionToleratesArithmeticNoiseAndNotAMistake() {
        final Geometry exact = Shapes.rectangle(BOHEMIA);

        assertTrue(Shapes.reproduces(exact, shifted(BOHEMIA, 1e-9)), "0.1 mm is noise");
        assertTrue(Shapes.reproduces(exact, shifted(BOHEMIA, 1e-7)), "1 cm is still noise");
        assertFalse(Shapes.reproduces(exact, shifted(BOHEMIA, 0.01)), "a kilometer is a mistake");
    }

    @Test
    @DisplayName("the tolerance is a share of the shape, not a fixed distance")
    void theToleranceIsRelativeToTheShape() {
        // Thirty meters is nothing across Bohemia and a lot across one valley.
        final double thirtyMeters = 30 / Shapes.METERS_PER_DEGREE_LATITUDE;
        assertTrue(Shapes.reproduces(Shapes.rectangle(BOHEMIA), shifted(BOHEMIA, thirtyMeters)));

        final LonLatBounds valley = new LonLatBounds(14.00, 50.00, 14.05, 50.04);
        assertFalse(Shapes.reproduces(Shapes.rectangle(valley), shifted(valley, thirtyMeters)));
    }

    @Test
    @DisplayName("the editor and the reader work the area out the same way")
    void oneDerivationServesTheEditorAndTheReader() {
        final Geometry subject = Shapes.GF.createGeometryCollection(new Geometry[]{
                Shapes.rectangle(BOHEMIA),
                Shapes.rectangle(new LonLatBounds(16.5, 48.5, 18.0, 49.5))}).union();

        final Geometry viaHelper = Shapes.areaFrom(subject, Shapes.Simplify.CONVEX_HULL, 5_000);
        final Geometry byHand = Shapes.validate(Shapes.refine(
                Shapes.validate(Shapes.unioned(subject), Shapes.Role.SUBJECT),
                Shapes.Simplify.CONVEX_HULL, 5_000), Shapes.Role.AREA);

        assertEquals(0.0, Shapes.deviation(viaHelper, byHand), 1e-12,
                "if these ever differ, every file the editor writes fails its own check on reopen");
    }

    // ------------------------------------------------------------------------------- the fade

    @Test
    @DisplayName("a fade starts from the fit before the growth, and only where there is a growth")
    void aFadeStartsFromTheFit(@TempDir final Path dir) throws java.io.IOException {
        final Geometry subject = Shapes.GF.createGeometryCollection(new Geometry[]{
                Shapes.rectangle(BOHEMIA),
                Shapes.rectangle(new LonLatBounds(16.5, 48.5, 18.0, 49.5))}).union();
        final Geometry area = Shapes.areaFrom(subject, Shapes.Simplify.CONVEX_HULL, 10_000);
        final WtfFile fading = withSubject(claiming(fitted(Shapes.Simplify.CONVEX_HULL, 10_000, true)),
                subject, area);

        final Path file = dir.resolve("fading.wtf");
        fading.write(file);
        final WtfFile back = WtfFile.read(file);
        assertEquals(Boolean.TRUE, back.refine().fade(), "the file keeps it");
        assertTrue(back.checkArea().reproduces(), "and the area is the same area, fade or not");

        final ComposeJob job = back.toJob(ProviderCatalog.builtIn(), dir);
        assertNotNull(job.fadeFrom());
        assertEquals(0.0, Shapes.deviation(Shapes.fitOf(subject, Shapes.Simplify.CONVEX_HULL), job.fadeFrom()),
                1e-12, "the hull of the subject, ungrown");
        assertEquals(0.0, Shapes.deviation(area, job.fillShape()), 1e-12, "and the area printed as it stands");

        final WtfFile hard = withSubject(claiming(fitted(Shapes.Simplify.CONVEX_HULL, 10_000, null)),
                subject, area);
        hard.write(file);
        assertFalse(java.nio.file.Files.readString(file).contains("\"fade\""), "a hard edge is not written");
        assertNull(WtfFile.read(file).toJob(ProviderCatalog.builtIn(), dir).fadeFrom());

        final WtfFile ungrown = withSubject(claiming(fitted(Shapes.Simplify.CONVEX_HULL, 0, true)), subject,
                Shapes.areaFrom(subject, Shapes.Simplify.CONVEX_HULL, 0));
        assertNull(ungrown.toJob(ProviderCatalog.builtIn(), dir).fadeFrom(), "no growth, no band to fade across");
    }

    // ------------------------------------------------------------------------ a Custom area

    /** Two overlapping parts, as a Custom shape built in the editor keeps them: not made one. */
    private static final Geometry TWO_PARTS = Shapes.GF.createMultiPolygon(new Polygon[]{
            Shapes.rectangle(BOHEMIA),
            Shapes.polygon(new Coordinate[]{new Coordinate(15.0, 49.0), new Coordinate(18.5, 48.6),
                    new Coordinate(17.0, 50.5)})});

    /**
     * A Custom area is a note as the fit is: the file states the area grown, the editor checks that
     * the Custom shape still grows into it, and a reader fades from the shape made one – the edge
     * the area was grown from.
     */
    @Test
    @DisplayName("a Custom area reproduces from its shape, and fades from its parts made one")
    void aCustomAreaReproducesAndFades(@TempDir final Path dir) throws java.io.IOException {
        final Geometry area = Shapes.areaFrom(TWO_PARTS, Shapes.Simplify.NONE, 10_000);
        final WtfFile f = withSubject(claiming(grownFrom(TWO_PARTS, 10_000, true)),
                Shapes.rectangle(new LonLatBounds(14.0, 49.5, 15.0, 50.3)), area);
        final Path file = dir.resolve("custom.wtf");
        f.write(file);

        final WtfFile back = WtfFile.read(file);
        assertEquals(2, back.refine().custom().polygons().size(), "the parts, as parts");
        assertEquals("drawn", back.refine().custom().source());
        assertNull(back.refine().simplify());
        final WtfFile.AreaCheck check = back.checkArea();
        assertTrue(check.claimed(), "a Custom shape is a claim");
        assertTrue(check.reproduces(), () -> "off by " + check.deviationMeters() + " m");

        final ComposeJob job = back.toJob(ProviderCatalog.builtIn(), dir);
        assertNotNull(job.fadeFrom());
        assertEquals(0.0, Shapes.deviation(Shapes.fitOf(TWO_PARTS, Shapes.Simplify.NONE), job.fadeFrom()),
                1e-12, "the Custom shape made one, ungrown");
        assertEquals(1, job.fadeFrom().getNumGeometries(), "one shape, overlap and all");
        assertEquals(0.0, Shapes.deviation(area, job.fillShape()), 1e-12, "and the area printed as it stands");
        assertNotNull(job.subject(), "the subject is the cut line all the same");
    }

    @Test
    @DisplayName("a Custom area whose shape no longer grows into it says how far off it is")
    void aDriftedCustomAreaSaysSo() {
        // The area stored ungrown, while the file claims ten kilometers of growth round the shape
        final WtfFile f = claiming(grownFrom(TWO_PARTS, 10_000, null));
        final WtfFile drifted = new WtfFile(f.format(), f.title(), f.notes(), f.saved(), f.source(),
                WtfFile.multiRingOf(Shapes.areaFrom(TWO_PARTS, Shapes.Simplify.NONE, 0), "polygon", "custom"),
                f.subject(), f.refine(), f.regions(), f.print(), f.medium(), f.export());

        final WtfFile.AreaCheck check = drifted.checkArea();
        assertTrue(check.claimed());
        assertFalse(check.reproduces());
        assertTrue(check.deviationMeters() > 10_000, "unexpected deviation: " + check.deviationMeters());
    }

    @Test
    @DisplayName("a refine block with a Custom shape and a fit both is refused as it is read")
    void aCustomShapeAndAFitBothAreRefused(@TempDir final Path dir) throws java.io.IOException {
        final WtfFile both = claiming(new WtfFile.Refine(WtfFile.multiRingOf(TWO_PARTS, null, "drawn"),
                Shapes.Simplify.BOUNDING_BOX, 10_000.0, null));
        final Path file = dir.resolve("both.wtf");
        both.write(file);

        final Refusal r = assertThrows(Refusal.class, () -> WtfFile.read(file));
        assertEquals(ComposeReason.REFINE_TWO_SOURCES, r.reason());
        assertEquals(List.of("both.wtf"), r.args());
    }

    @Test
    @DisplayName("a Custom shape names its growth, as a fit does")
    void aCustomShapeNeedsItsGrowth(@TempDir final Path dir) throws java.io.IOException {
        final Path file = dir.resolve("ungrown.wtf");
        claiming(new WtfFile.Refine(WtfFile.multiRingOf(TWO_PARTS, null, "drawn"), null, null, null)).write(file);

        final Refusal r = assertThrows(Refusal.class, () -> WtfFile.read(file));
        assertEquals(ComposeReason.FIELD_MISSING, r.reason());
        assertEquals(List.of("ungrown.wtf", "refine.bufferValue"), r.args());
    }

    @Test
    @DisplayName("the fit a fade starts from is the area before its growth")
    void theFitIsTheAreaUngrown() {
        final Geometry subject = Shapes.GF.createGeometryCollection(new Geometry[]{
                Shapes.rectangle(BOHEMIA),
                Shapes.rectangle(new LonLatBounds(16.5, 48.5, 18.0, 49.5))}).union();
        for (final Shapes.Simplify fit : Shapes.Simplify.values()) {
            assertEquals(0.0, Shapes.deviation(Shapes.areaFrom(subject, fit, 0),
                    Shapes.fitOf(subject, fit)), 1e-12, fit::name);
        }
    }

    /** The same rectangle, moved bodily by a distance in degrees. */
    private static Geometry shifted(final LonLatBounds b, final double by) {
        return Shapes.rectangle(new LonLatBounds(
                b.west() + by, b.south() + by, b.east() + by, b.north() + by));
    }
}
