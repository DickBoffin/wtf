package net.trdlo.wtf.tiles;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Turns a stream of {@link ProgressSink#advance} calls into elapsed time, a rate and an ETA.
 * <p>
 * It lives here, in an engine module, so the GUI and the CLI cannot disagree about how long is
 * left, and so it can be unit-tested against a synthetic stream – which matters, because ETA
 * arithmetic is exactly the kind of code that stays wrong for months without anybody noticing.
 * <p>
 * The rules that make the answer usable rather than merely available:
 * <ul>
 *   <li><b>A rolling window, not a cumulative average.</b> A slow start or a stall in the middle
 *       stops poisoning the estimate for the rest of the hour.</li>
 *   <li><b>A median, not a mean.</b> Tile sizes vary by an order of magnitude between open sea and
 *       a city center; one 120 kB tile must not move the estimate.</li>
 *   <li><b>Silence until it means something.</b> No ETA before {@value #MIN_SAMPLE_MILLIS} ms and
 *       {@value #MIN_SAMPLE_UNITS} units have passed.</li>
 *   <li><b>Hysteresis.</b> The published estimate only moves when it has really changed, so it
 *       reads as a measurement rather than a guess.</li>
 *   <li><b>Now is a sample.</b> The window is measured up to the moment it is asked about, not
 *       up to the last unit done, so a stall shows as a stall: once nothing has arrived for a
 *       whole window the rate is zero and there is no estimate. Measured only at each unit, a
 *       stalled download would go on reporting the rate it had before, and the minutes it would
 *       take, for as long as it stood still.</li>
 * </ul>
 * Not thread-safe by itself; {@link #advance} is synchronized because the downloader calls it from
 * several threads.
 */
public final class ProgressModel {

    /** How far back the rate is measured. */
    public static final long WINDOW_MILLIS = 30_000;

    /** Nothing is claimed before this much time has passed. */
    public static final long MIN_SAMPLE_MILLIS = 5_000;

    /** ...nor before this many units are done. */
    public static final long MIN_SAMPLE_UNITS = 20;

    /** The published ETA moves only when it changes by this fraction... */
    private static final double HYSTERESIS_FRACTION = 0.05;

    /** ...or by this much, whichever is larger. */
    private static final long HYSTERESIS_MILLIS = 30_000;

    private record Sample(long atMillis, long done) { }

    private final java.util.function.LongSupplier clock;

    private String phaseName = "";
    private ProgressSink.Unit unit = ProgressSink.Unit.TILES;
    private long total = -1;
    private long done;
    private long phaseStartMillis;
    private final Deque<Sample> window = new ArrayDeque<>();

    private @Nullable Duration publishedEta;

    public ProgressModel() {
        this(System::currentTimeMillis);
    }

    /** For tests: a clock that does not require the test to actually wait thirty seconds. */
    public ProgressModel(final java.util.function.@NotNull LongSupplier clock) {
        this.clock = clock;
        this.phaseStartMillis = clock.getAsLong();
    }

    public synchronized void phase(final @NotNull String name, final long total, final @NotNull ProgressSink.Unit unit) {
        this.phaseName = name;
        this.total = total;
        this.unit = unit;
        this.done = 0;
        this.phaseStartMillis = clock.getAsLong();
        this.window.clear();
        this.publishedEta = null;
        this.window.add(new Sample(phaseStartMillis, 0));
    }

    public synchronized void advance(final long delta) {
        done += delta;
        final long now = clock.getAsLong();
        window.addLast(new Sample(now, done));
        while (window.size() > 2 && now - window.peekFirst().atMillis() > WINDOW_MILLIS) {
            window.removeFirst();
        }
    }

    public synchronized @NotNull String phaseName() {
        return phaseName;
    }

    public synchronized @NotNull ProgressSink.Unit unit() {
        return unit;
    }

    public synchronized long done() {
        return done;
    }

    public synchronized long total() {
        return total;
    }

    public synchronized boolean isDeterminate() {
        return total >= 0;
    }

    /** 0..1, or a negative number when the phase is indeterminate. */
    public synchronized double fraction() {
        if (total <= 0) {
            return total == 0 ? 1.0 : -1.0;
        }
        return Math.min(1.0, (double) done / total);
    }

    public synchronized @NotNull Duration elapsed() {
        return Duration.ofMillis(clock.getAsLong() - phaseStartMillis);
    }

    /**
     * Units per second over the rolling window up to now, a negative number when too little has
     * happened to say, or zero when nothing has arrived for a whole window. The median of the
     * per-interval rates, so one slow or fast burst does not dominate.
     */
    public synchronized double ratePerSecond() {
        final long now = clock.getAsLong();
        if (now - phaseStartMillis < MIN_SAMPLE_MILLIS || done < MIN_SAMPLE_UNITS || window.size() < 3) {
            return -1;
        }
        final List<Sample> s = new ArrayList<>(window);
        if (now > s.getLast().atMillis()) {
            s.add(new Sample(now, done));       // the time since the last unit, in which nothing came
        }
        while (s.size() > 2 && now - s.getFirst().atMillis() > WINDOW_MILLIS) {
            s.removeFirst();
        }
        final List<Double> rates = new ArrayList<>(s.size());
        for (int i = 1; i < s.size(); i++) {
            final long dt = s.get(i).atMillis() - s.get(i - 1).atMillis();
            if (dt <= 0) {
                continue;
            }
            rates.add((s.get(i).done() - s.get(i - 1).done()) * 1000.0 / dt);
        }
        if (rates.isEmpty()) {
            return -1;
        }
        rates.sort(Double::compare);
        final int mid = rates.size() / 2;
        final double median = rates.size() % 2 == 1
                ? rates.get(mid)
                : (rates.get(mid - 1) + rates.get(mid)) / 2.0;
        return Math.max(0, median);
    }

    /**
     * How much longer, or null when it cannot honestly be said yet. Hysteresis is applied here, so
     * a caller polling this every frame still gets a number that holds still.
     */
    public synchronized @Nullable Duration eta() {
        if (total < 0) {
            return null;
        }
        final long remaining = total - done;
        if (remaining <= 0) {
            publishedEta = Duration.ZERO;
            return publishedEta;
        }
        final double rate = ratePerSecond();
        if (rate < 0) {
            return publishedEta;   // keep the last honest answer rather than flickering to nothing
        }
        if (rate == 0) {
            publishedEta = null;   // stalled: the last answer is no longer honest
            return null;
        }
        final Duration fresh = Duration.ofMillis(Math.round(remaining / rate * 1000.0));
        if (publishedEta == null) {
            publishedEta = fresh;
            return publishedEta;
        }
        final long diff = Math.abs(fresh.toMillis() - publishedEta.toMillis());
        if (diff > Math.max(HYSTERESIS_MILLIS, publishedEta.toMillis() * HYSTERESIS_FRACTION)) {
            publishedEta = fresh;
        }
        return publishedEta;
    }

    /**
     * The ETA as a human would say it. Deliberately vague at the top end: a countdown ticking
     * {@code 01:47:23} claims a precision the estimate does not have, and makes a three-second
     * wobble look like a fault.
     */
    public static @NotNull String describe(final @Nullable Duration d) {
        final Rough r = rough(d);
        return switch (r.span()) {
            case ESTIMATING -> "estimating…";
            case UNDER_A_MINUTE -> "less than a minute";
            case A_MINUTE -> "about a minute";
            case MINUTES -> "about " + (long) r.count() + " minutes";
            case AN_HOUR -> "about an hour";
            case AN_HOUR_AND_A_HALF -> "about an hour and a half";
            case HOURS -> "about " + (r.isWhole() ? Long.toString((long) r.count())
                    : String.format(Locale.ROOT, "%.1f", r.count())) + " hours";
        };
    }

    /** The steps an ETA is said in; see {@link #rough}. */
    public enum Span { ESTIMATING, UNDER_A_MINUTE, A_MINUTE, MINUTES, AN_HOUR, AN_HOUR_AND_A_HALF, HOURS }

    /**
     * An ETA as it is said, in no language.
     *
     * @param count the minutes for {@code MINUTES}, the hours for {@code HOURS} – in halves below
     *              ten, whole from there – and zero for the rest
     */
    public record Rough(@NotNull Span span, double count) {
        public boolean isWhole() {
            return count == Math.rint(count);
        }
    }

    /**
     * Which step an ETA falls in. One place for the arithmetic, so that the console and the window,
     * which say it in different languages, say the same thing.
     * <p>
     * Every step is rounded before it is chosen: 59 minutes and 40 seconds rounds to sixty minutes,
     * which is said as an hour, not as "about 60 minutes"; an hour and 50 minutes rounds to two
     * hours, not to an hour and a half; nine hours and 50 minutes to ten, not to "9.5". The
     * hour-and-a-half step is chosen from rounded half hours, not by integer division, which would
     * put an hour and 20 minutes at "an hour" and an hour and 50 at "an hour and a half".
     */
    public static @NotNull Rough rough(final @Nullable Duration d) {
        if (d == null) {
            return new Rough(Span.ESTIMATING, 0);
        }
        final long s = d.getSeconds();
        if (s < 45) {
            return new Rough(Span.UNDER_A_MINUTE, 0);
        }
        if (s < 90) {
            return new Rough(Span.A_MINUTE, 0);
        }
        final long minutes = Math.round(s / 60.0);
        if (minutes < 60) {
            return new Rough(Span.MINUTES, minutes);
        }
        final long halfHours = Math.round(s / 1800.0);
        if (halfHours <= 2) {
            return new Rough(Span.AN_HOUR, 0);
        }
        if (halfHours == 3) {
            return new Rough(Span.AN_HOUR_AND_A_HALF, 0);
        }
        if (halfHours < 20) {
            return new Rough(Span.HOURS, halfHours / 2.0);
        }
        return new Rough(Span.HOURS, Math.round(s / 3600.0));
    }

    /** One line for a console or a status bar. */
    public synchronized @NotNull String describe() {
        final StringBuilder sb = new StringBuilder(phaseName);
        if (total >= 0) {
            sb.append(": ").append(String.format(Locale.ROOT, "%,d of %,d", done, total));
            sb.append(String.format(Locale.ROOT, " (%.0f %%)", fraction() * 100));
        } else {
            sb.append(": ").append(String.format(Locale.ROOT, "%,d", done));
        }
        final double rate = ratePerSecond();
        if (rate > 0) {
            sb.append(unit == ProgressSink.Unit.BYTES
                    ? String.format(Locale.ROOT, ", %.1f MB/s", rate / 1_048_576)
                    : String.format(Locale.ROOT, ", %.1f/s", rate));
            sb.append(", ").append(describe(eta())).append(" left");
        }
        return sb.toString();
    }
}
