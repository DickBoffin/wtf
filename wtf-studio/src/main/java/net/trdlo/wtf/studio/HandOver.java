package net.trdlo.wtf.studio;

import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * A project handed over from the file manager ({@link SingleInstance}), as the window takes it:
 * claimed at once, then the unsaved-changes question naming the file and the open – only once WTF
 * is asking nothing else ({@link Modal}).
 * <p>
 * The FX thread runs what is posted to it inside the event loop of a dialog and of a file chooser
 * too, so a hand-over arrives in the middle of whatever is being asked. Acted on there, it would
 * open under the question, and the code that asked would then go on with the answer over the
 * project handed over: the close's Don't save would close the window on it, a country picked would
 * go into its subject, an export asked to save first would go on with the old project's job. So
 * only the claim comes at once – the one part of a hand-over that is timed, which tells the second
 * WTF within its few seconds that this one is answering – and the rest waits for as long as the
 * person takes, looked at again every {@link #RECHECK}: once an offer is claimed, the second WTF
 * waits for the answer without a limit. The window comes to the front with the claim, with what it
 * is asking above it, so whoever opened the file sees what stands in its way.
 * <p>
 * Hand-overs are taken in the order they came, one at a time: the second waits for the first's
 * question too. One the window closes before getting to is neither acted on nor answered, since
 * WTF is on its way out. The connection closes with the process, once the window has kept its
 * state, and the second WTF, hearing nobody there, opens the file in a window of its own.
 */
final class HandOver implements SingleInstance.Offer {

    /** How soon a hand-over waiting for an answer looks again: a moment after the dialog closes. */
    static final Duration RECHECK = Duration.ofMillis(200);

    private final Stage stage;
    private final Predicate<Path> confirmDiscard;
    private final Predicate<Path> open;

    /** Claimed and not yet acted on, oldest first; the FX thread's alone, as is the flag below. */
    private final Deque<Claimed> waiting = new ArrayDeque<>();
    private boolean recheckDue;

    private record Claimed(@NotNull Path project, SingleInstance.@NotNull Answer answer) { }

    /**
     * @param stage          the window, brought to the front for each hand-over
     * @param confirmDiscard the unsaved-changes question, naming the file that would open
     * @param open           opens the file, saying why when it cannot; asked only once the question allows
     */
    HandOver(final @NotNull Stage stage, final @NotNull Predicate<Path> confirmDiscard,
             final @NotNull Predicate<Path> open) {
        this.stage = stage;
        this.confirmDiscard = confirmDiscard;
        this.open = open;
    }

    /** On the listener's thread: everything happens on the FX thread. */
    @Override
    public void offer(final @NotNull Path project, final SingleInstance.@NotNull Answer answer) {
        Platform.runLater(() -> {
            if (!answer.begin()) {
                return;             // the listener gave up on this offer before the FX thread got to it
            }
            raise();
            waiting.add(new Claimed(project, answer));
            next();
        });
    }

    /** Acts on the oldest hand-over when nothing is being asked, and looks again later when something is. */
    private void next() {
        if (waiting.isEmpty()) {
            return;
        }
        if (Modal.anyOpen()) {
            recheck();
            return;
        }
        final Claimed first = waiting.removeFirst();
        try {
            act(first);
        } finally {
            if (!waiting.isEmpty()) {
                Platform.runLater(this::next);
            }
        }
    }

    /**
     * Posted after {@link #RECHECK}, not run in a pause's handler: an animation is no place for the
     * question, which waits for an answer.
     */
    private void recheck() {
        if (recheckDue) {
            return;
        }
        recheckDue = true;
        CompletableFuture.delayedExecutor(RECHECK.toMillis(), TimeUnit.MILLISECONDS, Platform::runLater).execute(() -> {
            recheckDue = false;
            next();
        });
    }

    private void act(final @NotNull Claimed claimed) {
        if (!stage.isShowing()) {
            return;                 // closed meanwhile: no answer, as the class comment says
        }
        // An answer goes back whatever happens here: the other WTF waits for it without a limit
        SingleInstance.Result result = SingleInstance.Result.FAILED;
        try {
            raise();
            // The unsaved-changes question first, as for New and Open, asked with the file so that
            // it names what opens once it is answered: a Cancel declines, and the file is never
            // offered to the open at all. Only then may opening the file fail.
            if (!confirmDiscard.test(claimed.project())) {
                result = SingleInstance.Result.DECLINED;
            } else if (open.test(claimed.project())) {
                result = SingleInstance.Result.OPENED;
            }
        } finally {
            claimed.answer().report(result);
        }
    }

    /**
     * The window to the front, and what it is asking above it, in the order it was asked: a dialog
     * it owns comes along, but one with no owner – the alert for an error nothing caught – would
     * stay behind it, and the hand-over would wait for it unseen.
     */
    private void raise() {
        if (!stage.isShowing()) {
            return;
        }
        stage.setIconified(false);
        stage.toFront();
        for (final Window window : List.copyOf(Window.getWindows())) {
            if (window != stage && window instanceof final Stage asking && Modal.isModal(asking)) {
                asking.toFront();
            }
        }
    }
}
