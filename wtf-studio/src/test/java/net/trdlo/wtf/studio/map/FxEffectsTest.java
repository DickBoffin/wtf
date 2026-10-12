package net.trdlo.wtf.studio.map;

import javafx.scene.effect.ColorAdjust;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import net.trdlo.wtf.compose.TileEffect;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The treatment, as the map and the 1:1 preview show it.
 * <p>
 * No toolkit: {@link ColorAdjust} is a plain object until something draws with it, and the
 * arithmetic is the whole point. A sign error here is invisible in a screenshot of a map that was
 * always going to look roughly like that.
 */
class FxEffectsTest {

    @Test
    @DisplayName("no treatment is no filter, rather than a filter that does nothing")
    void identityIsNothing() {
        assertNull(FxEffects.toFx(null));
        assertNull(FxEffects.toFx(TileEffect.NONE));
    }

    @Test
    @DisplayName("saturation counts the other way round from the FX one")
    void saturationIsInverted() {
        // The effect's 1 leaves color alone; ColorAdjust's 0 does. Getting this backwards turns
        // "no change" into "gray" and nobody can tell which of the two they asked for.
        final ColorAdjust untouched = (ColorAdjust) FxEffects.toFx(
                new TileEffect(1, 0.2, 0));
        assertEquals(0, untouched.getSaturation(), 1e-9);

        final ColorAdjust gray = (ColorAdjust) FxEffects.toFx(
                new TileEffect(0, 0, 0));
        assertEquals(-1, gray.getSaturation(), 1e-9);
    }

    @Test
    @DisplayName("lightness and contrast pass through as they are")
    void theOtherTwoPassThrough() {
        final ColorAdjust c = (ColorAdjust) FxEffects.toFx(
                new TileEffect(1, 0.35, -0.45));
        assertEquals(0.35, c.getBrightness(), 1e-9);
        assertEquals(-0.45, c.getContrast(), 1e-9);
    }

    @Test
    @DisplayName("the faded preset comes out faded")
    void theFadedPreset() {
        final ColorAdjust c = (ColorAdjust) FxEffects.toFx(TileEffect.FADED);
        assertEquals(-1, c.getSaturation(), 1e-9, "gray");
        assertTrue(c.getBrightness() > 0, "lighter");
        assertTrue(c.getContrast() < 0, "flatter");
    }

    @Test
    @DisplayName("a treatment past what ColorAdjust accepts is clamped, not thrown")
    void extremesAreClamped() {
        final ColorAdjust c = (ColorAdjust) FxEffects.toFx(
                new TileEffect(4, 1, 1));
        assertEquals(1, c.getSaturation(), 1e-9, "saturation 4 would be 3, which FX refuses");
    }
}
