package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.CachedTileSource;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.NothingThereException;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.TileCache;
import net.trdlo.wtf.tiles.TileKey;
import net.trdlo.wtf.tiles.TileSource;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A tile the provider answered 204 or 404 for is sea, or past the edge of its coverage: it is the
 * map, not a hole in it. The inventory counts it apart, a coastal export does not refuse over it, and
 * no proof marker is drawn on it – whatever the missing-tile policy says. A print that is <b>only</b>
 * that – nothing cached, every tile known empty – is a different case: it is refused under every
 * policy, since a policy says what goes in a gap and it has none; it is not a coast, it is the whole
 * print missing the place it was meant to show.
 * <p>
 * Six tiles over the Bay of Biscay, recorded as not existing. The background is a color, so the
 * sea is that color wherever it is printed, and a marker anywhere shows as something else.
 */
class KnownEmptyTilesTest {

    private static final int SEA = 0x3366CC;

    /** Three columns and two rows of tiles at zoom 6, with no edge on a tile boundary. */
    private static final LonLatBounds BISCAY = new LonLatBounds(-10, 46, 4, 50);

    private static ComposeJob job(final Path dir, final MissingTilePolicy policy,
                                  final ComposeJob.OutputFormat format, final ComposeJob.OutputFormat... more) {
        return TestJobs.job(BISCAY).onItsOwnSheet().background(SEA).missing(policy).formats(format, more)
                .dir(dir).name("sea").build();
    }

    /** A cache in which the provider has answered 204 for the first {@code recorded} of the job's tiles. */
    private static TileSource sea(final Path dir, final ComposeJob job, final int recorded) throws IOException {
        final TileCache cache = new TileCache(dir.resolve("cache"));
        final List<TileKey> keys = List.copyOf(TilePlan.of(job).distinctKeys());
        assertEquals(6, keys.size(), "the job is the six tiles it is meant to be");
        for (final TileKey key : keys.subList(0, recorded)) {
            cache.recordEmpty(key);
        }
        return CachedTileSource.cacheOnly(cache, job.provider());
    }

    /** One real tile of land, in this color, and the rest recorded as 204 – an ordinary coastal print. */
    private static TileSource landAndSea(final Path dir, final ComposeJob job, final int land)
            throws IOException {
        final TileCache cache = new TileCache(dir.resolve("cache"));
        final List<TileKey> keys = List.copyOf(TilePlan.of(job).distinctKeys());
        assertEquals(6, keys.size(), "the job is the six tiles it is meant to be");
        cache.store(keys.getFirst(), TestJobs.solidPng(land), null);
        for (final TileKey key : keys.subList(1, keys.size())) {
            cache.recordEmpty(key);
        }
        return CachedTileSource.cacheOnly(cache, job.provider());
    }

    private static Path file(final Composer.ExportReport report, final String extension) {
        final Path out = report.files().stream().filter(p -> p.toString().endsWith(extension))
                .findFirst().orElseThrow(() -> new AssertionError("no " + extension + " written"));
        assertTrue(Files.isRegularFile(out), out + " is on disk");
        return out;
    }

