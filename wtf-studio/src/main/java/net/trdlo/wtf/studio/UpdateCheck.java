package net.trdlo.wtf.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Asks GitHub whether there is a newer release.
 * <p>
 * Three rules, all of them about not being rude:
 * <ul>
 *   <li><b>Off is a real setting.</b> {@link Prefs#CHECK_UPDATES} is honored before any socket is
 *       opened, not after.</li>
 *   <li><b>It never blocks anything.</b> It runs after the window is up, on its own thread, with
 *       a short timeout, and a failure is a debug line – an application that will not start
 *       because a release server is down is a worse application.</li>
 *   <li><b>At most once a day.</b> The last check is remembered, so opening the window eight
 *       times in an afternoon is one request.</li>
 * </ul>
 * Nothing is sent but the request itself: no identifier, no version, no telemetry.
 */
public final class UpdateCheck {

    private static final Logger log = LoggerFactory.getLogger(UpdateCheck.class);

    private static final String LAST_CHECK = "update.lastCheck";
    private static final String SKIPPED = "update.skip";

    private static final Duration INTERVAL = Duration.ofDays(1);
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    public record Release(@NotNull String version, @NotNull String url, @NotNull String notes) { }

    /** What a check learned. Not knowing is its own answer, and never "up to date". */
    public enum Status { NEWER, UP_TO_DATE, UNKNOWN }

    /** @param release the newer release, present exactly when the status is {@link Status#NEWER} */
    public record Result(@NotNull Status status, @Nullable Release release) {

        static final Result UP_TO_DATE = new Result(Status.UP_TO_DATE, null);
        static final Result UNKNOWN = new Result(Status.UNKNOWN, null);

        static @NotNull Result found(final @NotNull Release release) {
            return new Result(Status.NEWER, release);
        }

        public @NotNull Optional<Release> newer() {
            return Optional.ofNullable(release);
        }
    }

    private UpdateCheck() {
    }

    /**
     * Looks for a newer release, in the background.
     * <p>
     * No network, a release list that answered 404 or anything but a list, and a check that was
     * not made at all – switched off, or made in the last day – are all {@link Status#UNKNOWN}.
     * They are not the empty answer "up to date" is, or the About dialog's button would say
     * "this is the newest release" to somebody on a train.
     */
    public static @NotNull CompletableFuture<Result> check(final @NotNull Prefs prefs, final boolean force) {
        if (!force && !prefs.get(Prefs.CHECK_UPDATES)) {
            return CompletableFuture.completedFuture(Result.UNKNOWN);
        }
        if (!force && checkedRecently(prefs)) {
            return CompletableFuture.completedFuture(Result.UNKNOWN);
        }
        final AppInfo info = AppInfo.get();
        final String api = apiUrlFor(info.repositoryUrl());
        if (api == null) {
            log.debug("No repository URL to check against");
            return CompletableFuture.completedFuture(Result.UNKNOWN);
        }

        return CompletableFuture.supplyAsync(() -> {
            final Result result = ask(api, info.version());
            // Only a check that got an answer counts as the day's check: one made offline
            // would otherwise stop the next start, online, from asking
            if (result.status() != Status.UNKNOWN) {
                prefs.put(LAST_CHECK, Instant.now().toString());
            }
            return result;
        });
    }

    /** Remembers that this version was declined, so it is not offered on every start. */
    public static void skip(final @NotNull Prefs prefs, final @NotNull Release release) {
        prefs.put(SKIPPED, release.version());
        prefs.flush();
    }

    /** Whether this release was declined; the start-up offer respects that, the button does not. */
    public static boolean isSkipped(final @NotNull Prefs prefs, final @NotNull Release release) {
        return release.version().equals(prefs.get(SKIPPED));
    }

    private static boolean checkedRecently(final @NotNull Prefs prefs) {
        final String last = prefs.get(LAST_CHECK);
        if (last == null) {
            return false;
        }
        try {
            return Instant.parse(last).isAfter(Instant.now().minus(INTERVAL));
        } catch (final RuntimeException ex) {
            return false;
        }
    }

    /** {@code https://github.com/owner/repo} becomes the releases endpoint. */
    static @Nullable String apiUrlFor(final @Nullable String repositoryUrl) {
        if (repositoryUrl == null || !repositoryUrl.contains("github.com/")) {
            return null;
        }
        final String path = repositoryUrl.substring(repositoryUrl.indexOf("github.com/") + "github.com/".length())
                .replaceAll("\\.git$", "")
                .replaceAll("/+$", "");
        if (path.chars().filter(c -> c == '/').count() != 1) {
            return null;
        }
        return "https://api.github.com/repos/" + path + "/releases";
    }

    /**
     * The newest non-draft, non-prerelease tag. {@code /releases/latest} would do it in one call
     * but 404s on a repository that has never published one, which is a confusing first
     * experience for something this optional.
     */
    static @NotNull Result ask(final @NotNull String api, final @NotNull String running) {
        try (final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()) {

            final HttpRequest request = HttpRequest.newBuilder(URI.create(api + "?per_page=20"))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", AppInfo.get().name().replace(' ', '-'))
                    .GET()
                    .build();

            final HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            return read(response.statusCode(), response.body(), running);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Result.UNKNOWN;
        } catch (final Exception ex) {
            // Not an error: being offline is the normal state of a laptop on a train.
            log.debug("Update check did not complete: {}", ex.toString());
            return Result.UNKNOWN;
        }
    }

    /** What an answer from the release list says about the version running. */
    static @NotNull Result read(final int status, final @NotNull String body, final @NotNull String running) {
        if (status != 200) {
            log.debug("Release list returned HTTP {}", status);
            return Result.UNKNOWN;
        }
        final JsonNode array;
        try {
            array = new ObjectMapper().readTree(body);
        } catch (final java.io.IOException ex) {
            log.debug("Release list was not JSON: {}", ex.toString());
            return Result.UNKNOWN;
        }
        if (!array.isArray()) {
            return Result.UNKNOWN;
        }
        JsonNode best = null;
        for (final JsonNode r : array) {
            if (r.path("draft").asBoolean(false) || r.path("prerelease").asBoolean(false)) {
                continue;
            }
            if (best == null || isNewer(r.path("tag_name").asText(""),
                    best.path("tag_name").asText(""))) {
                best = r;
            }
        }
        if (best == null) {
            return Result.UP_TO_DATE;       // nothing released yet, so nothing newer
        }
        final Release newest = new Release(
                Changelog.baseVersion(best.path("tag_name").asText("")),
                best.path("html_url").asText(AppInfo.get().releasesUrl()),
                best.path("body").asText(""));
        if (!isNewer(newest.version(), running)) {
            log.debug("Up to date: running {}, newest is {}", running, newest.version());
            return Result.UP_TO_DATE;
        }
        return Result.found(newest);
    }

    /**
     * Numeric, segment by segment, so {@code 0.10.0} is newer than {@code 0.9.0} – which string
     * comparison gets backwards, and which is the bug every hand-written version check has.
     */
    static boolean isNewer(final @NotNull String candidate, final @NotNull String current) {
        final int[] a = segments(candidate);
        final int[] b = segments(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            final int x = i < a.length ? a[i] : 0;
            final int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return x > y;
            }
        }
        // Same numbers: a release beats the -SNAPSHOT of itself, and nothing beats a release.
        return isSnapshot(current) && !isSnapshot(candidate);
    }

    private static boolean isSnapshot(final @NotNull String version) {
        return version.toUpperCase(java.util.Locale.ROOT).contains("SNAPSHOT");
    }

    private static int[] segments(final @NotNull String version) {
        final String[] parts = Changelog.baseVersion(version).split("\\.");
        final int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].replaceAll("\\D.*$", ""));
            } catch (final NumberFormatException ex) {
                out[i] = 0;
            }
        }
        return out;
    }

    /** Only used to keep the comparator honest in tests. */
    static @NotNull Comparator<String> byVersion() {
        return (a, b) -> isNewer(a, b) ? 1 : isNewer(b, a) ? -1 : 0;
    }
}
