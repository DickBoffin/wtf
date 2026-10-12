package net.trdlo.wtf.compose;

import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * A PNG written a band at a time, so the whole raster never exists.
 * <p>
 * <b>Why this is hand-rolled.</b> {@code RasterExporter} was written to band its output through
 * ImageIO's {@code prepareWriteEmpty}/{@code replacePixels} protocol, with a fallback to writing
 * the image in one piece when a writer does not support it. It turns out the PNG writer does not:
 * {@code canWriteEmpty()} and {@code canReplacePixels()} are both false, so <i>every</i> PNG
 * export took the fallback and allocated the entire raster. A 915 x 3000 mm strip at 300 DPI is
 * 0.38 Gpx, which is 1.07 GiB as RGB in a single array – exactly the allocation the design set out
 * to avoid, quietly reintroduced by a fallback that looked like belt and braces.
 * <p>
 * A PNG is a signature, a header chunk, a zlib stream cut into {@code IDAT} chunks and an end
 * marker, so streaming one is a small amount of code and removes the allocation entirely. Peak
 * memory is one band plus the deflater's window.
 * <p>
 * Rows are filtered adaptively with the minimum-sum-of-absolute-differences heuristic that libpng
 * uses. Filtering is not decoration: on map imagery it is the difference between a file that
 * compresses and one that does not.
 */
public final class PngStreamWriter implements Closeable {

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    /** Bigger chunks mean fewer 12-byte headers; 1 MiB is far past where that stops mattering. */
    private static final int IDAT_CHUNK_BYTES = 1 << 20;

    private final OutputStream out;
    private final int width;
    private final int height;
    private final boolean alpha;
    private final int bytesPerPixel;        // RGB8, or RGBA8 with an alpha channel

    private final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
    private final byte[] deflateBuffer = new byte[IDAT_CHUNK_BYTES];
    private final java.io.ByteArrayOutputStream pending = new java.io.ByteArrayOutputStream(IDAT_CHUNK_BYTES);

    private final int stride;
    private final byte[] previous;
    private final byte[] current;
    private final byte[] candidate;
    private final byte[] best;

    private int rowsWritten;
    private boolean closed;

    /**
     * @param dpi     across, written as a {@code pHYs} chunk. A PNG without one is a pile of
     *                pixels and the print software will assume 72.
     * @param dpiDown down, separately: the two edges are rounded to whole pixels separately, so
     *                one resolution for both would make the height a size the paper is not
     */
    public PngStreamWriter(final @NotNull OutputStream out, final int width, final int height,
                           final double dpi, final double dpiDown) throws IOException {
        this(out, width, height, dpi, dpiDown, false);
    }

    /**
     * @param alpha whether the image has an alpha channel, for a file that is laid over something
     *              else: RGBA rather than RGB, and a quarter bigger before compression
     */
    public PngStreamWriter(final @NotNull OutputStream out, final int width, final int height,
                           final double dpi, final double dpiDown, final boolean alpha) throws IOException {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Empty image: " + width + " x " + height);
        }
        this.out = out;
        this.width = width;
        this.height = height;
        this.alpha = alpha;
        this.bytesPerPixel = alpha ? 4 : 3;
        this.stride = width * bytesPerPixel;
        this.previous = new byte[stride];
        this.current = new byte[stride];
        this.candidate = new byte[stride];
        this.best = new byte[stride];

