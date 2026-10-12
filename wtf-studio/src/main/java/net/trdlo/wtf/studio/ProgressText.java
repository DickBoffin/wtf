package net.trdlo.wtf.studio;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import net.trdlo.wtf.compose.Composer;
import net.trdlo.wtf.compose.PdfExporter;
import net.trdlo.wtf.compose.PdfTileImages;
import net.trdlo.wtf.compose.RasterExporter;
import net.trdlo.wtf.tiles.ProgressModel;
import net.trdlo.wtf.tiles.ProgressSink;
import net.trdlo.wtf.tiles.TileDownloader;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The progress line and the reports after it, in the interface language.
 * <p>
 * The engine has no language by construction – it is used from a command line as well – so it
 * reports in English, and its phrases are constants beside the code that uses them. This turns
 * the phrases it is known to use back into their parts and says them from the bundle; a phrase it
 * does not know is shown as the engine said it, which is English but never wrong.
 */
public final class ProgressText {

    /** An engine phrase, as a pattern over its format string, and the key that says it here. */
    private record Phrase(@NotNull Pattern pattern, @NotNull String key) {

        static @NotNull Phrase of(final @NotNull String format, final @NotNull String key) {
            // %d and %s are the only conversions the phrases use; everything else is literal
            final String regex = Pattern.quote(format)
                    .replace("%d", "\\E(\\d+)\\Q")
                    .replace("%s", "\\E(.+)\\Q");
            return new Phrase(Pattern.compile(regex), key);
        }
    }

    private static final List<Phrase> PHRASES = List.of(
            Phrase.of(TileDownloader.PHASE_DOWNLOADING, "progress.downloading"),
            Phrase.of(TileDownloader.FINISHED_ALL_CACHED, "progress.allCached"),
            Phrase.of(PdfExporter.PHASE_PLACING, "progress.placing"),
            Phrase.of(PdfExporter.PHASE_WRITING, "progress.writing"),
            Phrase.of(RasterExporter.PHASE_RASTERIZING, "progress.rasterizing"),
            // Why no request could be made for a tile, which a download's failure says
            Phrase.of(TileDownloader.UNMADE_ADDRESS, "download.unmade.address"),
            Phrase.of(TileDownloader.UNMADE_KEY, "download.unmade.key"));

    /**
     * The downloader's closing line, {@link TileDownloader#FINISHED_REPORT}. A download's progress
     * ends on it, and the export step's label kept it – in English – after a download that did not
     * go on into an export. Its counts are grouped, one number has a decimal and the time is
     * {@code 42 s} or {@code 3 min 5 s}, which is more than {@link Phrase} reads, so it is taken
     * apart here and said as {@link #report(TileDownloader.Report)} says a report – with how it
     * ends when the server's rate limit ended the job, {@link TileDownloader#FINISHED_RATE_LIMITED}
     * or, with the time the server named, {@link TileDownloader#FINISHED_RATE_LIMITED_UNTIL}.
     */
    private static final Pattern DOWNLOAD_REPORT = Pattern.compile(Pattern.quote(TileDownloader.FINISHED_REPORT)
            .replace("%,d", "\\E([\\d,]+)\\Q")
            .replace("%,.1f", "\\E([\\d,]+\\.\\d)\\Q")
            .replace("%s", "\\E(?:(\\d+) min )?(\\d+) s\\Q")
            + "(?:(" + Pattern.quote(TileDownloader.FINISHED_RATE_LIMITED) + ")|"
            + Pattern.quote(TileDownloader.FINISHED_RATE_LIMITED_UNTIL).replace("%s", "\\E([^,\\s]+)\\Q") + ")?");

    private ProgressText() {
    }

    /**
     * A phase name, a closing line or why no request could be made for a tile, from the engine,
     * said in the interface language.
     */
    public static @NotNull String phrase(final @NotNull String engine) {
        final Matcher report = DOWNLOAD_REPORT.matcher(engine);
        if (report.matches()) {
            final long seconds = (report.group(6) == null ? 0 : 60 * Long.parseLong(report.group(6)))
                    + Long.parseLong(report.group(7));
            final TileDownloader.RateLimited limited;
            try {
                limited = report.group(9) != null ? new TileDownloader.RateLimited(Instant.parse(report.group(9)))
                        : report.group(8) != null ? new TileDownloader.RateLimited(null) : null;
            } catch (final DateTimeParseException notAnInstant) {
                return engine;
            }
            return report(new TileDownloader.Report(count(report.group(1)), count(report.group(2)),
                    count(report.group(3)), count(report.group(4)),
                    Math.round(Double.parseDouble(report.group(5).replace(",", "")) * 1_048_576),
                    Duration.ofSeconds(seconds), List.of(), limited, null));
        }
        for (final Phrase p : PHRASES) {
            final Matcher m = p.pattern().matcher(engine);
            if (m.matches()) {
                final Object[] args = new Object[m.groupCount()];
                for (int i = 0; i < args.length; i++) {
                    final String g = m.group(i + 1);
                    args[i] = g.chars().allMatch(Character::isDigit) ? I18n.integer(Long.parseLong(g)) : g;
                }
                return I18n.format(p.key(), args);
            }
        }
        return engine;
    }

    private static int count(final @NotNull String grouped) {
        return Integer.parseInt(grouped.replace(",", ""));
    }

