package net.trdlo.wtf.studio.steps;

import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.stage.Stage;
import net.trdlo.wtf.compose.Shapes;
import net.trdlo.wtf.studio.Dialogs;
import net.trdlo.wtf.studio.ErrorText;
import net.trdlo.wtf.studio.Headless;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.studio.Prefs;
import net.trdlo.wtf.studio.project.ProjectModel;
import net.trdlo.wtf.tiles.LonLatBounds;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.prefs.Preferences;
import java.util.stream.Stream;

import static net.trdlo.wtf.studio.Headless.find;
import static net.trdlo.wtf.studio.Headless.onFx;
import static net.trdlo.wtf.studio.Headless.waitUntil;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The Export button, pressed before the step has counted: it counts first, and a count that
 * throws gives the button back, enabled, and says why nothing happened. {@code BackgroundWorkTest} holds the landing; this presses the button.
 * <p>
 * On JavaFX's headless platform ({@link Headless}). The throw is the count's own: the cache the
 * count asks its context for, on its own thread, is a supplier that throws – a runtime exception,
 * and an error, as a stack overflow on a detailed cache is. In batch mode, so the error is a line
 * on stderr rather than a window nobody would close. The print's provider is
 * {@link Headless#nowhere}, and its folder a temporary one, so a press that got past the count
 * would reach nothing beyond this machine and write nowhere of the machine's.
 */
class ExportPanelTest {

    private static final LonLatBounds BISCAY = new LonLatBounds(-10, 46, 4, 50);

    @BeforeAll
    static void toolkit() {
        Headless.start();
    }

    static Stream<Throwable> thrown() {
        return Stream.of(new IllegalStateException("thrown by ExportPanelTest: the cache went away"),
                new StackOverflowError("thrown by ExportPanelTest: a detailed cache"));
    }

    /** The count's work throwing, whatever it throws, from where it asks for the cache. */
    private static <T> T raise(final Throwable thrown) {
        if (thrown instanceof final RuntimeException ex) {
            throw ex;
        }
        if (thrown instanceof final Error ex) {
            throw ex;
        }
        throw new AssertionError(thrown);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("thrown")
    @DisplayName("a count that throws gives the Export button back, and says so")
    void aCountThatThrowsGivesTheButtonBack(final Throwable thrown, @TempDir final Path dir) throws Exception {
        final String path = "trdlo.net/wtf-test-" + UUID.randomUUID();
        final boolean batch = Dialogs.isBatchMode();
        final PrintStream err = System.err;
        final ByteArrayOutputStream said = new ByteArrayOutputStream();
        ExportPanel shown = null;
        Stage stage = null;
        try {
            Dialogs.setBatchMode(true);
            final ProjectModel m = new ProjectModel();
            final Provider nowhere = Headless.nowhere();
            m.startOver(() -> {
                m.providerProperty().set(nowhere);
                m.themeProperty().set(nowhere.themes().getFirst());
                m.areaSourceProperty().set(ProjectModel.AreaSource.CUSTOM);
                m.customProperty().set(Shapes.rectangle(BISCAY));
                m.zoomProperty().set(6);
                m.outputDirProperty().set(dir.resolve("out"));
            });
            final StepContext ctx = new StepContext(null, null, new Prefs(path), ProviderCatalog::builtIn,
                    () -> raise(thrown), () -> false, () -> false, p -> { });
            final ExportPanel panel = new ExportPanel(m, ctx);
            stage = onFx(() -> Headless.show(new ScrollPane(panel.node()), 360, 800));
            shown = panel;
            final Button export = onFx(() -> find(panel.node(), Button.class,
                    b -> I18n.get("export.run").equals(b.getText())).orElseThrow());

            System.setErr(new PrintStream(said, true, StandardCharsets.UTF_8));
            // The step appears and Export is pressed at once, before its count has landed
            assertTrue(onFx(() -> {
                panel.shown();
                export.fire();
                return export.isDisabled();
            }), "the press waits for a count of its own");
            waitUntil("the Export button back after its count threw", () -> !export.isDisabled());
            final String out = said.toString(StandardCharsets.UTF_8);
            assertTrue(out.contains(ErrorText.explain(thrown).headline()), () -> "said: " + out);
        } finally {
            System.setErr(err);
            Dialogs.setBatchMode(batch);
            final ExportPanel panel = shown;
            final Stage window = stage;
            onFx(() -> {
                if (panel != null) {
                    panel.hidden();
                }
                if (window != null) {
                    window.close();
                }
                return null;
            });
            Preferences.userRoot().node(path).removeNode();
        }
    }
}
