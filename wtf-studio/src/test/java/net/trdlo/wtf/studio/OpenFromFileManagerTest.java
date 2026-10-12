package net.trdlo.wtf.studio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The project a file manager hands WTF, which the installers' {@code .wtf} association asks for. */
class OpenFromFileManagerTest {

    @Test
    @DisplayName("the first argument naming a .wtf file is opened, in whatever case the system wrote it")
    void theFirstProjectArgument() {
        assertEquals(Path.of("C:/Maps/Alps.WTF"),
                WtfApp.projectToOpen(List.of("notes.txt", "C:/Maps/Alps.WTF", "second.wtf")));
    }

    @Test
    @DisplayName("started without a project, WTF opens none")
    void noProjectArgument() {
        assertNull(WtfApp.projectToOpen(List.of()));
        assertNull(WtfApp.projectToOpen(List.of("wall.pdf", "wtf")));
    }
}
