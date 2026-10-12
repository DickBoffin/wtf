package net.trdlo.wtf.studio.steps;

import net.trdlo.wtf.compose.ComposeJob;
import net.trdlo.wtf.compose.Medium;
import net.trdlo.wtf.compose.MissingTilePolicy;
import net.trdlo.wtf.compose.PrintRect;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.compose.TileEffect;
import net.trdlo.wtf.compose.TilePlan;
import net.trdlo.wtf.studio.FileText;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.studio.Units;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Mercator;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import net.trdlo.wtf.tiles.TileCache;
import net.trdlo.wtf.tiles.TileKey;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two files the export step writes beside the print – the layout and the attribution – in the
 * interface language, which the engine has none of: each line in the words the window uses for the
 * same thing, every number as the window writes it and every length in its unit. The command line's
 * are the engine's English ({@code FileWordsTest}, {@code ComposeCliTest}).
 * <p>
 * The test JVM runs in English; a test about Czech chooses it, and everything is put back after.
 */
class LayoutAndAttributionTest {

    private static final ProviderCatalog CATALOG = ProviderCatalog.builtIn();
    private static final Locale CZECH = Locale.forLanguageTag("cs-CZ");
    private static final LonLatBounds BOHEMIA = new LonLatBounds(12.0, 48.5, 18.9, 51.1);
    private static final LonLatBounds MIDDLE = new LonLatBounds(14.0, 49.5, 15.5, 50.4);

    private Locale language;
    private Units units;

    @BeforeEach
    void remember() {
        language = I18n.locale();
        units = Units.current();
    }

    @AfterEach
    void putBack() {
        I18n.setLocale(language);
        Units.use(units.key());
    }

    /** Bohemia on two A4 sheets side by side at zoom 6, written under this name to this folder. */
    private static @NotNull ProjectModel twoSheets(final @NotNull Path dir, final @NotNull String name) {
        final ProjectModel m = new ProjectModel();
        final Provider osm = CATALOG.require("osm");
        m.providerProperty().set(osm);
        m.themeProperty().set(osm.themes().getFirst());
        m.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
        m.customProperty().set(Shapes.rectangle(BOHEMIA));
        m.paperProperty().set(Medium.paper("A4"));
        m.orientationProperty().set(Medium.Orientation.PORTRAIT);
        m.overlapMmProperty().set(20);
        m.sizeByProperty().set(ProjectModel.SizeBy.SHEETS);
        m.countAcrossProperty().set(true);
        m.sheetCountProperty().set(2);
        m.zoomProperty().set(6);
        m.formats().clear();
        m.formats().add(ComposeJob.OutputFormat.PDF);
        m.outputDirProperty().set(dir.resolve("out"));
        m.baseNameProperty().set(name);
        return m;
    }

    private static @NotNull ComposeJob job(final @NotNull ProjectModel m) {
        final ProjectModel.Built built = m.build();
        assertTrue(built.ok(), () -> "not buildable: " + built.problem());
        return java.util.Objects.requireNonNull(built.job());
    }

