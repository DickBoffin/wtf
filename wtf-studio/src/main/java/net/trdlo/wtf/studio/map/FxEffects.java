package net.trdlo.wtf.studio.map;

import javafx.scene.effect.ColorAdjust;
import javafx.scene.effect.Effect;
import org.jetbrains.annotations.Nullable;
import net.trdlo.wtf.compose.TileEffect;

/**
 * A region's treatment, as a filter the card can apply.
 * <p>
 * The exporter applies the same numbers exactly, to the palette of each tile; this is the cheap
 * picture of what that will do, and the only one anybody sees before the PDF exists. Both the map
 * and the 1:1 preview use it, which is why it lives here rather than in either of them.
 * <p>
 * <b>Saturation counts the other way round from the FX one.</b> The effect's 1 means "leave the
 * color alone" and {@link ColorAdjust}'s 0 does, so the two differ by one – a sign error nobody
 * can see in a screenshot of a map that was always going to look roughly like that, and the
 * reason this arithmetic has tests of its own.
 */
public final class FxEffects {

    private FxEffects() {
    }

    /**
     * @param effect the region's treatment, or null
     * @return the filter, or null when it would change nothing
     */
    public static @Nullable Effect toFx(final @Nullable TileEffect effect) {
        if (effect == null || effect.isIdentity()) {
            return null;
        }
        final ColorAdjust color = new ColorAdjust();
        color.setSaturation(clamp(effect.saturation() - 1));
        color.setBrightness(clamp(effect.lightness()));
        color.setContrast(clamp(effect.contrast()));
        return color;
    }

    private static double clamp(final double v) {
        return Math.max(-1, Math.min(1, v));
    }
}
