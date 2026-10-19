package net.trdlo.wtf.studio;

import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A dialog of the studio's: its owner, its title, whether it may be resized, its buttons and the
 * studio's stylesheet, set in one place.
 * <p>
 * Nothing else. No model, no FXML and nothing handed over statically: each dialog takes what it
 * needs in its own constructor, and one that composes a {@link Dialog} rather than being one can
 * make a {@code StudioDialog} as it is. An alert, which is JavaFX's own kind of dialog, gets the
 * sheet through {@link #style}.
 * <p>
 * The sheet goes on the pane itself. JavaFX binds a dialog's scene to its owner's stylesheets
 * when the owner is a stage with a scene, which is how most of the dialogs had it, but an alert
 * with no owner had none, and two dialogs copied the owner's list besides, each its own way. The
 * sheet is the one the stages take too ({@link Windows}).
 */
public class StudioDialog<R> extends Dialog<R> {

    /**
     * @param owner     the window the dialog belongs to, or null for none
     * @param titleKey  the key of its title in the bundle
     * @param resizable whether it may be resized
     * @param buttons   the buttons along its bottom, in the roles they carry
     */
    public StudioDialog(final @Nullable Window owner, final @NotNull String titleKey, final boolean resizable,
                        final @NotNull ButtonType... buttons) {
        initOwner(owner);
        setTitle(I18n.get(titleKey));
        setResizable(resizable);
        if (resizable) {
            // Lifts the theme's cap on a dialog pane's width (studio.css)
            getDialogPane().getStyleClass().add("resizable-dialog");
        }
        getDialogPane().getButtonTypes().addAll(buttons);
        style(getDialogPane());
    }

    /** Puts the studio's stylesheet on a dialog's pane: the one way any dialog or alert gets it. */
    public static void style(final @NotNull DialogPane pane) {
        Windows.style(pane.getStylesheets());
    }

    /** What a dialog or an alert opened from this one belongs to: this one, not the window under it. */
    protected final @Nullable Window self() {
        return getDialogPane().getScene().getWindow();
    }
}
