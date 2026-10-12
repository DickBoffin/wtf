package net.trdlo.wtf.studio;

import ch.qos.logback.core.PropertyDefinerBase;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Where the log file goes, for {@code logback.xml} and for the error dialog that tells a user where
 * to find it – one answer for both, so the dialog never points at a folder the log is not in.
 * <p>
 * The platform's own place for it, like the tile cache: {@code %LOCALAPPDATA%\WTF\logs} on Windows,
 * {@code ~/Library/Logs/WTF} on macOS, {@code ~/.cache/wtf/logs} elsewhere. {@code -Dwtf.log.dir}
 * overrides it.
 * <p>
 * logback reads it as {@code <define name="LOG_DIR" class="net.trdlo.wtf.studio.LogDir"/>}, which
 * runs before the first line is logged – a system property set in {@code main} would come too late
 * for the classes whose loggers are created when they load.
 */
public final class LogDir extends PropertyDefinerBase {

    @Override
    public String getPropertyValue() {
        return path().toString();
    }

    public static @NotNull Path path() {
        final String configured = System.getProperty("wtf.log.dir");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        final String home = System.getProperty("user.home", ".");
        if (os.contains("win")) {
            final String local = System.getenv("LOCALAPPDATA");
            final Path base = local != null && !local.isBlank() ? Path.of(local) : Path.of(home, "AppData", "Local");
            return base.resolve("WTF").resolve("logs");
        }
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Logs", "WTF");
        }
        final String xdg = System.getenv("XDG_CACHE_HOME");
        final Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(home, ".cache");
        return base.resolve("wtf").resolve("logs");
    }

    /** The file itself, for the error dialog. */
    public static @NotNull Path file() {
        return path().resolve("wtf.log");
    }
}
