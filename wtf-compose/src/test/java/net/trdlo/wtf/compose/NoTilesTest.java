package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.CachedTileSource;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.Refusal;
import net.trdlo.wtf.tiles.TileCache;
import net.trdlo.wtf.tiles.TileSource;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A print whose regions draw no tile – neither side drawn, or only the inside of a subject that
 * misses the area – would be blank sheets with a credit. It is refused under every missing-tile
 * policy, with a reason of its own, before anything is written (D19).
 */
class NoTilesTest {

    private static final LonLatBounds BISCAY = new LonLatBounds(-10, 46, 4, 50);

    /** Far from the area, so the inside of it is nowhere on the paper. */
    private static final LonLatBounds LAPLAND = new LonLatBounds(20, 66, 24, 68);

    private static List<ComposeJob> jobs(final Path out, final MissingTilePolicy policy) {
        final TestJobs.Job base = TestJobs.job(BISCAY).onItsOwnSheet().missing(policy)
                .formats(ComposeJob.OutputFormat.PDF, ComposeJob.OutputFormat.PNG).dir(out).name("none");
        return List.of(
                TestJobs.from(base.build()).region(Regions.RegionSpec.BLANK).build(),
                TestJobs.from(base.build()).subject(Shapes.rectangle(LAPLAND))
                        .regions(new Regions(6, Regions.RegionSpec.PLAIN, Regions.RegionSpec.BLANK, null)).build());
    }

    private static @NotNull TileSource emptyCache(final Path dir, final ComposeJob job) {
        return CachedTileSource.cacheOnly(new TileCache(dir.resolve("cache")), job.provider());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(MissingTilePolicy.class)
    @DisplayName("a print whose regions draw no tile is refused under every policy, nothing written")
    void noTileIsRefused(final MissingTilePolicy policy, @TempDir final Path dir) throws IOException {
        final Path out = dir.resolve("out");
        for (final ComposeJob job : jobs(out, policy)) {
            final TilePlan.Inventory inv = TilePlan.inventory(TilePlan.of(job), emptyCache(dir, job));
            assertEquals(0, inv.needed(), () -> job.regions().describe() + " plans no tile");
            assertFalse(inv.nothingThere(), "not the provider's nothing: there is nothing to ask it");

            final Refusal ex = assertThrows(Refusal.class,
                    () -> new Composer().export(job, emptyCache(dir, job), null, ProgressSink.IGNORE),
                    () -> job.regions().describe());
            assertEquals(ComposeReason.NO_MAP_PRINTED, ex.reason());
            assertFalse(Files.exists(out), "nothing is written, not even the folder");
        }
    }
}
