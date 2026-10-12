package net.trdlo.wtf.compose;

import org.jetbrains.annotations.NotNull;

/**
 * What to do about a tile that is not in the cache.
 * <p>
 * The question only arises because two very different things look the same from the exporter's
 * side, and keeping them apart is the whole point:
 * <ul>
 *   <li><b>A tile that does not exist.</b> The provider answered 204 or 404 – open sea, or past
 *       the edge of a national provider's coverage. Kartverket simply stops at Norway. This is not
 *       a gap, it is the map, and it is <i>always</i> filled with the background silently,
 *       whatever this policy says.</li>
 *   <li><b>A tile that was never fetched.</b> A real gap, and the thing that turns a five-meter
 *       print into a five-meter mistake. This policy is about those.</li>
 * </ul>
 * The default is to refuse the export entirely rather than choose any of these, because silently
 * printing a wall-sized map with holes in it is the worst available outcome. Every value here is
 * an explicit opt-in.
 * <p>
 * A print with nothing but the first kind in it – nothing cached, every tile known not to exist –
 * is refused whatever this says ({@link net.trdlo.wtf.tiles.NothingThereException}): it has no
 * gap to fill, and nobody means a print that is sea from edge to edge.
 */
public enum MissingTilePolicy {

    /**
     * Refuse to export and say which tiles and where. The default, and the right answer when a
     * download simply has not finished.
     */
    REFUSE,

    /**
     * Draw nothing at all, leaving the print transparent there. On paper it is the paper, which is
     * assumed white; over something else it is see-through.
     * <p>
     * The PDF paints no background under the tile, so the image is simply not there, and a PNG or
     * a TIFF has an alpha channel, the tile's square see-through. A format that could carry no
     * transparency would print white there; every format WTF writes carries it.
     */
    TRANSPARENT,

    /**
     * A red square with a cross over each hole, so a proof shows exactly where the holes are.
     * <p>
     * <b>Never for a final print.</b> The export report says so, every print file's name gains a
     * {@code -proof} suffix – {@code wall-s01-proof.pdf} – and the marker is deliberately ugly: a
     * person who prints this by accident must notice before they hang it on a wall.
     */
    PLACEHOLDER;

    public boolean allowsExport() {
        return this != REFUSE;
    }

    /** Whether a file produced under this policy is safe to send to a printer. */
    public boolean isPrintable() {
        return this == TRANSPARENT;
    }

    public @NotNull String describe() {
        return switch (this) {
            case REFUSE -> "refuse to export";
            case TRANSPARENT -> "leave it transparent";
            case PLACEHOLDER -> "draw a visible marker (proofs only)";
        };
    }
}
