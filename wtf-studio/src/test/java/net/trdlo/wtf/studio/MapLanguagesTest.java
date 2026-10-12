package net.trdlo.wtf.studio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import net.trdlo.wtf.tiles.TileKey;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The map languages the toolbar offers, for a map lettered in one (D25). */
class MapLanguagesTest {

    private static final Provider MAPY = ProviderCatalog.builtIn().require("mapy-com");
    private static final Provider OSM = ProviderCatalog.builtIn().require("osm");

    @Test
    @DisplayName("Mapy.com names the languages its tiles differ in, and each is one a tile key takes")
    void mapyNamesItsLanguages() {
        final List<String> listed = MAPY.languages();
        assertNotNull(listed);
        assertEquals(List.of("cs", "en"), listed, "the other twelve it takes are the same tiles as one of these");
        for (final String tag : listed) {
            assertTrue(TileKey.LANG.matcher(tag).matches(), tag);
        }
    }

    @Test
    @DisplayName("a provider that names its languages is offered those, and a provider that names none the window's")
    void theProvidersOwnList() {
        assertEquals(MAPY.languages(), MapLanguages.offered(MAPY, "en"));
        assertNull(OSM.languages());
        assertEquals(List.of("en", "cs"), MapLanguages.offered(OSM, null));
        assertEquals(List.of("en", "cs"), MapLanguages.offered(null, null));
    }

    @Test
    @DisplayName("a language a project file named is offered too, so the choice can show it")
    void theCurrentOneIsAmongThem() {
        assertTrue(MapLanguages.offered(MAPY, "hu").contains("hu"));
        assertEquals(MAPY.languages(), MapLanguages.offered(MAPY, "en"), "not twice");
        assertEquals(MAPY.languages(), MapLanguages.offered(MAPY, "../x"), "and never a path");
    }

    @Test
    @DisplayName("a language the provider does not name gives way to the system's, English or the first")
    void fitting() {
        final String system = ProjectModel.defaultMapLang();
        assertEquals(system, MapLanguages.fitting(List.of("xx", system)));
        assertEquals("en", MapLanguages.fitting(List.of("xx", "en")), "the system's, or else English");
        assertEquals("xx", MapLanguages.fitting(List.of("xx", "yy")));
    }

    @Test
    @DisplayName("what is typed is a language by its name or its code; anything else keeps the one there was")
    void typed() {
        final List<String> offered = List.of("en", "cs");
        assertEquals("cs", MapLanguages.fromTyped("Čeština", offered));
        assertEquals("cs", MapLanguages.fromTyped(" CS ", offered));
        assertEquals("hu", MapLanguages.fromTyped("hu", offered));
        assertEquals("pt-BR", MapLanguages.fromTyped("pt-BR", offered));
        assertNull(MapLanguages.fromTyped("../x", offered));
        assertNull(MapLanguages.fromTyped("  ", offered));
    }

    @Test
    @DisplayName("a language is named in itself, whatever the window speaks")
    void namedInItself() {
        assertEquals("Deutsch", MapLanguages.nameOf("de"));
        assertEquals("Čeština", MapLanguages.nameOf("cs"));
        assertEquals("", MapLanguages.nameOf(null));
    }

    @Test
    @DisplayName("the choice is offered for the shipped map that takes a language, and only there")
    void onlyWhereTheAddressTakesOne() {
        assertTrue(MAPY.takesLanguage(MAPY.requireTheme("outdoor")));
        assertFalse(OSM.takesLanguage(OSM.themes().getFirst()));
    }
}
