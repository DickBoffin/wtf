package net.trdlo.wtf.compose;

import org.jetbrains.annotations.NotNull;
import net.trdlo.wtf.tiles.Refusal;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.trdlo.wtf.tiles.Mercator;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.TileKey;
import net.trdlo.wtf.tiles.TileSource;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.Locale;

/**
 * Writes one sheet as a flat raster.
 * <p>
 * Unlike the PDF exporters this really does composite pixels, so the memory rule bites: a sheet is
 * written in <b>bands</b> and the whole raster never exists. A 915 x 3000 mm strip at 300 DPI is
 * 0.38 Gpx, which would be 1.07 GiB as RGB in one allocation.
 * <p>
 * PNG goes through {@link PngStreamWriter} rather than ImageIO, because ImageIO's PNG writer
 * cannot write incrementally – {@code canWriteEmpty()} is false – and the way round that is the
 * single allocation the banding exists to prevent.
 * <p>
 * TIFF keeps ImageIO, and there the choice is banding <b>or</b> compression: the writer can
 * replace pixels in an uncompressed image and not in a compressed one. Large sheets are banded
 * uncompressed, small ones compressed in one piece; see {@code writeViaImageIo}.
 * <p>
 * There is no linking here and no passthrough – every pixel is composited, so the crop is a mask
 * and an effect is arithmetic on samples that are being touched anyway.
 */
public final class RasterExporter {

    private static final Logger log = LoggerFactory.getLogger(RasterExporter.class);

    /** What this tells a progress sink, named so an interface in another language can recognize it. */
    public static final String PHASE_RASTERIZING = "Rasterizing sheet %d";

    /**
     * The most rows of tiles a band holds when nobody names its height: sixteen, which is 4 096 rows
     * of pixels, and fewer on a raster too wide for them ({@link #defaultBandTiles}). As
     * {@code TYPE_INT_RGB}, four bytes a pixel, that is 66 MB across 4 000 pixels and 256 MB across
     * 15 625, the widest raster that keeps all sixteen.
     */
    public static final int DEFAULT_BAND_TILES = 16;

    /**
     * How many pixels a raster may be composited in at once. A TIFF sheet of up to this many is
     * composited whole, so that it can be compressed, and a larger one band by band, as a PNG always
     * is; and a band whose height nobody names holds no more than this, unless one row of tiles is
     * already more ({@link #defaultBandTiles}).
     * <p>
     * 64 Mpx is 256 MB as {@code TYPE_INT_RGB}, which is where a single allocation stops being
     * something to shrug at. Below it, compression is worth more than the memory.
     */
    static final long PIXELS_AT_ONCE = 64_000_000L;

    /**
     * How many rows of tiles a band of a raster this wide holds when nobody names the height – the
     * studio never does, and the command line only with {@code --band-tiles}: sixteen, or on a
     * raster too wide for sixteen as many as stay within the 64 Mpx a whole TIFF is composited in
     * ({@code PIXELS_AT_ONCE}), and never less than one, however wide.
     * <p>
     * Not a flat sixteen: across a strip 35 000 pixels wide, sixteen rows of tiles are 573 MB at a
     * time, and the seven this gives are 251 MB.
     *
     * @param widthPx the raster's width in pixels
     */
    public static int defaultBandTiles(final int widthPx) {
        return defaultBandTiles(widthPx, Mercator.TILE_SIZE);
    }

    /** The same, for tiles this many pixels tall: half as many rows of {@code @2x} tiles. */
    static int defaultBandTiles(final int widthPx, final int tilePx) {
        final long rows = PIXELS_AT_ONCE / Math.max(1, widthPx);
        return Math.clamp(rows / tilePx, 1, DEFAULT_BAND_TILES);
    }

    private RasterExporter() {
    }

    public record Result(long pixels, long bytes, int width, int height, double dpi) { }

    /** Classic TIFF offsets are 32-bit, so a file cannot pass 4 GiB. The JDK has no BigTIFF writer. */
    private static final long TIFF_LIMIT_BYTES = 4L * 1024 * 1024 * 1024;

    /** The most pixels a sheet's raster may have: past it, a Java raster cannot index them. */
    private static final long RASTER_LIMIT_PIXELS = 2_000_000_000L;

    /**
     * Refuses a sheet that cannot be written in this format: more pixels than a raster can index,
     * or, as a TIFF, more bytes uncompressed than a classic TIFF can address. Asked of every sheet
     * by {@link Composer#export} before it moves anything aside, so that a refusal leaves the
     * folder as it was (compose-module.md §7), and again by {@link #write}.
     *
     * @param format {@code "png"} or {@code "tiff"}
     * @throws Refusal {@code RASTER_TOO_LARGE} or {@code TIFF_TOO_LARGE}, naming the sheet
     */
    static void refuseTooLarge(final @NotNull ComposeJob job, final TilePlan.@NotNull Plan plan,
                               final SheetLayout.@NotNull Sheet sheet, final @NotNull String format) {
        final PrintRect slice = sheet.slice();
        final double pxPerUnit = (double) Mercator.TILE_SIZE * job.regions().scale()
                * Mercator.tileCount(job.regions().zoom());
        final int width = (int) Math.round(slice.unitWidth() * pxPerUnit);
        final int height = (int) Math.round(slice.unitHeight() * pxPerUnit);
        if ((long) width * height > RASTER_LIMIT_PIXELS) {
            throw new Refusal(ComposeReason.RASTER_TOO_LARGE, sheet.index(), width, height,
                    width * (double) height / 1e9);
        }
        if ("tiff".equalsIgnoreCase(format)) {
            // With alpha as write decides it: under TRANSPARENT, and under a fade
            final boolean alpha = job.missingTiles() == MissingTilePolicy.TRANSPARENT || plan.fade() != null;
            final long uncompressed = (long) width * height * (alpha ? 4 : 3);
            if (uncompressed > TIFF_LIMIT_BYTES) {
                throw new Refusal(ComposeReason.TIFF_TOO_LARGE, sheet.index(), uncompressed / 1e9);
            }
        }
    }

