package net.trdlo.wtf.tiles;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ETA arithmetic is exactly the kind of code that stays wrong for months without anybody noticing,
 * which is why it lives in an engine module and takes a clock it can be lied to about – no test
 * here waits thirty real seconds.
 */
class ProgressModelTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final ProgressModel model = new ProgressModel(now::get);

    private void tick(final long millis) {
        now.addAndGet(millis);
    }

    @Test
    @DisplayName("says nothing until there is enough evidence to say it")
    void silentAtFirst() {
        model.phase("Downloading", 1000, ProgressSink.Unit.TILES);
        assertNull(model.eta(), "nothing after zero work");
        assertEquals("estimating…", ProgressModel.describe(model.eta()));

        for (int i = 0; i < 5; i++) {      // too few units and too little time
            tick(100);
            model.advance(1);
        }
        assertTrue(model.ratePerSecond() < 0, "5 units in half a second is not evidence");
        assertNull(model.eta());
    }

    @Test
    @DisplayName("a steady rate produces the obvious estimate")
    void steadyRate() {
        model.phase("Downloading", 1000, ProgressSink.Unit.TILES);
        for (int i = 0; i < 100; i++) {    // 10 per second for 10 seconds
            tick(100);
            model.advance(1);
        }
        assertEquals(10.0, model.ratePerSecond(), 0.5);
        final Duration eta = model.eta();
        assertNotNull(eta);
        assertEquals(90, eta.getSeconds(), 5, "900 left at 10 per second");
        assertEquals(0.1, model.fraction(), 1e-9);
    }

    @Test
    @DisplayName("one enormous outlier does not move the estimate, because the rate is a median")
    void medianResistsOutliers() {
        model.phase("Downloading", 10_000, ProgressSink.Unit.BYTES);
        for (int i = 0; i < 60; i++) {
            tick(100);
            model.advance(10);             // a steady 100 per second
        }
        final double steady = model.ratePerSecond();

        tick(100);
        model.advance(5000);               // one absurd burst
        final double afterBurst = model.ratePerSecond();

        assertEquals(steady, afterBurst, steady * 0.25,
                "a mean would have roughly doubled here; a median barely moves");
    }

    @Test
    @DisplayName("the published estimate holds still unless it has really changed")
    void hysteresis() {
        model.phase("Downloading", 100_000, ProgressSink.Unit.TILES);
        for (int i = 0; i < 100; i++) {
            tick(100);
            model.advance(10);
        }
        final Duration first = model.eta();
        assertNotNull(first);

        for (int i = 0; i < 5; i++) {      // carry on at very nearly the same rate
            tick(100);
            model.advance(10);
        }
        assertEquals(first, model.eta(), "a tiny change must not republish the estimate");
    }

    @Test
    @DisplayName("finishing reports zero, not a negative remainder")
    void finishing() {
        model.phase("Writing", 100, ProgressSink.Unit.SHEETS);
        tick(10_000);
        model.advance(120);                // overshoot, as a byte-based estimate can
        assertEquals(Duration.ZERO, model.eta());
        assertEquals(1.0, model.fraction(), 1e-9, "fraction is capped at 1");
    }

    @Test
    @DisplayName("an indeterminate phase has no fraction and no ETA")
    void indeterminate() {
        model.phase("Scanning the cache", -1, ProgressSink.Unit.TILES);
        tick(10_000);
        model.advance(500);
        assertFalse(model.isDeterminate());
        assertTrue(model.fraction() < 0);
        assertNull(model.eta());
        assertTrue(model.describe().contains("500"));
    }

    @Test
    @DisplayName("durations are described the way a person would say them")
    void describe() {
        assertEquals("estimating…", ProgressModel.describe(null));
        assertEquals("less than a minute", ProgressModel.describe(Duration.ofSeconds(30)));
        assertEquals("about a minute", ProgressModel.describe(Duration.ofSeconds(60)));
        assertEquals("about 15 minutes", ProgressModel.describe(Duration.ofMinutes(15)));
        assertEquals("about 2.5 hours", ProgressModel.describe(Duration.ofMinutes(150)));
        assertEquals("about 14 hours", ProgressModel.describe(Duration.ofHours(14)));
        // never a ticking countdown: that claims a precision the estimate does not have
        assertFalse(ProgressModel.describe(Duration.ofSeconds(6423)).matches(".*\\d+:\\d+.*"));
    }

    @Test
    @DisplayName("a new phase forgets the previous one's rate")
    void phaseResets() {
        model.phase("Placing tiles", 100, ProgressSink.Unit.TILES);
        for (int i = 0; i < 100; i++) {
            tick(10);
            model.advance(1);
        }
        model.phase("Writing the document", 1_000_000, ProgressSink.Unit.BYTES);
        assertEquals(0, model.done());
        assertNull(model.eta(), "the tile rate says nothing about the byte rate");
    }

    @Test
    @DisplayName("a stall is a stall: once nothing has come for a whole window there is no rate and no estimate")
    void aStallIsAStall() {
        model.phase("Downloading", 1000, ProgressSink.Unit.TILES);
        for (int i = 0; i < 100; i++) {    // 10 per second for 10 seconds
            tick(100);
            model.advance(1);
        }
        assertNotNull(model.eta());

        tick(ProgressModel.WINDOW_MILLIS / 2);
        assertTrue(model.ratePerSecond() > 0, "half a window of silence is outvoted by the rest of it");

        tick(ProgressModel.WINDOW_MILLIS);
        assertEquals(0, model.ratePerSecond(), "nothing for a whole window is a rate of nothing");
        assertNull(model.eta(), "and the minutes it took before are no longer an answer");
        assertEquals("estimating…", ProgressModel.describe(model.eta()));
        assertFalse(model.describe().contains("left"), "no rate, no time left");

        for (int i = 0; i < 100; i++) {    // and it comes back
            tick(100);
            model.advance(1);
        }
        assertNotNull(model.eta(), "a download that moves again is estimated again");
    }

    @Test
    @DisplayName("every step of the wording starts where it should, on both sides of each boundary")
    void wordingBoundaries() {
        assertEquals("less than a minute", ProgressModel.describe(Duration.ofSeconds(44)));
        assertEquals("about a minute", ProgressModel.describe(Duration.ofSeconds(45)));
        assertEquals("about a minute", ProgressModel.describe(Duration.ofSeconds(89)));
        assertEquals("about 2 minutes", ProgressModel.describe(Duration.ofSeconds(90)));
        assertEquals("about 59 minutes", ProgressModel.describe(Duration.ofSeconds(3569)));
        assertEquals("about an hour", ProgressModel.describe(Duration.ofSeconds(3570)),
                "sixty minutes, rounded, is an hour and not \"about 60 minutes\"");
        assertEquals("about an hour", ProgressModel.describe(Duration.ofSeconds(4499)));
        assertEquals("about an hour and a half", ProgressModel.describe(Duration.ofSeconds(4500)),
                "an hour and a quarter rounds up to the half");
        assertEquals("about an hour and a half", ProgressModel.describe(Duration.ofMinutes(80)),
                "integer division called this an hour");
        assertEquals("about an hour and a half", ProgressModel.describe(Duration.ofSeconds(6299)));
        assertEquals("about 2 hours", ProgressModel.describe(Duration.ofSeconds(6300)),
                "integer division called an hour and 45 minutes an hour and a half");
        assertEquals("about 2 hours", ProgressModel.describe(Duration.ofMinutes(110)));
        assertEquals("about 2 hours", ProgressModel.describe(Duration.ofSeconds(8099)), "not \"2.0\"");
        assertEquals("about 2.5 hours", ProgressModel.describe(Duration.ofSeconds(8100)));
        assertEquals("about 9.5 hours", ProgressModel.describe(Duration.ofSeconds(35_099)));
        assertEquals("about 10 hours", ProgressModel.describe(Duration.ofSeconds(35_100)), "not \"10.0\"");
        assertEquals("about 11 hours", ProgressModel.describe(Duration.ofSeconds(37_800)));
    }

    @Test
    @DisplayName("the steps are the same whichever language says them")
    void roughIsTheStep() {
        assertEquals(new ProgressModel.Rough(ProgressModel.Span.HOURS, 2.5), ProgressModel.rough(Duration.ofSeconds(8100)));
        assertEquals(new ProgressModel.Rough(ProgressModel.Span.MINUTES, 59), ProgressModel.rough(Duration.ofSeconds(3569)));
        assertEquals(ProgressModel.Span.AN_HOUR, ProgressModel.rough(Duration.ofSeconds(3570)).span());
        assertEquals(ProgressModel.Span.ESTIMATING, ProgressModel.rough(null).span());
    }
}
