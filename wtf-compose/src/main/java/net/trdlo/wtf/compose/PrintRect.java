package net.trdlo.wtf.compose;

import org.jetbrains.annotations.NotNull;
import net.trdlo.wtf.tiles.Refusal;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Mercator;

import java.util.Locale;

/**
 * The printed area: a rectangle in the Web Mercator unit square, plus how big it is on paper.
 * <p>
 * y runs 0..1 from north to south, as the tile grid does. The scale is uniform by construction –
 * {@code widthMm / (x1 - x0)} equals {@code heightMm / (y1 - y0)} – because a map with a
 * non-uniform scale is not a map, and {@link #checkUniform()} says so out loud.
 */
public record PrintRect(double x0, double y0, double x1, double y1,
                        double widthMm, double heightMm) {

    public PrintRect {
        if (x1 <= x0 || y1 <= y0) {
            throw new IllegalArgumentException("Degenerate print rectangle: "
                    + x0 + "," + y0 + " .. " + x1 + "," + y1);
        }
        if (widthMm <= 0 || heightMm <= 0) {
            throw new Refusal(ComposeReason.PRINT_SIZE_NOT_POSITIVE, widthMm, heightMm);
        }
    }

    public double unitWidth() {
        return x1 - x0;
    }

    public double unitHeight() {
        return y1 - y0;
    }

    /** Millimeters of paper per unit of the unit square. The number everything else is built on. */
    public double mmPerUnit() {
        return widthMm / unitWidth();
    }

    public void checkUniform() {
        final double byWidth = widthMm / unitWidth();
        final double byHeight = heightMm / unitHeight();
        if (Math.abs(byWidth - byHeight) > byWidth * 1e-6) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "Non-uniform scale: %.6f mm/unit across, %.6f down. The map would be stretched.",
                    byWidth, byHeight));
        }
    }

    public double centerLat() {
        return Mercator.unitYToLat((y0 + y1) / 2);
    }

    public @NotNull LonLatBounds bounds() {
        return Shapes.fromUnitSquare(x0, y0, x1, y1);
    }

    // -------------------------------------------------------------------------------------------
    // The scale triangle: ground, paper and dots. Any two fix the third.
    // -------------------------------------------------------------------------------------------

    /** Pixels across, if the print were rendered at this zoom's native resolution. */
    public double pixelWidth(final int zoom) {
        return pixelWidth(zoom, 1);
    }

    /** Pixels across, of this zoom's tiles at this scale: twice as many from {@code @2x} tiles. */
    public double pixelWidth(final int zoom, final int scale) {
        return unitWidth() * Mercator.TILE_SIZE * scale * Mercator.tileCount(zoom);
    }

    public double pixelHeight(final int zoom) {
        return unitHeight() * Mercator.TILE_SIZE * Mercator.tileCount(zoom);
    }

    /** Dots per inch at this zoom, native: one tile pixel is one print dot. */
    public double dpi(final int zoom) {
        return dpi(zoom, 1);
    }

    /**
     * Dots per inch from this zoom's tiles at this scale. A {@code @2x} tile doubles it and leaves
     * {@link #labelHeightMm} as it is: its names are drawn in twice the pixels too.
     */
    public double dpi(final int zoom, final int scale) {
        return pixelWidth(zoom, scale) / (widthMm / 25.4);
    }

    /** Ground meters spanned across, at the rectangle's center latitude. */
    public double groundWidthMeters() {
        return unitWidth() * Mercator.EQUATORIAL_CIRCUMFERENCE * Math.cos(Math.toRadians(centerLat()));
    }

    /** The map scale denominator: 1:N. */
    public double mapScale() {
        return groundWidthMeters() / (widthMm / 1000.0);
    }

    /**
     * How far apart the scale is at the top and the bottom edge, as a fraction: 0.01 is one
     * percent. Mercator stretches the ground by 1/cos(latitude), so a city prints at one scale and
     * a continent at none.
     */
    public double scaleVariation() {
        final double top = Math.cos(Math.toRadians(Mercator.unitYToLat(y0)));
        final double bottom = Math.cos(Math.toRadians(Mercator.unitYToLat(y1)));
        return Math.abs(top - bottom) / Math.max(top, bottom);
    }

    /**
     * How tall a place name will be on the paper, in millimeters.
     * <p>
     * The counterintuitive number, and the easiest way to ruin a five-meter print: in native mode
     * the DPI and the zoom are the same choice, so raising the DPI <b>shrinks</b> every label.
     * Reading stops working near 1.5 mm, which puts a readable map wallpaper around 100-150 DPI
     * rather than the 300 a photograph wants. See docs/design/compose-module.md §3.
     *
     * @param labelPixels a place name is typically 10-14 px in a rendered tile
     */
    public double labelHeightMm(final int zoom, final double labelPixels) {
        return labelPixels / dpi(zoom) * 25.4;
    }

    public double labelHeightMm(final int zoom) {
        return labelHeightMmAt(dpi(zoom));
    }

    /** How tall a typical place name is at this DPI, whatever the zoom: one tile pixel is one dot. */
    public static double labelHeightMmAt(final double dpi) {
        return LABEL_PIXELS / dpi * 25.4;
    }

    /** A typical place name's height in a rendered tile, in pixels. */
    private static final double LABEL_PIXELS = 12;

    /** Grown symmetrically, in millimeters of paper – how the layout spends the spare paper. */
    public @NotNull PrintRect grownByMm(final double acrossMm, final double downMm) {
        final double perUnit = mmPerUnit();
        final double dx = acrossMm / perUnit / 2;
        final double dy = downMm / perUnit / 2;
        return new PrintRect(x0 - dx, y0 - dy, x1 + dx, y1 + dy,
                widthMm + acrossMm, heightMm + downMm);
    }

    /** The sub-rectangle of this print covered by a sheet at {@code (xMm, yMm)} from the top-left. */
    public @NotNull PrintRect slice(final double xMm, final double yMm,
                                    final double wMm, final double hMm) {
        final double perUnit = mmPerUnit();
        return new PrintRect(x0 + xMm / perUnit, y0 + yMm / perUnit,
                x0 + (xMm + wMm) / perUnit, y0 + (yMm + hMm) / perUnit, wMm, hMm);
    }

    @Override
    public @NotNull String toString() {
        return String.format(Locale.ROOT, "PrintRect[%.0f x %.0f mm, 1:%,.0f]", widthMm, heightMm, mapScale());
    }
}
