package net.trdlo.wtf.studio;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import net.trdlo.wtf.tiles.Provider;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * JavaFX's headless platform, for the studio's tests that need the toolkit: no display, and no
 * window anybody sees.
 * <p>
 * Started once for the test JVM, however many classes ask – Surefire runs them all in one – with
 * {@code glass.platform=Headless} and {@code prism.order=sw} set before anything of the toolkit
 * loads. Then the studio's dark theme, as the application applies it, and a line of text drawn
 * with an effect: JavaFX unpacks its native libraries into {@code user.home} the first time it
 * draws either, and a test that has moved the home to a temporary folder would get them there,
 * where Windows does not let the folder be deleted while they are loaded.
 * <p>
 * A platform that will not start fails every test that asks for it, with the reason, and never
 * skips one: a test skipped on a machine without the headless platform would read as a pass.
 * <p>
 * Its one screen is 1000 by 1000 pixels, and a window that reaches below it throws on the FX
 * thread as it is drawn ({@code HeadlessWindow.blit}), so the tests keep their windows inside it.
 */
public final class Headless {

    /** How long a call on the FX thread may take, and how long a wait for something there lasts. */
    private static final Duration CALL = Duration.ofSeconds(60);
    private static final Duration WAIT = Duration.ofSeconds(10);

    private static boolean started;
    private static Throwable failed;

    private Headless() {
    }

    /** Something to do on the FX thread, which may throw. */
    public interface FxCall<T> {
        T call() throws Exception;
    }

    /** Starts the platform, once; fails with the reason, now and for every later class, if it cannot. */
    public static synchronized void start() {
        if (failed != null) {
            throw new AssertionError("JavaFX's headless platform did not start", failed);
        }
        if (started) {
            return;
        }
        try {
            System.setProperty("glass.platform", "Headless");
            System.setProperty("prism.order", "sw");
            final CompletableFuture<Void> up = new CompletableFuture<>();
            try {
                Platform.startup(() -> up.complete(null));
            } catch (final IllegalStateException alreadyStarted) {
                up.complete(null);      // by something else; the call below says whether it still runs
            }
            up.get(30, TimeUnit.SECONDS);
            Platform.setImplicitExit(false);
            onFx(() -> {
                WtfApp.applyTheme(true);
                final Label text = new Label("x");
                text.setEffect(new ColorAdjust());
                new Scene(new StackPane(text)).snapshot(null);
                return null;
            });
            started = true;
        } catch (final Exception | Error ex) {
            failed = ex;
            throw new AssertionError("JavaFX's headless platform did not start", ex);
        }
    }

    /** Runs a call on the FX thread and waits for it: what it returns, or what it threw. */
    public static <T> T onFx(final FxCall<T> call) throws Exception {
        try {
            return later(call).get(CALL.toSeconds(), TimeUnit.SECONDS);
        } catch (final ExecutionException ex) {
            if (ex.getCause() instanceof final Exception thrown) {
                throw thrown;
            }
            if (ex.getCause() instanceof final Error thrown) {
                throw thrown;
            }
            throw ex;
        }
    }

    /**
     * Posts a call to the FX thread without waiting for it – for one that opens a modal window and
     * returns only once the window has closed. What is posted after it runs inside the nested event
     * loop the window's {@code showAndWait} runs, so that is where a test answers the window.
     */
    public static <T> CompletableFuture<T> later(final FxCall<T> call) {
        final CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(call.call());
            } catch (final Exception | Error ex) {
                result.completeExceptionally(ex);
            }
        });
        return result;
    }

    /** Asks on the FX thread until there is an answer, and returns it; fails after {@link #WAIT}. */
    public static <T> T waitFor(final String what, final FxCall<Optional<T>> answer) throws Exception {
        final long end = System.nanoTime() + WAIT.toNanos();
        while (true) {
            final Optional<T> got = onFx(answer);
            if (got.isPresent()) {
                return got.get();
            }
            if (System.nanoTime() > end) {
                throw new AssertionError("waited " + WAIT.toSeconds() + " s for " + what);
            }
            Thread.sleep(20);
        }
    }

    /** Asks on the FX thread until the answer is yes; fails after {@link #WAIT}. */
    public static void waitUntil(final String what, final FxCall<Boolean> done) throws Exception {
        waitFor(what, () -> Boolean.TRUE.equals(done.call()) ? Optional.of(Boolean.TRUE) : Optional.empty());
    }

    /**
     * A stage showing {@code root}, {@code width} by {@code height}, at the screen's corner: inside
     * the headless screen, which a window reaching below it overruns. Called on the FX thread.
     */
    public static Stage show(final Parent root, final double width, final double height) {
        final Stage stage = new Stage();
        stage.setScene(new Scene(root, width, height));
        stage.setX(0);
        stage.setY(0);
        stage.show();
        return stage;
    }

    /**
     * The first node at or under {@code root}, a control's own parts included, of a type and with
     * what a person would know it by – its text, its tooltip. Called on the FX thread.
     */
    public static <T extends Node> Optional<T> find(final Node root, final Class<T> type,
                                                    final Predicate<? super T> which) {
        if (type.isInstance(root) && which.test(type.cast(root))) {
            return Optional.of(type.cast(root));
        }
        if (root instanceof final Parent parent) {
            for (final Node child : List.copyOf(parent.getChildrenUnmodifiable())) {
                final Optional<T> found = find(child, type, which);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * A provider at {@code 127.0.0.1:1}, where nothing listens, with one map and no key: what a test's
     * window opens on, so that whatever it might fetch goes nowhere beyond this machine.
     */
    public static Provider nowhere() {
        return new Provider("wtf-test-nowhere", "Nowhere", "http://127.0.0.1:1/{z}/{x}/{y}.png", null, 0, 19,
                null, List.of(new Provider.Theme("map", "Map", null, null, null, null, false, null)),
                null, "test", null, null, null, true, null, null, null, null);
    }
}
