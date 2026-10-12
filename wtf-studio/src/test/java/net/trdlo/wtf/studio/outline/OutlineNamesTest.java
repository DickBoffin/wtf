package net.trdlo.wtf.studio.outline;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import net.trdlo.wtf.studio.I18n;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The library in the interface language: names shown and found in Czech, found without accents,
 * and the English names still what a project file records.
 * <p>
 * A library of its own rather than the shared one, which is loaded in whatever language the test
 * that gets there first has.
 */
class OutlineNamesTest {

    private static final Locale CZECH = Locale.forLanguageTag("cs-CZ");

    private static Locale format;
    private static Locale display;
    private static OutlineLibrary czech;

    @BeforeAll
    static void loadInCzech() {
        format = Locale.getDefault(Locale.Category.FORMAT);
        display = Locale.getDefault(Locale.Category.DISPLAY);
        // A Czech on a Czech machine: the machine decides which country's regions go by their
        // local names
        Locale.setDefault(Locale.Category.FORMAT, CZECH);
        I18n.setLocale(CZECH);
        czech = OutlineLibrary.load();
    }

    @AfterAll
    static void back() {
        Locale.setDefault(Locale.Category.FORMAT, format);
        I18n.setLocale(display);
    }

    private static OutlineLibrary.Entry entry(final String id) {
        return czech.find(id).orElseThrow(() -> new AssertionError(id + " is not in the library"));
    }

    @Test
    @DisplayName("continents and countries are shown by their Czech names, and keep their English ones")
    void czechNames() {
        assertEquals("Evropa", entry("nec:europe").shown());
        assertEquals("Německo", entry("ne0:DEU").shown());
        assertEquals("Germany", entry("ne0:DEU").name(), "the English name is what a file records");
    }

    @Test
    @DisplayName("a region is labeled with its country's Czech name")
    void regionsNameTheirCountryInCzech() {
        final OutlineLibrary.Entry bavaria = czech.all().stream()
                .filter(e -> e.name().equals("Bavaria")).findFirst().orElseThrow();
        assertEquals("Bavaria – Německo", bavaria.label());
    }

    @Test
    @DisplayName("the regions of the machine's own country go by their local names")
    void homeRegionsAreLocal() {
        final OutlineLibrary.Entry southMoravia = czech.all().stream()
                .filter(e -> "CZ-JM".equals(e.iso())).findFirst().orElseThrow();
        assertEquals("Jihomoravský – Česko", southMoravia.label());
    }

    @Test
    @DisplayName("a Czech name is found, with or without its accents, and so is the English one")
    void foundInEitherLanguage() {
        assertEquals("ne0:DEU", czech.best(czech.grouped("německo", 20), "německo").id());
        assertEquals("ne0:DEU", czech.best(czech.grouped("nemecko", 20), "nemecko").id());
        assertEquals("ne0:DEU", czech.best(czech.grouped("germany", 20), "germany").id());
        assertEquals("ne0:CZE", czech.best(czech.grouped("cesko", 20), "cesko").id());
    }

    @Test
    @DisplayName("folding takes the accents and the case away, and nothing else")
    void folding() {
        assertEquals("plzen", OutlineLibrary.fold("Plzeň"));
        assertEquals("zurich", OutlineLibrary.fold("Zürich"));
        assertEquals("new york", OutlineLibrary.fold("New York"));
    }
}
