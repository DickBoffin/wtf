package net.trdlo.wtf.studio;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * What a start does with the recovery copies it found, in order: off the toolkit, so that
 * {@code RecoveryOfferTest} can hold the order without JavaFX, on the model of {@link Closing}.
 * <p>
 * Newest first, one question each, until one is opened or the question is put off. A copy thrown
 * away is deleted and the next one is asked about, since a window holds one project and the next
 * copy may be the one that is wanted. A copy opened is the window's own from then on. One that will
 * not open is kept – the error has been said – and so is every copy not asked about, for the next
 * start.
 */
final class RecoveryOffer {

    private RecoveryOffer() {
    }

    /**
     * @param found the copies whose windows have gone, newest first, as {@link Recovery#orphans} has them
     * @param ask   the question about one of them
     * @param open  opens one in this window, and says whether it opened
     * @return the copy that was opened, or null when none was
     */
    static @Nullable Recovery.Orphan decide(final @NotNull List<Recovery.Orphan> found,
                                            final @NotNull Function<Recovery.Orphan, Dialogs.Recovered> ask,
                                            final @NotNull Predicate<Recovery.Orphan> open) {
        Recovery.Orphan opened = null;
        try {
            for (final Recovery.Orphan orphan : found) {
                final Dialogs.Recovered answer = ask.apply(orphan);
                if (answer == Dialogs.Recovered.DISCARD) {
                    orphan.discard();
                    continue;
                }
                if (answer == Dialogs.Recovered.OPEN && open.test(orphan)) {
                    orphan.take();
                    opened = orphan;
                }
                break;
            }
        } finally {
            // Whatever was not taken or thrown away waits for the next start, a question that
            // threw included; keeping one that was decided does nothing
            found.forEach(Recovery.Orphan::keep);
        }
        return opened;
    }
}
