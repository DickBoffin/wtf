package net.trdlo.wtf.studio;

import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import net.trdlo.wtf.studio.map.ProjectOverlay;
import net.trdlo.wtf.studio.map.TileCanvas;
import net.trdlo.wtf.studio.project.Example;
import net.trdlo.wtf.studio.project.ProjectIo;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.studio.steps.Step;
import net.trdlo.wtf.studio.steps.StepRail;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The project as a file: new, open and save, the examples the Start page opens, the question before
 * a change is thrown away, the window's title that says which file it is and whether it is saved,
 * the paper a new project starts on, and the recovery copies of unsaved work that a start offers back.
 * <p>
 * Part of {@link MainWindow}, whose toolbar, keys and close request call these.
 */
final class ProjectFiles {

    private final Stage stage;
    private final Prefs prefs;
    private final ProjectModel model;
    private final TileCanvas map;
    private final StepRail rail;
    private final MapSource source;

    ProjectFiles(final @NotNull Stage stage, final @NotNull Prefs prefs, final @NotNull ProjectModel model,
                 final @NotNull TileCanvas map, final @NotNull StepRail rail, final @NotNull MapSource source) {
        this.stage = stage;
        this.prefs = prefs;
        this.model = model;
        this.map = map;
        this.rail = rail;
        this.source = source;
    }

    /**
     * Remembers what the next start opens: the project's file as it is now – after New there is
     * none, and a new project is what comes back. A project closed unsaved comes back as it was
     * last saved; its unsaved work is the recovery copy's.
     */
    static void rememberForNextStart(final @NotNull Prefs prefs, final @Nullable Path file) {
        prefs.putPath(Prefs.LAST_PROJECT, file == null ? null : file.toAbsolutePath());
    }

    /**
     * The project a start opens: the one it was handed, from the file manager or the command line,
     * else the one the window last closed on, while it is still there. Null for a new project.
     */
    static @Nullable Path atStart(final @Nullable Path given, final @Nullable Path last) {
        if (given != null) {
            return given;
        }
        return last != null && java.nio.file.Files.isRegularFile(last) ? last : null;
    }

    void rememberForNextStart() {
        rememberForNextStart(prefs, model.fileProperty().get());
    }

    void updateTitle() {
        final AppInfo info = AppInfo.get();
        final Path file = model.fileProperty().get();
        final String name = file != null ? file.getFileName().toString()
                : I18n.get("project.untitled");
        stage.setTitle(I18n.format(model.dirtyProperty().get() ? "project.windowTitle.dirty" : "project.windowTitle",
                name, info.name(), info.version()));
    }

    boolean confirmDiscard() {
        return confirmDiscard(null);
    }

    /**
     * Whether the project may be thrown away, asked when it is not saved.
     *
     * @param opening the file a hand-over would open, named in the question; null for New, Open,
     *                a recovery and a close, whose person already knows what comes next
     */
    boolean confirmDiscard(final @Nullable Path opening) {
        if (!model.dirtyProperty().get()) {
            return true;
        }
        return Dialogs.askUnsaved(stage, opening).goesOn(() -> saveProject(false));
    }

    /**
     * The paper from last time, the orientation it was turned to, and the printer's white edge.
     * <p>
     * A person has the paper and the printer they have, and a new project starting on A4 portrait
     * every time made them pick their own again at each start. Written back whenever it changes,
     * whether chosen by hand or brought in by an opened project – either way, that is the paper
     * last used; an example's is put back ({@link #openExample}). Set as where the project starts,
     * so starting up marks nothing as changed and leaves nothing to undo.
     */
    void restorePaper() {
        model.startOver(() -> writePaper(model));
        model.paperProperty().addListener((o, a, p) -> {
            // Only a paper the list offers: a project's own sheet size, opened as it is, is no paper
            // to start the next project on, and its name finds nothing at the next start
            if (p != null && net.trdlo.wtf.compose.Medium.PAPER_SIZES.contains(p)) {
                prefs.put(Prefs.LAST_PAPER, p.name());
            }
        });
        model.orientationProperty().addListener((o, a, v) -> {
            if (v != null) {
                prefs.put(Prefs.LAST_ORIENTATION, v.name());
            }
        });
        model.edgeMmProperty().addListener((o, a, mm) -> {
            // Not one the job refuses, which would be the next project's first problem
            if (mm.doubleValue() >= 0) {
                prefs.putDouble(Prefs.PRINT_EDGE_MM, mm.doubleValue());
            }
        });
    }

    /** The printer's white edge, the paper and the way it is turned, as last used, written into a project. */
    private void writePaper(final @NotNull ProjectModel into) {
        into.edgeMmProperty().set(Math.max(0, prefs.getDouble(Prefs.PRINT_EDGE_MM,
                net.trdlo.wtf.compose.Marks.DEFAULT_EDGE_MM)));
        final String paper = prefs.get(Prefs.LAST_PAPER);
        if (paper != null) {
            try {
                into.paperProperty().set(net.trdlo.wtf.compose.Medium.paper(paper));
            } catch (final IllegalArgumentException unknown) {
                // a size this build no longer offers: keep the default
            }
        }
        final String orientation = prefs.get(Prefs.LAST_ORIENTATION);
        if (orientation != null) {
            try {
                into.orientationProperty().set(net.trdlo.wtf.compose.Medium.Orientation.valueOf(orientation));
            } catch (final IllegalArgumentException unknown) {
                // likewise
            }
        }
    }

    /**
     * A new project, on the Start page: as a start begins one, on the provider, map and language
     * last chosen and the paper, its orientation and the printer's edge last used – the person's
     * own, which an example opened since has left as they were (D43).
     */
    void newProject() {
        if (!confirmDiscard()) {
            return;
        }
        model.startNew(fresh -> {
            source.writeChoice(fresh);
            writePaper(fresh);
        });
        model.fileProperty().set(null);
        rail.select(Step.START);
        map.requestPaint();
    }

