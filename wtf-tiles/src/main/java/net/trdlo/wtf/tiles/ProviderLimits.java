package net.trdlo.wtf.tiles;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * What each provider allows every downloader of the process together: how many requests it takes
 * at once, and after a 429 the quiet it asked for, one request at a time and a budget of refusals.
 * <p>
 * A limit is the server's word to everybody who asks it, not to one job. Held per job, it would
 * not hold, because a job can be as small as one tile: the 1:1 preview fetches a tile at a time
 * from each of its four decoders, each a job of its own beside the view's download, so
 * OpenStreetMap would be asked four tiles at once where it allows two, and a 429 would quiet only
 * the job it answered.
 * {@link TileDownloader}'s public constructor hands every downloader the process's own, so the
 * places that build one need not agree on anything.
 * <p>
 * A provider is its id and its address. One edited without moving – another name, other
 * languages, the viewing-only flag, which each request reads from its own job's definition – is
 * the same server and keeps its limit; one moved to another address is not held by the quiet the
 * old one asked for. Nothing here is forgotten but what passes by itself: a process asks a few
 * dozen providers at most.
 */
final class ProviderLimits {

    private static final Logger log = LoggerFactory.getLogger(ProviderLimits.class);

    /**
     * How many 429s the jobs asking a provider together meet before its limit is taken for one
     * that lasts, whichever tiles they answered.
     */
    static final int MAX_REFUSALS = 5;

    private final Duration firstBackoff;
    private final Duration maxWait;
    private final ConcurrentMap<Address, Limit> limits = new ConcurrentHashMap<>();

    /** A provider as its server knows it: its id, and the address its template makes. */
    private record Address(@NotNull String providerId, @NotNull String template) {
    }

    /**
     * @param firstBackoff the wait after the first refusal that names no time, doubling with each
     *                     one after it; a tile's retries after a 5xx wait the same
     * @param maxWait      the longest wait between two attempts; a limit that asks for longer
     *                     outlasts what a job waits
     */
    ProviderLimits(final @NotNull Duration firstBackoff, final @NotNull Duration maxWait) {
        this.firstBackoff = firstBackoff;
        this.maxWait = maxWait;
    }

    @NotNull Duration firstBackoff() {
        return firstBackoff;
    }

    @NotNull Duration maxWait() {
        return maxWait;
    }

    /** The provider's limit: the same object for every job that asks it at this address. */
    @NotNull Limit of(final @NotNull Provider provider) {
        return limits.computeIfAbsent(new Address(provider.id(), provider.urlTemplate()),
                a -> new Limit(a.providerId()));
    }

    /**
     * One provider's limit, as every job asking it meets it: one monitor over the requests in
     * flight, the quiet and the refusals, so that no request is let out between a refusal and its
     * counting.
     * <p>
     * The quiet is a time, and passes by itself. What a 429 starts besides it – one request at a
     * time, the count of refusals with the backoff that doubles with it, and the verdict that the
     * limit lasts – belongs to the jobs that are asking the provider together, and begins afresh
     * when a job starts with none asking and the quiet over. So the preview's one-tile jobs, which
     * overlap, meet one budget; a download canceled and started again within the quiet is not
     * given five refusals more; a job started while the quiet of a limit that lasts runs asks
     * nothing; and a 429 in the morning does not slow the afternoon.
     */
    final class Limit {

        private final String providerId;

        /** The jobs asking the provider now. */
        private int jobs;
        /** The requests sent and not yet taken in, whichever job sent them. */
        private int inFlight;
        /** The {@link System#nanoTime()} before which nothing is asked, because a 429 said so. */
        private long quietUntil = System.nanoTime();
        /** When the quiet ends, if the server named the time: its Retry-After gave the quiet. */
        private @Nullable Instant namedUntil;

        /** Since a 429 to the jobs asking now: one request at a time. */
        private boolean throttled;
        /** The limit outlasts what a job waits: the jobs asking now ask nothing more. */
        private boolean outlasted;
        /** Whether a 429 has come back to them yet, and {@link System#nanoTime()} when the last did. */
        private boolean refusedBefore;
        private long lastAt;
        /** Their refusals, but for the same one said again, and the backoff that doubles with them. */
        private TileDownloader.Budget refusals = newBudget();
        /** The backoff the last refusal that spent was given, which the same refusal again reuses. */
        private Duration lastBackoff = Duration.ZERO;

        private Limit(final @NotNull String providerId) {
            this.providerId = providerId;
        }

        private @NotNull TileDownloader.Budget newBudget() {
            return new TileDownloader.Budget(MAX_REFUSALS, firstBackoff, maxWait);
        }

        /**
         * A job begins asking. With none asking and the quiet over, whatever a 429 started has
         * ended with them, and the limit begins afresh.
         */
        synchronized void join() {
            if (jobs == 0 && quietUntil - System.nanoTime() <= 0) {
                throttled = false;
                outlasted = false;
                refusedBefore = false;
                refusals = newBudget();
                lastBackoff = Duration.ZERO;
            }
            jobs++;
        }

