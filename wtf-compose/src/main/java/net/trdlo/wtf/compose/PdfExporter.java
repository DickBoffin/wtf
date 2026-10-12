package net.trdlo.wtf.compose;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.trdlo.wtf.tiles.Mercator;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.TileKey;
import net.trdlo.wtf.tiles.TileSource;

import java.awt.Color;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes sheets as PDF pages, each tile an image XObject placed by a matrix.
 * <p>
 * Nothing is ever rasterized: the page is a list of placements, and the tiles' own compressed bytes
 * are the image data. Peak memory is one tile plus the document's object table, which M0 measured
 * at about 5.8 kB per distinct image.
 */
public final class PdfExporter {

    private static final Logger log = LoggerFactory.getLogger(PdfExporter.class);

    /** What this tells a progress sink, named so an interface in another language can recognize it. */
    public static final String PHASE_PLACING = "Placing tiles, sheet %d";
    public static final String PHASE_WRITING = "Writing %s";

    /** PDF user space is 1/72 inch. */
    public static final double PT_PER_MM = 72.0 / 25.4;

    /** The specification's page limit in each direction: 200 inches. */
    public static final double MAX_PAGE_PT = 14_400;

    private PdfExporter() {
    }

    public static double mmToPt(final double mm) {
        return mm * PT_PER_MM;
    }

    /**
     * Writes one document.
     *
     * @param sheets which sheets go in; one for {@code PER_SHEET}, all of them for a single document
     * @param out    the file, written through {@code .part} and renamed
     * @return how many tiles were placed
     * @throws Composer.CanceledException when the sink is canceled before the file is in place;
     *                                    nothing is left behind then, not even the {@code .part}
     */
    public static @NotNull Result write(final @NotNull ComposeJob job,
                                        final @NotNull TilePlan.Plan plan,
                                        final @NotNull List<TilePlan.SheetPlan> sheets,
                                        final @NotNull TileSource tiles,
                                        final @NotNull Path out,
                                        final @NotNull ProgressSink progress) throws IOException {
        // Scratch-file stream cache, not the heap. M0: without it 47 000 tiles die at a 1 GB heap;
        // with it the same job runs in 732 MB. Set up before the first OutOfMemoryError, not after.
        try (final PDDocument doc = new PDDocument(IOUtils.createTempFileOnlyStreamCache())) {
            final PdfTileImages images = new PdfTileImages(doc);
            final Lettering lettering = new Lettering(doc);
            int placed = 0;
            // What goes into the file's /ID: see identify
            final MessageDigest content = PdfTileImages.sha256();
            content.update(out.getFileName().toString().getBytes(StandardCharsets.UTF_8));
            // The fade is drawn from an image of its own on every page, which a page's drawing
            // names and does not carry
            if (plan.fade() != null) {
                content.update(plan.fade().grid().digest());
            }

            for (final TilePlan.SheetPlan sp : sheets) {
                Composer.CanceledException.check(progress);
                placed += writePage(doc, images, lettering, job, plan, sp, tiles, progress, content);
            }
            // A cancel after the last tile still spares the save
            Composer.CanceledException.check(progress);
            identify(doc, content, images);
            images.logReport();

            Files.createDirectories(out.toAbsolutePath().getParent());
            final Path part = out.resolveSibling(out.getFileName() + ".part");
            progress.phase(String.format(Locale.ROOT, PHASE_WRITING, out.getFileName()), -1, ProgressSink.Unit.BYTES);
            try {
                // What save(File) does, with the sink asked between the buffer's writes: a cancel
                // stops the save within a buffer, not at its end, and the bytes are the same
                try (final OutputStream file = new BufferedOutputStream(
                        new CancelableOutputStream(Files.newOutputStream(part), progress))) {
                    doc.save(file);
                }
                // A cancel after the last write is honored here: the last moment the document can
                // still be taken back, since once renamed it is in place
                Composer.CanceledException.check(progress);
            } catch (final Throwable ex) {
                // An OutOfMemoryError too: a .part left behind is a file somebody may yet rename
                // and send to a printer
                OutputFiles.discardPart(part, ex);
                throw ex;
            }
            OutputFiles.replace(part, out);

            return new Result(placed, Files.size(out), images.tally());
        }
    }

    /** @param images how many images the document holds, and why each decoded one was */
    public record Result(int tilesPlaced, long bytes, PdfTileImages.@NotNull Tally images) { }

    /**
     * Asks the sink before every write it passes on, and throws the cancel instead of writing.
     * {@code PDDocument.save} has no callback, so this is the only way into it; behind a buffer, a
     * write is a buffer's worth, and a cancel waits for one of those rather than the whole file.
     * It passes every byte on unchanged, so the file is the one {@code save(File)} writes.
     */
    static final class CancelableOutputStream extends FilterOutputStream {
        private final ProgressSink progress;

        CancelableOutputStream(final @NotNull OutputStream out, final @NotNull ProgressSink progress) {
            super(out);
            this.progress = progress;
        }

