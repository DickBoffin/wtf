package net.trdlo.wtf.studio;

import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.trdlo.wtf.tiles.Provider;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Alerts, and the batch mode that makes an unattended run possible.
 * <p>
 * The batch flag is what matters: without it the first unexpected dialog in a headless run
 * blocks the FX thread in {@code showAndWait} forever, and that single failure is what makes
 * automated runs useless.
 */
public final class Dialogs {

    private static final Logger log = LoggerFactory.getLogger(Dialogs.class);

    private static boolean batchMode;

    private Dialogs() {
    }

    /** In batch mode nothing waits for a click; dialogs become console lines. */
    public static void setBatchMode(final boolean on) {
        batchMode = on;
    }

    public static boolean isBatchMode() {
        return batchMode;
    }

    public static void info(final @Nullable Window owner, final @NotNull String message) {
        if (batchMode) {
            System.out.println("[batch] " + message);
            return;
        }
        alert(owner, Alert.AlertType.INFORMATION, message).showAndWait();
    }

    /**
     * Something went wrong: what, in words a person can act on, and the details behind an expander.
     * <p>
     * The words come from {@link ErrorText}; the class name and the stack trace are not the
     * message. The details are one click away, with a link that copies them and one that
     * opens the folder the log is in, because those two are what a bug report is made of.
     */
    public static void error(final @Nullable Window owner, final @Nullable Throwable ex) {
        final ErrorText.Explained e = ErrorText.explain(ex);
        log.error("{} {}", e.headline(), e.body(), ex);
        if (batchMode) {
            System.err.println("[batch] " + e.headline() + " " + e.body());
            return;
        }
        final Alert a = alert(owner, Alert.AlertType.ERROR, e.body());
        a.setHeaderText(e.headline());

        final javafx.scene.control.Hyperlink logFolder = new javafx.scene.control.Hyperlink(
                I18n.format("error.log", LogDir.path()));
        logFolder.setWrapText(true);
        logFolder.setOnAction(x -> openInSystem(LogDir.path()));
        final VBox content = new VBox(10, a.getDialogPane().getContent(), logFolder);
        a.getDialogPane().setContent(content);

        if (!e.details().isEmpty()) {
            final TextArea area = new TextArea(e.details());
            area.setEditable(false);
            area.setPrefRowCount(14);
            VBox.setVgrow(area, Priority.ALWAYS);
            final javafx.scene.control.Hyperlink copy = new javafx.scene.control.Hyperlink(
                    I18n.get("error.details.copy"));
            copy.setOnAction(x -> {
                final javafx.scene.input.ClipboardContent clip = new javafx.scene.input.ClipboardContent();
                clip.putString(e.details());
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(clip);
            });
            a.getDialogPane().setExpandableContent(new VBox(4, copy, area));
        }
        a.showAndWait();
    }

    /**
     * A file in the program the system opens it with, or a folder in the file manager.
     * <p>
     * Off the FX thread, because {@code Desktop.open} can take a moment, and quietly when it
     * cannot: a machine with no program for PDFs is not an error in WTF. A folder that does not
     * exist yet is made first – the log folder, before anything has been logged.
     */
    public static void openInSystem(final @NotNull java.nio.file.Path path) {
        Thread.ofVirtual().start(() -> {
            try {
                if (!java.nio.file.Files.exists(path)) {
                    java.nio.file.Files.createDirectories(path);
                }
                if (java.awt.Desktop.isDesktopSupported()
                        && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                    java.awt.Desktop.getDesktop().open(path.toFile());
                }
            } catch (final Exception ex) {
                log.warn("Could not open {}: {}", path, ex.toString());
            }
        });
    }

    public static void warn(final @Nullable Window owner, final @NotNull String header,
                            final @NotNull String message) {
        if (batchMode) {
            System.err.println("[batch] " + header + ": " + message);
            return;
        }
        final Alert a = alert(owner, Alert.AlertType.WARNING, message);
        a.setHeaderText(header);
        a.showAndWait();
    }

