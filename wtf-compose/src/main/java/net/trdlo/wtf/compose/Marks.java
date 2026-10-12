package net.trdlo.wtf.compose;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import net.trdlo.wtf.tiles.Refusal;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * What is printed besides the map, and what the printer leaves unprinted.
 * <p>
 * The three seam marks go only into the strip that the neighboring sheet covers on the wall – see
 * {@link SeamMarks} – so they are never seen once the print is up. The credit and the scale are the
 * exceptions: they are meant to be read, and sit in the bottom corners of the finished print.
 * <p>
 * <b>The label</b> says which sheet it is and where it goes, then what the print is called and the
 * day: {@code Sheet 2 of 6 · column 2, row 1 · Čelákovicko · 2026-10-10}. The engine has no
 * language, so it is given the words as it is given the name and the day: the studio says them in
 * the interface language, and a job that is not given them – the command line's – says them in
 * English.
 *
 * @param edgeMm       how much the printer leaves white at every edge of the paper. The sheets keep
 *                     it free round the print ({@link SheetLayout#plan}), everything is placed
 *                     clear of it, and the hidden strip is worked out from it
 * @param label        which sheet this is and where it goes, in the hidden strip
 * @param arrows       which neighbor goes over each seam, in the hidden strip
 * @param registration a hairline where the covering sheet's trimmed edge lands
 * @param credit       the provider's credit on the finished print, bottom right, with its logo where
 *                     its definition names one
 * @param scale        the map scale on the finished print, bottom left; null for none
 * @param name         what the label calls the print; may be blank. The studio gives the name the
 *                     print's files are written under; a job made from a project file has the
 *                     file's title
 * @param date         when it was exported, for the label: {@link #today()}
 * @param sheetLine    the label's words for which sheet it is, with {@code {0}} where the sheet's
 *                     number goes, {@code {1}} the number of sheets, {@code {2}} its column and
 *                     {@code {3}} its row – each filled in as a plain integer, counted from one.
 *                     Printed as it stands otherwise: nothing in it is quoted. {@link #SHEET_LINE}
 *                     in English
 */
public record Marks(double edgeMm, boolean label, boolean arrows, boolean registration, boolean credit,
                    @Nullable ScaleMark scale, @NotNull String name, @NotNull String date,
                    @NotNull String sheetLine) {

    /** Which sheet a label says it is, in English: "Sheet 2 of 6 · column 2, row 1". */
    public static final String SHEET_LINE = "Sheet {0} of {1} · column {2}, row {3}";

    /** Nothing but map. */
    public static final Marks NONE = new Marks(0, false, false, false, false, null, "", "");

    /** What a printer leaves white when nobody has said: typical of an office inkjet. */
    public static final double DEFAULT_EDGE_MM = 5;

    /** The system property that names the day a label says, in place of today. */
    public static final String DATE_PROPERTY = "wtf.export.date";

    public Marks {
        if (edgeMm < 0) {
            throw new Refusal(ComposeReason.EDGE_NEGATIVE, edgeMm);
        }
    }

    /** With the label's words in English, as the command line prints them. */
    public Marks(final double edgeMm, final boolean label, final boolean arrows, final boolean registration,
                 final boolean credit, final @Nullable ScaleMark scale, final @NotNull String name,
                 final @NotNull String date) {
        this(edgeMm, label, arrows, registration, credit, scale, name, date, SHEET_LINE);
    }

    /**
     * The day a label says it was exported: today, where the machine is, or the day
     * {@code -Dwtf.export.date=2026-01-01} names.
     * <p>
     * <b>The one date an export prints</b>, and the one thing in it the project and the tiles do
     * not decide – so a labeled print made again is the same file only on the same day, or with
     * the day given. Read at every call, so a test can set it and clear it again.
     *
     * @throws IllegalArgumentException when the property is set to something that is not a day
     */
    public static @NotNull String today() {
        final String fixed = System.getProperty(DATE_PROPERTY);
        if (fixed == null || fixed.isBlank()) {
            return LocalDate.now().toString();
        }
        try {
            return LocalDate.parse(fixed.strip()).toString();
        } catch (final DateTimeParseException ex) {
            throw new IllegalArgumentException("-D" + DATE_PROPERTY + " wants a day such as 2026-01-01, not \""
                    + fixed + "\"", ex);
        }
    }

    public boolean any() {
        return label || arrows || registration || credit || scale != null;
    }
}
