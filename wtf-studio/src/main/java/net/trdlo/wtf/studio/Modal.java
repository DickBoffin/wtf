package net.trdlo.wtf.studio;

import javafx.application.Platform;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.function.Supplier;

/**
 * Whether WTF is asking something: a modal window of its own open – a dialog, an alert, a file or
 * folder chooser – or the FX thread still in the event loop of one that has just closed, on its way
 * back to the code that asked, which then goes on with the answer. What must not land in the middle
 * of that, a project handed over from the file manager ({@link HandOver}), waits until nothing is.
 * <p>
 * JavaFX lists its own windows, and counts the nested event loop a {@code showAndWait} runs. A
 * chooser is the system's own window in the system's own loop, which JavaFX neither lists nor
 * counts, though what is posted to the FX thread runs inside it all the same. So every chooser of
 * the studio's is shown through here, which counts it.
 */
public final class Modal {

    /** How many choosers are showing; the FX thread's alone, as every chooser is shown on it. */
    private static int choosers;

    private Modal() {
    }

    /** A file to open, chosen in {@code chooser}; null when it was closed without one. */
    public static @Nullable File openFile(final @NotNull FileChooser chooser, final @Nullable Window owner) {
        return choosing(() -> chooser.showOpenDialog(owner));
    }

    /** Where to save, chosen in {@code chooser}; null when it was closed without a name. */
    public static @Nullable File saveFile(final @NotNull FileChooser chooser, final @Nullable Window owner) {
        return choosing(() -> chooser.showSaveDialog(owner));
    }

    /** A folder, chosen in {@code chooser}; null when it was closed without one. */
    public static @Nullable File folder(final @NotNull DirectoryChooser chooser, final @Nullable Window owner) {
        return choosing(() -> chooser.showDialog(owner));
    }

    /** Counts a chooser as open for as long as {@code show} takes to answer, or to throw. */
    static <T> T choosing(final @NotNull Supplier<T> show) {
        choosers++;
        try {
            return show.get();
        } finally {
            choosers--;
        }
    }

    /** Whether WTF is asking anything, as the class comment says; on the FX thread. */
    static boolean anyOpen() {
        if (choosers > 0 || Platform.isNestedLoopRunning()) {
            return true;
        }
        for (final Window window : Window.getWindows()) {
            if (isModal(window)) {
                return true;
            }
        }
        return false;
    }

    /** A stage showing with a modality: a dialog or an alert, asking something. */
    static boolean isModal(final @NotNull Window window) {
        return window.isShowing() && window instanceof final Stage stage && stage.getModality() != Modality.NONE;
    }
}
