package net.trdlo.wtf.tiles;

import org.jetbrains.annotations.NotNull;

import java.io.PrintStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link ProgressSink} for a terminal. Each phase is a line as it starts, with its total; within
 * a phase the count is reported at most once every {@value #REPORT_INTERVAL_MILLIS} ms, so a quick
 * phase is that one line and a run that seems hung visibly is not. The summary follows once
 * anything was done or reported.
 * <p>
 * The phase and the ETA come from {@link ProgressModel}, so the console and the GUI cannot disagree.
 */
public final class ConsoleProgress implements ProgressSink {

    public static final long REPORT_INTERVAL_MILLIS = 5_000;

    private final PrintStream out;
    private final String prefix;
    private final ProgressModel model = new ProgressModel();
    private final AtomicBoolean canceled = new AtomicBoolean();

    private long lastReportMillis;
    private boolean reportedAnything;

    public ConsoleProgress(final @NotNull String prefix) {
        this(prefix, System.out);
    }

    public ConsoleProgress(final @NotNull String prefix, final @NotNull PrintStream out) {
        this.prefix = prefix;
        this.out = out;
        this.lastReportMillis = System.currentTimeMillis();
    }

    public @NotNull ProgressModel model() {
        return model;
    }

    /** Lets a shutdown hook or a signal handler stop a long job cleanly. */
    public void cancel() {
        canceled.set(true);
    }

    @Override
    public void phase(final @NotNull String name, final long total, final @NotNull Unit unit) {
        model.phase(name, total, unit);
        lastReportMillis = System.currentTimeMillis();
        if (total >= 0) {
            out.printf(Locale.ROOT, "%s %s: %,d %s%n", prefix, name, total, unit.name().toLowerCase(Locale.ROOT));
        } else {
            out.printf(Locale.ROOT, "%s %s…%n", prefix, name);
        }
    }

    @Override
    public void advance(final long delta) {
        model.advance(delta);
        final long now = System.currentTimeMillis();
        synchronized (this) {
            if (now - lastReportMillis < REPORT_INTERVAL_MILLIS) {
                return;
            }
            lastReportMillis = now;
            reportedAnything = true;
        }
        out.println(prefix + " " + model.describe());
    }

    @Override
    public void note(final @NotNull String detail) {
        // Deliberately dropped: one line per tile would be 47 000 lines. The GUI shows these.
    }

    @Override
    public boolean isCanceled() {
        return canceled.get();
    }

    @Override
    public void finished(final @NotNull String summary) {
        if (reportedAnything || model.done() > 0) {
            out.println(prefix + " " + summary);
        }
    }
}
