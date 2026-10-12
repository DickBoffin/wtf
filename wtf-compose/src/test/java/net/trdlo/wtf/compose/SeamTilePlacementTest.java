package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Mercator;
import net.trdlo.wtf.tiles.TileKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A tile can be planned on two neighboring sheets at once, along the seam – that is correct, and
 * how a seam is drawn. What is not correct is placing it on a sheet that shows none of it: a tile
 * the seam runs through can meet the printed shape, or its region's side of the subject, only on
 * one sheet's own side of the seam, and on the other sheet it is drawn under a clip that hides it.
 * Which side of the subject a tile is on stays the whole tile's question all the same, so that
 * "a tile the outline crosses" does not depend on where the sheets are cut.
 * <p>
 * Two sheets side by side, cut from a print wider than the shapes ask for, so a shape's edge sits
 * inside one particular tile rather than on a sheet's own edge.
 */
class SeamTilePlacementTest {

    // The print's own midpoint – where two equal roll strips meet – is 23°, 203/360 of the unit
    // square, which is no power-of-two fraction: at 22.5°, which 2° to 43° has, it is 9/16, and the
    // seam runs along a tile's edge at every zoom from 4 up.
    private static final LonLatBounds WORLD = new LonLatBounds(2.3, 30, 43.7, 60);

    /**
     * Two sheets side by side on a roll, the zoom and the tile their seam runs through, and
     * {@code edgeX}: halfway between the seam and that tile's far edge, where a shape's edge
     * crosses the tile on sheet 1's side of the seam only.
     */
    private record Seam(PrintRect print, Medium roll, int zoom, int tileX, int tileY, double edgeX) {
    }

    private static Seam seam() {
        // A print two sheets wide on a roll, with no overlap: the sheets meet with nothing shared,
        // so the seam is one exact coordinate and every tile the seam crosses is a candidate for
        // both sheets' own ranges.
        final PrintRect print = PrintSize.width(400).apply(WORLD);
        final Medium roll = new Medium.Roll(200, Medium.Axis.VERTICAL);
        final SheetLayout probe = SheetLayout.plan(print, roll, 0, 0);
        assertEquals(2, probe.count(), "two sheets side by side");
        assertEquals(2, probe.columns());
        assertEquals(1, probe.rows(), "one row, so a tile's sheet depends only on where it falls across");
        final double seamX = probe.sheets().get(0).slice().x1();
        assertEquals(seamX, probe.sheets().get(1).slice().x0(), 1e-9, "no gap and no overlap at the seam");

        // A zoom whose tile at the seam has a comfortable share on each side, so the numbers below
        // are never close enough to the tile's own edges for a rounding error to matter, and lies
        // well inside the print, so that a shape's edge at edgeX is on the paper
        for (int z = 2; z <= 14; z++) {
            final int n = Mercator.tileCount(z);
            final double u = 1.0 / n;
            final int tx = (int) Math.floor(seamX / u);
            final double frac = seamX / u - tx;
            if (frac > 0.2 && frac < 0.8 && tx * u > print.x0() && (tx + 1) * u < print.x1()) {
                // A row safely inside the print's own reach: the middle of the range the print's
                // own top and bottom cover, not the tile grid's – at a coarse zoom the print is
                // nowhere near the whole globe the grid is.
                final int rowLow = (int) Math.floor(Math.min(print.y0(), print.y1()) * n);
                final int rowHigh = (int) Math.ceil(Math.max(print.y0(), print.y1()) * n) - 1;
                return new Seam(print, roll, z, tx, (rowLow + rowHigh) / 2, (seamX + (tx + 1) * u) / 2);
            }
        }
        return fail("no zoom whose seam tile has room either side of the seam");
    }

    private static ComposeJob job(final Seam s, final Geometry fill, final Geometry subject, final Regions regions,
                                  final Path dir) {
        final ComposeJob job = TestJobs.job(fill).subject(subject).regions(regions).print(s.print())
                .medium(s.roll()).formats(ComposeJob.OutputFormat.PNG).perSheet().dir(dir)
                .name("seam").build();
        assertEquals(2, job.layout().count(), "the shapes are narrower than the print, which still has two sheets");
        return job;
    }

