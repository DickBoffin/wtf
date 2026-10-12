package net.trdlo.wtf.compose;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import net.trdlo.wtf.tiles.LonLatBounds;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The strip arithmetic. Getting this wrong by a millimeter over six strips is six millimeters of
 * wall, and getting the count wrong is a wasted afternoon at a print shop.
 */
class SheetLayoutTest {

    /** The wall the whole project exists for: Bohemia, 5 m wide, on a 915 mm roll. */
    private static PrintRect wall(final double widthMm) {
        return PrintSize.width(widthMm).apply(new LonLatBounds(12.09, 49.44, 15.98, 50.73));
    }

    @Test
    @DisplayName("five meters on a 915 mm roll with 15 mm overlap is six strips")
    void theMotivatingCase() {
        final SheetLayout l = SheetLayout.plan(wall(5000), new Medium.Roll(915, Medium.Axis.VERTICAL),
                15, 0);
        assertEquals(6, l.count(), "ceil((5000 - 15) / 900) = 6");
        assertEquals(6, l.columns());
        assertEquals(1, l.rows(), "a vertical strip is the full print height");
        assertEquals(5415, l.print().widthMm(), 0.001,
                "the 415 mm of spare paper is spent on more map, so the print grows to what the paper covers");
        assertEquals(207.5, (wall(5000).x0() - l.print().x0()) * l.print().mmPerUnit(), 1e-6,
                "half of it at each end");
        for (final SheetLayout.Sheet s : l.sheets()) {
            assertEquals(915, s.widthMm(), 0.001);
        }
        assertEquals(900, l.sheets().get(1).xMm(), 0.001, "each strip starts one overlap back");
    }

    @Test
    @DisplayName("the formula: n = ceil((L - o) / (w - o))")
    void stripCount() {
        assertEquals(6, SheetLayout.cut(5000, 915, 15).n());
        assertEquals(1, SheetLayout.cut(500, 915, 15).n(), "one sheet is enough");
        assertEquals(2, SheetLayout.cut(1000, 915, 15).n());
        assertEquals(1, SheetLayout.cut(915, 915, 15).n(), "exactly one sheet");
    }

    @Test
    @DisplayName("a negative overlap is a deliberate gap and needs no special case")
    void negativeOverlap() {
        final SheetLayout.Sheet first = SheetLayout.plan(wall(2000),
                new Medium.Roll(500, Medium.Axis.VERTICAL), -10, 0)
                .sheets().getFirst();
        assertEquals(0, first.xMm(), 0.001);
        final SheetLayout l = SheetLayout.plan(wall(2000), new Medium.Roll(500, Medium.Axis.VERTICAL),
                -10, 0);
        assertEquals(510, l.sheets().get(1).xMm(), 0.001, "a gap of 10 mm between strips");
    }

