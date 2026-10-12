package net.trdlo.wtf.compose;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Where the marks go on one sheet: pure geometry, drawn by both exporters the same way.
 * <p>
 * <b>The seam rule.</b> At every seam the sheet to the right, and the sheet below, lies on top. So a
 * sheet's left and top white edge is cut off along the ink boundary – the printer's own edge is the
 * cut guide – and its right and bottom white edge is left on, because the neighbor covers it.
 * <p>
 * <b>The hidden strip.</b> Along a right seam the covering sheet's paper edge lies at the neighbor's
 * {@code x}, and once its white edge {@code e} is trimmed, its map starts {@code e} further in. From
 * there to this sheet's own white edge is covered on the wall and printable on the paper:
 * <pre>
 *   neighbor.x + e + gap  ...  own edge - e - gap        (and the same down a bottom seam)
 * </pre>
 * which is {@code overlap - 2e - 2gap} wide. The marks go there and nowhere else. The gap is a
 * millimeter each side, because neither a printer's margin nor a cut is exact. Six millimeter edges
 * and a fifteen millimeter overlap leave a millimeter – room for a hairline, not for text.
 * <p>
 * Positions are millimeters on the sheet's page, from its top-left corner, y down. The seam is
 * measured from where the neighbor actually is, in the layout, rather than worked out again from
 * the overlap.
 */
public final class SeamMarks {

    /** Neither a printer's margin nor a knife is exact to better than this. */
    public static final double GAP_MM = 1;

    /** The narrowest strip text is put in: six-point capitals are two millimeters tall. */
    public static final double TEXT_STRIP_MM = 3;

    /** How far in from the outer edge the visible credit sits, past the white edge. */
    public static final double CREDIT_INSET_MM = 3;

    static final double MARK_PT = 6;
    static final double CREDIT_PT = 7;

    /** The longest a scale bar is drawn: it is the round distance that fits. */
    static final double BAR_MAX_MM = 40;

    /** How long the bar's end ticks are, above it and below; the middle ones are two thirds of it. */
    static final double BAR_TICK_MM = 1.5;

    /** Between a tick's end and its label. */
    static final double BAR_LABEL_GAP_MM = 0.8;

    /** From the bar's line down to the baseline of the labels under it, which rise a capital's height. */
    static final double BAR_BELOW_MM = BAR_TICK_MM + BAR_LABEL_GAP_MM + CREDIT_PT * 25.4 / 72 * 0.72;

    /** A sheet narrower than this has no room for the scale and the credit side by side. */
    static final double SHARED_CORNER_MM = 120;

    /** How far above the credit the scale goes, where the two share a narrow sheet. */
    static final double CREDIT_LINE_MM = 4;

    /** From one percent apart, the ratio says the latitude it is true at. */
    static final double SCALE_VARIATION_NOTED = 0.01;

    /** Groups the thousands of a number on the paper, in no language: "1:50 000". */
    private static final char THOUSANDS = '\u202F';

    private static final double PT_MM = 25.4 / 72;

    private SeamMarks() {
    }

    /**
     * How a mark is inked: faint, to be hidden anyway; or as the credit and the scale, which are
     * meant to be read, dark over a white halo.
     */
    public enum Style { FAINT, CREDIT }

    /** Which point of a text its position names. */
    public enum Anchor { START, MIDDLE, END }

    public sealed interface Mark permits Line, Text { }

    /**
     * @param registration true for the registration hairline, false for an arrow's strokes or the
     *                     scale bar
     * @param style        faint for a seam's lines, the credit's for the scale bar
     */
    public record Line(double x1, double y1, double x2, double y2, boolean registration,
                       @NotNull Style style) implements Mark {
        Line(final double x1, final double y1, final double x2, final double y2, final boolean registration) {
            this(x1, y1, x2, y2, registration, Style.FAINT);
        }

        Line(final double x1, final double y1, final double x2, final double y2) {
            this(x1, y1, x2, y2, false, Style.FAINT);
        }
    }

    /**
     * @param angle degrees counter-clockwise as seen on the paper: 0 reads left to right, 90 reads
     *              upwards. The position is on the baseline.
     */
    public record Text(double x, double y, double angle, double sizePt, @NotNull String text,
                       @NotNull Style style, @NotNull Anchor anchor) implements Mark { }