    private static Set<TileKey> keysOf(final TilePlan.SheetPlan sp, final TilePlan.Side side) {
        final Set<TileKey> keys = new HashSet<>();
        for (final TilePlan.RegionTiles r : sp.regions()) {
            if (side == null || r.side() == side) {
                keys.addAll(r.keys());
            }
        }
        return keys;
    }

    @Test
    @DisplayName("a tile the seam runs through is placed only on the sheet whose own part meets the shape")
    void notPlacedOnTheSheetItOnlyLooksLikeItReaches(@TempDir final Path dir) {
        final Seam s = seam();
        // The printed shape's own left edge at edgeX: the seam tile's part on sheet 0 (west of the
        // seam) never meets it, and its part on sheet 1 (east of the seam, up to the tile's own
        // right edge) does. Two of the print's own corners, converted back to degrees, and a nick
        // at one so the shape is not a rectangle, since a plain rectangle would be swapped back
        // for the print rectangle it very nearly is (see TilePlan.printedShape).
        final double westLon = Mercator.unitXToLon(s.edgeX()), eastLon = Mercator.unitXToLon(s.print().x1());
        final double lat0 = Mercator.unitYToLat(s.print().y0()), lat1 = Mercator.unitYToLat(s.print().y1());
        final double nick = 1e-6;
        final Polygon shape = Shapes.polygon(new Coordinate[]{
                new Coordinate(westLon, lat0), new Coordinate(eastLon, lat0), new Coordinate(eastLon, lat1),
                new Coordinate(westLon + nick, lat1), new Coordinate(westLon, lat1 + (lat0 - lat1) * 1e-6)});
        assertFalse(Shapes.isRectangle(shape), "nicked so printedShape uses the shape, not the print rectangle");

        final ComposeJob job = job(s, shape, null, Regions.single(s.zoom()), dir);
        final TilePlan.Plan plan = TilePlan.of(job);
        final TileKey seamTile = job.key(s.zoom(), s.tileX(), s.tileY());
        assertFalse(keysOf(plan.sheets().get(0), null).contains(seamTile),
                "sheet 0's own part of this tile is west of the shape's edge, so never meets it");
        assertTrue(keysOf(plan.sheets().get(1), null).contains(seamTile),
                "sheet 1's own part of the same tile straddles the shape's edge, so it does");
    }

    /**
     * The subject's west edge at {@code edgeX}, everything else of it past the print: its outline
     * crosses the seam tile on sheet 1's side of the seam only, and sheet 0's part of the tile is
     * clear of it.
     */
    @Test
    @DisplayName("which side of the outline a seam tile is on is asked of the whole tile")
    void theSideIsTheWholeTilesQuestion(@TempDir final Path dir) {
        final Seam s = seam();
        final Polygon fill = Shapes.rectangle(WORLD);
        final Polygon subject = Shapes.rectangle(new LonLatBounds(Mercator.unitXToLon(s.edgeX()), 20, 50, 70));
        final Regions regions = Regions.subjectAndFadedSurround(s.zoom());

        // Sheet 0 shows the tile's part outside the subject and none inside it; sheet 1 shows both,
        // since the outline crosses its part
        final ComposeJob touching = job(s, fill, subject, regions, dir);
        final TileKey seamTile = touching.key(s.zoom(), s.tileX(), s.tileY());
        final TilePlan.Plan on = TilePlan.of(touching);
        assertTrue(keysOf(on.sheets().get(0), TilePlan.Side.OUTSIDE).contains(seamTile));
        assertFalse(keysOf(on.sheets().get(0), TilePlan.Side.INSIDE).contains(seamTile),
                "sheet 0's part is clear of the subject, so the inside's clip would hide all of it");
        assertTrue(keysOf(on.sheets().get(1), TilePlan.Side.INSIDE).contains(seamTile));
        assertTrue(keysOf(on.sheets().get(1), TilePlan.Side.OUTSIDE).contains(seamTile));
    }
}
