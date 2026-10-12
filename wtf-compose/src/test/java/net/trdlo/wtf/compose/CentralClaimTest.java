package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.CachedTileSource;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Mercator;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.TileCache;
import net.trdlo.wtf.tiles.TileKey;
import net.trdlo.wtf.tiles.TileSource;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The central claim, read off the file: a tile's compressed bytes are the image stream in the PDF,
 * the very bytes, and the dictionary around them says exactly how to read them.
 * <p>
 * {@link PdfRoundTripTest} shows that the pixels come back; this shows why – that nothing was
 * decoded and encoded again on the way, which is the whole difference between a 900 MB wall and a
 * 3 GB one. The PNG is taken apart here by its own few lines, not by the {@code PngTile} under
 * test, and the PDF is saved and read back before anything is looked at.
 */
class CentralClaimTest {

    // ------------------------------------------------------------------ the PNG, taken apart here

    /** The chunks of a PNG, in order, as type and data. */
    private static List<Map.Entry<String, byte[]>> chunks(final byte[] png) {
        final List<Map.Entry<String, byte[]>> out = new ArrayList<>();
        final ByteBuffer b = ByteBuffer.wrap(png);
        b.position(8);
        while (b.remaining() >= 12) {
            final int length = b.getInt();
            final byte[] type = new byte[4];
            b.get(type);
            final byte[] data = new byte[length];
            b.get(data);
            b.getInt();                         // the CRC
            out.add(Map.entry(new String(type, java.nio.charset.StandardCharsets.US_ASCII), data));
        }
        return out;
    }