    /**
     * Writes a sheet.
     *
     * @param plan      the job's plan, for the shapes its tiles were planned against
     * @param format    {@code "png"} or {@code "tiff"}
     * @param bandTiles how many rows of tiles a band holds, one at the least; null for as many as
     *                  {@link #defaultBandTiles} gives the sheet's width
     * @throws Composer.CanceledException when the sink is canceled before the file is in place;
     *                                    the {@code .part} is deleted by then
     * @throws IllegalArgumentException   when the sheet is refused, a {@code Refusal}: before anything
     *                                    is written for too many pixels for a raster or too many bytes
     *                                    for a TIFF, and at the tile for one no decoder can read, the
     *                                    {@code .part} deleted. The message says what to change instead.
     */
    public static @NotNull Result write(final @NotNull ComposeJob job, final TilePlan.@NotNull Plan plan,
                                        final @NotNull TilePlan.SheetPlan sp,
                                        final @NotNull TileSource tiles, final @NotNull Path out,
                                        final @NotNull String format, final @Nullable Integer bandTiles,
                                        final @NotNull ProgressSink progress) throws IOException {
        final SheetLayout.Sheet sheet = sp.sheet();
        // the raster is sized by the print's zoom, so a tile is its own pixels
        final int zoom = job.regions().zoom();
        final PrintRect slice = sheet.slice();
        final int tilePx = Mercator.TILE_SIZE * job.regions().scale();
        final double pxPerUnit = (double) tilePx * Mercator.tileCount(zoom);

        final int width = (int) Math.round(slice.unitWidth() * pxPerUnit);
        final int height = (int) Math.round(slice.unitHeight() * pxPerUnit);
        // Composer.export has asked this of every sheet already, before it set anything aside; asked
        // again for a caller that writes a sheet by itself
        refuseTooLarge(job, plan, sheet, format);
        // The resolution each way, from the pixels actually written. One DPI taken from the
        // width made the height come back as a size the paper is not – 420.02 mm on A3 – because
        // the two edges are rounded to whole pixels separately. See metadataWithDpi.
        final double dpi = width / (sheet.widthMm() / 25.4);
        final double dpiDown = height / (sheet.heightMm() / 25.4);

        // The rows of tiles asked for, or when nobody asked, as many as a sheet this wide allows
        final int bandRows = (bandTiles == null ? defaultBandTiles(width, tilePx) : Math.max(1, bandTiles))
                * tilePx;
        // Under TRANSPARENT a missing tile is see-through (D28), for a file laid over something
        // else: an alpha channel, in a PNG and a TIFF alike. And under a fade, which is the map
        // going see-through towards its edge: both formats carry alpha, so it fades to nothing
        // rather than to a white that the file would then have to be printed on
        final FadeMask.Grid fade = plan.fade() == null ? null : plan.fade().grid();
        final boolean alpha = job.missingTiles() == MissingTilePolicy.TRANSPARENT || fade != null;

        // The shapes as far as this sheet reaches, cut once for all its bands. The raster is the
        // slice and nothing else; a millimeter past it all round, so that the edges the cut adds
        // are never where a pixel is decided, and a stroke width more, as in the PDF: the stroke
        // runs along those edges too, and its round joins reach half a width past them.
        final Regions.BoundaryStroke stroke = job.regions().boundary();
        final double marginMm = 1 + (stroke == null ? 0 : stroke.widthMm());
        final TilePlan.Projected onSheet = plan.projected().within(slice.grownByMm(2 * marginMm, 2 * marginMm));

        progress.phase(String.format(Locale.ROOT, PHASE_RASTERIZING, sheet.index()),
                height, ProgressSink.Unit.ROWS);

        Files.createDirectories(out.toAbsolutePath().getParent());
        final Path part = out.resolveSibling(out.getFileName() + ".part");

        try {
            if ("png".equalsIgnoreCase(format)) {
                writeBandedPng(job, sp, onSheet, tiles, zoom, slice, pxPerUnit, width, height, dpi, dpiDown,
                        bandRows, part, alpha, fade, progress);
            } else {
                writeViaImageIo(job, sp, onSheet, tiles, zoom, slice, pxPerUnit, width, height, dpi, dpiDown,
                        bandRows, part, format, alpha, fade, progress);
            }
            // The last moment the sheet can still be taken back: once renamed it is in place
            Composer.CanceledException.check(progress);
        } catch (final Throwable ex) {
            // Half a raster is not a raster, whatever stopped it – an OutOfMemoryError too. Leaving
            // it behind under a .part name is tidy enough that somebody will eventually rename it
            // and wonder why the printer refused it.
            OutputFiles.discardPart(part, ex);
            throw ex;
        }
        OutputFiles.replace(part, out);
        return new Result((long) width * height, Files.size(out), width, height, dpi);
    }

