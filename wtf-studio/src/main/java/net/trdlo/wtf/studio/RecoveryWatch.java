package net.trdlo.wtf.studio;

import javafx.application.Platform;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.trdlo.wtf.compose.WtfFile;
import net.trdlo.wtf.studio.project.ProjectIo;
import net.trdlo.wtf.studio.project.ProjectModel;

import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * When this window's recovery copy of unsaved work is written, and when it goes; the copy itself
 * is {@link Recovery}'s.
 * <p>
 * <b>A copy while the project is unsaved, and none otherwise.</b> Two seconds after the project last
 * changed – and thirty at the most after the first change the copy does not have, so somebody who
 * never pauses still has one – the project is made into a file on the FX thread, as a save makes it,
 * and written on the recovery thread.
 * <p>
 * <b>Never while an area is on its way.</b> {@link ProjectIo#toFile} would work it out on the thread
 * that calls it, which under "the outline itself" is the whole subject joined on the FX thread; and a
 * copy of the area from before would not reproduce its own area, and be refused when it is opened.
 * The area's landing asks again. So the FX thread only turns the model into a {@link WtfFile} – a few
 * milliseconds for North America, the largest outline in the library – and the JSON and the disk are
 * the recovery thread's.
 * <p>
 * The copy goes as soon as the project is no longer unsaved – saved, undone back to its save,
 * replaced by New, Open or a hand-over, each after its own question – and with a close that went
 * ahead ({@link #discard}). A window that goes without either, a session ending ({@link #stop}),
 * leaves its copy for the next start.
 * <p>
 * Off the toolkit but for {@link #forWindow}: the wait, the thread and the clock are handed in, so a
 * test holds the order of a change, the interval and a save without them, as it holds
 * {@link FetchAsIPan}'s.
 */
final class RecoveryWatch {

    private static final Logger log = LoggerFactory.getLogger(RecoveryWatch.class);

    /** How long the project has to be left alone before it is copied. */
    static final long QUIET_MILLIS = 2_000;

    /** How long a change may wait for its copy, however the project goes on changing. */
    static final long LONGEST_MILLIS = 30_000;

    /** Runs a task on the FX thread once some time has passed. */
    @FunctionalInterface
    interface Wait {
        /** @return what calls the task off, when it has not run yet */
        @NotNull Runnable after(long millis, @NotNull Runnable onFx);
    }

    private final ProjectModel model;
    private final Recovery copies;
    private final Wait wait;
    /** Where the copy is written and deleted: one thing at a time, in the order asked. */
    private final Executor io;
    private final LongSupplier millis;
    /** The recovery thread {@link #forWindow} made, ended by {@link #stop}; null in a test. */
    private final @Nullable ScheduledThreadPoolExecutor thread;

    private @Nullable Runnable callOff;
    /** When the first change the copy does not have was made, or -1 when it has them all. */
    private long firstUnwritten = -1;
    /** Moved on by every change, so that a wait which runs out for an older one does nothing. */
    private int generation;
    /** Set once the close has been answered or the window is going: nothing more is written. */
    private boolean done;
    private boolean stopped;

    RecoveryWatch(final @NotNull ProjectModel model, final @NotNull Recovery copies, final @NotNull Wait wait,
                  final @NotNull Executor io, final @NotNull LongSupplier millis) {
        this(model, copies, wait, io, millis, null);
    }

    private RecoveryWatch(final @NotNull ProjectModel model, final @NotNull Recovery copies,
                          final @NotNull Wait wait, final @NotNull Executor io,
                          final @NotNull LongSupplier millis, final @Nullable ScheduledThreadPoolExecutor thread) {
        this.model = model;
        this.copies = copies;
        this.wait = wait;
        this.io = io;
        this.millis = millis;
        this.thread = thread;
    }

    /**
     * The watch a window keeps: the copy written on a thread of its own, the waits kept there too
     * and landing on the FX thread, where the model is.
     */
    static @NotNull RecoveryWatch forWindow(final @NotNull ProjectModel model, final @NotNull Recovery copies) {
        final ScheduledThreadPoolExecutor thread = new ScheduledThreadPoolExecutor(1, r -> {
            final Thread t = new Thread(r, "recovery");
            t.setDaemon(true);
            return t;
        });
        // A wait that has not run out when the window goes is called off, not run
        thread.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        final Wait onTheThread = (after, onFx) -> {
            final ScheduledFuture<?> waiting =
                    thread.schedule(() -> Platform.runLater(onFx), after, TimeUnit.MILLISECONDS);
            return () -> waiting.cancel(false);
        };
        return new RecoveryWatch(model, copies, onTheThread, thread, () -> System.nanoTime() / 1_000_000, thread);
    }

    /** Starts watching. A project that is unsaved already – a copy just opened – is copied in its turn. */
    void start() {
        model.revisionProperty().addListener((o, was, is) -> changed());
        model.dirtyProperty().addListener((o, was, unsaved) -> {
            if (unsaved) {
                changed();
            } else {
                saved();
            }
        });
        model.areaPendingProperty().addListener((o, was, pending) -> {
            if (!pending) {
                changed();
            }
        });
        changed();
    }

    /** Something changed: while the project is unsaved, a copy is due once it has been left alone. */
    private void changed() {
        if (done || !model.dirtyProperty().get()) {
            return;
        }
        final long now = millis.getAsLong();
        if (firstUnwritten < 0) {
            firstUnwritten = now;
        }
        callOff();
        final int mine = ++generation;
        final long due = Math.max(0, Math.min(QUIET_MILLIS, firstUnwritten + LONGEST_MILLIS - now));
        callOff = wait.after(due, () -> {
            if (!done && mine == generation) {
                callOff = null;
                copy();
            }
        });
    }

    /** The copy, made here and written there – unless an area is on its way, whose landing asks again. */
    private void copy() {
        if (!model.dirtyProperty().get() || model.areaPendingProperty().get()) {
            return;
        }
        final WtfFile file;
        try {
            file = ProjectIo.toFile(model);
        } catch (final RuntimeException ex) {
            // A project with no provider, which no copy could be opened on either
            log.debug("No recovery copy of this project: {}", ex.toString());
            return;
        }
        firstUnwritten = -1;
        final Path of = model.fileProperty().get();
        io.execute(() -> copies.write(file, of));
    }

    /** The project is not unsaved any more: the copy goes. */
    private void saved() {
        if (done) {
            return;
        }
        callOff();
        generation++;
        firstUnwritten = -1;
        io.execute(copies::delete);
    }

    /**
     * The close went ahead, its question answered: what the copy held has been saved or thrown away,
     * so it goes, and nothing more is written.
     */
    void discard() {
        if (done) {
            return;
        }
        done = true;
        callOff();
        generation++;
        io.execute(copies::delete);
    }

    /**
     * The window is going. What changed since the last copy is copied first, the recovery thread ends
     * once it has written it, and a copy the project still needs stays for the next start, its lock
     * let go – which is what a crash leaves too.
     */
    void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        callOff();
        if (!done && firstUnwritten >= 0) {
            copy();
        }
        done = true;
        generation++;
        io.execute(copies::close);
        if (thread != null) {
            thread.shutdown();
            try {
                if (!thread.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("The recovery copy was still being written as the window went");
                }
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void callOff() {
        final Runnable waiting = callOff;
        callOff = null;
        if (waiting != null) {
            waiting.run();
        }
    }
}
