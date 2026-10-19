package net.trdlo.wtf.studio;

import net.trdlo.wtf.compose.ComposeReason;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.tiles.DownloadReason;
import net.trdlo.wtf.tiles.ProviderReason;
import net.trdlo.wtf.tiles.Refusal;
import net.trdlo.wtf.tiles.TileFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The engine's refusals, said in the window's language rather than in the English the engine
 * writes for a command line.
 */
class EngineTextTest {

    private final Locale before = I18n.locale();
    private final Units unitsBefore = Units.current();

    /** Millimeters unless a test says otherwise: the test JVM's en-US would make it inches. */
    @BeforeEach
    void millimeters() {
        Units.use("mm");
    }

    @AfterEach
    void restore() {
        I18n.setLocale(before);
        Units.use(unitsBefore.key());
    }

    /** A placeholder the engine writes "mm" after: {@code {0} mm}, {@code {1,number,0.0} mm}. */
    private static final Pattern IN_MILLIMETERS = Pattern.compile("\\{(\\d)(?:,number,[^}]*)?} mm");

    /** Every reason the engine can give, from every module. */
    static Stream<Refusal.Reason> reasons() {
        return Stream.of(ProviderReason.values(), DownloadReason.values(), ComposeReason.values())
                .flatMap(Stream::of);
    }

    /**
     * An English window says what the command line says, word for word: the bundle's English is
     * the engine's pattern, so the two cannot drift apart and a reader of either sees one sentence.
     * The one difference is a length's unit, which the engine writes after the number and the
     * window puts in the value, so that it can be inches.
     */
    @Test
    void theEnglishIsTheEnginesOwn() {
        final ResourceBundle english = ResourceBundle.getBundle("net.trdlo.wtf.studio.messages", Locale.ROOT);
        final List<String> differ = reasons()
                .filter(r -> !english.getString(EngineText.key(r))
                        .equals(IN_MILLIMETERS.matcher(r.english()).replaceAll("{$1}")))
                .map(r -> r + " = " + english.getString(EngineText.key(r)))
                .toList();
        assertTrue(differ.isEmpty(), () -> "the bundle says these differently from the engine: " + differ);
    }

    /**
     * Every value the engine writes "mm" after is one the window writes as a length, and nothing
     * else is – or a new refusal would say "12.7" where it meant half an inch.
     */
    @Test
    void theLengthsAreTheOnesTheEngineGivesInMillimeters() {
        reasons().forEach(r -> {
            final Set<Integer> engine = new TreeSet<>();
            final Matcher m = IN_MILLIMETERS.matcher(r.english());
            while (m.find()) {
                engine.add(Integer.parseInt(m.group(1)));
            }
            final Set<Integer> window = new TreeSet<>();
            for (final int[] length : EngineText.lengths(r)) {
                window.add(length[0]);
            }
            // "{0} × {1} mm" gives the unit once, for both
            assertTrue(window.containsAll(engine), () -> r + " writes mm after " + engine + ", lengths are " + window);
            assertEquals(!engine.isEmpty(), !window.isEmpty(), () -> r + " has lengths only on one side");
        });
    }

    /** Under inches a refusal's lengths are inches too (D32), while the engine keeps millimeters. */
    @Test
    void underInchesTheLengthsAreInches() {
        Units.use("inch");
        I18n.setLocale(Locale.ENGLISH);
        final Refusal overlap = new Refusal(ComposeReason.OVERLAP_TOO_WIDE, 12.7, 10.0);
        assertEquals("An overlap of 0.5 in on a 0.394 in sheet has no solution", EngineText.of(overlap));
        assertEquals("An overlap of 12.7 mm on a 10.0 mm sheet has no solution", overlap.getMessage());
        assertEquals("Print size must be positive: 0 in × 11 in",
                EngineText.of(new Refusal(ComposeReason.PRINT_SIZE_NOT_POSITIVE, 0.0, 279.4)));
    }

