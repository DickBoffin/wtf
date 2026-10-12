package net.trdlo.wtf.compose;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.Geometry;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.Refusal;
import net.trdlo.wtf.tiles.TileKey;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

/**
 * Everything needed to produce a set of print files. Built by the CLI from a {@code .wtf}
 * file, or by the GUI from the five steps.
 *
 * @param fillShape       {@code buffer(simplify(area shape))} – what is printed
 * @param subject         the cut line, exactly as given, or null for one region
 * @param lang            the map language, for a theme lettered in one; ignored by the rest
 * @param exactArea       whether a rectangular area is printed to its edge and no further: the
 *                        spare paper that whole sheets and "fill a box exactly" would fill with
 *                        more map stays paper, as it does around an outline. See
 *                        {@link #clipsToArea}
 * @param fadeFrom        where a fade across the area's growth starts, in longitude and latitude:
 *                        the shape the area was grown from – the subject's outline, box or hull,
 *                        or a Custom shape made one. The map is whole inside it and fades to
 *                        nothing at the edge of what is printed, which on paper is white – see
 *                        {@link FadeMask}. Null for a print with a hard edge
 * @param backgroundColor painted under every sheet, because transparent tiles exist: Kartverket's
 *                        topographic tiles really do use their alpha, and a transparent tile over
 *                        nothing prints as nothing
 * @param marks           what is printed besides the map – see {@link SeamMarks}
 * @param words           the words of the layout and the attribution written beside the print:
 *                        the studio's in the interface language, or {@link FileWords#ENGLISH}, the
 *                        command line's
 */
public record ComposeJob(
        @NotNull Geometry fillShape,
        @Nullable Geometry subject,
        @NotNull Provider provider,
        @NotNull String themeId,
        @Nullable String lang,
        @NotNull Regions regions,
        @NotNull PrintRect print,
        @NotNull Medium medium,
        double overlapMm,
        boolean exactArea,
        @Nullable Geometry fadeFrom,
        int backgroundColor,
        @NotNull MissingTilePolicy missingTiles,
        @NotNull Set<OutputFormat> formats,
        @NotNull OutputGrouping grouping,
        @NotNull Path outputDir,
        @NotNull String baseName,
        @NotNull Marks marks,
        @NotNull FileWords words) {

    public enum OutputFormat {
        /** One image XObject per tile, bytes copied. The default. */
        PDF,
        /** A flat raster per sheet. */
        PNG,
        /** A flat raster per sheet, for print shops that ask for it. */
        TIFF;

        public boolean isPdf() {
            return this == PDF;
        }
    }

    public enum OutputGrouping {
        /** One file per sheet. The default: six 130 MB files survive a USB stick, one 750 MB file does not. */
        PER_SHEET,
        /** One document with a page per sheet. */
        SINGLE_DOCUMENT
    }

    /**
     * True when the policy this job is given <b>can</b> leave a hole a printer must not see.
     * <p>
     * This is the policy alone – it says nothing about whether the plan this job resolves to
     * actually has a missing tile. Asked before a plan exists, as the studio asks it to decide
     * whether to confirm before exporting, it is all there is to go on. Once a plan and an
     * inventory exist, {@link Composer.ExportReport#proof()} is the true answer: a proof is a file
     * with a hole in it, and a policy that permits one is not the same as one it drew.
     */
    public boolean isProofOnly() {
        return !missingTiles.isPrintable() && missingTiles != MissingTilePolicy.REFUSE;
    }

    public ComposeJob {
        formats = formats.isEmpty() ? EnumSet.of(OutputFormat.PDF) : EnumSet.copyOf(formats);
        // Every output file is the base name resolved against the folder, so it is made one name
        // that stays there, whoever built the job: the studio, the CLI or a project file
        baseName = FileNames.safe(baseName, "");
        if (baseName.isBlank()) {
            throw new IllegalArgumentException("A base name for the output files is required");
        }
        if (subject == null && !regions.isUniform()) {
            throw new IllegalArgumentException(
                    "Two regions were configured but there is no subject shape to divide them");
        }
        final Provider.Theme theme = provider.requireTheme(themeId);
        // The map's zooms, and its @2x tiles are of the same ones. Asked before anything lists a
        // tile: a zoom past them is no print, and over a country it is tens of millions of tiles
        // that every plan would list before a server said so.
        if (regions.zoom() < provider.minZoomOf(theme) || regions.zoom() > provider.maxZoomOf(theme)) {
            throw new Refusal(ComposeReason.ZOOM_OUT_OF_RANGE, provider.displayName(), themeId,
                    provider.minZoomOf(theme), provider.maxZoomOf(theme), regions.zoom());
        }
        if (regions.scale() != 1 && !provider.offersRetina(theme)) {
            throw new Refusal(ComposeReason.NO_RETINA, provider.displayName(), themeId);
        }
    }

    /** With the layout and the attribution in English, as the command line writes them. */
    public ComposeJob(final @NotNull Geometry fillShape, final @Nullable Geometry subject,
                      final @NotNull Provider provider, final @NotNull String themeId,
                      final @Nullable String lang, final @NotNull Regions regions,
                      final @NotNull PrintRect print, final @NotNull Medium medium, final double overlapMm,
                      final boolean exactArea, final @Nullable Geometry fadeFrom, final int backgroundColor,
                      final @NotNull MissingTilePolicy missingTiles, final @NotNull Set<OutputFormat> formats,
                      final @NotNull OutputGrouping grouping, final @NotNull Path outputDir,
                      final @NotNull String baseName, final @NotNull Marks marks) {
        this(fillShape, subject, provider, themeId, lang, regions, print, medium, overlapMm,
                exactArea, fadeFrom, backgroundColor, missingTiles, formats, grouping, outputDir,
                baseName, marks, FileWords.ENGLISH);
    }

    /**
     * A job whose rectangle, if the area is one, fills the sheets as the spare paper allows, whose
     * edge does not fade, and whose layout and attribution are in English.
     */
    public ComposeJob(final @NotNull Geometry fillShape, final @Nullable Geometry subject,
                      final @NotNull Provider provider, final @NotNull String themeId,
                      final @Nullable String lang, final @NotNull Regions regions,
                      final @NotNull PrintRect print, final @NotNull Medium medium, final double overlapMm,
                      final int backgroundColor, final @NotNull MissingTilePolicy missingTiles,
                      final @NotNull Set<OutputFormat> formats, final @NotNull OutputGrouping grouping,
                      final @NotNull Path outputDir, final @NotNull String baseName,
                      final @NotNull Marks marks) {
        this(fillShape, subject, provider, themeId, lang, regions, print, medium, overlapMm,
                false, null, backgroundColor, missingTiles, formats, grouping, outputDir,
                baseName, marks, FileWords.ENGLISH);
    }

    /**
     * Whether the map stops at the area's edge, the rest of the sheet being paper: always for an
     * outline or a hull, and for a rectangle when it is to be printed exactly. A rectangle that is
     * not grows with the print instead – the spare paper becomes more map (D34). The one question
     * both the tile plan and the two exporters ask, so that what is downloaded and what is printed
     * cannot disagree.
     */
    public boolean clipsToArea() {
        return exactArea || !Shapes.isRectangle(fillShape);
    }

    /** One tile of this job's map, in its language where the map has one, at the print's scale. */
    public @NotNull TileKey key(final int zoom, final int x, final int y) {
        return provider.key(themeId, lang, zoom, x, y).atScale(regions.scale());
    }

    public @NotNull SheetLayout layout() {
        return SheetLayout.plan(print, medium, overlapMm, marks.edgeMm());
    }

}
