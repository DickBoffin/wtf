package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.CachedTileSource;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Mercator;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import net.trdlo.wtf.tiles.TileCache;
import net.trdlo.wtf.tiles.TileKey;
import net.trdlo.wtf.tiles.TileSource;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.imageio.ImageIO;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A gray tile prints as gray as it is stored, wherever it is decoded.
 * <p>
 * {@code BufferedImage.getRGB} converts a one-channel image from linear gray to sRGB, so a sample
 * of 128 read through it is 188, and a decode path that read through it would print a gray tile
 * under saturation at 0xBC where 0x80 is stored and under FADED at 0xC2 for 0xAC, and would
 * brighten a gray-with-alpha tile with no effect at all, in both exporters. A flat-128 gray PNG
 * and a gray-with-alpha one, through {@code PdfTileImages} and whole exports in both formats, read
 * back at 0x80 or
 * the effect's own arithmetic; and a gray PNG is not decoded for saturation, which does nothing
 * to one channel.
 */
class GrayTilesTest {

    private static final Provider OSM = ProviderCatalog.builtIn().require("osm");
    private static final int GRAY = 0x80, ZOOM = 5;

    private static final TileEffect SATURATION = new TileEffect(0, 0, 0);
    /** Contrast whose {@code /Decode} ends fall outside 0..1, so the tile is decoded. */
    private static final TileEffect CONTRAST = new TileEffect(1, 0, 0.8);

