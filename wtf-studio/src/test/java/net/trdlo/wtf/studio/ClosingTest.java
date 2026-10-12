package net.trdlo.wtf.studio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Closing the window while step 5 is downloading or exporting: what is asked, and when the job stops;
 * and the view's download, stopped without a question on every close that goes through.
 */
class ClosingTest {

    /** Records what was asked and done, in order, and answers the questions as told. */
    private static final class Window {
        final List<String> events = new ArrayList<>();
        final boolean stopIt;
        final boolean discardIt;

        Window(final boolean stopIt, final boolean discardIt) {
            this.stopIt = stopIt;
            this.discardIt = discardIt;
        }

        boolean close(final boolean running) {
            return Closing.decide(running,
                    () -> {
                        events.add("asked to stop");
                        return stopIt;
                    },
                    () -> {
                        events.add("asked about changes");
                        return discardIt;
                    },
                    () -> events.add("stopped and waited"),
                    () -> events.add("view stopped"));
        }
    }

    @Test
    @DisplayName("nothing running: only the unsaved-changes question, and no stop")
    void nothingRunning() {
        final Window w = new Window(true, true);
        assertTrue(w.close(false));
        assertEquals(List.of("asked about changes", "view stopped"), w.events,
                "the view's download is stopped on every close that goes through, and not asked about");

        final Window kept = new Window(true, false);
        assertFalse(kept.close(false));
        assertEquals(List.of("asked about changes"), kept.events, "a Cancel leaves the view's download running");
    }

    @Test
    @DisplayName("a job running: asked about first, and a no leaves it running without the other question")
    void keepRunning() {
        final Window w = new Window(false, true);
        assertFalse(w.close(true));
        assertEquals(List.of("asked to stop"), w.events, "no second question, nothing stopped, the view's either");
    }

    @Test
    @DisplayName("stop and close: the job is stopped only once both questions are answered")
    void stopAndClose() {
        final Window w = new Window(true, true);
        assertTrue(w.close(true));
        assertEquals(List.of("asked to stop", "asked about changes", "stopped and waited", "view stopped"), w.events);

        final Window changedMind = new Window(true, false);
        assertFalse(changedMind.close(true));
        assertEquals(List.of("asked to stop", "asked about changes"), changedMind.events,
                "a Cancel to the unsaved question leaves the job running as it was");
    }
}
