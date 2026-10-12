package net.trdlo.wtf.studio;

import net.trdlo.wtf.compose.FileWords;
import net.trdlo.wtf.compose.Medium;
import net.trdlo.wtf.compose.MissingTilePolicy;
import net.trdlo.wtf.compose.Regions;
import net.trdlo.wtf.compose.SheetLayout;
import net.trdlo.wtf.compose.TileEffect;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The two files an export writes beside its print – the layout and the attribution – in the
 * interface language.
 * <p>
 * The engine has no language: given no words, it writes them in English, which is what the command
 * line gets ({@link FileWords#ENGLISH}). A job built in the window carries these instead, as it
 * carries the sheet label's words, and the engine lays the files out and asks them for each line.
 * Each is said from the bundle in the words the window uses for the same thing. A number is written
 * as the window writes it, a length in the window's unit: the files are read by a person and never
 * by a program, so nothing in them needs {@code Locale.ROOT}, and in Czech a comma is a decimal
 * point. The provider's own credit is the provider's and stays as it is given.
 * <p>
 * Said on the export's thread when the files are written. The language and the unit are read once
 * at startup and do not change while the window is open.
 */
public final class FileText implements FileWords {

    /** The words of every job the window builds. */
    public static final FileWords WORDS = new FileText();

    private FileText() {
    }

    @Override
    public @NotNull String title(final @NotNull String name) {
        return I18n.format("export.layout.title", name);
    }

    @Override
    public @NotNull String medium(final @NotNull Medium medium) {
        final Units u = Units.current();
        return switch (medium) {
            case Medium.Roll roll -> I18n.format(roll.stripAxis() == Medium.Axis.VERTICAL
                            ? "export.layout.roll.vertical" : "export.layout.roll.horizontal",
                    u.length(roll.widthMm(), 0));
            case Medium.CutSheet sheet -> I18n.format(sheet.orientation() == Medium.Orientation.PORTRAIT
                            ? "export.layout.sheets.portrait" : "export.layout.sheets.landscape",
                    u.size(sheet.acrossMm(), sheet.downMm(), 0));
        };
    }

    /** The zoom as the detail step's table names it, then each side as its controls do. */
    @Override
    public @NotNull String regions(final @NotNull Regions regions) {
        final String zoom = regions.scale() == 1 ? Integer.toString(regions.zoom())
                : I18n.format("detail.zoom.retina", regions.zoom());
        final boolean outlined = regions.boundary() != null;
        if (regions.isUniform()) {
            return I18n.format(outlined ? "export.layout.regions.whole.outlined" : "export.layout.regions.whole",
                    zoom, side(regions.inside()));
        }
        return I18n.format(outlined ? "export.layout.regions.split.outlined" : "export.layout.regions.split",
                zoom, side(regions.inside()), side(regions.outside()));
    }

    /** One side of the subject: its colors as the detail step names them, or the paper where it is not drawn. */
    private static @NotNull String side(final Regions.@NotNull RegionSpec spec) {
        final TileEffect effect = spec.effect();
        if (!spec.drawn()) {
            return I18n.get("export.layout.side.blank");
        }
        if (effect.isIdentity()) {
            return I18n.get("export.layout.side.unchanged");
        }
        if (effect.equals(TileEffect.FADED)) {
            return I18n.get("export.layout.side.faded");
        }
        return I18n.format("export.layout.side.custom", I18n.number(effect.saturation(), 2),
                I18n.number(effect.lightness(), 2), I18n.number(effect.contrast(), 2));
    }

    @Override
    public @NotNull String scale(final double mapScale, final double dpi) {
        return I18n.format("export.layout.scale", I18n.integer(Math.round(mapScale)), I18n.integer(Math.round(dpi)));
    }

    @Override
    public @NotNull String missing(final int tiles, final @NotNull MissingTilePolicy policy) {
        return I18n.format(policy == MissingTilePolicy.PLACEHOLDER
                ? "export.layout.missing.marked" : "export.layout.missing.blank", I18n.integer(tiles));
    }

    @Override
    public @NotNull String proof() {
        return I18n.get("export.layout.proof");
    }

    @Override
    public @NotNull String print(final @NotNull SheetLayout layout) {
        final Units u = Units.current();
        return I18n.format("export.layout.print", u.size(layout.print().widthMm(), layout.print().heightMm(), 0),
                I18n.integer(layout.count()), I18n.integer(layout.columns()), I18n.integer(layout.rows()),
                u.length(layout.overlapMm(), 1));
    }

    @Override
    public @NotNull String sheet(final SheetLayout.@NotNull Sheet sheet) {
        final Units u = Units.current();
        return I18n.format("export.layout.sheet", I18n.integer(sheet.index()), I18n.integer(sheet.column() + 1),
                I18n.integer(sheet.row() + 1), u.length(sheet.xMm(), 0), u.length(sheet.yMm(), 0),
                u.size(sheet.widthMm(), sheet.heightMm(), 0));
    }

    @Override
    public @NotNull List<String> hanging() {
        return List.of(I18n.get("export.layout.hanging"), I18n.get("export.layout.hanging.cut"),
                I18n.get("export.layout.hanging.leave"));
    }

    @Override
    public @NotNull String theme(final @NotNull String themeId) {
        return I18n.format("export.attribution.map", themeId);
    }

    @Override
    public @NotNull String terms(final @NotNull String url) {
        return I18n.format("export.attribution.terms", url);
    }

    @Override
    public @NotNull String producedBy() {
        return I18n.get("export.attribution.producedBy");
    }
}