    /**
     * PNG, one band at a time, through {@link PngStreamWriter}.
     * <p>
     * Not through ImageIO: its PNG writer reports {@code canWriteEmpty() == false}, so the
     * empty-then-replace protocol throws, and the one way left through it is to composite the whole
     * sheet in one allocation – 1.07 GiB for a 915 x 3000 mm strip at 300 DPI, which is precisely
     * what banding exists to avoid.
     *
     * @param alpha RGBA rather than RGB, so that a missing tile under {@code TRANSPARENT} is
     *              see-through
     */
    private static void writeBandedPng(final @NotNull ComposeJob job, final @NotNull TilePlan.SheetPlan sp,
                                       final TilePlan.@NotNull Projected onSheet,
                                       final @NotNull TileSource tiles, final int zoom,
                                       final @NotNull PrintRect slice, final double pxPerUnit,
                                       final int width, final int height, final double dpi, final double dpiDown,
                                       final int bandRows, final @NotNull Path part, final boolean alpha,
                                       final FadeMask.@Nullable Grid fade,
                                       final @NotNull ProgressSink progress) throws IOException {
        try (final java.io.OutputStream raw = Files.newOutputStream(part);
             final java.io.BufferedOutputStream buffered = new java.io.BufferedOutputStream(raw, 1 << 16);
             final PngStreamWriter png = new PngStreamWriter(buffered, width, height, dpi, dpiDown, alpha)) {
            for (int top = 0; top < height; top += bandRows) {
                // A half-written PNG is not a PNG; the .part file is removed by the caller's
                // failure path rather than left looking like an export.
                Composer.CanceledException.check(progress);
                final int rows = Math.min(bandRows, height - top);
                png.writeBand(renderBand(job, sp, onSheet, tiles, zoom, slice, pxPerUnit, top, rows, width, alpha,
                        fade));
                progress.advance(rows);
            }
        }
    }

    /**
     * Everything else, through ImageIO, banded when the writer allows it.
     * <p>
     * <b>The JDK's TIFF writer can band or compress, not both</b> – measured, not assumed:
     * {@code canReplacePixels} is true for an uncompressed image and false for a compressed one,
     * because replacing a strip in place is not possible once it has been deflated. So the two
     * cannot both be had and the choice is made by size: a sheet of up to {@link #PIXELS_AT_ONCE}
     * is compressed in one piece, and a larger one is banded uncompressed, because on a large one
     * the allocation is the thing that actually fails while the file size is merely annoying. The
     * 4 GB guard above already measures the uncompressed size, so it stays honest either way.
     *
     * @param alpha with an alpha channel, so that a missing tile under {@code TRANSPARENT} is
     *              see-through; the writer records it as unassociated alpha
     */
    private static void writeViaImageIo(final @NotNull ComposeJob job, final @NotNull TilePlan.SheetPlan sp,
                                        final TilePlan.@NotNull Projected onSheet,
                                        final @NotNull TileSource tiles, final int zoom,
                                        final @NotNull PrintRect slice, final double pxPerUnit,
                                        final int width, final int height, final double dpi, final double dpiDown,
                                        final int bandRows, final @NotNull Path part,
                                        final @NotNull String format, final boolean alpha,
                                        final FadeMask.@Nullable Grid fade,
                                        final @NotNull ProgressSink progress) throws IOException {
        final boolean wantsBanding = (long) width * height > PIXELS_AT_ONCE;
        final ImageWriter writer = writerFor(format);
        try (final ImageOutputStream ios = openEmpty(part)) {
            writer.setOutput(ios);
            final ImageWriteParam param = writer.getDefaultWriteParam();
            if (!wantsBanding) {
                compress(param, format);
            }
            if (!wantsBanding || !writer.canWriteEmpty()) {
                throw new UnsupportedOperationException(wantsBanding
                        ? format + " cannot be written incrementally"
                        : format + " is small enough to compress in one piece");
            }
            writer.prepareWriteEmpty(null, imageType(alpha), width, height,
                    metadataWithDpi(writer, param, dpi, dpiDown, alpha), null, param);
            writer.endWriteEmpty();

            for (int top = 0; top < height; top += bandRows) {
                // The empty image is already on disk, whole and zeroed, so stopping quietly here
                // would pass the bands not yet written off as black; the .part goes instead
                Composer.CanceledException.check(progress);
                final int rows = Math.min(bandRows, height - top);
                final BufferedImage band = renderBand(job, sp, onSheet, tiles, zoom, slice, pxPerUnit, top, rows,
                        width, alpha, fade);
                // Each band is its own replace: declare the region, say where it lands, write it,
                // close it. Calling replacePixels without the prepare/end pair around it throws
                // "No previous call to prepareReplacePixels!" – and only a real TIFF export shows
                // it, because a unit test that only asks canWriteEmpty() never reaches this line.
                writer.prepareReplacePixels(0, new java.awt.Rectangle(0, top, width, rows));
                final ImageWriteParam bandParam = writer.getDefaultWriteParam();
                bandParam.setDestinationOffset(new java.awt.Point(0, top));
                writer.replacePixels(band, bandParam);
                writer.endReplacePixels();
                progress.advance(rows);
            }
        } catch (final UnsupportedOperationException ex) {
            final long megabytes = (long) width * height * 4 / 1_048_576;
            if (wantsBanding) {
                // Wanted to band and could not: this is the case that runs out of heap on a
                // wall-sized sheet, so say so loudly enough to explain the failure if it comes.
                log.warn("{} cannot be written band by band; compositing sheet {} in one piece "
                                + "({} x {} px). This needs about {} MB of heap.",
                        format, sp.sheet().index(), width, height, megabytes);
            } else {
                log.debug("Writing sheet {} in one piece, compressed ({} x {} px, about {} MB)",
                        sp.sheet().index(), width, height, megabytes);
            }
            writeWhole(job, sp, onSheet, tiles, zoom, slice, pxPerUnit, width, height, dpi, dpiDown, part, format,
                    alpha, fade, progress);
        } finally {
            writer.dispose();
        }
    }

