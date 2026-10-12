package net.trdlo.wtf.studio.steps;

import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import net.trdlo.wtf.tiles.ProviderStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The provider the export step's key question names – the one whose key a download
 * refused – is asked of the catalog when the key is refused, not of the catalog the window was
 * built with. The Providers dialog replaces the catalog, as {@code MapSource.reloadProviders}
 * does, and the context, built before, sees the new one. Without the toolkit: nothing here needs
 * the stage, the map or the settings.
 */
class StepContextCatalogTest {

    private static final String ADDED = "added-since-test";

    @Test
    @DisplayName("a provider added and one edited since the context was built are found as they are now")
    void theCatalogIsAskedAtTheTime(@TempDir final Path dir) throws IOException {
        final AtomicReference<ProviderCatalog> current = new AtomicReference<>(ProviderCatalog.builtIn());
        final StepContext ctx = new StepContext(null, null, null, current::get, () -> null,
                () -> false, () -> false, p -> { });
        final Provider osm = ctx.provider("osm");
        assertThrows(IllegalArgumentException.class, () -> ctx.provider(ADDED),
                "not there before the dialog adds it");

        // What the Providers dialog does: the user file written, and the catalog loaded again
        final Path file = dir.resolve("providers.json");
        final ProviderStore store = new ProviderStore(file);
        store.put(ProviderStore.copyUnder(osm, ADDED, "Added Since"));
        store.put(ProviderStore.copyUnder(osm, "osm", "OSM, Edited"));
        current.set(ProviderCatalog.load(file));

        final Provider added = ctx.provider(ADDED);
        assertEquals("Added Since", added.displayName());
        final Provider edited = ctx.provider("osm");
        assertEquals("OSM, Edited", edited.displayName(), "the edited copy, not the one of the start");
        assertNotEquals(osm.displayName(), edited.displayName());
    }
}
