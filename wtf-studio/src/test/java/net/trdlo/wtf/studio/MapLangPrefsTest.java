package net.trdlo.wtf.studio;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

/** The map language last used with each provider, in a preference node of the test's own. */
class MapLangPrefsTest {

    private final String path = "trdlo.net/wtf-test-" + UUID.randomUUID();
    private final Prefs prefs = new Prefs(path);

    @AfterEach
    void removeTheNode() throws BackingStoreException {
        Preferences.userRoot().node(path).removeNode();
    }

    @Test
    @DisplayName("each provider keeps the language last used with it")
    void perProvider() {
        assertNull(prefs.mapLangOf("mapy-com"), "none until one is used");
        prefs.putMapLang("mapy-com", "de");
        prefs.putMapLang("other", "fr");
        assertEquals("de", prefs.mapLangOf("mapy-com"));
        assertEquals("fr", prefs.mapLangOf("other"));
        prefs.putMapLang("mapy-com", "cs");
        assertEquals("cs", prefs.mapLangOf("mapy-com"), "the last one used");
    }

    @Test
    @DisplayName("a hand-edited value that is no language is no language")
    void onlyALanguage() {
        prefs.put("map.lang.mapy-com", "../x");
        assertNull(prefs.mapLangOf("mapy-com"));
    }

    @Test
    @DisplayName("each provider keeps the map last used with it too")
    void mapPerProvider() {
        assertNull(prefs.themeOf("mapy-com"), "none until one is used");
        prefs.putTheme("mapy-com", "winter");
        prefs.putTheme("usgs", "USGSTopo");
        assertEquals("winter", prefs.themeOf("mapy-com"));
        assertEquals("USGSTopo", prefs.themeOf("usgs"), "in the server's case");
        prefs.put("map.theme.osm", "../x");
        assertNull(prefs.themeOf("osm"), "a hand-edited value that is no map id is none");
    }
}