    /**
     * A yes/no the user must actually answer.
     * <p>
     * In batch mode this returns <b>false</b>. Everything asked through here is something that
     * costs money, overwrites files or produces a print that must not be printed, and an
     * unattended run has nobody to take responsibility for any of those.
     */
    public static boolean confirm(final @Nullable Window owner, final @NotNull String header,
                                  final @NotNull String message) {
        if (batchMode) {
            System.out.println("[batch] declined without asking: " + header + " - " + message);
            return false;
        }
        final Alert a = alert(owner, Alert.AlertType.CONFIRMATION, message);
        a.setHeaderText(header);
        a.getButtonTypes().setAll(ButtonType.OK, Buttons.CANCEL);
        return a.showAndWait().orElse(Buttons.CANCEL) == ButtonType.OK;
    }

    /** The answers to changes about to be lost – by New, Open, closing the window, or a project handed over. */
    public enum Unsaved {
        SAVE, DISCARD, CANCEL;

        /**
         * Whether what asked may go on. After Save only when something was saved: a picker closed
         * without choosing, or a write that failed, keeps the changes where they are.
         */
        public boolean goesOn(final @NotNull BooleanSupplier save) {
            return switch (this) {
                case SAVE -> save.getAsBoolean();
                case DISCARD -> true;
                case CANCEL -> false;
            };
        }
    }

    /** The answers to unsaved changes before an export. */
    public enum BeforeExport {
        SAVE, SAVE_AS, EXPORT_UNSAVED, CANCEL;

        /**
         * What is offered. Save as only for a project that has a file: for one that has none, Save
         * asks where too, and the two would be one button twice.
         */
        public static @NotNull List<BeforeExport> offered(final boolean hasFile) {
            return hasFile ? List.of(SAVE, SAVE_AS, EXPORT_UNSAVED, CANCEL)
                    : List.of(SAVE, EXPORT_UNSAVED, CANCEL);
        }

        /**
         * Whether the export goes on. After Save or Save as only when something was saved: whoever
         * asked to save and then closed the picker has not said to print unsaved changes.
         */
        public boolean goesOn(final @NotNull BooleanSupplier save, final @NotNull BooleanSupplier saveAs) {
            return switch (this) {
                case SAVE -> save.getAsBoolean();
                case SAVE_AS -> saveAs.getAsBoolean();
                case EXPORT_UNSAVED -> true;
                case CANCEL -> false;
            };
        }
    }

    /**
     * Save, Don't save or Cancel, before changes would be lost. Enter saves, Escape cancels.
     * <p>
     * In batch mode the answer is Don't save. Batch mode is a capture run, and the only project a
     * capture window ever holds is the one the run seeded: it opens no file and takes no hand-over,
     * and hears neither the mouse nor the keys, so nobody can open one in it. Its changes are the
     * run's own, and Cancel kept them at the price of a window whose X did not close it.
     */
    public static @NotNull Unsaved askUnsaved(final @Nullable Window owner) {
        return askUnsaved(owner, null);
    }

    /**
     * {@link #askUnsaved(Window)}, for a project handed over from the file manager: the question
     * names the file that opens once it is answered, which nobody at this window asked for.
     *
     * @param opening the file a hand-over would open, or null for the ordinary question
     */
    public static @NotNull Unsaved askUnsaved(final @Nullable Window owner, final @Nullable Path opening) {
        final String header = I18n.get("project.discard.title");
        final String body = unsavedBody(opening);
        if (batchMode) {
            System.out.println("[batch] not saved without asking: " + header + " - " + body);
            return Unsaved.DISCARD;
        }
        final Map<Unsaved, ButtonType> buttons = new LinkedHashMap<>();
        buttons.put(Unsaved.SAVE, new ButtonType(I18n.get("button.save"), ButtonBar.ButtonData.YES));
        buttons.put(Unsaved.DISCARD, new ButtonType(I18n.get("button.dontSave"), ButtonBar.ButtonData.NO));
        buttons.put(Unsaved.CANCEL, Buttons.CANCEL);
        return choose(owner, header, body, buttons, Unsaved.SAVE, Unsaved.CANCEL);
    }

