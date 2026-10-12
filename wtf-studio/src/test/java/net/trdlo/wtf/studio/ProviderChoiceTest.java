package net.trdlo.wtf.studio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;

import static org.junit.jupiter.api.Assertions.*;

/** The map and the language a provider opens on when it is chosen. */
class ProviderChoiceTest {

    private static final Provider MAPY = ProviderCatalog.builtIn().require("mapy-com");
    private static final Provider OSM = ProviderCatalog.builtIn().require("osm");

    @Test
    @DisplayName("the map last used with the provider, as far as it still has it")
    void theRememberedMap() {
        final Provider.Theme osmFirst = OSM.themes().getFirst();
        assertEquals("winter", ProviderChoice.theme(MAPY, "winter", osmFirst).id());
        assertEquals("winter", ProviderChoice.theme(MAPY, "WINTER", null).id(), "by id, ignoring case");
        assertEquals(MAPY.themes().getFirst(), ProviderChoice.theme(MAPY, "gone", osmFirst),
                "a map it no longer has, and a current map of another provider: its first");
        assertEquals(MAPY.themes().getFirst(), ProviderChoice.theme(MAPY, null, null));
    }

    @Test
    @DisplayName("without a remembered map, the current one where the provider has a map of that id")
    void theCurrentMapById() {
        final Provider.Theme winter = MAPY.requireTheme("winter");
        final Provider.Theme lookalike = new Provider.Theme("winter", "Edited", null, 18, null, null, false, null);
        assertSame(winter, ProviderChoice.theme(MAPY, null, lookalike),
                "an edited definition's map is the same map, by id, not by every field");
    }

    @Test
    @DisplayName("the language last used with the provider, where it takes it")
    void theRememberedLanguage() {
        assertEquals("en", ProviderChoice.language(MAPY, "en", "cs"));
        assertEquals("cs", ProviderChoice.language(MAPY, "de", "cs"),
                "one it does not name gives way to the current one if it names that");
        assertEquals(MapLanguages.fitting(MAPY.languages()), ProviderChoice.language(MAPY, "de", "fr"),
                "and to the one its list fits otherwise");
        assertEquals("fr", ProviderChoice.language(OSM, "fr", "cs"), "a provider that names none takes any");
        assertEquals("cs", ProviderChoice.language(OSM, "../x", "cs"), "but only a language");
        assertEquals(ProjectModel.defaultMapLang(), ProviderChoice.language(OSM, null, null));
    }
}
