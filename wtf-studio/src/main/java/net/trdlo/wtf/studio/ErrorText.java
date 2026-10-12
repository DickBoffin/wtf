package net.trdlo.wtf.studio;

import com.fasterxml.jackson.core.JacksonException;
import net.trdlo.wtf.compose.Composer;
import net.trdlo.wtf.studio.project.ProjectRefusal;
import net.trdlo.wtf.tiles.NothingThereException;
import net.trdlo.wtf.tiles.Refusal;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * What went wrong, said so that somebody can do something about it.
 * <p>
 * <b>Not one sentence per exception.</b> There are hundreds of exception types and most of them can
 * only reach this application in one or two ways. What a user can act on is much smaller: the file
 * is in use, the file is gone, the folder may not be written, the disk said no, the network is
 * down, the file is not a project, memory ran out – or one of WTF's own checks refused, which is a
 * {@link Refusal} from the engine or a {@link ProjectRefusal} from the editor and nothing else,
 * because those two are the only exceptions whose message is a reason for a person. Each of those
 * gets a headline and a sentence. Everything else is "WTF did not see this one coming", with the
 * details a bug report needs, which is honest and is exactly what should be sent.
 * <p>
 * The cause chain is searched, not just the exception itself: an {@code IOException} wrapped in a
 * {@code RuntimeException} on its way out of a task is still a full disk.
 */
public final class ErrorText {

    /** A headline, a sentence or two under it, and the technical details behind an expander. */
    public record Explained(@NotNull String headline, @NotNull String body, @NotNull String details) {
    }

    private ErrorText() {
    }

    public static @NotNull Explained explain(final @Nullable Throwable ex) {
        if (ex == null) {
            return new Explained(I18n.get("error.generic"), I18n.get("error.unexpected.body"), "");
        }
        final String details = details(ex);
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            final Explained e = known(t, details);
            if (e != null) {
                return e;
            }
        }
        return new Explained(I18n.get("error.generic"), I18n.get("error.unexpected.body"), details);
    }

    private static @Nullable Explained known(final @NotNull Throwable t, final @NotNull String details) {
        return switch (t) {
            case Composer.FileInUseException f -> new Explained(I18n.get("export.inUse.title"),
                    I18n.format("export.inUse.body", name(f.file())), details);
            case NoSuchFileException f -> new Explained(I18n.get("error.file.missing.title"),
                    I18n.format("error.file.missing", name(f.getFile())), details);
            case AccessDeniedException f -> new Explained(I18n.get("error.file.denied.title"),
                    I18n.format("error.file.denied", name(f.getFile())), details);
            // The system's own reason, which is in the user's language and usually exact: "There is
            // not enough space on the disk"
            case FileSystemException f -> new Explained(I18n.get("error.file.other.title"),
                    I18n.format("error.file.other", name(f.getFile()),
                            f.getReason() != null ? f.getReason() : String.valueOf(f.getMessage())), details);
            case UnknownHostException u -> network(details);
            case ConnectException c -> network(details);
            case SocketTimeoutException s -> network(details);
            case HttpTimeoutException h -> network(details);
            case JacksonException j -> new Explained(I18n.get("error.damaged.title"),
                    I18n.get("error.damaged"), details);
            // Not a fault: the provider answered, and the answer was "nothing here" everywhere –
            // for the view, for a provider's test, and for a print, whichever press found it out
            case NothingThereException n -> new Explained(I18n.get("download.nothing.title"),
                    I18n.format("download.nothing.body", n.provider().displayName(), I18n.integer(n.tiles())),
                    details);
            case OutOfMemoryError o -> new Explained(I18n.get("error.memory.title"),
                    I18n.get("error.memory"), details);
            // The engine's checks, said in the interface language rather than its English
            case Refusal r -> new Explained(I18n.get("error.refused.title"), EngineText.say(r), details);
            // The editor's own checks of a project, said from the bundle where they are thrown.
            // These two types and no other: an IllegalArgumentException with a message used to
            // count, and JTS's "Invalid number of points in LinearRing" and a JavaFX threading
            // error were shown as though WTF had refused something
            case ProjectRefusal p -> new Explained(I18n.get("error.refused.title"), p.getMessage(), details);
            default -> null;
        };
    }

    private static @NotNull Explained network(final @NotNull String details) {
        return new Explained(I18n.get("error.network.title"), I18n.get("error.network"), details);
    }

    private static @NotNull String name(final @Nullable Object file) {
        if (file == null) {
            return "?";
        }
        final Path p = file instanceof final Path path ? path : Path.of(file.toString());
        return p.getFileName() == null ? p.toString() : p.getFileName().toString();
    }

    /**
     * The version and the stack trace: what a bug report needs, in the order it needs them. The
     * whole commit hash, because a report is read against the source.
     */
    static @NotNull String details(final @NotNull Throwable ex) {
        final StringWriter sw = new StringWriter();
        final AppInfo info = AppInfo.get();
        sw.append(info.name()).append(' ').append(info.version()).append(", commit ").append(info.commit())
                .append(", Java ").append(System.getProperty("java.version"))
                .append(", ").append(System.getProperty("os.name")).append(System.lineSeparator())
                .append(System.lineSeparator());
        ex.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
