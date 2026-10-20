package net.trdlo.wtf.studio.map;

import net.trdlo.wtf.tiles.Mercator;
import net.trdlo.wtf.tiles.TileRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The 1:1 preview's fixed zoom over a view too big to draw at it: Czechia on two A4 sheets at zoom
 * 15 drew zoom 8 everywhere, and said zoom 15 in the caption. The view is drawn at the scale's zoom
 * and the fixed one over a patch in the middle, of no more tiles than a frame may hold.
 */
class FixedZoomPatchTest {

    /** About Czechia on two A4 sheets side by side, on a 900 by 620 window at 96 DPI. */
    private static Camera czechiaOnTwoSheets() {
        final Camera camera = new Camera();
        camera.setViewSize(900, 620);
        camera.setFractionalZoom(8.3);
        camera.centerOn(15.5, 49.8, 8.3);
        return camera;
    }

    @Test
    @DisplayName("a zoom too fine for the whole view is drawn over a patch in the middle")
    void tooFineIsAPatch() {
        final Camera camera = czechiaOnTwoSheets();
        final TileLayer layer = new TileLayer(camera, () -> { });
        layer.setFixedTileZoom(15);

        assertEquals(camera.tileZoom(), layer.drawZoom(), "the ground is the scale's zoom");
        final TileRange patch = layer.patch();
        assertFalse(patch.isEmpty(), "and zoom 15 is drawn somewhere");
        assertTrue(patch.count() <= 1_000, "no more than a frame may hold: " + patch.count());
        assertTrue(patch.count() > 900, "and not much less: " + patch.count());

        final int n = Mercator.tileCount(15);
        final int cx = (int) Math.floor(camera.toUnitX(450) * n);
        final int cy = (int) Math.floor(camera.toUnitY(310) * n);
        assertTrue(patch.contains(cx, cy), "about the middle of the view");
        assertEquals(camera.visibleTiles(0, 15).intersect(patch), patch, "and within it");
    }

    @Test
    @DisplayName("a zoom the whole view can be drawn at has no patch")
    void fineEnoughIsEverywhere() {
        final Camera camera = czechiaOnTwoSheets();
        final TileLayer layer = new TileLayer(camera, () -> { });
        layer.setFixedTileZoom(9);
        assertEquals(9, layer.drawZoom());
        assertTrue(layer.patch().isEmpty());

        layer.setFixedTileZoom(null);
        assertTrue(layer.patch().isEmpty(), "nor does a view with no fixed zoom");
    }
}
