package net.trdlo.wtf.studio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The notices an installation carries, read from {@code target/libs} as the build leaves it for
 * jpackage: every jar has its line in {@code THIRD-PARTY.txt}, and every license a line names has
 * its full text in {@code licenses/}. Run by failsafe after {@code package}, which is when the
 * folder is whole.
 */
class ThirdPartyNoticesIT {

    private static final Path LIBS = Path.of("target", "libs");

    /** A line of the list: four spaces, the licenses in parentheses, then what they cover. */
    private static final Pattern ENTRY = Pattern.compile("^ {4}((?:\\([^()]+\\) )+)\\S.*$");
    private static final Pattern LICENSE = Pattern.compile("\\(([^()]+)\\) ");
    /** A Maven dependency's coordinates at the end of its line: (group:artifact:version - url). */
    private static final Pattern COORDINATES = Pattern.compile("\\([\\w.-]+:([\\w.-]+):(\\S+) - \\S+\\)$");

    @Test
    @DisplayName("THIRD-PARTY.txt names every jar in target/libs")
    void namesEveryJar() throws IOException {
        final List<String> named = new ArrayList<>();
        for (final String line : entries()) {
            final Matcher m = COORDINATES.matcher(line);
            if (m.find()) {
                named.add(m.group(1) + "-" + m.group(2));
            }
        }
        final List<String> jars;
        try (Stream<Path> files = Files.list(LIBS)) {
            jars = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".jar"))
                    .sorted()
                    .toList();
        }
        assertFalse(jars.isEmpty(), "no jars in " + LIBS.toAbsolutePath());
        final List<String> unnamed = jars.stream()
                .filter(jar -> named.stream().noneMatch(n -> isJarOf(jar, n)))
                .toList();
        assertEquals(List.of(), unnamed, "jars THIRD-PARTY.txt does not name; it names " + named);
    }

    @Test
    @DisplayName("every license THIRD-PARTY.txt names has its text in licenses/")
    void everyLicenseHasItsText() throws IOException {
        final Set<String> licenses = new TreeSet<>();
        for (final String line : entries()) {
            final Matcher m = ENTRY.matcher(line);
            assertTrue(m.matches(), line);
            final Matcher l = LICENSE.matcher(m.group(1));
            while (l.find()) {
                licenses.add(l.group(1));
            }
        }
        assertTrue(licenses.contains("GPL-3.0-or-later"), "WTF's own license: " + licenses);
        for (final String license : licenses) {
            final Path text = LIBS.resolve("licenses").resolve(license + ".txt");
            assertTrue(Files.isRegularFile(text), "no text for " + license + " at " + text);
            assertFalse(Files.readString(text, StandardCharsets.UTF_8).isBlank(), "an empty " + text);
        }
    }

    /** The lines of the file that name a license, which is every line that says what ships. */
    private static List<String> entries() throws IOException {
        final Path notices = LIBS.resolve("THIRD-PARTY.txt");
        assertTrue(Files.isRegularFile(notices), "no " + notices.toAbsolutePath());
        final List<String> entries = Files.readAllLines(notices, StandardCharsets.UTF_8).stream()
                .filter(line -> line.startsWith("    ("))
                .toList();
        assertFalse(entries.isEmpty(), "THIRD-PARTY.txt lists nothing");
        return entries;
    }

    /** {@code artifact-version.jar}, or with a classifier after the version, as JavaFX's are. */
    private static boolean isJarOf(final String jar, final String artifactAndVersion) {
        return jar.equals(artifactAndVersion + ".jar")
                || jar.startsWith(artifactAndVersion + "-") && jar.endsWith(".jar");
    }
}
