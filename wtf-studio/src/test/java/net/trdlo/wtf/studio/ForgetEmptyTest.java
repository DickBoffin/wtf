package net.trdlo.wtf.studio;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.stage.Stage;
import javafx.stage.Window;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.compose.TilePlan;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.studio.steps.Step;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import net.trdlo.wtf.tiles.ProviderStore;
import net.trdlo.wtf.tiles.TileCache;
import net.trdlo.wtf.tiles.TileKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static net.trdlo.wtf.studio.Headless.find;
import static net.trdlo.wtf.studio.Headless.later;
import static net.trdlo.wtf.studio.Headless.onFx;
import static net.trdlo.wtf.studio.Headless.waitFor;
import static net.trdlo.wtf.studio.Headless.waitUntil;
import static org.junit.jupiter.api.Assertions.*;

/**
 * In the window: "Forget empty tiles" in the Providers dialog makes the export step count again,
 * so that Export downloads what the forgotten answers called sea. The call that does it is
 * {@code MainWindow.openProviders}', which {@code ExportCountTest} cannot see: it invalidates the
 * count itself.
 * <p>
 * The whole main window, on JavaFX's headless platform ({@link Headless}), and nothing of the
 * machine's. {@code user.home} is a temporary folder, so the providers file the window and the
 * dialog read is the test's own; the preferences are a node of the test's own, which names a cache
 * in that folder – without it the map would open on the machine's own cache – and the provider the
 * map opens on is {@link Headless#nowhere}. Nothing is fetched from it either: the view's own
 * download finds the port closed and says so, once, and the export's is not pressed. The dialog is answered as a person answers it, inside the
 * nested event loop its {@code showAndWait} runs.
 */
class ForgetEmptyTest {

    private static final LonLatBounds BISCAY = new LonLatBounds(-10, 46, 4, 50);

    @BeforeAll
    static void toolkit() {
        Headless.start();
    }

    /** The pane of the showing dialog of that title, or of that header: called on the FX thread. */
    private static DialogPane showing(final String titleOrHeader) {
        return dialog(titleOrHeader).orElseThrow(() -> new AssertionError("no dialog showing " + titleOrHeader));
    }

    private static Optional<DialogPane> dialog(final String titleOrHeader) {
        return Window.getWindows().stream()
                .filter(w -> w.isShowing() && w instanceof Stage)
                .map(w -> (Stage) w)
                .filter(s -> s.getScene() != null && s.getScene().getRoot() instanceof final DialogPane pane
                        && (titleOrHeader.equals(s.getTitle()) || titleOrHeader.equals(pane.getHeaderText())))
                .map(s -> (DialogPane) s.getScene().getRoot())
                .findFirst();
    }

