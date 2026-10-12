package net.trdlo.wtf.tiles;

import org.jetbrains.annotations.NotNull;

/**
 * Where a long operation says how it is getting on. Deliberately tiny, deliberately in the tile
 * module: {@code wtf-compose} needs the same thing, and the GUI implements it once.
 * <p>
 * Phases exist because the long operations have several – a download is one phase, an export is
 * "placing tiles" then "writing the document", per sheet. Reporting them as a single number makes a
 * bar that sticks at 95 %, which is the thing everybody hates about progress bars.
 * <p>
 * {@link #advance} may be called from any thread. Implementations must be cheap: a download calls
 * it tens of times a second.
 */
public interface ProgressSink {

    /** ROWS is for the raster exporters, which advance a band of pixel rows at a time. */
    enum Unit { TILES, BYTES, SHEETS, ROWS }

    /**
     * Begins a phase, ending any previous one.
     *
     * @param total the number of units that make 100 %, or a negative number when it is unknown
     */
    void phase(@NotNull String name, long total, @NotNull Unit unit);

    void advance(long delta);

    /** A line of detail: the tile being fetched, the sheet being written. Cheap, and droppable. */
    void note(@NotNull String detail);

    /** Checked between units of work. An operation that cannot be canceled will be killed instead. */
    boolean isCanceled();

    void finished(@NotNull String summary);

    /** Discards everything. For tests and for callers that genuinely do not care. */
    ProgressSink IGNORE = new ProgressSink() {
        @Override public void phase(final @NotNull String name, final long total, final @NotNull Unit unit) { }
        @Override public void advance(final long delta) { }
        @Override public void note(final @NotNull String detail) { }
        @Override public boolean isCanceled() { return false; }
        @Override public void finished(final @NotNull String summary) { }
    };
}
