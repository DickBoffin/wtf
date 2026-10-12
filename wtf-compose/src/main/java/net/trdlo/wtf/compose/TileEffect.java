package net.trdlo.wtf.compose;

import org.jetbrains.annotations.NotNull;
import net.trdlo.wtf.tiles.Refusal;

import java.util.Locale;

/**
 * How a region is colored.
 * <p>
 * The whole effect is one pure function, {@code int rgb -> int rgb}, and that single decision is
 * what keeps effects from destroying the passthrough design: on an indexed tile it is applied to
 * the 256-entry palette rather than to 65 536 pixels, which is exact and effectively free.
 * See docs/design/compose-module.md §5.2.
 *
 * @param saturation 1 leaves color alone, 0 is gray
 * @param lightness  0 leaves it alone, +1 is white, -1 is black
 * @param contrast   0 leaves it alone, -1 is flat mid-gray
 */
public record TileEffect(double saturation, double lightness, double contrast) {

    public static final TileEffect NONE = new TileEffect(1, 0, 0);

    /** The preset for a region that should recede. */
    public static final TileEffect FADED = new TileEffect(0, 0.35, -0.45);

    public TileEffect {
        if (saturation < 0 || lightness < -1 || lightness > 1 || contrast < -1 || contrast > 1) {
            throw new Refusal(ComposeReason.EFFECT_OUT_OF_RANGE, saturation, lightness, contrast);
        }
    }

    public boolean isIdentity() {
        return saturation == 1 && lightness == 0 && contrast == 0;
    }

    /**
     * True when the effect is a per-channel linear map, which a PDF {@code /Decode} array can
     * carry without touching a pixel. Saturation is the exception: it mixes channels, and no
     * per-channel map can do that.
     */
    public boolean isLinearPerChannel() {
        return saturation == 1;
    }

    /**
     * The same effect on a one-channel image, where saturation has no channels to mix and so
     * does nothing: its lightness and contrast, which a {@code /Decode} array can carry. A gray
     * tile is not decoded for saturation.
     */
    public @NotNull TileEffect onOneChannel() {
        return saturation == 1 ? this : new TileEffect(1, lightness, contrast);
    }

    /**
     * {@code /Decode} coefficient: {@code out = a * in + b}. Valid only when {@link #isLinearPerChannel()}.
     * <p>
     * Contrast first, about mid-gray; then lightness, which moves every value toward white by
     * that share of the way, or toward black – {@code out(1 + l)} – when it is negative, as the
     * preview's brightness does. Not {@code 1 - l} on both sides, which below zero stretches the
     * values instead: at −1 white would stay white and mid-gray go black.
     */
    public double decodeA() {
        return (1 + contrast) * (1 - Math.abs(lightness));
    }

    public double decodeB() {
        return -0.5 * contrast * (1 - Math.abs(lightness)) + Math.max(0, lightness);
    }

    /**
     * True when a {@code /Decode} array can carry the effect: a per-channel linear map whose
     * two ends, {@code b} and {@code a + b}, are colors. Added contrast pushes them past 0 or 1,
     * and a component outside its range is left to whatever the viewer or the RIP makes of it;
     * {@link #apply} clamps, and the decode path is where that is sure.
     */
    public boolean fitsDecode() {
        final double low = decodeB();
        final double high = decodeA() + low;
        return isLinearPerChannel() && low >= -DECODE_SLACK && low <= 1 + DECODE_SLACK
                && high >= -DECODE_SLACK && high <= 1 + DECODE_SLACK;
    }

    /** Arithmetic noise, so that {@code (1 - l) + l} counts as the 1 it is. */
    private static final double DECODE_SLACK = 1e-9;

    /** The whole color transform, on one packed {@code 0xRRGGBB}. */
    public int apply(final int rgb) {
        double r = ((rgb >> 16) & 0xFF) / 255.0;
        double g = ((rgb >> 8) & 0xFF) / 255.0;
        double b = (rgb & 0xFF) / 255.0;

        if (saturation != 1) {
            // Rec. 601 luma, which is what every "desaturate" everywhere uses
            final double y = 0.299 * r + 0.587 * g + 0.114 * b;
            r = y + (r - y) * saturation;
            g = y + (g - y) * saturation;
            b = y + (b - y) * saturation;
        }
        final double a = decodeA();
        final double c = decodeB();
        return (clamp(r * a + c) << 16) | (clamp(g * a + c) << 8) | clamp(b * a + c);
    }

    private static int clamp(final double v) {
        return (int) Math.round(Math.max(0, Math.min(1, v)) * 255);
    }

    @Override
    public @NotNull String toString() {
        if (isIdentity()) {
            return "none";
        }
        return String.format(Locale.ROOT, "saturation %.2f, lightness %+.2f, contrast %+.2f",
                saturation, lightness, contrast);
    }
}