    /** Map imagery is flat color and compresses well; an uncompressed TIFF is wasteful. */
    private static void compress(final @NotNull ImageWriteParam param, final @NotNull String format) {
        if (!"tiff".equalsIgnoreCase(format) || !param.canWriteCompressed()) {
            return;
        }
        for (final String wanted : new String[]{"Deflate", "LZW"}) {
            for (final String available : param.getCompressionTypes()) {
                if (wanted.equalsIgnoreCase(available)) {
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionType(available);
                    return;
                }
            }
        }
    }

    private static javax.imageio.ImageTypeSpecifier imageType(final boolean alpha) {
        return javax.imageio.ImageTypeSpecifier.createFromBufferedImageType(
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
    }

    private static void writeWhole(final @NotNull ComposeJob job, final @NotNull TilePlan.SheetPlan sp,
                                   final TilePlan.@NotNull Projected onSheet,
                                   final @NotNull TileSource tiles, final int zoom,
                                   final @NotNull PrintRect slice, final double pxPerUnit,
                                   final int width, final int height, final double dpi, final double dpiDown,
                                   final @NotNull Path part, final @NotNull String format, final boolean alpha,
                                   final FadeMask.@Nullable Grid fade,
                                   final @NotNull ProgressSink progress) throws IOException {
        final BufferedImage whole = renderBand(job, sp, onSheet, tiles, zoom, slice, pxPerUnit, 0, height, width,
                alpha, fade);
        Composer.CanceledException.check(progress);
        final ImageWriter writer = writerFor(format);
        try (final ImageOutputStream ios = openEmpty(part)) {
            writer.setOutput(ios);
            final ImageWriteParam param = writer.getDefaultWriteParam();
            // In one piece, so compressed: that is the point of writing it this way. The param
            // compress() set up for the banded attempt is that writer's, and a fresh default one
            // here wrote every small TIFF uncompressed – 791 kB for what Deflate makes 16 kB.
            compress(param, format);
            writer.write(null, new IIOImage(whole, null, metadataWithDpi(writer, param, dpi, dpiDown, alpha)), param);
        } finally {
            writer.dispose();
        }
        progress.advance(height);
    }

    /**
     * Composites one horizontal band of the sheet.
     *
     * @param onSheet the shapes as far as this sheet reaches, in the unit square
     * @param alpha   whether the band has an alpha channel, so that a missing tile under
     *                {@code TRANSPARENT} can be left see-through
     * @param fade    the fade across the growth, which needs {@code alpha}; null for none
     */
    private static @NotNull BufferedImage renderBand(final @NotNull ComposeJob job,
                                                     final @NotNull TilePlan.SheetPlan sp,
                                                     final TilePlan.@NotNull Projected onSheet,
                                                     final @NotNull TileSource tiles, final int zoom,
                                                     final @NotNull PrintRect slice, final double pxPerUnit,
                                                     final int top, final int rows, final int width,
                                                     final boolean alpha, final FadeMask.@Nullable Grid fade)
            throws IOException {
        final BufferedImage band = new BufferedImage(width, rows,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = band.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // Inside the area only, when the area is not a rectangle or is one to be printed
            // exactly, as in the PDF: outside it is
            // paper, and the map ends at the outline rather than at the tiles that outline touched.
            // Under an alpha channel this raster may be composited over something else, so outside
            // the outline is left as the band was allocated – transparent – rather than painted
            // white: the PDF leaves that part of the page unpainted (D28), and a raster that is
            // going to be laid over something should not paint paper where the PDF paints nothing.
            final Shape area = job.clipsToArea() ? unitPath(onSheet.printed(), slice, pxPerUnit, top) : null;
            if (area != null && !alpha) {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, width, rows);
            }
            g.setClip(area);
            g.setColor(new Color(job.backgroundColor()));
            g.fillRect(0, 0, width, rows);

            for (final TilePlan.RegionTiles region : sp.regions()) {
                g.setClip(area);
                g.clip(clipFor(job, onSheet.subject(), region.side(), slice, pxPerUnit, top));
                if (alpha) {
                    clearHoles(g, region, tiles, slice, pxPerUnit, top, rows);
                }
                drawRegionBand(g, job, region, tiles, slice, pxPerUnit, top, rows);
            }
            if (fade != null) {
                fadeOut(band, fade, slice, pxPerUnit, top);     // the map only, as in the PDF
            }
            g.setClip(null);
            strokeBoundary(g, job, onSheet.subject(), slice, pxPerUnit, top, width / sp.sheet().widthMm());
            drawMarks(g, job, sp.sheet(), width / sp.sheet().widthMm(), top);
        } finally {
            g.dispose();
        }
        return band;
    }

