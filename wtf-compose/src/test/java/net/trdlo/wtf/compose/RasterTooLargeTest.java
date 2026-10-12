package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.CachedTileSource;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.Refusal;
import net.trdlo.wtf.tiles.TileCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A sheet too large for a raster format is refused before the export writes anything or sets the
 * last export aside (compose-module.md §7), not when its turn comes: PDF goes first, and a TIFF
 * refused after it would leave the new PDFs in place of the last export, with no layout beside
 * them.
 */
class RasterTooLargeTest {

    /**
     * 1.6° by 1° at zoom 15 on a sheet of its own: about 35 000 by 33 000 pixels, 1.2 Gpx, which a
     * raster holds and a TIFF with alpha – holes left transparent – does not, at about 5 GB.
     */
    private static final LonLatBounds AREA = new LonLatBounds(2, 45, 3.6, 46);

    @Test
    void aPdfAndTiffJobOverTheTiffLimitIsRefusedWithTheFolderUntouched(@TempDir final Path dir) throws IOException {
        final Path out = dir.resolve("out");
        Files.createDirectories(out);
        // The last export, as it was left
        for (final String name : new String[] {"print.pdf", "print.tif", "print-s01.tif",
                "print" + OutputFiles.LAYOUT, "print" + OutputFiles.ATTRIBUTION}) {
            Files.writeString(out.resolve(name), "last export's " + name, StandardCharsets.UTF_8);
        }
        final Map<String, String> before = contents(out);

        final ComposeJob job = TestJobs.job(AREA).zoom(15).onItsOwnSheet()
                .formats(ComposeJob.OutputFormat.PDF, ComposeJob.OutputFormat.TIFF)
                .dir(out).name("print").build();
        assertEquals(1, job.layout().count(), "one sheet, the print's own");

        final Refusal refusal = assertThrows(Refusal.class, () -> new Composer().export(job,
                CachedTileSource.cacheOnly(new TileCache(dir.resolve("cache")), job.provider()),
                null, ProgressSink.IGNORE));
        assertEquals(ComposeReason.TIFF_TOO_LARGE, refusal.reason());
        assertEquals(before, contents(out), "nothing written, renamed or set aside");
    }

    private static Map<String, String> contents(final Path folder) throws IOException {
        final Map<String, String> all = new TreeMap<>();
        try (Stream<Path> files = Files.list(folder)) {
            for (final Path file : files.toList()) {
                all.put(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return all;
    }
}
