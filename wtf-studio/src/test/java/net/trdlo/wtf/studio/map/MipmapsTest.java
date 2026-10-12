package net.trdlo.wtf.studio.map;

import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import net.trdlo.wtf.studio.Headless;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A tile drawn at a third of its size is drawn from a half that averages, not from the tile. */
class MipmapsTest {

    @BeforeAll
    static void toolkit() {
        Headless.start();
    }

    /** One-pixel black and white stripes: the pattern a sampling filter breaks into bands. */
    private static WritableImage stripes(final int size) {
        final WritableImage img = new WritableImage(size, size);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                img.getPixelWriter().setColor(x, y, x % 2 == 0 ? Color.BLACK : Color.WHITE);
            }
        }
        return img;
    }

    @Test
    void stripesHalveToGray() {
        final Image half = Mipmaps.half(stripes(8));
        assertEquals(4, half.getWidth());
        final Color c = half.getPixelReader().getColor(1, 2);
        assertEquals(0.5, c.getRed(), 0.01);
        assertEquals(1.0, c.getOpacity(), 0.01);
    }

    @Test
    void aTransparentPixelLendsNoColor() {
        final WritableImage img = new WritableImage(2, 2);
        img.getPixelWriter().setColor(0, 0, Color.RED);
        final Color c = Mipmaps.half(img).getPixelReader().getColor(0, 0);
        assertEquals(1.0, c.getRed(), 0.01);
        assertEquals(0.25, c.getOpacity(), 0.01);
    }

    @Test
    void theLevelIsTheSmallestAtLeastAsWide() {
        final WritableImage tile = stripes(256);
        assertSame(tile, Mipmaps.forWidth(tile, 200));
        assertSame(tile, Mipmaps.forWidth(tile, 128.5));
        assertEquals(128, Mipmaps.forWidth(tile, 128).getWidth());
        assertEquals(128, Mipmaps.forWidth(tile, 85).getWidth());
        assertEquals(64, Mipmaps.forWidth(tile, 60).getWidth());
        assertSame(Mipmaps.forWidth(tile, 60), Mipmaps.forWidth(tile, 50), "made once");
    }
}