        @Override
        public void write(final int b) throws IOException {
            Composer.CanceledException.check(progress);
            out.write(b);
        }

        @Override
        public void write(final byte[] b, final int off, final int len) throws IOException {
            Composer.CanceledException.check(progress);
            out.write(b, off, len);
        }
    }

    /**
     * Gives the document an {@code /ID} worked out from what is in it: the file's name, each page's
     * size and drawing, and every image. The same export is then the same file, byte for byte –
     * acceptance criterion 4 in docs/README.md – and two different files have two identifiers.
     * <p>
     * <b>Not the clock.</b> PDFBox makes one from the time when a document has none, and that alone
     * made every export of one project a different file. The name is in it because a sheet's own
     * file and the whole document can hold the same page and the same images. The
     * file has no creation date for the same reason: all of it follows from the project and the
     * tiles, and the one exception is the day a label prints ({@link Marks#today()}).
     *
     * @param pages the name and every page so far, as {@link #fingerprint} added them
     */
    private static void identify(final @NotNull PDDocument doc, final @NotNull MessageDigest pages,
                                 final @NotNull PdfTileImages images) {
        pages.update(images.fingerprint());
        final COSString id = new COSString(pages.digest());
        final COSArray ids = new COSArray();
        ids.add(id);
        ids.add(id);            // twice: the second changes with an update, and this file has had none
        doc.getDocument().getTrailer().setItem(COSName.ID, ids);
    }

    /** A written page into the file's {@code /ID}: its size and its drawing, see {@link #identify}. */
    private static void fingerprint(final @NotNull MessageDigest pages, final @NotNull PDPage page)
            throws IOException {
        final PDRectangle box = page.getMediaBox();
        pages.update(ByteBuffer.allocate(5 * Float.BYTES)
                .putFloat(box.getLowerLeftX()).putFloat(box.getLowerLeftY())
                .putFloat(box.getWidth()).putFloat(box.getHeight())
                .putFloat(page.getUserUnit())
                .array());
        try (final InputStream in = new DigestInputStream(page.getContents(), pages)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
    }

    private static int writePage(final @NotNull PDDocument doc, final @NotNull PdfTileImages images,
                                 final @NotNull Lettering lettering,
                                 final @NotNull ComposeJob job, final TilePlan.@NotNull Plan plan,
                                 final @NotNull TilePlan.SheetPlan sp,
                                 final @NotNull TileSource tiles, final @NotNull ProgressSink progress,
                                 final @NotNull MessageDigest pages)
            throws IOException {
        final SheetLayout layout = plan.layout();
        final SheetLayout.Sheet sheet = sp.sheet();
        final double wPt = mmToPt(sheet.widthMm());
        final double hPt = mmToPt(sheet.heightMm());

        final PDPage page = new PDPage(new PDRectangle((float) wPt, (float) hPt));
        // /UserUnit raises the 14 400 limit, but it needs PDF 1.6 and is not honored everywhere
        float userUnit = 1f;
        if (Math.max(wPt, hPt) > MAX_PAGE_PT) {
            userUnit = (float) Math.ceil(Math.max(wPt, hPt) / MAX_PAGE_PT);
            page.getCOSObject().setFloat(COSName.getPDFName("UserUnit"), userUnit);
            page.setMediaBox(new PDRectangle((float) (wPt / userUnit), (float) (hPt / userUnit)));
            log.warn("Sheet {} is {} x {} pt, past the 14 400 pt page limit; /UserUnit {} set. "
                            + "Not every consumer honors it.", sheet.index(),
                    String.format(Locale.ROOT, "%.0f", wPt), String.format(Locale.ROOT, "%.0f", hPt), userUnit);
        }
        doc.addPage(page);

        progress.phase(String.format(Locale.ROOT, PHASE_PLACING, sheet.index()),
                sp.tileCount(), ProgressSink.Unit.TILES);

        // The shapes as far as this page reaches, cut once and used for every clip and the stroke
        final TilePlan.Projected onPage = plan.projected().within(pageBox(job, sheet));

        int placed = 0;
        try (final PDPageContentStream cs =
                     new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.OVERWRITE, false)) {
            // The content stream below is written in unscaled points throughout; /UserUnit only
            // changes what the MediaBox counts as one unit, so everything drawn has to shrink to
            // match, right here, before a single operator follows.
            if (userUnit != 1f) {
                cs.transform(Matrix.getScaleInstance(1f / userUnit, 1f / userUnit));
            }

            // a background under everything, because transparent tiles exist and a transparent tile
            // over nothing prints as nothing
            //
            // And inside the area only, when the area is not a rectangle or is one to be printed
            // exactly: the print ends at the outline it was given, not at the edges of whichever
            // tiles that outline touched. The shape is the one the tiles were planned against, so
            // the two cannot disagree.
            final boolean clipToArea = job.clipsToArea();
            if (clipToArea) {
                cs.saveGraphicsState();
                if (!appendUnitPath(cs, onPage.printed(), sheet, sheet.heightMm())) {
                    appendNowhere(cs);          // the area does not reach this page: all of it is paper
                }
                cs.clipEvenOdd();
            }
            // The fade across the growth, over everything the map draws and nothing past it: the
            // stroke runs along the subject, which is whole, and the marks are the paper's.
            //
            // The map goes into a group of its own first, and the fade is laid over the group
            // once. A soft mask applies to each thing painted under it, and a tile painted at
            // half over a background painted at half shows three quarters, and the background
            // through it.
            final FadeMask fade = plan.fade();
            if (fade == null) {
                placed += drawMap(cs, images, job, sp, onPage, tiles, progress, hPt);
            } else {
                // An appearance stream is a form XObject and nothing more, and the one kind PDFBox
                // lets a page's content stream write, so the map is drawn by the same code either way
                final PDAppearanceStream map = new PDAppearanceStream(doc);
                map.setResources(new PDResources());
                map.setBBox(new PDRectangle((float) wPt, (float) hPt));
                map.getCOSObject().setItem(COSName.GROUP, transparencyGroup(null));
                try (final PDPageContentStream group = new PDPageContentStream(doc, map)) {
                    placed += drawMap(group, images, job, sp, onPage, tiles, progress, hPt);
                }
                cs.saveGraphicsState();
                cs.setGraphicsStateParameters(fadeState(doc, fade.grid(), sheet, hPt));
                cs.drawForm(map);
                cs.restoreGraphicsState();
                // The page names the group; what the group draws goes into the /ID from here
                try (final InputStream in = new DigestInputStream(map.getContents(), pages)) {
                    in.transferTo(OutputStream.nullOutputStream());
                }
            }
            if (clipToArea) {
                cs.restoreGraphicsState();      // past the area's shape, onto the paper again
            }
            strokeBoundary(cs, job, sheet, onPage.subject());
            drawMarks(cs, lettering, job, layout, sheet, hPt);
        }
        fingerprint(pages, page);
        return placed;
    }