        /** A job is done asking: every request it sent has been taken in. */
        synchronized void leave() {
            jobs--;
        }

        /**
         * Waits until the provider may be asked – its quiet over, and room for one more request:
         * one in all while a 429 holds, {@code connections} otherwise – and takes it. Looks every
         * {@value TileDownloader#STOP_CHECK_MILLIS} ms whether the job still wants it, and once
         * more with it in hand: the answer that made the room may have been the one that stopped
         * the job, and a request sent now would slip out after the refusal it follows.
         *
         * @param connections how many requests the provider takes at once from the whole process,
         *                    as the asking job's definition of it says
         * @return false when the job was stopped meanwhile, and nothing was taken
         */
        boolean enter(final int connections, final @NotNull BooleanSupplier stopped) {
            try {
                while (true) {
                    // Outside the monitor: the job's answer is its caller's code as well
                    if (stopped.getAsBoolean()) {
                        return false;
                    }
                    synchronized (this) {
                        final long quiet = quietUntil - System.nanoTime();
                        if (quiet <= 0 && inFlight < (throttled ? 1 : connections)) {
                            inFlight++;
                            break;
                        }
                        wait(quiet > 0
                                ? Math.min(TileDownloader.STOP_CHECK_MILLIS, TimeUnit.NANOSECONDS.toMillis(quiet) + 1)
                                : TileDownloader.STOP_CHECK_MILLIS);
                    }
                }
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (stopped.getAsBoolean()) {
                exit();
                return false;
            }
            return true;
        }

        /** A request's answer is taken in – counted, if it was a refusal – and its room is free. */
        synchronized void exit() {
            inFlight--;
            notifyAll();
        }

        /**
         * Counts a 429, received now, to a request sent at {@code sentAt}, on the same clock, and
         * quiets the provider for the time the server asked for or the backoff: every job asking
         * it waits, and from then on one request is in flight at a time.
         * <p>
         * A 429 to a request sent before the last 429 came back is the same refusal said again –
         * the requests that went together before anybody knew of the limit – and spends nothing,
         * nor doubles the backoff; only a request sent after the last 429 arrived can be refused
         * anew. So a burst limit that refuses a first round of six requests at once is waited out,
         * as one refusal, and a limit that lasts ends the jobs asking at its fifth – as it does
         * when the server asks for longer than a job waits.
         *
         * @param asked the server's own {@code Retry-After}, if it gave one
         * @return how long nothing is asked, or null when the limit outlasts what a job waits
         */
        @Nullable Duration refused(final long sentAt, final @NotNull Optional<Duration> asked) {
            final boolean verdict;
            synchronized (this) {
                throttled = true;
                final boolean again = refusedBefore && sentAt - lastAt < 0;
                final long now = System.nanoTime();
                if (!refusedBefore || now - lastAt > 0) {
                    lastAt = now;
                }
                refusedBefore = true;
                boolean spent = false;
                final Duration quiet;
                if (again) {
                    quiet = asked.orElse(lastBackoff);
                } else {
                    spent = !refusals.spend();
                    if (asked.isPresent()) {
                        quiet = asked.get();
                    } else {
                        lastBackoff = refusals.backoff();
                        quiet = lastBackoff;
                    }
                }
                // The fifth refusal quiets the provider too, so that a job started after it does
                // not ask straight away what the jobs before it were refused
                final long end = now + quiet.toNanos();
                if (end - quietUntil > 0) {
                    quietUntil = end;
                    namedUntil = asked.isPresent() ? Instant.now().plus(quiet) : null;
                }
                // Asked to wait longer than a job waits, or refused a fifth time: the limit is not
                // a burst, and every tile asked for now would be refused too
                verdict = !outlasted && (spent || quiet.compareTo(maxWait) > 0);
                outlasted |= verdict;
                if (!outlasted) {
                    return quiet;
                }
            }
            if (verdict) {
                log.warn("{} is still rate limiting ({}), longer than a job waits; nothing more is asked of it,"
                                + " and what is left fails", providerId,
                        asked.map(d -> "asked to wait " + d.toSeconds() + " s").orElse(MAX_REFUSALS + " times over"));
            }
            return null;
        }

        /** Whether a 429 has come to the jobs asking now, so that one request is in flight at a time. */
        synchronized boolean throttled() {
            return throttled;
        }

        /** Whether the limit outlasts what a job waits, so that the jobs asking now ask nothing more. */
        synchronized boolean outlasted() {
            return outlasted;
        }

        /** When the provider's quiet ends, if the server named the time; null if it did not. */
        synchronized @Nullable Instant namedUntil() {
            return namedUntil;
        }
    }
}
