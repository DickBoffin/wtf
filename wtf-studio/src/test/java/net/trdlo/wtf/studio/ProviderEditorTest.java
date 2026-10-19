package net.trdlo.wtf.studio;

import javafx.scene.control.DialogPane;
import javafx.scene.control.TableView;
import javafx.scene.layout.Region;
import net.trdlo.wtf.tiles.ProviderStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static net.trdlo.wtf.studio.Headless.onFx;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The provider editor, laid out on JavaFX's headless platform ({@link Headless}) under the
 * studio's theme.
 */
class ProviderEditorTest {

    @BeforeAll
    static void toolkit() {
        Headless.start();
    }

    /** The maps table took its header for a row's height, and the theme's is taller. */
    @Test
    @DisplayName("the maps table is tall enough for its header and every row")
    void theMapsTableShowsEveryRowWhole(final @TempDir Path dir) throws Exception {
        final double[] sizes = onFx(() -> {
            final DialogPane pane = new ProviderEditor(null,
                    new ProviderStore(dir.resolve("providers.json")), null, false).getDialogPane();
            pane.resize(900, 2000);
            pane.applyCss();
            pane.layout();
            // The height follows the header's, which is known only after the first pass
            pane.layout();
            final TableView<?> table = (TableView<?>) pane.lookup(".table-view");
            final Region header = (Region) table.lookup(".column-header-background");
            return new double[] {table.getHeight(), header.getHeight(),
                    table.getFixedCellSize() * table.getItems().size()};
        });
        assertTrue(sizes[2] > 0, "a new provider starts with a map");
        assertTrue(sizes[1] > 0, "the header has been laid out");
        assertTrue(sizes[0] >= sizes[1] + sizes[2],
                () -> "table " + sizes[0] + ", header " + sizes[1] + ", rows " + sizes[2]);
    }
}
