package net.trdlo.wtf.studio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What the application says it was built from: the commit, which two builds of the same source
 * share, and not the day, which made every build a different jar.
 */
class AppInfoTest {

    private static Properties commit(final String value) {
        final Properties p = new Properties();
        p.setProperty("app.commit", value);
        return p;
    }

    @Test
    @DisplayName("a build of a git checkout names the commit, whole")
    void theBuildNamesItsCommit() {
        // The module's parent is the checkout; a source archive has no .git, and says "-"
        assumeTrue(Files.exists(Path.of("..", ".git")), "not built from a git checkout");
        // So does the IDE's own build, which runs no Maven plugin to ask git
        assumeTrue(!AppInfo.NO_COMMIT.equals(AppInfo.get().commit()),
                "app.properties was not filled in by Maven: the IDE's own build, or no git on the path");
        assertTrue(AppInfo.get().commit().matches("[0-9a-f]{40}"), AppInfo.get().commit());
        assertEquals(AppInfo.get().commit().substring(0, 7), AppInfo.get().shortCommit());
    }

    @Test
    @DisplayName("a commit nobody filled in is a dash, not a placeholder")
    void anUnfilledCommitIsADash() {
        // The IDE's own build leaves the placeholder; a build without git leaves nothing
        assertEquals(AppInfo.NO_COMMIT, AppInfo.of(commit("${git.commit}")).commit());
        assertEquals(AppInfo.NO_COMMIT, AppInfo.of(commit("")).commit());
        assertEquals(AppInfo.NO_COMMIT, AppInfo.of(new Properties()).commit());
        assertEquals(AppInfo.NO_COMMIT, AppInfo.of(new Properties()).shortCommit());
    }

    @Test
    @DisplayName("the commit is shown the way git shows it, seven characters")
    void theShortCommit() {
        final AppInfo info = AppInfo.of(commit("42354b65642609e5255c27b592f8a3e6662a2965"));
        assertEquals("42354b65642609e5255c27b592f8a3e6662a2965", info.commit());
        assertEquals("42354b6", info.shortCommit());
    }
}
