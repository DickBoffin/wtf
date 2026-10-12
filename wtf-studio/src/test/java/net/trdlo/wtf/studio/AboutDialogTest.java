package net.trdlo.wtf.studio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The About window's license table, read without opening the window. */
class AboutDialogTest {

    @Test
    @DisplayName("the license table names the runtime every installer links and the jar PDFBox brings")
    void namesWhatAnInstallerCarries() {
        final List<String> rows = AboutDialog.licenseRows();
        assertTrue(rows.contains("OpenJDK"), "jlink puts the runtime in every installer: " + rows);
        assertTrue(rows.contains("Apache Commons Logging"),
                "PDFBox depends on it, so every installer carries it: " + rows);
    }
}
