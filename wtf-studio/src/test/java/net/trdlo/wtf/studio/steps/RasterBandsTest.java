package net.trdlo.wtf.studio.steps;

import net.trdlo.wtf.compose.*;
import net.trdlo.wtf.studio.project.ProjectIo;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.tiles.*;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The studio bands a raster as the command line does. No project names a band height – a new
 * one's would be 0, and reach the raster exporter as bands one row of tiles tall – and both leave
 * it to the engine: one project, saved by the studio, exported as the
 * export step exports it and as {@code wtf-compose export} does without {@code --band-tiles},
 * advances its raster by the same bands.
 */
class RasterBandsTest {

    private static final ProviderCatalog CATALOG = ProviderCatalog.builtIn();

    /** At zoom 12 about 440 pixels across and 4 990 down, two tiles by twenty. */
    private static final LonLatBounds STRIP = new LonLatBounds(14.0, 49.5, 14.15, 50.6);

    /** One PNG strip of {@link #STRIP}, on a roll as wide as the print and its white edges. */
    private static ProjectModel strip(final Path out) {
        final Provider osm = CATALOG.require("osm");
        final ProjectModel m = new ProjectModel();
        m.providerProperty().set(osm);
        m.themeProperty().set(osm.themes().getFirst());
        m.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
        m.customProperty().set(Shapes.rectangle(STRIP));
        m.zoomProperty().set(12);
        m.mediumKindProperty().set(ProjectModel.MediumKind.ROLL);
        m.rollWidthMmProperty().set(100);
        m.printModeProperty().set(PrintSize.Mode.FIT_WIDTH);
        // One strip: the roll less the printer's 5 mm white edge kept free either side
        m.printWidthMmProperty().set(90);
        m.formats().clear();
        m.formats().add(ComposeJob.OutputFormat.PNG);
        m.outputDirProperty().set(out);
        m.baseNameProperty().set("strip");
        return m;
    }

    @Test
    @DisplayName("the studio's export and the command line band a raster of one project alike")
    void theStudioBandsAsTheCommandLineDoes(@TempDir final Path dir) throws IOException {
        final ProjectModel m = strip(dir.resolve("out"));
        final Path file = dir.resolve("strip.wtf");
        ProjectIo.save(m, file);

        // The job the export step runs, and the one the command line makes of the same file
        final ProjectModel.Built built = m.build();
        assertTrue(built.ok(), () -> "not buildable: " + built.problem());
        final ComposeJob studio = built.job();
        final ComposeJob commandLine = WtfFile.read(file).toJob(CATALOG, dir.resolve("out"));
        assertEquals(1, studio.layout().count(), "one strip");

        final TileCache cache = new TileCache(dir.resolve("cache"));
        final byte[] tile = png();
        for (final TileKey key : TilePlan.of(studio).distinctKeys()) {
            cache.store(key, tile, null);
        }

        final Bands shown = new Bands();
        ExportPanel.write(studio, cache, shown);
        final Bands printed = new Bands();
        // As ComposeCli.export runs it without --band-tiles, which leaves its band null
        new Composer().export(commandLine, CachedTileSource.cacheOnly(cache, commandLine.provider()), null,
                printed);

        assertEquals(printed.rows, shown.rows, "the studio bands as the command line does");
        // Narrow enough for the whole sixteen rows of tiles, and taller: one band and the rest
        final long band = (long) RasterExporter.DEFAULT_BAND_TILES * Mercator.TILE_SIZE;
        assertTrue(shown.height > band && shown.height <= 2 * band, () -> shown.height + " rows");
        assertEquals(List.of(band, shown.height - band), shown.rows);
    }

    private static byte[] png() throws IOException {
        final BufferedImage image = new BufferedImage(Mercator.TILE_SIZE, Mercator.TILE_SIZE,
                BufferedImage.TYPE_INT_RGB);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** What a raster export says of its bands: the rows each one advanced by, and the sheet's height. */
    private static final class Bands implements ProgressSink {
        final List<Long> rows = new ArrayList<>();
        long height;
        private boolean rasterizing;

        @Override
        public void phase(final @NotNull String name, final long total, final @NotNull Unit unit) {
            rasterizing = unit == Unit.ROWS;
            if (rasterizing) {
                height = total;
            }
        }

        @Override
        public void advance(final long delta) {
            if (rasterizing) {
                rows.add(delta);
            }
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
}
