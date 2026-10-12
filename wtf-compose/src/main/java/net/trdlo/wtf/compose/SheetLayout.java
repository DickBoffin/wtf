package net.trdlo.wtf.compose;

import org.jetbrains.annotations.NotNull;
import net.trdlo.wtf.tiles.Refusal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How a print is cut into sheets, and where each one sits.
 *
 * @param print     the rectangle the sheets are cut from: the print, with the printer's white edge
 *                  round it, grown by the spare paper whole sheets leave
 * @param sheets    in output order, which is their numbering: row by row (D36)
 * @param columns   how many across
 * @param rows      how many down
 * @param overlapMm the band two neighbors share, across and down alike
 */
public record SheetLayout(@NotNull PrintRect print, @NotNull List<Sheet> sheets,
                          int columns, int rows, double overlapMm) {

    public SheetLayout {
        sheets = List.copyOf(sheets);
    }

    /**
     * One sheet.
     *
     * @param index  one-based, and what appears in the file name
     * @param xMm    from the left edge of the print
     * @param slice  the part of the print this sheet shows
     */
    public record Sheet(int index, int column, int row,
                        double xMm, double yMm, double widthMm, double heightMm,
                        @NotNull PrintRect slice) {

        public @NotNull String fileSuffix() {
            return String.format(Locale.ROOT, "s%02d", index);
        }

        @Override
        public @NotNull String toString() {
            return String.format(Locale.ROOT, "Sheet %d (col %d, row %d) at %.0f,%.0f mm, %.0f x %.0f mm",
                    index, column + 1, row + 1, xMm, yMm, widthMm, heightMm);
        }
    }

    public int count() {
        return sheets.size();
    }

    /**
     * How much roll or paper the job consumes, in millimeters along the feed.
     * <p>
     * The question anybody with a roll asks and nobody could answer here: set the print five
     * meters across and the length that comes off the roll is the thing you have to have. It is
     * the sheets laid end to end – every one of them, including the overlap, because the overlap
     * is printed twice and paid for twice.
     */
    public double mediaLengthMm() {
        double total = 0;
        for (final Sheet sheet : sheets) {
            total += sheet.heightMm();
        }
        return total;
    }

    /**
     * A diagram for the file that goes with the export, in English, as the command line prints it.
     * Six unlabeled strips on a floor are six identical strips on a floor.
     */
    public @NotNull String describe() {
        return describe(FileWords.ENGLISH);
    }

    /**
     * The diagram in the words given: the print and how it is cut, then its sheets drawn row by
     * row as their numbers, which are in no language, then each sheet on a line of its own.
     */
    @NotNull String describe(final @NotNull FileWords words) {
        final StringBuilder sb = new StringBuilder(words.print(this));
        sb.append(System.lineSeparator()).append(System.lineSeparator());
        for (int r = 0; r < rows; r++) {
            final StringBuilder line = new StringBuilder("  ");
            for (int c = 0; c < columns; c++) {
                final int finalR = r, finalC = c;
                sheets.stream()
                        .filter(s -> s.row() == finalR && s.column() == finalC)
                        .findFirst()
                        .ifPresent(s -> line.append(String.format(Locale.ROOT, "[%2d]", s.index())));
            }
            sb.append(line).append(System.lineSeparator());
        }
        sb.append(System.lineSeparator());
        for (final Sheet s : sheets) {
            sb.append("  ").append(words.sheet(s)).append(System.lineSeparator());
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------------------------

    /**
     * Plans the cut. All the arithmetic of docs/design/compose-module.md §4 lives here.
     * <p>
     * <b>The printer's white edge is kept free round the print</b>, once on each outer side and
     * only along the axes the paper is cut in: the sheets cover the print grown by {@code 2 x edge}
     * that way, so the outermost map is never where the printer cannot put ink. Inside the print
     * the overlap deals with the edge, as it always has (D7); a roll's own length has no edge to
     * keep, since the strip is as long as the print. The rectangle the sheets are cut from is that
     * grown one, so the sheets start {@code edge} before the print asked for.
     * <p>
     * <b>Whole sheets rarely add up to the size asked for</b>, and the spare paper is spent on more
     * map: the rectangle grows by it, half at each end, and is trimmed against the wall. For 5000 mm
     * on 915 mm strips with a 15 mm overlap, six strips cover 5415 mm, and the print shows 207.5 mm
     * more at each end. Where the map stops at the area's edge – an outline, a hull, a rectangle
     * printed exactly – the spare is paper instead (D34), which the tile plan and the exporters
     * decide from {@link ComposeJob#clipsToArea}.
     *
     * @param print  the print the size step asked for, without the edge or the spare paper
     * @param edgeMm how much the printer leaves white at the edge of the paper
     */
    public static @NotNull SheetLayout plan(final @NotNull PrintRect print, final @NotNull Medium medium,
                                            final double overlapMm, final double edgeMm) {
        final PrintRect paper = print.grownByMm(medium.acrossMm() > 0 ? 2 * edgeMm : 0,
                medium.downMm() > 0 ? 2 * edgeMm : 0);
        final double acrossSheet = medium.acrossMm() > 0 ? medium.acrossMm() : paper.widthMm();
        final double downSheet = medium.downMm() > 0 ? medium.downMm() : paper.heightMm();

        final Cut across = cut(paper.widthMm(), acrossSheet, overlapMm);
        final Cut down = cut(paper.heightMm(), downSheet, overlapMm);

        // The spare paper is spent on more map, so the print rectangle itself grows
        final PrintRect finalPrint = paper.grownByMm(across.slackMm(), down.slackMm());

        // Row by row, the order sheets are laid out on a floor: three across and two down read
        // 1, 2, 3 along the top (D36). A strip layout is one row or one column, so a roll is
        // numbered along its strips either way.
        final List<Sheet> sheets = new ArrayList<>(across.n() * down.n());
        int index = 1;
        for (int r = 0; r < down.n(); r++) {
            for (int c = 0; c < across.n(); c++) {
                final double x = c * (across.sheetMm() - overlapMm);
                final double y = r * (down.sheetMm() - overlapMm);
                sheets.add(new Sheet(index++, c, r, x, y, across.sheetMm(), down.sheetMm(),
                        finalPrint.slice(x, y, across.sheetMm(), down.sheetMm())));
            }
        }
        return new SheetLayout(finalPrint, sheets, across.n(), down.n(), overlapMm);
    }

    /** Package-private so the arithmetic can be tested directly; it is the part worth testing. */
    record Cut(int n, double sheetMm, double slackMm) { }

    /**
     * How far a length may run over what whole sheets cover and still be covered by them: a
     * nanometer. A print sized as {@code n} sheets is {@code n x w - (n - 1) x o - 2 x edge}, which
     * in inches comes out a hair over what {@code n} sheets cover, and would be {@code n + 1}.
     */
    private static final double TOLERANCE_MM = 1e-6;

    /**
     * {@code n = ceil((L - o) / (w - o))}, and the spare paper that leaves.
     * <p>
     * A negative overlap is legal and means a deliberate gap; the formula handles it without a
     * special case. Only {@code o >= w} has no solution. A length over by no more than
     * {@link #TOLERANCE_MM} counts as covered, and leaves no spare.
     */
    static @NotNull Cut cut(final double lengthMm, final double sheetMm, final double overlapMm) {
        if (overlapMm >= sheetMm) {
            throw new Refusal(ComposeReason.OVERLAP_TOO_WIDE, overlapMm, sheetMm);
        }
        if (sheetMm + TOLERANCE_MM >= lengthMm) {
            return new Cut(1, sheetMm, Math.max(0, sheetMm - lengthMm));
        }
        final int n = (int) Math.ceil((lengthMm - TOLERANCE_MM - overlapMm) / (sheetMm - overlapMm));
        return new Cut(n, sheetMm, Math.max(0, n * sheetMm - (n - 1) * overlapMm - lengthMm));
    }
}