    /**
     * The fade across the growth, into the band's alpha: each pixel keeps the share of itself the
     * fade gives its center. The pixels are the band's own, unpremultiplied, so a color is left as
     * it is and only how much of it shows changes.
     */
    private static void fadeOut(final @NotNull BufferedImage band, final FadeMask.@NotNull Grid fade,
                                final @NotNull PrintRect slice, final double pxPerUnit, final int top) {
        final int[] argb = ((java.awt.image.DataBufferInt) band.getRaster().getDataBuffer()).getData();
        final int width = band.getWidth();
        // A pixel's column and row are counted from these, as pxAcross and pxDown count them
        final double left = Math.rint(slice.x0() * pxPerUnit);
        final double above = Math.rint(slice.y0() * pxPerUnit) + top;
        for (int y = 0; y < band.getHeight(); y++) {
            final double v = (above + y + 0.5) / pxPerUnit;
            for (int x = 0; x < width; x++) {
                final int k = y * width + x;
                final int a = argb[k] >>> 24;
                if (a != 0) {
                    final double shown = fade.alphaAt((left + x + 0.5) / pxPerUnit, v);
                    argb[k] = (int) Math.round(a * shown) << 24 | argb[k] & 0xFFFFFF;
                }
            }
        }
    }

    /**
     * The line along the subject's outline, when the job asks for one: over both regions and
     * outside the area's clip, as the PDF strokes it: butt caps, which a closed ring never shows,
     * and round joins. Every band strokes the whole path moved up by its top row, so the line
     * meets itself across a band's edge as the marks do.
     *
     * @param subject the subject as far as this sheet reaches, in the unit square
     */
    private static void strokeBoundary(final @NotNull Graphics2D g, final @NotNull ComposeJob job,
                                       final @Nullable Geometry subject, final @NotNull PrintRect slice,
                                       final double pxPerUnit, final int top, final double pxPerMm) {
        final Regions.BoundaryStroke stroke = job.regions().boundary();
        if (stroke == null || subject == null) {
            return;
        }
        final Stroke before = g.getStroke();
        g.setColor(new Color(stroke.rgb()));
        g.setStroke(new BasicStroke((float) (stroke.widthMm() * pxPerMm), BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_ROUND));
        g.draw(unitPath(subject, slice, pxPerUnit, top));
        g.setStroke(before);
    }

    // -------------------------------------------------------------------------------------------
    // Seam marks and the credit – placed by SeamMarks, drawn to look as they do in the PDF
    // -------------------------------------------------------------------------------------------

    private static final Color FAINT = new Color(0x96, 0x96, 0x96);
    private static final Color CREDIT = new Color(0x3A, 0x3A, 0x3A);
    private static final double HAIRLINE_MM = 0.15;

    /**
     * The sheet's marks, drawn into one band.
     * <p>
     * In the sheet's own coordinates, moved up by the band's top row, so a label that crosses a band
     * boundary is drawn half in each and meets itself. The layout is worked out again for each
     * band, which is arithmetic over a handful of sheets and cheaper than carrying it here.
     */
    private static void drawMarks(final @NotNull Graphics2D g, final @NotNull ComposeJob job,
                                  final SheetLayout.@NotNull Sheet sheet, final double pxPerMm, final int top)
            throws IOException {
        final java.util.List<SeamMarks.Mark> marks = SeamMarks.forSheet(job, job.layout(), sheet);
        if (marks.isEmpty()) {
            return;
        }
        final java.awt.geom.AffineTransform before = g.getTransform();
        g.translate(0, -top);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        // Every halo under a line meant to be read before any of its ink, as the PDF has them
        for (final SeamMarks.Mark mark : marks) {
            if (mark instanceof SeamMarks.Line l && l.style() == SeamMarks.Style.CREDIT) {
                drawLine(g, l, Color.WHITE, PdfExporter.BAR_MM + PdfExporter.BAR_HALO_MM, pxPerMm);
            }
        }
        for (final SeamMarks.Mark mark : marks) {
            switch (mark) {
                case SeamMarks.Line l -> {
                    if (l.style() == SeamMarks.Style.CREDIT) {
                        drawLine(g, l, CREDIT, PdfExporter.BAR_MM, pxPerMm);
                    } else {
                        drawLine(g, l, FAINT, HAIRLINE_MM, pxPerMm);
                    }
                }
                case SeamMarks.Text t -> drawText(g, t, pxPerMm);
            }
        }
        g.setTransform(before);
    }

    /**
     * One straight line. A hairline has butt ends, as a PDF line has – Java's default square cap
     * runs half a width past the end, out of the strip the line was placed in; the scale bar's are
     * square, as in the PDF.
     */
    private static void drawLine(final @NotNull Graphics2D g, final SeamMarks.@NotNull Line l,
                                 final @NotNull Color color, final double widthMm, final double pxPerMm) {
        g.setColor(color);
        g.setStroke(new java.awt.BasicStroke((float) Math.max(1, widthMm * pxPerMm),
                l.style() == SeamMarks.Style.CREDIT ? java.awt.BasicStroke.CAP_SQUARE : java.awt.BasicStroke.CAP_BUTT,
                java.awt.BasicStroke.JOIN_MITER));
        g.draw(new java.awt.geom.Line2D.Double(l.x1() * pxPerMm, l.y1() * pxPerMm,
                l.x2() * pxPerMm, l.y2() * pxPerMm));
    }