    /**
     * Opens an example from the Start page, after the question Open asks about unsaved changes: a
     * new project with every step filled in, on no file ({@link ProjectIo#openExample}).
     * <p>
     * <b>A visit, not a choice</b> (D43). The provider, its map and language, the paper and the
     * printer's edge an example brings are not remembered as the ones last used: they are put back
     * as they were, so that New and the next start open on the person's own, and not on a poster's
     * A1 or on a national map far from the person's country.
     */
    void openExample(final @NotNull Example example) {
        if (!confirmDiscard()) {
            return;
        }
        final Prefs.Kept choices = prefs.keepChoices();
        final List<String> warnings;
        try {
            warnings = ProjectIo.openExample(model, example, source.catalog());
        } catch (final IOException | RuntimeException ex) {
            Dialogs.error(stage, ex);
            return;
        } finally {
            choices.putBack();
        }
        opened(warnings);
    }

    void openProject() {
        if (!confirmDiscard()) {
            return;
        }
        final FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("project.open"));
        startIn(chooser);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                I18n.get("project.filter"), List.of("*" + ProjectIo.EXTENSION)));
        final java.io.File chosen = Modal.openFile(chooser, stage);
        if (chosen != null) {
            openProjectFile(chosen.toPath());
        }
    }

    /**
     * Opens a project without asking which: the one chosen in the dialog, or the one the
     * system handed WTF at start, when a {@code .wtf} file was opened from a file manager.
     *
     * @return whether it opened – false, with the error already shown, when it did not
     */
    boolean openProjectFile(final @NotNull Path file) {
        try {
            final List<String> warnings = ProjectIo.load(model, file, source.catalog());
            rememberProjectFolder(file);
            opened(warnings);
            return true;
        } catch (final IOException | RuntimeException ex) {
            Dialogs.error(stage, ex);
            return false;
        }
    }

    /**
     * Offers the recovery copies a start found, whose windows have gone, and opens the one somebody
     * takes: the project as its window left it, unsaved, on its own file. See {@link RecoveryOffer}.
     */
    void offerRecovery(final @NotNull List<Recovery.Orphan> found) {
        RecoveryOffer.decide(found,
                orphan -> Dialogs.askRecovery(stage, orphan.of(), orphan.changed()),
                orphan -> confirmDiscard() && recover(orphan));
    }

    private boolean recover(final Recovery.@NotNull Orphan orphan) {
        try {
            opened(ProjectIo.recover(model, orphan.copy(), orphan.of(), source.catalog()));
            return true;
        } catch (final IOException | RuntimeException ex) {
            Dialogs.error(stage, ex);
            return false;
        }
    }

    /**
     * What follows a project opened – from a file, a recovery copy or an example: its steps, from the
     * first, when it opened over the Start page; the map on it; and what it said.
     */
    private void opened(final @NotNull List<String> warnings) {
        source.rebuild();
        if (rail.current() == Step.START) {
            rail.select(Step.SUBJECT);
        }
        new ProjectOverlay(model, rail::current).frame(map.camera());
        map.requestPaint();
        if (!warnings.isEmpty()) {
            // A project is a thing you can mail somebody. It opening with a note beats it
            // refusing to open because their copy has one provider fewer.
            Dialogs.warn(stage, I18n.get("project.opened.warnings"),
                    String.join(System.lineSeparator(), warnings));
        }
    }

    /**
     * Opens a project or save dialog where the work last was.
     * <p>
     * The folder a project was last saved to or opened from, else the one last exported to – the
     * two are usually the same place, and a project that was only ever exported has only the
     * second. Without this every Open after a restart began in the home directory.
     */
    private void startIn(final @NotNull FileChooser chooser) {
        for (final String key : List.of(Prefs.LAST_PROJECT_DIR, Prefs.LAST_EXPORT_DIR)) {
            final Path dir = prefs.getPath(key);
            if (dir != null && java.nio.file.Files.isDirectory(dir)) {
                chooser.setInitialDirectory(dir.toFile());
                return;
            }
        }
    }

    private void rememberProjectFolder(final @NotNull Path file) {
        final Path dir = file.toAbsolutePath().getParent();
        if (dir != null) {
            prefs.putPath(Prefs.LAST_PROJECT_DIR, dir);
        }
    }

    /** @return whether it was saved – false when the picker was closed, or the write failed */
    boolean saveProject(final boolean forceAskWhere) {
        Path target = model.fileProperty().get();
        if (target == null || forceAskWhere) {
            final FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.get(forceAskWhere ? "project.saveAs" : "project.save"));
            startIn(chooser);
            // A title is free text, and "1:25 000" is not a name the system's dialog will save
            chooser.setInitialFileName(net.trdlo.wtf.compose.FileNames.safe(model.titleProperty().get(),
                            I18n.get("project.baseName"))
                    + ProjectIo.EXTENSION);
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                    I18n.get("project.filter"), List.of("*" + ProjectIo.EXTENSION)));
            final java.io.File chosen = Modal.saveFile(chooser, stage);
            if (chosen == null) {
                return false;
            }
            target = chosen.toPath();
            if (!target.toString().endsWith(ProjectIo.EXTENSION)) {
                target = target.resolveSibling(target.getFileName() + ProjectIo.EXTENSION);
            }
        }
        try {
            ProjectIo.save(model, target);
            rememberProjectFolder(target);
            updateTitle();
            return true;
        } catch (final IOException | RuntimeException ex) {
            Dialogs.error(stage, ex);
            return false;
        }
    }
}