    /**
     * The map itself: the background, then every region's tiles, each in its region's clip.
     *
     * @param onPage the shapes as far as this page reaches
     * @return how many tiles were placed
     */
    private static int drawMap(final @NotNull PDPageContentStream cs, final @NotNull PdfTileImages images,
                               final @NotNull ComposeJob job, final TilePlan.@NotNull SheetPlan sp,
                               final TilePlan.@NotNull Projected onPage, final @NotNull TileSource tiles,
                               final @NotNull ProgressSink progress, final double hPt) throws IOException {
        paintBackground(cs, job, sp, onPage.subject(), tiles, hPt);
        int placed = 0;
        for (final TilePlan.RegionTiles region : sp.regions()) {
            placed += drawRegion(cs, images, job, sp.sheet(), onPage.subject(), region, tiles, progress);
        }
        return placed;
    }

    /**
     * A transparency group's attributes, isolated: what is in it is put together on nothing, and
     * only then laid on the page.
     *
     * @param colorSpace the group's own, or null for the page's
     */
    private static @NotNull COSDictionary transparencyGroup(final @Nullable COSName colorSpace) {
        final COSDictionary group = new COSDictionary();
        group.setItem(COSName.TYPE, COSName.GROUP);
        group.setItem(COSName.S, COSName.TRANSPARENCY);
        group.setBoolean(COSName.I, true);
        if (colorSpace != null) {
            group.setItem(COSName.CS, colorSpace);
        }
        return group;
    }

    /** How many cells past the page the fade's image reaches, so that a viewer interpolates across the edge. */
    private static final int FADE_MARGIN_CELLS = 2;

