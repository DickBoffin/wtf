package net.trdlo.wtf.studio;

import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.trdlo.wtf.studio.Headless.later;
import static net.trdlo.wtf.studio.Headless.onFx;
import static net.trdlo.wtf.studio.Headless.waitUntil;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A project handed over from the file manager while WTF is asking something – a dialog, an alert,
 * a file chooser – is claimed at once and acted on only once that has been answered, never inside
 * it, where the code that asked would go on with the answer over the project handed over.
 * <p>
 * On JavaFX's headless platform ({@link Headless}): a window, and over it an alert of the studio's,
 * shown and waited for as {@link Dialogs} shows each, and answered inside the nested event loop its
 * {@code showAndWait} runs – which is where the FX thread runs what is posted to it meanwhile, the
 * hand-over's claim among it. The window's question and its open are stand-ins that note when they
 * were asked; what they do is {@code MainWindow}'s, tested with it.
 */
class HandOverTest {

    /** Long enough for a few of the hand-over's looks at whether anything is still asked. */
    private static final long A_WHILE_MS = HandOver.RECHECK.toMillis() * 5;

    @BeforeAll
    static void toolkit() {
        Headless.start();
    }

    @Test
    @DisplayName("a hand-over while a modal dialog is up is claimed at once, and asked, opened and answered only after it closes")
    void aHandOverWaitsForTheDialog(@TempDir final Path dir) throws Exception {
        final Path project = dir.resolve("Alps.wtf");
        final Shown shown = new Shown();
        try {
            shown.window();
            final CompletableFuture<Optional<ButtonType>> question = shown.alert();
            final List<String> done = new CopyOnWriteArrayList<>();
            final HandOver handOver = new HandOver(shown.stage.get(),
                    noting(done, "asked about unsaved changes", question),
                    noting(done, "opened", question));
            final Claims claims = new Claims();
            try (final SingleInstance running = SingleInstance.serve(dir,
                    (file, answer) -> handOver.offer(file, claims.watching(answer)))) {
                assertNotNull(running);
                // The second WTF, which waits for the answer once the offer is claimed
                final CompletableFuture<SingleInstance.Outcome> second =
                        CompletableFuture.supplyAsync(() -> SingleInstance.handOver(dir, project));

                assertTrue(claims.begun.get(10, TimeUnit.SECONDS),
                        "claimed while the alert is up: the claim is the timed part of a hand-over");
                Thread.sleep(A_WHILE_MS);
                onFx(() -> null);
                assertEquals(List.of(), done, "nothing is asked or opened under the alert");
                assertFalse(second.isDone(), "the second WTF is told nothing before anything is done");
                assertTrue(onFx(() -> shown.alert.get().isShowing()));

                // The person answers the alert, and its showAndWait goes on with the answer
                onFx(() -> {
                    ((Button) shown.alert.get().getDialogPane().lookupButton(ButtonType.OK)).fire();
                    return null;
                });
                assertEquals(SingleInstance.Outcome.TAKEN, second.get(10, TimeUnit.SECONDS));
                assertEquals(List.of("asked about unsaved changes after the alert", "opened after the alert"), done);
                assertEquals(Optional.of(ButtonType.OK), question.get(10, TimeUnit.SECONDS),
                        "the alert's own answer reached the code that asked it");
            } finally {
                claims.unblock();
            }
        } finally {
            shown.close();
        }
    }

    /**
     * The close's own question, answered Don't save: the alert goes and the window with it. The
     * claimed hand-over is then neither asked, nor opened, nor answered – no answer at all, so that
     * the second WTF, which hears the connection close with this process, opens the file itself.
     */
    @Test
    @DisplayName("a window that closes while a hand-over waits asks nothing about it, opens nothing and answers nothing")
    void aWindowClosedMeanwhileActsOnNothing(@TempDir final Path dir) throws Exception {
        final Shown shown = new Shown();
        try {
            shown.window();
            final CompletableFuture<Optional<ButtonType>> question = shown.alert();
            final List<String> done = new CopyOnWriteArrayList<>();
            final HandOver handOver = new HandOver(shown.stage.get(),
                    noting(done, "asked about unsaved changes", question),
                    noting(done, "opened", question));
            final Claims claims = new Claims();
            handOver.offer(dir.resolve("Alps.wtf"), claims.watching(null));
            assertTrue(claims.begun.get(10, TimeUnit.SECONDS));

            onFx(() -> {
                ((Button) shown.alert.get().getDialogPane().lookupButton(ButtonType.OK)).fire();
                shown.stage.get().hide();
                return null;
            });
            question.get(10, TimeUnit.SECONDS);
            Thread.sleep(A_WHILE_MS);
            onFx(() -> null);
            assertEquals(List.of(), done);
            assertFalse(claims.reported.isDone(), () -> "answered " + claims.reported.getNow(null));
        } finally {
            shown.close();
        }
    }

