package net.trdlo.wtf.studio;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The change log, read from the one in the repository rather than retyped into a dialog.
 * <p>
 * {@code CHANGELOG.md} is copied into the jar at build time, so there is exactly one copy and it
 * is the one people edit. A second list of what changed, kept in Java, is a list that will be
 * wrong by the second release.
 */
public final class Changelog {

    private static final Logger log = LoggerFactory.getLogger(Changelog.class);

    private static final String RESOURCE = "/CHANGELOG.md";

    /** {@code ## 0.2.0 - 2026-10-01}, with the date optional. */
    private static final Pattern HEADING =
            Pattern.compile("^##\\s+v?([0-9][^\\s]*)\\s*(?:[-–]\\s*(.*))?$");

    public record Release(@NotNull String version, @Nullable String date, @NotNull String body) {

        public @NotNull String heading() {
            return date == null || date.isBlank() ? version : version + " – " + date;
        }
    }

    private static @Nullable List<Release> cached;

    private Changelog() {
    }

    /**
     * The file itself, for the renderer.
     * <p>
     * {@link #releases()} splits it into versions, which is what the update check needs; the
     * About dialog wants the Markdown whole so that {@link Markdown} can lay out its headings
     * and lists as written.
     */
    public static @NotNull String source() {
        return read();
    }

    /** Newest first, as the file is written. Empty when the resource is missing or unparsable. */
    public static @NotNull List<Release> releases() {
        if (cached == null) {
            cached = parse(read());
        }
        return cached;
    }

    public static @Nullable Release latest() {
        final List<Release> all = releases();
        return all.isEmpty() ? null : all.getFirst();
    }

    /** The entry for a version, matched loosely so {@code 0.1.0-SNAPSHOT} finds {@code 0.1.0}. */
    public static @Nullable Release forVersion(final @NotNull String version) {
        final String wanted = baseVersion(version);
        return releases().stream()
                .filter(r -> baseVersion(r.version()).equals(wanted))
                .findFirst()
                .orElse(null);
    }

    /** Everything up to the first qualifier: {@code 0.1.0-SNAPSHOT} and {@code v0.1.0} both give {@code 0.1.0}. */
    public static @NotNull String baseVersion(final @NotNull String version) {
        final String v = version.startsWith("v") || version.startsWith("V")
                ? version.substring(1) : version;
        final int dash = v.indexOf('-');
        return dash < 0 ? v : v.substring(0, dash);
    }

    private static @NotNull String read() {
        try (final InputStream in = Changelog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                log.warn("{} is not on the classpath; About will have nothing to show", RESOURCE);
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException ex) {
            log.warn("Could not read {}", RESOURCE, ex);
            return "";
        }
    }

    static @NotNull List<Release> parse(final @NotNull String markdown) {
        final List<Release> out = new ArrayList<>();
        String version = null;
        String date = null;
        StringBuilder body = new StringBuilder();

        for (final String line : markdown.split("\r?\n")) {
            final Matcher m = HEADING.matcher(line.strip());
            if (!m.matches()) {
                if (version != null) {
                    body.append(line).append(System.lineSeparator());
                }
                continue;
            }
            if (version != null) {
                out.add(new Release(version, date, body.toString().strip()));
            }
            version = m.group(1);
            date = m.group(2) == null || m.group(2).isBlank() ? null : m.group(2).strip();
            body = new StringBuilder();
        }
        if (version != null) {
            out.add(new Release(version, date, body.toString().strip()));
        }
        return List.copyOf(out);
    }
}