    /** Color type 0: one channel of 128. */
    private static byte[] gray() throws IOException {
        final BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                img.getRaster().setSample(x, y, 0, GRAY);
            }
        }
        return TestJobs.png(img);
    }

    /** Color type 4: 128 with an alpha channel, opaque but for the top-left pixel. */
    private static byte[] grayAlpha() throws IOException {
        final ColorModel cm = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY), true, false,
                Transparency.TRANSLUCENT, DataBuffer.TYPE_BYTE);
        final WritableRaster raster = cm.createCompatibleWritableRaster(256, 256);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                raster.setSample(x, y, 0, GRAY);
                raster.setSample(x, y, 1, x == 0 && y == 0 ? 0 : 255);
            }
        }
        return TestJobs.png(new BufferedImage(cm, raster, false, null));
    }

    private static byte[] tile(final boolean alpha) throws IOException {
        return alpha ? grayAlpha() : gray();
    }

    private static int expected(final TileEffect effect) {
        return effect.apply(GRAY << 16 | GRAY << 8 | GRAY) & 0xFF;
    }

    private static String hex(final int rgb) {
        return String.format(Locale.ROOT, "%08X", rgb);
    }

    /** The blue channel at (10, 10), which for gray is the gray; the XObject read back through PDFBox. */
    private static int at(final PDImageXObject image, final int x, final int y) throws IOException {
        return image.getImage().getRGB(x, y) & 0xFF;
    }

    // ------------------------------------------------------------------ one tile into a PDF

    @Test
    @DisplayName("a gray tile is not decoded for saturation, and reads back as it was stored")
    void grayIsNotDecodedForSaturation() throws IOException {
        final byte[] tile = gray();
        assertEquals(PngTile.GRAY, PngTile.read(tile).colorType());
        try (PDDocument doc = new PDDocument()) {
            final PdfTileImages images = new PdfTileImages(doc);
            final PDImageXObject plain = images.of(tile, SATURATION);
            assertEquals(1, images.count(PdfTileImages.Path.FLATE_PASSTHROUGH), images.report());
            assertEquals(0, images.count(PdfTileImages.Path.DECODED), images.report());
            assertNull(plain.getCOSObject().getItem(COSName.DECODE), "saturation alone is no effect on one channel");
            assertEquals(GRAY, at(plain, 10, 10));

            // FADED is saturation, lightness and contrast: the last two go into /Decode, still copied
            final PDImageXObject faded = images.of(tile, TileEffect.FADED);
            assertEquals(2, images.count(PdfTileImages.Path.FLATE_PASSTHROUGH), images.report());
            final COSArray decode = (COSArray) faded.getCOSObject().getItem(COSName.DECODE);
            assertNotNull(decode);
            assertEquals(2, decode.size(), "one channel, two numbers");
            final double low = ((org.apache.pdfbox.cos.COSNumber) decode.get(0)).floatValue();
            final double high = ((org.apache.pdfbox.cos.COSNumber) decode.get(1)).floatValue();
            assertEquals(expected(TileEffect.FADED), Math.round((low + (high - low) * GRAY / 255) * 255), 1,
                    "the array's arithmetic on 128 is the effect's");
            assertEquals(GRAY, at(faded, 10, 10), "and the samples are untouched");
        }
    }

    @ParameterizedTest(name = "alpha {0}, {1}")
    @CsvSource({"false, CONTRAST", "true, NONE", "true, SATURATION", "true, FADED", "true, CONTRAST"})
    @DisplayName("a gray tile that is decoded is written back as gray, at the effect's own value")
    void decodedGrayStaysGray(final boolean alpha, final String name) throws IOException {
        final TileEffect effect = switch (name) {
            case "NONE" -> TileEffect.NONE;
            case "SATURATION" -> SATURATION;
            case "FADED" -> TileEffect.FADED;
            default -> CONTRAST;
        };
        try (PDDocument doc = new PDDocument()) {
            final PdfTileImages images = new PdfTileImages(doc);
            final PDImageXObject image = images.of(tile(alpha), effect);
            assertEquals(1, images.count(PdfTileImages.Path.DECODED), images.report());
            assertSame(PDDeviceGray.INSTANCE, image.getColorSpace(), "one channel in, one channel out");
            assertNull(image.getCOSObject().getItem(COSName.DECODE), "the effect is in the samples");
            assertEquals(expected(effect), at(image, 10, 10));
            final BufferedImage read = image.getImage();
            if (alpha) {
                assertNotNull(image.getSoftMask(), "a used alpha is the /SMask");
                assertEquals(0xFF, read.getRGB(10, 10) >>> 24);
                assertEquals(0, read.getRGB(0, 0) >>> 24, "the one transparent pixel");
            } else {
                assertNull(image.getSoftMask());
            }
        }
    }

    // ------------------------------------------------------------------ whole exports

    private static final LonLatBounds BISCAY = new LonLatBounds(-10, 46, 4, 50);

    private static ComposeJob job(final Path dir, final ComposeJob.OutputFormat format, final TileEffect effect) {
        return TestJobs.job(BISCAY).zoom(6).region(new Regions.RegionSpec(effect, true)).missing(MissingTilePolicy.REFUSE).formats(format)
                .dir(dir.resolve("out")).name("gray").build();
    }

    /** A point well inside the first sheet, as the export made it. */
    private static int inside(final ComposeJob job, final TileSource tiles) throws IOException {
        new Composer().export(job, tiles, 0, ProgressSink.IGNORE);
        final SheetLayout.Sheet sheet = job.layout().sheets().getFirst();
        final BufferedImage img;
        final double pxPerUnit;
        if (job.formats().contains(ComposeJob.OutputFormat.PNG)) {
            img = ImageIO.read(PdfExporter.fileFor(job, sheet, "png").toFile());
            pxPerUnit = img.getWidth() / sheet.slice().unitWidth();
        } else {
            final int dpi = 72;
            try (PDDocument doc = Loader.loadPDF(PdfExporter.fileFor(job, null, "pdf").toFile())) {
                img = new PDFRenderer(doc).renderImageWithDPI(0, dpi);
            }
            pxPerUnit = sheet.slice().mmPerUnit() / 25.4 * dpi;
        }
        final int px = (int) ((Mercator.lonToUnitX(-7.8) - sheet.slice().x0()) * pxPerUnit);
        final int py = (int) ((Mercator.latToUnitY(49.48) - sheet.slice().y0()) * pxPerUnit);
        return img.getRGB(px, py);
    }

    /**
     * Every case but FADED on the opaque gray tile in the PDF, which passes through with its
     * lightness and contrast in a {@code /Decode} array that PDFBox's renderer does not apply to
     * an eight-bit image; {@link #grayIsNotDecodedForSaturation} reads that array's arithmetic.
     */
    @ParameterizedTest(name = "{0}, alpha {1}, {2}")
    @CsvSource({
            "PDF, false, NONE", "PDF, false, SATURATION",
            "PDF, false, CONTRAST", "PDF, true, NONE", "PDF, true, FADED",
            "PNG, false, NONE", "PNG, false, SATURATION", "PNG, false, FADED", "PNG, false, CONTRAST",
            "PNG, true, NONE", "PNG, true, FADED"})
    @DisplayName("both exporters print a gray tile at the effect's own value")
    void anExportPrintsGrayAsStored(final ComposeJob.OutputFormat format, final boolean alpha, final String name,
                                    @TempDir final Path dir) throws IOException {
        final TileEffect effect = switch (name) {
            case "NONE" -> TileEffect.NONE;
            case "SATURATION" -> SATURATION;
            case "FADED" -> TileEffect.FADED;
            default -> CONTRAST;
        };
        final TileCache cache = new TileCache(dir.resolve("cache"));
        final byte[] tile = tile(alpha);
        final ComposeJob job = job(dir, format, effect);
        for (final TileKey k : TilePlan.of(job).distinctKeys()) {
            cache.store(k, tile, null);
        }
        final int rgb = inside(job, CachedTileSource.cacheOnly(cache, OSM));
        final int want = expected(effect);
        assertEquals(hex(0xFF000000 | want * 0x010101), hex(rgb), "a point inside the sheet");
    }
}
