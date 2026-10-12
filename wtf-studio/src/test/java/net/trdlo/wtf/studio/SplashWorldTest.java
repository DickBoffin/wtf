package net.trdlo.wtf.studio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;

import static org.junit.jupiter.api.Assertions.*;

/** Where a first run's world comes from (D37). */
class SplashWorldTest {

    @Test
    @DisplayName("the splash fetches its first world from OpenStreetMap, by the order of the shipped file")
    void theFirstWorldIsOpenStreetMaps() {
        final Provider world = Splash.worldSource(ProviderCatalog.builtIn());
        assertNotNull(world, "no provider to fetch a first world from");
        assertEquals("osm", world.id(), "the first global, key-free provider in providers.json");
    }
}
