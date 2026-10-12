package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.LonLatBounds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a job prints and how it fades, asked without planning its tiles – as the studio's 1:1
 * preview asks – and the same answers the plan gives the exporters.
 */
class PrintedForPreviewTest {

    private static final LonLatBounds BISCAY = new LonLatBounds(-10, 46, 4, 50);
    private static final LonLatBounds FIT = new LonLatBounds(-7, 47, 1, 49);

    @Test
    @DisplayName("an outline prints itself, and a box that is not exact prints the sheets' rectangle")
    void printedIsThePlansShape() {
        final Geometry triangle = Shapes.polygon(new Coordinate[]{new Coordinate(-10, 46),
                new Coordinate(4, 46), new Coordinate(-3, 50), new Coordinate(-10, 46)});
        final ComposeJob outline = TestJobs.job(triangle).build();
        assertTrue(TilePlan.printed(outline).equalsExact(Shapes.toUnitSquare(triangle), 1e-15));

        final ComposeJob box = TestJobs.job(BISCAY).build();
        assertTrue(TilePlan.printed(box).equalsExact(TilePlan.of(box).projected().printed(), 0), "a box");
        final PrintRect sheets = box.layout().print();
        assertTrue(TilePlan.printed(box).getEnvelopeInternal().equals(
                new org.locationtech.jts.geom.Envelope(sheets.x0(), sheets.x1(), sheets.y0(), sheets.y1())),
                "and it is the sheets' rectangle, the spare paper's map included");
        final ComposeJob exact = TestJobs.job(BISCAY).exact(true).build();
        assertTrue(TilePlan.printed(exact).equalsExact(Shapes.toUnitSquare(Shapes.rectangle(BISCAY)), 1e-15));
    }

    @Test
    @DisplayName("the fade is the one the exporters lay over the map, and there is none without a fit")
    void fadeIsThePlans() {
        final ComposeJob hard = TestJobs.job(BISCAY).build();
        assertNull(TilePlan.fade(hard, TilePlan.printed(hard)));

        final ComposeJob faded = TestJobs.job(BISCAY).fadeFrom(Shapes.rectangle(FIT)).build();
        final FadeMask mask = TilePlan.fade(faded, TilePlan.printed(faded));
        assertNotNull(mask);
        assertArrayEquals(java.util.Objects.requireNonNull(TilePlan.of(faded).fade()).grid().digest(),
                mask.grid().digest());
    }
}
