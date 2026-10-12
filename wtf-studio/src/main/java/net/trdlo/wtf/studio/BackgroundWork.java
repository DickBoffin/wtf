package net.trdlo.wtf.studio;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Runs work that might throw and hands whatever it returns, or what it threw, to what comes after
 * – always, so a worker that dies partway through does not leave whatever {@code then} was going
 * to reset stuck as it was: the export button disabled over a count that never landed, or "fetch
 * as I pan" stuck on a view it never finished checking.
 * <p>
 * An {@link Error} is caught too, not only a {@link RuntimeException} – a stack overflow on a
 * detailed cache is not a reason to swallow the failure, the same reason {@code ProjectDerivation}
 * catches both for its own worker. Off the toolkit, so the rule can be tested without a window.
 */
public final class BackgroundWork {

    private BackgroundWork() {
    }

    /**
     * @param work what a background thread does, which may throw
     * @param then told the answer, or what {@code work} threw – never both, and always one
     */
    public static <T> void land(final @NotNull Supplier<T> work,
                                 final @NotNull BiConsumer<@Nullable T, @Nullable Throwable> then) {
        T result = null;
        Throwable problem = null;
        try {
            result = work.get();
        } catch (final RuntimeException | Error ex) {
            problem = ex;
        }
        then.accept(result, problem);
    }
}