    /**
     * The fade as this page's soft mask: the part of the grid under the page's map, as an image,
     * in a transparency group whose luminosity is the alpha of what is drawn while it is set –
     * the map's own group, see {@link #writePage}.
     * <p>
     * <b>A mask of its own</b>, and the reason the tiles stay copied under a fade: the mask is
     * an image apart from them, and the tiles below it are drawn as they always are.
     * Where the group paints nothing – past the image – the mask is its backdrop, black, and
     * nothing shows, as nothing is printed there either.
     */
    private static @NotNull PDExtendedGraphicsState fadeState(final @NotNull PDDocument doc,
                                                              final FadeMask.@NotNull Grid grid,
                                                              final SheetLayout.@NotNull Sheet sheet,
                                                              final double hPt) throws IOException {
        final PrintRect slice = sheet.slice();
        final double mmPerUnit = slice.mmPerUnit();
        final int i0 = grid.column(slice.x0()) - FADE_MARGIN_CELLS;
        final int j0 = grid.row(slice.y0()) - FADE_MARGIN_CELLS;
        final int w = grid.column(slice.x1()) + FADE_MARGIN_CELLS + 1 - i0;
        final int h = grid.row(slice.y1()) + FADE_MARGIN_CELLS + 1 - j0;
        final byte[] cells = grid.crop(i0, j0, w, h);
        final java.io.ByteArrayOutputStream deflated = new java.io.ByteArrayOutputStream(cells.length / 4);
        try (java.util.zip.DeflaterOutputStream zip = new java.util.zip.DeflaterOutputStream(deflated)) {
            zip.write(cells);
        }
        final PDImageXObject image = new PDImageXObject(doc, new java.io.ByteArrayInputStream(deflated.toByteArray()),
                COSName.FLATE_DECODE, w, h, 8, PDDeviceGray.INSTANCE);
        // Half a millimeter a cell is fine enough; smoothed, it has no steps at all
        image.getCOSObject().setItem(COSName.INTERPOLATE, COSBoolean.TRUE);

        // Where those cells lie on the page, in points
        final float left = (float) mmToPt((grid.x0 + i0 * grid.cell - slice.x0()) * mmPerUnit);
        final float top = (float) (hPt - mmToPt((grid.y0 + j0 * grid.cell - slice.y0()) * mmPerUnit));
        final float width = (float) mmToPt(w * grid.cell * mmPerUnit);
        final float height = (float) mmToPt(h * grid.cell * mmPerUnit);

        final PDFormXObject group = new PDFormXObject(doc);
        group.setResources(new PDResources());
        group.setBBox(new PDRectangle(left, top - height, width, height));
        group.getCOSObject().setItem(COSName.GROUP, transparencyGroup(COSName.DEVICEGRAY));
        try (final PDFormContentStream gcs = new PDFormContentStream(group)) {
            gcs.drawImage(image, new Matrix(width, 0, 0, height, left, top - height));
        }

        final COSDictionary mask = new COSDictionary();
        mask.setItem(COSName.TYPE, COSName.MASK);
        mask.setItem(COSName.S, COSName.LUMINOSITY);
        mask.setItem(COSName.G, group);
        final PDExtendedGraphicsState state = new PDExtendedGraphicsState();
        state.getCOSObject().setItem(COSName.SMASK, mask);
        return state;
    }

    /**
     * The background under the map, which is what a tile's transparent pixels and a tile the
     * provider says does not exist look like.
     * <p>
     * <b>Under {@code TRANSPARENT} a missing tile has none under it</b> (D28): the page is left
     * unpainted there, for whatever the file is laid over. Under every other policy, and on a
     * page without a hole, the background is one rectangle.
     * <p>
     * A hole is its region's, so it is a hole only on that region's side of the subject: the rest
     * of its square is the other region's, which draws its own tile there or shows the background.
     * So the background goes down once with every hole left out, and each side then paints back
     * whatever of the other side's holes falls on it.
     *
     * @param subject the subject as far as this page reaches, in the unit square
     */
    private static void paintBackground(final @NotNull PDPageContentStream cs, final @NotNull ComposeJob job,
                                        final TilePlan.@NotNull SheetPlan sp, final @Nullable Geometry subject,
                                        final @NotNull TileSource tiles, final double hPt) throws IOException {
        final SheetLayout.Sheet sheet = sp.sheet();
        final float mapW = (float) mmToPt(sheet.widthMm()), mapH = (float) mmToPt(sheet.heightMm());
        final Rectangle2D map = new Rectangle2D.Float(0, (float) hPt - mapH, mapW, mapH);
        cs.setNonStrokingColor(new Color(job.backgroundColor()));
        final Map<TilePlan.Side, Area> holes = job.missingTiles() == MissingTilePolicy.TRANSPARENT
                ? holes(sp, tiles, map, hPt) : Map.of();
        if (holes.isEmpty()) {
            cs.addRect(0, (float) hPt - mapH, mapW, mapH);
            cs.fill();
            return;
        }
        final Area all = new Area();
        holes.values().forEach(all::add);
        final Area painted = new Area(map);
        painted.subtract(all);
        if (!painted.isEmpty()) {
            fill(cs, painted);
        }
        if (subject == null || job.regions().isUniform()) {
            return;                 // one region, or two drawn alike and not clipped: a hole is a hole
        }
        for (final TilePlan.Side side : TilePlan.Side.values()) {
            final Area others = new Area(all);
            final Area own = holes.get(side);
            if (own != null) {
                others.subtract(own);
            }
            if (others.isEmpty()) {
                continue;
            }
            cs.saveGraphicsState();
            clipToRegion(cs, job, sheet, subject, side);
            fill(cs, others);
            cs.restoreGraphicsState();
        }
    }

