package net.trdlo.wtf.studio;

import net.trdlo.wtf.tiles.TileKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The view's count of failed tiles is the count of the view on screen, whatever order a
 * check of the cache, a pan and the check's landing come in – and no check is left unrun, nor run
 * again without end.
 */
class FailedOnScreenTest {

    private static final List<TileKey> WEST = List.of(tile(1, 1), tile(1, 2), tile(1, 3));
    private static final List<TileKey> EAST = List.of(tile(9, 1));
    private static final List<TileKey> NORTH = List.of(tile(5, 0), tile(6, 0));

    private static TileKey tile(final int x, final int y) {
        return new TileKey("osm", "standard", 12, x, y);
    }

    @Test
    @DisplayName("a count that lands for a view that has gone is dropped, and the check runs again for the one on screen")
    void staleLandingIsDroppedAndAskedAgain() {
        final FailedOnScreen failed = new FailedOnScreen();
        final FailedOnScreen.Check west = failed.ask(WEST);
        assertNotNull(west);

        // A pan while the west is being checked: its question waits for the check out
        assertNull(failed.ask(EAST), "one check at a time");

        // The west's answer lands after the pan
        final FailedOnScreen.Check east = failed.landed(west, 3);
        assertEquals(0, failed.count(), "three failed tiles in the west say nothing of the east");
        assertNotNull(east, "the view on screen is asked about when the check out lands");
        assertEquals(EAST, east.suspects());

        assertNull(failed.landed(east, 1), "a landing asks nothing, so nothing runs again");
        assertEquals(1, failed.count());
    }

    @Test
    @DisplayName("two pans during one check run one more check, for the view the second one left")
    void twoPansRunOneMoreCheck() {
        final FailedOnScreen failed = new FailedOnScreen();
        final FailedOnScreen.Check west = failed.ask(WEST);
        assertNull(failed.ask(EAST));
        assertNull(failed.ask(NORTH));

        final FailedOnScreen.Check north = failed.landed(west, 3);
        assertNotNull(north);
        assertEquals(NORTH, north.suspects(), "the east is gone too; only the last view is asked about");
        assertNull(failed.landed(north, 2));
        assertEquals(2, failed.count());
    }

    @Test
    @DisplayName("a pan away and back during a check still asks again: the cache may have changed meanwhile")
    void backToTheSameViewAsksAgain() {
        final FailedOnScreen failed = new FailedOnScreen();
        final FailedOnScreen.Check first = failed.ask(WEST);
        assertNull(failed.ask(EAST));
        assertNull(failed.ask(WEST), "back where the check started, which a download may have filled in since");

        final FailedOnScreen.Check again = failed.landed(first, 3);
        assertEquals(0, failed.count(), "the first answer is to an older question");
        assertNotNull(again);
        assertEquals(WEST, again.suspects());
        assertNull(failed.landed(again, 0));
        assertEquals(0, failed.count(), "the download got them all");
    }

    @Test
    @DisplayName("a view with nothing failed is 0 at once, and a check still out does not bring the old count back")
    void nothingFailedWinsOverACheckOut() {
        final FailedOnScreen failed = new FailedOnScreen();
        assertNull(failed.landed(failed.ask(WEST), 3));
        assertEquals(3, failed.count());

        // A download lands, and the view is checked again
        final FailedOnScreen.Check first = failed.ask(WEST);
        assertNotNull(first);

        assertNull(failed.ask(List.of()), "nothing to check");
        assertEquals(0, failed.count(), "said at once, not when the check out lands");

        assertNull(failed.landed(first, 3), "nothing waits: there is no question to ask the cache");
        assertEquals(0, failed.count(), "the check out answered a view that has gone");
    }

    @Test
    @DisplayName("a check that could not read the cache leaves the count, and a new question after a landing starts at once")
    void failureKeepsTheCountAndTheNextQuestionStarts() {
        final FailedOnScreen failed = new FailedOnScreen();
        assertNull(failed.landed(failed.ask(WEST), 3));
        assertEquals(3, failed.count());

        final FailedOnScreen.Check unread = failed.ask(WEST);
        assertNotNull(unread, "nothing is out, so the question is asked now");
        assertNull(failed.landed(unread, null));
        assertEquals(3, failed.count(), "not knowing is not the same as none failed");

        assertNotNull(failed.ask(EAST), "a failed check does not leave the next one waiting forever");
    }
}