    /** A hidden strip: a rectangle on the page, and which way it runs. */
    public record Strip(double x0, double y0, double x1, double y1, boolean vertical) {
        public double width() {
            return vertical ? x1 - x0 : y1 - y0;
        }
    }

    // -------------------------------------------------------------------------------------------

    /** The strip along the sheet's right seam, if it has a neighbor there and any strip is left. */
    public static @NotNull Optional<Strip> rightStrip(final @NotNull SheetLayout layout,
                                                      final SheetLayout.@NotNull Sheet sheet, final double edgeMm) {
        final SheetLayout.Sheet next = at(layout, sheet.column() + 1, sheet.row());
        if (next == null) {
            return Optional.empty();
        }
        final double x0 = next.xMm() - sheet.xMm() + edgeMm + GAP_MM;
        final double x1 = sheet.widthMm() - edgeMm - GAP_MM;
        final double y0 = edgeMm + GAP_MM;
        final double y1 = sheet.heightMm() - edgeMm - GAP_MM;
        return x1 > x0 && y1 > y0 ? Optional.of(new Strip(x0, y0, x1, y1, true)) : Optional.empty();
    }

    /** The strip along the sheet's bottom seam, if it has a neighbor there and any strip is left. */
    public static @NotNull Optional<Strip> bottomStrip(final @NotNull SheetLayout layout,
                                                       final SheetLayout.@NotNull Sheet sheet, final double edgeMm) {
        final SheetLayout.Sheet next = at(layout, sheet.column(), sheet.row() + 1);
        if (next == null) {
            return Optional.empty();
        }
        final double y0 = next.yMm() - sheet.yMm() + edgeMm + GAP_MM;
        final double y1 = sheet.heightMm() - edgeMm - GAP_MM;
        final double x0 = edgeMm + GAP_MM;
        final double x1 = sheet.widthMm() - edgeMm - GAP_MM;
        return y1 > y0 && x1 > x0 ? Optional.of(new Strip(x0, y0, x1, y1, false)) : Optional.empty();
    }

    /** The narrowest overlap that leaves room for text, for a printer that leaves {@code edgeMm}. */
    public static double overlapForText(final double edgeMm) {
        return 2 * edgeMm + 2 * GAP_MM + TEXT_STRIP_MM;
    }

    /** The narrowest overlap that covers both white edges at all. */
    public static double overlapToCover(final double edgeMm) {
        return 2 * edgeMm + 2 * GAP_MM;
    }

    // -------------------------------------------------------------------------------------------

    /** Every mark on this sheet. Empty when nothing is switched on. */
    public static @NotNull List<Mark> forSheet(final @NotNull ComposeJob job, final @NotNull SheetLayout layout,
                                               final SheetLayout.@NotNull Sheet sheet) {
        final Marks m = job.marks();
        final List<Mark> out = new ArrayList<>();
        if (!m.any()) {
            return out;
        }
        final double e = m.edgeMm();
        final Optional<Strip> right = rightStrip(layout, sheet, e);
        final Optional<Strip> bottom = bottomStrip(layout, sheet, e);

        // The label goes once per sheet, in the first strip that can take text
        final Strip forText = right.filter(s -> s.width() >= TEXT_STRIP_MM)
                .or(() -> bottom.filter(s -> s.width() >= TEXT_STRIP_MM)).orElse(null);
        if (forText != null) {
            // The other seam crosses this strip at the corner; text keeps clear of where it runs
            final Strip crossing = forText.vertical() ? bottom.orElse(null) : right.orElse(null);
            if (m.label()) {
                out.add(along(forText, crossing, label(layout, sheet, m), true));
            }
        }
        right.ifPresent(s -> seam(out, m, s, at(layout, sheet.column() + 1, sheet.row()), sheet, e));
        bottom.ifPresent(s -> seam(out, m, s, at(layout, sheet.column(), sheet.row() + 1), sheet, e));

        if (m.credit() && sheet.column() == layout.columns() - 1 && sheet.row() == layout.rows() - 1
                && !job.provider().attribution().isBlank()) {
            final double inset = e + CREDIT_INSET_MM;
            out.add(new Text(sheet.widthMm() - inset, sheet.heightMm() - inset, 0, CREDIT_PT,
                    job.provider().attribution(), Style.CREDIT, Anchor.END));
        }
        final ScaleMark scale = m.scale();
        if (scale != null && sheet.column() == 0 && sheet.row() == layout.rows() - 1) {
            // The credit's corner mirrored, and a line above the credit where one narrow sheet
            // holds both corners
            final boolean shared = m.credit() && sheet.column() == layout.columns() - 1
                    && !job.provider().attribution().isBlank() && sheet.widthMm() < SHARED_CORNER_MM;
            final double inset = e + CREDIT_INSET_MM;
            scale(out, job.print(), scale, inset, sheet.heightMm() - inset - (shared ? CREDIT_LINE_MM : 0));
        }
        return out;
    }