    @Test
    void everyReasonHasASentenceInEveryLanguage() {
        for (final Locale l : I18n.LANGUAGES) {
            I18n.setLocale(l);
            reasons().forEach(r -> assertFalse(I18n.get(EngineText.key(r)).startsWith("!"),
                    () -> r + " has nothing to say in " + l));
        }
    }

    @Test
    void aRefusalIsSaidInCzech() {
        I18n.setLocale(Locale.forLanguageTag("cs"));
        assertEquals("Tvar oblasti je prázdný",
                EngineText.of(new Refusal(ComposeReason.SHAPE_EMPTY, Shapes.Role.AREA)));
        // Numbers in the window's own way of writing them
        assertEquals("Překryv 12,5 mm na listu 10,0 mm nemá řešení",
                EngineText.of(new Refusal(ComposeReason.OVERLAP_TOO_WIDE, 12.5, 10.0)));
        assertEquals("Dvě mapy mají stejné id „topo“",
                EngineText.of(new Refusal(ProviderReason.MAP_ID_TWICE, "topo")));
        assertEquals("Server tiles.example.org odpověděl HTTP 403, ačkoli tento poskytovatel žádný "
                        + "klíč nevyžaduje. Nejde o odmítnutý klíč – server odmítl samotný požadavek.",
                EngineText.of(new Refusal(DownloadReason.REJECTED_WITHOUT_KEY, "tiles.example.org", 403)));
    }

    /** While the engine goes on saying it in English, which is what a command line prints. */
    @Test
    void theEngineStillSpeaksEnglish() {
        I18n.setLocale(Locale.forLanguageTag("cs"));
        final Refusal r = new Refusal(ComposeReason.OVERLAP_TOO_WIDE, 12.5, 10.0);
        assertEquals("An overlap of 12.5 mm on a 10.0 mm sheet has no solution", r.getMessage());
        assertEquals("The header's value has no {key}, so the key would never be sent. The key itself "
                + "does not go in it – WTF keeps it apart, under Providers and keys. Leave the value "
                + "empty to send the key alone.", new Refusal(ProviderReason.HEADER_VALUE_WITHOUT_KEY).getMessage());
    }

    /** The error dialog says it the same way, from wherever in the cause chain it comes. */
    @Test
    void theErrorDialogSaysItInCzech() {
        I18n.setLocale(Locale.forLanguageTag("cs"));
        final RuntimeException wrapped = new RuntimeException(
                new Refusal(ComposeReason.IMPORT_KIND_UNKNOWN, "hranice.shp"));
        assertEquals("Nedá se poznat, co je to za soubor: hranice.shp. Použijte .csv, .geojson nebo .kml.",
                ErrorText.explain(wrapped).body());
    }

    /**
     * A tile no decoder can read, as the error dialog says it: under the refusal's headline rather
     * than as something WTF did not see coming, with the tile and its format in each language, and
     * where to turn instead.
     */
    @Test
    void anUnreadableTileIsSaidInEitherLanguage() {
        final Refusal r = new Refusal(ComposeReason.TILE_UNREADABLE, "osm/mapnik/9/277/173", TileFormat.WEBP);
        I18n.setLocale(Locale.ENGLISH);
        assertEquals(I18n.get("error.refused.title"), ErrorText.explain(r).headline());
        assertEquals("WTF cannot read tile osm/mapnik/9/277/173 (format WEBP), so it cannot be printed. "
                + "Choose another map.", ErrorText.explain(r).body());
        I18n.setLocale(Locale.forLanguageTag("cs"));
        assertEquals(I18n.get("error.refused.title"), ErrorText.explain(r).headline());
        assertEquals("WTF nedokáže přečíst dlaždici osm/mapnik/9/277/173 (formát WEBP), takže ji nelze "
                + "vytisknout. Zvolte jinou mapu.", ErrorText.explain(r).body());
    }

    /** Anything that is not a refusal is left as it was said. */
    @Test
    void anythingElseIsShownAsItWasSaid() {
        I18n.setLocale(Locale.forLanguageTag("cs"));
        assertEquals("disk on fire", EngineText.of(new java.io.IOException("disk on fire")));
        assertEquals("java.lang.NullPointerException", EngineText.of(new NullPointerException()));
    }
}
