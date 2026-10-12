package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.locationtech.jts.geom.Geometry;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the tests build their jobs and tiles from: the one place a test calls the
 * {@link ComposeJob} constructor, with the defaults most of them share.
 */
final class TestJobs {

    static final Provider OSM = ProviderCatalog.builtIn().require("osm");

    private TestJobs() {
    }

    /**
     * A job over this area: OpenStreetMap's first theme at zoom 6, 200 mm across on A4 landscape,
     * no overlap, on white, holes left transparent, one PDF of every sheet named "test" in the
     * working folder, no marks. A rectangle shows more map on the paper to spare, see
     * {@link SheetLayout#plan}.
     */
    static @NotNull Job job(final @NotNull Geometry fillShape) {
        return new Job(fillShape);
    }

    /** {@link #job(Geometry)} over this rectangle. */
    static @NotNull Job job(final @NotNull LonLatBounds area) {
        return new Job(Shapes.rectangle(area));
    }

    /** A job that is this one until told otherwise. */
    static @NotNull Job from(final @NotNull ComposeJob j) {
        return new Job(j.fillShape()).subject(j.subject()).provider(j.provider()).theme(j.themeId())
                .lang(j.lang()).regions(j.regions()).print(j.print()).medium(j.medium()).overlap(j.overlapMm())
                .exact(j.exactArea()).fadeFrom(j.fadeFrom())
                .background(j.backgroundColor()).missing(j.missingTiles()).formats(j.formats())
                .grouping(j.grouping()).dir(j.outputDir()).name(j.baseName()).marks(j.marks())
                .words(j.words());
    }

    static final class Job {
        private final Geometry fillShape;
        private @Nullable Geometry subject;
        private Provider provider = OSM;
        private @Nullable String themeId;
        private @Nullable String lang;
        private Regions regions = Regions.single(6);
        private @Nullable PrintRect print;
        private double widthMm = 200;
        private Medium medium = Medium.paper("A4").sheet(Medium.Orientation.LANDSCAPE);
        private double overlapMm;
        private boolean ownSheet;
        private boolean exactArea;
        private @Nullable Geometry fadeFrom;
        private int backgroundColor = 0xFFFFFF;
        private MissingTilePolicy missingTiles = MissingTilePolicy.TRANSPARENT;
        private Set<ComposeJob.OutputFormat> formats = EnumSet.of(ComposeJob.OutputFormat.PDF);
        private ComposeJob.OutputGrouping grouping = ComposeJob.OutputGrouping.SINGLE_DOCUMENT;
        private Path outputDir = Path.of(".");
        private String baseName = "test";
        private Marks marks = Marks.NONE;
        private FileWords words = FileWords.ENGLISH;

        private Job(final @NotNull Geometry fillShape) {
            this.fillShape = fillShape;
        }

        @NotNull Job subject(final @Nullable Geometry subject) {
            this.subject = subject;
            return this;
        }

        /** Its first theme, unless {@link #theme} names another. */
        @NotNull Job provider(final @NotNull Provider provider) {
            this.provider = provider;
            return this;
        }

        @NotNull Job theme(final @NotNull String themeId) {
            this.themeId = themeId;
            return this;
        }

        @NotNull Job lang(final @Nullable String lang) {
            this.lang = lang;
            return this;
        }

        @NotNull Job regions(final @NotNull Regions regions) {
            this.regions = regions;
            return this;
        }

        /** One plain region at this zoom. */
        @NotNull Job zoom(final int zoom) {
            return regions(Regions.single(zoom));
        }

        /** Both regions this one, and no boundary stroke. */
        @NotNull Job region(final Regions.@NotNull RegionSpec spec) {
            return regions(new Regions(regions.zoom(), spec, spec, null));
        }

        /** Printed this wide across the bounds of the area. */
        @NotNull Job width(final double widthMm) {
            this.widthMm = widthMm;
            this.print = null;
            return this;
        }

        @NotNull Job print(final @NotNull PrintRect print) {
            this.print = print;
            return this;
        }

        @NotNull Job medium(final @NotNull Medium medium) {
            this.medium = medium;
            return this;
        }

        @NotNull Job overlap(final double overlapMm) {
            this.overlapMm = overlapMm;
            return this;
        }

        /**
         * On one sheet cut to the print's own size and the printer's edge round it, in place of the
         * medium: no paper is spare, so the print is exactly what was asked for and its tiles are
         * the area's own.
         */
        @NotNull Job onItsOwnSheet() {
            this.ownSheet = true;
            return this;
        }

