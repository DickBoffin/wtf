package net.trdlo.wtf.studio.steps;

import javafx.beans.property.IntegerProperty;
import javafx.util.StringConverter;
import net.trdlo.wtf.studio.I18n;
import org.jetbrains.annotations.NotNull;

/**
 * The text of the scale combo and what of it reaches the model, so it can be tested without the
 * toolkit. The model holds the denominator, 50000 for 1:50 000.
 * <p>
 * Two numbers in the display locale with a colon between them, spaces allowed round it, come to the
 * whole denominator nearest their quotient: {@code 3,1415:100000} is 1:31 832. One number alone is
 * the denominator. Anything else – a scale larger than 1:1, a zero, an exponent the locale does not
 * write – gives back the model's own.
 */
final class ScaleText extends StringConverter<Integer> {

    /** What the list offers: a town plan, the hiking maps, a region, a country. */
    static final java.util.List<Integer> COMMON = java.util.List.of(5_000, 10_000, 20_000, 25_000, 50_000,
            100_000, 200_000, 250_000, 500_000, 1_000_000);

    /** The whole Earth forty millimeters across; anything smaller is not a map anybody prints. */
    static final int MAX = 1_000_000_000;

    private final IntegerProperty scale;

    ScaleText(final @NotNull IntegerProperty scale) {
        this.scale = scale;
    }

    @Override
    public String toString(final Integer value) {
        return value == null ? "" : I18n.format("medium.scale.ratio", I18n.integer(value));
    }

    @Override
    public Integer fromString(final String s) {
        if (s == null) {
            return scale.get();
        }
        final String[] sides = s.split(":", -1);
        final Double paper = sides.length == 2 ? I18n.parseNumber(sides[0].strip()) : Double.valueOf(1);
        final Double ground = I18n.parseNumber(sides[sides.length - 1].strip());
        if (sides.length > 2 || paper == null || ground == null || !(paper > 0) || !(ground > 0)) {
            return scale.get();
        }
        final double n = ground / paper;
        return n >= 1 && n <= MAX ? (int) Math.round(n) : scale.get();
    }
}
