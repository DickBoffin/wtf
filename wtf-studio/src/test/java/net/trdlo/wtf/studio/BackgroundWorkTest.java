package net.trdlo.wtf.studio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A count that throws must still land, so nothing it was going to reset – the export
 * button, the view's download – is left stuck as it was; and an {@link Error} is caught as well as a
 * {@link RuntimeException}, the same as {@code ProjectDerivation}'s own worker.
 */
class BackgroundWorkTest {

    @Test
    @DisplayName("work that returns lands its answer, with nothing said to have gone wrong")
    void workThatReturnsLandsItsAnswer() {
        final AtomicReference<String> answer = new AtomicReference<>();
        final AtomicReference<Throwable> problem = new AtomicReference<>();
        BackgroundWork.land(() -> "the answer", (r, ex) -> {
            answer.set(r);
            problem.set(ex);
        });
        assertEquals("the answer", answer.get());
        assertNull(problem.get());
    }

    @Test
    @DisplayName("a RuntimeException still lands, rather than leaving what follows never called")
    void aRuntimeExceptionStillLands() {
        final AtomicReference<Throwable> landed = new AtomicReference<>();
        final RuntimeException thrown = new IllegalStateException("the disk is full");
        BackgroundWork.land(() -> {
            throw thrown;
        }, (r, ex) -> landed.set(ex));
        assertSame(thrown, landed.get());
    }

    @Test
    @DisplayName("an Error lands too, not only a RuntimeException – the button must not stay disabled over a stack overflow")
    void anErrorLandsToo() {
        final AtomicReference<Throwable> landed = new AtomicReference<>();
        final AtomicReference<Object> answer = new AtomicReference<>("never set");
        final Error thrown = new StackOverflowError("a detailed cache");
        assertDoesNotThrow(() -> BackgroundWork.land(() -> {
            throw thrown;
        }, (r, ex) -> {
            answer.set(r);
            landed.set(ex);
        }));
        assertSame(thrown, landed.get());
        assertNull(answer.get(), "nothing to show for work that never finished");
    }
}