        out.write(SIGNATURE);
        writeHeader();
        if (dpi > 0 && dpiDown > 0) {
            writePhys(dpi, dpiDown);
        }
    }

    private void writeHeader() throws IOException {
        final ByteBuffer b = ByteBuffer.allocate(13);
        b.putInt(width);
        b.putInt(height);
        b.put((byte) 8);        // bit depth
        b.put((byte) (alpha ? 6 : 2));      // color type 6: truecolor with alpha; 2: truecolor RGB
        b.put((byte) 0);        // deflate
        b.put((byte) 0);        // adaptive filtering
        b.put((byte) 0);        // no interlace
        writeChunk("IHDR", b.array());
    }

    private void writePhys(final double dpi, final double dpiDown) throws IOException {
        final ByteBuffer b = ByteBuffer.allocate(9);
        b.putInt((int) Math.round(dpi / 0.0254));
        b.putInt((int) Math.round(dpiDown / 0.0254));
        b.put((byte) 1);        // unit: meters
        writeChunk("pHYs", b.array());
    }

    /**
     * Appends a horizontal band. Its width must match the image; its height is whatever suits the
     * caller. Bands must arrive top to bottom and must total the declared height.
     */
    public void writeBand(final @NotNull BufferedImage band) throws IOException {
        if (band.getWidth() != width) {
            throw new IllegalArgumentException("Band is " + band.getWidth() + " px wide, image is " + width);
        }
        if (rowsWritten + band.getHeight() > height) {
            throw new IllegalArgumentException("Band overruns the declared height of " + height);
        }
        final int[] row = new int[width];
        for (int y = 0; y < band.getHeight(); y++) {
            band.getRGB(0, y, width, 1, row, 0, width);
            for (int x = 0, i = 0; x < width; x++) {
                final int rgb = row[x];
                current[i++] = (byte) (rgb >> 16);
                current[i++] = (byte) (rgb >> 8);
                current[i++] = (byte) rgb;
                if (alpha) {
                    current[i++] = (byte) (rgb >>> 24);
                }
            }
            writeFilteredRow();
            System.arraycopy(current, 0, previous, 0, stride);
            rowsWritten++;
        }
    }

    public int rowsWritten() {
        return rowsWritten;
    }

    // -------------------------------------------------------------------------------------------
    // Filtering. The five PNG filters, and the heuristic that picks one per row.
    // -------------------------------------------------------------------------------------------

    private void writeFilteredRow() throws IOException {
        int bestType = 0;
        long bestScore = Long.MAX_VALUE;
        for (int type = 0; type <= 4; type++) {
            final long score = filterInto(type, candidate);
            if (score < bestScore) {
                bestScore = score;
                bestType = type;
                System.arraycopy(candidate, 0, best, 0, stride);
            }
        }
        deflate(new byte[]{(byte) bestType}, 1);
        deflate(best, stride);
    }

    /** Filters the current row into {@code into} and returns its sum of absolute signed bytes. */
    private long filterInto(final int type, final byte @NotNull [] into) {
        long score = 0;
        for (int i = 0; i < stride; i++) {
            final int raw = current[i] & 0xFF;
            final int left = i >= bytesPerPixel ? current[i - bytesPerPixel] & 0xFF : 0;
            final int up = previous[i] & 0xFF;
            final int upLeft = i >= bytesPerPixel ? previous[i - bytesPerPixel] & 0xFF : 0;

            final int value = switch (type) {
                case 1 -> raw - left;
                case 2 -> raw - up;
                case 3 -> raw - ((left + up) >> 1);
                case 4 -> raw - paeth(left, up, upLeft);
                default -> raw;
            };
            final byte b = (byte) value;
            into[i] = b;
            score += Math.abs((int) b);
        }
        return score;
    }

    private static int paeth(final int a, final int b, final int c) {
        final int p = a + b - c;
        final int pa = Math.abs(p - a);
        final int pb = Math.abs(p - b);
        final int pc = Math.abs(p - c);
        return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
    }

    // -------------------------------------------------------------------------------------------
    // Deflating into IDAT chunks
    // -------------------------------------------------------------------------------------------

    private void deflate(final byte @NotNull [] data, final int length) throws IOException {
        deflater.setInput(data, 0, length);
        while (!deflater.needsInput()) {
            drain(false);
        }
    }

    private void drain(final boolean finishing) throws IOException {
        final int n = finishing
                ? deflater.deflate(deflateBuffer, 0, deflateBuffer.length, Deflater.NO_FLUSH)
                : deflater.deflate(deflateBuffer);
        if (n > 0) {
            pending.write(deflateBuffer, 0, n);
        }
        if (pending.size() >= IDAT_CHUNK_BYTES) {
            flushIdat();
        }
    }

    private void flushIdat() throws IOException {
        if (pending.size() == 0) {
            return;
        }
        writeChunk("IDAT", pending.toByteArray());
        pending.reset();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (rowsWritten != height) {
                throw new IOException("PNG declared " + height + " rows but only " + rowsWritten
                        + " were written");
            }
            deflater.finish();
            while (!deflater.finished()) {
                final int n = deflater.deflate(deflateBuffer);
                if (n > 0) {
                    pending.write(deflateBuffer, 0, n);
                }
                if (pending.size() >= IDAT_CHUNK_BYTES) {
                    flushIdat();
                }
            }
            flushIdat();
            writeChunk("IEND", new byte[0]);
            out.flush();
        } finally {
            deflater.end();
        }
    }

    private void writeChunk(final @NotNull String type, final byte @NotNull [] data) throws IOException {
        final byte[] name = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        final ByteBuffer header = ByteBuffer.allocate(8);
        header.putInt(data.length);
        header.put(name);
        out.write(header.array());
        out.write(data);

        final CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        final ByteBuffer tail = ByteBuffer.allocate(4);
        tail.putInt((int) crc.getValue());
        out.write(tail.array());
    }
}