    /**
     * The job exported as the step exports it, from a cache with every tile it needs but the first
     * few of its plan, and its layout and attribution read back, a line each.
     */
    private static @NotNull List<List<String>> export(final @NotNull ComposeJob job, final @NotNull Path dir,
                                                      final int missing) throws IOException {
        final TileCache cache = new TileCache(dir.resolve("cache"));
        final ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(Mercator.TILE_SIZE, Mercator.TILE_SIZE, BufferedImage.TYPE_INT_RGB), "png", png);
        final List<TileKey> keys = List.copyOf(TilePlan.of(job).distinctKeys());
        for (final TileKey key : keys.subList(missing, keys.size())) {
            cache.store(key, png.toByteArray(), null);
        }
        ExportPanel.write(job, cache, ProgressSink.IGNORE);
        return List.of(
                Files.readAllLines(job.outputDir().resolve(job.baseName() + "-layout.txt"), StandardCharsets.UTF_8),
                Files.readAllLines(job.outputDir().resolve(job.baseName() + "-attribution.txt"), StandardCharsets.UTF_8));
    }

    /** The scale line's numbers, as the window writes them in the language it is in now. */
    private static @NotNull String scaleAndDpi(final @NotNull ComposeJob job) {
        final PrintRect print = job.print();
        return "1:" + I18n.integer(Math.round(print.mapScale())) + ", "
                + I18n.integer(Math.round(print.dpi(job.regions().zoom(), job.regions().scale()))) + " DPI";
    }

    @Test
    @DisplayName("a job built in Czech writes the layout and the attribution in Czech")
    void aJobBuiltInCzechWritesCzech(@TempDir final Path dir) throws IOException {
        I18n.setLocale(CZECH);
        Units.use("mm");
        final ComposeJob job = job(twoSheets(dir, "Čelákovicko"));
        assertSame(FileText.WORDS, job.words(), "the studio's words, not the engine's English");
        assertTrue(job.print().mapScale() > 1_000_000, "a scale with its thousands grouped");

        final List<List<String>> files = export(job, dir, 0);
        assertEquals(List.of(
                "Plán listů z WTF: Čelákovicko",
                "Listy 210 × 297 mm, na výšku",
                "Přiblížení 6. Celá mapa: barvy beze změny.",
                "Měřítko " + scaleAndDpi(job),
                "",
                "Tisk 400 × 297 mm. Listy: 2 (2 na šířku, 1 na výšku). Překryv: 20,0 mm.",
                "",
                "  [ 1][ 2]",
                "",
                "  List 1 (sloupec 1, řádek 1): 0 mm zleva, 0 mm shora, 210 × 297 mm",
                "  List 2 (sloupec 2, řádek 1): 190 mm zleva, 0 mm shora, 210 × 297 mm",
                "",
                "Lepení: list vpravo a list pod ním jdou navrch.",
                "  Z každého listu odřízněte bílý okraj vlevo a nahoře až po potištěnou plochu.",
                "  Vpravo a dole ho nechte, jak je: zakryje ho sousední list, a s ním i čísla",
                "  listů a šipky, které jsou tam vytištěné."), files.get(0));
        assertFalse(files.get(0).get(3).matches(".*1:\\d{1,3},\\d{3}.*"),
                () -> "no comma between thousands, which a Czech reader takes for a decimal one: " + files.get(0).get(3));
        assertEquals(List.of(
                "© OpenStreetMap contributors",
                "Mapa: standard",
                "Podmínky: https://operations.osmfoundation.org/policies/tiles/",
                "Vytvořeno programem WTF."), files.get(1), "the provider's own credit as it gives it");
    }

    @Test
    @DisplayName("in Czech, the subject's two sides, missing tiles and a proof are said in Czech too")
    void theRegionsAndAProofInCzech(@TempDir final Path dir) throws IOException {
        I18n.setLocale(CZECH);
        Units.use("mm");
        final ProjectModel m = twoSheets(dir, "tisk");
        m.subjectProperty().set(Shapes.rectangle(MIDDLE));
        m.subjectOriginProperty().set("drawn");
        m.outsideDiffersProperty().set(true);
        m.outsideEffectProperty().set(TileEffect.FADED);
        m.insideEffectProperty().set(new TileEffect(0.5, 0.2, -0.1));
        m.boundaryStrokedProperty().set(true);
        m.missingTilesProperty().set(MissingTilePolicy.PLACEHOLDER);
        final List<String> layout = export(job(m), dir, 1).get(0);
        assertEquals("Přiblížení 6. Uvnitř předmětu: vlastní barvy (sytost 0,50, světlost 0,20, kontrast "
                + I18n.number(-0.1, 2) + "). Mimo něj: barvy vybledlé. Obrys předmětu je obtažený.", layout.get(2));
        assertEquals(List.of("", "!! Chybějící dlaždice: 1. Jsou označené červeně.",
                "!! TOHLE JE NÁTISK. Neposílejte ho do tiskárny.", ""), layout.subList(4, 8));

        m.missingTilesProperty().set(MissingTilePolicy.TRANSPARENT);
        m.insideDrawnProperty().set(false);
        final List<String> blank = export(job(m), dir.resolve("blank"), 2).get(0);
        assertEquals("Přiblížení 6. Uvnitř předmětu: bílý papír. Mimo něj: barvy vybledlé. Obrys předmětu je obtažený.",
                blank.get(2));
        assertEquals(List.of("", "!! Chybějící dlaždice: 2. Zůstaly prázdné: na papíře bílé.", ""),
                blank.subList(4, 7), "a print with holes left blank is no proof");
    }

    @Test
    @DisplayName("in English the files say what the window says, which is not quite the command line's")
    void inEnglishTheFilesSpeakAsTheWindow(@TempDir final Path dir) throws IOException {
        I18n.setLocale(Locale.ENGLISH);
        Units.use("mm");
        final ComposeJob job = job(twoSheets(dir, "Wall"));
        final List<List<String>> files = export(job, dir, 0);
        assertEquals(List.of(
                "WTF layout for Wall",
                "210 × 297 mm sheets, portrait",
                "Zoom 6. Whole map: colors unchanged.",
                "Scale " + scaleAndDpi(job),
                "",
                "Print 400 × 297 mm. Sheets: 2 (2 across, 1 down). Overlap: 20.0 mm.",
                "",
                "  [ 1][ 2]",
                "",
                "  Sheet 1 (column 1, row 1): 0 mm from the left, 0 mm from the top, 210 × 297 mm",
                "  Sheet 2 (column 2, row 1): 190 mm from the left, 0 mm from the top, 210 × 297 mm",
                "",
                "Hanging: the sheet to the right and the sheet below go on top.",
                "  Cut the white edge off the left and the top of each sheet, along the ink.",
                "  Leave the right and the bottom as they are; the neighbor covers them, and",
                "  any sheet numbers and arrows printed there with them."), files.get(0));
        assertEquals(List.of(
                "© OpenStreetMap contributors",
                "Map: standard",
                "Terms: https://operations.osmfoundation.org/policies/tiles/",
                "Produced by WTF."), files.get(1));
    }

    @Test
    @DisplayName("lengths are in inches when the window's are, with no millimeter beside them")
    void lengthsInTheWindowsUnit(@TempDir final Path dir) throws IOException {
        I18n.setLocale(Locale.ENGLISH);
        Units.use("inch");
        final List<String> layout = export(job(twoSheets(dir, "Wall")), dir, 0).get(0);
        assertEquals("8.27 × 11.69 in sheets, portrait", layout.get(1));
        assertEquals("Print 15.75 × 11.69 in. Sheets: 2 (2 across, 1 down). Overlap: 0.787 in.", layout.get(5));
        assertEquals("  Sheet 2 (column 2, row 1): 7.48 in from the left, 0 in from the top, 8.27 × 11.69 in",
                layout.get(10));
        assertTrue(layout.stream().noneMatch(line -> line.contains(" mm")), () -> String.join("\n", layout));
    }
}
