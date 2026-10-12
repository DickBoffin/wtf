package net.trdlo.wtf.studio.map;

import net.trdlo.wtf.tiles.TileKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An invalidate after a download neither doubles a decode already out nor lets its
 * landing count as news, without the toolkit {@link TileImageCache}'s decodes need.
 */
class InFlightDecodesTest {

    private static final TileKey KEY = new TileKey("osm", "standard", 5, 3, 3);

    @Test
    @DisplayName("one decode per key, freed by its landing")
    void oneDecodePerKey() {
        final InFlightDecodes decodes = new InFlightDecodes();
        final int startedAt = decodes.generation();
        assertTrue(decodes.start(KEY));
        assertFalse(decodes.start(KEY));
        assertEquals(1, decodes.pending());
        assertTrue(decodes.land(KEY, startedAt));
        assertEquals(0, decodes.pending());
        assertTrue(decodes.start(KEY));
    }

    @Test
    @DisplayName("after an invalidate the decode still out is not doubled, and its landing is stale")
    void aStaleLandingAfterAnInvalidate() {
        final InFlightDecodes decodes = new InFlightDecodes();
        final int before = decodes.generation();
        assertTrue(decodes.start(KEY));

        decodes.bump();                              // a download landed

        assertFalse(decodes.start(KEY), "the next frame does not decode it a second time");
        assertEquals(1, decodes.pending(), "and the capture still waits for it");
        assertFalse(decodes.land(KEY, before), "what it found missing may have just arrived");
        assertEquals(0, decodes.pending());

        final int after = decodes.generation();
        assertTrue(decodes.start(KEY), "the repaint after it asks again");
        assertTrue(decodes.land(KEY, after));
    }
}
