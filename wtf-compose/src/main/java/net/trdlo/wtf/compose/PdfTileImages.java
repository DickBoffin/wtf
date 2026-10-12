package net.trdlo.wtf.compose;

import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.trdlo.wtf.tiles.TileFormat;

import javax.imageio.IIOException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Turns a cached tile's bytes into a PDF image XObject, copying rather than converting wherever
 * the format allows.
 * <p>
 * This is the heart of the export, and M0 proved it: 47 261 tiles into a 930 MB PDF at 0.50 ms
 * each, every image pixel-identical to its source. The dispatch table is
 * docs/design/compose-module.md §6.
 */
public final class PdfTileImages {

    private static final Logger log = LoggerFactory.getLogger(PdfTileImages.class);

    /** How a tile got in, so an export can report why it was slow. */
    public enum Path { FLATE_PASSTHROUGH, DCT_PASSTHROUGH, DECODED }

    /**
     * Why an image went in decoded rather than copied, in the words a person can act on: the first
     * two are what the treatment asks for, the last two are the tiles' own (compose-module.md
     * §5.4 and §6). The finer detail – which JPEG process, which PNG feature – is the log's,
     * {@link PdfTileImages#report()}. The studio says each from its bundle.
     */
    public enum Fallback {
        /** On a tile with channels to mix – a truecolor PNG, a three-component JPEG */
        SATURATION("saturation"),
        /** Added contrast, which pushes a {@code /Decode} array past 0..1 on a tile that is not indexed */
        CONTRAST("added contrast"),
        /** PDF wants the alpha as an {@code /SMask} of its own: five of sixteen themes (§6.3) */
        ALPHA_CHANNEL("alpha channel"),
        /**
         * Everything else the dispatch table decodes: an interlaced PNG, one with {@code tRNS}, a
         * JPEG {@code DCTDecode} does not read, another format, or a tile the copy could not parse
         */
        FORMAT("tile format");

        private final String english;

        Fallback(final @NotNull String english) {
            this.english = english;
        }

        /** For the log and the command line; the studio has its own words. */
        public @NotNull String english() {
            return english;
        }
    }

    /**
     * How many images a document holds and how many of them went in decoded rather than copied, by
     * why – or an export's PDFs added up, which is what its summary says. A raster has none to
     * count, since every pixel of one is composited.
     *
     * @param total   distinct images, copied and decoded, over every document counted
     * @param decoded the decoded ones by why, in the order of {@link Fallback}; a why with none is
     *                absent
     */
    public record Tally(int total, @NotNull Map<Fallback, Integer> decoded) {

        /** Nothing counted: an export that wrote no PDF, or finished none before its cancel. */
        public static final Tally NONE = new Tally(0, Map.of());

        public Tally {
            final Map<Fallback, Integer> kept = new EnumMap<>(Fallback.class);
            decoded.forEach((why, n) -> {
                if (n > 0) {
                    kept.put(why, n);
                }
            });
            decoded = Collections.unmodifiableMap(kept);
        }

        public int decodedCount() {
            return decoded.values().stream().mapToInt(Integer::intValue).sum();
        }

        /** Two documents' images together, as an export adds up the PDFs it writes. */
        public @NotNull Tally plus(final @NotNull Tally other) {
            final Map<Fallback, Integer> sum = new EnumMap<>(Fallback.class);
            sum.putAll(decoded);
            other.decoded.forEach((why, n) -> sum.merge(why, n, Integer::sum));
            return new Tally(total + other.total, sum);
        }

        /** "images decoded rather than copied: 10 of 20 (saturation: 8, alpha channel: 2)", in English. */
        public @NotNull String describe() {
            final StringJoiner why = new StringJoiner(", ");
            decoded.forEach((f, n) -> why.add(String.format(Locale.ROOT, "%s: %,d", f.english(), n)));
            return String.format(Locale.ROOT, "images decoded rather than copied: %,d of %,d (%s)",
                    decodedCount(), total, why);
        }
    }

