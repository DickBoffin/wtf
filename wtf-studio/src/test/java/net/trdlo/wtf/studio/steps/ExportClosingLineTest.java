package net.trdlo.wtf.studio.steps;

import net.trdlo.wtf.compose.*;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.studio.ProgressText;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.tiles.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The export step's closing line says how many of the PDF's images went in decoded rather
 * than copied, and why, on a line of its own – for a map whose tiles have an alpha channel, exported
 * as the step exports it – and an export that copies every tile says nothing more. The words are
 * the bundle's, so the Czech is {@code CzechTest}'s.
 */
class ExportClosingLineTest {

    private static final ProviderCatalog CATALOG = ProviderCatalog.builtIn();

    /** A corner of Prague at zoom 12: a handful of tiles. */
    private static final LonLatBounds AREA = new LonLatBounds(14.4, 50.05, 14.5, 50.1);

    private final Locale before = I18n.locale();

    @BeforeEach
    void english() {
        I18n.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void restore() {
        I18n.setLocale(before);
    }

    @Test
    @DisplayName("an export that decodes tiles says how many and why, and one that copies says nothing more")
    void theClosingLineSaysWhatWasDecoded(@TempDir final Path dir) throws IOException {
        final String decoded = closingLineOf(dir.resolve("rgba"), BufferedImage.TYPE_INT_ARGB);
        final String[] lines = decoded.split("\n");
        assertEquals(2, lines.length, decoded);
        assertTrue(lines[0].startsWith("Files: 3. Sheets: 1. Tiles placed: "), decoded);
        // One image: every tile has the same bytes, and a PDF holds them once
        assertEquals("Images decoded rather than copied: 1 of 1 (alpha channel: 1).", lines[1]);

        final String copied = closingLineOf(dir.resolve("rgb"), BufferedImage.TYPE_INT_RGB);
        assertTrue(copied.startsWith("Files: 3. Sheets: 1. Tiles placed: "), copied);
        assertFalse(copied.contains("\n"), copied);
        assertFalse(copied.contains("decoded"), copied);
    }

    /** What the step says when its export of a small PDF, every tile these pixels, is done. */
    private static String closingLineOf(final Path dir, final int pixels) throws IOException {
        final Provider osm = CATALOG.require("osm");
        final ProjectModel m = new ProjectModel();
        m.providerProperty().set(osm);
        m.themeProperty().set(osm.themes().getFirst());
        m.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
        m.customProperty().set(Shapes.rectangle(AREA));
        m.zoomProperty().set(12);
        m.mediumKindProperty().set(ProjectModel.MediumKind.ROLL);
        m.rollWidthMmProperty().set(100);
        m.printModeProperty().set(PrintSize.Mode.FIT_WIDTH);
        // One strip: the roll less the printer's 5 mm white edge kept free either side
        m.printWidthMmProperty().set(90);
        m.formats().clear();
        m.formats().add(ComposeJob.OutputFormat.PDF);
        m.outputDirProperty().set(dir.resolve("out"));
        m.baseNameProperty().set("print");
        final ProjectModel.Built built = m.build();
        assertTrue(built.ok(), () -> "not buildable: " + built.problem());

        final TileCache cache = new TileCache(dir.resolve("cache"));
        final byte[] tile = png(pixels);
        for (final TileKey key : TilePlan.of(built.job()).distinctKeys()) {
            cache.store(key, tile, null);
        }
        return ProgressText.report(ExportPanel.write(built.job(), cache, ProgressSink.IGNORE));
    }

    private static byte[] png(final int type) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(Mercator.TILE_SIZE, Mercator.TILE_SIZE, type), "png", out);
        return out.toByteArray();
    }
}