    @Test
    @DisplayName("empty tiles forgotten in the Providers dialog are counted again, and Export downloads them")
    void forgottenTilesAreCountedAgain(@TempDir final Path dir) throws Exception {
        final String path = "trdlo.net/wtf-test-" + UUID.randomUUID();
        final String home = System.getProperty("user.home");
        final AtomicReference<Stage> main = new AtomicReference<>();
        final AtomicReference<MainWindow> window = new AtomicReference<>();
        CompletableFuture<Void> pressed = null;
        try {
            System.setProperty("user.home", dir.toString());
            final Provider nowhere = Headless.nowhere();
            final Path tiles = dir.resolve("tiles");
            final Prefs prefs = new Prefs(path);
            prefs.putPath(Prefs.CACHE_DIR, tiles);
            prefs.put(Prefs.LAST_PROVIDER, nowhere.id());
            // Read back: a write the preferences dropped would open the window on the machine's cache
            assertEquals(tiles, prefs.getPath(Prefs.CACHE_DIR));
            assertEquals(nowhere.id(), prefs.get(Prefs.LAST_PROVIDER));
            final Path providers = ProviderStore.defaultFile();
            assertTrue(providers.startsWith(dir), providers::toString);
            new ProviderStore(providers).put(nowhere);
            final ProviderCatalog catalog = ProviderCatalog.load(providers);
            final TileCache cache = new TileCache(tiles);

            onFx(() -> {
                final Stage stage = new Stage();
                final MainWindow built = new MainWindow(stage, prefs, catalog, cache);
                window.set(built);
                main.set(stage);
                stage.setScene(built.build());
                // As wide as it is built – narrower, the toolbar moves its last buttons, Providers
                // among them, into its overflow menu – and at the screen's corner, inside its rows
                stage.setX(0);
                stage.setY(0);
                stage.show();
                return null;
            });
            final MainWindow w = window.get();
            final Node root = onFx(() -> main.get().getScene().getRoot());
            assertEquals(nowhere.id(), onFx(() -> w.model().providerProperty().get().id()),
                    "the map opens on the provider nobody can reach");
            // The view's own download is answered by nobody, three times a tile, and says so once,
            // offering to try again
            final DialogPane unanswered = waitFor("the view's download to say its tiles did not arrive",
                    () -> dialog(I18n.get("view.failed.title")));
            onFx(() -> {
                assertNotNull(find(unanswered, Button.class,
                        b -> I18n.get("button.retry").equals(b.getText())).orElse(null), "no answer may pass");
                ((Button) unanswered.lookupButton(Buttons.CLOSE)).fire();
                return null;
            });

            // A print over the sea, and every tile of it once answered "no map here"
            final int n = onFx(() -> {
                final ProjectModel m = w.model();
                m.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
                m.customProperty().set(Shapes.rectangle(BISCAY));
                m.settleArea();         // the window's model grows a Custom area on its worker
                m.zoomProperty().set(6);
                final ProjectModel.Built built = m.build();
                assertTrue(built.ok(), built::problem);
                final List<TileKey> keys = List.copyOf(TilePlan.of(built.job()).distinctKeys());
                for (final TileKey key : keys) {
                    cache.recordEmpty(key);
                }
                w.rail().select(Step.EXPORT);
                return keys.size();
            });
            assertTrue(n > 0, "a print with tiles in it");
            final Node step = onFx(() -> w.exportPanel().node());
            final String counted = I18n.format("export.inventory", I18n.integer(n), I18n.integer(0),
                    I18n.integer(n), I18n.integer(0));
            final Label inventory = waitFor("the step's count, every tile known empty",
                    () -> find(step, Label.class, l -> counted.equals(l.getText())));
            final Button export = onFx(() -> find(step, Button.class,
                    b -> I18n.get("export.run").equals(b.getText())).orElseThrow());
            final Button providersButton = onFx(() -> find(root, Button.class, b -> b.getTooltip() != null
                    && I18n.get("toolbar.providers").equals(b.getTooltip().getText())).orElseThrow());

            // The dialog is modal: the press returns once it has closed, and what is asked meanwhile
            // is asked inside its own event loop
            pressed = later(() -> {
                providersButton.fire();
                return null;
            });
            final DialogPane dialog = onFx(() -> showing(I18n.get("providers.title")));
            final String theme = nowhere.themes().getFirst().displayName();
            final MenuItem forget = onFx(() -> find(dialog, MenuButton.class,
                    b -> I18n.get("providers.forgetEmpty").equals(b.getText())).orElseThrow()
                    .getItems().stream().filter(i -> theme.equals(i.getText())).findFirst().orElseThrow());
            final CompletableFuture<Void> chosen = later(() -> {
                forget.fire();
                return null;
            });
            onFx(() -> {
                ((Button) showing(I18n.get("providers.forgetEmpty.confirm.title")).lookupButton(ButtonType.OK)).fire();
                return null;
            });
            chosen.get(60, TimeUnit.SECONDS);
            final String forgotten = I18n.format("providers.forgetEmpty.done", I18n.integer(n));
            waitFor("the dialog to say the empty tiles are forgotten",
                    () -> find(dialog, Label.class, l -> forgotten.equals(l.getText())));
            onFx(() -> {
                ((Button) dialog.lookupButton(Buttons.CLOSE)).fire();
                return null;
            });
            pressed.get(60, TimeUnit.SECONDS);

            // The press has returned: the count that called the tiles empty is gone at once ...
            assertNotEquals(counted, onFx(inventory::getText),
                    "the empty tiles were forgotten, and the export step still counts them empty");
            // ... and the one made again finds them missing, so Export downloads them
            final String download = I18n.format("export.run.withDownload", I18n.integer(n));
            waitUntil("Export to offer the download", () -> download.equals(export.getText()));
            assertEquals(I18n.format("export.inventory", I18n.integer(n), I18n.integer(0), I18n.integer(0),
                    I18n.integer(n)), onFx(inventory::getText));
        } finally {
            // Whatever a failure left open: a dialog would keep the FX thread in its nested loop
            onFx(() -> {
                for (final Window open : List.copyOf(Window.getWindows())) {
                    if (open != main.get()) {
                        open.hide();
                    }
                }
                return null;
            });
            if (pressed != null) {
                pressed.exceptionally(ex -> null).get(60, TimeUnit.SECONDS);     // what it threw is the test's
            }
            // The map lets its decoders go, and the window's bounds and view land in the test's node
            onFx(() -> {
                if (window.get() != null) {
                    window.get().saveState();
                }
                if (main.get() != null) {
                    main.get().hide();
                }
                return null;
            });
            System.setProperty("user.home", home);
            Preferences.userRoot().node(path).removeNode();
        }
    }
}
