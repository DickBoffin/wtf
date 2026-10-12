package net.trdlo.wtf.studio;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * The view's download while it runs: its thread, and the stop the window's close asks of it.
 * <p>
 * The thread is not a daemon, and {@code System.exit} would end it mid-tile with a {@code .part}
 * for the next start to clear. So the close cancels it and waits for the tile in
 * hand – without a question, since a view's download holds nothing a person could lose – for at
 * most {@link #STOP_WAIT}. Off the toolkit, as {@link Closing} is, so a close can be tested against
 * a real download.
 */
public final class ViewJob {

    private static final Logger log = LoggerFactory.getLogger(ViewJob.class);

    /**
     * How long the close waits for a download's tile in hand – this one's, and the export step's
     * download before its export ({@code ExportPanel.stopAndWait}). The close waits on the FX
     * thread, so the window is frozen for as long as it does, and a request in flight is not woken
     * by a cancel: a stalled connection could hold it for the client's 15 s connect and 30 s
     * request timeouts. The downloader's own waits – a backoff, a 429's quiet – look for the cancel
     * every 100 ms, so an ordinary tile lands well within this. What is left past it is what every
     * close left before, a {@code .part} the next start clears, and that is no loss.
     */
    public static final Duration STOP_WAIT = Duration.ofSeconds(2);

    private final Thread thread;
    private final Runnable cancel;

    /**
     * @param work   the download, run on a thread of its own
     * @param cancel what tells the download's progress sink it is canceled
     */
    ViewJob(final @NotNull String name, final @NotNull Runnable work, final @NotNull Runnable cancel) {
        this.thread = new Thread(work, name);
        this.cancel = cancel;
    }

    void start() {
        thread.start();
    }

    /**
     * Cancels the download and waits, for at most {@link #STOP_WAIT}, until its thread is done:
     * the downloader stops between one tile and the next, so the tile in hand is stored whole or
     * not at all, and no {@code .part} is left. A thread still running after the wait is left to
     * the process's exit.
     *
     * @return whether the download ended within the wait
     */
    boolean stopAndWait() {
        cancel.run();
        try {
            thread.join(STOP_WAIT);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            log.info("The view's download did not stop within {} ms; the close goes on without it",
                    STOP_WAIT.toMillis());
            return false;
        }
        return true;
    }
}