    /** What {@link #askUnsaved(Window, Path)} says, off the toolkit: with the file's name when one is opening. */
    static @NotNull String unsavedBody(final @Nullable Path opening) {
        if (opening == null || opening.getFileName() == null) {
            return I18n.get("project.discard.body");
        }
        return I18n.format("project.discard.body.handOver", opening.getFileName().toString());
    }

    /**
     * Save, Save as, Export without saving or Cancel, before an export of unsaved changes. Enter
     * saves, Escape cancels.
     * <p>
     * In batch mode the export goes on unsaved: an unattended run writes no project file it was
     * not asked for, and the export is what it was asked for.
     */
    public static @NotNull BeforeExport askBeforeExport(final @Nullable Window owner, final boolean hasFile) {
        final String header = I18n.get("export.askSave.title");
        final String body = I18n.get(hasFile ? "export.askSave.body" : "export.askSave.body.new");
        if (batchMode) {
            System.out.println("[batch] exported without saving: " + header + " - " + body);
            return BeforeExport.EXPORT_UNSAVED;
        }
        final Map<BeforeExport, ButtonType> buttons = new LinkedHashMap<>();
        for (final BeforeExport answer : BeforeExport.offered(hasFile)) {
            buttons.put(answer, switch (answer) {
                case SAVE -> new ButtonType(I18n.get("button.save"), ButtonBar.ButtonData.YES);
                case SAVE_AS -> new ButtonType(I18n.get("button.saveAs"), ButtonBar.ButtonData.OTHER);
                case EXPORT_UNSAVED -> new ButtonType(I18n.get("button.exportUnsaved"), ButtonBar.ButtonData.NO);
                case CANCEL -> Buttons.CANCEL;
            });
        }
        return choose(owner, header, body, buttons, BeforeExport.SAVE, BeforeExport.CANCEL);
    }

    /** The answers to a recovery copy of unsaved work that a start found; see {@link RecoveryOffer}. */
    public enum Recovered { OPEN, DISCARD, LATER }

    /**
     * Open the copy, throw it away, or Cancel, which keeps it for the next start. Enter opens, Escape
     * cancels.
     * <p>
     * In batch mode the copy is kept: nobody is there to carry on with it, or to say it may go.
     *
     * @param of      the file it is a copy of, or null for a project that was never saved
     * @param changed when its window last wrote it
     */
    public static @NotNull Recovered askRecovery(final @Nullable Window owner, final @Nullable Path of,
                                                 final @NotNull Instant changed) {
        final String header = I18n.get("project.recover.title");
        final String body = of == null
                ? I18n.format("project.recover.body.untitled", I18n.dateTime(changed))
                : I18n.format("project.recover.body", String.valueOf(of.getFileName()), I18n.dateTime(changed));
        if (batchMode) {
            System.out.println("[batch] kept without asking: " + header + " - " + body);
            return Recovered.LATER;
        }
        final Map<Recovered, ButtonType> buttons = new LinkedHashMap<>();
        buttons.put(Recovered.OPEN, new ButtonType(I18n.get("button.recover"), ButtonBar.ButtonData.YES));
        buttons.put(Recovered.DISCARD, new ButtonType(I18n.get("button.discardCopy"), ButtonBar.ButtonData.NO));
        buttons.put(Recovered.LATER, Buttons.CANCEL);
        return choose(owner, header, body, buttons, Recovered.OPEN, Recovered.LATER);
    }

