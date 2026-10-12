package net.trdlo.wtf.studio;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.stage.Stage;
import javafx.stage.Window;
import net.trdlo.wtf.compose.Medium;
import net.trdlo.wtf.studio.project.Example;
import net.trdlo.wtf.studio.project.ProjectIo;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.studio.steps.Step;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import net.trdlo.wtf.tiles.ProviderStore;
import net.trdlo.wtf.tiles.TileCache;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

import static net.trdlo.wtf.studio.Headless.find;
import static net.trdlo.wtf.studio.Headless.onFx;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The Start page in the window: what a window holding a new project shows, what opening a project
 * or an example moves on from, and what New comes back to – an example being a visit, which
 * changes nothing the machine remembers as last used (D43).
 * <p>
 * The whole main window on JavaFX's headless platform ({@link Headless}), and nothing of the
 * machine's, as {@code ForgetEmptyTest} has it: {@code user.home} a temporary folder, the
 * preferences a node of the test's own, the map on {@link Headless#nowhere} – and the examples'
 * providers stood in for in the test's own providers file, at the same address, so that opening one
 * fetches nothing from swisstopo or IGN. The dialogs answer themselves, as in a capture run: the
 * view's download, which finds nobody there, says so on the console.
 */
class StartPageTest {

    @BeforeAll
    static void toolkit() {
        Headless.start();
    }

    /** A provider of that id and map where nothing listens, in place of a shipped one. */
    private static @NotNull Provider standIn(final @NotNull String id, final @NotNull String theme) {
        return new Provider(id, "Stand-in for " + id, "http://127.0.0.1:1/{z}/{x}/{y}.png", null, 0, 19,
                null, List.of(new Provider.Theme(theme, "Map", null, null, null, null, false, null)),
                null, "test", null, null, null, true, null, null, null);
    }

    /**
     * Czech is the longer language, and a label too narrow for its words ends in an ellipsis
     * instead of saying them: the page is built in Czech at the width the window gives it.
     */
    @Test
    @DisplayName("in Czech, every word on the Start page is shown at the panel's width, none cut to an ellipsis")
    void nothingIsCutInCzech() throws Exception {
        final java.util.Locale was = I18n.locale();
        final AtomicReference<Stage> shown = new AtomicReference<>();
        try {
            I18n.useLanguage("cs");
            final List<String> cut = onFx(() -> {
                final net.trdlo.wtf.studio.steps.StartPanel page = new net.trdlo.wtf.studio.steps.StartPanel(
                        new ProjectModel(), example -> { }, () -> { });
                // As MainWindow hosts a step: a column 330 pixels wide, scrolled up and down
                final javafx.scene.control.ScrollPane host = new javafx.scene.control.ScrollPane(page.node());
                host.setFitToWidth(true);
                host.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
                final Stage stage = Headless.show(host, 330, 900);
                shown.set(stage);
                host.applyCss();
                host.layout();
                final List<String> found = new java.util.ArrayList<>();
                collectCut(host, found);
                return found;
            });
            assertEquals(List.of(), cut);
        } finally {
            I18n.setLocale(was);
            onFx(() -> {
                if (shown.get() != null) {
                    shown.get().hide();
                }
                return null;
            });
        }
    }

    /** Every label under {@code node} whose text is shown cut short, as the label's whole text. */
    private static void collectCut(final @NotNull Node node, final @NotNull List<String> cut) {
        if (node instanceof final javafx.scene.control.Labeled labeled && labeled.getText() != null) {
            find(labeled, javafx.scene.text.Text.class, t -> true)
                    .filter(shownText -> !labeled.getText().equals(shownText.getText()))
                    .ifPresent(shownText -> cut.add(labeled.getText() + " shown as " + shownText.getText()));
        }
        if (node instanceof final javafx.scene.Parent parent) {
            for (final Node child : parent.getChildrenUnmodifiable()) {
                collectCut(child, cut);
            }
        }
    }

