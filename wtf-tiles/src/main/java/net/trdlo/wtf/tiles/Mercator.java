package net.trdlo.wtf.tiles;

import org.jetbrains.annotations.NotNull;

/**
 * Web Mercator (EPSG:3857) slippy-map arithmetic: WGS84 degrees in, tile and pixel coordinates out.
 * Pure, stateless and the one place where a mistake is silent, so it is also the most heavily
 * tested class in the project.
 * <p>
 * Two coordinate spaces are used throughout WTF:
 * <ul>
 *   <li><b>WGS84 degrees</b> – what the user sees, what a project file stores, JTS order
 *       {@code (x = lon, y = lat)}.</li>
 *   <li><b>The unit square</b> – Web Mercator normalized so that x and y both run 0..1 from the
 *       top-left. Tile coordinates at zoom z are that times {@code 2^z}; pixel coordinates are
 *       that times {@code 256 * 2^z}.</li>
 * </ul>
 * Latitude is clamped to {@value #MAX_LATITUDE} degrees, the point where Web Mercator's y would run
 * off to infinity. Clamped, never wrapped: a tile index outside {@code 0 .. 2^z - 1} is a bug
 * everywhere downstream, so it is not produced here.
 *
 * @see <a href="https://wiki.openstreetmap.org/wiki/Slippy_map_tilenames">Slippy map tilenames</a>
 */
public final class Mercator {

    /** The standard tile edge in pixels; a {@code @2x} tile is twice it on the same ground. */
    public static final int TILE_SIZE = 256;

    /** Where Web Mercator stops. {@code atan(sinh(PI))} in degrees. */
    public static final double MAX_LATITUDE = 85.0511287798066;

    /** Earth's equatorial circumference in meters, the WGS84 semi-major axis times 2 pi. */
    public static final double EQUATORIAL_CIRCUMFERENCE = 2 * Math.PI * 6378137.0;

    /** Meters per pixel at the equator at zoom 0. */
    public static final double EQUATOR_RESOLUTION = EQUATORIAL_CIRCUMFERENCE / TILE_SIZE;

    private Mercator() {
    }

    /** {@code 2^zoom}, the number of tiles along one edge of the world. */
    public static int tileCount(final int zoom) {
        checkZoom(zoom);
        return 1 << zoom;
    }

    private static void checkZoom(final int zoom) {
        if (zoom < 0 || zoom > 30) {
            throw new IllegalArgumentException("Zoom out of range: " + zoom);
        }
    }

    public static double clampLatitude(final double lat) {
        return Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, lat));
    }

    // -------------------------------------------------------------------------------------------
    // The unit square
    // -------------------------------------------------------------------------------------------

    /** Longitude to the unit square's x, 0 at -180 degrees and 1 at +180. */
    public static double lonToUnitX(final double lon) {
        return (lon + 180.0) / 360.0;
    }

    /** Latitude to the unit square's y, 0 at the north edge and 1 at the south. */
    public static double latToUnitY(final double lat) {
        final double rad = Math.toRadians(clampLatitude(lat));
        return (1.0 - Math.log(Math.tan(rad) + 1.0 / Math.cos(rad)) / Math.PI) / 2.0;
    }

    public static double unitXToLon(final double x) {
        return x * 360.0 - 180.0;
    }

    public static double unitYToLat(final double y) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * y))));
    }

    // -------------------------------------------------------------------------------------------
    // Tiles
    // -------------------------------------------------------------------------------------------

    /** Fractional tile x – the integer part is the tile, the fraction is the position within it. */
    public static double lonToTileX(final double lon, final int zoom) {
        return lonToUnitX(lon) * tileCount(zoom);
    }

    public static double latToTileY(final double lat, final int zoom) {
        return latToUnitY(lat) * tileCount(zoom);
    }

    /** The x index of the tile containing this longitude, clamped into range. */
    public static int tileX(final double lon, final int zoom) {
        return clampTile((int) Math.floor(lonToTileX(lon, zoom)), zoom);
    }

    public static int tileY(final double lat, final int zoom) {
        return clampTile((int) Math.floor(latToTileY(lat, zoom)), zoom);
    }

    private static int clampTile(final int index, final int zoom) {
        return Math.max(0, Math.min(tileCount(zoom) - 1, index));
    }

    public static double tileXToLon(final double tileX, final int zoom) {
        return unitXToLon(tileX / tileCount(zoom));
    }

    public static double tileYToLat(final double tileY, final int zoom) {
        return unitYToLat(tileY / tileCount(zoom));
    }

    /** The geographic bounds of one tile. */
    public static @NotNull LonLatBounds tileBounds(final int zoom, final int x, final int y) {
        return new LonLatBounds(
                tileXToLon(x, zoom), tileYToLat(y + 1.0, zoom),
                tileXToLon(x + 1.0, zoom), tileYToLat(y, zoom));
    }

    /**
     * The tiles a geographic rectangle touches, clamped to the world.
     *
     * @param expandByTiles tiles of margin added on every side; may be fractional, and is rounded
     *                      outwards. Zero for an exact cover.
     */
    public static @NotNull TileRange range(final @NotNull LonLatBounds bounds, final int zoom,
                                           final double expandByTiles) {
        final int margin = (int) Math.ceil(Math.max(0, expandByTiles));
        final int minX = tileX(bounds.west(), zoom);
        final int minY = tileY(bounds.north(), zoom);
        final TileRange exact = new TileRange(minX, minY,
                maxTile(lonToTileX(bounds.east(), zoom), minX, zoom),
                maxTile(latToTileY(bounds.south(), zoom), minY, zoom));
        return exact.expandedBy(margin).clampedTo(zoom);
    }

    /**
     * The last tile a maximum edge touches. {@code floor} is wrong here: an edge landing exactly on
     * a tile boundary would pull in the next tile, and a rectangle snapped to tile boundaries –
     * which is the ordinary case once a shape has been rounded outwards – would gain a spurious
     * column and row. That is a whole extra rank of tiles to download and pay for.
     * <p>
     * So the maximum is {@code ceil(t) - 1}, held at or above the minimum so that a degenerate
     * rectangle sitting exactly on a boundary still yields one tile rather than none.
     */
    private static int maxTile(final double fractional, final int min, final int zoom) {
        final int candidate = (int) Math.ceil(fractional) - 1;
        return clampTile(Math.max(min, candidate), zoom);
    }

    public static @NotNull TileRange range(final @NotNull LonLatBounds bounds, final int zoom) {
        return range(bounds, zoom, 0);
    }

    // -------------------------------------------------------------------------------------------
    // Ground resolution
    // -------------------------------------------------------------------------------------------

    /**
     * Meters of ground per tile pixel at this latitude and zoom. The number the whole scale
     * calculation of the print rests on – see docs/design/compose-module.md §3.
     */
    public static double groundResolution(final double lat, final int zoom) {
        return EQUATOR_RESOLUTION * Math.cos(Math.toRadians(clampLatitude(lat))) / tileCount(zoom);
    }

    /** Meters of ground covered by one whole tile at this latitude and zoom. */
    public static double tileGroundSize(final double lat, final int zoom) {
        return groundResolution(lat, zoom) * TILE_SIZE;
    }
}
