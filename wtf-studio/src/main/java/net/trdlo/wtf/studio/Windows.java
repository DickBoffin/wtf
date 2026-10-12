package net.trdlo.wtf.studio;

import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * What every stage of the studio's is given before it is shown: the icons, the stylesheet and,
 * for a window whose place is worth keeping, the bounds it was last closed with – in that order,
 * in one place.
 * <p>
 * Statics rather than a base class: the main window's stage is the one JavaFX hands the
 * application, a dialog's is the one JavaFX makes for it, and neither can be a subclass of ours.
 * A dialog has its sheet on its pane ({@link StudioDialog#style}) and its icons from its owner, so
 * a dialog that wants its bounds kept takes only {@link #remember}.
 */
public final class Windows {

    /** The studio's stylesheet: the one lookup of it, which the stages and the dialogs share. */
    static final String STYLESHEET =
            Objects.requireNonNull(Windows.class.getResource("studio.css")).toExternalForm();

    /** Every size, so the window manager and the task bar each pick the one they want. */
    private static final int[] ICON_SIZES = {16, 24, 32, 48, 64, 128, 256, 512};

    private Windows() {
    }

    /**
     * The icons and the stylesheet, for a stage whose bounds are not remembered: the splash, which
     * goes where the main window is about to be. The stage must have its scene. Before the stage
     * is shown, which is when Windows takes the icon for its taskbar button: an undecorated window
     * or one with an owner still has a button, and without its icons it shows Java's coffee cup.
     */
    public static void decorate(final @NotNull Stage stage) {
        icons(stage);
        style(Objects.requireNonNull(stage.getScene(), "a stage is decorated once it has its scene"));
    }

    /**
     * The icons, the stylesheet and the bounds the window was last closed with under {@code name},
     * which it keeps from then on: the main window and the 1:1 preview.
     */
    public static void decorate(final @NotNull Stage stage, final @NotNull Prefs prefs,
                                final @NotNull String name) {
        decorate(stage);
        remember(stage, prefs, name);
    }

    /**
     * Puts the window where it was last closed under {@code name}, unless that is now off every
     * screen ({@link Prefs#restoreWindow}), and keeps where it is each time it is hidden. A
     * handler, not {@code setOnHidden}, so a window's own handler for its hiding stays its own.
     * For a dialog, whose stage JavaFX makes: {@code (Stage) dialog.getDialogPane().getScene()
     * .getWindow()}, before the dialog is shown, so its explicit size and place win over the
     * pane's preferred size and the centering over the owner.
     */
    public static void remember(final @NotNull Stage stage, final @NotNull Prefs prefs,
                                final @NotNull String name) {
        prefs.restoreWindow(stage, name);
        stage.addEventHandler(WindowEvent.WINDOW_HIDDEN, e -> prefs.saveWindow(stage, name));
    }

    /** Puts the studio's stylesheet on a scene, once. */
    static void style(final @NotNull Scene scene) {
        if (!scene.getStylesheets().contains(STYLESHEET)) {
            scene.getStylesheets().add(STYLESHEET);
        }
    }

    private static void icons(final @NotNull Stage stage) {
        for (final int size : ICON_SIZES) {
            try (final java.io.InputStream in = Windows.class.getResourceAsStream("icon-" + size + ".png")) {
                if (in != null) {
                    stage.getIcons().add(new Image(in));
                }
            } catch (final Exception ignored) {
                // an icon is not worth failing to start over
            }
        }
    }
}
