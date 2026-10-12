package net.trdlo.wtf.studio.steps;

import javafx.beans.property.IntegerProperty;
import javafx.util.StringConverter;
import net.trdlo.wtf.studio.I18n;
import org.jetbrains.annotations.NotNull;

/**
 * The text of the DPI combo and what of it reaches the model, so it can be tested without the
 * toolkit. A whole number in the display locale; anything else, or a DPI outside what a printer
 * makes, gives back the model's own.
 */
final class DpiText extends StringConverter<Integer> {

    /** What the list offers. 72 is a poster read from across a room, 600 a print looked at with a loupe. */
    static final java.util.List<Integer> COMMON = java.util.List.of(72, 100, 150, 200, 300, 600);

    static final int MIN = 10;
    static final int MAX = 2400;

    private final IntegerProperty dpi;

    DpiText(final @NotNull IntegerProperty dpi) {
        this.dpi = dpi;
    }

    @Override
    public String toString(final Integer value) {
        return value == null ? "" : I18n.integer(value);
    }

    @Override
    public Integer fromString(final String s) {
        final Double v = I18n.parseNumber(s);
        if (v == null || v.isNaN() || Math.round(v) < MIN || Math.round(v) > MAX) {
            return dpi.get();
        }
        return (int) Math.round(v);
    }
}
