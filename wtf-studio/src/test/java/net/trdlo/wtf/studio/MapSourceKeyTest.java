package net.trdlo.wtf.studio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MapSource.SourceKey}: whether {@link MapSource#rebuild} has to point the map at its
 * source again, tested without the toolkit {@link MapSource} itself needs.
 */
class MapSourceKeyTest {

    private static final Provider OSM = ProviderCatalog.builtIn().require("osm");
    private static final Path ROOT = Path.of(".");

    private static MapSource.SourceKey keyOf(final Provider p, final Provider.Theme t) {
        return new MapSource.SourceKey(p, t, "en", ROOT);
    }

    private static Provider.Theme edited(final Provider.Theme t, final Integer maxZoom, final String urlTemplate) {
        return new Provider.Theme(t.id(), t.displayName(), t.minZoom(), maxZoom, t.expectedFormat(),
                urlTemplate, t.transparent(), t.note());
    }

    @Test
    @DisplayName("editing the current map's top zoom re-keys it, though its id did not change")
    void editedTopZoomReKeys() {
        final Provider.Theme original = OSM.themes().getFirst();
        final Provider.Theme sameIdEditedZoom = edited(original, 12, original.urlTemplate());
        assertEquals(original.id(), sameIdEditedZoom.id(), "same map, a different top zoom");
        assertNotEquals(keyOf(OSM, original), keyOf(OSM, sameIdEditedZoom));
    }

    @Test
    @DisplayName("adding {lang} to the address re-keys the layer")
    void addedLanguageReKeys() {
        final Provider.Theme original = OSM.themes().getFirst();
        final Provider.Theme lettered = edited(original, original.maxZoom(),
                "https://tile.example/{z}/{x}/{y}-{lang}.png");
        assertEquals(original.id(), lettered.id(), "same map, a lettered address");
        assertNotEquals(keyOf(OSM, original), keyOf(OSM, lettered));
    }

    @Test
    @DisplayName("the same definition, freshly loaded, is the same key – nothing to rebuild")
    void unchangedDefinitionIsTheSameKey() {
        final Provider reloaded = ProviderCatalog.builtIn().require("osm");
        assertNotSame(OSM, reloaded, "a fresh load, not the object rebuild saw last");
        assertEquals(keyOf(OSM, OSM.themes().getFirst()), keyOf(reloaded, reloaded.themes().getFirst()));
    }
}
