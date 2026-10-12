package net.trdlo.wtf.compose;

import org.jetbrains.annotations.NotNull;
import net.trdlo.wtf.tiles.Refusal;

import java.util.List;
import java.util.Locale;

/** What the print is put on: a roll cut into strips, or sheets tiled both ways. */
public sealed interface Medium {

    enum Axis {
        /** Strips run top to bottom; each is the full print height and the roll's width across. */
        VERTICAL,
        /** Strips run left to right. */
        HORIZONTAL
    }

    enum Orientation { PORTRAIT, LANDSCAPE }

    /** The width of one sheet across the print, in millimeters. */
    double acrossMm();

    /** The length of one sheet down the print, or a negative number when it is the whole print. */
    double downMm();

    @NotNull String describe();

    /**
     * A roll, cut in one direction only.
     *
     * @param widthMm the roll's full width; the printer's unprintable edge is <b>not</b> modeled –
     *                it is trimmed off anyway, and the user compensates in the overlap. See
     *                docs/design/compose-module.md §4.
     */
    record Roll(double widthMm, @NotNull Axis stripAxis) implements Medium {
        public Roll {
            if (widthMm <= 0) {
                throw new Refusal(ComposeReason.ROLL_WIDTH_NOT_POSITIVE, widthMm);
            }
        }

        @Override
        public double acrossMm() {
            return stripAxis == Axis.VERTICAL ? widthMm : -1;
        }

        @Override
        public double downMm() {
            return stripAxis == Axis.VERTICAL ? -1 : widthMm;
        }

        @Override
        public @NotNull String describe() {
            return String.format(Locale.ROOT, "%.0f mm roll, %s strips",
                    widthMm, stripAxis.name().toLowerCase(Locale.ROOT));
        }
    }

    /** A cut sheet, tiled in both directions. */
    record CutSheet(double widthMm, double heightMm, @NotNull Orientation orientation) implements Medium {
        public CutSheet {
            if (widthMm <= 0 || heightMm <= 0) {
                throw new Refusal(ComposeReason.SHEET_SIZE_NOT_POSITIVE);
            }
        }

        private double effectiveWidth() {
            return orientation == Orientation.PORTRAIT ? Math.min(widthMm, heightMm) : Math.max(widthMm, heightMm);
        }

        private double effectiveHeight() {
            return orientation == Orientation.PORTRAIT ? Math.max(widthMm, heightMm) : Math.min(widthMm, heightMm);
        }

        @Override
        public double acrossMm() {
            return effectiveWidth();
        }

        @Override
        public double downMm() {
            return effectiveHeight();
        }

        @Override
        public @NotNull String describe() {
            return String.format(Locale.ROOT, "%.0f x %.0f mm sheets, %s",
                    effectiveWidth(), effectiveHeight(), orientation.name().toLowerCase(Locale.ROOT));
        }
    }

    /** Standard paper, portrait millimeters. The A series is defined by area: A0 is one square meter. */
    record Paper(@NotNull String name, double widthMm, double heightMm) {
        public @NotNull CutSheet sheet(final @NotNull Orientation o) {
            return new CutSheet(widthMm, heightMm, o);
        }
    }

    List<Paper> PAPER_SIZES = List.of(
            new Paper("4A0", 1682, 2378),   // four square meters
            new Paper("2A0", 1189, 1682),   // two – the brief's "A00"
            new Paper("A0", 841, 1189),     // one
            new Paper("A1", 594, 841),
            new Paper("A2", 420, 594),
            new Paper("A3", 297, 420),
            new Paper("A4", 210, 297),
            new Paper("A5", 148, 210),
            new Paper("A6", 105, 148),
            // The North American series, because an application that only offers ISO paper is
            // useless to most of one continent. Names as a print shop there says them.
            //
            // In inches, converted exactly, and not rounded to whole millimeters the way ISO
            // rounds its own: ISO defines its sizes in millimeters and these are defined in
            // inches, and the rounding would make Letter 612.28 points wide instead of 612. A
            // printer driver matches a page to a tray by its size, and a page that is not quite
            // Letter is a custom size it asks about.
            inches("Letter", 8.5, 11),
            inches("Legal", 8.5, 14),
            inches("Tabloid", 11, 17),               // ANSI B
            inches("ANSI C", 17, 22),
            inches("ANSI D", 22, 34),
            inches("ANSI E", 34, 44),
            inches("Arch D", 24, 36),                // the architect's sheet
            inches("Arch E", 36, 48));

    /** A paper defined in inches, in millimeters exactly: an inch is 25.4 mm by definition. */
    private static @NotNull Paper inches(final @NotNull String name, final double w, final double h) {
        return new Paper(name, w * 25.4, h * 25.4);
    }

    /**
     * Common roll widths. 914 mm is 36 inches and 915 mm is the metric roll; both exist, and
     * getting it wrong by a millimeter over six strips is 6 mm of wall.
     */
    List<Double> ROLL_WIDTHS = List.of(610.0, 762.0, 914.0, 915.0, 1067.0, 1118.0, 1270.0, 1524.0);

    static @NotNull Paper paper(final @NotNull String name) {
        return PAPER_SIZES.stream()
                .filter(p -> p.name().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown paper size '" + name + "'. Known: "
                        + PAPER_SIZES.stream().map(Paper::name).toList()));
    }
}
