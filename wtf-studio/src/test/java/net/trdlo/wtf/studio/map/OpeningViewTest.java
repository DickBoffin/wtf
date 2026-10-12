package net.trdlo.wtf.studio.map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The view the map opens on, without a canvas: a camera is arithmetic, and the canvas's part is
 * two calls on every resize – the new size, as a move that is not the user's, then the view again.
 */
class OpeningViewTest {

    private static void resize(final Camera camera, final OpeningView view, final double w, final double h) {
        view.layingOut(() -> camera.setViewSize(w, h));
        view.apply();
    }

    /** A view on Prague at zoom 8, opened before the canvas had a size and applied at its first. */
    private static OpeningView opened(final Camera camera) {
        final OpeningView view = new OpeningView(camera);
        view.install();
        view.open(() -> camera.centerOn(14.42, 50.08, 8));
        resize(camera, view, 1200, 800);
        return view;
    }

    @Test
    @DisplayName("a view opened before the canvas has a size is applied at the first size and again at every resize")
    void appliedAtEverySize() {
        final Camera camera = new Camera();
        final OpeningView view = new OpeningView(camera);
        view.install();
        final int[] applied = {0};
        view.open(() -> {
            applied[0]++;
            camera.centerOn(14.42, 50.08, 8);
        });
        assertEquals(0, applied[0], "nothing to fit it to until the canvas has a size");

        resize(camera, view, 400, 300);
        resize(camera, view, 1200, 800);

        assertEquals(2, applied[0]);
        assertTrue(view.isPending(), "and still the view to open on");
        assertEquals(14.42, camera.lonAt(600), 1e-6, "in the middle of the size the window settled at");
    }

    @Test
    @DisplayName("a pan across, a pan straight north and a zoom each take the view over")
    void anythingElseTakesTheView() {
        final List<Consumer<Camera>> moves = List.of(
                c -> c.panByPixels(50, 0),
                c -> c.panByPixels(0, 50),
                c -> c.zoomAt(600, 400, 1));
        for (final Consumer<Camera> move : moves) {
            final Camera camera = new Camera();
            final OpeningView view = opened(camera);
            move.accept(camera);
            assertFalse(view.isPending(), "the view is the user's from here on");
        }
    }

    @Test
    @DisplayName("its own moves and the re-centering of a resize keep it, a resize from anywhere else does not")
    void aResizeKeepsIt() {
        final Camera camera = new Camera();
        final OpeningView view = opened(camera);

        resize(camera, view, 1000, 700);
        assertTrue(view.isPending(), "a window being laid out is not somebody taking the view");

        camera.setViewSize(900, 600);
        assertFalse(view.isPending(), "the same move outside the laying-out is a move like any other");
    }

    @Test
    @DisplayName("a second view replaces the first, and one opened after the map was taken over opens again")
    void aSecondViewReplacesTheFirst() {
        final Camera camera = new Camera();
        final OpeningView view = new OpeningView(camera);
        view.install();
        final List<String> ran = new ArrayList<>();
        view.open(() -> ran.add("first"));
        view.open(() -> ran.add("second"));
        resize(camera, view, 800, 600);
        assertEquals(List.of("second"), ran);

        camera.panByPixels(-40, -40);       // away from the world's top-left corner, where it starts
        assertFalse(view.isPending());
        view.apply();
        assertEquals(List.of("second"), ran, "nothing left to apply");

        view.open(() -> ran.add("third"));
        resize(camera, view, 820, 610);
        assertEquals(List.of("second", "third"), ran);
    }
}
