package net.trdlo.wtf.studio.map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The view the map opens on: applied once the canvas has a size, applied again at every resize,
 * and given up the moment anything else moves the camera. {@link TileCanvas#openOn} says why it has
 * to be all three.
 */
final class OpeningView {

    private final Camera camera;

    /** The opening view, until the canvas has a size and until the user moves the map; see {@link #open}. */
    private @Nullable Runnable pendingView;
    /** True while the opening view is being applied, so its own camera moves do not cancel it. */
    private boolean applyingOpeningView;

    OpeningView(final @NotNull Camera camera) {
        this.camera = camera;
    }

    /** The listener that gives the view up, on the camera: installed by the canvas. */
    void install() {
        // Whoever moves the camera owns the view. Watching the camera rather than the gestures
        // catches the ones that are not gestures at all: opening a project, framing an area.
        final javafx.beans.value.ChangeListener<Number> notOurs = (o, a, b) -> {
            if (!applyingOpeningView) {
                pendingView = null;
            }
        };
        camera.originXProperty().addListener(notOurs);
        camera.originYProperty().addListener(notOurs);     // a pan straight north is a pan too
        camera.pixelsPerUnitProperty().addListener(notOurs);
    }

    /** A view to open on, in place of one still waiting. */
    void open(final @NotNull Runnable action) {
        pendingView = action;
    }

    /** Applies the view, as long as it is still the one to open on; its own camera moves keep it. */
    void apply() {
        if (pendingView == null) {
            return;
        }
        applyingOpeningView = true;
        try {
            pendingView.run();
        } finally {
            applyingOpeningView = false;
        }
    }

    /** A camera move that is not somebody taking the view – the window being laid out. */
    void layingOut(final @NotNull Runnable move) {
        applyingOpeningView = true;
        try {
            move.run();
        } finally {
            applyingOpeningView = false;
        }
    }

    /** Whether there is still a view to open on, which the next resize applies again. */
    boolean isPending() {
        return pendingView != null;
    }
}
