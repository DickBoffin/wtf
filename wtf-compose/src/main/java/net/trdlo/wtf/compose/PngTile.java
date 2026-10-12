package net.trdlo.wtf.compose;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A PNG read far enough to decide whether its bytes can go straight into a PDF, and no further.
 * <p>
 * A PNG {@code IDAT} stream is zlib-compressed scanlines each prefixed by a filter byte, which is
 * exactly what PDF's {@code FlateDecode} with {@code /Predictor 15} expects – not a coincidence,
 * it is where PDF got it from. So for most tiles nothing is decoded and nothing is re-encoded.
 * <p>
 * The exception that matters: <b>RGBA cannot pass through</b>. PNG interleaves alpha with color in
 * the same samples and PDF wants it as a separate {@code /SMask}, and five of the sixteen provider
 * themes probed are RGBA. See docs/design/compose-module.md §6.3.
 */
public record PngTile(int width, int height, int bitDepth, int colorType, boolean interlaced,
                      byte @Nullable [] palette, byte @Nullable [] transparency,
                      byte @NotNull [] idat) {

    public static final int GRAY = 0, RGB = 2, INDEXED = 3, GRAY_ALPHA = 4, RGBA = 6;

    /** Samples per pixel for this color type – {@code /Colors} in a PDF {@code /DecodeParms}. */
    public int components() {
        return switch (colorType) {
            case GRAY, INDEXED -> 1;
            case RGB -> 3;
            case GRAY_ALPHA -> 2;
            case RGBA -> 4;
            default -> throw new IllegalStateException("Unknown PNG color type " + colorType);
        };
    }

    public boolean hasAlphaChannel() {
        return colorType == GRAY_ALPHA || colorType == RGBA;
    }

    /**
     * Whether the {@code IDAT} can be copied into a PDF image stream unchanged.
     * <p>
     * Interlaced is out because PDF has no equivalent of Adam7; alpha is out because PDF has no
     * interleaved-alpha image; {@code tRNS} is out for now because it would need an {@code /SMask}
     * built from the palette, and no shipped base-map theme uses it.
     */
    public boolean canPassThrough() {
        return !interlaced && !hasAlphaChannel() && transparency == null
                && (colorType == GRAY || colorType == RGB || colorType == INDEXED);
    }

    /** Why not, for the log line that explains a slow export. */
    public @NotNull String passThroughObstacle() {
        if (interlaced) {
            return "interlaced";
        }
        if (hasAlphaChannel()) {
            return "alpha channel";
        }
        if (transparency != null) {
            return "tRNS transparency";
        }
        return "color type " + colorType;
    }

    public int paletteEntries() {
        return palette == null ? 0 : palette.length / 3;
    }

    /** The palette with an effect applied – the whole of "effects are free on indexed tiles". */
    public byte @NotNull [] transformedPalette(final @NotNull TileEffect effect) {
        if (palette == null) {
            throw new IllegalStateException("Not an indexed PNG");
        }
        if (effect.isIdentity()) {
            return palette;
        }
        final byte[] out = new byte[palette.length];
        for (int i = 0; i + 2 < palette.length; i += 3) {
            final int rgb = ((palette[i] & 0xFF) << 16) | ((palette[i + 1] & 0xFF) << 8) | (palette[i + 2] & 0xFF);
            final int t = effect.apply(rgb);
            out[i] = (byte) (t >> 16);
            out[i + 1] = (byte) (t >> 8);
            out[i + 2] = (byte) t;
        }
        return out;
    }

    // -------------------------------------------------------------------------------------------

    /** Reads the chunks this cares about. Does not verify CRCs: a corrupt tile fails later, loudly. */
    public static @NotNull PngTile read(final byte @NotNull [] d) throws IOException {
        if (d.length < 8 || (d[0] & 0xFF) != 0x89 || d[1] != 'P' || d[2] != 'N' || d[3] != 'G') {
            throw new IOException("Not a PNG");
        }
        int w = 0, h = 0, bd = 0, ct = 0, il = 0;
        byte[] plte = null;
        byte[] trns = null;
        final List<byte[]> idatParts = new ArrayList<>();

        int i = 8;
        while (i + 8 <= d.length) {
            final int len = ByteBuffer.wrap(d, i, 4).getInt();
            if (len < 0 || i + 12 + len > d.length) {
                throw new IOException("Truncated PNG chunk at offset " + i);
            }
            final String type = new String(d, i + 4, 4, StandardCharsets.US_ASCII);
            final int at = i + 8;
            switch (type) {
                case "IHDR" -> {
                    w = ByteBuffer.wrap(d, at, 4).getInt();
                    h = ByteBuffer.wrap(d, at + 4, 4).getInt();
                    bd = d[at + 8] & 0xFF;
                    ct = d[at + 9] & 0xFF;
                    il = d[at + 12] & 0xFF;
                }
                case "PLTE" -> plte = Arrays.copyOfRange(d, at, at + len);
                case "tRNS" -> trns = Arrays.copyOfRange(d, at, at + len);
                case "IDAT" -> idatParts.add(Arrays.copyOfRange(d, at, at + len));
                default -> { }
            }
            i = at + len + 4;
            if ("IEND".equals(type)) {
                break;
            }
        }
        if (w <= 0 || h <= 0) {
            throw new IOException("PNG has no IHDR");
        }
        if (idatParts.isEmpty()) {
            throw new IOException("PNG has no IDAT");
        }
        return new PngTile(w, h, bd, ct, il != 0, plte, trns, concat(idatParts));
    }

    private static byte @NotNull [] concat(final @NotNull List<byte[]> parts) {
        int total = 0;
        for (final byte[] p : parts) {
            total += p.length;
        }
        final byte[] out = new byte[total];
        int at = 0;
        for (final byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }
}
