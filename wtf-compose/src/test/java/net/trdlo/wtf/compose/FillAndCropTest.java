package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Mercator;
import net.trdlo.wtf.tiles.TileKey;
import net.trdlo.wtf.tiles.TileRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "Fill and crop" takes in more map until a box has the paper's shape, and the map it takes in is
 * printed: its tiles are planned, and the band does not print as background.
 */
class FillAndCropTest {

    private static final int ZOOM = 10;
    /** 4° × 1.4°, much wider than it is tall, printed into a square. */
    private static final LonLatBounds BOX = new LonLatBounds(12, 49.5, 16, 50.9);

    private static ComposeJob job(final Path dir, final PrintSize size) {
        return TestJobs.job(BOX).zoom(ZOOM).print(size.apply(BOX))
                .medium(Medium.paper("A4").sheet(Medium.Orientation.PORTRAIT))
                .formats(ComposeJob.OutputFormat.PNG).perSheet().dir(dir).name("square").build();
    }

    /** Every tile the rectangle covers, as {@code Mercator.range} counts them. */
    private static Set<TileKey> under(final ComposeJob job, final PrintRect r) {
        final TileRange range = Mercator.range(Shapes.fromUnitSquare(r.x0(), r.y0(), r.x1(), r.y1()), ZOOM);
        final Set<TileKey> keys = new HashSet<>();
        for (final int[] xy : range) {
            keys.add(new TileKey(job.provider().id(), job.themeId(), ZOOM, xy[0], xy[1]));
        }
        return keys;
    }

    @Test
    @DisplayName("fill and crop plans every tile under the print, the band it took in included")
    void theBandHasItsTiles(@TempDir final Path dir) {
        final ComposeJob job = job(dir, PrintSize.fill(300, 300));
        final PrintRect print = job.layout().print();
        assertTrue(print.unitHeight() > Shapes.toUnitSquare(Shapes.rectangle(BOX)).getEnvelopeInternal().getHeight() * 1.5,
                "the print took in a band of map above and below the box");

        final Set<TileKey> planned = TilePlan.of(job).distinctKeys();
        final Set<TileKey> expected = under(job, print);
        assertTrue(planned.containsAll(expected), () -> (expected.size() - planned.size())
                + " tiles under the print are not planned, of " + expected.size());
    }

    @Test
    @DisplayName("a box printed to its own shape plans what it always did")
    void aFittedBoxIsUnchanged(@TempDir final Path dir) {
        final ComposeJob job = job(dir, PrintSize.width(150));
        final PrintRect print = job.layout().print();
        assertEquals(under(job, print), TilePlan.of(job).distinctKeys());
    }
}