    @Test
    @DisplayName("a file chooser counts as a modal window while it is open, and not once it has answered or thrown")
    void aChooserCountsWhileItIsOpen() throws Exception {
        assertFalse(onFx(Modal::anyOpen), "nothing is open");
        assertTrue(onFx(() -> Modal.choosing(Modal::anyOpen)), "a chooser is");
        assertFalse(onFx(Modal::anyOpen), "it answered");
        assertThrows(IllegalStateException.class, () -> onFx(() -> Modal.choosing(() -> {
            throw new IllegalStateException("no chooser here");
        })));
        assertFalse(onFx(Modal::anyOpen), "it threw");
    }

    /**
     * A chooser is the system's window, which JavaFX neither lists nor counts: shown past
     * {@link Modal}, it is a question a hand-over would not wait for.
     */
    @Test
    @DisplayName("every file and folder chooser of the studio's is shown through Modal, which counts it")
    void everyChooserIsShownThroughModal() throws IOException {
        final Pattern shows = Pattern.compile("\\.show(?:Open|Save|OpenMultiple)?Dialog\\(");
        final Path root = Path.of("src", "main", "java");
        assertTrue(Files.isDirectory(root), () -> root.toAbsolutePath() + " is not where the tests run");
        final List<String> past = new ArrayList<>();
        int counted = 0;
        try (final var files = Files.walk(root)) {
            for (final Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                final Matcher m = shows.matcher(Files.readString(p));
                while (m.find()) {
                    if (p.getFileName().toString().equals("Modal.java")) {
                        counted++;
                    } else {
                        past.add(p.getFileName() + ": " + m.group());
                    }
                }
            }
        }
        assertEquals(3, counted, "the scan finds Modal's own open, save and folder");
        assertTrue(past.isEmpty(), () -> "choosers shown past Modal: " + past);
    }

    // -------------------------------------------------------------------------------------------

    /** A stand-in for the window's question or open: notes what it did and whether the alert's wait had returned. */
    private static @NotNull Predicate<Path> noting(final @NotNull List<String> done, final @NotNull String what,
                                                   final @NotNull CompletableFuture<?> question) {
        return file -> {
            done.add(what + (question.isDone() ? " after the alert" : " under the alert"));
            return true;
        };
    }

    /** The window, and the alert over it; hides whatever a failure left open, so no loop is left running. */
    private static final class Shown {
        final AtomicReference<Stage> stage = new AtomicReference<>();
        final AtomicReference<Alert> alert = new AtomicReference<>();
        private @Nullable CompletableFuture<?> waiting;

        void window() throws Exception {
            stage.set(onFx(() -> Headless.show(new StackPane(), 400, 300)));
        }

        /** The alert, shown as {@link Dialogs} shows one and waited for: its answer, once it closes. */
        @NotNull CompletableFuture<Optional<ButtonType>> alert() throws Exception {
            final CompletableFuture<Optional<ButtonType>> answered = later(() -> {
                final Alert a = Dialogs.alert(stage.get(), Alert.AlertType.CONFIRMATION, "A country to pick");
                alert.set(a);
                return a.showAndWait();
            });
            waiting = answered;
            waitUntil("the alert to show", () -> alert.get() != null && alert.get().isShowing());
            return answered;
        }

        void close() throws Exception {
            onFx(() -> {
                for (final Window open : List.copyOf(Window.getWindows())) {
                    if (open != stage.get()) {
                        open.hide();
                    }
                }
                return null;
            });
            if (waiting != null) {
                waiting.exceptionally(ex -> null).get(60, TimeUnit.SECONDS);
            }
            onFx(() -> {
                if (stage.get() != null) {
                    stage.get().hide();
                }
                return null;
            });
        }
    }

    /** An answer that notes the claim and the report, passing both on to the listener's when there is one. */
    private static final class Claims {
        final CompletableFuture<Boolean> begun = new CompletableFuture<>();
        final CompletableFuture<SingleInstance.Result> reported = new CompletableFuture<>();
        /** Set on the listener's thread, read on the test's. */
        private volatile SingleInstance.@Nullable Answer listener;

        @NotNull SingleInstance.Answer watching(final SingleInstance.@Nullable Answer listener) {
            this.listener = listener;
            return new SingleInstance.Answer() {
                @Override
                public boolean begin() {
                    final boolean claimed = listener == null || listener.begin();
                    begun.complete(claimed);
                    return claimed;
                }

                @Override
                public void report(final SingleInstance.@NotNull Result result) {
                    reported.complete(result);
                    if (listener != null) {
                        listener.report(result);
                    }
                }
            };
        }

        /** A test that failed with the second WTF still waiting lets it go. */
        void unblock() {
            final SingleInstance.Answer waiting = listener;
            if (waiting != null && !reported.isDone()) {
                waiting.report(SingleInstance.Result.FAILED);
            }
        }
    }
}