    @Test
    @DisplayName("a new project shows the Start page; a card opens its example on step 1, untitled, clean and "
            + "unremembered; New comes back on the machine's own choices")
    void theStartPage(@TempDir final Path dir) throws Exception {
        final String path = "trdlo.net/wtf-test-" + UUID.randomUUID();
        final String home = System.getProperty("user.home");
        final boolean batch = Dialogs.isBatchMode();
        final AtomicReference<Stage> main = new AtomicReference<>();
        final AtomicReference<MainWindow> window = new AtomicReference<>();
        try {
            System.setProperty("user.home", dir.toString());
            Dialogs.setBatchMode(true);
            final Provider nowhere = Headless.nowhere();
            final Path tiles = dir.resolve("tiles");
            final Prefs prefs = new Prefs(path);
            prefs.putPath(Prefs.CACHE_DIR, tiles);
            prefs.put(Prefs.LAST_PROVIDER, nowhere.id());
            prefs.put(Prefs.LAST_PAPER, "A3");
            prefs.put(Prefs.LAST_ORIENTATION, Medium.Orientation.LANDSCAPE.name());
            prefs.putDouble(Prefs.PRINT_EDGE_MM, 3);
            final Path providers = ProviderStore.defaultFile();
            assertTrue(providers.startsWith(dir), providers::toString);
            final ProviderStore store = new ProviderStore(providers);
            store.put(nowhere);
            store.put(standIn("swisstopo", "pixelkarte-farbe"));
            store.put(standIn("ign-fr", "plan-ign"));
            final ProviderCatalog catalog = ProviderCatalog.load(providers);
            assertTrue(catalog.require("swisstopo").urlTemplate().startsWith("http://127.0.0.1:1/"),
                    "nothing goes to swisstopo");

            onFx(() -> {
                final Stage stage = new Stage();
                final MainWindow built = new MainWindow(stage, prefs, catalog, new TileCache(tiles));
                window.set(built);
                main.set(stage);
                stage.setScene(built.build());
                stage.setX(0);
                stage.setY(0);
                stage.show();
                return null;
            });
            final MainWindow w = window.get();
            final ProjectModel m = w.model();
            final Scene scene = onFx(() -> main.get().getScene());
            final Node root = onFx(scene::getRoot);

            // A window holding a new project is on the Start page: four examples, and the way to step 1
            assertEquals(Step.START, onFx(() -> w.rail().current()));
            for (final Example example : Example.values()) {
                assertTrue(onFx(() -> find(root, Button.class, b -> example.title().equals(b.getText())).isPresent()),
                        () -> "no card for " + example);
            }
            final Button begin = onFx(() -> find(root, Button.class,
                    b -> I18n.get("start.begin").equals(b.getText())).orElseThrow());
            onFx(() -> {
                begin.fire();
                return null;
            });
            assertEquals(Step.SUBJECT, onFx(() -> w.rail().current()), "Start with step 1");
            onFx(() -> {
                scene.getAccelerators().get(new KeyCodeCombination(KeyCode.DIGIT0, KeyCombination.ALT_DOWN)).run();
                return null;
            });
            assertEquals(Step.START, onFx(() -> w.rail().current()), "Alt+0");

            // A card: its example, on its first step, as a new project
            final Button hike = onFx(() -> find(root, Button.class,
                    b -> Example.HIKE.title().equals(b.getText())).orElseThrow());
            onFx(() -> {
                hike.fire();
                return null;
            });
            assertEquals(Step.SUBJECT, onFx(() -> w.rail().current()));
            assertEquals("swisstopo", onFx(() -> m.providerProperty().get().id()));
            assertEquals(Medium.paper("A4"), onFx(() -> m.paperProperty().get()));
            assertNull(onFx(() -> m.fileProperty().get()), "Save asks for a name");
            assertFalse(onFx(() -> m.dirtyProperty().get()), "nothing unsaved");
            assertFalse(onFx(() -> m.history().canUndoProperty().get()), "nothing to undo");
            assertTrue(onFx(() -> main.get().getTitle()).startsWith(I18n.get("project.untitled")),
                    () -> "titled " + main.get().getTitle());
            // A visit: nothing it brought is remembered as last used, nor the map of its provider
            assertEquals(nowhere.id(), prefs.get(Prefs.LAST_PROVIDER));
            assertEquals("A3", prefs.get(Prefs.LAST_PAPER));
            assertEquals(Medium.Orientation.LANDSCAPE.name(), prefs.get(Prefs.LAST_ORIENTATION));
            assertEquals(3, prefs.getDouble(Prefs.PRINT_EDGE_MM, -1));
            assertNull(prefs.themeOf("swisstopo"));

            // New: the Start page again, on the machine's own provider, paper and edge
            final Button newProject = onFx(() -> find(root, Button.class, b -> b.getTooltip() != null
                    && I18n.get("project.new").equals(b.getTooltip().getText())).orElseThrow());
            onFx(() -> {
                newProject.fire();
                return null;
            });
            assertEquals(Step.START, onFx(() -> w.rail().current()));
            assertEquals(nowhere.id(), onFx(() -> m.providerProperty().get().id()));
            assertEquals(Medium.paper("A3"), onFx(() -> m.paperProperty().get()));
            assertEquals(Medium.Orientation.LANDSCAPE, onFx(() -> m.orientationProperty().get()));
            assertEquals(3, onFx(() -> m.edgeMmProperty().get()));
            assertNull(onFx(() -> m.subjectProperty().get()));
            assertNull(onFx(() -> m.areaProperty().get()));
            assertFalse(onFx(() -> m.dirtyProperty().get()));

            // A project opened over the Start page goes to its steps; over another step, it stays there
            final Path file = dir.resolve("mine.wtf");
            onFx(() -> {
                ProjectIo.save(m, file);
                return null;
            });
            assertEquals(Step.START, onFx(() -> w.rail().current()), "saving goes nowhere");
            assertTrue(onFx(() -> w.openProjectFile(file)));
            assertEquals(Step.SUBJECT, onFx(() -> w.rail().current()));
            onFx(() -> {
                w.rail().select(Step.DETAIL);
                return null;
            });
            assertTrue(onFx(() -> w.openProjectFile(file)));
            assertEquals(Step.DETAIL, onFx(() -> w.rail().current()));
        } finally {
            onFx(() -> {
                for (final Window open : List.copyOf(Window.getWindows())) {
                    if (open != main.get()) {
                        open.hide();
                    }
                }
                if (window.get() != null) {
                    window.get().saveState();
                }
                if (main.get() != null) {
                    main.get().hide();
                }
                return null;
            });
            Dialogs.setBatchMode(batch);
            System.setProperty("user.home", home);
            Preferences.userRoot().node(path).removeNode();
        }
    }
}