    /**
     * Each region's missing tiles on this sheet, as the squares of the map they would have
     * covered, in points. A tile the provider says does not exist is not one of them.
     */
    private static @NotNull Map<TilePlan.Side, Area> holes(final TilePlan.@NotNull SheetPlan sp,
                                                            final @NotNull TileSource tiles,
                                                            final @NotNull Rectangle2D map, final double hPt) {
        final PrintRect slice = sp.sheet().slice();
        final double mmPerUnit = slice.mmPerUnit();
        final Map<TilePlan.Side, Area> holes = new EnumMap<>(TilePlan.Side.class);
        for (final TilePlan.RegionTiles region : sp.regions()) {
            final double tileUnit = 1.0 / Mercator.tileCount(region.zoom());
            final double tilePt = mmToPt(tileUnit * mmPerUnit);
            // Squares side by side, as one path wound one way, which an Area turns into their union
            final Path2D squares = new Path2D.Double(Path2D.WIND_NON_ZERO);
            for (final TileKey key : region.keys()) {
                if (tiles.isCached(key) || tiles.isKnownEmpty(key)) {
                    continue;
                }
                final double x = mmToPt((key.x() * tileUnit - slice.x0()) * mmPerUnit);
                final double y = hPt - mmToPt((key.y() * tileUnit - slice.y0()) * mmPerUnit) - tilePt;
                squares.append(new Rectangle2D.Double(x, y, tilePt, tilePt), false);
            }
            final Area area = new Area(squares);
            area.intersect(new Area(map));
            if (!area.isEmpty()) {
                holes.merge(region.side(), area, (a, b) -> {
                    a.add(b);
                    return a;
                });
            }
        }
        return holes;
    }