    private final PDDocument doc;

    /**
     * Identical bytes under identical treatment become one XObject. The key
     * includes the effect, because two regions may draw the same tile differently and sharing it
     * would wash out the wrong one – subtle enough to reach the printer.
     */
    private final Map<Key, PDImageXObject> cache = new HashMap<>();

    private final Map<Path, AtomicInteger> counts = new HashMap<>();
    private final Map<String, AtomicInteger> fallbackReasons = new HashMap<>();
    /** The decoded images by why, for {@link #tally()}: {@link #fallbackReasons} with less detail. */
    private final Map<Fallback, AtomicInteger> decodedBy = new EnumMap<>(Fallback.class);

    /**
     * Every image this document has been given, in the order they were made: what each is made of
     * and how it is drawn. Half of the file's {@code /ID}, which {@link PdfExporter} works out from
     * what is in the file rather than from the clock – see {@link #fingerprint}.
     */
    private final MessageDigest contents = sha256();

    static @NotNull MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Every Java has SHA-256", ex);
        }
    }

    /**
     * A tile's bytes by SHA-256, which is what two tiles must share to share an XObject.
     * <p>
     * <b>Not a 64-bit hash of them.</b> A polynomial one is a key two different tiles can
     * share – two neighboring bytes, one up by one and the next down by 31, are enough – and the
     * second tile would then print as the first without a word. Thirty-two bytes a tile is
     * the price of that never happening.
     */
    record Digest(long a, long b, long c, long d) {

        static @NotNull Digest of(final byte @NotNull [] data) {
            final ByteBuffer b = ByteBuffer.wrap(sha256().digest(data));
            return new Digest(b.getLong(), b.getLong(), b.getLong(), b.getLong());
        }
    }

    private record Key(@NotNull Digest bytes, @NotNull TileEffect effect) { }

    public PdfTileImages(final @NotNull PDDocument doc) {
        this.doc = doc;
        for (final Path p : Path.values()) {
            counts.put(p, new AtomicInteger());
        }
    }

    /**
     * The XObject for these bytes under this effect, reusing one when it has been seen before.
     *
     * @param data the cached tile, exactly as it is on disk
     * @throws net.trdlo.wtf.tiles.Refusal for bytes no decoder can read, which names the tile "?",
     *                                     having no key for it
     */
    public @NotNull PDImageXObject of(final byte @NotNull [] data, final @NotNull TileEffect effect)
            throws IOException {
        return shared(null, data, effect);
    }

    /**
     * The XObject for a tile.
     *
     * @throws net.trdlo.wtf.tiles.Refusal for a tile no decoder can read, naming it and its format
     *                                     ({@link TileSamples#decode})
     */
    public @NotNull PDImageXObject of(final net.trdlo.wtf.tiles.@NotNull TileKey key,
                                      final byte @NotNull [] data, final @NotNull TileEffect effect)
            throws IOException {
        return shared(key, data, effect);
    }

    /** The one XObject for these bytes under this effect, made the first time they are seen. */
    private @NotNull PDImageXObject shared(final net.trdlo.wtf.tiles.@Nullable TileKey key,
                                           final byte @NotNull [] data, final @NotNull TileEffect effect)
            throws IOException {
        final Key k = new Key(Digest.of(data), effect);
        final PDImageXObject existing = cache.get(k);
        if (existing != null) {
            return existing;
        }
        final PDImageXObject made;
        try {
            made = build(key, data, effect);
        } catch (final IOException ex) {
            throw naming(key, ex);
        }
        cache.put(k, made);
        remember(k.bytes(), effect);
        return made;
    }

    /**
     * One more image into {@link #contents}: what it is made of – its digest, a record of numbers
     * whose text is the same on every run – and the treatment, to the bit.
     */
    private void remember(final @NotNull Digest source, final @NotNull TileEffect effect) {
        contents.update(String.valueOf(source).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        contents.update(ByteBuffer.allocate(3 * Long.BYTES)
                .putLong(Double.doubleToLongBits(effect.saturation()))
                .putLong(Double.doubleToLongBits(effect.lightness()))
                .putLong(Double.doubleToLongBits(effect.contrast()))
                .array());
    }

    /**
     * What the images made so far are, as a SHA-256: the same images in the same order, the same
     * thirty-two bytes, on any machine and in any run.
     */
    byte @NotNull [] fingerprint() {
        try {
            return ((MessageDigest) contents.clone()).digest();
        } catch (final CloneNotSupportedException ex) {
            throw new IllegalStateException("A SHA-256 digest can be copied", ex);
        }
    }

    /**
     * The error with the tile it is about in front of it. Every tile error names its
     * {@code TileKey} – one no decoder can read is a refusal that does ({@link TileSamples#decode}),
     * and this names the tile in front of any other: an error on one of forty thousand tiles that
     * does not say which is a message nobody can act on, and the key is what finds the file in the
     * cache.
     */
    private static @NotNull IOException naming(final net.trdlo.wtf.tiles.@Nullable TileKey key,
                                               final @NotNull IOException ex) {
        return new IOException((key == null ? "A tile" : "Tile " + key) + ": " + ex.getMessage(), ex);
    }

    /**
     * Just enough of a JPEG's SOF marker to describe the image without decoding it.
     *
     * @param frame the SOF marker itself, {@code 0xC0} to {@code 0xCF}: which JPEG process made it
     */
    record Jpeg(int width, int height, int components, int precision, int frame) {

        /**
         * Why this JPEG cannot be a {@code DCTDecode} stream as it is, or null when it can.
         * <p>
         * <b>Baseline and progressive, eight bits, one or three components</b> – what PDF's
         * {@code DCTDecode} reads, progressive since PDF 1.3. The other processes – extended
         * sequential, lossless, hierarchical, arithmetic-coded – and twelve-bit samples are not
         * in the filter, and a viewer that shows them anyway is no promise that a RIP will. Four
         * components is Adobe's CMYK, whose inversion is a guess; a tile server does not send it.
         */
        @Nullable String passThroughObstacle() {
            if (frame != 0xC0 && frame != 0xC2) {
                return switch (frame) {
                    case 0xC1 -> "extended sequential JPEG";
                    case 0xC3 -> "lossless JPEG";
                    case 0xC5, 0xC6, 0xC7 -> "hierarchical JPEG";
                    default -> "arithmetic-coded JPEG";
                };
            }
            if (precision != 8) {
                return precision + "-bit JPEG";
            }
            if (components != 1 && components != 3) {
                return components + "-component JPEG";
            }
            return null;
        }

        static @Nullable Jpeg read(final byte @NotNull [] d) {
            int i = 2;                              // past SOI
            while (i + 9 < d.length) {
                if ((d[i] & 0xFF) != 0xFF) {
                    i++;
                    continue;
                }
                final int marker = d[i + 1] & 0xFF;
                if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                    i += 2;                         // standalone markers carry no length
                    continue;
                }
                final int length = ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
                // SOF0..SOF15, except the four that are not frame headers (DHT, JPG, DAC, and the
                // restart markers already handled above)
                final boolean isFrame = marker >= 0xC0 && marker <= 0xCF
                        && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
                if (isFrame) {
                    final int precision = d[i + 4] & 0xFF;
                    final int height = ((d[i + 5] & 0xFF) << 8) | (d[i + 6] & 0xFF);
                    final int width = ((d[i + 7] & 0xFF) << 8) | (d[i + 8] & 0xFF);
                    final int components = d[i + 9] & 0xFF;
                    return width > 0 && height > 0 && components > 0
                            ? new Jpeg(width, height, components, precision, marker) : null;
                }
                if (marker == 0xDA) {
                    return null;                    // reached the scan without a frame header
                }
                i += 2 + length;
            }
            return null;
        }
    }

    /** @param key the tile, for the refusal of one no decoder can read; null for a caller that has none */
    private @NotNull PDImageXObject build(final net.trdlo.wtf.tiles.@Nullable TileKey key,
                                          final byte @NotNull [] data, final @NotNull TileEffect given)
            throws IOException {
        final TileFormat format = TileFormat.sniff(data);

        if (format == TileFormat.PNG) {
            final PngTile png;
            try {
                png = PngTile.read(data);
            } catch (final IOException ex) {
                // Chunks that run past the end of the file, say, as a tile damaged on the disk has:
                // what the decoder can read goes in decoded, and what it cannot either is refused
                // by name, as the raster refuses it, rather than as this reader's own complaint
                log.debug("PngTile could not read a tile's chunks, so it is decoded: {}", ex.getMessage());
                return decoded(key, data, given, Fallback.FORMAT, "unreadable PNG chunks");
            }
            if (!png.canPassThrough()) {
                // The first obstacle decides, as passThroughObstacle names it: an interlaced tile is
                // the format's, whatever else it has
                final Fallback why = !png.interlaced() && png.hasAlphaChannel()
                        ? Fallback.ALPHA_CHANNEL : Fallback.FORMAT;
                return decoded(key, data, given, why, png.passThroughObstacle());
            }
            if (png.colorType() == PngTile.INDEXED) {
                return flatePassthrough(png, given);       // the palette carries any effect
            }
            // Saturation does nothing to one channel, so a gray tile is not decoded for it
            final TileEffect effect = png.colorType() == PngTile.GRAY ? given.onOneChannel() : given;
            if (!effect.isLinearPerChannel()) {
                // saturation on a gray or truecolor PNG mixes channels; no dictionary can do that
                return decoded(key, data, effect, Fallback.SATURATION, "saturation on non-indexed PNG");
            }
            if (!effect.fitsDecode()) {
                return decoded(key, data, effect, Fallback.CONTRAST, DECODE_RANGE);
            }
            return flatePassthrough(png, effect);
        }
        if (format == TileFormat.JPEG) {
            final Jpeg header = Jpeg.read(data);
            final String obstacle = header == null ? UNREADABLE_HEADER : header.passThroughObstacle();
            if (obstacle != null) {
                return decoded(key, data, given, Fallback.FORMAT, obstacle);
            }
            final TileEffect effect = header != null && header.components() == 1 ? given.onOneChannel() : given;
            if (!effect.isLinearPerChannel()) {
                return decoded(key, data, effect, Fallback.SATURATION, "saturation on JPEG");
            }
            if (!effect.fitsDecode()) {
                return decoded(key, data, effect, Fallback.CONTRAST, DECODE_RANGE);
            }
            try {
                return dctPassthrough(data, effect);
            } catch (final IIOException ex) {
                // PDFBox reads the header again, through ImageIO; what it cannot read, the decode
                // path may, and if that cannot either, the refusal says which tile it was
                log.debug("JPEGFactory could not read a tile, so it is decoded: {}", ex.getMessage());
                return decoded(key, data, effect, Fallback.FORMAT, "unreadable by JPEGFactory");
            }
        }
        // WebP among them, which the JDK has no decoder for and this build carries none: refused
        // by name in decoded, as the raster refuses it, unless a decoder here can read it after all
        return decoded(key, data, given, Fallback.FORMAT, "format " + format);
    }

    /**
     * The PNG path: the {@code IDAT} becomes the stream, the {@code PLTE} becomes the color space,
     * and {@code /DecodeParms} says the samples are PNG-predicted. Not one byte of image data is
     * touched – an effect on an indexed tile is 768 bytes of palette arithmetic.
     */
    private @NotNull PDImageXObject flatePassthrough(final @NotNull PngTile png,
                                                     final @NotNull TileEffect effect) throws IOException {
        final PDColorSpace cs;
        if (png.colorType() == PngTile.INDEXED) {
            final byte[] palette = png.transformedPalette(effect);
            final COSArray indexed = new COSArray();
            indexed.add(COSName.INDEXED);
            indexed.add(COSName.DEVICERGB);
            indexed.add(COSInteger.get(png.paletteEntries() - 1L));
            indexed.add(new COSString(palette));
            cs = PDColorSpace.create(indexed);
        } else {
            cs = png.colorType() == PngTile.GRAY ? PDDeviceGray.INSTANCE : PDDeviceRGB.INSTANCE;
        }

        final PDImageXObject image = new PDImageXObject(doc, new ByteArrayInputStream(png.idat()),
                COSName.FLATE_DECODE, png.width(), png.height(), png.bitDepth(), cs);

        final COSDictionary parms = new COSDictionary();
        parms.setInt(COSName.PREDICTOR, 15);
        parms.setInt(COSName.COLORS, png.components());
        parms.setInt(COSName.BITS_PER_COMPONENT, png.bitDepth());
        parms.setInt(COSName.COLUMNS, png.width());
        image.getCOSObject().setItem(COSName.DECODE_PARMS, parms);

        // an indexed tile already carries the effect in its palette; anything else needs /Decode
        if (png.colorType() != PngTile.INDEXED && !effect.isIdentity()) {
            applyDecode(image, effect, png.components());
        }
        counts.get(Path.FLATE_PASSTHROUGH).incrementAndGet();
        return image;
    }

    /** The JPEG path: a JPEG file <i>is</i> a {@code DCTDecode} stream, so it goes in whole. */
    private @NotNull PDImageXObject dctPassthrough(final byte @NotNull [] data,
                                                   final @NotNull TileEffect effect) throws IOException {
        final PDImageXObject image = JPEGFactory.createFromByteArray(doc, data);
        if (!effect.isIdentity()) {
            applyDecode(image, effect, image.getColorSpace().getNumberOfComponents());
        }
        counts.get(Path.DCT_PASSTHROUGH).incrementAndGet();
        return image;
    }

    /**
     * Lightness and contrast without touching a pixel: {@code /Decode} remaps each sample to a
     * color-component range, and lightness and contrast composed are exactly a per-channel linear
     * map. Six numbers in a dictionary instead of a re-encode.
     */
    private static void applyDecode(final @NotNull PDImageXObject image, final @NotNull TileEffect effect,
                                    final int components) {
        final double a = effect.decodeA();
        final double b = effect.decodeB();
        final COSArray decode = new COSArray();
        for (int i = 0; i < components; i++) {
            decode.add(new COSFloat((float) b));
            decode.add(new COSFloat((float) (a + b)));
        }
        image.getCOSObject().setItem(COSName.DECODE, decode);
    }

    private static final String UNREADABLE_HEADER = "unreadable JPEG header";

    /** Why an effect a {@code /Decode} array could otherwise carry is decoded all the same. */
    private static final String DECODE_RANGE = "/Decode outside 0..1";

    /**
     * The honest fallback: decode, apply, re-encode. Correct, just slower and bigger.
     * <p>
     * The pixels are read as their samples are ({@link TileSamples}), the effect is applied to
     * them, and an alpha channel that is opaque everywhere – the common RGBA case, PDOK and
     * basemap.de both ship a wasted channel – is dropped; a genuinely used one becomes the
     * {@code /SMask}. A gray tile is written back as {@code DeviceGray} by hand, because
     * {@code LosslessFactory} reads a gray image through {@code getRGB} too, and would brighten
     * it on the way in. A tile no decoder can read is refused here, naming it and its format
     * ({@link TileSamples#decode}).
     *
     * @param why    why the tile is decoded rather than copied, for the export's summary
     * @param reason the same in more detail, for the log's report
     */
    private @NotNull PDImageXObject decoded(final net.trdlo.wtf.tiles.@Nullable TileKey key,
                                            final byte @NotNull [] data, final @NotNull TileEffect effect,
                                            final @NotNull Fallback why, final @NotNull String reason)
            throws IOException {
        final BufferedImage img = TileSamples.decode(key, data);
        final int w = img.getWidth(), h = img.getHeight();
        final int[] argb = TileSamples.argb(img);
        if (!effect.isIdentity()) {
            for (int i = 0; i < argb.length; i++) {
                argb[i] = argb[i] & 0xFF000000 | effect.apply(argb[i] & 0xFFFFFF);
            }
        }
        fallbackReasons.computeIfAbsent(reason, k -> new AtomicInteger()).incrementAndGet();
        decodedBy.computeIfAbsent(why, k -> new AtomicInteger()).incrementAndGet();
        counts.get(Path.DECODED).incrementAndGet();
        final boolean alpha = !TileSamples.isOpaque(argb);
        if (TileSamples.isGray(img)) {
            // the effect keeps equal channels equal, so what came in gray goes out gray
            final PDImageXObject gray = grayImage(argb, w, h, 0);
            if (alpha) {
                gray.getCOSObject().setItem(COSName.SMASK, grayImage(argb, w, h, 24));
            }
            return gray;
        }
        return LosslessFactory.createFromImage(doc, TileSamples.image(argb, w, h, alpha));
    }

    /**
     * An eight-bit {@code DeviceGray} image of one byte of each pixel, deflated: the blue for the
     * gray itself, the alpha for an {@code /SMask}.
     *
     * @param shift which byte of the packed ARGB, {@code 0} for blue and {@code 24} for alpha
     */
    private @NotNull PDImageXObject grayImage(final int @NotNull [] argb, final int w, final int h, final int shift)
            throws IOException {
        final byte[] samples = new byte[argb.length];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (byte) (argb[i] >>> shift);
        }
        final java.io.ByteArrayOutputStream deflated = new java.io.ByteArrayOutputStream(samples.length / 2);
        try (java.util.zip.DeflaterOutputStream zip = new java.util.zip.DeflaterOutputStream(deflated)) {
            zip.write(samples);
        }
        return new PDImageXObject(doc, new ByteArrayInputStream(deflated.toByteArray()), COSName.FLATE_DECODE,
                w, h, 8, PDDeviceGray.INSTANCE);
    }

    public int distinctImages() {
        return cache.size();
    }

    public int count(final @NotNull Path path) {
        return counts.get(path).get();
    }

    /**
     * What this document's images are, for the export to add up and say: a job that silently fell
     * back on 30 000 tiles took twenty times as long and produced a file three times the size, and
     * the user should be told which case caused it.
     */
    public @NotNull Tally tally() {
        final Map<Fallback, Integer> decoded = new EnumMap<>(Fallback.class);
        decodedBy.forEach((why, n) -> decoded.put(why, n.get()));
        return new Tally(distinctImages(), decoded);
    }

    /**
     * What the document actually did, in the detail a developer needs – every path, and why each
     * decoded tile was so – for the log.
     */
    public @NotNull String report() {
        final StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                "%,d distinct images: %,d Flate passthrough, %,d JPEG passthrough, %,d re-encoded",
                distinctImages(), count(Path.FLATE_PASSTHROUGH), count(Path.DCT_PASSTHROUGH),
                count(Path.DECODED)));
        if (!fallbackReasons.isEmpty()) {
            sb.append(" (");
            fallbackReasons.forEach((reason, n) -> sb.append(n.get()).append(" ").append(reason).append(", "));
            sb.setLength(sb.length() - 2);
            sb.append(")");
        }
        return sb.toString();
    }

    /**
     * At debug level, a document at a time. What a person reads is the export's summary, which adds
     * up every document's {@link #tally()} and says it once, at INFO (headless-and-debugging.md §6).
     */
    public void logReport() {
        log.debug("Tile images: {}", report());
    }

    public @Nullable PDDocument document() {
        return doc;
    }
}
