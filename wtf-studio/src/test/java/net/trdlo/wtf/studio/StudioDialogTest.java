package net.trdlo.wtf.studio;

import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import net.trdlo.wtf.studio.outline.OutlinePicker;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import net.trdlo.wtf.tiles.ProviderStore;
import net.trdlo.wtf.tiles.TileCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static net.trdlo.wtf.studio.Headless.onFx;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Every dialog and alert of the studio carries its stylesheet on its own pane, owned or not. The
 * Settings, Providers and outline dialogs had it only through JavaFX's binding to an owner's
 * scene, and an alert with no owner not at all.
 * <p>
 * On JavaFX's headless platform ({@link Headless}), under the studio's theme, whose tokens the
 * sheet's colors are looked up in; the dialogs are built and not shown, but for the outline
 * picker, which is shown to be built. The Providers dialog reads the provider file of a home of
 * the test's own, and its preferences are a node of the test's own, with a provider selected that
 * needs no key.
 */
class StudioDialogTest {

    private final String path = "trdlo.net/wtf-test-" + UUID.randomUUID();
    private final String home = System.getProperty("user.home");

    @BeforeAll
    static void toolkit() {
        Headless.start();
    }

    @AfterEach
    void restore() throws BackingStoreException {
        System.setProperty("user.home", home);
        Preferences.userRoot().node(path).removeNode();
    }

    @Test
    @DisplayName("every dialog's pane carries the studio's stylesheet")
    void everyDialogsPaneCarriesTheSheet(final @TempDir Path dir) throws Exception {
        System.setProperty("user.home", dir.toString());
        final Prefs prefs = new Prefs(path);
        final TileCache cache = new TileCache(dir.resolve("tiles"));
        final ProviderCatalog catalog = ProviderCatalog.builtIn();
        final Provider keyless = catalog.all().stream().filter(p -> !p.needsKey()).findFirst().orElseThrow();
        final UpdateCheck.Release release =
                new UpdateCheck.Release("9.9.9", "https://example.invalid", "- one");

        final Map<String, List<String>> sheets = onFx(() -> {
            final Map<String, DialogPane> panes = new LinkedHashMap<>();
            panes.put("Settings", new SettingsDialog(null, prefs, cache).getDialogPane());
            panes.put("Providers", new ProvidersDialog(null, prefs, catalog, cache, keyless).getDialogPane());
            panes.put("provider editor", new ProviderEditor(null,
                    new ProviderStore(dir.resolve("providers.json")), null, false).getDialogPane());
            panes.put("About", AboutDialog.dialog(null, catalog, prefs, false).getDialogPane());
            panes.put("update", AboutDialog.offerAlert(null, release).getDialogPane());
            panes.put("alert", Dialogs.alert(null, Alert.AlertType.WARNING, "message").getDialogPane());

            // An owner with no sheet of its own, so the picker has nothing to inherit
            final Stage owner = new Stage();
            owner.setScene(new Scene(new StackPane()));
            final List<Scene> shown = new ArrayList<>();
            OutlinePicker.openForCapture(owner, "bavaria", shown::add);
            panes.put("outline picker", (DialogPane) shown.getFirst().getRoot());
            shown.getFirst().getWindow().hide();

            final Map<String, List<String>> out = new LinkedHashMap<>();
            panes.forEach((name, pane) -> out.put(name, List.copyOf(pane.getStylesheets())));
            return out;
        });

        assertEquals(7, sheets.size());
        final List<String> without = new ArrayList<>();
        sheets.forEach((name, list) -> {
            if (!list.contains(Windows.STYLESHEET)) {
                without.add(name + " " + list);
            }
        });
        assertTrue(without.isEmpty(), () -> "dialogs whose pane lacks studio.css: " + without);
        assertTrue(Windows.STYLESHEET.endsWith("/net/trdlo/wtf/studio/studio.css"),
                Windows.STYLESHEET);
    }

    @Test
    @DisplayName("a dialog is given its owner, title, resizability and buttons, and the sheet once")
    void aStudioDialogIsWhatItWasGiven() throws Exception {
        final Stage[] owner = new Stage[1];
        final Dialog<?> d = onFx(() -> {
            owner[0] = new Stage();
            owner[0].setScene(new Scene(new StackPane()));
            final StudioDialog<Void> dialog =
                    new StudioDialog<>(owner[0], "settings.title", true, Buttons.CLOSE);
            StudioDialog.style(dialog.getDialogPane());
            return dialog;
        });
        assertSame(owner[0], d.getOwner());
        assertEquals(I18n.get("settings.title"), d.getTitle());
        assertTrue(d.isResizable());
        assertEquals(List.of(Buttons.CLOSE), d.getDialogPane().getButtonTypes());
        assertEquals(List.of(Windows.STYLESHEET), d.getDialogPane().getStylesheets(),
                "styled twice, carried once");
    }
}
