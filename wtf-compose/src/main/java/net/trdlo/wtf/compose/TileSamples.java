package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.Refusal;
import net.trdlo.wtf.tiles.TileFormat;
import net.trdlo.wtf.tiles.TileKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * A tile decoded, and its pixels as its samples are.
 * <p>
 * Every exporter decodes a tile through {@link #decode} – the PDF's decode path and the raster –
 * so that the two read the same tiles and refuse the same ones.
 * <p>
 * {@code BufferedImage.getRGB} does not read a one-channel image's sample: it converts it from
 * Java's linear gray to sRGB, so a sample of 128 comes back as 188. Java2D's own drawing copies
 * the sample, a PDF viewer shows a {@code DeviceGray} sample as it is, and so does every other
 * program a tile passes through – so the sample is the truth, and a gray tile that goes through
 * {@code getRGB} on the way to the printer comes out much lighter than the same tile passed
 * through untouched. This reads the samples off the raster for a gray image, with or without
 * alpha, and through the color model for everything else, whose models are sRGB and convert
 * nothing; and what is written back goes into an {@code INT_RGB} or {@code INT_ARGB} image,
 * whose {@code setRGB} and {@code getRGB} are a plain pack and unpack.
 */
final class TileSamples {

    private TileSamples() {
    }

    /**
     * A tile's bytes as an image, or a refusal naming the tile when no decoder can read them: none
     * knows their format – none in the JDK knows WebP, and this build carries none – or the one
     * that does gives up on them, as on a twelve-bit JPEG or a PNG cut short. Either way no export
     * can print the tile, and another map is what a person can do about it.
     * <p>
     * <b>From memory.</b> ImageIO's own way in from a stream spools every tile through a temporary
     * file first, and a temporary folder that is full or cannot be written fails it with the same
     * {@code IIOException} a damaged tile gives – so the refusal would have sent somebody to
     * another map over their disk. Read from memory, a decoder that gives up means the tile and
     * nothing else; and a 256-pixel PNG took 1.0 ms here where the temporary file made it 3.3.
     *
     * @param key the tile, which the refusal names; null only for a caller that has none, and then
     *            the refusal names it "?"
     * @throws Refusal {@link ComposeReason#TILE_UNREADABLE}, with the decoder's own error beneath
     *                 it when it had one
     */
    static @NotNull BufferedImage decode(final @Nullable TileKey key, final byte @NotNull [] data) {
        final BufferedImage img;
        try {
            final ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(data));
            img = ImageIO.read(in);
            if (img == null) {
                in.close();             // ImageIO closes it only when a reader took it
            }
        } catch (final IOException ex) {
            throw unreadable(key, data, ex);
        }
        if (img == null) {
            throw unreadable(key, data, null);
        }
        return img;
    }

    private static @NotNull Refusal unreadable(final @Nullable TileKey key, final byte @NotNull [] data,
                                               final @Nullable IOException cause) {
        return new Refusal(cause, ComposeReason.TILE_UNREADABLE, key == null ? "?" : key.toString(),
                TileFormat.sniff(data));
    }

    /** One gray channel, with or without alpha: the images {@code getRGB} misreads. */
    static boolean isGray(final @NotNull BufferedImage img) {
        return img.getColorModel() instanceof ComponentColorModel
                && img.getColorModel().getColorSpace().getType() == ColorSpace.TYPE_GRAY;
    }

    /** Packed ARGB, row by row, as the samples are, with a 16-bit sample cut to its top byte. */
    static int @NotNull [] argb(final @NotNull BufferedImage img) {
        final int w = img.getWidth(), h = img.getHeight();
        if (!isGray(img)) {
            return img.getRGB(0, 0, w, h, null, 0, w);
        }
        final Raster raster = img.getRaster();
        final ColorModel cm = img.getColorModel();
        final int[] gray = raster.getSamples(0, 0, w, h, 0, (int[]) null);
        final int[] alpha = cm.hasAlpha() ? raster.getSamples(0, 0, w, h, 1, (int[]) null) : null;
        final int bits = cm.getComponentSize(0);
        final int alphaBits = alpha == null ? 8 : cm.getComponentSize(1);
        final int[] out = new int[w * h];
        for (int i = 0; i < out.length; i++) {
            final int g = eightBits(gray[i], bits);
            final int a = alpha == null ? 0xFF : eightBits(alpha[i], alphaBits);
            out[i] = a << 24 | g << 16 | g << 8 | g;
        }
        return out;
    }

    private static int eightBits(final int sample, final int bits) {
        if (bits == 8) {
            return sample;
        }
        return bits > 8 ? sample >> (bits - 8) : sample * 255 / ((1 << bits) - 1);
    }

    /**
     * An image of these pixels that Java2D draws and PDFBox writes as they are.
     *
     * @param alpha whether the image keeps an alpha channel, {@code INT_ARGB} rather than {@code INT_RGB}
     */
    static @NotNull BufferedImage image(final int @NotNull [] argb, final int w, final int h, final boolean alpha) {
        final BufferedImage img = new BufferedImage(w, h, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, w, h, argb, 0, w);
        return img;
    }

    /** Whether every pixel's alpha is 255. */
    static boolean isOpaque(final int @NotNull [] argb) {
        for (final int p : argb) {
            if (p >>> 24 != 0xFF) {
                return false;
            }
        }
        return true;
    }
}
