package net.trdlo.wtf.studio;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.TileDownloader;
import net.trdlo.wtf.tiles.TileKey;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Whether "fetch as I pan" may try the view again, and whether to say what stood in its way, off
 * the toolkit so the rules are testable, as {@link Closing} and {@code ExportCount} are.
 * <p>
 * A view that settles twice in a row is not two decisions. A provider that needs a key and has
 * none is not asked; a viewing-only provider is not asked for more than it allows; and a key the
 * provider has rejected is not sent again until the key itself changes – checked against the key's
 * current value, not against whichever dialog last touched it, so fixing it anywhere lifts the
 * block on its own. What a job did not get is the same kind of leftover whether it landed with
 * some of it still missing or threw outright: {@link #mayTry} leaves it alone either way, until a
 * later job lands or somebody asks for it again ({@link #retry}).
 * <p>
 * Each of these is said once ({@link #tell}), then kept quiet, so that panning does not raise the
 * same dialog over and over; choosing a provider or changing its key makes it worth saying again.
 */
final class FetchAsIPan {

    /** What stood in the way of the view's download, each said once. */
    enum Notice { NO_KEY, KEY_REJECTED, TOO_MANY, FAILED }

    private final Set<TileKey> failedHere = new HashSet<>();
    private @Nullable String rejectedProvider;
    private @Nullable String rejectedKey;

    private final Set<Notice> told = EnumSet.noneOf(Notice.class);
    private @Nullable String toldProvider;
    private @Nullable String toldKey;

    /** Whether the given tile is one the last job asked for and did not get. */
    boolean isFailed(final @NotNull TileKey key) {
        return failedHere.contains(key);
    }

    /**
     * Whether the view may be tried now: something is missing, the provider has a key
     * it needs, the view is not more than a viewing-only provider allows in one go, and the key
     * is not one the provider has already rejected.
     */
    boolean mayTry(final @NotNull Provider p, final @Nullable String apiKey, final int missing) {
        if (missing <= 0) {
            return false;
        }
        if (p.needsKey() && apiKey == null) {
            return false;
        }
        if (!p.bulkDownload() && missing > TileDownloader.BROWSE_LIMIT) {
            return false;
        }
        return !(p.id().equals(rejectedProvider) && Objects.equals(apiKey, rejectedKey));
    }

    /** A job landed, perhaps leaving some of what it tried behind. */
    void finished(final @NotNull Collection<TileKey> stillMissing) {
        failedHere.clear();
        failedHere.addAll(stillMissing);
    }

    /** A job threw instead of landing: what it tried is as failed as a landed job's leftovers. */
    void threw(final @NotNull Collection<TileKey> attempted) {
        failedHere.addAll(attempted);
    }

    /**
     * The provider rejected this key: {@link #mayTry} says no until the key changes. The tiles are
     * not marked failed, so a new key fetches them.
     */
    void keyRejected(final @NotNull String providerId, final @Nullable String apiKey) {
        rejectedProvider = providerId;
        rejectedKey = apiKey;
    }

    /**
     * Whether to say this now: true the first time for the provider and key, false after it until
     * the provider is chosen again ({@link #providerChanged}) or its key changes.
     */
    boolean tell(final @NotNull Notice notice, final @NotNull String providerId, final @Nullable String apiKey) {
        if (!providerId.equals(toldProvider) || !Objects.equals(apiKey, toldKey)) {
            told.clear();
            toldProvider = providerId;
            toldKey = apiKey;
        }
        return told.add(notice);
    }

    /** Another provider was chosen, or the same one again: everything is worth saying once more. */
    void providerChanged() {
        told.clear();
    }

    /**
     * Somebody asked for the view again: the tiles it failed on may be tried, and what goes wrong
     * this time is said, since somebody is waiting to hear.
     */
    void retry() {
        failedHere.clear();
        told.clear();
    }
}
