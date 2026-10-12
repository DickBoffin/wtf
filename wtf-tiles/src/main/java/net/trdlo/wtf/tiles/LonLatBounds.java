package net.trdlo.wtf.tiles;

import org.jetbrains.annotations.NotNull;

/**
 * A geographic rectangle in WGS84 degrees. Deliberately not a JTS {@code Envelope}: keeping JTS out
 * of {@code wtf-tiles} means the tile module has no geometry dependency at all, and the one place
 * that needs prepared geometry – tile selection against a shape – lives in {@code wtf-compose},
 * which has JTS anyway.
 *
 * @param west  minimum longitude
 * @param south minimum latitude
 * @param east  maximum longitude
 * @param north maximum latitude
 */
public record LonLatBounds(double west, double south, double east, double north) {

    public LonLatBounds {
        if (east < west) {
            throw new IllegalArgumentException(
                    "east (" + east + ") is west of west (" + west + "); a range crossing the "
                            + "antimeridian is refused - see docs/open-questions.md D22");
        }
        if (north < south) {
            throw new IllegalArgumentException("north (" + north + ") is south of south (" + south + ")");
        }
    }

    /** One place, in degrees. Here rather than in wtf-compose, for the same reason as the bounds. */
    public record Point(double lon, double lat) {
    }

    public boolean contains(final double lon, final double lat) {
        return lon >= west && lon <= east && lat >= south && lat <= north;
    }

    public static @NotNull LonLatBounds of(final double lon, final double lat) {
        return new LonLatBounds(lon, lat, lon, lat);
    }

    public double widthDegrees() {
        return east - west;
    }

    public double heightDegrees() {
        return north - south;
    }

    public double centerLon() {
        return (west + east) / 2;
    }

    public double centerLat() {
        return (south + north) / 2;
    }

    public @NotNull LonLatBounds expandedBy(final double degrees) {
        return new LonLatBounds(west - degrees, Math.max(-90, south - degrees),
                east + degrees, Math.min(90, north + degrees));
    }

    @Override
    public @NotNull String toString() {
        return String.format(java.util.Locale.ROOT, "[%.5f %.5f, %.5f %.5f]", west, south, east, north);
    }
}