    /**
     * The scale with its lowest baseline at {@code y} and its start at {@code x}: the bar, ticked in
     * meters above and in miles or feet below, and the ratio after it on the bar's line.
     * <p>
     * The scale is the job's print's, the one the size was asked at.
     */
    private static void scale(final @NotNull List<Mark> out, final @NotNull PrintRect print,
                              final @NotNull ScaleMark how, final double x, final double y) {
        final double n = print.mapScale();
        // The bar's line, with the imperial labels under it down to the lowest baseline
        final double line = how.bar() ? y - BAR_BELOW_MM : y;
        double at = x;
        if (how.bar()) {
            final Round metric = metric(n);
            final Round imperial = imperial(metric.meters());
            final double metricMm = metric.meters() / n * 1000;
            final double imperialMm = imperial.meters() / n * 1000;
            // As long as the longer of the two: the round distance in miles nearest the one in
            // kilometers may be either side of it
            final double length = Math.max(metricMm, imperialMm);
            out.add(new Line(x, line, x + length, line, false, Style.CREDIT));
            ticks(out, x, line, metricMm, -1);
            ticks(out, x, line, imperialMm, 1);
            final double above = line - BAR_TICK_MM - BAR_LABEL_GAP_MM;
            out.add(new Text(x, above, 0, CREDIT_PT, "0", Style.CREDIT, Anchor.MIDDLE));
            out.add(new Text(x + metricMm, above, 0, CREDIT_PT, metric.label(), Style.CREDIT, Anchor.MIDDLE));
            out.add(new Text(x + imperialMm, y, 0, CREDIT_PT, imperial.label(), Style.CREDIT, Anchor.MIDDLE));
            // Past the end labels' overhang, which is above and below the ratio's line but would
            // read as one with it
            at = x + length + 5;
        }
        if (how.ratio()) {
            out.add(new Text(at, line, 0, CREDIT_PT, ratio(print), Style.CREDIT, Anchor.START));
        }
    }

    /** Ticks at the start, the middle and the end of {@code lengthMm}, up ({@code -1}) or down ({@code 1}). */
    private static void ticks(final @NotNull List<Mark> out, final double x, final double line,
                              final double lengthMm, final int way) {
        out.add(new Line(x, line, x, line + way * BAR_TICK_MM, false, Style.CREDIT));
        out.add(new Line(x + lengthMm / 2, line, x + lengthMm / 2, line + way * BAR_TICK_MM * 2 / 3,
                false, Style.CREDIT));
        out.add(new Line(x + lengthMm, line, x + lengthMm, line + way * BAR_TICK_MM, false, Style.CREDIT));
    }

    /** A round distance on the ground, and what the bar calls it. */
    record Round(double meters, @NotNull String label) { }

    static final double INCH_M = 0.0254;
    static final double FOOT_M = 0.3048;
    static final double MILE_M = 1609.344;

    /** The longest round metric distance that is at most {@link #BAR_MAX_MM} at 1:n. */
    static @NotNull Round metric(final double n) {
        final double meters = barMeters(n);
        return new Round(meters, distance(meters));
    }

    /**
     * The 1, 2 or 5 times a power of ten inches, feet or miles nearest {@code meters}, by ratio: a
     * mile beside two kilometers, 500 ft beside 200 m. Feet go up to 2 000, where a mile is nearer
     * than 5 000 ft would be to anybody who reads them.
     */
    static @NotNull Round imperial(final double meters) {
        Round best = null;
        double off = Double.MAX_VALUE;
        for (final Round r : imperialRounds()) {
            final double d = Math.abs(Math.log(r.meters() / meters));
            if (d < off) {
                off = d;
                best = r;
            }
        }
        return java.util.Objects.requireNonNull(best);
    }

