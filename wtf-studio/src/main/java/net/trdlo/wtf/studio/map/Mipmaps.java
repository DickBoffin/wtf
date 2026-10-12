package net.trdlo.wtf.studio.map;

import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A tile halved, and halved again, for drawing it smaller than half its size.
 * <p>
 * The canvas's smoothing is linear: it blends the four pixels nearest each screen pixel, which is
 * right for a tile drawn larger and for one drawn a little smaller. Drawn at a third of its size –
 * the 1:1 preview, where a print's tile pixels are finer than the screen's – it skips most of the
 * tile and the map looks like nearest neighbor: broken lines, sparkling text. Each half here
 * averages all four pixels under it, so the last step the canvas makes is under a factor of two.
 * <p>
 * The halves are kept as long as the tile they come from is, and only made when asked for.
 */
final class Mipmaps {

    private static final Map<Image, List<Image>> LEVELS = new WeakHashMap<>();

    private Mipmaps() {
    }

    /**
     * The tile, or the smallest of its halves still at least {@code devicePx} wide.
     *
     * @param devicePx the width it is drawn at, in the screen's own pixels
     */
    static @NotNull Image forWidth(final @NotNull Image img, final double devicePx) {
        if (img.getWidth() / 2 < devicePx || img.getWidth() < 2 || img.getHeight() < 2) {
            return img;
        }
        final List<Image> levels = LEVELS.computeIfAbsent(img, k -> new ArrayList<>());
        Image level = img;
        for (int i = 0; level.getWidth() / 2 >= devicePx && level.getWidth() >= 2 && level.getHeight() >= 2; i++) {
            if (i == levels.size()) {
                levels.add(half(level));
            }
            level = levels.get(i);
        }
        return level;
    }

    /** Each pixel the average of the four under it, weighted by their alpha. */
    static @NotNull Image half(final @NotNull Image src) {
        final int w = (int) src.getWidth(), h = (int) src.getHeight();
        final int[] in = new int[w * h];
        src.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), in, 0, w);
        final int hw = w / 2, hh = h / 2;
        final int[] out = new int[hw * hh];
        for (int y = 0; y < hh; y++) {
            for (int x = 0; x < hw; x++) {
                final int i = 2 * y * w + 2 * x;
                out[y * hw + x] = average(in[i], in[i + 1], in[i + w], in[i + w + 1]);
            }
        }
        final WritableImage dst = new WritableImage(hw, hh);
        dst.getPixelWriter().setPixels(0, 0, hw, hh, PixelFormat.getIntArgbInstance(), out, 0, hw);
        return dst;
    }

    private static int average(final int... argb) {
        int a = 0, r = 0, g = 0, b = 0;
        for (final int p : argb) {
            final int pa = p >>> 24;
            a += pa;
            r += ((p >> 16) & 0xff) * pa;
            g += ((p >> 8) & 0xff) * pa;
            b += (p & 0xff) * pa;
        }
        if (a == 0) {
            return 0;
        }
        return ((a + 2) / 4) << 24 | (r + a / 2) / a << 16 | (g + a / 2) / a << 8 | (b + a / 2) / a;
    }
}
