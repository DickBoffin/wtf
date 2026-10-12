package net.trdlo.wtf.compose;

import net.trdlo.wtf.tiles.CachedTileSource;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.ProviderCatalog;
import net.trdlo.wtf.tiles.TileCache;
import net.trdlo.wtf.tiles.TileKey;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two files an export writes beside its print, the layout and the attribution: laid out by the
 * engine, every line in the job's words, and in the engine's own English when the job has none –
 * the command line's, which are the files as they have always been, byte for byte. The studio's
 * words are tested where the studio builds its jobs.
 */
class FileWordsTest {

    private static final LonLatBounds BOHEMIA = new LonLatBounds(12.0, 48.5, 18.9, 51.1);
    private static final LonLatBounds MIDDLE = new LonLatBounds(14.0, 49.5, 15.5, 50.4);

    /** The text of a file as written: the lines given, each ended as the platform ends a line. */
    private static @NotNull String lines(final @NotNull String text) {
        return text.replace("\n", System.lineSeparator());
    }

    /**
     * Exports the job from a cache that has every tile it needs but the first few of its plan, and
     * returns what is in its layout and its attribution.
     */
    private static @NotNull List<String> export(final @NotNull ComposeJob job, final @NotNull Path dir,
                                                final int missing) throws IOException {
        final TileCache cache = new TileCache(dir.resolve("cache"));
        final byte[] tile = TestJobs.solidPng(0x88AACC);
        final List<TileKey> keys = List.copyOf(TilePlan.of(job).distinctKeys());
        assertTrue(keys.size() >= missing, () -> keys.size() + " tiles");
        for (final TileKey key : keys.subList(missing, keys.size())) {
            cache.store(key, tile, null);
        }
        new Composer().export(job, CachedTileSource.cacheOnly(cache, job.provider()), null, ProgressSink.IGNORE);
        return List.of(Files.readString(job.outputDir().resolve(job.baseName() + "-layout.txt"), StandardCharsets.UTF_8),
                Files.readString(job.outputDir().resolve(job.baseName() + "-attribution.txt"), StandardCharsets.UTF_8));
    }

    /** A proof: @2x tiles on A3 sheets, a treatment of its own inside the subject and nothing outside it. */
    private static @NotNull TestJobs.Job proof(final @NotNull Path dir) {
        return TestJobs.job(BOHEMIA).subject(Shapes.rectangle(MIDDLE))
                .provider(ProviderCatalog.builtIn().require("mapy-com")).theme("outdoor").lang("cs")
                .regions(new Regions(7, 2, new Regions.RegionSpec(new TileEffect(0.5, 0.2, -0.1), true),
                        Regions.RegionSpec.BLANK, null))
                .width(800).medium(Medium.paper("A3").sheet(Medium.Orientation.LANDSCAPE)).overlap(12.5)
                .missing(MissingTilePolicy.PLACEHOLDER).dir(dir.resolve("out")).name("test")
                .marks(new Marks(5, false, false, false, false, null, "", ""));
    }

    @Test
    @DisplayName("a job given no words writes the files in the engine's English, as they have always been")
    void theEnginesEnglishIsAsItWas(@TempDir final Path dir) throws IOException {
        final List<String> proof = export(proof(dir.resolve("proof")).build(), dir.resolve("proof"), 1);
        assertEquals(lines("""
                WTF layout for test
                420 x 297 mm sheets, landscape
                zoom 7 @2x, inside saturation 0.50, lightness +0.20, contrast -0.10, outside not drawn
                Scale 1:619,500, 40 DPI

                !! 1 tile(s) were not cached; the policy was to draw a visible marker (proofs only).
                !! THIS IS A PROOF. Do not send it to a printer.

                Print 828 x 582 mm, 4 sheet(s) in 2 column(s) x 2 row(s), overlap 12.5 mm

                  [ 1][ 2]
                  [ 3][ 4]

                  Sheet 1 (col 1, row 1) at 0,0 mm, 420 x 297 mm
                  Sheet 2 (col 2, row 1) at 408,0 mm, 420 x 297 mm
                  Sheet 3 (col 1, row 2) at 0,285 mm, 420 x 297 mm
                  Sheet 4 (col 2, row 2) at 408,285 mm, 420 x 297 mm

                Hanging: the sheet to the right and the sheet below go on top.
                  Cut the white edge off the left and the top of each sheet, along the ink.
                  Leave the right and the bottom as they are; the neighbor covers them, and
                  any sheet numbers and arrows printed there with them.
                """), proof.get(0));
        assertEquals(lines("""
                © Seznam.cz a.s., © OpenStreetMap contributors
                Theme: outdoor
                Terms: https://developer.mapy.com/terms/
                Produced by WTF.
                """), proof.get(1));

        // A roll of strips, the subject in its colors and the rest faded under an outline, holes left
        final List<String> roll = export(TestJobs.job(BOHEMIA).subject(Shapes.rectangle(MIDDLE))
                .regions(new Regions(7, 1, Regions.RegionSpec.PLAIN, Regions.RegionSpec.FADED,
                        new Regions.BoundaryStroke(0.5, 0xFF0000)))
                .width(2000).medium(new Medium.Roll(914, Medium.Axis.VERTICAL)).overlap(20)
                .dir(dir.resolve("roll")).name("wall").marks(new Marks(5, false, false, false, false, null, "", ""))
                .build(), dir.resolve("roll"), 8);
        assertEquals(lines("""
                WTF layout for wall
                914 mm roll, vertical strips
                zoom 7, inside none, outside saturation 0.00, lightness +0.35, contrast -0.45, boundary stroked
                Scale 1:247,800, 8 DPI

                !! 8 tile(s) were not cached; the policy was to leave it transparent.

                Print 2702 x 1168 mm, 3 sheet(s) in 3 column(s) x 1 row(s), overlap 20.0 mm

                  [ 1][ 2][ 3]

                  Sheet 1 (col 1, row 1) at 0,0 mm, 914 x 1168 mm
                  Sheet 2 (col 2, row 1) at 894,0 mm, 914 x 1168 mm
                  Sheet 3 (col 3, row 1) at 1788,0 mm, 914 x 1168 mm

                Hanging: the sheet to the right and the sheet below go on top.
                  Cut the white edge off the left and the top of each sheet, along the ink.
                  Leave the right and the bottom as they are; the neighbor covers them, and
                  any sheet numbers and arrows printed there with them.
                """), roll.get(0));

        // One strip, which has nothing to hang
        final List<String> one = export(TestJobs.job(BOHEMIA).regions(Regions.single(6))
                .width(300).medium(new Medium.Roll(1524, Medium.Axis.HORIZONTAL))
                .dir(dir.resolve("one")).name("one").build(), dir.resolve("one"), 0);
        assertEquals(lines("""
                WTF layout for one
                1524 mm roll, horizontal strips
                zoom 6, one region
                Scale 1:1,652,001, 27 DPI

                Print 300 x 1524 mm, 1 sheet(s) in 1 column(s) x 1 row(s), overlap 0.0 mm

                  [ 1]

                  Sheet 1 (col 1, row 1) at 0,0 mm, 300 x 1524 mm
                """), one.get(0));
        assertEquals(lines("""
                © OpenStreetMap contributors
                Theme: standard
                Terms: https://operations.osmfoundation.org/policies/tiles/
                Produced by WTF.
                """), one.get(1));
    }