    /** Every {@code IDAT}'s data, end to end: the zlib stream a PDF reader is to inflate. */
    static byte[] idat(final byte[] png) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (final Map.Entry<String, byte[]> c : chunks(png)) {
            if (c.getKey().equals("IDAT")) {
                out.writeBytes(c.getValue());
            }
        }
        return out.toByteArray();
    }

    private static byte[] chunk(final String type, final byte[] png) {
        return chunks(png).stream().filter(c -> c.getKey().equals(type)).findFirst().orElseThrow().getValue();
    }

    /** The same PNG with its image data cut into this many {@code IDAT} chunks, as encoders may. */
    private static byte[] splitIdat(final byte[] png, final int parts) {
        final byte[] data = idat(png);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(java.util.Arrays.copyOf(png, 8));
        boolean written = false;
        for (final Map.Entry<String, byte[]> c : chunks(png)) {
            if (!c.getKey().equals("IDAT")) {
                writeChunk(out, c.getKey(), c.getValue());
            } else if (!written) {
                final int step = (data.length + parts - 1) / parts;
                for (int at = 0; at < data.length; at += step) {
                    writeChunk(out, "IDAT", java.util.Arrays.copyOfRange(data, at, Math.min(data.length, at + step)));
                }
                written = true;
            }
        }
        return out.toByteArray();
    }

    private static void writeChunk(final ByteArrayOutputStream out, final String type, final byte[] data) {
        final byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(t);
        out.writeBytes(data);
        final CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    // ---------------------------------------------------------------------------- the tiles

    /** What a tile server sends, and what each must become in the PDF. */
    enum Kind {
        INDEXED_8(256, 256, 1, 8), INDEXED_4(200, 120, 1, 4), GRAY(256, 256, 1, 8),
        TRUECOLOR(300, 170, 3, 8), MULTI_IDAT(256, 256, 1, 8);

        final int width, height, colors, bits;

        Kind(final int width, final int height, final int colors, final int bits) {
            this.width = width;
            this.height = height;
            this.colors = colors;
            this.bits = bits;
        }

        boolean indexed() {
            return this == INDEXED_8 || this == INDEXED_4 || this == MULTI_IDAT;
        }

        byte[] png() throws IOException {
            final BufferedImage img = switch (this) {
                case INDEXED_8, MULTI_IDAT -> new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED);
                case INDEXED_4 -> {
                    final byte[] r = new byte[16], g = new byte[16], b = new byte[16];
                    for (int i = 0; i < 16; i++) {
                        r[i] = (byte) (i * 17);
                        g[i] = (byte) (255 - i * 13);
                        b[i] = (byte) (i * 7 + 40);
                    }
                    yield new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY,
                            new IndexColorModel(4, 16, r, g, b));
                }
                case GRAY -> new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
                case TRUECOLOR -> new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            };
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    // blocky, like a map, with a gradient through it
                    img.setRGB(x, y, ((x / 10) * 23 % 256) << 16 | ((y / 7) * 31 % 256) << 8 | ((x + y) % 256));
                }
            }
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(img, "png", out));
            final byte[] png = out.toByteArray();
            return this == MULTI_IDAT ? splitIdat(png, 3) : png;
        }
    }

    private static byte[] jpeg(final int rgb) throws IOException {
        final BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                img.setRGB(x, y, (rgb + ((x / 32) << 4) + ((y / 32) << 12)) & 0xFFFFFF);
            }
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, "jpg", out));
        return out.toByteArray();
    }

    /** One tile on one page, saved and read back: its XObject as the file has it. */
    private record Embedded(COSStream stream, byte[] raw, BufferedImage pixels) { }

    private static Embedded embed(final byte[] tile, final TileEffect effect, final PdfTileImages.Path expected)
            throws IOException {
        final ByteArrayOutputStream file = new ByteArrayOutputStream();
        try (final PDDocument doc = new PDDocument()) {
            final PDPage page = new PDPage(new PDRectangle(256, 256));
            doc.addPage(page);
            final PdfTileImages images = new PdfTileImages(doc);
            try (final PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(images.of(tile, effect), 0, 0, 256, 256);
            }
            assertEquals(1, images.count(expected), images.report());
            assertEquals(0, images.count(PdfTileImages.Path.DECODED), images.report());
            doc.save(file);
        }
        try (final PDDocument doc = Loader.loadPDF(file.toByteArray())) {
            final PDImageXObject image = onlyImage(doc.getPage(0).getResources());
            final COSStream stream = image.getCOSObject();
            try (var in = stream.createRawInputStream()) {
                return new Embedded(stream, in.readAllBytes(), image.getImage());
            }
        }
    }

    private static PDImageXObject onlyImage(final PDResources res) throws IOException {
        PDImageXObject found = null;
        for (final COSName n : res.getXObjectNames()) {
            final PDXObject xo = res.getXObject(n);
            if (xo instanceof final PDImageXObject img) {
                assertNull(found, "one image on the page");
                found = img;
            }
        }
        assertNotNull(found, "an image on the page");
        return found;
    }

    private static int integer(final COSDictionary d, final COSName key) {
        final COSBase v = d.getDictionaryObject(key);
        assertInstanceOf(COSNumber.class, v, key.getName());
        return ((COSNumber) v).intValue();
    }

    private static void assertSamePixels(final BufferedImage expected, final BufferedImage actual) {
        assertEquals(expected.getWidth(), actual.getWidth(), "width");
        assertEquals(expected.getHeight(), actual.getHeight(), "height");
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                assertEquals(expected.getRGB(x, y) & 0xFFFFFF, actual.getRGB(x, y) & 0xFFFFFF, "pixel " + x + "," + y);
            }
        }
    }

    // --------------------------------------------------------------------------------- PNG

    @ParameterizedTest(name = "{0}")
    @EnumSource(Kind.class)
    @DisplayName("a PNG's image stream is its IDAT, byte for byte, with the /DecodeParms to read it")
    void theStreamIsTheIdat(final Kind kind) throws IOException {
        final byte[] tile = kind.png();
        if (kind == Kind.MULTI_IDAT) {
            assertEquals(3, chunks(tile).stream().filter(c -> c.getKey().equals("IDAT")).count());
        }
        final Embedded e = embed(tile, TileEffect.NONE, PdfTileImages.Path.FLATE_PASSTHROUGH);

        assertArrayEquals(idat(tile), e.raw(), "the stream is the IDAT data, every chunk of it in order");
        assertEquals(COSName.FLATE_DECODE, e.stream().getDictionaryObject(COSName.FILTER));
        assertEquals(kind.width, integer(e.stream(), COSName.WIDTH));
        assertEquals(kind.height, integer(e.stream(), COSName.HEIGHT));
        assertEquals(kind.bits, integer(e.stream(), COSName.BITS_PER_COMPONENT));

        final COSDictionary parms = (COSDictionary) e.stream().getDictionaryObject(COSName.DECODE_PARMS);
        assertNotNull(parms, "/DecodeParms");
        assertEquals(15, integer(parms, COSName.PREDICTOR), "PNG prediction, chosen per row");
        assertEquals(kind.colors, integer(parms, COSName.COLORS));
        assertEquals(kind.bits, integer(parms, COSName.BITS_PER_COMPONENT));
        assertEquals(kind.width, integer(parms, COSName.COLUMNS), "a row is the tile's width, square or not");

        final COSBase cs = e.stream().getDictionaryObject(COSName.COLORSPACE);
        if (kind.indexed()) {
            final COSArray indexed = (COSArray) cs;
            assertEquals(COSName.INDEXED, indexed.getObject(0));
            assertEquals(COSName.DEVICERGB, indexed.getObject(1));
            final byte[] plte = chunk("PLTE", tile);
            assertEquals(plte.length / 3 - 1, ((COSNumber) indexed.getObject(2)).intValue(), "hival");
            assertArrayEquals(plte, ((COSString) indexed.getObject(3)).getBytes(), "the lookup is the PLTE");
        } else {
            assertEquals(kind == Kind.GRAY ? COSName.DEVICEGRAY : COSName.DEVICERGB, cs);
        }
        assertNull(e.stream().getDictionaryObject(COSName.DECODE), "no effect, no /Decode");
        final BufferedImage src = ImageIO.read(new ByteArrayInputStream(tile));
        if (kind == Kind.GRAY) {
            assertSameGray(src, e.pixels());
        } else {
            assertSamePixels(src, e.pixels());
        }
    }

    /**
     * Gray by its samples: Java's gray images are linear and turn a sample into sRGB on the way
     * out of {@code getRGB}, so two identical grays can disagree there.
     */
    private static void assertSameGray(final BufferedImage expected, final BufferedImage actual) {
        assertEquals(expected.getWidth(), actual.getWidth(), "width");
        assertEquals(expected.getHeight(), actual.getHeight(), "height");
        final boolean gray = actual.getType() == BufferedImage.TYPE_BYTE_GRAY;
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                assertEquals(expected.getRaster().getSample(x, y, 0),
                        gray ? actual.getRaster().getSample(x, y, 0) : actual.getRGB(x, y) & 0xFF,
                        "sample " + x + "," + y);
            }
        }
    }

    @Test
    @DisplayName("an effect on a palette tile changes the lookup and not one byte of the stream")
    void anEffectLeavesTheStream() throws IOException {
        final byte[] tile = Kind.INDEXED_8.png();
        final Embedded e = embed(tile, TileEffect.FADED, PdfTileImages.Path.FLATE_PASSTHROUGH);
        assertArrayEquals(idat(tile), e.raw());
        final byte[] plte = chunk("PLTE", tile);
        final byte[] lookup = ((COSString) ((COSArray) e.stream().getDictionaryObject(COSName.COLORSPACE))
                .getObject(3)).getBytes();
        for (int i = 0; i < plte.length / 3; i++) {
            final int rgb = TileEffect.FADED.apply((plte[3 * i] & 0xFF) << 16 | (plte[3 * i + 1] & 0xFF) << 8
                    | (plte[3 * i + 2] & 0xFF));
            assertArrayEquals(new byte[]{(byte) (rgb >> 16), (byte) (rgb >> 8), (byte) rgb},
                    java.util.Arrays.copyOfRange(lookup, 3 * i, 3 * i + 3), "entry " + i);
        }
    }

    @Test
    @DisplayName("lightness and contrast on a truecolor tile are six numbers in /Decode, and the stream stays")
    void aDecodeArrayLeavesTheStream() throws IOException {
        final byte[] tile = Kind.TRUECOLOR.png();
        final TileEffect effect = new TileEffect(1, 0.3, -0.4);
        final Embedded e = embed(tile, effect, PdfTileImages.Path.FLATE_PASSTHROUGH);
        assertArrayEquals(idat(tile), e.raw());
        final COSArray decode = (COSArray) e.stream().getDictionaryObject(COSName.DECODE);
        assertEquals(6, decode.size());
        for (int c = 0; c < 3; c++) {
            assertEquals(effect.decodeB(), ((COSNumber) decode.getObject(2 * c)).floatValue(), 1e-6);
            assertEquals(effect.decodeA() + effect.decodeB(), ((COSNumber) decode.getObject(2 * c + 1)).floatValue(),
                    1e-6);
        }
    }

    // -------------------------------------------------------------------------------- JPEG

    @Test
    @DisplayName("a JPEG's image stream is the file, byte for byte, with nothing to decode it by but DCT")
    void theStreamIsTheJpeg() throws IOException {
        final byte[] tile = jpeg(0x305070);
        final Embedded e = embed(tile, TileEffect.NONE, PdfTileImages.Path.DCT_PASSTHROUGH);
        assertArrayEquals(tile, e.raw());
        assertEquals(COSName.DCT_DECODE, e.stream().getDictionaryObject(COSName.FILTER));
        assertNull(e.stream().getDictionaryObject(COSName.DECODE_PARMS));
        assertNull(e.stream().getDictionaryObject(COSName.DECODE));
        assertEquals(COSName.DEVICERGB, e.stream().getDictionaryObject(COSName.COLORSPACE));
        assertEquals(8, integer(e.stream(), COSName.BITS_PER_COMPONENT));
        assertSamePixels(ImageIO.read(new ByteArrayInputStream(tile)), e.pixels());
    }
}
