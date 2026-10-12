package net.trdlo.wtf.studio.outline;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The outline files as git keeps them (D35): {@code tools/outlines/OutlineImport.java} run over
 * unchanged Natural Earth data has to leave nothing to commit. So the index carries no date, and
 * every number is a plain decimal – a longitude of 180 is not {@code 1.8E+2}, which is what the
 * rounding gives when it strips trailing zeros. Both are checked in the files themselves, since
 * the importer needs a download to run.
 */
class OutlineFilesTest {

    /** Read from the source tree, like the design document's example in ProviderCatalogTest. */
    private static final Path LIBRARY = Path.of("src", "main", "resources", "net", "trdlo", "wtf", "studio",
            "outlines");

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("the index says where the outlines come from, and not when they were made")
    void theIndexCarriesNoDate() throws IOException {
        final var index = JSON.readTree(LIBRARY.resolve("index.json").toFile());
        assertFalse(index.path("source").asText().isBlank());
        final List<String> fields = new ArrayList<>();
        index.fieldNames().forEachRemaining(fields::add);
        assertEquals(List.of("source", "entries"), fields,
                "a field beside these changes with every run, or is new to this test");
    }

    @Test
    @DisplayName("every number in every outline file is a plain decimal")
    void everyNumberIsPlain() throws IOException {
        final List<Path> files;
        try (var walk = Files.walk(LIBRARY)) {
            files = walk.filter(f -> f.toString().endsWith(".json")).sorted().toList();
        }
        assertTrue(files.size() > 600, "the library is not where the test looks: " + LIBRARY.toAbsolutePath());
        final List<String> exponents = new ArrayList<>();
        for (final Path f : files) {
            try (JsonParser p = JSON.createParser(f.toFile())) {
                for (JsonToken t = p.nextToken(); t != null; t = p.nextToken()) {
                    if (t.isNumeric() && p.getText().matches(".*[eE].*")) {
                        exponents.add(LIBRARY.relativize(f) + ": " + p.getText());
                        break;
                    }
                }
            }
        }
        assertEquals(List.of(), exponents);
    }
}