    /** A shape of straight edges – rectangles, and what they add up to – filled. */
    private static void fill(final @NotNull PDPageContentStream cs, final @NotNull Shape shape) throws IOException {
        final double[] c = new double[6];
        final PathIterator it = shape.getPathIterator(null);
        for (; !it.isDone(); it.next()) {
            switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO -> cs.moveTo((float) c[0], (float) c[1]);
                case PathIterator.SEG_LINETO -> cs.lineTo((float) c[0], (float) c[1]);
                case PathIterator.SEG_CLOSE -> cs.closePath();
                default -> throw new IllegalStateException("A curve in a shape made of rectangles");
            }
        }
        if (it.getWindingRule() == PathIterator.WIND_EVEN_ODD) {
            cs.fillEvenOdd();
        } else {
            cs.fill();
        }
    }

    /** How far past the page the shapes are cut, whatever the stroke. */
    private static final double CUT_MARGIN_MM = 1;

    /**
     * The page in the unit square, grown by a margin all round: the box a page's shapes are cut
     * to.
     * <p>
     * The margin keeps the edges the cut adds off the page. A millimeter, so that no dot is ever
     * decided on one, and a stroke width more: the stroke runs along those edges too, and its
     * round joins reach half a width past them.
     */
    private static @NotNull PrintRect pageBox(final @NotNull ComposeJob job,
                                              final SheetLayout.@NotNull Sheet sheet) {
        final Regions.BoundaryStroke stroke = job.regions().boundary();
        final double marginMm = CUT_MARGIN_MM + (stroke == null ? 0 : stroke.widthMm());
        return sheet.slice().grownByMm(2 * marginMm, 2 * marginMm);
    }

    // -------------------------------------------------------------------------------------------
    // Seam marks and the credit – where they go is SeamMarks' business, how they look is here
    // -------------------------------------------------------------------------------------------

    /**
     * A mid gray for the seam marks: easy to find on the sheet, little ink, and hidden under the
     * neighbor anyway. A light gray is hard to find on paper at all. See D14.
     */
    private static final Color FAINT = new Color(0x96, 0x96, 0x96);
    private static final Color CREDIT = new Color(0x3A, 0x3A, 0x3A);
    private static final float HAIRLINE_MM = 0.15f;
    /** The scale bar's line, and the white either side of it, as the credit's letters have. */
    static final float BAR_MM = 0.25f;
    static final float BAR_HALO_MM = 0.4f;

    /**
     * The face every line of text is set in, Liberation Sans (D29), bundled with its license beside
     * it.
     * <p>
     * <b>Not one of the standard fourteen.</b> Helvetica from those covers Western European
     * letters and not the Czech ř or č, so Plzeň printed as Plzen on the credit, the one mark
     * meant to be read. This one covers Latin, Greek and Cyrillic, and only the glyphs a document
     * uses are embedded – a few kilobytes, where the whole face is four hundred.
     */
    static final String FONT = "LiberationSans-Regular.ttf";

    /** The face, loaded into its document when the first line of text needs it. */
    static final class Lettering {

        private final PDDocument doc;
        private @Nullable PDFont font;

        Lettering(final @NotNull PDDocument doc) {
            this.doc = doc;
        }

        @NotNull PDFont font() throws IOException {
            if (font == null) {
                try (final InputStream in = PdfExporter.class.getResourceAsStream(FONT)) {
                    if (in == null) {
                        throw new IOException("The bundled font " + FONT + " is missing from the build");
                    }
                    font = PDType0Font.load(doc, in, true);
                }
            }
            return font;
        }
    }

    private static void drawMarks(final @NotNull PDPageContentStream cs, final @NotNull Lettering lettering,
                                  final @NotNull ComposeJob job,
                                  final @NotNull SheetLayout layout, final SheetLayout.@NotNull Sheet sheet,
                                  final double hPt) throws IOException {
        final List<SeamMarks.Mark> marks = SeamMarks.forSheet(job, layout, sheet);
        // Every halo under a line meant to be read before any of its ink, so that no line's white
        // covers where another line meets it
        for (final SeamMarks.Mark mark : marks) {
            if (mark instanceof SeamMarks.Line l && l.style() == SeamMarks.Style.CREDIT) {
                drawLine(cs, l, Color.WHITE, BAR_MM + BAR_HALO_MM, hPt);
            }
        }
        for (final SeamMarks.Mark mark : marks) {
            switch (mark) {
                case SeamMarks.Line l -> {
                    if (l.style() == SeamMarks.Style.CREDIT) {
                        drawLine(cs, l, CREDIT, BAR_MM, hPt);
                    } else {
                        drawLine(cs, l, FAINT, HAIRLINE_MM, hPt);
                    }
                }
                case SeamMarks.Text t -> drawText(cs, lettering.font(), t, hPt);
            }
        }
    }

    /**
     * One straight line. A hairline has butt ends, as it was placed to fit its strip; the scale
     * bar's run half a width on, so its ticks close the corners they meet the bar in.
     */
    private static void drawLine(final @NotNull PDPageContentStream cs, final SeamMarks.@NotNull Line l,
                                 final @NotNull Color color, final float widthMm, final double hPt)
            throws IOException {
        cs.saveGraphicsState();
        cs.setStrokingColor(color);
        cs.setLineWidth((float) mmToPt(widthMm));
        if (l.style() == SeamMarks.Style.CREDIT) {
            cs.setLineCapStyle(2);
        }
        cs.moveTo((float) mmToPt(l.x1()), (float) (hPt - mmToPt(l.y1())));
        cs.lineTo((float) mmToPt(l.x2()), (float) (hPt - mmToPt(l.y2())));
        cs.stroke();
        cs.restoreGraphicsState();
    }

    /**
     * One line of text at its anchor and angle.
     * <p>
     * The credit gets a white outline drawn first and the dark letters over it – the halo every
     * map uses for a label, so it reads on sea and on forest alike.
     */
    private static void drawText(final @NotNull PDPageContentStream cs, final @NotNull PDFont font,
                                 final SeamMarks.@NotNull Text t, final double hPt) throws IOException {
        final String text = encodable(font, t.text());
        final float size = (float) t.sizePt();
        final double width = font.getStringWidth(text) / 1000 * size;
        final double angle = Math.toRadians(t.angle());
        // To the start of the line: an END anchor names where the text stops, a MIDDLE its middle
        final double back = switch (t.anchor()) {
            case START -> 0;
            case MIDDLE -> width / 2;
            case END -> width;
        };
        final double x = mmToPt(t.x()) - back * Math.cos(angle);
        final double y = hPt - mmToPt(t.y()) - back * Math.sin(angle);
        final Matrix at = Matrix.getRotateInstance(angle, (float) x, (float) y);

        cs.saveGraphicsState();
        if (t.style() == SeamMarks.Style.CREDIT) {
            cs.beginText();
            cs.setFont(font, size);
            cs.setTextMatrix(at);
            cs.setStrokingColor(Color.WHITE);
            cs.setLineWidth(size / 6);
            // Round, as the raster's: a mitered outline spikes at the point of a 1 or a parenthesis
            cs.setLineJoinStyle(1);
            cs.setRenderingMode(RenderingMode.STROKE);
            cs.showText(text);
            cs.endText();
        }
        cs.beginText();
        cs.setFont(font, size);
        cs.setTextMatrix(at);
        cs.setNonStrokingColor(t.style() == SeamMarks.Style.CREDIT ? CREDIT : FAINT);
        cs.setRenderingMode(RenderingMode.FILL);
        cs.showText(text);
        cs.endText();
        cs.restoreGraphicsState();
    }

    /**
     * The text with every character the face has no glyph for replaced by its plain letter, or by
     * a question mark when it has no plain letter either.
     * <p>
     * Liberation Sans carries Plzeň and Αθήνα and Київ, but not 北京. A project named in a script
     * it lacks should still export, with question marks in the label, rather than fail on the one
     * character.
     */
    static @NotNull String encodable(final @NotNull PDFont font, final @NotNull String text) {
        final StringBuilder sb = new StringBuilder(text.length());
        text.codePoints().forEach(c -> {
            final String one = Character.toString(c);
            if (canEncode(font, one)) {
                sb.append(one);
                return;
            }
            final String plain = java.text.Normalizer.normalize(one, java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{M}", "");
            sb.append(!plain.isEmpty() && canEncode(font, plain) ? plain : "?");
        });
        return sb.toString();
    }

    private static boolean canEncode(final @NotNull PDFont font, final @NotNull String s) {
        try {
            font.encode(s);
            return true;
        } catch (final IllegalArgumentException | IOException ex) {
            return false;
        }
    }

    private static int drawRegion(final @NotNull PDPageContentStream cs, final @NotNull PdfTileImages images,
                                  final @NotNull ComposeJob job, final SheetLayout.@NotNull Sheet sheet,
                                  final @Nullable Geometry subject,
                                  final @NotNull TilePlan.RegionTiles region, final @NotNull TileSource tiles,
                                  final @NotNull ProgressSink progress) throws IOException {
        cs.saveGraphicsState();
        clipToRegion(cs, job, sheet, subject, region.side());

        final PrintRect slice = sheet.slice();
        final double mmPerUnit = slice.mmPerUnit();
        final double tileUnit = 1.0 / Mercator.tileCount(region.zoom());
        final double tilePt = mmToPt(tileUnit * mmPerUnit);
        final double hPt = mmToPt(sheet.heightMm());

        int placed = 0;
        for (final TileKey key : region.keys()) {
            Composer.CanceledException.check(progress);
            final byte[] data = tiles.get(key);
            final PDImageXObject image = data == null ? null : images.of(key, data, region.effect());
            if (image == null) {
                // Sea, or past the provider's coverage, is not a hole but the map, and the
                // background under the page is what it looks like, whatever the policy says.
                // Anything else was reported by the inventory and allowed by the policy, so the
                // hole is deliberate: TRANSPARENT painted no background here.
                if (!tiles.isKnownEmpty(key) && job.missingTiles() == MissingTilePolicy.PLACEHOLDER) {
                    drawPlaceholder(cs, key, slice, mmPerUnit, tileUnit, tilePt, hPt);
                }
                // Done with, if not placed: the phase counts every tile of the plan, or a sheet
                // with sea on it ends short of its count
                progress.advance(1);
                continue;
            }

            // the tile's top-left in the unit square, then in millimeters from the sheet's top-left
            final double xMm = (key.x() * tileUnit - slice.x0()) * mmPerUnit;
            final double topMm = (key.y() * tileUnit - slice.y0()) * mmPerUnit;
            // PDF y runs up from the bottom of the page
            final double yPt = hPt - mmToPt(topMm) - tilePt;

            cs.drawImage(image, new Matrix((float) tilePt, 0, 0, (float) tilePt,
                    (float) mmToPt(xMm), (float) yPt));
            placed++;
            progress.advance(1);
        }
        cs.restoreGraphicsState();
        return placed;
    }

    /** The red of the proof's marks. */
    private static final Color PLACEHOLDER_RED = new Color(0xE0, 0x60, 0x60);

    /** A deliberately ugly marker, so a proof cannot be mistaken for a finished print. */
    private static void drawPlaceholder(final @NotNull PDPageContentStream cs, final @NotNull TileKey key,
                                        final @NotNull PrintRect slice, final double mmPerUnit,
                                        final double tileUnit, final double tilePt, final double hPt)
            throws IOException {
        final double xMm = (key.x() * tileUnit - slice.x0()) * mmPerUnit;
        final double topMm = (key.y() * tileUnit - slice.y0()) * mmPerUnit;
        final float x = (float) mmToPt(xMm);
        final float y = (float) (hPt - mmToPt(topMm) - tilePt);
        final float s = (float) tilePt;

        cs.saveGraphicsState();
        cs.setStrokingColor(PLACEHOLDER_RED);
        cs.setLineWidth(Math.max(0.5f, s / 64));
        cs.addRect(x, y, s, s);
        cs.moveTo(x, y);
        cs.lineTo(x + s, y + s);
        cs.moveTo(x, y + s);
        cs.lineTo(x + s, y);
        cs.stroke();
        cs.restoreGraphicsState();
    }

    /**
     * Clips to one side of the subject outline. Exact, vector, and free: no mask raster, no
     * blending, and a multipart outline with holes costs nothing extra – which matters, because
     * real country outlines are multipart with holes.
     *
     * @param subject the subject as far as this page reaches, in the unit square
     */
    private static void clipToRegion(final @NotNull PDPageContentStream cs, final @NotNull ComposeJob job,
                                     final SheetLayout.@NotNull Sheet sheet, final @Nullable Geometry subject,
                                     final TilePlan.@NotNull Side side) throws IOException {
        if (subject == null || job.regions().isUniform()) {
            return;
        }
        final double wPt = mmToPt(sheet.widthMm());
        final double hPt = mmToPt(sheet.heightMm());

        if (side == TilePlan.Side.OUTSIDE) {
            // the page with the subject as a hole: both paths, even-odd rule, no geometry subtraction
            cs.moveTo(0, 0);
            cs.lineTo((float) wPt, 0);
            cs.lineTo((float) wPt, (float) hPt);
            cs.lineTo(0, (float) hPt);
            cs.closePath();
        }
        if (!appendUnitPath(cs, subject, sheet, sheet.heightMm()) && side == TilePlan.Side.INSIDE) {
            appendNowhere(cs);              // the subject does not reach this page: nothing is inside it
        }
        // Even-odd on both sides, as the raster clips: under nonzero winding a hole whose ring runs
        // the same way as its shell is filled, and an enclave printed as the country around it
        cs.clipEvenOdd();
    }

    /**
     * Every ring of a shape in the unit square – the subject, or the area – into the current path,
     * for a clip or the stroke.
     *
     * @return false when the shape has no ring on this page, and nothing was added
     */
    private static boolean appendUnitPath(final @NotNull PDPageContentStream cs,
                                          final @NotNull Geometry unit,
                                          final SheetLayout.@NotNull Sheet sheet,
                                          final double pageHeightMm) throws IOException {
        boolean any = false;
        for (int g = 0; g < unit.getNumGeometries(); g++) {
            if (!(unit.getGeometryN(g) instanceof final Polygon poly)) {
                continue;
            }
            any |= appendRing(cs, poly.getExteriorRing().getCoordinates(), sheet, pageHeightMm);
            for (int h = 0; h < poly.getNumInteriorRing(); h++) {
                any |= appendRing(cs, poly.getInteriorRingN(h).getCoordinates(), sheet, pageHeightMm);
            }
        }
        return any;
    }

    /**
     * A point's worth of square off the page's bottom-left corner, for a clip that is to show
     * nothing. A clip operator with no path before it is not a clip every viewer reads the same way.
     */
    private static void appendNowhere(final @NotNull PDPageContentStream cs) throws IOException {
        cs.addRect(-2, -2, 1, 1);
    }

    private static boolean appendRing(final @NotNull PDPageContentStream cs,
                                      final @NotNull Coordinate @NotNull [] ring,
                                      final SheetLayout.@NotNull Sheet sheet,
                                      final double pageHeightMm) throws IOException {
        if (ring.length < 3) {
            return false;
        }
        final PrintRect slice = sheet.slice();
        final double mmPerUnit = slice.mmPerUnit();
        final double hPt = mmToPt(pageHeightMm);
        for (int i = 0; i < ring.length; i++) {
            final float x = (float) mmToPt((ring[i].x - slice.x0()) * mmPerUnit);
            final float y = (float) (hPt - mmToPt((ring[i].y - slice.y0()) * mmPerUnit));
            if (i == 0) {
                cs.moveTo(x, y);
            } else {
                cs.lineTo(x, y);
            }
        }
        cs.closePath();
        return true;
    }

    /** @param subject the subject as far as this page reaches, in the unit square */
    private static void strokeBoundary(final @NotNull PDPageContentStream cs, final @NotNull ComposeJob job,
                                       final SheetLayout.@NotNull Sheet sheet,
                                       final @Nullable Geometry subject) throws IOException {
        final Regions.BoundaryStroke stroke = job.regions().boundary();
        if (stroke == null || subject == null) {
            return;
        }
        cs.saveGraphicsState();
        cs.setStrokingColor(new Color(stroke.rgb()));
        cs.setLineWidth((float) mmToPt(stroke.widthMm()));
        // Round, because an outline has corners far sharper than a right angle, and a mitered
        // one draws a spike up to five widths long out of a fjord or a border's switchback
        cs.setLineJoinStyle(1);
        if (appendUnitPath(cs, subject, sheet, sheet.heightMm())) {
            cs.stroke();
        }
        cs.restoreGraphicsState();
    }

    /**
     * The file name for a sheet, or for the whole document, on the policy alone – {@code -proof}
     * exactly when {@link ComposeJob#isProofOnly()} does. {@link Composer} always resolves a plan
     * first and calls the four-argument overload with the {@code proof} it found there; this one
     * is a test's, for a job whose policy and plan are known to agree.
     */
    public static @NotNull Path fileFor(final @NotNull ComposeJob job, final SheetLayout.@Nullable Sheet sheet,
                                        final @NotNull String extension) {
        return fileFor(job, sheet, extension, job.isProofOnly());
    }

    /** The file name for a sheet, or for the whole document, {@code proof} said by the caller. */
    static @NotNull Path fileFor(final @NotNull ComposeJob job, final SheetLayout.@Nullable Sheet sheet,
                                 final @NotNull String extension, final boolean proof) {
        return job.outputDir().resolve(stemOf(job, sheet, proof) + "." + extension);
    }

    /**
     * The name before its extension: the project's, the sheet's number, and {@code -proof} when
     * the caller says this file is one. The file name is what goes to a print shop, so it is where
     * a proof has to say it is one – and a print with nothing missing must not, even under a
     * policy that would have marked a hole had there been one (see
     * {@link Composer.ExportReport#proof()}).
     */
    private static @NotNull String stemOf(final @NotNull ComposeJob job, final SheetLayout.@Nullable Sheet sheet,
                                          final boolean proof) {
        return job.baseName() + (sheet == null ? "" : "-" + sheet.fileSuffix()) + (proof ? "-proof" : "");
    }
}
