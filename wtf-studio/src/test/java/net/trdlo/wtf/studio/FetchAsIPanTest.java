package net.trdlo.wtf.studio;

import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.TileDownloader;
import net.trdlo.wtf.tiles.TileKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What "fetch as I pan" tries again, and what it leaves alone – a rejected key, a viewing-
 * only provider past its limit, and a job that threw rather than one that only landed short.
 */
class FetchAsIPanTest {

    private static Provider keyedProvider(final boolean bulkDownload) {
        return new Provider("maptiler", "MapTiler", "https://x/{z}/{x}/{y}.png",
                new Provider.Auth(Provider.AuthKind.QUERY, "key"), 0, 19, null,
                List.of(new Provider.Theme("basic", "Basic", null, null, null, null, false, null)),
                null, "none", null, null, null, bulkDownload, null, null, null, null);
    }

    private static Provider viewingOnlyProvider() {
        return new Provider("esri", "Esri", "https://x/{z}/{x}/{y}.png",
                null, 0, 19, null,
                List.of(new Provider.Theme("world", "World", null, null, null, null, false, null)),
                null, "none", null, null, null, false, null, null, null, null);
    }

    private static TileKey tile(final int x, final int y) {
        return new TileKey("maptiler", "basic", 12, x, y);
    }

    @Test
    @DisplayName("a rejected key is tried once, then left alone across every later pan")
    void oneDialogNotOnePerPan() {
        final Provider p = keyedProvider(true);
        final FetchAsIPan fetch = new FetchAsIPan();
        final String badKey = "revoked";

        // The view settles for the first time; nothing has been rejected yet, so it is worth trying
        assertTrue(fetch.mayTry(p, badKey, 12));

        // The job throws MissingCredentialsException
        fetch.keyRejected(p.id(), badKey);

        // Every later pan settles on a different view – fewer tiles, more tiles – still the same
        // rejected key, so the view's download stays quiet instead of asking again and being told no again
        for (final int missing : new int[] {1, 40, 300}) {
            assertFalse(fetch.mayTry(p, badKey, missing), "the key has not changed");
        }

        // The key is fixed – in the Providers dialog, or wherever else it might be – and the very
        // next pan tries again on its own, with nobody having told this class which dialog it was
        assertTrue(fetch.mayTry(p, "fixed", 12), "a different key lifts the block by itself");
        assertFalse(fetch.isFailed(tile(5, 10)), "and the tiles are fetched with it, not left as failed");
        assertFalse(fetch.mayTry(p, null, 12), "clearing the key back out is not the fixed key either");
    }

    @Test
    @DisplayName("a viewing-only provider past its browsing limit is left alone, not refused every pan")
    void bulkLimitStopsAutoDownloadToo() {
        final Provider p = viewingOnlyProvider();
        final FetchAsIPan fetch = new FetchAsIPan();

        assertTrue(fetch.mayTry(p, null, TileDownloader.BROWSE_LIMIT));
        assertFalse(fetch.mayTry(p, null, TileDownloader.BROWSE_LIMIT + 1),
                "past the limit, a download is asking for BulkDownloadRefusedException");
    }

    @Test
    @DisplayName("nothing missing, or a key not yet given, is quiet regardless of any earlier rejection")
    void ordinaryReasonsStillApply() {
        final Provider p = keyedProvider(true);
        final FetchAsIPan fetch = new FetchAsIPan();

        assertFalse(fetch.mayTry(p, "any-key", 0), "nothing missing");
        assertFalse(fetch.mayTry(p, null, 12), "the provider needs a key and has none");
    }

    @Test
    @DisplayName("a job that threw marks its tiles failed, the same as one that landed with leftovers")
    void threwCountsTheSameAsLanding() {
        final FetchAsIPan fetch = new FetchAsIPan();
        final TileKey a = tile(1, 1);
        final TileKey b = tile(1, 2);
        assertFalse(fetch.isFailed(a));
        assertFalse(fetch.isFailed(b));

        fetch.threw(List.of(a, b));
        assertTrue(fetch.isFailed(a));
        assertTrue(fetch.isFailed(b));

        // A later job landed, this time with only one of them left over
        fetch.finished(List.of(b));
        assertFalse(fetch.isFailed(a));
        assertTrue(fetch.isFailed(b));
    }

    @Test
    @DisplayName("each notice is said once, then not again on any later pan")
    void saidOnce() {
        final FetchAsIPan fetch = new FetchAsIPan();
        assertTrue(fetch.tell(FetchAsIPan.Notice.NO_KEY, "maptiler", null));
        for (int pan = 0; pan < 3; pan++) {
            assertFalse(fetch.tell(FetchAsIPan.Notice.NO_KEY, "maptiler", null), "said already");
        }
        assertTrue(fetch.tell(FetchAsIPan.Notice.FAILED, "maptiler", null), "another notice is its own");
    }

    @Test
    @DisplayName("choosing a provider, even the same one again, makes every notice worth saying again")
    void forgottenWithTheProvider() {
        final FetchAsIPan fetch = new FetchAsIPan();
        assertTrue(fetch.tell(FetchAsIPan.Notice.NO_KEY, "maptiler", null));
        fetch.providerChanged();
        assertTrue(fetch.tell(FetchAsIPan.Notice.NO_KEY, "maptiler", null), "chosen again");

        assertTrue(fetch.tell(FetchAsIPan.Notice.FAILED, "osm", null), "told about another provider");
        assertTrue(fetch.tell(FetchAsIPan.Notice.NO_KEY, "maptiler", null), "and back");
    }

    @Test
    @DisplayName("a new key, also rejected, is said again")
    void forgottenWithTheKey() {
        final FetchAsIPan fetch = new FetchAsIPan();
        assertTrue(fetch.tell(FetchAsIPan.Notice.KEY_REJECTED, "maptiler", "wrong"));
        assertFalse(fetch.tell(FetchAsIPan.Notice.KEY_REJECTED, "maptiler", "wrong"));
        assertTrue(fetch.tell(FetchAsIPan.Notice.KEY_REJECTED, "maptiler", "also wrong"));
    }

    @Test
    @DisplayName("Try again asks for the failed tiles, and what goes wrong then is said")
    void retryTriesAndTells() {
        final FetchAsIPan fetch = new FetchAsIPan();
        final TileKey a = tile(1, 1);
        fetch.finished(List.of(a));
        assertTrue(fetch.tell(FetchAsIPan.Notice.FAILED, "maptiler", "k"));

        fetch.retry();

        assertFalse(fetch.isFailed(a), "asked for again");
        assertTrue(fetch.tell(FetchAsIPan.Notice.FAILED, "maptiler", "k"), "somebody is waiting to hear");
    }
}
