package net.trdlo.wtf.studio.project;

import org.jetbrains.annotations.NotNull;

/**
 * The editor's own check of a project said no: a file whose area or size does not reproduce, a
 * project with no provider to save.
 * <p>
 * The engine's checks throw a {@link net.trdlo.wtf.tiles.Refusal}, whose reason the window says
 * from the bundle; these checks live in the window already, so they are said from the bundle at
 * the throw and carry the sentence. The error dialog gives a refusal's headline to exactly these
 * two types – not to any exception that happens to have a message, which would make a JavaFX
 * threading error "That did not work" and hide a bug behind the wording of a refusal.
 */
public final class ProjectRefusal extends IllegalArgumentException {

    /** @param said the reason, already in the interface language */
    public ProjectRefusal(final @NotNull String said) {
        super(said);
    }
}
