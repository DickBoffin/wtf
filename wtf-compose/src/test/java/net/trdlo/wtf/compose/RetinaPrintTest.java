package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.Refusal;
import net.trdlo.wtf.tiles.TileCache;
import net.trdlo.wtf.tiles.TileKey;
import net.trdlo.wtf.tiles.CachedTileSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A print at scale 2 is made of the zoom's {@code @2x} tiles: the same sheets, the same ground,
 * twice the pixels each way – and a map without them refuses the scale rather than printing the
 * usual tiles as though they were.
 */
class RetinaPrintTest {

    private static final LonLatBounds AREA = new LonLatBounds(12.37, 48.61, 16.83, 50.94);
    private static final int ONE = 0x336699;

    /** A map whose address says where @2x goes. */
    private static final Provider RETINA = new Provider("retina", "Retina", "http://127.0.0.1:1/{z}/{x}/{y}{scale}.png",
            null, 0, 19, null, List.of(new Provider.Theme("t", "T", null, null, null, null, false, null)),
            null, "test", null, null, null, true, null, null, null);

    @Test
    @DisplayName("a raster from @2x tiles is the same sheet in twice the pixels each way")
    void twiceThePixels(@TempDir final Path dir) throws IOException {
        final ComposeJob one = TestJobs.job(AREA).provider(RETINA).zoom(6).formats(ComposeJob.OutputFormat.PNG)
                .perSheet().dir(dir).name("one").build();
        final ComposeJob two = TestJobs.from(one).regions(new Regions(6, 2, Regions.RegionSpec.PLAIN,
                Regions.RegionSpec.PLAIN, null)).name("two").build();
        assertEquals(List.of(2), TilePlan.of(two).distinctKeys().stream().map(TileKey::scale).distinct().toList(),
                "the job asks for @2x tiles");

        final BufferedImage a = ImageIO.read(export(one, dir.resolve("cache1"), 256).toFile());
        final BufferedImage b = ImageIO.read(export(two, dir.resolve("cache2"), 512).toFile());
        assertEquals(2 * a.getWidth(), b.getWidth(), 1);
        assertEquals(2 * a.getHeight(), b.getHeight(), 1);
        assertEquals(ONE, b.getRGB(b.getWidth() / 2, b.getHeight() / 2) & 0xFFFFFF);
        assertEquals(one.print().dpi(7), two.print().dpi(6, 2), 1e-9, "the DPI of the zoom above");
    }

    @Test
    @DisplayName("a map without @2x tiles refuses a print at scale 2")
    void aMapWithoutThemRefuses() {
        final Refusal r = assertThrows(Refusal.class, () -> TestJobs.job(AREA).zoom(6)
                .regions(new Regions(6, 2, Regions.RegionSpec.PLAIN, Regions.RegionSpec.PLAIN, null)).build());
        assertEquals(ComposeReason.NO_RETINA, r.reason());
    }

    private static Path export(final ComposeJob job, final Path cacheDir, final int pixels) throws IOException {
        final TileCache cache = new TileCache(cacheDir);
        final byte[] tile = TestJobs.solidPng(ONE, pixels);
        for (final TileKey key : TilePlan.of(job).distinctKeys()) {
            cache.store(key, tile, null);
        }
        return new Composer().export(job, CachedTileSource.cacheOnly(cache, job.provider()), 0, ProgressSink.IGNORE)
                .files().stream().filter(p -> p.toString().endsWith(".png")).findFirst().orElseThrow();
    }
}