        @NotNull Job exact(final boolean exactArea) {
            this.exactArea = exactArea;
            return this;
        }

        @NotNull Job fadeFrom(final @Nullable Geometry fadeFrom) {
            this.fadeFrom = fadeFrom;
            return this;
        }

        @NotNull Job background(final int backgroundColor) {
            this.backgroundColor = backgroundColor;
            return this;
        }

        @NotNull Job missing(final @NotNull MissingTilePolicy missingTiles) {
            this.missingTiles = missingTiles;
            return this;
        }

        @NotNull Job formats(final ComposeJob.@NotNull OutputFormat first, final ComposeJob.OutputFormat... more) {
            return formats(EnumSet.of(first, more));
        }

        @NotNull Job formats(final @NotNull Set<ComposeJob.OutputFormat> formats) {
            this.formats = formats;
            return this;
        }

        @NotNull Job grouping(final ComposeJob.@NotNull OutputGrouping grouping) {
            this.grouping = grouping;
            return this;
        }

        @NotNull Job perSheet() {
            return grouping(ComposeJob.OutputGrouping.PER_SHEET);
        }

        @NotNull Job dir(final @NotNull Path outputDir) {
            this.outputDir = outputDir;
            return this;
        }

        @NotNull Job name(final @NotNull String baseName) {
            this.baseName = baseName;
            return this;
        }

        @NotNull Job marks(final @NotNull Marks marks) {
            this.marks = marks;
            return this;
        }

        /** The layout's and the attribution's, which are the engine's English until told otherwise. */
        @NotNull Job words(final @NotNull FileWords words) {
            this.words = words;
            return this;
        }

        @NotNull ComposeJob build() {
            final PrintRect p = print != null ? print : PrintSize.width(widthMm).apply(Shapes.boundsOf(fillShape));
            final double e = marks.edgeMm();
            final Medium m = !ownSheet ? medium : new Medium.CutSheet(p.widthMm() + 2 * e, p.heightMm() + 2 * e,
                    p.widthMm() >= p.heightMm() ? Medium.Orientation.LANDSCAPE : Medium.Orientation.PORTRAIT);
            return new ComposeJob(fillShape, subject, provider,
                    themeId != null ? themeId : provider.themes().getFirst().id(), lang, regions, p,
                    m, overlapMm, exactArea, fadeFrom, backgroundColor,
                    missingTiles, formats, grouping, outputDir, baseName, marks, words);
        }
    }

    // ------------------------------------------------------------------ tiles

    static byte @NotNull [] png(final @NotNull BufferedImage img) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /**
     * A cache holding a tile of one color for every tile the job needs, so that the map is that
     * color wherever it is printed and the paper is white wherever it is not.
     */
    static net.trdlo.wtf.tiles.@NotNull TileSource solidCache(final @NotNull Path dir, final @NotNull ComposeJob job,
                                                             final int rgb) throws IOException {
        final net.trdlo.wtf.tiles.TileCache cache = new net.trdlo.wtf.tiles.TileCache(dir);
        final byte[] tile = solidPng(rgb);
        for (final net.trdlo.wtf.tiles.TileKey key : TilePlan.of(job).distinctKeys()) {
            cache.store(key, tile, null);
        }
        return net.trdlo.wtf.tiles.CachedTileSource.cacheOnly(cache, job.provider());
    }

    /** A 256-pixel truecolor PNG tile of one color. */
    static byte @NotNull [] solidPng(final int rgb) throws IOException {
        return solidPng(rgb, 256);
    }

    /** A truecolor PNG of one color, this many pixels square. */
    static byte @NotNull [] solidPng(final int rgb, final int size) throws IOException {
        final BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                img.setRGB(x, y, rgb);
            }
        }
        return png(img);
    }

    /**
     * Fails unless the pixel at (px, py) is the expected color, give or take a rounding: its three
     * channels together differ from it by less than the tolerance.
     */
    static void assertColor(final int expected, final @NotNull BufferedImage img, final int px, final int py,
                            final int tolerance, final @NotNull String where) {
        final int actual = img.getRGB(px, py) & 0xFFFFFF;
        final int d = Math.abs((actual >> 16) - (expected >> 16)) + Math.abs(((actual >> 8) & 0xFF)
                - ((expected >> 8) & 0xFF)) + Math.abs((actual & 0xFF) - (expected & 0xFF));
        assertTrue(d < tolerance, () -> String.format(Locale.ROOT, "%s (%d, %d) is %06X, not %06X",
                where, px, py, actual, expected));
    }
}
