package net.trdlo.wtf.compose;

import org.jetbrains.annotations.NotNull;
import net.trdlo.wtf.tiles.Refusal;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The subject shape cuts the print in two, and each side carries its own treatment, at the one
 * zoom of the whole print.
 * <p>
 * <b>The two regions are symmetric.</b> Whatever can be done to one can be done to the other, with
 * the same record and the same code – printing everything around Switzerland in color and
 * Switzerland itself faded is the same two values the other way round, not a special case.
 * <p>
 * Everything is opt-in: with no subject shape there is one region, no clip and no effect, and the
 * export is what it would have been without the feature.
 *
 * @param zoom  the zoom every tile of the print is fetched at, on both sides
 * @param scale 1, or 2 for the {@code @2x} tiles of that zoom: the same ground and the same names
 *              in twice the pixels, so the print has the DPI of the zoom above and the names of
 *              this one
 */
public record Regions(int zoom, int scale, @NotNull RegionSpec inside, @NotNull RegionSpec outside,
                      @Nullable BoundaryStroke boundary) {

    public Regions {
        if (scale != 1 && scale != 2) {
            throw new IllegalArgumentException("A print's scale is 1 or 2, not " + scale);
        }
    }

    /** At the usual scale. */
    public Regions(final int zoom, final @NotNull RegionSpec inside, final @NotNull RegionSpec outside,
                   final @Nullable BoundaryStroke boundary) {
        this(zoom, 1, inside, outside, boundary);
    }

    /**
     * One region.
     *
     * @param drawn false blanks it – crops the map to the other side – and is available on
     *              either side, because a map that exists only outside a country outline is a
     *              perfectly reasonable stencil
     */
    public record RegionSpec(@NotNull TileEffect effect, boolean drawn) {

        public static final RegionSpec PLAIN = new RegionSpec(TileEffect.NONE, true);
        public static final RegionSpec FADED = new RegionSpec(TileEffect.FADED, true);
        public static final RegionSpec BLANK = new RegionSpec(TileEffect.NONE, false);

        @Override
        public @NotNull String toString() {
            return drawn ? effect.toString() : "not drawn";
        }
    }

    /** An optional visible line along the subject's own outline. Vector, so it stays sharp. */
    public record BoundaryStroke(double widthMm, int rgb) {
        public BoundaryStroke {
            if (widthMm <= 0) {
                throw new Refusal(ComposeReason.STROKE_NOT_POSITIVE);
            }
        }
    }

    /** One region for the whole print: no subject, or a subject that changes nothing. */
    public static @NotNull Regions single(final int zoom) {
        return new Regions(zoom, RegionSpec.PLAIN, RegionSpec.PLAIN, null);
    }

    /** The common arrangement: the subject in full color, everything around it faded. */
    public static @NotNull Regions subjectAndFadedSurround(final int zoom) {
        return new Regions(zoom, RegionSpec.PLAIN, RegionSpec.FADED, null);
    }

    /**
     * True when both sides are drawn alike, so the export is one region: planned once and clipped
     * nowhere. The boundary stroke is no part of it, because it is drawn on top of the map either
     * way; counting it made a stroked outline plan its ring of tiles twice.
     */
    public boolean isUniform() {
        return inside.equals(outside);
    }

    public @NotNull String describe() {
        return String.format(Locale.ROOT, "zoom %d%s, ", zoom, scale == 1 ? "" : " @" + scale + "x")
                + (isUniform() ? "one region" : "inside " + inside + ", outside " + outside)
                + (boundary == null ? "" : ", boundary stroked");
    }
}
