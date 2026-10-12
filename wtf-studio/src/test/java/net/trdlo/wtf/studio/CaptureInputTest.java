package net.trdlo.wtf.studio;

import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static net.trdlo.wtf.studio.Headless.onFx;
import static org.junit.jupiter.api.Assertions.*;

/**
 * In a capture run no window hears the pointer or the keys, whether the pointer rests over the
 * window when it opens or crosses it later – and a window opened without it hears both, as every
 * ordinary start does.
 * <p>
 * On JavaFX's headless platform ({@link Headless}), whose robot moves a pointer of its own and
 * sends it to the topmost window under it. A window there is entered as it opens, at its corner,
 * where the button is; so a window open before the refusal may be hovered from the first, and only
 * a pointer that reaches it afterwards can show that it no longer takes one.
 */
class CaptureInputTest {

    @BeforeAll
    static void toolkit() {
        Headless.start();
    }

    /** A window with one button, and what the button heard. */
    private record Window(Stage stage, Button button, AtomicInteger moves, AtomicInteger keys) { }

    private static Window open(final double x) {
        final Button button = new Button("Export");
        button.setPrefSize(300, 200);
        final AtomicInteger moves = new AtomicInteger(), keys = new AtomicInteger();
        button.setOnMouseMoved(e -> moves.incrementAndGet());
        button.setOnKeyPressed(e -> keys.incrementAndGet());
        final Stage stage = new Stage();
        stage.setScene(new Scene(new StackPane(button), 300, 200));
        stage.setX(x);
        stage.setY(100);
        stage.show();
        button.requestFocus();
        return new Window(stage, button, moves, keys);
    }

    /** The pointer onto the middle of the button, moved there in steps as a hand would. */
    private static void pointAt(final Robot robot, final Button button) {
        final Point2D middle = button.localToScreen(150, 100);
        for (int i = 3; i >= 0; i--) {
            robot.mouseMove(middle.getX() + i * 7, middle.getY() + i * 5);
        }
    }

    @Test
    @DisplayName("an ordinary window hovers under the pointer and hears it and the keys")
    void anOrdinaryWindowTakesTheMouse() throws Exception {
        final Window w = onFx(() -> open(100));
        try {
            onFx(() -> {
                final Robot robot = new Robot();
                pointAt(robot, w.button());
                robot.keyType(KeyCode.A);
                return null;
            });
            onFx(() -> null);       // the events posted above, delivered
            assertTrue(onFx(() -> w.button().isHover()), "the button lights up under the pointer");
            assertTrue(w.moves().get() > 0, "the button hears the pointer move");
            assertTrue(w.keys().get() > 0, "the button hears the keys");
        } finally {
            onFx(() -> {
                w.stage().close();
                return null;
            });
        }
    }

    @Test
    @DisplayName("in a capture run a window takes neither the pointer nor the keys, open or opening")
    void aCaptureWindowTakesNoMouse() throws Exception {
        // Side by side, so the pointer moved over each reaches that window and not the other
        final Window before = onFx(() -> open(100));
        final Runnable undo = onFx(BatchMode::refuseInput);
        final Window after = onFx(() -> open(450));
        try {
            for (final Window w : new Window[]{before, after}) {
                // The pointer resting over the window as it opens, then moving across it
                onFx(() -> {
                    final Robot robot = new Robot();
                    pointAt(robot, w.button());
                    robot.keyType(KeyCode.A);
                    return null;
                });
                onFx(() -> null);
                assertFalse(onFx(() -> w.button().isHover()), "no hover reaches the picture");
                assertEquals(0, w.moves().get(), "no pointer position reaches the window");
                assertEquals(0, w.keys().get(), "no key reaches the window");
            }
            // A root put in later is as deaf as the first
            onFx(() -> {
                after.stage().getScene().setRoot(new StackPane(new Button("Other")));
                return null;
            });
            assertTrue(onFx(() -> after.stage().getScene().getRoot().isMouseTransparent()));
        } finally {
            onFx(() -> {
                undo.run();
                before.stage().close();
                after.stage().close();
                return null;
            });
        }
    }
}
