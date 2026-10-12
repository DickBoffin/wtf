package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.CachedTileSource;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.Refusal;
import net.trdlo.wtf.tiles.TileCache;
import net.trdlo.wtf.tiles.TileFormat;
import net.trdlo.wtf.tiles.TileKey;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The edges of the copying: which JPEGs a {@code DCTDecode} stream may be, which lightness and
 * contrast a {@code /Decode} array may carry, and how a tile no decoder can read is refused.
 * <p>
 * Every case that cannot be copied is decoded instead, and the report says why; only a tile that
 * no decoder can read either is refused, naming it and its format. A JPEG is changed a byte at a
 * time from one ImageIO wrote, since ImageIO writes only baseline and progressive ones: the frame
 * marker for another process, the precision for twelve bits, a table's index for a file that only
 * its header reader accepts.
 */
class JpegEdgesTest {

    private static final TileKey KEY = new TileKey("osm", "mapnik", 9, 277, 173);

    private static byte[] jpeg(final boolean progressive) throws IOException {
        final BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                img.setRGB(x, y, (x * 4) << 16 | (y * 5) << 8 | 0x80);
            }
        }
        final ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        final ImageWriteParam param = writer.getDefaultWriteParam();
        if (progressive) {
            param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (final ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static byte[] truecolorPng() throws IOException {
        final BufferedImage img = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                img.setRGB(x, y, (x * 6) << 16 | (y * 8) << 8 | 0x70);
            }
        }
        return TestJobs.png(img);
    }

    /** Where the first marker of this kind starts, at its {@code FF}. */
    private static int marker(final byte[] jpeg, final int kind) {
        for (int i = 2; i + 1 < jpeg.length; i++) {
            if ((jpeg[i] & 0xFF) == 0xFF && (jpeg[i + 1] & 0xFF) == kind) {
                return i;
            }
        }
        throw new AssertionError("no marker " + Integer.toHexString(kind));
    }

    /** A baseline JPEG relabeled as another process: the same scan, which ImageIO still reads. */
    private static byte[] relabeled(final int frame) throws IOException {
        final byte[] b = jpeg(false);
        b[marker(b, 0xC0) + 1] = (byte) frame;
        return b;
    }

    private static BufferedImage decode(final byte[] data) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(data));
    }

    /**
     * The refusal of a tile no decoder can read: its reason, the tile, the format its bytes are,
     * and the English they make, which is what the command line prints.
     *
     * @param tile a pattern the tile's key matches
     */
    private static void assertUnreadable(final Refusal r, final String tile, final TileFormat format) {
        assertEquals(ComposeReason.TILE_UNREADABLE, r.reason(), r::getMessage);
        final String named = String.valueOf(r.args().getFirst());
        assertTrue(named.matches(tile), named);
        assertEquals(format, r.args().get(1));
        assertEquals("WTF cannot read tile " + named + " (format " + format + "), so it cannot be printed."
                + " Choose another map.", r.getMessage());
    }

    private static void assertSamePixels(final BufferedImage expected, final BufferedImage actual) {
        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                assertEquals(expected.getRGB(x, y) & 0xFFFFFF, actual.getRGB(x, y) & 0xFFFFFF,
                        "pixel " + x + "," + y);
            }
        }
    }

    // ------------------------------------------------------------------------------ which JPEGs

    @Test
    @DisplayName("a progressive JPEG goes in whole, as a baseline one does")
    void progressiveIsCopied(@TempDir final Path dir) throws IOException {
        final byte[] tile = jpeg(true);
        assertEquals(0xC2, PdfTileImages.Jpeg.read(tile).frame(), "ImageIO wrote a progressive JPEG");
        final Path pdf = dir.resolve("progressive.pdf");
        try (final PDDocument doc = new PDDocument()) {
            final PdfTileImages images = new PdfTileImages(doc);
            final PDPage page = new PDPage(new PDRectangle(64, 48));
            doc.addPage(page);
            try (final PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(images.of(tile, TileEffect.NONE), 0, 0, 64, 48);
            }
            assertEquals(1, images.count(PdfTileImages.Path.DCT_PASSTHROUGH), images.report());
            assertEquals(0, images.count(PdfTileImages.Path.DECODED), images.report());
            doc.save(pdf.toFile());
        }
        try (final PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            final PDImageXObject image = (PDImageXObject) doc.getPage(0).getResources()
                    .getXObject(doc.getPage(0).getResources().getXObjectNames().iterator().next());
            assertArrayEquals(tile, image.getCOSObject().createRawInputStream().readAllBytes(),
                    "the stream is the file, byte for byte");
            assertSamePixels(decode(tile), image.getImage());
        }
    }

    @ParameterizedTest(name = "SOF{0}")
    @ValueSource(ints = {1, 3, 5, 9})
    @DisplayName("a JPEG of any other process is decoded, and the report says which")
    void otherProcessesAreDecoded(final int sof) throws IOException {
        final PdfTileImages.Jpeg header = PdfTileImages.Jpeg.read(relabeled(0xC0 + sof));
        assertNotNull(header);
        final String obstacle = header.passThroughObstacle();
        assertNotNull(obstacle, "SOF" + sof + " is not a DCTDecode stream");
        assertEquals(switch (sof) {
            case 1 -> "extended sequential JPEG";
            case 3 -> "lossless JPEG";
            case 5 -> "hierarchical JPEG";
            default -> "arithmetic-coded JPEG";
        }, obstacle);
        assertNull(PdfTileImages.Jpeg.read(jpeg(false)).passThroughObstacle(), "baseline is");
        assertNull(PdfTileImages.Jpeg.read(jpeg(true)).passThroughObstacle(), "and progressive is");
    }

    @Test
    @DisplayName("an extended sequential JPEG comes out decoded and right")
    void extendedSequentialIsDecoded() throws IOException {
        final byte[] tile = relabeled(0xC1);
        try (final PDDocument doc = new PDDocument()) {
            final PdfTileImages images = new PdfTileImages(doc);
            final PDImageXObject image = images.of(KEY, tile, TileEffect.NONE);
            assertEquals(1, images.count(PdfTileImages.Path.DECODED), images.report());
            assertEquals(0, images.count(PdfTileImages.Path.DCT_PASSTHROUGH), images.report());
            assertTrue(images.report().contains("extended sequential JPEG"), images.report());
            assertEquals(COSName.FLATE_DECODE, image.getCOSObject().getItem(COSName.FILTER),
                    "re-encoded losslessly");
            assertSamePixels(decode(tile), image.getImage());
        }
    }

    @Test
    @DisplayName("a twelve-bit JPEG is not copied, and when nothing can decode it the refusal names the tile")
    void twelveBitsNameTheTile() throws IOException {
        final byte[] tile = jpeg(false);
        tile[marker(tile, 0xC0) + 4] = 12;
        assertEquals("12-bit JPEG", PdfTileImages.Jpeg.read(tile).passThroughObstacle());
        try (final PDDocument doc = new PDDocument()) {
            final PdfTileImages images = new PdfTileImages(doc);
            final Refusal r = assertThrows(Refusal.class, () -> images.of(KEY, tile, TileEffect.NONE));
            assertUnreadable(r, Pattern.quote(KEY.toString()), TileFormat.JPEG);
            assertInstanceOf(javax.imageio.IIOException.class, r.getCause(),
                    "the decoder's own error is kept underneath, for the details");
            assertEquals(0, images.count(PdfTileImages.Path.DCT_PASSTHROUGH));
        }
    }

    /**
     * A table index of 15 is one no JPEG may have. This code's header reader, which looks at the
     * frame alone, takes the file for a baseline JPEG; PDFBox's, which is ImageIO's, does not.
     */
    @Test
    @DisplayName("a JPEG that JPEGFactory cannot read goes to the decode path, and its refusal names the tile")
    void jpegFactoryFailureFallsBack() throws IOException {
        final byte[] tile = jpeg(false);
        tile[marker(tile, 0xDB) + 4] = 0x0F;
        assertNull(PdfTileImages.Jpeg.read(tile).passThroughObstacle(), "the frame says baseline");
        try (final PDDocument doc = new PDDocument()) {
            final PdfTileImages images = new PdfTileImages(doc);
            final Refusal r = assertThrows(Refusal.class, () -> images.of(KEY, tile, TileEffect.NONE));
            assertUnreadable(r, Pattern.quote(KEY.toString()), TileFormat.JPEG);
            assertInstanceOf(javax.imageio.IIOException.class, r.getCause(),
                    "ImageIO's own error is kept underneath");
        }
    }

    // ------------------------------------------------------------------------ which /Decode arrays

    @Test
    @DisplayName("/Decode carries lightness and contrast only while both its ends are colors")
    void theRangeOfDecode() {
        assertTrue(TileEffect.NONE.fitsDecode());
        assertTrue(new TileEffect(1, 0.3, -0.4).fitsDecode(), "lighter and flatter");
        assertTrue(new TileEffect(1, 0.1, 0).fitsDecode(), "(1 - l) + l is 1, arithmetic noise or not");
        assertTrue(new TileEffect(1, -0.2, -0.5).fitsDecode(), "darker and flatter still fits");
        assertFalse(new TileEffect(1, 0, 0.5).fitsDecode(), "more contrast reaches past black and white");
        assertTrue(new TileEffect(1, -0.3, 0).fitsDecode(), "darker alone scales toward black, and fits");
        assertFalse(new TileEffect(1, -0.2, 0.6).fitsDecode(), "darker with more contrast does not");
        assertFalse(TileEffect.FADED.fitsDecode(), "saturation is no per-channel map at all");
    }

    /**
     * Negative lightness goes toward black as positive goes toward white – not {@code 1 - l} for
     * both, which at −1 would leave white white and turn mid-gray black.
     */
    @Test
    @DisplayName("lightness -1 is black, -0.5 halves every value, and +0.5 is half-way to white")
    void lightnessBothWays() {
        final TileEffect black = new TileEffect(1, -1, 0);
        for (final int rgb : new int[]{0xFFFFFF, 0x808080, 0x3366AA, 0x000000}) {
            assertEquals(0x000000, black.apply(rgb), Integer.toHexString(rgb));
        }
        assertEquals(0x80_80_80, new TileEffect(1, -0.5, 0).apply(0xFFFFFF), "white at half");
        assertEquals(0x1A_33_55, new TileEffect(1, -0.5, 0).apply(0x3466AA), "every channel halved");
        assertEquals(0xFFFFFF, new TileEffect(1, 1, 0).apply(0x3366AA));
        assertEquals(0x33_5C_FF, new TileEffect(1, 0.2, 0).apply(0x0033FF), "a fifth of the way to white, as before");
        assertEquals(0, new TileEffect(1, -1, 0).decodeA(), "and the /Decode array says the same");
        assertEquals(0, new TileEffect(1, -1, 0).decodeB());
    }

    @Test
    @DisplayName("more contrast on a truecolor PNG is decoded, and prints what the effect says")
    void contrastPastTheRangeIsDecoded() throws IOException {
        final byte[] tile = truecolorPng();
        final TileEffect effect = new TileEffect(1, 0, 0.5);
        try (final PDDocument doc = new PDDocument()) {
            final PdfTileImages images = new PdfTileImages(doc);
            final PDImageXObject image = images.of(KEY, tile, effect);
            assertEquals(1, images.count(PdfTileImages.Path.DECODED), images.report());
            assertTrue(images.report().contains("/Decode outside 0..1"), images.report());
            assertNull(image.getCOSObject().getItem(COSName.DECODE), "no /Decode past the range");

            final BufferedImage src = decode(tile);
            final BufferedImage expected = new BufferedImage(src.getWidth(), src.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < src.getHeight(); y++) {
                for (int x = 0; x < src.getWidth(); x++) {
                    expected.setRGB(x, y, effect.apply(src.getRGB(x, y) & 0xFFFFFF));
                }
            }
            assertSamePixels(expected, image.getImage());
        }
    }

    @Test
    @DisplayName("on a JPEG too, while an effect inside the range stays a copy")
    void jpegPastTheRange() throws IOException {
        final byte[] tile = jpeg(false);
        try (final PDDocument doc = new PDDocument()) {
            final PdfTileImages images = new PdfTileImages(doc);
            images.of(KEY, tile, new TileEffect(1, -0.2, 0.6));
            assertEquals(1, images.count(PdfTileImages.Path.DECODED), images.report());
            assertTrue(images.report().contains("/Decode outside 0..1"), images.report());

            final PDImageXObject lighter = images.of(KEY, tile, new TileEffect(1, 0.3, -0.4));
            assertEquals(1, images.count(PdfTileImages.Path.DCT_PASSTHROUGH), images.report());
            assertNotNull(lighter.getCOSObject().getItem(COSName.DECODE));
        }
    }

    // ------------------------------------------------------------------------ errors, end to end

    /**
     * An export over a cache whose every tile is these bytes, which has to be refused – naming a
     * tile of the print and the format its bytes are, whichever exporter it was – and leave no
     * file behind.
     */
    private static Refusal exportFails(final Path dir, final ComposeJob.OutputFormat format,
                                       final TileEffect effect, final byte[] tile) throws IOException {
        final LonLatBounds area = new LonLatBounds(14.3, 49.9, 14.6, 50.1);
        final ComposeJob job = TestJobs.job(area).zoom(9).region(new Regions.RegionSpec(effect, true)).width(100).formats(format).perSheet()
                .dir(dir.resolve("out")).name("unreadable").build();
        final TileCache cache = new TileCache(dir.resolve("cache"));
        for (final TileKey key : TilePlan.of(job).distinctKeys()) {
            cache.store(key, tile, null);
        }
        final Refusal r = assertThrows(Refusal.class, () -> new Composer()
                .export(job, CachedTileSource.cacheOnly(cache, job.provider()), 1, ProgressSink.IGNORE));
        assertUnreadable(r, "osm/" + Pattern.quote(job.themeId()) + "/9/\\d+/\\d+", TileFormat.sniff(tile));
        try (final var left = Files.list(job.outputDir())) {
            assertEquals(java.util.List.of(), left.toList(), "no file left behind");
        }
        return r;
    }

    /** The JPEG only this code's header reader accepts, with the decoder's own error beneath. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ComposeJob.OutputFormat.class, names = {"PDF", "PNG"})
    @DisplayName("an export that meets a tile nothing can read refuses it by name")
    void anExportNamesTheTile(final ComposeJob.OutputFormat format, @TempDir final Path dir) throws IOException {
        final byte[] tile = jpeg(false);
        tile[marker(tile, 0xDB) + 4] = 0x0F;
        assertInstanceOf(javax.imageio.IIOException.class, exportFails(dir, format, TileEffect.NONE, tile).getCause());
    }

    /**
     * A WebP tile, which the cache takes, being an image, and which nothing in the JDK can read.
     * Every exporter refuses it by name, rather than leaving its square blank and carrying on, or
     * stopping with an error nobody could act on, said in English under "WTF did not see this one
     * coming".
     */
    @ParameterizedTest(name = "{0}, faded: {1}")
    @CsvSource({"PDF, false", "PNG, false", "TIFF, false",
            "PDF, true", "PNG, true", "TIFF, true"})
    @DisplayName("a tile no decoder can read is refused by every exporter, with the tile and its format")
    void anUndecodableTileIsRefused(final ComposeJob.OutputFormat format, final boolean faded,
                                    @TempDir final Path dir) throws IOException {
        final byte[] webp = "RIFF\0\0\0\0WEBPVP8 not really a picture".getBytes(StandardCharsets.US_ASCII);
        assertEquals(TileFormat.WEBP, TileFormat.sniff(webp));
        final Refusal r = exportFails(dir, format, faded ? TileEffect.FADED : TileEffect.NONE, webp);
        assertNull(r.getCause(), "no decoder knows the format, so none has an error of its own to add");
    }

    /**
     * A PNG cut short, as a tile damaged on the disk is: its last chunk runs past the end of the
     * file. The PDF's own chunk reader refuses it by name, as the raster's decoder does, not as an
     * error nobody could act on.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ComposeJob.OutputFormat.class, names = {"PDF", "PNG"})
    @DisplayName("a PNG cut short is refused by the PDF as by the raster")
    void aPngCutShortIsRefusedAlike(final ComposeJob.OutputFormat format, @TempDir final Path dir) throws IOException {
        final byte[] whole = truecolorPng();
        final byte[] cut = java.util.Arrays.copyOf(whole, whole.length / 2);
        assertThrows(IOException.class, () -> PngTile.read(cut), "its chunks run past its end");
        assertInstanceOf(javax.imageio.IIOException.class, exportFails(dir, format, TileEffect.NONE, cut).getCause());
    }
}
