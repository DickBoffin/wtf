package net.trdlo.wtf.studio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import net.trdlo.wtf.studio.Dialogs.BeforeExport;
import net.trdlo.wtf.studio.Dialogs.Unsaved;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What each answer to the two save questions does – before changes are lost, and before an
 * export – and what the first says for a project handed over from the file manager.
 */
class SaveQuestionsTest {

    private static final Path ALPS = Path.of("maps", "Alps.wtf");

    /** A save that answers {@code saved} and counts how often it was asked. */
    private static final class Save implements BooleanSupplier {
        private final boolean saved;
        int calls;

        Save(final boolean saved) {
            this.saved = saved;
        }

        @Override
        public boolean getAsBoolean() {
            calls++;
            return saved;
        }
    }

    @Test
    @DisplayName("before changes are lost: Save goes on only once saved, Don't save goes on, Cancel stops")
    void unsaved() {
        final Save saves = new Save(true);
        assertTrue(Unsaved.SAVE.goesOn(saves));
        assertEquals(1, saves.calls);

        final Save closedPicker = new Save(false);
        assertFalse(Unsaved.SAVE.goesOn(closedPicker), "a picker closed without choosing keeps the changes");

        final Save untouched = new Save(true);
        assertTrue(Unsaved.DISCARD.goesOn(untouched));
        assertFalse(Unsaved.CANCEL.goesOn(untouched));
        assertEquals(0, untouched.calls, "neither saves anything");
    }

    @Test
    @DisplayName("before an export: each save goes on once saved, the unsaved export goes on, Cancel stops")
    void beforeExport() {
        final Save save = new Save(true);
        final Save saveAs = new Save(true);
        assertTrue(BeforeExport.SAVE.goesOn(save, saveAs));
        assertEquals(List.of(1, 0), List.of(save.calls, saveAs.calls), "Save saves to the file");
        assertTrue(BeforeExport.SAVE_AS.goesOn(save, saveAs));
        assertEquals(List.of(1, 1), List.of(save.calls, saveAs.calls), "Save as asks where");

        final Save closedPicker = new Save(false);
        assertFalse(BeforeExport.SAVE.goesOn(closedPicker, closedPicker));
        assertFalse(BeforeExport.SAVE_AS.goesOn(closedPicker, closedPicker),
                "whoever asked to save and closed the picker has not said to print unsaved changes");

        final Save untouched = new Save(true);
        assertTrue(BeforeExport.EXPORT_UNSAVED.goesOn(untouched, untouched));
        assertFalse(BeforeExport.CANCEL.goesOn(untouched, untouched));
        assertEquals(0, untouched.calls);
    }

    @Test
    @DisplayName("Save as is offered before an export only when the project has a file to differ from")
    void saveAsOnlyWithAFile() {
        assertEquals(List.of(BeforeExport.SAVE, BeforeExport.SAVE_AS, BeforeExport.EXPORT_UNSAVED, BeforeExport.CANCEL),
                BeforeExport.offered(true));
        assertEquals(List.of(BeforeExport.SAVE, BeforeExport.EXPORT_UNSAVED, BeforeExport.CANCEL),
                BeforeExport.offered(false));
    }

    @Test
    @DisplayName("with nobody there, the seeded project is not saved, so its X closes it, and an export goes on unsaved")
    void batchMode() {
        final boolean was = Dialogs.isBatchMode();
        Dialogs.setBatchMode(true);
        try {
            final Unsaved answer = Dialogs.askUnsaved(null);
            assertEquals(Unsaved.DISCARD, answer);
            assertTrue(answer.goesOn(() -> fail("a capture run writes no project file")),
                    "the close goes on");
            assertEquals(BeforeExport.EXPORT_UNSAVED, Dialogs.askBeforeExport(null, true));
        } finally {
            Dialogs.setBatchMode(was);
        }
    }

    @Test
    @DisplayName("a hand-over's question names the file in both languages; the ordinary one is unchanged")
    void aHandOversQuestionNamesTheFile() {
        final Locale before = I18n.locale();
        try {
            for (final Locale language : I18n.LANGUAGES) {
                I18n.setLocale(language);
                final String ordinary = I18n.get("project.discard.body");
                assertEquals(ordinary, Dialogs.unsavedBody(null), language.toString());
                final String handedOver = Dialogs.unsavedBody(ALPS);
                assertTrue(handedOver.contains("Alps.wtf"), language + ": " + handedOver);
                assertFalse(handedOver.contains("maps"), language + ": the name, not the path: " + handedOver);
                assertFalse(ordinary.contains("Alps.wtf"), language.toString());
            }
        } finally {
            I18n.setLocale(before);
        }
    }

    @Test
    @DisplayName("the question a hand-over raises says the file, asked through the dialog itself")
    void aHandOversQuestionIsAskedWithTheFile() {
        final Locale before = I18n.locale();
        final boolean was = Dialogs.isBatchMode();
        final PrintStream out = System.out;
        final ByteArrayOutputStream said = new ByteArrayOutputStream();
        try {
            I18n.setLocale(Locale.ENGLISH);
            Dialogs.setBatchMode(true);
            System.setOut(new PrintStream(said, true, StandardCharsets.UTF_8));
            assertEquals(Dialogs.Unsaved.DISCARD, Dialogs.askUnsaved(null, ALPS));
            assertEquals(Dialogs.Unsaved.DISCARD, Dialogs.askUnsaved(null));
            System.setOut(out);
            final String[] lines = said.toString(StandardCharsets.UTF_8).strip().split("\\R");
            assertEquals(2, lines.length, String.join("|", lines));
            assertTrue(lines[0].contains(Dialogs.unsavedBody(ALPS)), lines[0]);
            assertTrue(lines[1].endsWith(I18n.get("project.discard.body")), lines[1]);
        } finally {
            System.setOut(out);
            Dialogs.setBatchMode(was);
            I18n.setLocale(before);
        }
    }
}
