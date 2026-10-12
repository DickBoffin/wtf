package net.trdlo.wtf.studio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.trdlo.wtf.studio.ProviderEditor.ThemeRow;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderReason;
import net.trdlo.wtf.tiles.ProviderStore;
import net.trdlo.wtf.tiles.Refusal;
import net.trdlo.wtf.tiles.TileFormat;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ProviderEditor#themeOf}: what a row gives back once OK is pressed, tested without the
 * toolkit the rest of the dialog needs.
 */
class ProviderEditorThemeTest {

    private static final String ADDRESS = "https://tiles/{z}/{x}/{y}.png";

    @Test
    @DisplayName("what the table does not show survives editing what it does")
    void keepsWhatTheTableDoesNotShow() {
        final ThemeRow row = new ThemeRow("aerial", "Aerial", "https://old/{z}/{x}/{y}.jpg", "4", "18",
                TileFormat.JPEG, true, "Probed: JPEG, 40 kB.", ADDRESS);
        row.setName("Satellite");                       // editing a shown column ...
        final Provider.Theme built = ProviderEditor.themeOf(row, ADDRESS);

        assertEquals("aerial", built.id());
        assertEquals("Satellite", built.displayName());   // ... changes only that one
        assertEquals(4, built.minZoom());
        assertEquals(18, built.maxZoom());
        assertEquals(TileFormat.JPEG, built.expectedFormat());
        assertTrue(built.transparent());
        assertEquals("Probed: JPEG, 40 kB.", built.note());
    }

    @Test
    @DisplayName("a theme added in the dialog has none of that to keep")
    void aNewRowKeepsNothingItNeverHad() {
        final ThemeRow row = new ThemeRow("map-1", "Map 1", "", "", "");
        final Provider.Theme built = ProviderEditor.themeOf(row, ADDRESS);

        assertNull(built.minZoom());
        assertNull(built.maxZoom());
        assertNull(built.expectedFormat());
        assertFalse(built.transparent());
        assertNull(built.note());
    }

    @Test
    @DisplayName("a top zoom below the lowest is put right in the table")
    void theLowestZoomIsAColumn(@TempDir final Path dir) {
        final ProviderStore store = new ProviderStore(dir.resolve("providers.json"));
        final ThemeRow row = new ThemeRow("aerial", "Aerial", "", "4", "18", null, false, null, ADDRESS);

        row.setMaxZoom("2");
        final Refusal refused = assertThrows(Refusal.class, () -> store.check(providerWith(row)));
        assertEquals(ProviderReason.ZOOMS_INVERTED, refused.reason());

        row.setMinZoom("1");
        final Provider fixed = providerWith(row);
        assertDoesNotThrow(() -> store.check(fixed));
        assertEquals(1, fixed.themes().getFirst().minZoom());

        row.setMinZoom("");
        assertNull(ProviderEditor.themeOf(row, ADDRESS).minZoom(), "blank is the provider's own");
        row.setMinZoom("x");
        assertThrows(IllegalArgumentException.class, () -> ProviderEditor.themeOf(row, ADDRESS));
    }

    @Test
    @DisplayName("a note outlives no change of the address it was written of")
    void theNoteGoesWithTheAddress() {
        final String note = "Probed: JPEG, 40 kB.";
        final ThemeRow own = new ThemeRow("aerial", "Aerial", "https://old/{z}/{x}/{y}.jpg", "", "",
                TileFormat.JPEG, false, note, ADDRESS);
        assertEquals(note, ProviderEditor.themeOf(own, "https://other/{z}/{x}/{y}.png").note(),
                "the provider's address is not the one this theme uses");
        own.setTemplate("https://new/{z}/{x}/{y}.jpg");
        assertNull(ProviderEditor.themeOf(own, ADDRESS).note(), "its own address changed");
        assertEquals(TileFormat.JPEG, ProviderEditor.themeOf(own, ADDRESS).expectedFormat());

        final ThemeRow inherited = new ThemeRow("std", "Standard", "", "", "", null, false, note, ADDRESS);
        assertEquals(note, ProviderEditor.themeOf(inherited, ADDRESS).note());
        assertNull(ProviderEditor.themeOf(inherited, "https://moved/{z}/{x}/{y}.png").note(),
                "the provider's address, which this theme uses, changed");
        inherited.setTemplate("https://moved/{z}/{x}/{y}.png");
        assertNull(ProviderEditor.themeOf(inherited, ADDRESS).note(), "an address of its own now");
    }

    private static Provider providerWith(final ThemeRow row) {
        return new Provider("p", "P", ADDRESS, null, 0, 19, null,
                List.of(ProviderEditor.themeOf(row, ADDRESS)),
                null, "none", null, null, null, true, null, null, null, null);
    }
}
