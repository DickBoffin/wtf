package net.trdlo.wtf.compose;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.trdlo.wtf.tiles.ProviderCatalog;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The example in docs/design/project-file.md, read from the document, so that the two cannot drift
 * apart: a field the code renames, drops or adds shows up here rather than in somebody's editor.
 */
class ProjectFileExampleTest {

    private static final Path DOC = Path.of("..", "docs", "design", "project-file.md");
    private static final String HEADING = "## The whole file";

    /**
     * Fields the example cannot hold, because it holds what stands in their place: a roll instead
     * of a sheet, a count across instead of down, a DPI or a scale, a width instead of a height for a print
     * sized to a width, the usual tiles rather than @2x ones, and a fit of the subject rather than a
     * Custom shape, which a file has one or the other of. The subject's {@code kind} is a note the
     * editor never writes.
     */
    private static final Set<String> NOT_IN_THE_EXAMPLE = Set.of(
            "subject.kind",
            "refine.custom", "refine.custom.kind", "refine.custom.source", "refine.custom.polygons",
            "print.heightMm", "print.setAside.heightMm", "print.dpi", "print.mapScale", "regions.scale",
            "medium.sheetWidthMm", "medium.sheetHeightMm", "medium.orientation", "medium.sheetsDown");

    private static String example;

    @BeforeAll
    static void readTheDocument() throws IOException {
        assertTrue(Files.isRegularFile(DOC), () -> "read from beside wtf-compose: " + DOC.toAbsolutePath());
        final String text = Files.readString(DOC);
        final int section = text.indexOf(HEADING);
        final int start = section < 0 ? -1 : text.indexOf("```json", section);
        final int end = start < 0 ? -1 : text.indexOf("```", start + "```json".length());
        assertTrue(section >= 0 && start > section && end > start,
                "the example is the first json block under \"" + HEADING + "\", and it has moved");
        example = text.substring(start + "```json".length(), end);
    }

    private static @NotNull WtfFile read(final @NotNull Path dir) throws IOException {
        final Path file = dir.resolve("example.wtf");
        Files.writeString(file, example);
        return WtfFile.read(file);
    }

    @Test
    @DisplayName("the document's example opens, and saving it again writes the same file")
    void theExampleIsWhatWtfWrites(@TempDir final Path dir) throws IOException {
        final WtfFile f = assertDoesNotThrow(() -> read(dir), "WtfFile.read refuses the example");

        final Path again = dir.resolve("again.wtf");
        f.write(again);
        final ObjectMapper json = new ObjectMapper();
        // As trees: field for field and value for value, 3161.0 not 3161, but not the line breaks
        assertEquals(json.readTree(example), json.readTree(again.toFile()),
                "the example is not what WtfFile writes; what it writes is:\n" + Files.readString(again));
    }

    @Test
    @DisplayName("the document's example shows every field the format has")
    void theExampleIsComplete() throws IOException {
        final Set<String> shown = new TreeSet<>();
        paths(new ObjectMapper().readTree(example), "", shown);
        final Set<String> known = new TreeSet<>();
        components(WtfFile.class, "", known);

        final Set<String> unknown = new TreeSet<>(shown);
        unknown.removeAll(known);
        assertEquals(Set.of(), unknown, "the example has fields the records do not");

        final Set<String> missing = new TreeSet<>(known);
        missing.removeAll(shown);
        missing.removeAll(NOT_IN_THE_EXAMPLE);
        assertEquals(Set.of(), missing, "the records have fields the example leaves out");
        assertTrue(known.containsAll(NOT_IN_THE_EXAMPLE), "a field excused from the example has gone");
    }

    @Test
    @DisplayName("the document's example agrees with itself, the way the editor checks on open")
    void theExampleReproducesItself(@TempDir final Path dir) throws IOException {
        final WtfFile f = read(dir);

        final WtfFile.AreaCheck area = f.checkArea();
        assertTrue(area.claimed(), "the example's area is worked out from its subject");
        assertTrue(area.reproduces(), () -> "working the area out again is " + area.deviationMeters()
                + " m off: it gives " + area.recomputed());

        // The count is a note beside the size, and the editor refuses a file where the two differ.
        // The arithmetic is ProjectModel.millimetersOf's, which this module cannot see: every seam
        // but the last is shared, and the printer's edge is kept free at either end.
        final WtfFile.MediumJson m = f.medium();
        final int n = m.sheetsAcross();
        assertEquals(n * m.rollWidthMm() - (n - 1) * m.overlapMm() - 2 * m.edgeMm(), f.print().widthMm(), 0.01,
                "the example's strips do not come to its width");
    }

    @Test
    @DisplayName("the document's example plans as a print")
    void theExamplePlans(@TempDir final Path dir) throws IOException {
        final ComposeJob job = read(dir).toJob(ProviderCatalog.builtIn(), dir);
        final SheetLayout layout = new Composer().layout(job);
        assertEquals(3, layout.count(), "three strips of the roll: " + layout.describe());
        assertNotNull(job.subject());
        assertNotNull(job.regions().boundary(), "the outline is drawn");
    }

    // -------------------------------------------------------------------------------------------

    /** Every field of an object, at every depth, as a dotted path; arrays are leaves. */
    private static void paths(final @NotNull JsonNode node, final @NotNull String prefix,
                              final @NotNull Set<String> out) {
        for (final Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            final String name = it.next();
            final String path = prefix + name;
            out.add(path);
            final JsonNode child = node.get(name);
            if (child.isObject()) {
                paths(child, path + ".", out);
            }
        }
    }

    /** Every component of a project-file record, into nested records, as a dotted path. */
    private static void components(final @NotNull Class<?> record, final @NotNull String prefix,
                                   final @NotNull Set<String> out) {
        for (final RecordComponent c : record.getRecordComponents()) {
            final String path = prefix + c.getName();
            out.add(path);
            if (c.getType().isRecord()) {
                components(c.getType(), path + ".", out);
            }
            assertFalse(c.getGenericType() instanceof final ParameterizedType p
                            && List.of(p.getActualTypeArguments()).stream().anyMatch(ProjectFileExampleTest::isRecord),
                    () -> path + " is a list of records, which this walk does not look into");
        }
    }

    private static boolean isRecord(final @NotNull Type t) {
        return t instanceof final Class<?> c && c.isRecord();
    }
}