    @Test
    @DisplayName("a project file's job, the command line's, has the engine's English")
    void aProjectFilesJobIsInEnglish(@TempDir final Path dir) {
        final ComposeJob job = WtfFile.sample("osm", "standard", 14, 50, 15, 51, 6, 200)
                .toJob(ProviderCatalog.builtIn(), dir);
        assertSame(FileWords.ENGLISH, job.words());
        assertSame(FileWords.ENGLISH, TestJobs.from(job).build().words(), "and a copy keeps them");
    }

    /** Words that say which line they were asked for, so the engine's layout of them shows. */
    private static final class Tagged implements FileWords {
        @Override
        public @NotNull String title(final @NotNull String name) {
            return "title " + name;
        }

        @Override
        public @NotNull String medium(final @NotNull Medium medium) {
            return "medium " + (medium instanceof Medium.CutSheet ? "sheets" : "roll");
        }

        @Override
        public @NotNull String regions(final @NotNull Regions regions) {
            return "regions at " + regions.zoom();
        }

        @Override
        public @NotNull String scale(final double mapScale, final double dpi) {
            return "scale and DPI";
        }

        @Override
        public @NotNull String missing(final int tiles, final @NotNull MissingTilePolicy policy) {
            return "missing " + tiles + ", " + policy;
        }

        @Override
        public @NotNull String proof() {
            return "proof";
        }

        @Override
        public @NotNull String print(final @NotNull SheetLayout layout) {
            return "print of " + layout.count();
        }

        @Override
        public @NotNull String sheet(final SheetLayout.@NotNull Sheet sheet) {
            return "sheet " + sheet.index();
        }

        @Override
        public @NotNull List<String> hanging() {
            return List.of("on top", "the edges: " + "word ".repeat(20).strip(), "the rest");
        }

        @Override
        public @NotNull String theme(final @NotNull String themeId) {
            return "map " + themeId;
        }

        @Override
        public @NotNull String terms(final @NotNull String url) {
            return "terms at " + url;
        }

        @Override
        public @NotNull String producedBy() {
            return "made";
        }
    }

    @Test
    @DisplayName("a job given words has every line of both files said in them, laid out by the engine")
    void everyLineIsInTheJobsWords(@TempDir final Path dir) throws IOException {
        final List<String> files = export(proof(dir).words(new Tagged()).build(), dir, 1);
        assertEquals(lines("""
                title test
                medium sheets
                regions at 7
                scale and DPI

                !! missing 1, PLACEHOLDER
                !! proof

                print of 4

                  [ 1][ 2]
                  [ 3][ 4]

                  sheet 1
                  sheet 2
                  sheet 3
                  sheet 4

                on top
                  the edges: word word word word word word word word word word word word word
                  word word word word word word word
                  the rest
                """), files.get(0));
        assertEquals(lines("""
                © Seznam.cz a.s., © OpenStreetMap contributors
                map outdoor
                terms at https://developer.mapy.com/terms/
                made
                """), files.get(1), "the provider's credit as the provider gives it, the rest in the job's words");
    }

    @Test
    @DisplayName("the hanging instructions wrap at a space, never inside a word or at a no-break space")
    void theHangingInstructionsWrap() {
        final String wide = "x".repeat(Composer.LINE_WIDTH + 5);
        final String wrapped = Composer.wrap(List.of("Lepení: list vpravo a list pod ním jdou navrch.",
                "Z každého listu odřízněte bílý okraj vlevo a nahoře, přesně podél potištěné plochy, 1 000 mm.",
                wide + " short", "  ", "last"));
        assertEquals(lines("""
                Lepení: list vpravo a list pod ním jdou navrch.
                  Z každého listu odřízněte bílý okraj vlevo a nahoře, přesně podél potištěné
                  plochy, 1 000 mm.
                  %s
                  short
                  last
                """.formatted(wide)), wrapped, "a word wider than a line has one to itself, and a blank paragraph none");
        for (final String line : wrapped.split(System.lineSeparator())) {
            assertTrue(line.length() <= Composer.LINE_WIDTH || !line.strip().contains(" "), line);
        }
    }
}
