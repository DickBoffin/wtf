package net.trdlo.wtf.studio;

import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import net.trdlo.wtf.tiles.Provider;
import net.trdlo.wtf.tiles.ProviderCatalog;
import net.trdlo.wtf.tiles.TileCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static net.trdlo.wtf.studio.Headless.onFx;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A stage the studio decorates carries its icons and its stylesheet, and opens with the bounds it
 * was last closed with – unless those are off every screen, as when the monitor they were on has
 * been unplugged. The Providers dialog keeps its bounds too, on the stage JavaFX makes for it.
 * <p>
 * On JavaFX's headless platform ({@link Headless}), with a preference node of the test's own.
 */
class WindowsTest {

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

    /** Somewhere well inside the primary screen, and a size that is not anybody's default. */
    private static double[] inside() throws Exception {
        final Rectangle2D s = onFx(() -> Screen.getPrimary().getVisualBounds());
        return new double[]{s.getMinX() + 37, s.getMinY() + 41, Math.min(613, s.getWidth() - 80),
                Math.min(419, s.getHeight() - 80)};
    }

    @Test
    @DisplayName("a decorated stage has its icons and the sheet, and opens as it was closed")
    void aStageOpensWithTheBoundsItWasClosedWith() throws Exception {
        final Prefs prefs = new Prefs(path);
        final double[] b = inside();

        final Stage first = onFx(() -> {
            final Stage stage = stage();
            Windows.decorate(stage, prefs, "probe");
            stage.show();
            stage.setX(b[0]);
            stage.setY(b[1]);
            stage.setWidth(b[2]);
            stage.setHeight(b[3]);
            stage.hide();
            return stage;
        });
        assertFalse(first.getIcons().isEmpty(), "icons");
        assertEquals(List.of(Windows.STYLESHEET), first.getScene().getStylesheets());

        final double[] reopened = onFx(() -> {
            final Stage stage = stage();
            Windows.decorate(stage, prefs, "probe");
            stage.show();
            final double[] at = {stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()};
            stage.hide();
            return at;
        });
        assertArrayEquals(b, reopened, 0.5);
    }

    @Test
    @DisplayName("bounds off every screen are not restored, and the stage opens where JavaFX puts it")
    void boundsOffEveryScreenAreLeftAlone() throws Exception {
        final Prefs prefs = new Prefs(path);
        final Rectangle2D all = onFx(() -> {
            double maxX = 0;
            double maxY = 0;
            for (final Screen s : Screen.getScreens()) {
                maxX = Math.max(maxX, s.getBounds().getMaxX());
                maxY = Math.max(maxY, s.getBounds().getMaxY());
            }
            return new Rectangle2D(0, 0, maxX, maxY);
        });
        // Where a monitor to the right of all the others was
        prefs.put("window.gone", String.format(Locale.ROOT, "%.1f;%.1f;%.1f;%.1f;%b",
                all.getMaxX() + 500, 100.0, 700.0, 500.0, false));

        final double[] at = onFx(() -> {
            final Stage stage = stage();
            Windows.decorate(stage, prefs, "gone");
            return new double[]{stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()};
        });
        for (final double v : at) {
            assertTrue(Double.isNaN(v), () -> "restored off screen: " + java.util.Arrays.toString(at));
        }
    }

    @Test
    @DisplayName("the Providers dialog opens with the bounds it was closed with")
    void theProvidersDialogKeepsItsBounds(final @TempDir Path dir) throws Exception {
        System.setProperty("user.home", dir.toString());
        final Prefs prefs = new Prefs(path);
        final TileCache cache = new TileCache(dir.resolve("tiles"));
        final ProviderCatalog catalog = ProviderCatalog.builtIn();
        final Provider keyless = catalog.all().stream().filter(p -> !p.needsKey()).findFirst().orElseThrow();
        final double[] b = inside();
        final Stage owner = onFx(WindowsTest::stage);

        final double[] opened = onFx(() -> {
            final ProvidersDialog dialog = new ProvidersDialog(owner, prefs, catalog, cache, keyless);
            dialog.show();
            final double[] first = {dialog.getX(), dialog.getY(), dialog.getWidth(), dialog.getHeight()};
            dialog.setX(b[0]);
            dialog.setY(b[1]);
            dialog.setWidth(b[2]);
            dialog.setHeight(b[3]);
            dialog.close();
            return first;
        });
        assertFalse(java.util.Arrays.equals(b, opened), "the first time, nothing to restore");

        final double[] reopened = onFx(() -> {
            final ProvidersDialog dialog = new ProvidersDialog(owner, prefs, catalog, cache, keyless);
            dialog.show();
            final double[] at = {dialog.getX(), dialog.getY(), dialog.getWidth(), dialog.getHeight()};
            dialog.close();
            return at;
        });
        assertArrayEquals(b, reopened, 0.5);
    }

    private static Stage stage() {
        final Stage stage = new Stage();
        stage.setScene(new Scene(new StackPane()));
        return stage;
    }
}
