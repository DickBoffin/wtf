package net.trdlo.wtf.tiles;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * Identifies one tile: which provider, which theme, in which language, and where. The cache key,
 * the download unit and the thing a conditional breakpoint is written against – hence the
 * readable {@link #toString()}, which is not decoration.
 * <p>
 * The language is part of the tile only for a theme whose address takes {@code {lang}} (D25):
 * Mapy.com letters its map in the language asked for, so a tile fetched in Czech is not the tile
 * in German, and a cache that did not tell them apart served whichever came first to everyone.
 * For every other theme it is null, which {@link Provider#key} sees to, so that one tile is not
 * cached twice under two languages it does not have.
 *
 * <p>
 * The scale is the tile's pixel density: 1 for the usual 256 px, 2 for a {@code @2x} tile, which
 * covers the same ground in 512 px – the same map drawn twice as sharp, not more of it.
 *
 * @param lang  the language the tile was lettered in, or null when the theme has no language
 * @param scale 1, or 2 for a {@code @2x} tile of a theme that offers one ({@link Provider#offersRetina})
 */
public record TileKey(@NotNull String providerId, @NotNull String themeId, @Nullable String lang,
                      int zoom, int x, int y, int scale) {

    /** What a language may be: a tag such as {@code cs} or {@code pt-BR}, and one folder name. */
    public static final Pattern LANG = Pattern.compile("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8}){0,3}");

    public TileKey {
        if (providerId.isBlank() || themeId.isBlank()) {
            throw new IllegalArgumentException("providerId and themeId are required");
        }
        if (lang != null && !LANG.matcher(lang).matches()) {
            throw new IllegalArgumentException("'" + lang + "' is not a language");
        }
        if (scale != 1 && scale != 2) {
            throw new IllegalArgumentException("A tile's scale is 1 or 2, not " + scale);
        }
        final int count = Mercator.tileCount(zoom);
        if (x < 0 || x >= count || y < 0 || y >= count) {
            throw new IllegalArgumentException(
                    "Tile " + x + "," + y + " is outside the world at zoom " + zoom + " (0.." + (count - 1) + ")");
        }
    }

    /** A tile at the usual scale. */
    public TileKey(final @NotNull String providerId, final @NotNull String themeId, final @Nullable String lang,
                   final int zoom, final int x, final int y) {
        this(providerId, themeId, lang, zoom, x, y, 1);
    }

    /** A tile of a theme that has no language, at the usual scale. */
    public TileKey(final @NotNull String providerId, final @NotNull String themeId,
                   final int zoom, final int x, final int y) {
        this(providerId, themeId, null, zoom, x, y, 1);
    }

    /** Another tile of the same map, in the same language and scale: a neighbor, a parent, a child. */
    public @NotNull TileKey at(final int zoom, final int x, final int y) {
        return new TileKey(providerId, themeId, lang, zoom, x, y, scale);
    }

    /** The same tile at another scale. */
    public @NotNull TileKey atScale(final int scale) {
        return new TileKey(providerId, themeId, lang, zoom, x, y, scale);
    }

    /** Its pixels across: 256, or 512 for a {@code @2x} tile. */
    public int pixels() {
        return Mercator.TILE_SIZE * scale;
    }

    public @NotNull LonLatBounds bounds() {
        return Mercator.tileBounds(zoom, x, y);
    }

    @Override
    public @NotNull String toString() {
        return providerId + "/" + themeId + (lang == null ? "" : "@" + lang) + "/" + zoom + "/" + x + "/" + y
                + (scale == 1 ? "" : "@" + scale + "x");
    }
}
