package net.trdlo.wtf.studio.steps;

import javafx.beans.property.DoubleProperty;
import javafx.util.StringConverter;
import net.trdlo.wtf.studio.I18n;
import net.trdlo.wtf.studio.Units;
import org.jetbrains.annotations.NotNull;

/**
 * The text of the roll-width combo and what of it reaches the model – {@link LengthText}'s rule,
 * in a combo, so it can be tested without the toolkit.
 * <p>
 * An editable combo commits its editor's text whenever it loses the focus, whether anybody typed
 * or not, and the text is rounded: under inches a 914 mm roll is "36", which read back is
 * 914.4 mm, and under millimeters a 914.4 mm roll from a file is "914". So the text the combo
 * shows for the model's width stands for that width, and only a text that differs from it is
 * parsed.
 */
final class RollWidthText extends StringConverter<Double> {

    private final DoubleProperty mm;
    private final Units units;

    RollWidthText(final @NotNull DoubleProperty mm, final @NotNull Units units) {
        this.mm = mm;
        this.units = units;
    }

    /** A roll's width as the combo shows it: whole millimeters, or inches to a tenth. */
    static @NotNull String text(final @NotNull Units units, final double widthMm) {
        return units == Units.INCHES ? I18n.numberUpTo(units.fromMm(widthMm), 1) : I18n.number(widthMm, 0);
    }

    @Override
    public String toString(final Double widthMm) {
        return widthMm == null ? "" : text(units, widthMm);
    }

    /**
     * The model's width for the text it is shown as, what was typed for any other, and the model's
     * width again for a text that is not a number.
     */
    @Override
    public Double fromString(final String s) {
        final double current = mm.get();
        if (s == null || s.strip().equals(text(units, current))) {
            return current;
        }
        final Double v = I18n.parseNumber(s);
        return v == null ? current : units.toMm(v);
    }
}