    /** One line for the progress label: the phase, how far, how fast, how long. */
    public static @NotNull String line(final @NotNull ProgressModel model) {
        final String phase = phrase(model.phaseName());
        final String far = model.total() >= 0
                ? I18n.format("progress.ofTotal", I18n.integer(model.done()), I18n.integer(model.total()),
                        I18n.number(model.fraction() * 100, 0))
                : I18n.integer(model.done());
        final double rate = model.ratePerSecond();
        if (rate <= 0) {
            return I18n.format("progress.line", phase, far);
        }
        final String speed = model.unit() == ProgressSink.Unit.BYTES
                ? I18n.format("progress.rate.bytes", I18n.number(rate / 1_048_576, 1))
                : I18n.format("progress.rate.items", I18n.number(rate, 1));
        return I18n.format("progress.line.eta", phase, far, speed, eta(model.eta()));
    }

    /**
     * How much longer, as a person would say it. Vague at the top end on purpose, for the reason
     * {@link ProgressModel#describe(Duration)} gives; the steps are {@link ProgressModel#rough}'s,
     * so the window and the console cannot round differently.
     */
    public static @NotNull String eta(final @Nullable Duration d) {
        final ProgressModel.Rough r = ProgressModel.rough(d);
        return switch (r.span()) {
            case ESTIMATING -> I18n.get("progress.eta.estimating");
            case UNDER_A_MINUTE -> I18n.get("progress.eta.underMinute");
            case A_MINUTE -> I18n.get("progress.eta.minute");
            case MINUTES -> I18n.format("progress.eta.minutes", I18n.integer((long) r.count()));
            case AN_HOUR -> I18n.get("progress.eta.hour");
            case AN_HOUR_AND_A_HALF -> I18n.get("progress.eta.hourAndHalf");
            case HOURS -> I18n.format("progress.eta.hours",
                    r.isWhole() ? I18n.integer((long) r.count()) : I18n.number(r.count(), 1));
        };
    }

    /** How long something took: "42 s", "3 min 5 s". */
    public static @NotNull String took(final @NotNull Duration d) {
        final long s = d.getSeconds();
        return s < 60 ? I18n.format("progress.took.seconds", s)
                : I18n.format("progress.took.minutes", s / 60, s % 60);
    }

    /**
     * A download's report: its counts, and – when the server's rate limit outlasted the job and
     * what it had not fetched failed – that it did, and until when if the server said.
     */
    public static @NotNull String report(final @NotNull TileDownloader.Report r) {
        final String counts = I18n.format("progress.report.download", I18n.integer(r.downloaded()),
                I18n.integer(r.fromCache()), I18n.integer(r.empty()), I18n.integer(r.failed()),
                I18n.number(r.bytes() / 1_048_576.0, 1), took(r.elapsed()));
        final TileDownloader.RateLimited limited = r.rateLimited();
        if (limited == null) {
            return counts;
        }
        // A sentence after the counts, not a line of its own: the labels that show it are one line
        final Instant until = limited.until();
        return counts + " " + (until == null ? I18n.get("progress.report.rateLimited")
                : I18n.format("progress.report.rateLimited.until", I18n.dateTime(until)));
    }

    /**
     * The export's closing line: what it wrote – how far a canceled one got is counted in files,
     * since a sheet in two formats is two – and, on a line of its own, how many of its PDFs' images
     * went in decoded rather than copied and why, when any did (compose-module.md §6). An export
     * that copied every tile says nothing more.
     */
    public static @NotNull String report(final @NotNull Composer.ExportReport r) {
        final String written = r.canceled()
                ? I18n.format("progress.report.export.canceled", I18n.integer(r.files().size()),
                        I18n.integer(r.planned()), I18n.integer(r.tilesPlaced()),
                        I18n.number(r.bytes() / 1_048_576.0, 1), took(r.elapsed()))
                : I18n.format("progress.report.export", I18n.integer(r.files().size()),
                        I18n.integer(r.sheets()), I18n.integer(r.tilesPlaced()),
                        I18n.number(r.bytes() / 1_048_576.0, 1), took(r.elapsed()));
        final PdfTileImages.Tally images = r.images();
        if (images.decodedCount() == 0) {
            return written;
        }
        final String reasons = images.decoded().entrySet().stream()
                .map(e -> reason(e.getKey(), e.getValue()))
                .collect(Collectors.joining(", "));
        return written + "\n" + I18n.format("progress.report.decoded", I18n.integer(images.decodedCount()),
                I18n.integer(images.total()), reasons);
    }

    /**
     * One reason and its count, a reason from the treatment named as the detail step names its
     * control – color, not saturation. The switch has no default, so a reason added to the engine
     * without a sentence here does not compile.
     */
    private static @NotNull String reason(final PdfTileImages.@NotNull Fallback fallback, final int images) {
        final String n = I18n.integer(images);
        return switch (fallback) {
            case SATURATION -> I18n.format("progress.report.decoded.saturation", n);
            case CONTRAST -> I18n.format("progress.report.decoded.contrast", n);
            case ALPHA_CHANNEL -> I18n.format("progress.report.decoded.alphaChannel", n);
            case FORMAT -> I18n.format("progress.report.decoded.format", n);
        };
    }
}
