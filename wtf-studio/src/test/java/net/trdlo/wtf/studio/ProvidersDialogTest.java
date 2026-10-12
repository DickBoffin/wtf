package net.trdlo.wtf.studio;

import javafx.scene.control.Button;
import javafx.scene.layout.GridPane;
import net.trdlo.wtf.tiles.ProviderStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ProvidersDialog#named}: what the dialog says, when it opens, of the entries of the user's
 * file the store could not read; {@link ProvidersDialog#keyIn}, the key its field is kept as; and
 * {@link ProvidersDialog#buttonGrid}, how the buttons under the list share its width.
 */
class ProvidersDialogTest {

    private final Locale before = I18n.locale();

    @AfterEach
    void restore() {
        I18n.setLocale(before);
    }

    @Test
    @DisplayName("an entry that could not be read is named by its id, or by its place when it has none")
    void anEntryThatCouldNotBeReadIsNamedByItsIdOrItsPlace() {
        final List<ProviderStore.UnreadableEntry> kept = List.of(
                new ProviderStore.UnreadableEntry(2, "bad", "maxZoom: not a number"),
                new ProviderStore.UnreadableEntry(4, null, "no id"));

        I18n.setLocale(Locale.ENGLISH);
        assertEquals("'bad' – maxZoom: not a number\nEntry 4 in the list – no id", ProvidersDialog.named(kept));

        I18n.setLocale(Locale.forLanguageTag("cs-CZ"));
        assertEquals("„bad“ – maxZoom: not a number\nZáznam 4 v seznamu – no id", ProvidersDialog.named(kept));
    }

    @Test
    @DisplayName("a key is kept without the spaces a paste brings around it, of whatever kind, and with any inside it")
    void aKeyIsKeptWithoutTheSpacesAroundIt() {
        assertEquals("k-1_x", ProvidersDialog.keyIn(" k-1_x\t\n"));
        assertEquals("k-1_x", ProvidersDialog.keyIn("\u00a0k-1_x\u00a0"), "the no-break space a web page copies");
        assertEquals("user:pass word", ProvidersDialog.keyIn("  user:pass word "), "a space inside is the key's");
        assertEquals("", ProvidersDialog.keyIn(" \t "), "nothing but spaces is no key");
    }

    @Test
    @DisplayName("the buttons are two columns of one width, sharing the side and never narrower than their words, the last one across both")
    void theButtonsAreTwoColumnsOfOneWidth() throws Exception {
        Headless.start();
        Headless.onFx(() -> {
            final Button add = new Button("Add…");
            final Button duplicate = new Button("Duplicate");
            final Button edit = new Button("Edit");
            final Button remove = new Button("Remove");
            final Button openFile = new Button("Open the folder of the file with the providers");
            final GridPane grid = ProvidersDialog.buttonGrid(add, duplicate, edit, remove, openFile);
            // Shown before anything is measured: a button has no width of its words until its skin
            final var stage = Headless.show(grid, 300, 200);
            try {
                grid.applyCss();
                final double widest = Stream.of(add, duplicate, edit, remove, openFile)
                        .mapToDouble(b -> b.prefWidth(-1)).max().orElseThrow();
                assertTrue(widest > 200, "the long one is the widest: " + widest);
                assertEquals(2 * widest + grid.getHgap(), grid.minWidth(-1), 1,
                        "the side is no narrower than two of the widest button");

                grid.resize(2 * widest + 200, 200);
                grid.layout();
                for (final Button b : List.of(add, duplicate, edit, remove)) {
                    assertEquals(add.getWidth(), b.getWidth(), 0.5, b.getText() + " is as wide as the others");
                }
                assertEquals(grid.getWidth(), 2 * add.getWidth() + grid.getHgap(), 1, "the two fill the side");
                assertEquals(grid.getWidth(), openFile.getWidth(), 1, "the fifth, alone in its row, spans it");
                assertEquals(2, GridPane.getRowIndex(openFile));
            } finally {
                stage.close();
            }
            return null;
        });
    }
}
