package net.trdlo.wtf.studio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The change log parser and the version comparison.
 * <p>
 * The comparison is the part worth testing hardest: every hand-written version check has the same
 * bug, which is that {@code "0.10.0"} sorts before {@code "0.9.0"} as a string, and the symptom is
 * an application that stops offering updates the moment a minor version reaches ten.
 */
class ChangelogAndUpdateTest {

    // -------------------------------------------------------------------------------------------
    // Versions
    // -------------------------------------------------------------------------------------------

    @Test
    void comparesSegmentsNumericallyNotAsText() {
        assertTrue(UpdateCheck.isNewer("0.10.0", "0.9.0"), "ten is after nine");
        assertTrue(UpdateCheck.isNewer("1.0.0", "0.99.99"));
        assertTrue(UpdateCheck.isNewer("0.2.0", "0.1.9"));
        assertFalse(UpdateCheck.isNewer("0.9.0", "0.10.0"));
        assertFalse(UpdateCheck.isNewer("1.2.3", "1.2.3"));
    }

    @Test
    void toleratesAVeePrefixAndMissingSegments() {
        assertTrue(UpdateCheck.isNewer("v2", "1.9.9"));
        assertFalse(UpdateCheck.isNewer("v1.0", "1.0.0"));
        assertTrue(UpdateCheck.isNewer("1.0.1", "1.0"));
    }

    @Test
    void aReleaseBeatsTheSnapshotOfItself() {
        assertTrue(UpdateCheck.isNewer("0.1.0", "0.1.0-SNAPSHOT"),
                "running a snapshot of 0.1.0, the released 0.1.0 is an update");
        assertFalse(UpdateCheck.isNewer("0.1.0-SNAPSHOT", "0.1.0"),
                "and never the other way round");
    }

    @Test
    void buildsTheApiUrlFromARepositoryUrl() {
        assertEquals("https://api.github.com/repos/DickBoffin/wtf/releases",
                UpdateCheck.apiUrlFor("https://github.com/DickBoffin/wtf"));
        assertEquals("https://api.github.com/repos/DickBoffin/wtf/releases",
                UpdateCheck.apiUrlFor("https://github.com/DickBoffin/wtf.git"));
        assertEquals("https://api.github.com/repos/DickBoffin/wtf/releases",
                UpdateCheck.apiUrlFor("https://github.com/DickBoffin/wtf/"));
    }

    @Test
    void refusesToGuessAtSomethingThatIsNotGitHub() {
        assertNull(UpdateCheck.apiUrlFor(null));
        assertNull(UpdateCheck.apiUrlFor("https://example.com/project"));
        assertNull(UpdateCheck.apiUrlFor("https://github.com/DickBoffin/wtf/tree/master/docs"));
    }

    // -------------------------------------------------------------------------------------------
    // The change log
    // -------------------------------------------------------------------------------------------

    @Test
    void parsesHeadingsAndKeepsTheBodyUnderEach() {
        final List<Changelog.Release> releases = Changelog.parse("""
                # Change log

                Prose above the first release is not a release.

                ## 0.2.0 - 2026-10-01

                - something
                - something else

                ## 0.1.0 - 2026-09-21

                - the first one
                """);
        assertEquals(2, releases.size());
        assertEquals("0.2.0", releases.getFirst().version());
        assertEquals("2026-10-01", releases.getFirst().date());
        assertTrue(releases.getFirst().body().contains("something else"));
        assertFalse(releases.getFirst().body().contains("the first one"),
                "the next heading must end the previous body");
        assertEquals("0.1.0", releases.get(1).version());
    }

    @Test
    void acceptsAHeadingWithNoDate() {
        final List<Changelog.Release> releases = Changelog.parse("## 0.3.0\n- x\n");
        assertEquals(1, releases.size());
        assertNull(releases.getFirst().date());
        assertEquals("0.3.0", releases.getFirst().heading());
    }

