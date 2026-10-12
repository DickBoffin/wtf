package net.trdlo.wtf.compose;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import net.trdlo.wtf.tiles.LonLatBounds;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Several shapes in one slot: added while they are being chosen, made one on the way out.
 * <p>
 * A subject is often several things – Germany and Slovakia, a country with a rectangle over the
 * part that matters – and the two halves of that have to agree: the parts stay parts for the
 * editor, and become one area for the engine.
 */
class CombineTest {

    private static final LonLatBounds WEST = new LonLatBounds(12.0, 48.5, 14.0, 50.0);
    private static final LonLatBounds EAST = new LonLatBounds(16.0, 48.5, 18.0, 50.0);
    private static final LonLatBounds OVERLAPS_WEST = new LonLatBounds(13.0, 48.5, 15.0, 50.0);

    @Test
    @DisplayName("adding to an empty slot is the shape itself")
    void theFirstOne() {
        final Geometry one = Shapes.rectangle(WEST);
        assertSame(one, Shapes.combine(null, one));
        assertEquals(1, Shapes.combine(null, one).getNumGeometries());
    }

    @Test
    @DisplayName("two apart are two parts, and neither is changed")
    void twoApart() {
        final Geometry both = Shapes.combine(Shapes.rectangle(WEST), Shapes.rectangle(EAST));
        assertEquals(2, both.getNumGeometries());
        assertEquals(Shapes.boundsOf(Shapes.rectangle(WEST)).west(), Shapes.boundsOf(both).west(), 1e-9);
        assertEquals(Shapes.boundsOf(Shapes.rectangle(EAST)).east(), Shapes.boundsOf(both).east(), 1e-9);
    }

    @Test
    @DisplayName("a third one joins the other two rather than replacing them")
    void threeOfThem() {
        Geometry all = Shapes.combine(Shapes.rectangle(WEST), Shapes.rectangle(EAST));
        all = Shapes.combine(all, Shapes.point(15.0, 49.0));
        assertEquals(3, all.getNumGeometries());
    }

    @Test
    @DisplayName("overlapping parts stay separate until they are unioned, and then they are one")
    void overlapsAreResolvedOnTheWayOut() {
        final Geometry parts = Shapes.combine(Shapes.rectangle(WEST), Shapes.rectangle(OVERLAPS_WEST));
        assertEquals(2, parts.getNumGeometries(), "still two things somebody can edit");

        final Geometry one = Shapes.unioned(parts);
        assertEquals(1, one.getNumGeometries(), "and one thing to print");
        // 12..15 rather than 12..14 plus 13..15 counted twice
        assertEquals(12.0, Shapes.boundsOf(one).west(), 1e-9);
        assertEquals(15.0, Shapes.boundsOf(one).east(), 1e-9);
        assertTrue(one.getArea() < parts.getArea(), "the shared part is not counted twice");
    }

    @Test
    @DisplayName("unioning one part is the part, without asking JTS to do anything")
    void oneIsAlreadyOne() {
        final Geometry one = Shapes.rectangle(WEST);
        assertSame(one, Shapes.unioned(one));
    }

    @Test
    @DisplayName("combined parts survive the validator, which is what the panel calls")
    void theCombinationIsValid() {
        final Geometry parts = Shapes.combine(Shapes.rectangle(WEST), Shapes.rectangle(OVERLAPS_WEST));
        assertDoesNotThrow(() -> Shapes.validate(parts, Shapes.Role.SUBJECT));
        assertDoesNotThrow(() -> Shapes.validate(Shapes.unioned(parts), Shapes.Role.SUBJECT));
    }

    @Test
    @DisplayName("adding a shape over another keeps both exactly as they were")
    void addingDoesNotMerge() {
        final Geometry west = Shapes.rectangle(WEST);
        final Geometry both = Shapes.withPart(west, Shapes.rectangle(OVERLAPS_WEST), Shapes.Role.SUBJECT);

        // Validating the combination does not repair it, since the repair for overlapping parts
        // is a union: two parts would go in and one come out.
        assertEquals(2, both.getNumGeometries(), "two parts in, two parts out");
        assertEquals(Shapes.boundsOf(west), Shapes.boundsOf(both.getGeometryN(0)));
        assertEquals(Shapes.boundsOf(Shapes.rectangle(OVERLAPS_WEST)), Shapes.boundsOf(both.getGeometryN(1)));
    }
}
