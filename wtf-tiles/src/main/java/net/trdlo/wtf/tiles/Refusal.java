package net.trdlo.wtf.tiles;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * One of WTF's checks said no: why, and the values that make it this refusal rather than another.
 * <p>
 * The engine has no language, by construction, so its message is English – the reason's pattern
 * filled with {@link Locale#ROOT}, which is what a command line prints. A window with a language
 * of its own says the {@link #reason()} from its bundle instead, over the same {@link #args()},
 * as it says the engine's progress phrases. A subclass of {@link IllegalArgumentException},
 * because that is what every check of WTF's has thrown and what its callers already catch.
 */
public final class Refusal extends IllegalArgumentException {

    /**
     * Why, and how it is said in English: a {@link MessageFormat} pattern over the arguments. The
     * reasons are enums, one per module, so a window can map every one of them and be told by the
     * compiler when one is added.
     */
    public interface Reason extends Serializable {
        @NotNull String english();
    }

    private final @NotNull Reason reason;
    private final @Nullable Object @NotNull [] args;

    public Refusal(final @NotNull Reason reason, final @Nullable Object... args) {
        this(null, reason, args);
    }

    public Refusal(final @Nullable Throwable cause, final @NotNull Reason reason, final @Nullable Object... args) {
        super(new MessageFormat(reason.english(), Locale.ROOT).format(args), cause);
        this.reason = reason;
        this.args = args.clone();
    }

    public @NotNull Reason reason() {
        return reason;
    }

    /** The values, in the order the pattern numbers them. */
    public @NotNull List<@Nullable Object> args() {
        return Arrays.asList(args.clone());
    }
}
