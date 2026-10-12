package net.trdlo.wtf.studio;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * An unattended run: start, let the canvas settle, write a screenshot, quit.
 * <p>
 * This is what makes a windowed application workable for somebody – or something – that is not
 * sitting in front of it. Three properties matter:
 * <ul>
 *   <li><b>No dialog may wait for a click.</b> {@link Dialogs#setBatchMode} turns them into console
 *       lines, because one unexpected alert otherwise blocks the FX thread forever and that single
 *       failure is what makes automated runs useless.</li>
 *   <li><b>Let the canvas settle first.</b> The viewport is not known until layout has run, and the
 *       tiles are not drawn until their decodes land.</li>
 *   <li><b>Exit non-zero when nothing was written</b>, so a caller can tell success from a hang
 *       without parsing the output.</li>
 * </ul>
 */
public final class BatchMode {

    private static final Logger log = LoggerFactory.getLogger(BatchMode.class);

    public static final String ARG = "--screenshot";

    /** One picture per step, into a directory. The M4-sized version of the same idea. */
    public static final String ARG_STEPS = "--screenshot-steps";

    /** The splash, caught mid-sweep. It is on screen for three seconds and then gone. */
    public static final String ARG_SPLASH = "--screenshot-splash";

    /**
     * The 1:1 preview, which is a window of its own and so is in no picture of the main window.
     * <p>
     * {@code --screenshot-preview=temp/p.png}, and {@code =temp/p.png@calibrate} for the state
     * with the card and the slider showing, so that it too can be looked at by eye.
     */
    public static final String ARG_PREVIEW = "--screenshot-preview";

    /**
     * {@code --screenshot-outlines=temp/o.png#america}: the outline picker, searching for the
     * words after the {@code #}. A {@code #} rather than the {@code @} the other flags use,
     * because the words after an {@code @} are modifiers, and {@code outline} is one of them.
     */
    public static final String ARG_OUTLINES = "--screenshot-outlines";

    /** Frames to let pass after layout, so the window has settled on its final size. */
    private static final int SETTLE_FRAMES = 4;

    /** How long to wait for tiles to stop arriving before giving up on a prettier picture. */
    private static final long QUIET_MILLIS = 1_500;

    private static final long TIMEOUT_MILLIS = 60_000;

    private static int exitCode;

    private BatchMode() {
    }

    /**
     * The plain screenshot, and only that one.
     * <p>
     * Every other flag starts with the same eleven characters, so a prefix test would answer true
     * for all of them – {@code --screenshot-preview} asking for the main window and getting it.
     * The flags are matched exactly, so the order they are checked in does not matter.
     */
    public static boolean isRequested(final Application.@Nullable Parameters parameters) {
        return has(parameters, ARG);
    }

    private static boolean has(final Application.@Nullable Parameters parameters,
                               final @NotNull String flag) {
        return parameters != null && parameters.getRaw().stream()
                .anyMatch(s -> s.equals(flag) || s.startsWith(flag + "="));
    }

    public static @NotNull Path outputFor(final Application.@Nullable Parameters parameters) {
        return pathOf(parameters, ARG, Path.of("temp", "wtf-studio-screenshot.png"));
    }

    /**
     * The path a capture flag names, with any {@code @modifiers} stripped off the end.
     * <p>
     * Every capture flag reads the same way: {@code --flag=<path>} and optionally {@code @} and a
     * word or two saying what state to catch it in. Three copies of that parsing is how the
     * modifiers ended up on one flag only.
     */
    private static @NotNull Path pathOf(final Application.@Nullable Parameters parameters,
                                        final @NotNull String flag, final @NotNull Path fallback) {
        if (parameters != null) {
            for (final String raw : parameters.getRaw()) {
                if (raw.startsWith(flag + "=")) {
                    final String value = raw.substring(flag.length() + 1);
                    final int at = value.indexOf('@');
                    return Path.of(at < 0 ? value : value.substring(0, at));
                }
            }
        }
        return fallback;
    }

    /**
     * {@code --lang=cs}: the interface language for this run, over whatever Settings says. For
     * capturing the same window in each language without touching the preferences.
     */
    public static @Nullable String languageOf(final Application.@Nullable Parameters parameters) {
        return valueOf(parameters, "--lang=");
    }

    /** {@code --units=inch} or {@code --units=mm}: the lengths for this run, the same way. */
    public static @Nullable String unitsOf(final Application.@Nullable Parameters parameters) {
        return valueOf(parameters, "--units=");
    }

    private static @Nullable String valueOf(final Application.@Nullable Parameters parameters,
                                            final @NotNull String prefix) {
        if (parameters != null) {
            for (final String raw : parameters.getRaw()) {
                if (raw.startsWith(prefix)) {
                    return raw.substring(prefix.length());
                }
            }
        }
        return null;
    }

    /**
     * Whether any capture flag carries this modifier, so {@code @faded} means the same everywhere.
     * <p>
     * Only the words after the {@code @} count, each whole, separated by {@code @} or a comma. A
     * substring test over the whole flag found {@code outline} in {@code --screenshot-outlines}
     * and in a folder called {@code outlines}, and {@code edit} in {@code temp/edited@faded}.
     */
    public static boolean hasModifier(final Application.@Nullable Parameters parameters,
                                      final @NotNull String modifier) {
        return parameters != null && parameters.getRaw().stream()
                .filter(s -> s.startsWith(ARG))
                .anyMatch(s -> {
                    final int at = s.indexOf('@');
                    return at >= 0 && java.util.Arrays.asList(s.substring(at + 1).split("[@,]"))
                            .contains(modifier);
                });
    }

    /** Whether this run is an unattended one: any capture flag at all, the splash's included. */
    public static boolean isAnyRequested(final Application.@Nullable Parameters parameters) {
        return parameters != null && parameters.getRaw().stream().anyMatch(s -> s.startsWith(ARG));
    }

    /**
     * Sets the exit code for the run these parameters start: 1 as soon as a capture flag is seen,
     * until a picture has demonstrably been written; 0 for an ordinary start.
     * <p>
     * It is 1 from the start, not from when a capture starts, so a run that fails at startup, or
     * has nothing to photograph, does not exit 0 as if it had written its picture.
     */
    public static void arm(final Application.@Nullable Parameters parameters) {
        exitCode = isAnyRequested(parameters) ? 1 : 0;
    }

    /**
     * The first argument that looks like a capture flag and is none of them, or null.
     * <p>
     * Such a run is unattended – its dialogs answer themselves – and yet photographs nothing, so it
     * would be an ordinary window with nobody at it: one that opens a project handed to it and
     * answers its own questions about it. It ends instead.
     */
    public static @Nullable String unknownFlag(final Application.@Nullable Parameters parameters) {
        if (parameters == null) {
            return null;
        }
        final List<String> known = List.of(ARG, ARG_STEPS, ARG_SPLASH, ARG_PREVIEW, ARG_OUTLINES);
        return parameters.getRaw().stream()
                .filter(s -> s.startsWith(ARG))
                .filter(s -> known.stream().noneMatch(f -> s.equals(f) || s.startsWith(f + "=")))
                .findFirst().orElse(null);
    }

    /**
     * Makes every window of this run deaf to the mouse and the keys, from now on, and answers what
     * undoes it.
     * <p>
     * A capture photographs a window shown on the screen, and the pointer resting over it, or
     * moving across it during the run, would light up a button, write its position into the status
     * bar and show the handles of the part under it – so two runs of one capture would differ. A
     * root that is transparent to the mouse is never picked, so nothing in it is hovered or hears a
     * move; {@link Scene#snapshot} draws no pointer of its own. The keys go too, so nobody can open
     * a project of theirs in a window whose questions answer themselves. The window's X is the
     * system's, not the scene's, and still closes it.
     */
    public static @NotNull Runnable refuseInput() {
        final javafx.event.EventHandler<javafx.scene.input.KeyEvent> swallow = javafx.event.Event::consume;
        final javafx.beans.value.ChangeListener<javafx.scene.Parent> rootDeaf = (o, was, root) -> {
            if (root != null) {
                root.setMouseTransparent(true);
            }
        };
        final javafx.beans.value.ChangeListener<Scene> sceneDeaf = (o, was, scene) -> {
            if (scene != null) {
                scene.rootProperty().addListener(rootDeaf);
                rootDeaf.changed(null, null, scene.getRoot());
            }
        };
        final java.util.function.Consumer<javafx.stage.Window> deafen = window -> {
            window.addEventFilter(javafx.scene.input.KeyEvent.ANY, swallow);
            window.sceneProperty().addListener(sceneDeaf);
            sceneDeaf.changed(null, null, window.getScene());
        };
        final javafx.collections.ListChangeListener<javafx.stage.Window> opened = change -> {
            while (change.next()) {
                change.getAddedSubList().forEach(deafen);
            }
        };
        javafx.stage.Window.getWindows().forEach(deafen);
        javafx.stage.Window.getWindows().addListener(opened);
        return () -> {
            javafx.stage.Window.getWindows().removeListener(opened);
            for (final javafx.stage.Window window : javafx.stage.Window.getWindows()) {
                window.removeEventFilter(javafx.scene.input.KeyEvent.ANY, swallow);
                window.sceneProperty().removeListener(sceneDeaf);
                if (window.getScene() != null) {
                    window.getScene().rootProperty().removeListener(rootDeaf);
                }
            }
        };
    }

    public static int exitCode() {
        return exitCode;
    }

    public static boolean isPreviewRequested(final Application.@Nullable Parameters parameters) {
        return has(parameters, ARG_PREVIEW);
    }

    /** True when the capture should be of the calibration state rather than the plain one. */
    public static boolean isPreviewCalibrating(final Application.@Nullable Parameters parameters) {
        return hasModifier(parameters, "calibrate");
    }

    /**
     * {@code @faded} seeds a visible treatment before any capture.
     * <p>
     * A new project has none, so a picture says nothing about whether one would show – which is
     * exactly the question that gets asked of both the map and the preview.
     */
    public static boolean isFaded(final Application.@Nullable Parameters parameters) {
        return hasModifier(parameters, "faded");
    }

    public static @NotNull Path previewOutputFor(final Application.@Nullable Parameters parameters) {
        return pathOf(parameters, ARG_PREVIEW, Path.of("temp", "preview.png"));
    }

    public static boolean isOutlinesRequested(final Application.@Nullable Parameters parameters) {
        return has(parameters, ARG_OUTLINES);
    }

    /** The path, and the phrase after the {@code #}; an empty phrase is the unfiltered list. */
    public static @NotNull String[] outlinesArgs(final Application.@Nullable Parameters parameters) {
        if (parameters != null) {
            for (final String raw : parameters.getRaw()) {
                if (raw.startsWith(ARG_OUTLINES + "=")) {
                    final String value = raw.substring(ARG_OUTLINES.length() + 1);
                    final int hash = value.indexOf('#');
                    return hash < 0 ? new String[]{value, ""}
                            : new String[]{value.substring(0, hash), value.substring(hash + 1)};
                }
            }
        }
        return new String[]{"temp/outlines.png", ""};
    }

    public static boolean isSplashRequested(final Application.@Nullable Parameters parameters) {
        return has(parameters, ARG_SPLASH);
    }

    public static @NotNull Path splashOutputFor(final Application.@Nullable Parameters parameters) {
        if (parameters != null) {
            for (final String raw : parameters.getRaw()) {
                if (raw.startsWith(ARG_SPLASH + "=")) {
                    final String value = raw.substring(ARG_SPLASH.length() + 1);
                    final int at = value.indexOf('@');
                    return Path.of(at < 0 ? value : value.substring(0, at));
                }
            }
        }
        return Path.of("temp", "splash.png");
    }

    /**
     * Snapshots the splash part-way through its sweep and quits.
     * <p>
     * Mid-sweep on purpose: the interesting frame is the one with the scan heads still crossing,
     * and that frame lasts about two seconds once every few runs of the application.
     */
    public static void captureSplash(final @NotNull Scene scene, final @NotNull Path out,
                                     final long afterMillis) {
        new AnimationTimer() {
            private final long until = System.currentTimeMillis() + afterMillis;

            @Override
            public void handle(final long now) {
                if (System.currentTimeMillis() < until) {
                    return;
                }
                stop();
                write(scene, out);
                Platform.exit();
            }
        }.start();
    }

    public static boolean isStepsRequested(final Application.@Nullable Parameters parameters) {
        return has(parameters, ARG_STEPS);
    }

    /**
     * The example a capture opens, as its card on the Start page does – {@code @hike},
     * {@code @poster}, {@code @wallpaper} or {@code @city} – or null for none.
     */
    public static net.trdlo.wtf.studio.project.@Nullable Example exampleOf(
            final Application.@Nullable Parameters parameters) {
        for (final net.trdlo.wtf.studio.project.Example example : net.trdlo.wtf.studio.project.Example.values()) {
            if (hasModifier(parameters, example.name().toLowerCase(java.util.Locale.ROOT))) {
                return example;
            }
        }
        return null;
    }

    public static @NotNull Path stepsDirFor(final Application.@Nullable Parameters parameters) {
        return pathOf(parameters, ARG_STEPS, Path.of("temp", "steps"));
    }

    /**
     * One picture per step, so a whole window's worth of layout can be looked at without sitting
     * in front of it.
     * <p>
     * This exists because the single screenshot at M3 found three bugs a green build had not, all
     * of them things only a picture shows. Six panels is six times the chance of the same kind
     * of mistake, and none of them would fail a test.
     *
     * @param firstNumber the number the rail gives the first step: the pictures are {@code step-0.png},
     *                    the Start page's, and on
     */
    public static void captureSteps(final @NotNull Stage stage, final @NotNull Scene scene,
                                    final @NotNull Path dir, final @NotNull Runnable pending,
                                    final @NotNull java.util.function.Consumer<Integer> selectStep,
                                    final int stepCount, final int firstNumber) {
        System.out.println("[batch] capturing " + stepCount + " steps into " + dir.toAbsolutePath());

        new AnimationTimer() {
            private final long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
            private int frames;
            private int step = -1;
            private long settledAt;
            private int written;

            @Override
            public void handle(final long now) {
                if (++frames < SETTLE_FRAMES) {
                    return;
                }
                if (step < 0) {
                    advance();
                    return;
                }
                final boolean quiet = pendingCount(pending) == 0
                        && System.currentTimeMillis() - settledAt > QUIET_MILLIS;
                if (!quiet && System.currentTimeMillis() < deadline) {
                    return;
                }
                if (write(scene, dir.resolve(String.format(java.util.Locale.ROOT, "step-%d.png", firstNumber + step)))) {
                    written++;
                }
                if (step + 1 >= stepCount) {
                    stop();
                    exitCode = written == stepCount ? 0 : 1;
                    System.out.printf("[batch] wrote %d of %d step pictures%n", written, stepCount);
                    Platform.exit();
                    return;
                }
                advance();
            }

            private void advance() {
                step++;
                selectStep.accept(step);
                settledAt = System.currentTimeMillis();
            }
        }.start();
    }

    /** Arms the capture. The window is already showing; this only decides when to snapshot. */
    public static void capture(final @NotNull Stage stage, final @NotNull Scene scene,
                               final @NotNull Path out, final @NotNull Runnable pending) {
        System.out.println("[batch] capturing to " + out.toAbsolutePath());

        new AnimationTimer() {
            private final long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
            private int frames;
            private long lastChangeMillis = System.currentTimeMillis();
            private int lastPending = -1;

            @Override
            public void handle(final long now) {
                if (++frames < SETTLE_FRAMES) {
                    return;
                }
                final int outstanding = pendingCount(pending);
                if (outstanding != lastPending) {
                    lastPending = outstanding;
                    lastChangeMillis = System.currentTimeMillis();
                }
                final boolean quiet = outstanding == 0
                        && System.currentTimeMillis() - lastChangeMillis > QUIET_MILLIS;
                if (!quiet && System.currentTimeMillis() < deadline) {
                    return;
                }
                stop();
                write(scene, out);
                Platform.exit();
            }
        }.start();
    }

    /** The runnable is a counter in disguise; kept as a Runnable so the caller stays simple. */
    private static int pendingCount(final @NotNull Runnable pending) {
        if (pending instanceof final PendingCount c) {
            return c.get();
        }
        pending.run();
        return 0;
    }

    /** Lets the caller expose "how many decodes are still in flight" without a new interface. */
    public interface PendingCount extends Runnable {
        int get();

        @Override
        default void run() {
        }
    }

    private static boolean write(final @NotNull Scene scene, final @NotNull Path out) {
        try {
            final WritableImage image = scene.snapshot(null);
            Files.createDirectories(out.toAbsolutePath().getParent());
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", out.toFile());
            exitCode = 0;
            System.out.printf("[batch] wrote %s (%.0f x %.0f)%n",
                    out.toAbsolutePath(), image.getWidth(), image.getHeight());
            return true;
        } catch (final Exception ex) {
            log.error("Could not write the screenshot", ex);
            System.err.println("[batch] failed: " + ex);
            return false;
        }
    }
}
