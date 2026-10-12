package net.trdlo.wtf.compose;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * {@link FileWords#ENGLISH}: the layout and the attribution in the engine's English, which the
 * command line writes.
 * <p>
 * The medium, the regions, the policy and each sheet are said as the log and the command line say
 * them, through their own {@code describe} and {@code toString}, so that the file and the console
 * cannot drift apart. Every number is written in {@link Locale#ROOT}.
 */
final class EnglishFileWords implements FileWords {

    private static final List<String> HANGING = List.of(
            "Hanging: the sheet to the right and the sheet below go on top.",
            "Cut the white edge off the left and the top of each sheet, along the ink.",
            "Leave the right and the bottom as they are; the neighbor covers them, and any sheet numbers"
                    + " and arrows printed there with them.");

    @Override
    public @NotNull String title(final @NotNull String name) {
        return "WTF layout for " + name;
    }

    @Override
    public @NotNull String medium(final @NotNull Medium medium) {
        return medium.describe();
    }

    @Override
    public @NotNull String regions(final @NotNull Regions regions) {
        return regions.describe();
    }

    @Override
    public @NotNull String scale(final double mapScale, final double dpi) {
        return String.format(Locale.ROOT, "Scale 1:%,.0f, %.0f DPI", mapScale, dpi);
    }

    @Override
    public @NotNull String missing(final int tiles, final @NotNull MissingTilePolicy policy) {
        return tiles + " tile(s) were not cached; the policy was to " + policy.describe() + ".";
    }

    @Override
    public @NotNull String proof() {
        return "THIS IS A PROOF. Do not send it to a printer.";
    }

    @Override
    public @NotNull String print(final @NotNull SheetLayout layout) {
        return String.format(Locale.ROOT,
                "Print %.0f x %.0f mm, %d sheet(s) in %d column(s) x %d row(s), overlap %.1f mm",
                layout.print().widthMm(), layout.print().heightMm(), layout.count(), layout.columns(),
                layout.rows(), layout.overlapMm());
    }

    @Override
    public @NotNull String sheet(final SheetLayout.@NotNull Sheet sheet) {
        return sheet.toString();
    }

    @Override
    public @NotNull List<String> hanging() {
        return HANGING;
    }

    @Override
    public @NotNull String theme(final @NotNull String themeId) {
        return "Theme: " + themeId;
    }

    @Override
    public @NotNull String terms(final @NotNull String url) {
        return "Terms: " + url;
    }

    @Override
    public @NotNull String producedBy() {
        return "Produced by WTF.";
    }
}
