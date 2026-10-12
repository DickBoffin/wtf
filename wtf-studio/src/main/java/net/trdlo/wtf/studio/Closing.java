package net.trdlo.wtf.studio;

import org.jetbrains.annotations.NotNull;

import java.util.function.BooleanSupplier;

/**
 * What a request to close the window does, in order, and off the toolkit so it can be tested.
 * <p>
 * The close asks about more than unsaved changes. The export step's worker is a daemon thread, so
 * an export or a download running there would die with the process: a {@code .part} on disk, and
 * the sheets this run had finished sitting beside the last export's under the same names. So a
 * running job is asked about first – it is what is in front of the person – then the
 * unsaved changes, and only once both are answered is the job stopped and waited for, so that a
 * Cancel to either question leaves the job running as it was.
 * <p>
 * The view's download from the toolbar is stopped and waited for too, on every close that goes
 * through, and asked about never: it holds nothing a person could lose, only the tile in hand.
 * Its thread is not a daemon, and without the wait it would end with {@code System.exit} mid-tile.
 */
public final class Closing {

    private Closing() {
    }

    /**
     * @param running        whether step 5 has a download or an export in flight
     * @param askStop        asked only when one is: whether to stop it and close
     * @param confirmDiscard the unsaved-changes question, asked when nothing else stands in the way
     * @param stop           stops the job and waits for its tidy-up – a download's tile in hand for
     *                       at most {@code ViewJob.STOP_WAIT}; called only when the window closes
     * @param stopView       stops the view's download, if one runs, and waits a moment for the tile
     *                       in hand ({@code ViewJob.STOP_WAIT}); called whenever the window closes
     * @return whether the window closes
     */
    public static boolean decide(final boolean running, final @NotNull BooleanSupplier askStop,
                                 final @NotNull BooleanSupplier confirmDiscard, final @NotNull Runnable stop,
                                 final @NotNull Runnable stopView) {
        if (running && !askStop.getAsBoolean()) {
            return false;
        }
        if (!confirmDiscard.getAsBoolean()) {
            return false;
        }
        if (running) {
            stop.run();
        }
        stopView.run();
        return true;
    }
}
