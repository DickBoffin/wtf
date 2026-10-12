package net.trdlo.wtf.studio;

import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import org.jetbrains.annotations.NotNull;
import net.trdlo.wtf.tiles.ProgressModel;
import net.trdlo.wtf.tiles.ProgressSink;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link ProgressSink} that drives a bar and a label on the FX thread.
 * <p>
 * The arithmetic is not here: {@link ProgressModel} does the rate and the ETA, in an engine module,
 * so the window and the command line cannot disagree about how long is left. This class only
 * decides how often to repaint.
 */
public final class FxProgress implements ProgressSink {

    /** Repainting on every tile would be sixty updates a second of nothing new. */
    private static final long UI_INTERVAL_MILLIS = 250;

    private final ProgressBar bar;
    private final Label label;
    private final ProgressModel model = new ProgressModel();
    private final AtomicBoolean canceled = new AtomicBoolean();
    private final AtomicLong lastUiMillis = new AtomicLong();

    private final java.util.function.@org.jetbrains.annotations.Nullable Consumer<String> notes;

    public FxProgress(final @NotNull ProgressBar bar, final @NotNull Label label) {
        this(bar, label, null);
    }

    /**
     * @param notes where the per-item detail goes – the export panel's log. Null throws it away,
     *              which is what the status bar wants: a line per tile scrolling past at four
     *              hundred a second is not information.
     */
    public FxProgress(final @NotNull ProgressBar bar, final @NotNull Label label,
                      final java.util.function.@org.jetbrains.annotations.Nullable Consumer<String> notes) {
        this.bar = bar;
        this.label = label;
        this.notes = notes;
    }

    public @NotNull ProgressModel model() {
        return model;
    }

    public void cancel() {
        canceled.set(true);
    }

    @Override
    public void phase(final @NotNull String name, final long total, final @NotNull Unit unit) {
        model.phase(name, total, unit);
        Platform.runLater(() -> {
            bar.setProgress(total < 0 ? ProgressBar.INDETERMINATE_PROGRESS : 0);
            label.setText(ProgressText.phrase(name));
        });
    }

    @Override
    public void advance(final long delta) {
        model.advance(delta);
        final long now = System.currentTimeMillis();
        final long last = lastUiMillis.get();
        if (now - last < UI_INTERVAL_MILLIS || !lastUiMillis.compareAndSet(last, now)) {
            return;
        }
        final double fraction = model.fraction();
        final String text = ProgressText.line(model);
        Platform.runLater(() -> {
            bar.setProgress(fraction < 0 ? ProgressBar.INDETERMINATE_PROGRESS : fraction);
            label.setText(text);
        });
    }

    @Override
    public void note(final @NotNull String detail) {
        if (notes != null) {
            Platform.runLater(() -> notes.accept(detail));
        }
    }

    @Override
    public boolean isCanceled() {
        return canceled.get();
    }

    @Override
    public void finished(final @NotNull String summary) {
        Platform.runLater(() -> {
            bar.setProgress(1);
            label.setText(ProgressText.phrase(summary));
        });
    }
}
