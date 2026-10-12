package net.trdlo.wtf.studio;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Properties;

/**
 * Who this application says it is: version, the commit it was built from, who made it, where the
 * releases live.
 * <p>
 * An immutable record loaded once, rather than public static fields assigned at startup – those
 * work while startup is single-threaded and ordered, and break quietly the day something reads one
 * of them earlier.
 *
 * @param commit the whole hash of the commit this build was made from, or {@code -} when the build
 *               did not know it. The commit and not a build date: two builds of one commit are then
 *               the same jar, and a date said when somebody pressed build, not what was built.
 */
public record AppInfo(@NotNull String name, @NotNull String version, @NotNull String commit,
                      @NotNull String author, @NotNull String releasesUrl, @NotNull String repositoryUrl) {

    /** What {@link #commit} says when there is none to name. */
    static final String NO_COMMIT = "-";

    /** The terms WTF is under, for the About window; the text itself is LICENSE in the repository. */
    public static final String LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.html";

    private static final Logger log = LoggerFactory.getLogger(AppInfo.class);

    private static final AppInfo INSTANCE = load();

    public static @NotNull AppInfo get() {
        return INSTANCE;
    }

    /**
     * What the tile servers are told is asking: this name and version, and where to reach the
     * people behind it. Every download says so, OpenStreetMap's policy asks it, and a hard-coded
     * "0.1" in the engine was every version's.
     */
    public @NotNull String userAgent() {
        return net.trdlo.wtf.tiles.TileDownloader.userAgent(name, version);
    }

    /** The commit as a person reads it, the first seven characters, as git shows it. */
    public @NotNull String shortCommit() {
        return commit.length() > 7 ? commit.substring(0, 7) : commit;
    }

    private static @NotNull AppInfo load() {
        final Properties p = new Properties();
        try (final InputStream in = AppInfo.class.getResourceAsStream("/app.properties")) {
            if (in != null) {
                p.load(in);
            } else {
                log.warn("app.properties is not on the classpath; this is a development run");
            }
        } catch (final Exception ex) {
            log.warn("Could not read app.properties: {}", ex.toString());
        }
        return of(p);
    }

    /** The values of an {@code app.properties}, as the build filtered it. */
    static @NotNull AppInfo of(final @NotNull Properties p) {
        final String commit = p.getProperty("app.commit", "");
        return new AppInfo(
                p.getProperty("app.name", "WTF"),
                p.getProperty("app.version", "dev"),
                // Still the placeholder when nothing filled it in: the IDE's own build, or a build
                // with no git to ask
                commit.isBlank() || commit.startsWith("${") ? NO_COMMIT : commit,
                p.getProperty("app.author", "DickBoffin & Claude Code"),
                p.getProperty("app.releasesUrl", "https://github.com/DickBoffin/wtf/releases"),
                p.getProperty("app.repositoryUrl", "https://github.com/DickBoffin/wtf"));
    }
}