    @Test
    void ignoresHeadingsThatAreNotVersions() {
        assertTrue(Changelog.parse("## Unreleased\n- x\n").isEmpty(),
                "a heading has to start with a digit to be a version");
    }

    @Test
    void stripsTheQualifierWhenMatchingAVersion() {
        assertEquals("0.1.0", Changelog.baseVersion("0.1.0-SNAPSHOT"));
        assertEquals("0.1.0", Changelog.baseVersion("v0.1.0"));
        assertEquals("2.0", Changelog.baseVersion("2.0"));
    }

    /**
     * The bundled file has to parse, or About shows an empty tab in a release build. The newest
     * released section has entries; the one above it may not yet, opened as a release goes out.
     */
    @Test
    void theBundledChangelogParses() {
        final List<Changelog.Release> releases = Changelog.releases();
        assertFalse(releases.isEmpty(), "CHANGELOG.md should be on the classpath and parsable");
        final Changelog.Release released = releases.stream()
                .filter(r -> r.date() != null && !r.date().isBlank() && !r.date().equals("unreleased"))
                .findFirst().orElseThrow();
        assertFalse(released.body().isBlank(), released.heading());
    }

    // -------------------------------------------------------------------------------------------
    // What a check says
    // -------------------------------------------------------------------------------------------

    private static final String RELEASES = """
            [{"tag_name": "v0.3.0", "draft": true, "html_url": "https://x/0.3.0"},
             {"tag_name": "v0.2.1-rc1", "prerelease": true, "html_url": "https://x/0.2.1"},
             {"tag_name": "v0.2.0", "html_url": "https://x/0.2.0", "body": "notes"},
             {"tag_name": "v0.1.0", "html_url": "https://x/0.1.0"}]""";

    @Test
    @DisplayName("a newer release is found, drafts and prereleases left out")
    void newer() {
        final UpdateCheck.Result r = UpdateCheck.read(200, RELEASES, "0.1.0");
        assertEquals(UpdateCheck.Status.NEWER, r.status());
        assertEquals("0.2.0", r.newer().orElseThrow().version());
        assertEquals("https://x/0.2.0", r.newer().orElseThrow().url());
    }

    @Test
    @DisplayName("up to date is said only when the list answered and had nothing newer")
    void upToDate() {
        assertEquals(UpdateCheck.Status.UP_TO_DATE, UpdateCheck.read(200, RELEASES, "0.2.0").status());
        assertEquals(UpdateCheck.Status.UP_TO_DATE, UpdateCheck.read(200, "[]", "0.1.0").status(),
                "nothing released yet");
        assertTrue(UpdateCheck.read(200, RELEASES, "0.2.0").newer().isEmpty());
    }

    @Test
    @DisplayName("a 404, a refusal, an answer that is not a list, and no network are not up to date")
    void couldNotCheck() {
        assertEquals(UpdateCheck.Status.UNKNOWN, UpdateCheck.read(404, "", "0.1.0").status());
        assertEquals(UpdateCheck.Status.UNKNOWN, UpdateCheck.read(403, "{\"message\": \"rate limit\"}", "0.1.0").status());
        assertEquals(UpdateCheck.Status.UNKNOWN, UpdateCheck.read(200, "<html>", "0.1.0").status());
        assertEquals(UpdateCheck.Status.UNKNOWN, UpdateCheck.read(200, "{}", "0.1.0").status());
        // Nothing listens on port 1, so the connection is refused at once
        assertEquals(UpdateCheck.Status.UNKNOWN,
                UpdateCheck.ask("http://127.0.0.1:1/repos/DickBoffin/wtf/releases", "0.1.0").status());
    }

    /** And it has to contain the version being built, or "what is new" has nothing to show. */
    @Test
    void theBundledChangelogCoversThisBuild() {
        final String version = AppInfo.get().version();
        assertNotNull(Changelog.forVersion(version),
                () -> "CHANGELOG.md has no entry for " + version + "; add one before releasing");
    }
}
