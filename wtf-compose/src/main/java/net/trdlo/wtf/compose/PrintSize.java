package net.trdlo.wtf.compose;

import org.jetbrains.annotations.NotNull;
import net.trdlo.wtf.tiles.Refusal;
import org.jetbrains.annotations.Nullable;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Mercator;

/**
 * How big the print is on paper.
 * <p>
 * The brief asks for the medium at step 3 and the zoom at step 4 and expects the DPI to fall out –
 * which it only does if the physical size is known, and the brief never says where that comes
 * from. It is an input, offered four ways. See docs/design/compose-module.md §3.
 */
public record PrintSize(@NotNull Mode mode, double widthMm, double heightMm) {

    public enum Mode {
        /** "The print is 5000 mm wide"; the height follows. The default: a wall is measured across first. */
        FIT_WIDTH,
        /** The mirror image. */
        FIT_HEIGHT,
        /** "It must fit in 5000 x 3000 mm"; the tighter constraint wins, the other axis comes out shorter. */
        FIT_INSIDE,
        /** "It is exactly 5000 x 3000 mm"; the map rectangle grows on the slack axis, taking in more map. */
        FILL_AND_CROP
    }

    public static @NotNull PrintSize width(final double mm) {
        return new PrintSize(Mode.FIT_WIDTH, mm, 0);
    }

    public static @NotNull PrintSize height(final double mm) {
        return new PrintSize(Mode.FIT_HEIGHT, 0, mm);
    }

    public static @NotNull PrintSize inside(final double wMm, final double hMm) {
        return new PrintSize(Mode.FIT_INSIDE, wMm, hMm);
    }

    public static @NotNull PrintSize fill(final double wMm, final double hMm) {
        return new PrintSize(Mode.FILL_AND_CROP, wMm, hMm);
    }

    /**
     * The width at which the fill shape's bounds print at this DPI from tiles of this zoom and
     * scale – the other way round of {@link PrintRect#dpi(int, int)}, so that one gives back
     * {@code dpi}.
     * <p>
     * One tile pixel is one printed dot, so a place name is as tall on paper at every zoom, and the
     * zoom decides only how big the print is; {@code @2x} tiles make it the size of the zoom above,
     * with this zoom's names twice as tall.
     */
    public static @NotNull PrintSize atDpi(final @NotNull LonLatBounds fillBounds, final int zoom,
                                           final int scale, final double dpi) {
        if (!(dpi > 0)) {
            throw new IllegalArgumentException("DPI must be positive: " + dpi);
        }
        final double[] u = Shapes.toUnitSquare(fillBounds);
        final double pixels = (u[2] - u[0]) * Mercator.TILE_SIZE * scale * Mercator.tileCount(zoom);
        return width(pixels / dpi * 25.4);
    }

    /**
     * The width at which the fill shape's bounds print at the scale 1:{@code denominator} – the
     * other way round of {@link PrintRect#mapScale()}, so that one gives back {@code denominator}.
     * Mercator's scale changes with the latitude; this is the one at the print's middle, where
     * {@code mapScale} measures it. The zoom decides only the DPI.
     */
    public static @NotNull PrintSize atScale(final @NotNull LonLatBounds fillBounds, final double denominator) {
        if (!(denominator > 0)) {
            throw new IllegalArgumentException("A scale's denominator must be positive: " + denominator);
        }
        final double[] u = Shapes.toUnitSquare(fillBounds);
        final double lat = Mercator.unitYToLat((u[1] + u[3]) / 2);
        final double groundMeters = (u[2] - u[0]) * Mercator.EQUATORIAL_CIRCUMFERENCE
                * Math.cos(Math.toRadians(lat));
        return width(groundMeters / denominator * 1000.0);
    }

    /**
     * Turns the fill shape's bounds into a print rectangle.
     * <p>
     * Only {@code FILL_AND_CROP} changes the geography; the rest only compute millimeters. None of
     * them ever distorts.
     */
    public @NotNull PrintRect apply(final @NotNull LonLatBounds fillBounds) {
        final double[] u = Shapes.toUnitSquare(fillBounds);
        double x0 = u[0], y0 = u[1], x1 = u[2], y1 = u[3];
        final double uw = x1 - x0, uh = y1 - y0;
        if (uw <= 0 || uh <= 0) {
            throw new Refusal(ComposeReason.PROJECTS_TO_NOTHING);
        }
        // width over height in the unit square, which is also the paper aspect: the scale is uniform
        final double aspect = uw / uh;

        final double w;
        final double h;
        switch (mode) {
            case FIT_WIDTH -> {
                require(widthMm > 0, ComposeReason.SIZE_NEEDS_WIDTH);
                w = widthMm;
                h = w / aspect;
            }
            case FIT_HEIGHT -> {
                require(heightMm > 0, ComposeReason.SIZE_NEEDS_HEIGHT);
                h = heightMm;
                w = h * aspect;
            }
            case FIT_INSIDE -> {
                require(widthMm > 0 && heightMm > 0, ComposeReason.SIZE_NEEDS_BOX);
                if (widthMm / aspect > heightMm) {   // too tall: the height binds
                    h = heightMm;
                    w = h * aspect;
                } else {
                    w = widthMm;
                    h = w / aspect;
                }
            }
            case FILL_AND_CROP -> {
                require(widthMm > 0 && heightMm > 0, ComposeReason.SIZE_NEEDS_BOX);
                w = widthMm;
                h = heightMm;
                final double wanted = w / h;
                if (wanted > aspect) {               // the paper is wider: take in more map east-west
                    final double grow = (uh * wanted - uw) / 2;
                    x0 -= grow;
                    x1 += grow;
                } else if (wanted < aspect) {
                    final double grow = (uw / wanted - uh) / 2;
                    y0 -= grow;
                    y1 += grow;
                }
            }
            default -> throw new IllegalStateException(String.valueOf(mode));
        }
        final PrintRect rect = new PrintRect(x0, y0, x1, y1, w, h);
        rect.checkUniform();
        return rect;
    }

    private static void require(final boolean ok, final @NotNull ComposeReason why) {
        if (!ok) {
            throw new Refusal(why);
        }
    }

    /**
     * For the project file, where a side the mode does not use is absent: not only a zero, but a
     * length the size step still holds and the mode ignores – the height of {@code FIT_WIDTH},
     * which the width and the area decide, and which the file would carry as though it mattered.
     */
    public @Nullable Double widthOrNull() {
        return mode != Mode.FIT_HEIGHT && widthMm > 0 ? widthMm : null;
    }

    public @Nullable Double heightOrNull() {
        return mode != Mode.FIT_WIDTH && heightMm > 0 ? heightMm : null;
    }
}