    @Test
    @DisplayName("an overlap at least as wide as the sheet has no solution and says so")
    void impossibleOverlap() {
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SheetLayout.cut(5000, 915, 915));
        assertTrue(ex.getMessage().contains("no solution"), ex.getMessage());
    }

    @Test
    @DisplayName("horizontal strips cut the other way")
    void horizontalStrips() {
        final SheetLayout l = SheetLayout.plan(wall(5000), new Medium.Roll(915, Medium.Axis.HORIZONTAL),
                15, 0);
        assertEquals(1, l.columns(), "a horizontal strip is the full print width");
        assertTrue(l.rows() >= 2);
        assertEquals(915, l.sheets().getFirst().heightMm(), 0.001);
    }

    @Test
    @DisplayName("cut sheets tile in both directions")
    void cutSheets() {
        // 3 m wide makes the print about 1.43 m tall, which needs A0 landscape both ways
        final SheetLayout l = SheetLayout.plan(wall(3000),
                Medium.paper("A0").sheet(Medium.Orientation.LANDSCAPE), 10, 0);
        assertTrue(l.columns() > 1 && l.rows() > 1,
                "expected a grid, got " + l.columns() + " x " + l.rows());
        assertEquals(l.columns() * l.rows(), l.count());
    }

    @Test
    @DisplayName("every sheet's slice is inside the print and the sheets together cover it")
    void slicesCoverThePrint() {
        final SheetLayout l = SheetLayout.plan(wall(5000), new Medium.Roll(915, Medium.Axis.VERTICAL),
                15, 0);
        final PrintRect p = l.print();
        double rightmost = 0;
        for (final SheetLayout.Sheet s : l.sheets()) {
            assertTrue(s.slice().x0() >= p.x0() - 1e-9, "sheet " + s.index() + " starts inside the print");
            assertTrue(s.slice().x1() <= p.x1() + 1e-9, "sheet " + s.index() + " ends inside the print");
            assertEquals(p.mmPerUnit(), s.slice().mmPerUnit(), p.mmPerUnit() * 1e-9,
                    "a sheet must not change the scale");
            rightmost = Math.max(rightmost, s.xMm() + s.widthMm());
        }
        assertTrue(rightmost >= p.widthMm() - 1e-6, "the sheets cover the whole print");
    }

    @Test
    @DisplayName("2A0 is the two-square-meter sheet, which is what the brief's A00 meant")
    void paperSizes() {
        final Medium.Paper a0 = Medium.paper("A0");
        assertEquals(1.0, a0.widthMm() * a0.heightMm() / 1_000_000, 0.01, "A0 is one square meter");
        final Medium.Paper a2 = Medium.paper("2A0");
        assertEquals(2.0, a2.widthMm() * a2.heightMm() / 1_000_000, 0.01, "2A0 is two");
        assertEquals(4.0, Medium.paper("4A0").widthMm() * Medium.paper("4A0").heightMm() / 1_000_000, 0.01);
        assertThrows(IllegalArgumentException.class, () -> Medium.paper("A00"));
    }

    @Test
    @DisplayName("the layout diagram names every sheet, so six strips on a floor are not identical")
    void diagram() {
        final String d = SheetLayout.plan(wall(5000), new Medium.Roll(915, Medium.Axis.VERTICAL),
                15, 0).describe();
        assertTrue(d.contains("[ 1]") && d.contains("[ 6]"), d);
        assertTrue(d.contains("Sheet 1"), d);
    }

    /** About 1.43 m tall at 3 m wide: A0 landscape cuts it three across and two down. */
    private static SheetLayout grid() {
        return SheetLayout.plan(wall(3000), Medium.paper("A0").sheet(Medium.Orientation.LANDSCAPE), 10, 0);
    }

    @Test
    @DisplayName("cut sheets are numbered row by row, 1, 2, 3 along the top (D36)")
    void rowMajor() {
        final SheetLayout l = grid();
        assertEquals(3, l.columns());
        assertEquals(2, l.rows());
        for (final SheetLayout.Sheet s : l.sheets()) {
            assertEquals(s.row() * l.columns() + s.column() + 1, s.index(), s.toString());
            assertEquals(s.index(), l.sheets().indexOf(s) + 1, "and they are listed in that order");
        }
        assertTrue(l.describe().contains("[ 1][ 2][ 3]"), l.describe());
        // A strip layout is one row, so the strips are numbered along the roll as before
        final SheetLayout strips = SheetLayout.plan(wall(5000), new Medium.Roll(915, Medium.Axis.VERTICAL),
                15, 0);
        for (final SheetLayout.Sheet s : strips.sheets()) {
            assertEquals(s.column() + 1, s.index());
        }
    }

    @Test
    @DisplayName("on a grid each neighbor starts one overlap back, across and down alike")
    void oneOverlapBothWays() {
        final SheetLayout l = grid();
        final SheetLayout.Sheet first = l.sheets().getFirst();
        assertEquals(first.widthMm() - 10, l.sheets().get(1).xMm(), 1e-9, "across");
        assertEquals(first.heightMm() - 10, l.sheets().get(l.columns()).yMm(), 1e-9, "down");
        assertTrue(l.describe().contains("overlap 10.0 mm"), l.describe());
    }

    @Test
    @DisplayName("one sheet on an axis: the print grows to the paper, and the sheet is the paper")
    void oneSheetOnAnAxis() {
        // 400 mm wide and about 176 mm tall on A4 portrait: two sheets across, one down
        final double printMm = wall(400).heightMm();
        assertTrue(printMm < 297, "the print is shorter than the paper: " + printMm);
        final SheetLayout l = SheetLayout.plan(wall(400), Medium.paper("A4").sheet(Medium.Orientation.PORTRAIT),
                10, 0);
        assertEquals(2, l.columns());
        assertEquals(1, l.rows());
        assertEquals(297, l.print().heightMm(), 1e-9, "the spare paper down is more map");
        for (final SheetLayout.Sheet s : l.sheets()) {
            assertEquals(297, s.heightMm(), 1e-9, "the sheet is as tall as the paper");
            assertEquals(l.print().y1(), s.slice().y1(), 1e-12,
                    "the sheet's slice ends where the print ends, not a tile past it");
        }
        assertEquals(10, l.overlapMm(), 1e-9, "the overlap across is the one asked for");

        // The same with one sheet both ways: a 150 mm print on A4 is the whole sheet
        final SheetLayout one = SheetLayout.plan(wall(150), Medium.paper("A4").sheet(Medium.Orientation.PORTRAIT),
                10, 0);
        assertEquals(1, one.count());
        assertEquals(210, one.sheets().getFirst().widthMm(), 1e-9);
        assertEquals(297, one.sheets().getFirst().heightMm(), 1e-9);
        assertEquals(60, SheetLayout.cut(150, 210, 10).slackMm(), 1e-9, "60 mm to spare, 30 at each side");
    }

    // --------------------------------------------------------------------- the printer's white edge

    /** How far the print lies inside the paper on each outer side: left, top, right, bottom. */
    private static double[] paperRound(final PrintRect print, final SheetLayout l) {
        double left = Double.MAX_VALUE, top = Double.MAX_VALUE, right = -Double.MAX_VALUE, bottom = -Double.MAX_VALUE;
        for (final SheetLayout.Sheet s : l.sheets()) {
            left = Math.min(left, s.slice().x0());
            top = Math.min(top, s.slice().y0());
            right = Math.max(right, s.slice().x1());
            bottom = Math.max(bottom, s.slice().y1());
        }
        final double mm = print.mmPerUnit();
        return new double[] {(print.x0() - left) * mm, (print.y0() - top) * mm,
                (right - print.x1()) * mm, (bottom - print.y1()) * mm};
    }

    @Test
    @DisplayName("the printer's white edge is kept free round the print, once on each outer side")
    void theWhiteEdgeIsKeptFreeRoundThePrint() {
        // Three A4 sheets across with 15 mm overlap hold 630 − 30 = 600 mm of paper, and a 5 mm
        // edge on the far left and the far right leaves 590 mm of print
        final PrintRect print = wall(590);
        final Medium a4 = Medium.paper("A4").sheet(Medium.Orientation.PORTRAIT);
        final SheetLayout l = SheetLayout.plan(print, a4, 15, 5);
        assertEquals(3, l.columns(), "exactly the three sheets the count asked for");
        final double[] round = paperRound(print, l);
        assertEquals(5, round[0], 1e-6, "no paper to spare across: the far left keeps the edge free, and no more");
        assertEquals(5, round[2], 1e-6, "and so does the far right");
        assertTrue(round[1] >= 5 - 1e-6 && round[3] >= 5 - 1e-6,
                "and the top and the bottom at least that: " + round[1] + ", " + round[3]);
        // Once, not at every seam: the overlap is what hides the edge between two sheets
        assertEquals(195, SheetLayout.plan(print, a4, 15, 5).sheets().get(1).xMm(), 1e-9);
        assertEquals(4, SheetLayout.plan(wall(591), a4, 15, 5).columns(),
                "a millimeter more is a sheet more");
        assertEquals(3, SheetLayout.plan(wall(600), a4, 15, 0).columns(),
                "with no edge, the paper is the print, as before");
    }

    @Test
    @DisplayName("a roll keeps the edge free across the roll only: the strip is as long as the print")
    void aRollKeepsTheEdgeAcrossOnly() {
        // Six strips cover 6 × 915 − 5 × 15 = 5415 mm, which is the print and a 5 mm edge each side
        // with no paper to spare
        final PrintRect print = wall(5405);
        final SheetLayout l = SheetLayout.plan(print, new Medium.Roll(915, Medium.Axis.VERTICAL), 15, 5);
        assertEquals(6, l.count());
        assertEquals(print.heightMm(), l.print().heightMm(), 1e-9, "nothing added along the roll");
        final double[] round = paperRound(print, l);
        assertEquals(5, round[0], 1e-6, "the first strip starts the edge before the print");
        assertEquals(5, round[2], 1e-6, "and the last one ends the edge past it");
        assertEquals(0, round[1], 1e-6);
        assertEquals(0, round[3], 1e-6);
    }

    @Test
    @DisplayName("a print sized as n sheets comes back as n sheets, for every preset, overlap and edge")
    void nSheetsAcrossIsN() {
        // What the studio asks for when the size is a count of sheets: the length n sheets cover,
        // less the overlaps and the edge (JobBuilder.millimetersOf). In inches the subtraction
        // comes out a hair over, and without a tolerance the count is n + 1.
        final double[] overlaps = {0, 5, 10, 15, 20, 6.35, 12.7, 25.4};
        final double[] edges = {0, 3, 5, 3.175, 6.35};
        final List<Medium> media = new ArrayList<>();
        for (final Medium.Paper p : Medium.PAPER_SIZES) {
            media.add(p.sheet(Medium.Orientation.PORTRAIT));
            media.add(p.sheet(Medium.Orientation.LANDSCAPE));
        }
        for (final double w : Medium.ROLL_WIDTHS) {
            media.add(new Medium.Roll(w, Medium.Axis.VERTICAL));
            media.add(new Medium.Roll(w, Medium.Axis.HORIZONTAL));
        }
        final LonLatBounds bohemia = new LonLatBounds(12.09, 49.44, 15.98, 50.73);
        for (final Medium m : media) {
            for (final boolean across : new boolean[]{true, false}) {
                final double sheet = across ? m.acrossMm() : m.downMm();
                if (sheet <= 0) {
                    continue;
                }
                for (final double o : overlaps) {
                    for (final double e : edges) {
                        for (int n = 2; n <= 12; n++) {
                            final double mm = n * sheet - (n - 1) * o - 2 * e;
                            final PrintRect print = (across ? PrintSize.width(mm) : PrintSize.height(mm))
                                    .apply(bohemia);
                            final SheetLayout l = SheetLayout.plan(print, m, o, e);
                            assertEquals(n, across ? l.columns() : l.rows(), () -> m.describe()
                                    + (across ? " across" : " down") + ", overlap " + o + ", edge " + e);
                        }
                    }
                }
            }
        }
    }
}
