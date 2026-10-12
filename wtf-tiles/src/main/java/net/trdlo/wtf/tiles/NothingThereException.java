package net.trdlo.wtf.tiles;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Locale;

/**
 * Raised when every tile of a job is one the provider says does not exist, and none of it is
 * cached either – a download that came to nothing, or a print with nothing in it.
 * <p>
 * Sea beside land is ordinary: a job with one real tile in it, cached or fetched, never raises
 * this. A job with none is entirely outside what the provider covers, or is asking the wrong
 * address – a mistyped template answers 404 everywhere – and neither is a success. The downloader
 * raises it for a job that came to nothing, and the composer for a print that would be nothing
 * but its background, whatever the missing-tile policy: one exception, where both modules reach
 * it, so that a window says the first press on such a print, which downloads, and the second,
 * which exports, the same way.
 * <p>
 * The answers are recorded. The downloader raises this only once every one of them is, since an
 * answer the cache would not keep stops the job with the cache's own error instead; real sea is
 * then not asked for twice, and once a wrong template is fixed, {@link TileCache#forgetEmpty}
 * takes the answers back.
 */
public final class NothingThereException extends IOException {
    private final transient Provider provider;
    private final int tiles;

    /** @param tiles how many tiles the provider says do not exist: the whole job */
    public NothingThereException(final @NotNull Provider p, final int tiles) {
        super(p.displayName() + " says that none of these " + String.format(Locale.ROOT, "%,d", tiles)
                + " tiles exist (HTTP 204 or 404). Either the place is outside what it covers,"
                + " or its address template is wrong.");
        this.provider = p;
        this.tiles = tiles;
    }

    public @NotNull Provider provider() {
        return provider;
    }

    public int tiles() {
        return tiles;
    }
}
