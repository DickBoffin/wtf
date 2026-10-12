package net.trdlo.wtf.compose;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The single most valuable test in the project.
 * <p>
 * The embedded-PDF trick is the one place where a subtle mistake produces a file that opens, looks
 * plausible and is wrong: a palette off by one, a {@code /DecodeParms} with the wrong
 * {@code /Columns}, a predictor the reader disagrees about. None of that shows up until somebody
 * has paid for five meters of paper.
 * <p>
 * So: build a PDF the way the exporter does, read it back with an independent parse, and compare
 * every pixel to the source.
 */
class PdfRoundTripTest {

    /** An indexed PNG, which is what a rendered map theme is. */
    private static byte[] indexedPng(final int size, final long seed) throws IOException {
        final BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_BYTE_INDEXED);
        final Random r = new Random(seed);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                // blocky, like a map: large flat areas with edges, not noise
                final int c = ((x / 8) * 31 + (y / 8) * 17 + (int) seed) % 216;
                img.setRGB(x, y, ((c / 36) * 51 << 16) | (((c / 6) % 6) * 51 << 8) | ((c % 6) * 51));
            }
        }
        img.setRGB(0, 0, 0x000000);
        img.setRGB(size - 1, size - 1, 0xFFFFFF);
        r.nextInt();
        return TestJobs.png(img);
    }

    private static byte[] truecolorPng(final int size) throws IOException {
        final BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                img.setRGB(x, y, (x * 255 / size) << 16 | (y * 255 / size) << 8 | 0x40);
            }
        }
        return TestJobs.png(img);
    }

    private static byte[] jpeg(final int size) throws IOException {
        final BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                img.setRGB(x, y, ((x / 16) * 40) << 16 | ((y / 16) * 40) << 8 | 0x80);
            }
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    /** Builds a one-page PDF holding these tiles, exactly as the exporter does. */
    private static Path writePdf(final Path dir, final String name, final TileEffect effect,
                                 final byte[]... tiles) throws IOException {
        final Path out = dir.resolve(name);
        try (final PDDocument doc = new PDDocument()) {
            final PDPage page = new PDPage(new PDRectangle(256 * tiles.length, 256));
            doc.addPage(page);
            final PdfTileImages images = new PdfTileImages(doc);
            try (final PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                for (int i = 0; i < tiles.length; i++) {
                    cs.drawImage(images.of(tiles[i], effect), i * 256f, 0f, 256f, 256f);
                }
            }
            doc.save(out.toFile());
        }
        return out;
    }

    private static void assertPixelIdentical(final BufferedImage expected, final BufferedImage actual,
                                             final String what) {
        assertNotNull(actual, what + ": nothing came back");
        assertEquals(expected.getWidth(), actual.getWidth(), what + ": width");
        assertEquals(expected.getHeight(), actual.getHeight(), what + ": height");
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                final int e = expected.getRGB(x, y) & 0xFFFFFF;
                final int a = actual.getRGB(x, y) & 0xFFFFFF;
                if (e != a) {
                    fail(String.format("%s: pixel %d,%d is %06X, expected %06X", what, x, y, a, e));
                }
            }
        }
    }

    private static BufferedImage firstImage(final Path pdf) throws IOException {
        try (final PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            final PDResources res = doc.getPage(0).getResources();
            for (final COSName n : res.getXObjectNames()) {
                final PDXObject xo = res.getXObject(n);
                if (xo instanceof final PDImageXObject img) {
                    return img.getImage();
                }
            }
        }
        return null;
    }

    @Test
    @DisplayName("an indexed PNG survives the trip through a PDF pixel for pixel")
    void indexedPassthrough(@TempDir final Path dir) throws IOException {
        final byte[] tile = indexedPng(256, 7);
        final Path pdf = writePdf(dir, "indexed.pdf", TileEffect.NONE, tile);
        assertPixelIdentical(ImageIO.read(new java.io.ByteArrayInputStream(tile)),
                firstImage(pdf), "indexed PNG");
    }

    @Test
    @DisplayName("a truecolor PNG survives it too")
    void truecolorPassthrough(@TempDir final Path dir) throws IOException {
        final byte[] tile = truecolorPng(128);
        final Path pdf = writePdf(dir, "rgb.pdf", TileEffect.NONE, tile);
        assertPixelIdentical(ImageIO.read(new java.io.ByteArrayInputStream(tile)),
                firstImage(pdf), "RGB PNG");
    }

    @Test
    @DisplayName("a JPEG goes in whole and comes back identical")
    void jpegPassthrough(@TempDir final Path dir) throws IOException {
        final byte[] tile = jpeg(128);
        final Path pdf = writePdf(dir, "jpeg.pdf", TileEffect.NONE, tile);
        // the same DCT data decoded by the same decoder must give the same pixels
        assertPixelIdentical(ImageIO.read(new java.io.ByteArrayInputStream(tile)),
                firstImage(pdf), "JPEG");
    }

    @Test
    @DisplayName("the passthrough really is a passthrough: no pixel is decoded")
    void nothingIsReEncoded(@TempDir final Path dir) throws IOException {
        try (final PDDocument doc = new PDDocument()) {
            final PdfTileImages images = new PdfTileImages(doc);
            images.of(indexedPng(256, 1), TileEffect.NONE);
            images.of(truecolorPng(64), TileEffect.NONE);
            images.of(jpeg(64), TileEffect.NONE);
            assertEquals(2, images.count(PdfTileImages.Path.FLATE_PASSTHROUGH));
            assertEquals(1, images.count(PdfTileImages.Path.DCT_PASSTHROUGH));
            assertEquals(0, images.count(PdfTileImages.Path.DECODED), images.report());
        }
    }

    @Test
    @DisplayName("an effect on an indexed tile transforms the palette and leaves the IDAT alone")
    void effectOnPaletteIsExact(@TempDir final Path dir) throws IOException {
        final byte[] tile = indexedPng(64, 3);
        final TileEffect effect = TileEffect.FADED;

        final Path pdf = writePdf(dir, "faded.pdf", effect, tile);
        try (final PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            assertEquals(1, doc.getNumberOfPages());
        }

        // what the PDF shows must equal the effect applied to every source pixel
        final BufferedImage src = ImageIO.read(new java.io.ByteArrayInputStream(tile));
        final BufferedImage expected = new BufferedImage(src.getWidth(), src.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                expected.setRGB(x, y, effect.apply(src.getRGB(x, y) & 0xFFFFFF));
            }
        }
        assertPixelIdentical(expected, firstImage(pdf),
                "a palette transform must equal a per-pixel transform, because every pixel is an index");
    }

    @Test
    @DisplayName("an effect on an indexed tile still costs no re-encode")
    void effectStaysCheap() throws IOException {
        try (final PDDocument doc = new PDDocument()) {
            final PdfTileImages images = new PdfTileImages(doc);
            images.of(indexedPng(64, 5), TileEffect.FADED);
            assertEquals(1, images.count(PdfTileImages.Path.FLATE_PASSTHROUGH), images.report());
            assertEquals(0, images.count(PdfTileImages.Path.DECODED));
        }
    }

    @Test
    @DisplayName("lightness and contrast on a JPEG ride in a /Decode array, not a re-encode")
    void decodeArrayCarriesLinearEffects() throws IOException {
        try (final PDDocument doc = new PDDocument()) {
            final PdfTileImages images = new PdfTileImages(doc);
            final PDImageXObject img = images.of(jpeg(64), new TileEffect(1, 0.3, -0.4));
            assertEquals(1, images.count(PdfTileImages.Path.DCT_PASSTHROUGH), images.report());
            assertNotNull(img.getCOSObject().getItem(COSName.DECODE), "a /Decode array must be set");
        }
    }

    @Test
    @DisplayName("saturation on a JPEG cannot ride in the dictionary, so it re-encodes and says so")
    void saturationOnJpegFallsBack() throws IOException {
        try (final PDDocument doc = new PDDocument()) {
            final PdfTileImages images = new PdfTileImages(doc);
            images.of(jpeg(64), new TileEffect(0, 0, 0));
            assertEquals(1, images.count(PdfTileImages.Path.DECODED), images.report());
            assertTrue(images.report().contains("saturation"), images.report());
        }
    }

    @Test
    @DisplayName("identical bytes under identical treatment become one image, different ones do not")
    void deduplication() throws IOException {
        final byte[] tile = indexedPng(64, 9);
        try (final PDDocument doc = new PDDocument()) {
            final PdfTileImages images = new PdfTileImages(doc);
            final PDImageXObject a = images.of(tile, TileEffect.NONE);
            final PDImageXObject b = images.of(tile.clone(), TileEffect.NONE);
            assertSame(a, b, "the same tile twice is one XObject");

            final PDImageXObject faded = images.of(tile, TileEffect.FADED);
            assertNotSame(a, faded,
                    "the same tile under a different effect must NOT be shared - "
                            + "that would wash out the wrong region, subtly enough to reach the printer");
            assertEquals(2, images.distinctImages());
        }
    }

    /** The key the XObjects were shared by until it was SHA-256: a polynomial over the bytes. */
    private static long polynomial(final byte[] b) {
        long h = 1125899906842597L;
        for (final byte x : b) {
            h = 31 * h + x;
        }
        return h;
    }

    /**
     * A JPEG and another as long as it, which that polynomial cannot tell apart: two neighboring
     * entries of the quantization table, one up by one and the next down by 31. A JPEG has no
     * checksum, so both are sound files – and the table scales every block, so they decode to
     * different pictures.
     */
    @Test
    @DisplayName("two different tiles a 64-bit hash could not tell apart are two images")
    void aCollisionIsNotShared() throws IOException {
        final BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                img.setRGB(x, y, (x * 4) << 16 | (y * 4) << 8 | 0x80);
            }
        }
        final javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        final javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.1f);      // a coarse table, whose entries are big enough to take 31 off
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (final javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new javax.imageio.IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        final byte[] a = out.toByteArray();

        // The first table's 64 entries follow FF DB, its length and its precision-and-id byte
        int table = -1;
        for (int i = 2; i + 1 < a.length && table < 0; i++) {
            if ((a[i] & 0xFF) == 0xFF && (a[i + 1] & 0xFF) == 0xDB) {
                table = i + 5;
            }
        }
        assertTrue(table > 0, "the JPEG has a quantization table");
        int at = -1;
        for (int i = table; i < table + 63 && at < 0; i++) {
            if (a[i] < 127 && a[i + 1] >= 32) {
                at = i;
            }
        }
        assertTrue(at > 0, "two neighboring entries that can take the change");
        final byte[] b = a.clone();
        b[at]++;
        b[at + 1] -= 31;

        assertEquals(a.length, b.length);
        assertEquals(polynomial(a), polynomial(b), "the old key is the same for both");
        assertFalse(java.util.Arrays.equals(pixels(a), pixels(b)), "and yet they are different pictures");

        try (final PDDocument doc = new PDDocument()) {
            final PdfTileImages images = new PdfTileImages(doc);
            assertNotSame(images.of(a, TileEffect.NONE), images.of(b, TileEffect.NONE),
                    "the second must not print as the first");
            assertEquals(2, images.distinctImages());
        }
    }

    private static int[] pixels(final byte[] jpeg) throws IOException {
        final BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(jpeg));
        return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    }

    @Test
    @DisplayName("an RGBA tile whose alpha is unused is flattened rather than refused")
    void wastedAlphaIsDropped() throws IOException {
        final BufferedImage rgba = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                rgba.setRGB(x, y, 0xFF000000 | (x * 8) << 16 | (y * 8) << 8);
            }
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(rgba, "png", out);

        try (final PDDocument doc = new PDDocument()) {
            final PdfTileImages images = new PdfTileImages(doc);
            images.of(out.toByteArray(), TileEffect.NONE);
            // PNG RGBA cannot pass through – PDF has no interleaved-alpha image
            assertEquals(1, images.count(PdfTileImages.Path.DECODED), images.report());
            assertTrue(images.report().contains("alpha"), images.report());
        }
    }

    @Test
    @DisplayName("a tile that is not an image at all is refused, not embedded")
    void refusesRubbish() throws IOException {
        try (final PDDocument doc = new PDDocument()) {
            final PdfTileImages images = new PdfTileImages(doc);
            final byte[] html = "<html>Rate limit exceeded</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            final net.trdlo.wtf.tiles.Refusal r = assertThrows(net.trdlo.wtf.tiles.Refusal.class,
                    () -> images.of(html, TileEffect.NONE));
            assertEquals(ComposeReason.TILE_UNREADABLE, r.reason(), r::getMessage);
            assertEquals(java.util.List.of("?", net.trdlo.wtf.tiles.TileFormat.UNKNOWN), r.args(),
                    "a tile given without its key is named as none");
        }
    }
}
