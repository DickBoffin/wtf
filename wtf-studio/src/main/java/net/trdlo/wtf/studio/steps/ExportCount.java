package net.trdlo.wtf.studio.steps;

import net.trdlo.wtf.compose.Composer;
import net.trdlo.wtf.compose.TilePlan;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What the export step last counted against the cache.
 * <p>
 * Good until the plan changes, or the cache does by a hand this panel is not told about – "Forget
 * empty tiles" in the Providers dialog, a second WTF, the command line – or a job fails, having
 * perhaps changed the cache first. A stale count skips a download that is needed, because it still
 * calls a tile empty that is not, and a refusal that is not kept repeats on every press. Off
 * {@link ExportPanel} so that the rule can be tested without a stage, as {@code Closing} is.
 */
final class ExportCount {

    private TilePlan.@Nullable Inventory inventory;

    /** What was last counted, or null when nothing was or it is no longer trusted. */
    TilePlan.@Nullable Inventory get() {
        return inventory;
    }

    /** A fresh count landed. */
    void set(final TilePlan.@NotNull Inventory inv) {
        inventory = inv;
    }

    /** What was counted may not be what the cache holds anymore. */
    void invalidate() {
        inventory = null;
    }

    /**
     * A job failed. A refusal over missing tiles is the freshest count there is, since the exporter
     * has just read the cache itself, and keeping it is what makes the next press download rather
     * than be refused again; any other failure may have changed the cache before it threw.
     */
    void failed(final @Nullable Throwable ex) {
        inventory = ex instanceof final Composer.MissingTilesException missing ? missing.inventory() : null;
    }
}
