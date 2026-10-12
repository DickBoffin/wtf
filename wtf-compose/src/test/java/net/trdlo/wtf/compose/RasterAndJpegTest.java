package net.trdlo.wtf.compose;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The streaming PNG encoder, and the JPEG header reader the JPEG passthrough depends on.
 * <p>
 * The encoder is hand-rolled, so "ImageIO reads it back identically" is the only claim worth
 * making about it, and it is made pixel by pixel rather than by comparing file sizes.
 */
class RasterAndJpegTest {

    // -------------------------------------------------------------------------------------------
    // PngStreamWriter
    // -------------------------------------------------------------------------------------------

    private static BufferedImage noise(final int width, final int height, final long seed) {
        final BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final Random random = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // structure plus noise: flat color would compress on any filter and prove nothing
                final int base = ((x / 8) * 20 % 256) << 16 | ((y / 8) * 13 % 256) << 8 | (x + y) % 256;
                img.setRGB(x, y, base ^ (random.nextInt(16) * 0x010101));
            }
        }
        return img;
    }

    private static byte[] encodeInBands(final BufferedImage whole, final int bandRows, final double dpi)
            throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (final PngStreamWriter png =
                     new PngStreamWriter(out, whole.getWidth(), whole.getHeight(), dpi, dpi)) {
            for (int top = 0; top < whole.getHeight(); top += bandRows) {
                final int rows = Math.min(bandRows, whole.getHeight() - top);
                png.writeBand(whole.getSubimage(0, top, whole.getWidth(), rows));
            }
        }
        return out.toByteArray();
    }

    private static void assertSamePixels(final BufferedImage expected, final BufferedImage actual) {
        assertEquals(expected.getWidth(), actual.getWidth(), "width");
        assertEquals(expected.getHeight(), actual.getHeight(), "height");
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                if ((expected.getRGB(x, y) & 0xFFFFFF) != (actual.getRGB(x, y) & 0xFFFFFF)) {
                    fail(String.format("pixel %d,%d: expected %06X but was %06X",
                            x, y, expected.getRGB(x, y) & 0xFFFFFF, actual.getRGB(x, y) & 0xFFFFFF));
                }
            }
        }
    }

    @Test
    void roundTripsPixelForPixelAcrossBandBoundaries() throws IOException {
        final BufferedImage source = noise(137, 200, 42);
        // 17 does not divide 200, so the last band is short and the boundaries land on odd rows –
        // which is exactly where a filter that reaches into the previous row goes wrong
        final BufferedImage back = ImageIO.read(new java.io.ByteArrayInputStream(
                encodeInBands(source, 17, 300)));
        assertNotNull(back, "ImageIO could not read what we wrote");
        assertSamePixels(source, back);
    }

    /** What a missing tile under TRANSPARENT is written with: the alpha goes in and comes back. */
    @Test
    void roundTripsAnAlphaChannel() throws IOException {
        final BufferedImage source = new BufferedImage(61, 40, BufferedImage.TYPE_INT_ARGB);
        final Random random = new Random(3);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                source.setRGB(x, y, random.nextInt());
            }
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (final PngStreamWriter png = new PngStreamWriter(out, 61, 40, 0, 0, true)) {
            for (int top = 0; top < 40; top += 7) {
                png.writeBand(source.getSubimage(0, top, 61, Math.min(7, 40 - top)));
            }
        }
        final BufferedImage back = ImageIO.read(new java.io.ByteArrayInputStream(out.toByteArray()));
        assertNotNull(back, "ImageIO could not read what we wrote");
        assertTrue(back.getColorModel().hasAlpha(), "an RGBA PNG");
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                assertEquals(source.getRGB(x, y), back.getRGB(x, y), "pixel " + x + "," + y);
            }
        }
    }

    @Test
    void oneBandAndManyBandsProduceTheSameBytes() throws IOException {
        final BufferedImage source = noise(64, 96, 7);
        assertArrayEquals(encodeInBands(source, 96, 0), encodeInBands(source, 8, 0),
                "how the caller chunks its bands must not change the file");
    }

    @Test
    void writesThePhysChunkSoPrintSoftwareKnowsTheDpi() throws IOException {
        final byte[] png = encodeInBands(noise(16, 16, 1), 16, 300);
        assertTrue(indexOf(png, "pHYs".getBytes(java.nio.charset.StandardCharsets.US_ASCII)) > 0,
                "a PNG without pHYs is a pile of pixels and the printer will guess 72 DPI");

        // 300 DPI is 11811 pixels per meter
        final int at = indexOf(png, "pHYs".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        final int perMeter = ((png[at + 4] & 0xFF) << 24) | ((png[at + 5] & 0xFF) << 16)
                | ((png[at + 6] & 0xFF) << 8) | (png[at + 7] & 0xFF);
        assertEquals(11811, perMeter);
        assertEquals(1, png[at + 12], "unit must be meters");
    }

    @Test
    void omitsPhysWhenTheDpiIsUnknown() throws IOException {
        final byte[] png = encodeInBands(noise(8, 8, 1), 8, 0);
        assertEquals(-1, indexOf(png, "pHYs".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }

    @Test
    void filteringActuallyCompresses() throws IOException {
        // gradients are the case adaptive filtering exists for: near-zero after Sub or Up
        final BufferedImage gradient = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 512; y++) {
            for (int x = 0; x < 512; x++) {
                gradient.setRGB(x, y, (x / 2) << 16 | (y / 2) << 8 | 0x40);
            }
        }
        final long raw = 512L * 512 * 3;
        final long written = encodeInBands(gradient, 64, 0).length;
        assertTrue(written < raw / 20,
                () -> "expected heavy compression on a gradient, got " + written + " of " + raw);
    }

    @Test
    void refusesToFinishAnIncompleteImage() throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final PngStreamWriter png = new PngStreamWriter(out, 10, 10, 0, 0);
        png.writeBand(noise(10, 4, 1));
        // A PNG that claims ten rows and holds four is a corrupt file; better to fail loudly
        assertThrows(IOException.class, png::close);
    }

    @Test
    void refusesABandThatOverrunsOrMismatches() throws IOException {
        try (final OutputStream sink = OutputStream.nullOutputStream()) {
            final PngStreamWriter png = new PngStreamWriter(sink, 10, 10, 0, 0);
            assertThrows(IllegalArgumentException.class, () -> png.writeBand(noise(11, 2, 1)));
            assertThrows(IllegalArgumentException.class, () -> png.writeBand(noise(10, 11, 1)));
        }
    }

    @Test
    void aSingleRowImageWorks() throws IOException {
        final BufferedImage row = noise(40, 1, 3);
        assertSamePixels(row, ImageIO.read(new java.io.ByteArrayInputStream(encodeInBands(row, 1, 0))));
    }

    private static int indexOf(final byte[] haystack, final byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    // -------------------------------------------------------------------------------------------
    // The JPEG header reader behind the JPEG passthrough
    // -------------------------------------------------------------------------------------------

    private static byte[] jpeg(final int width, final int height) throws IOException {
        final BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = img.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, width, height);
        g.dispose();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", out);
        return out.toByteArray();
    }

    @Test
    void readsTheJpegFrameHeaderWithoutDecoding() throws IOException {
        final PdfTileImages.Jpeg header = PdfTileImages.Jpeg.read(jpeg(256, 192));
        assertNotNull(header);
        assertEquals(256, header.width());
        assertEquals(192, header.height());
        assertEquals(3, header.components());
        assertEquals(8, header.precision());
    }

    @Test
    void returnsNullRatherThanGuessingOnSomethingThatIsNotAJpeg() {
        assertNull(PdfTileImages.Jpeg.read(new byte[]{(byte) 0xFF, (byte) 0xD8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}));
        assertNull(PdfTileImages.Jpeg.read(new byte[]{1, 2, 3}));
    }

    // -------------------------------------------------------------------------------------------
    // TIFF, through the composer's raster path
    // -------------------------------------------------------------------------------------------

    /**
     * The measurement the TIFF policy rests on: the writer can replace pixels in an uncompressed
     * image and not in a compressed one, so banding and compression cannot both be had.
     * {@code RasterExporter} therefore bands large sheets uncompressed and compresses small ones
     * in one piece. If a future JDK lifts this, that choice is worth revisiting, and this test is
     * what will say so.
     */
    @Test
    void tiffCanBandOrCompressButNotBoth(@TempDir final Path dir) throws IOException {
        assertFalse(canReplacePixels(dir.resolve("compressed.tif"), true),
                "if this starts passing, large TIFFs can be banded AND compressed");
        assertTrue(canReplacePixels(dir.resolve("plain.tif"), false),
                "banding a large sheet depends on this");
    }

    private static boolean canReplacePixels(final Path file, final boolean compressed)
            throws IOException {
        final var writers = ImageIO.getImageWritersByFormatName("tiff");
        assertTrue(writers.hasNext(), "the JDK has shipped a TIFF writer since Java 9");
        final var writer = writers.next();
        try (final var ios = ImageIO.createImageOutputStream(file.toFile())) {
            writer.setOutput(ios);
            final var param = writer.getDefaultWriteParam();
            if (compressed) {
                param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionType("Deflate");
            }
            assertTrue(writer.canWriteEmpty(), "the incremental protocol starts here");
            writer.prepareWriteEmpty(null,
                    javax.imageio.ImageTypeSpecifier.createFromBufferedImageType(
                            BufferedImage.TYPE_INT_RGB),
                    64, 64, null, null, param);
            writer.endWriteEmpty();
            return writer.canReplacePixels(0);
        } finally {
            writer.dispose();
        }
    }

    @Test
    void tiffRoundTripsThroughImageIo(@TempDir final Path dir) throws IOException {
        final BufferedImage source = noise(70, 50, 11);
        final Path file = dir.resolve("a.tif");
        assertTrue(ImageIO.write(source, "tiff", file.toFile()));
        assertSamePixels(source, ImageIO.read(file.toFile()));
        assertTrue(Files.size(file) > 0);
    }
}
