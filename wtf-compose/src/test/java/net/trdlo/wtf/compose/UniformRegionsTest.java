package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.LonLatBounds;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Geometry;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A subject whose two sides are drawn alike cuts nothing: the print is one region, and its tiles
 * are planned once. The ring along the outline is not planned for both sides, which an
 * export that clips nothing when the sides are alike would place twice.
 */
class UniformRegionsTest {

    private static final int ZOOM = 6;
    private static final LonLatBounds BISCAY = new LonLatBounds(-10, 46, 4, 50);
    private static final Geometry SUBJECT = Shapes.rectangle(new LonLatBounds(-6.3, 47.1, 0.4, 49.1));

    private static ComposeJob job(final Path dir, final @Nullable Geometry subject, final Regions regions) {
        return TestJobs.job(BISCAY).subject(subject).regions(regions).width(280).dir(dir).name("uniform").build();
    }

    @ParameterizedTest(name = "stroked: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("two sides drawn alike place as many tiles as one region")
    void plannedOnce(final boolean stroked, @TempDir final Path dir) {
        final Regions alike = new Regions(ZOOM, Regions.RegionSpec.PLAIN, Regions.RegionSpec.PLAIN,
                stroked ? new Regions.BoundaryStroke(0.5, 0x000000) : null);
        assertTrue(alike.isUniform(), "a stroke is drawn on top, and divides nothing");

        final TilePlan.Plan cut = TilePlan.of(job(dir, SUBJECT, alike));
        final TilePlan.Plan single = TilePlan.of(job(dir, null, Regions.single(ZOOM)));

        assertEquals(single.placements(), cut.placements());
        assertEquals(single.distinctKeys(), cut.distinctKeys());
        assertNotNull(cut.projected().subject(), "the subject stays on the plan, for the stroke");
        assertEquals(stroked, alike.describe().contains("boundary stroked"));
    }
}
