package net.trdlo.wtf.studio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The About window's license table and the files it links, read without opening the window. */
class AboutDialogTest {

    @Test
    @DisplayName("the license table names the runtime every installer links and the jar PDFBox brings")
    void namesWhatAnInstallerCarries() {
        final List<String> rows = AboutDialog.licenseRows();
        assertTrue(rows.contains("OpenJDK"), "jlink puts the runtime in every installer: " + rows);
        assertTrue(rows.contains("Apache Commons Logging"),
                "PDFBox depends on it, so every installer carries it: " + rows);
    }

    @Test
    @DisplayName("the license files are linked when the application's folder has both, and not otherwise")
    void linksTheFilesBesideTheJars(final @TempDir Path folder) throws IOException {
        assertEquals(List.of(), AboutDialog.licenseFiles(null));
        Files.writeString(folder.resolve("THIRD-PARTY.txt"), "notices");
        assertEquals(List.of(), AboutDialog.licenseFiles(folder), "no licenses folder");
        Files.createDirectory(folder.resolve("licenses"));
        assertEquals(List.of(folder.resolve("THIRD-PARTY.txt").toAbsolutePath(),
                        folder.resolve("licenses").toAbsolutePath()),
                AboutDialog.licenseFiles(folder));
    }
}