    private static void drawText(final @NotNull Graphics2D g, final SeamMarks.@NotNull Text t, final double pxPerMm)
            throws IOException {
        final float sizePx = (float) (t.sizePt() * 25.4 / 72 * pxPerMm);
        final java.awt.Font font = lettering().deriveFont(sizePx);
        final java.awt.font.TextLayout text = new java.awt.font.TextLayout(t.text(), font, g.getFontRenderContext());
        final double back = switch (t.anchor()) {
            case START -> 0;
            case MIDDLE -> text.getAdvance() / 2;
            case END -> text.getAdvance();
        };

        final java.awt.geom.AffineTransform before = g.getTransform();
        g.translate(t.x() * pxPerMm, t.y() * pxPerMm);
        g.rotate(-Math.toRadians(t.angle()));      // counter-clockwise on the paper, y down here
        final Shape outline = text.getOutline(java.awt.geom.AffineTransform.getTranslateInstance(-back, 0));
        if (t.style() == SeamMarks.Style.CREDIT) {
            // The white halo first, as on every map label, then the letters over it
            g.setColor(Color.WHITE);
            g.setStroke(new java.awt.BasicStroke(sizePx / 6, java.awt.BasicStroke.CAP_ROUND,
                    java.awt.BasicStroke.JOIN_ROUND));
            g.draw(outline);
        }
        g.setColor(t.style() == SeamMarks.Style.CREDIT ? CREDIT : FAINT);
        g.fill(outline);
        g.setTransform(before);
    }

    /** The face every line of a raster's marks is set in, loaded once. */
    private static volatile Font letteringFont;

    /**
     * {@link PdfExporter#FONT}, the same bundled Liberation Sans the PDF uses (D29), at a nominal
     * size a caller derives from.
     * <p>
     * <b>Not Java's logical {@code SansSerif}</b>: that maps to whatever the exporting machine
     * happens to have, so the same job printed the same way from two machines could show Plzeň in
     * two different faces, or as Plzen where one lacks the diacritic – the very inconsistency D29
     * bundling a font was meant to end, for the raster as for the PDF.
     */
    static @NotNull Font lettering() throws IOException {
        Font font = letteringFont;
        if (font == null) {
            try (final InputStream in = RasterExporter.class.getResourceAsStream(PdfExporter.FONT)) {
                if (in == null) {
                    throw new IOException("The bundled font " + PdfExporter.FONT + " is missing from the build");
                }
                font = Font.createFont(Font.TRUETYPE_FONT, in);
            } catch (final FontFormatException ex) {
                throw new IOException("The bundled font " + PdfExporter.FONT + " could not be read", ex);
            }
            letteringFont = font;
        }
        return font;
    }

    private static void drawRegionBand(final @NotNull Graphics2D g, final @NotNull ComposeJob job,
                                       final @NotNull TilePlan.RegionTiles region,
                                       final @NotNull TileSource tiles, final @NotNull PrintRect slice,
                                       final double pxPerUnit, final int top, final int rows)
            throws IOException {
        final double tileUnit = 1.0 / Mercator.tileCount(region.zoom());
        final double tilePx = tileUnit * pxPerUnit;

        for (final TileKey key : region.keys()) {
            final double x = pxAcross(key.x() * tileUnit, slice, pxPerUnit);
            final double y = pxDown(key.y() * tileUnit, slice, pxPerUnit) - top;
            if (y + tilePx <= 0 || y >= rows) {
                continue;                        // not in this band
            }
            final byte[] data = tiles.get(key);
            if (data == null) {
                if (tiles.isKnownEmpty(key)) {
                    continue;      // sea, or past the coverage: the map is the background there
                }
                if (job.missingTiles() == MissingTilePolicy.PLACEHOLDER) {
                    drawPlaceholderMark(g, x, y, tilePx);
                }
                continue;      // TRANSPARENT: cleared by clearHoles
            }
            // A tile no decoder can read – a WebP tile, say – is refused by name on the way, as the
            // PDF refuses it, rather than printed as a blank square (TileSamples.decode)
            final BufferedImage img = decoded(key, data, region.effect());
            if (isWhole(tilePx) && img.getWidth() == (int) tilePx && img.getHeight() == (int) tilePx
                    && isWhole(x) && isWhole(y)) {
                // On whole pixels: copied as it is, pixel for pixel
                g.drawImage(img, (int) x, (int) y, null);
                continue;
            }
            // Off whole pixels, or a tile of another size, which the filtering draws
            final AffineTransform at = new AffineTransform();
            at.translate(x, y);
            at.scale(tilePx / img.getWidth(), tilePx / img.getHeight());
            g.drawImage(img, at, null);
        }
    }