    /** The first pixel in the box that is not the sea, give or take a rounding, or null. */
    private static @Nullable String notSea(final BufferedImage img, final int x0, final int y0,
                                           final int x1, final int y1) {
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                final int rgb = img.getRGB(x, y) & 0xFFFFFF;
                for (int shift = 0; shift <= 16; shift += 8) {
                    if (Math.abs((rgb >> shift & 0xFF) - (SEA >> shift & 0xFF)) > 3) {
                        return String.format(Locale.ROOT, "pixel %d,%d is %06X", x, y, rgb);
                    }
                }
            }
        }
        return null;
    }

    @Test
    @DisplayName("six tiles recorded as 204 are empty, not missing")
    void emptyIsNotMissing(@TempDir final Path dir) throws IOException {
        final ComposeJob job = job(dir, MissingTilePolicy.REFUSE, ComposeJob.OutputFormat.PDF);
        final TilePlan.Inventory inv = TilePlan.inventory(TilePlan.of(job), sea(dir, job, 6));

        assertEquals(6, inv.needed());
        assertEquals(0, inv.cached());
        assertEquals(6, inv.empty(), "sea is an answer, not a gap");
        assertEquals(List.of(), inv.missing());
        assertTrue(inv.isComplete());

        assertEquals(1, inv.byRegion().size(), "one region, since there is no subject");
        final TilePlan.Inventory.Entry region = inv.byRegion().getFirst();
        assertEquals(6, region.empty());
        assertEquals(0, region.missing(), "and the region says the same");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(MissingTilePolicy.class)
    @DisplayName("and a coastal print of them exports under every policy, the one refusing a gap included")
    void theExportRuns(final MissingTilePolicy policy, @TempDir final Path dir) throws IOException {
        final ComposeJob job = job(dir, policy,
                ComposeJob.OutputFormat.PDF, ComposeJob.OutputFormat.PNG);
        // Real land beside the sea: the print has something in it, so no policy refuses it – only a
        // print that is sea from edge to edge is refused (nothingCachedIsRefused)
        final TileSource tiles = landAndSea(dir, job, 0x000000);
        final Composer.ExportReport report = assertDoesNotThrow(
                () -> new Composer().export(job, tiles, 0, ProgressSink.IGNORE));
        file(report, ".pdf");
        file(report, ".png");
        assertFalse(report.proof(), "and nothing is missing from it, so it is no proof");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(MissingTilePolicy.class)
    @DisplayName("a print that is sea from edge to edge is refused under every policy, nothing written")
    void nothingCachedIsRefused(final MissingTilePolicy policy, @TempDir final Path dir) throws IOException {
        final Path out = dir.resolve("out");
        final ComposeJob job = job(out, policy,
                ComposeJob.OutputFormat.PDF, ComposeJob.OutputFormat.PNG);
        final TileSource tiles = sea(dir, job, 6);
        final TilePlan.Inventory inv = TilePlan.inventory(TilePlan.of(job), tiles);
        assertTrue(inv.nothingThere(), "nothing cached, and it is entirely known empty");
        assertTrue(inv.isComplete(), "isComplete on its own does not say so – nothingThere is separate");

        // Not the policy's to let through, as a gap is: a policy says what goes where a tile is
        // missing, and here none is – there is only the background, which nobody means to print
        final NothingThereException ex = assertThrows(NothingThereException.class,
                () -> new Composer().export(job, tiles, 0, ProgressSink.IGNORE));
        assertEquals(6, ex.tiles(), "every tile the print needs");
        assertEquals("osm", ex.provider().id());
        assertFalse(Files.exists(out), "nothing is written, not even the folder");
    }

    @Test
    @DisplayName("a coastal sheet's placing phase ends at its count, though one tile of six is placed")
    void thePhaseCountsTheSea(@TempDir final Path dir) throws IOException {
        final ComposeJob job = job(dir.resolve("out"), MissingTilePolicy.REFUSE,
                ComposeJob.OutputFormat.PDF);
        final TilePlan.Plan plan = TilePlan.of(job);
        final Counted progress = new Counted();

        final PdfExporter.Result result = PdfExporter.write(job, plan, plan.sheets(),
                landAndSea(dir, job, 0x000000), dir.resolve("out").resolve("sea.pdf"), progress);

        assertEquals(1, result.tilesPlaced(), "the land is placed, the sea is not");
        final String placing = String.format(Locale.ROOT, PdfExporter.PHASE_PLACING, 1);
        assertEquals(6, progress.totals.get(placing), "the phase is the sheet's six tiles");
        assertEquals(6, progress.advanced.get(placing),
                "and it ends at six: the five of sea advance it as the land does");
    }

    /** Each phase's total and how far it was advanced, by name. */
    private static final class Counted implements ProgressSink {
        final Map<String, Long> totals = new HashMap<>();
        final Map<String, Long> advanced = new HashMap<>();
        private @Nullable String phase;

        @Override
        public void phase(final @NotNull String name, final long total, final @NotNull Unit unit) {
            phase = name;
            totals.put(name, total);
            advanced.put(name, 0L);
        }

        @Override
        public void advance(final long delta) {
            advanced.merge(Objects.requireNonNull(phase), delta, Long::sum);
        }

        @Override
        public void note(final @NotNull String detail) {
        }

        @Override
        public boolean isCanceled() {
            return false;
        }

        @Override
        public void finished(final @NotNull String summary) {
        }
    }

    @Test
    @DisplayName("under PLACEHOLDER they print as the background, not as red crosses")
    void noMarkerOnTheSea(@TempDir final Path dir) throws IOException {
        final ComposeJob job = job(dir, MissingTilePolicy.PLACEHOLDER,
                ComposeJob.OutputFormat.PDF, ComposeJob.OutputFormat.PNG);
        // A print of nothing but sea is refused, so one tile is real – painted the sea's own color,
        // so that a marker on any of the five known empty is the one thing on the page that is not sea
        final Composer.ExportReport report = new Composer().export(job, landAndSea(dir, job, SEA), 0,
                ProgressSink.IGNORE);

        final BufferedImage png = ImageIO.read(file(report, ".png").toFile());
        final String rasterOff = notSea(png, 0, 0, png.getWidth(), png.getHeight());
        assertNull(rasterOff, () -> "the PNG is sea everywhere, but its " + rasterOff);

        try (final PDDocument doc = Loader.loadPDF(file(report, ".pdf").toFile())) {
            final BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(0, 36);
            // The page, less a rim where the map's edge meets the page's
            final String pageOff = notSea(img, 2, 2, img.getWidth() - 2, img.getHeight() - 2);
            assertNull(pageOff, () -> "the PDF's map is sea everywhere, but its " + pageOff);
        }
    }

    @Test
    @DisplayName("a tile never fetched beside them is still missing, still refused, and still marked")
    void aGapIsStillAGap(@TempDir final Path dir) throws IOException {
        final ComposeJob job = job(dir, MissingTilePolicy.REFUSE, ComposeJob.OutputFormat.PNG);
        final TileSource tiles = sea(dir, job, 5);

        final TilePlan.Inventory inv = TilePlan.inventory(TilePlan.of(job), tiles);
        assertEquals(5, inv.empty());
        assertEquals(1, inv.missing().size(), "only the tile nobody asked for is missing");

        final Composer.MissingTilesException ex = assertThrows(Composer.MissingTilesException.class,
                () -> new Composer().export(job, tiles, 0, ProgressSink.IGNORE));
        assertEquals(1, ex.inventory().missing().size());

        // And the marker the test above looks for is one it would see
        final ComposeJob proof = job(dir, MissingTilePolicy.PLACEHOLDER, ComposeJob.OutputFormat.PNG);
        final BufferedImage png = ImageIO.read(
                file(new Composer().export(proof, tiles, 0, ProgressSink.IGNORE), ".png").toFile());
        assertNotNull(notSea(png, 0, 0, png.getWidth(), png.getHeight()), "the gap is marked");
    }
}