    private static @NotNull List<Round> imperialRounds() {
        final List<Round> all = new ArrayList<>();
        for (final long in : new long[] {1, 2, 5}) {
            all.add(new Round(in * INCH_M, in + "\u00A0in"));
        }
        for (final long ft : new long[] {1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000}) {
            all.add(new Round(ft * FOOT_M, grouped(ft) + "\u00A0ft"));
        }
        for (long power = 1; power <= 1_000_000_000L; power *= 10) {
            for (final long f : new long[] {1, 2, 5}) {
                all.add(new Round(f * power * MILE_M, grouped(f * power) + "\u00A0mi"));
            }
        }
        return all;
    }

    /** The longest of 1, 2 and 5 times a power of ten meters that is at most {@link #BAR_MAX_MM} at 1:n. */
    static double barMeters(final double n) {
        final double most = BAR_MAX_MM / 1000 * n;
        final double power = Math.pow(10, Math.floor(Math.log10(most)));
        for (final double f : new double[] {5, 2, 1}) {
            if (f * power <= most * (1 + 1e-9)) {
                return f * power;
            }
        }
        return power;
    }

    /** A bar's length on the ground, in whole centimeters, meters or kilometers. */
    static @NotNull String distance(final double meters) {
        if (meters < 1) {
            return Math.round(meters * 100) + "\u00A0cm";
        }
        if (meters < 1000) {
            return Math.round(meters) + "\u00A0m";
        }
        return grouped(Math.round(meters / 1000)) + "\u00A0km";
    }

    /**
     * "1:50 000", and where Mercator's scale differs by more than a percent between the print's top
     * and bottom, the latitude it is true at: "1:2 500 000 (49°48′ N)".
     */
    static @NotNull String ratio(final @NotNull PrintRect print) {
        final String ratio = "1:" + grouped(Math.round(print.mapScale()));
        return print.scaleVariation() > SCALE_VARIATION_NOTED
                ? ratio + " (" + latitude(print.centerLat()) + ")" : ratio;
    }

    /** Degrees and minutes, which need no decimal separator and so no language. */
    static @NotNull String latitude(final double lat) {
        final long minutes = Math.round(Math.abs(lat) * 60);
        final String hemisphere = minutes == 0 ? "" : lat > 0 ? " N" : " S";
        return String.format(Locale.ROOT, "%d°%02d′%s", minutes / 60, minutes % 60, hemisphere);
    }

