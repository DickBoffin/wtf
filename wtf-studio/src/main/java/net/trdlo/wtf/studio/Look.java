package net.trdlo.wtf.studio;

import atlantafx.base.theme.CupertinoDark;
import atlantafx.base.theme.CupertinoLight;
import atlantafx.base.theme.Dracula;
import atlantafx.base.theme.NordDark;
import atlantafx.base.theme.NordLight;
import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The interface's look: one of AtlantaFX's themes, or one of the two stylesheets JavaFX itself
 * comes with. Most are light or dark as Settings says; Dracula is dark only, and JavaFX's own are
 * light only. studio.css colors the studio's own parts with AtlantaFX's tokens, which JavaFX's
 * stylesheets are given by {@link Windows#JAVAFX_COLORS}.
 */
public enum Look {
    PRIMER, CUPERTINO, NORD, DRACULA, MODENA, CASPIAN;

    public boolean hasLight() {
        return this != DRACULA;
    }

    public boolean hasDark() {
        return !isJavafx();
    }

    /** Whether it is shown dark, when dark is what was asked for or not. */
    public boolean dark(final boolean wanted) {
        return hasDark() && (wanted || !hasLight());
    }

    /** JavaFX's own stylesheet rather than an AtlantaFX theme. */
    public boolean isJavafx() {
        return this == MODENA || this == CASPIAN;
    }

    /** What {@link Application#setUserAgentStylesheet} is given for it. */
    public @NotNull String stylesheet(final boolean wanted) {
        final boolean dark = dark(wanted);
        return switch (this) {
            case PRIMER -> (dark ? new PrimerDark() : new PrimerLight()).getUserAgentStylesheet();
            case CUPERTINO -> (dark ? new CupertinoDark() : new CupertinoLight()).getUserAgentStylesheet();
            case NORD -> (dark ? new NordDark() : new NordLight()).getUserAgentStylesheet();
            case DRACULA -> new Dracula().getUserAgentStylesheet();
            case MODENA -> Application.STYLESHEET_MODENA;
            case CASPIAN -> Application.STYLESHEET_CASPIAN;
        };
    }

    /** The look saved under this name, or Primer for none or a name no look has. */
    public static @NotNull Look named(final @Nullable String name) {
        for (final Look look : values()) {
            if (look.name().equals(name)) {
                return look;
            }
        }
        return PRIMER;
    }
}
