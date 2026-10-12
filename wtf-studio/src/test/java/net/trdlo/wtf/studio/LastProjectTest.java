package net.trdlo.wtf.studio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** A start opens the project the window last closed on, unless it is handed one, or there was none. */
class LastProjectTest {

    @Test
    @DisplayName("a project handed to the start wins; else the last one, while it is still there")
    void whatAStartOpens(@TempDir final Path dir) throws Exception {
        final Path last = Files.writeString(dir.resolve("hall.wtf"), "{}");
        final Path given = dir.resolve("given.wtf");
        assertEquals(given, ProjectFiles.atStart(given, last), "the file manager's, or the command line's");
        assertEquals(last, ProjectFiles.atStart(null, last), "the one the window closed on");
        assertNull(ProjectFiles.atStart(null, null), "after New, a new project");
        assertNull(ProjectFiles.atStart(null, dir.resolve("moved.wtf")), "a file gone is not opened");
    }

    @Test
    @DisplayName("a close remembers the project's file, and after New that there is none")
    void aCloseRemembers(@TempDir final Path dir) throws Exception {
        final String node = "trdlo.net/wtf-test-" + java.util.UUID.randomUUID();
        final Prefs prefs = new Prefs(node);
        try {
            final Path file = dir.resolve("hall.wtf");
            ProjectFiles.rememberForNextStart(prefs, file);
            assertEquals(file.toAbsolutePath(), prefs.getPath(Prefs.LAST_PROJECT));
            ProjectFiles.rememberForNextStart(prefs, null);
            assertNull(prefs.getPath(Prefs.LAST_PROJECT), "New, then close: nothing to reopen");
        } finally {
            java.util.prefs.Preferences.userRoot().node(node).removeNode();
        }
    }
}