    /** The digits in threes, apart by a narrow no-break space. */
    static @NotNull String grouped(final long n) {
        final String digits = Long.toString(n);
        final StringBuilder sb = new StringBuilder(digits.length() + digits.length() / 3);
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                sb.append(THOUSANDS);
            }
            sb.append(digits.charAt(i));
        }
        return sb.toString();
    }

    /** The hairline and the arrow for one seam. */
    private static void seam(final @NotNull List<Mark> out, final @NotNull Marks m, final @NotNull Strip s,
                             final SheetLayout.@Nullable Sheet next, final SheetLayout.@NotNull Sheet sheet,
                             final double e) {
        if (next == null) {
            return;
        }
        if (m.registration()) {
            // Half a gap inside the covered side of where the trimmed edge lands: hidden, and a
            // line to lay that edge against
            if (s.vertical()) {
                final double x = s.x0() - GAP_MM / 2;
                out.add(new Line(x, s.y0(), x, s.y1(), true));
            } else {
                final double y = s.y0() - GAP_MM / 2;
                out.add(new Line(s.x0(), y, s.x1(), y, true));
            }
        }
        if (m.arrows() && s.width() >= TEXT_STRIP_MM) {
            // An arrow pointing across the seam, towards the sheet that goes over it, and its number
            final double c = s.vertical() ? (s.y0() + s.y1()) / 2 : (s.x0() + s.x1()) / 2;
            final double head = Math.min(1.2, s.width() / 3);
            final String number = Integer.toString(next.index());
            if (s.vertical()) {
                final double x0 = s.x0() + 0.4, x1 = s.x1() - 0.4;
                out.add(new Line(x0, c, x1, c));
                out.add(new Line(x1, c, x1 - head, c - head));
                out.add(new Line(x1, c, x1 - head, c + head));
                out.add(new Text(baseline(s), c - 1.5, 90, MARK_PT, number, Style.FAINT, Anchor.START));
            } else {
                final double y0 = s.y0() + 0.4, y1 = s.y1() - 0.4;
                out.add(new Line(c, y0, c, y1));
                out.add(new Line(c, y1, c - head, y1 - head));
                out.add(new Line(c, y1, c + head, y1 - head));
                out.add(new Text(c + 1.5, baseline(s), 0, MARK_PT, number, Style.FAINT, Anchor.START));
            }
        }
    }

    /**
     * A line of text along a strip, near its start or its end.
     * <p>
     * A vertical strip is read upwards, as a book's spine is, so its start is the bottom.
     */
    private static @NotNull Text along(final @NotNull Strip s, final @Nullable Strip crossing,
                                       final @NotNull String text, final boolean atStart) {
        final double margin = 5;
        if (s.vertical()) {
            final double end = clearOf(s.y1(), crossing, true);
            return atStart
                    ? new Text(baseline(s), end - margin, 90, MARK_PT, text, Style.FAINT, Anchor.START)
                    : new Text(baseline(s), s.y0() + margin, 90, MARK_PT, text, Style.FAINT, Anchor.END);
        }
        final double end = clearOf(s.x1(), crossing, false);
        return atStart
                ? new Text(s.x0() + margin, baseline(s), 0, MARK_PT, text, Style.FAINT, Anchor.START)
                : new Text(end - margin, baseline(s), 0, MARK_PT, text, Style.FAINT, Anchor.END);
    }

    /** How far text keeps from the hairline of the seam that crosses its strip. */
    static final double CROSSING_CLEARANCE_MM = 3;

    /**
     * Where a strip's text has to stop, short of the seam that crosses it at the far corner.
     * <p>
     * A right strip runs the full height of the sheet, and at the bottom it passes through the
     * bottom seam – which is where that seam's hairline runs, and a label starting there would
     * have the line straight through its first letter. So text stops a clearance short of where
     * the crossing seam's hairline is, whether or not the hairline is switched on: the corner is
     * the other seam's either way. The near ends need nothing, because this sheet lies on top of
     * the sheets to its left and above, and no seam of its own runs there.
     */
    private static double clearOf(final double end, final @Nullable Strip crossing, final boolean vertical) {
        if (crossing == null) {
            return end;
        }
        final double hairline = (vertical ? crossing.y0() : crossing.x0()) - GAP_MM / 2;
        return Math.min(end, hairline - CROSSING_CLEARANCE_MM);
    }

    /**
     * The baseline that centers capitals in the strip. Upright text rises from its baseline, so the
     * baseline sits half a capital below the middle; text read upwards rises to the left, so there
     * the baseline sits half a capital to the right.
     */
    private static double baseline(final @NotNull Strip s) {
        final double cap = MARK_PT * PT_MM * 0.72;
        return s.vertical() ? (s.x0() + s.x1()) / 2 + cap / 2 : (s.y0() + s.y1()) / 2 + cap / 2;
    }

    /**
     * Which sheet this is and where it goes, in the words the job was given, then the print's name
     * and the day, each where the job has one. The numbers are plain integers: the engine has no
     * language to group digits in, and a count of sheets needs none.
     */
    private static @NotNull String label(final @NotNull SheetLayout layout, final SheetLayout.@NotNull Sheet sheet,
                                         final @NotNull Marks m) {
        final StringBuilder sb = new StringBuilder(m.sheetLine()
                .replace("{0}", Integer.toString(sheet.index()))
                .replace("{1}", Integer.toString(layout.count()))
                .replace("{2}", Integer.toString(sheet.column() + 1))
                .replace("{3}", Integer.toString(sheet.row() + 1)));
        if (!m.name().isBlank()) {
            sb.append(" · ").append(m.name());
        }
        if (!m.date().isBlank()) {
            sb.append(" · ").append(m.date());
        }
        return sb.toString();
    }

    private static SheetLayout.@Nullable Sheet at(final @NotNull SheetLayout layout, final int column, final int row) {
        for (final SheetLayout.Sheet s : layout.sheets()) {
            if (s.column() == column && s.row() == row) {
                return s;
            }
        }
        return null;
    }
}