    /** The proof's deliberately ugly marker over one tile's square, as {@code PdfExporter} draws it. */
    private static void drawPlaceholderMark(final @NotNull Graphics2D g, final double x, final double y,
                                            final double tilePx) {
        final Color previous = g.getColor();
        g.setColor(new Color(0xE0, 0x60, 0x60));
        g.drawRect((int) x, (int) y, (int) tilePx, (int) tilePx);
        g.drawLine((int) x, (int) y, (int) (x + tilePx), (int) (y + tilePx));
        g.drawLine((int) x, (int) (y + tilePx), (int) (x + tilePx), (int) y);
        g.setColor(previous);
    }

    /**
     * A tile's bytes as an image under the effect; one no decoder can read is refused, naming the
     * tile and its format ({@link TileSamples#decode}).
     * <p>
     * Java2D draws a gray image's samples as they are, but a gray-with-alpha one through
     * {@code getRGB}, brightened; that one is rebuilt from its samples even with no effect.
     */
    private static @NotNull BufferedImage decoded(final @NotNull TileKey key, final byte @NotNull [] data,
                                                  final @NotNull TileEffect effect) {
        final BufferedImage img = TileSamples.decode(key, data);
        if (!effect.isIdentity()) {
            return withEffect(img, effect);
        }
        return TileSamples.isGray(img) && img.getColorModel().hasAlpha()
                ? TileSamples.image(TileSamples.argb(img), img.getWidth(), img.getHeight(), true) : img;
    }

    /**
     * Under {@code TRANSPARENT}, takes the background away from under this region's missing tiles,
     * before its tiles are drawn beside them, so that the PNG is see-through there and nowhere
     * else. A tile the provider says does not exist keeps its background: that is the map.
     */
    private static void clearHoles(final @NotNull Graphics2D g, final @NotNull TilePlan.RegionTiles region,
                                   final @NotNull TileSource tiles, final @NotNull PrintRect slice,
                                   final double pxPerUnit, final int top, final int rows) {
        final double tileUnit = 1.0 / Mercator.tileCount(region.zoom());
        final double tilePx = tileUnit * pxPerUnit;
        final Composite before = g.getComposite();
        g.setComposite(AlphaComposite.Clear);
        for (final TileKey key : region.keys()) {
            final double x = pxAcross(key.x() * tileUnit, slice, pxPerUnit);
            final double y = pxDown(key.y() * tileUnit, slice, pxPerUnit) - top;
            if (y + tilePx <= 0 || y >= rows || tiles.isCached(key) || tiles.isKnownEmpty(key)) {
                continue;
            }
            g.fill(new java.awt.geom.Rectangle2D.Double(x, y, tilePx, tilePx));
        }
        g.setComposite(before);
    }

    /**
     * The tile under an effect that works on each color alone.
     * <p>
     * A palette tile – most PNG map tiles are one – has its palette changed and its pixels left
     * as they are: at most 256 colors instead of 65 536 pixels, and the same result, since every
     * pixel of one index is the same color before and after. Any other tile's pixels are read as
     * their samples are ({@link TileSamples}) – a gray tile read through {@code getRGB} printed
     * much lighter than the PDF's {@code /Decode} made it – and written into an image Java2D
     * copies.
     */
    private static @NotNull BufferedImage withEffect(final @NotNull BufferedImage src,
                                                     final @NotNull TileEffect effect) {
        if (src.getColorModel() instanceof final java.awt.image.IndexColorModel palette) {
            final int[] colors = new int[palette.getMapSize()];
            palette.getRGBs(colors);
            for (int i = 0; i < colors.length; i++) {
                colors[i] = (colors[i] & 0xFF000000) | effect.apply(colors[i] & 0xFFFFFF);
            }
            final java.awt.image.IndexColorModel changed = new java.awt.image.IndexColorModel(
                    palette.getPixelSize(), colors.length, colors, 0, palette.hasAlpha(),
                    palette.getTransparentPixel(), palette.getTransferType());
            return new BufferedImage(changed, src.getRaster(), false, null);
        }
        final int[] argb = TileSamples.argb(src);
        for (int i = 0; i < argb.length; i++) {
            argb[i] = argb[i] & 0xFF000000 | effect.apply(argb[i] & 0xFFFFFF);
        }
        return TileSamples.image(argb, src.getWidth(), src.getHeight(), src.getColorModel().hasAlpha());
    }

    /**
     * The region mask for this band. Only tiles the outline actually crosses need it – tiles wholly
     * inside or wholly outside are copied straight – so the cost is a ring of tiles, not the sheet.
     *
     * @param subject the subject as far as this sheet reaches, in the unit square
     */
    private static @NotNull Shape clipFor(final @NotNull ComposeJob job, final @Nullable Geometry subject,
                                          final TilePlan.@NotNull Side side,
                                          final @NotNull PrintRect slice, final double pxPerUnit,
                                          final int top) {
        if (subject == null || job.regions().isUniform()) {
            return new java.awt.Rectangle(Integer.MIN_VALUE / 4, Integer.MIN_VALUE / 4,
                    Integer.MAX_VALUE / 2, Integer.MAX_VALUE / 2);
        }
        final Path2D path = unitPath(subject, slice, pxPerUnit, top);
        if (side == TilePlan.Side.INSIDE) {
            return path;
        }
        final Area outside = new Area(new java.awt.Rectangle(Integer.MIN_VALUE / 4, Integer.MIN_VALUE / 4,
                Integer.MAX_VALUE / 2, Integer.MAX_VALUE / 2));
        outside.subtract(new Area(path));
        return outside;
    }

