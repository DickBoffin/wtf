package net.trdlo.wtf.studio;

import net.trdlo.wtf.studio.project.ProjectModel;
import org.jetbrains.annotations.NotNull;

/**
 * The marks for putting the sheets together, kept for the machine rather than for a project.
 * <p>
 * Shown where they are used, beside the export, and stored here, because they describe the habits
 * of whoever prints, which the same project printed elsewhere would not share. The printer's edge
 * is the project's, since it decides the layout; the one last used is remembered the way the paper
 * is, by {@code ProjectFiles}, for the next new project.
 */
public final class PrintingPrefs {

    private PrintingPrefs() {
    }

    public static ProjectModel.@NotNull Printing load(final @NotNull Prefs prefs) {
        return new ProjectModel.Printing(prefs.getBoolean(Prefs.SEAM_MARKS, true));
    }

    public static void save(final @NotNull Prefs prefs, final ProjectModel.@NotNull Printing p) {
        prefs.putBoolean(Prefs.SEAM_MARKS, p.marks());
    }
}
