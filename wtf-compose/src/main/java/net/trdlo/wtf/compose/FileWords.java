package net.trdlo.wtf.compose;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The words of the two files an export writes beside its print: the layout, which says what was
 * printed and how the sheets go together, and the attribution, which credits the map.
 * <p>
 * The engine has no language, so it is given them in the job, as it is given a sheet label's
 * ({@link Marks#sheetLine}): the studio says them in the interface language, and a job that is not
 * given any – the command line's – has {@link #ENGLISH}. What goes into the files, in what order,
 * and how it is laid out is the engine's: the lines and the blank lines between them, the
 * {@code !!} before a warning, the sheets drawn row by row as their numbers, the hanging
 * instructions wrapped. Each method here says one line of it, its numbers and lengths written as
 * the words see fit.
 */
public interface FileWords {

    /** The engine's own, which the command line writes: the files' English, numbers in {@code Locale.ROOT}. */
    FileWords ENGLISH = new EnglishFileWords();

    /** The layout's first line, which names the print: {@code WTF layout for wall}. */
    @NotNull String title(@NotNull String name);

    /** What the print is put on: {@code 914 mm roll, vertical strips}. */
    @NotNull String medium(@NotNull Medium medium);

    /** The zoom, and how each side of the subject is drawn: {@code zoom 15, one region}. */
    @NotNull String regions(@NotNull Regions regions);

    /** The print's map scale and its DPI: {@code Scale 1:25,000, 191 DPI}. */
    @NotNull String scale(double mapScale, double dpi);

    /**
     * How many tiles were missing and what went where they would have been. Asked only under a
     * policy that exports with holes, {@link MissingTilePolicy#TRANSPARENT} or
     * {@link MissingTilePolicy#PLACEHOLDER}.
     */
    @NotNull String missing(int tiles, @NotNull MissingTilePolicy policy);

    /** That the files are a proof and must not go to a printer, said after {@link #missing}. */
    @NotNull String proof();

    /** The print's size, and how it is cut into sheets. */
    @NotNull String print(@NotNull SheetLayout layout);

    /** One sheet: its number, its column and row, where it lies in the print and how big it is. */
    @NotNull String sheet(SheetLayout.@NotNull Sheet sheet);

    /**
     * How the sheets go up, on a print of more than one: which goes on top, then what to do with
     * their edges. Each a paragraph, which the engine wraps.
     */
    @NotNull List<String> hanging();

    /** The attribution's line naming the map, by its id: {@code Theme: outdoor}. */
    @NotNull String theme(@NotNull String themeId);

    /** The attribution's line with the provider's terms, for a provider that has them. */
    @NotNull String terms(@NotNull String url);

    /** The attribution's last line. */
    @NotNull String producedBy();
}