    /**
     * Where a point of the unit square lands in the sheet's pixels, across.
     * <p>
     * <b>The sheet's origin is snapped to a whole pixel of its zoom</b>, the deepest of its
     * regions, so that a tile at that zoom lands on whole pixels and is copied rather than
     * resampled. At the slice's own fractional offset every pixel of every tile would be blended
     * with its neighbor, and all the map's lettering would come out soft. The shift is under half
     * a pixel, and the same for the tiles, the clips and the holes, so they still meet.
     */
    private static double pxAcross(final double ux, final @NotNull PrintRect slice, final double pxPerUnit) {
        return ux * pxPerUnit - Math.rint(slice.x0() * pxPerUnit);
    }

    /** Where a point of the unit square lands in the sheet's pixels, down; see {@link #pxAcross}. */
    private static double pxDown(final double uy, final @NotNull PrintRect slice, final double pxPerUnit) {
        return uy * pxPerUnit - Math.rint(slice.y0() * pxPerUnit);
    }

    /**
     * Whether a pixel position is a whole pixel, exactly. Exactness is meant: a tile a hair off
     * the grid is resampled rather than copied one pixel over. Said through {@code Double.compare}
     * because an {@code ==} between doubles is what SpotBugs fails the build on.
     */
    private static boolean isWhole(final double px) {
        return Double.compare(px, Math.rint(px)) == 0;
    }

    /** A shape in the unit square as a path in the band's pixels, every ring of it. */
    private static @NotNull Path2D unitPath(final @NotNull Geometry unit, final @NotNull PrintRect slice,
                                            final double pxPerUnit, final int top) {
        final Path2D.Double path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        for (int i = 0; i < unit.getNumGeometries(); i++) {
            if (!(unit.getGeometryN(i) instanceof final org.locationtech.jts.geom.Polygon poly)) {
                continue;
            }
            appendRing(path, poly.getExteriorRing().getCoordinates(), slice, pxPerUnit, top);
            for (int h = 0; h < poly.getNumInteriorRing(); h++) {
                appendRing(path, poly.getInteriorRingN(h).getCoordinates(), slice, pxPerUnit, top);
            }
        }
        return path;
    }

    private static void appendRing(final @NotNull Path2D.Double path,
                                   final org.locationtech.jts.geom.Coordinate @NotNull [] ring,
                                   final @NotNull PrintRect slice, final double pxPerUnit, final int top) {
        for (int i = 0; i < ring.length; i++) {
            final double x = pxAcross(ring[i].x, slice, pxPerUnit);
            final double y = pxDown(ring[i].y, slice, pxPerUnit) - top;
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        path.closePath();
    }

    /**
     * The {@code .part} to write a raster into, emptied first. {@code ImageIO.createImageOutputStream}
     * opens a file without truncating it, so a smaller raster written over a stale, longer
     * {@code .part} would end with the old one's bytes.
     */
    private static @NotNull ImageOutputStream openEmpty(final @NotNull Path part) throws IOException {
        final java.io.RandomAccessFile file = new java.io.RandomAccessFile(part.toFile(), "rw");
        try {
            file.setLength(0);
            return new javax.imageio.stream.FileImageOutputStream(file);
        } catch (final IOException | RuntimeException ex) {
            file.close();
            throw ex;
        }
    }

    private static @NotNull ImageWriter writerFor(final @NotNull String format) throws IOException {
        final Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName(format);
        if (!it.hasNext()) {
            throw new IOException("No image writer for '" + format + "'");
        }
        return it.next();
    }

    /**
     * The DPI in the file's metadata. A PNG without a {@code pHYs} chunk is just a pile of pixels
     * and the print software will guess 72.
     */
    private static IIOMetadata metadataWithDpi(final @NotNull ImageWriter writer,
                                               final @NotNull ImageWriteParam param, final double dpi,
                                               final double dpiDown, final boolean alpha) {
        try {
            final IIOMetadata meta = writer.getDefaultImageMetadata(imageType(alpha), param);
            if (meta.isReadOnly() || !meta.isStandardMetadataFormatSupported()) {
                return meta;
            }
            // Across and down separately, so the file's physical size is the paper's to within
            // what the format can store, rather than the height being worked back from the width
            final double mmPerDot = 25.4 / dpi;
            final double mmPerDotDown = 25.4 / dpiDown;
            final IIOMetadataNode dim = new IIOMetadataNode("Dimension");
            final IIOMetadataNode hps = new IIOMetadataNode("HorizontalPixelSize");
            hps.setAttribute("value", Double.toString(mmPerDot));
            final IIOMetadataNode vps = new IIOMetadataNode("VerticalPixelSize");
            vps.setAttribute("value", Double.toString(mmPerDotDown));
            dim.appendChild(hps);
            dim.appendChild(vps);
            final IIOMetadataNode root = new IIOMetadataNode(javax.imageio.metadata.IIOMetadataFormatImpl.standardMetadataFormatName);
            root.appendChild(dim);
            meta.mergeTree(javax.imageio.metadata.IIOMetadataFormatImpl.standardMetadataFormatName, root);
            return meta;
        } catch (final Exception ex) {
            log.debug("Could not set the DPI metadata: {}", ex.toString());
            return null;
        }
    }
}
