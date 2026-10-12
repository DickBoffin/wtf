package net.trdlo.wtf.studio.steps;

import net.trdlo.wtf.compose.*;
import net.trdlo.wtf.tiles.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the export step's count keeps: nothing once it is invalidated, and after a failed job a refusal's own count, so that the next press downloads rather than being refused
 * again. That "Forget empty tiles" invalidates it is the window's call, which these tests make
 * themselves; {@code ForgetEmptyTest} makes it through the Providers dialog.
 */
class ExportCountTest {

    /** Six tiles over the Bay of Biscay at zoom 6, as {@code KnownEmptyTilesTest} builds them. */
    private static final LonLatBounds BISCAY = new LonLatBounds(-10, 46, 4, 50);

    /** On a sheet the print's own size, so that no paper is spare and the print is its six tiles. */
    private static ComposeJob job(final Path dir) {
        final Provider osm = ProviderCatalog.builtIn().require("osm");
        final PrintRect print = PrintSize.width(200).apply(BISCAY);
        return new ComposeJob(Shapes.rectangle(BISCAY), null, osm, osm.themes().getFirst().id(), null,
                Regions.single(6), print,
                new Medium.CutSheet(print.widthMm(), print.heightMm(), Medium.Orientation.LANDSCAPE), 0,
                0x3366CC, MissingTilePolicy.REFUSE, EnumSet.of(ComposeJob.OutputFormat.PDF),
                ComposeJob.OutputGrouping.SINGLE_DOCUMENT, dir, "sea", Marks.NONE);
    }

    @Test
    @DisplayName("forget, then export: the refusal is kept, and what the next press downloads is the six")
    void forgetThenExport(@TempDir final Path dir) throws IOException {
        final ComposeJob job = job(dir.resolve("out"));
        final TilePlan.Plan plan = TilePlan.of(job);
        final List<TileKey> keys = List.copyOf(plan.distinctKeys());
        assertEquals(6, keys.size(), "the job is the six tiles it is meant to be");

        final TileCache cache = new TileCache(dir.resolve("cache"));
        for (final TileKey key : keys) {
            cache.recordEmpty(key);        // the provider once answered "no map here" for all six
        }
        final TileSource tiles = CachedTileSource.cacheOnly(cache, job.provider());
        final ExportCount count = new ExportCount();
        count.set(TilePlan.inventory(plan, tiles));
        assertEquals(0, count.get().missing().size(), "all six counted as empty, none missing");

        // The address was wrong and got corrected: the markers go, and the window says so
        assertEquals(6, cache.forgetEmpty(job.provider().id(), job.themeId()));
        count.invalidate();
        assertNull(count.get(), "the next press counts before it decides");

        // A count that was never told, or a refusal that was not kept: the export refuses ...
        final Composer.MissingTilesException refused = assertThrows(Composer.MissingTilesException.class,
                () -> new Composer().export(job, tiles, 0, ProgressSink.IGNORE));
        count.failed(refused);
        // ... and the next press hands the downloader the six, instead of being refused again
        assertEquals(6, count.get().missing().size());
        assertEquals(keys.size(), count.get().wanted().size());
    }

    @Test
    @DisplayName("any other failed job is counted again, since it may have changed the cache")
    void anyOtherFailureCountsAgain(@TempDir final Path dir) throws IOException {
        final ComposeJob job = job(dir.resolve("out"));
        final TileCache cache = new TileCache(dir.resolve("cache"));
        final ExportCount count = new ExportCount();
        count.set(TilePlan.inventory(TilePlan.of(job), CachedTileSource.cacheOnly(cache, job.provider())));

        count.failed(new IOException("No space left on device"));
        assertNull(count.get());
    }
}