    /** One button per answer; the dialog closed any other way is {@code closed}. */
    private static <T> @NotNull T choose(final @Nullable Window owner, final @NotNull String header,
                                         final @NotNull String message, final @NotNull Map<T, ButtonType> buttons,
                                         final @NotNull T byDefault, final @NotNull T closed) {
        final Alert a = alert(owner, Alert.AlertType.CONFIRMATION, message);
        a.setHeaderText(header);
        a.getButtonTypes().setAll(buttons.values());
        ((Button) a.getDialogPane().lookupButton(buttons.get(byDefault))).setDefaultButton(true);
        ((Button) a.getDialogPane().lookupButton(buttons.get(closed))).setCancelButton(true);
        final ButtonType pressed = a.showAndWait().orElse(null);
        for (final Map.Entry<T, ButtonType> e : buttons.entrySet()) {
            if (e.getValue() == pressed) {
                return e.getKey();
            }
        }
        return closed;
    }

    /**
     * The 401/403 hand-off the brief asks for: never a stack trace, never silence – which provider
     * refused, and a button that goes straight to where the key is entered.
     */
    public static void credentialsRejected(final @Nullable Window owner, final @NotNull Provider provider,
                                           final int status, final @NotNull Runnable openSettings) {
        final String body = I18n.format("error.credentials.body", provider.displayName(), status);
        if (batchMode) {
            System.err.println("[batch] " + body);
            return;
        }
        final Alert a = alert(owner, Alert.AlertType.WARNING, body);
        a.setHeaderText(I18n.get("error.credentials.title"));
        final ButtonType open = new ButtonType(I18n.get("error.credentials.open"),
                ButtonBar.ButtonData.OK_DONE);
        a.getButtonTypes().setAll(open, Buttons.CANCEL);
        if (a.showAndWait().orElse(Buttons.CANCEL) == open) {
            openSettings.run();
        }
    }

    /** What was pressed in {@link #viewTrouble}. */
    public enum ViewTrouble { RETRY, PROVIDERS, CLOSED }

    /**
     * What stood in the way of the map's own download: a warning with Close, and Try again and the
     * Providers dialog where they can help. Closed any other way, and in batch mode, it is
     * {@link ViewTrouble#CLOSED}.
     */
    public static @NotNull ViewTrouble viewTrouble(final @Nullable Window owner, final @NotNull String header,
                                                   final @NotNull String body, final boolean retry,
                                                   final boolean providers) {
        if (batchMode) {
            System.err.println("[batch] " + header + ": " + body);
            return ViewTrouble.CLOSED;
        }
        final Alert a = alert(owner, Alert.AlertType.WARNING, body);
        a.setHeaderText(header);
        final ButtonType again = new ButtonType(I18n.get("button.retry"), ButtonBar.ButtonData.OK_DONE);
        final ButtonType open = new ButtonType(I18n.get("error.credentials.open"), ButtonBar.ButtonData.OTHER);
        final List<ButtonType> offered = new java.util.ArrayList<>();
        if (retry) {
            offered.add(again);
        }
        if (providers) {
            offered.add(open);
        }
        offered.add(Buttons.CLOSE);
        a.getButtonTypes().setAll(offered);
        final ButtonType pressed = a.showAndWait().orElse(Buttons.CLOSE);
        return pressed == again ? ViewTrouble.RETRY : pressed == open ? ViewTrouble.PROVIDERS : ViewTrouble.CLOSED;
    }

    /** An alert as each of these starts: modal to its owner, with the message and the studio's sheet. */
    static @NotNull Alert alert(final @Nullable Window owner, final Alert.@NotNull AlertType type,
                                final @NotNull String message) {
        final Alert a = new Alert(type);
        a.initOwner(owner);
        a.initModality(Modality.WINDOW_MODAL);
        a.setTitle(I18n.get("error.title"));
        a.setHeaderText(null);
        final Label label = new Label(message);
        label.setWrapText(true);
        label.setMaxWidth(460);
        a.getDialogPane().setContent(label);
        StudioDialog.style(a.getDialogPane());
        return a;
    }
}
